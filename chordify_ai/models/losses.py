"""Multi-task loss for ChordNet (plan 03 §2.2)."""
from __future__ import annotations

import torch
import torch.nn.functional as F

from .targets import IGNORE

DEFAULT_WEIGHTS = {"chord": 1.0, "root": 0.5, "bass": 0.5, "tones": 0.5, "key_tonic": 0.3, "key_mode": 0.2,
                   "boundary": 0.5}


def _ce(logits: torch.Tensor, target: torch.Tensor, **kw) -> torch.Tensor:
    if (target != IGNORE).sum() == 0:
        return logits.sum() * 0.0
    return F.cross_entropy(logits.reshape(-1, logits.shape[-1]), target.reshape(-1), ignore_index=IGNORE, **kw)


def chordnet_loss(outputs: dict[str, torch.Tensor], batch: dict[str, torch.Tensor],
                  weights: dict[str, float] | None = None, label_smoothing: float = 0.1,
                  class_weights: torch.Tensor | None = None, boundary_pos_weight: float = 10.0
                  ) -> tuple[torch.Tensor, dict[str, float]]:
    weights = {**DEFAULT_WEIGHTS, **(weights or {})}
    parts = {
        "chord": _ce(outputs["chord"], batch["chord"], label_smoothing=label_smoothing, weight=class_weights),
        "root": _ce(outputs["root"], batch["root"]),
        "bass": _ce(outputs["bass"], batch["bass"]),
        "key_tonic": _ce(outputs["key_tonic"], batch["key_tonic"]),
        "key_mode": _ce(outputs["key_mode"], batch["key_mode"]),
    }
    tone_mask = batch["tones_mask"].unsqueeze(-1).float()
    tone_loss = F.binary_cross_entropy_with_logits(outputs["tones"], batch["tones"], reduction="none")
    parts["tones"] = (tone_loss * tone_mask).sum() / (tone_mask.sum() * 12).clamp(min=1.0)
    frame_mask = batch["frame_mask"].float()
    boundary = F.binary_cross_entropy_with_logits(
        outputs["boundary"], batch["boundary"], reduction="none",
        pos_weight=torch.tensor(boundary_pos_weight, device=frame_mask.device))
    parts["boundary"] = (boundary * frame_mask).sum() / frame_mask.sum().clamp(min=1.0)
    total = sum(weights[k] * v for k, v in parts.items())
    return total, {k: float(v.detach()) for k, v in parts.items()}


def quality_class_weights(counts: torch.Tensor, n_qualities: int, power: float = 0.5) -> torch.Tensor:
    """1/frequency**power per chord quality (roots are balanced by transposition); N keeps 1.
    ``counts`` may be raw frame counts or frequencies; power 0 turns weighting off."""
    per_quality = counts[:-1].reshape(n_qualities, 12).sum(dim=1).float()
    w = per_quality.clamp(min=1e-6 * float(per_quality.sum())) ** -power
    w = w / w.mean()
    return torch.cat([w.repeat_interleave(12), torch.ones(1)])
