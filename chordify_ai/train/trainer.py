"""ChordNet training loop with WCSR-based model selection (plan 03 §2.3)."""
from __future__ import annotations

import copy
import json
import math
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

from chordify_core import acoustic, decode, vocab

from ..eval import chords as chord_eval
from ..models.chordnet import ChordNet, ChordNetConfig, count_parameters
from ..models.losses import chordnet_loss, quality_class_weights
from .dataset import CropDataset, collate
from .sources import EvalTrack


@dataclass
class TrainConfig:
    vocabulary: str = "majmin"
    model: ChordNetConfig = field(default_factory=ChordNetConfig)
    crop: int = 512
    batch_size: int = 16
    lr: float = 1e-3
    weight_decay: float = 0.01
    warmup_steps: int = 200
    epochs: int = 30
    items_per_epoch: int = 2000
    patience: int = 6
    ema_decay: float = 0.999
    seed: int = 0
    threads: int = 0
    device: str = "auto"  # "auto" (cuda, then Apple's mps, then cpu), "cuda", "mps" or "cpu"

    def to_dict(self) -> dict:
        return {**asdict(self), "model": self.model.to_dict()}


def resolve_device(name: str = "auto") -> torch.device:
    if name != "auto":
        return torch.device(name)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def torch_runner(model: ChordNet):
    device = next(model.parameters()).device

    def run(x: np.ndarray) -> dict[str, np.ndarray]:
        with torch.no_grad():
            out = model(torch.from_numpy(x).to(device))
        return {k: v.cpu().numpy() for k, v in out.items()}
    return run


def predict_segments(model: ChordNet, track: EvalTrack, vocabulary: vocab.Vocabulary, prior: np.ndarray | None,
                     decoder: dict) -> list[tuple[float, float, str]]:
    model.eval()
    logits = acoustic.windowed(torch_runner(model), track.example.features)
    posteriors = acoustic.softmax(logits["chord"])
    boundary = acoustic.sigmoid(logits["boundary"])
    segments = decode.decode(posteriors, track.frame_rate, beats=np.asarray(track.beats) if track.beats else None,
                             change_prob=boundary, prior=prior, frame_offset=track.frame_offset,
                             duration=track.reference[-1][1] if track.reference else None, **decoder)
    return [(s.start, s.end, vocabulary.decode(s.index)) for s in segments]


def evaluate(model: ChordNet, tracks: list[EvalTrack], vocabulary: vocab.Vocabulary, prior: np.ndarray | None,
             decoder: dict | None = None) -> dict[str, float]:
    decoder = decoder or {"alpha": 0.3}
    rows = [chord_eval.evaluate_track(t.reference, predict_segments(model, t, vocabulary, prior, decoder))
            for t in tracks]
    return chord_eval.aggregate(rows)


def class_prior(examples, vocabulary: vocab.Vocabulary) -> np.ndarray:
    counts = np.ones(vocabulary.size)
    for ex in examples:
        valid = ex.targets.chord[ex.targets.chord >= 0]
        counts += np.bincount(valid, minlength=vocabulary.size)
    # transposition makes roots uniform in training, so average each quality over its 12 roots
    q = counts[:-1].reshape(-1, 12).mean(axis=1, keepdims=True).repeat(12, axis=1).reshape(-1)
    prior = np.concatenate([q, counts[-1:]])
    return prior / prior.sum()


def train(config: TrainConfig, train_tracks: list[EvalTrack], val_tracks: list[EvalTrack], out_dir: str | Path,
          log=print) -> dict:
    torch.manual_seed(config.seed)
    if config.threads:
        torch.set_num_threads(config.threads)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "best.pt").unlink(missing_ok=True)  # never export a checkpoint left by an earlier run
    vocabulary = vocab.VOCABULARIES[config.vocabulary]
    config.model.n_classes = vocabulary.size
    device = resolve_device(config.device)
    model = ChordNet(config.model).to(device)
    ema = copy.deepcopy(model).eval()
    log(f"ChordNet {config.model.encoder}: {count_parameters(model) / 1e6:.2f} M parameters, "
        f"{len(train_tracks)} train / {len(val_tracks)} validation tracks, on {device}")

    examples = [t.example for t in train_tracks]
    counts = torch.zeros(vocabulary.size)
    for ex in examples:
        valid = ex.targets.chord[ex.targets.chord >= 0]
        counts += torch.bincount(torch.from_numpy(valid), minlength=vocabulary.size).float()
    weights = quality_class_weights(counts, len(vocabulary.qualities)).to(device)
    prior = class_prior(examples, vocabulary)

    dataset = CropDataset(examples, vocabulary, crop=config.crop, items_per_epoch=config.items_per_epoch,
                          seed=config.seed)
    loader = DataLoader(dataset, batch_size=config.batch_size, collate_fn=collate, num_workers=0)
    optimiser = torch.optim.AdamW(model.parameters(), lr=config.lr, weight_decay=config.weight_decay)
    total_steps = config.epochs * math.ceil(config.items_per_epoch / config.batch_size)

    def lr_at(step: int) -> float:
        if step < config.warmup_steps:
            return (step + 1) / config.warmup_steps
        progress = (step - config.warmup_steps) / max(1, total_steps - config.warmup_steps)
        return 0.5 * (1 + math.cos(math.pi * min(1.0, progress)))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimiser, lr_at)
    history: list[dict] = []
    try:
        epochs_run = _run_epochs(config, model, ema, loader, optimiser, scheduler, weights, val_tracks, vocabulary,
                                 prior, device, out, history, log)
    except KeyboardInterrupt:  # Ctrl-C: keep the best checkpoint so far and export it
        log("stopped by the user; keeping the best checkpoint so far")
        epochs_run = len(history)
    best_score = max((row["validation"].get("majmin", -1.0) for row in history), default=-1.0)
    (out / "history.json").write_text(json.dumps(history, indent=1))
    return {"best_score": best_score, "history": history, "checkpoint": str(out / "best.pt"), "prior": prior,
            "epochs": epochs_run}


def _run_epochs(config, model, ema, loader, optimiser, scheduler, weights, val_tracks, vocabulary, prior, device,
                out: Path, history: list, log) -> int:
    best_score, stale, step = -1.0, 0, 0
    for epoch in range(config.epochs):
        model.train()
        started, losses = time.time(), []
        for batch in loader:
            batch = {name: value.to(device) for name, value in batch.items()}
            outputs = model(batch["features"], key_padding_mask=~batch["frame_mask"])
            loss, parts = chordnet_loss(outputs, batch, class_weights=weights)
            optimiser.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            scheduler.step()
            step += 1
            with torch.no_grad():
                decay = min(config.ema_decay, (1 + step) / (10 + step))
                for p_ema, p in zip(ema.state_dict().values(), model.state_dict().values()):
                    if p.dtype.is_floating_point:
                        p_ema.mul_(decay).add_(p.detach(), alpha=1 - decay)
                    else:
                        p_ema.copy_(p)
            losses.append(parts)
        scores = evaluate(ema, val_tracks, vocabulary, prior) if val_tracks else {}
        mean = {k: float(np.mean([p[k] for p in losses])) for k in losses[0]}
        row = {"epoch": epoch + 1, "seconds": round(time.time() - started, 1), "loss": mean, "validation": scores}
        history.append(row)
        score = scores.get("majmin", -mean["chord"])
        log(f"epoch {epoch + 1:3d}  chord loss {mean['chord']:.3f}  boundary {mean['boundary']:.3f}  "
            f"val majmin {scores.get('majmin', float('nan')) * 100:.1f}  seg {scores.get('seg', float('nan')) * 100:.1f}"
            f"  ({row['seconds']} s)")
        (out / "history.json").write_text(json.dumps(history, indent=1))
        if score > best_score:
            best_score, stale = score, 0
            state = {name: value.detach().cpu().clone() for name, value in ema.state_dict().items()}
            torch.save({"config": config.to_dict(), "state_dict": state, "prior": prior.tolist(),
                        "validation": scores, "epoch": epoch + 1}, out / "best.pt")
        else:
            stale += 1
            if stale >= config.patience:
                log("early stopping")
                break
    return len(history)


def load_checkpoint(path: str | Path) -> tuple[ChordNet, dict]:
    data = torch.load(path, map_location="cpu", weights_only=False)
    config = ChordNetConfig(**data["config"]["model"])
    model = ChordNet(config)
    model.load_state_dict(data["state_dict"])
    return model.eval(), data
