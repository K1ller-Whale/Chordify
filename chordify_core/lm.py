"""Progression model runtime: what chord comes next?

Baseline from the plan (02 §2.5, 03 §5.4): a key-relative n-gram trained on many
songs, mixed with a *song cache* that looks for the current context earlier in the
same song. Songs repeat their progressions, so the cache is the strongest signal;
the corpus n-gram covers the start of a song and new material.

Tokens are key-relative: ``"<semitones above the tonic>:<maj|min>"``, e.g. V = "7:maj",
vi = "9:min". The same model also supplies the decoder's chord-change matrix.
"""
from __future__ import annotations

import gzip
import json
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import numpy as np

from . import theory, vocab
from .theory import Key

TOKENS = tuple(f"{d}:{q}" for q in ("maj", "min") for d in range(12))


def token(label: str, key: Key) -> str | None:
    """Key-relative token of a chord label; None for N/X. Sus/power/aug count as major,
    dim as minor (the third decides)."""
    chord = vocab.parse(label)
    if not chord.is_chord:
        return None
    quality = "min" if chord.third == "min" else "maj"
    return f"{(chord.root - key.tonic) % 12}:{quality}"


def token_label(tok: str, key: Key) -> str:
    """Absolute Harte label for a token in a key: '9:min' in D -> 'B:min'."""
    degree, quality = tok.split(":")
    return f"{key.spell((key.tonic + int(degree)) % 12)}:{quality}"


def dedupe(seq: Sequence) -> list:
    return [x for i, x in enumerate(seq) if i == 0 or seq[i - 1] != x]


@dataclass
class Candidate:
    token: str
    p: float
    cache_count: int


class NgramProgressionModel:
    def __init__(self, order: int = 4, min_count: int = 3, cache_weight: float = 0.75, max_context: int = 6,
                 counts: list[dict[tuple[str, ...], Counter]] | None = None):
        self.order = order
        self.min_count = min_count
        self.cache_weight = cache_weight
        self.max_context = max_context
        self.counts = counts or [dict() for _ in range(order)]

    # -- training / persistence ------------------------------------------------
    @classmethod
    def fit(cls, sequences: Sequence[Sequence[str]], order: int = 4, **kwargs) -> "NgramProgressionModel":
        model = cls(order=order, **kwargs)
        for seq in sequences:
            seq = dedupe(list(seq))
            for t in range(len(seq)):
                for k in range(order):
                    if t - k < 0:
                        break
                    context = tuple(seq[t - k:t])
                    model.counts[k].setdefault(context, Counter())[seq[t]] += 1
        return model

    def to_json(self) -> dict:
        return {"type": "ngram-cache", "order": self.order, "min_count": self.min_count,
                "cache_weight": self.cache_weight, "max_context": self.max_context,
                "counts": [{" ".join(ctx): dict(c) for ctx, c in level.items()} for level in self.counts]}

    @classmethod
    def from_json(cls, data: dict) -> "NgramProgressionModel":
        counts = [{tuple(ctx.split()) if ctx else (): Counter(c) for ctx, c in level.items()}
                  for level in data["counts"]]
        return cls(data["order"], data["min_count"], data["cache_weight"], data["max_context"], counts)

    def save(self, path: str | Path) -> None:
        with gzip.open(path, "wt", encoding="utf-8") as handle:
            json.dump(self.to_json(), handle, separators=(",", ":"))

    @classmethod
    def load(cls, path: str | Path) -> "NgramProgressionModel":
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return cls.from_json(json.load(handle))

    # -- distributions -----------------------------------------------------------
    def corpus_distribution(self, history: Sequence[str]) -> dict[str, float]:
        """Longest context seen at least ``min_count`` times, smoothed over all tokens."""
        history = dedupe(list(history))
        chosen: Counter | None = None
        for k in range(min(self.order - 1, len(history)), -1, -1):
            context = tuple(history[len(history) - k:]) if k else ()
            counts = self.counts[k].get(context)
            if counts and sum(counts.values()) >= self.min_count:
                chosen = counts
                break
        dist = {t: 1e-3 for t in TOKENS}
        if chosen:
            total = sum(chosen.values())
            for t, c in chosen.items():
                dist[t] = dist.get(t, 1e-3) + c / total
        if history:
            dist[history[-1]] = 0.0  # a "change" is never to the same chord
        norm = sum(dist.values())
        return {t: p / norm for t, p in dist.items()}

    def cache_counts(self, history: Sequence[str]) -> tuple[Counter, int]:
        """What followed the longest matching context earlier in this song."""
        history = dedupe(list(history))
        n = len(history)
        for k in range(min(self.max_context, n - 1), 0, -1):
            context = history[n - k:]
            followers: Counter = Counter()
            for j in range(k, n):
                if history[j - k:j] == context and j < n:
                    followers[history[j]] += 1
            followers.pop(history[-1], None)
            if followers:
                return followers, k
        return Counter(), 0

    def distribution(self, history: Sequence[str]) -> tuple[dict[str, float], Counter]:
        corpus = self.corpus_distribution(history)
        cache, _ = self.cache_counts(history)
        if not cache:
            return corpus, cache
        total = sum(cache.values())
        mixed = {t: (1 - self.cache_weight) * p for t, p in corpus.items()}
        for t, c in cache.items():
            mixed[t] = mixed.get(t, 0.0) + self.cache_weight * c / total
        return mixed, cache

    def top(self, history: Sequence[str], k: int = 3) -> list[Candidate]:
        dist, cache = self.distribution(history)
        ranked = sorted(dist.items(), key=lambda kv: -kv[1])[:k]
        return [Candidate(t, p, cache.get(t, 0)) for t, p in ranked]

    # -- decoder prior -----------------------------------------------------------
    def change_matrix(self, vocabulary: vocab.Vocabulary, key: Key, same_token_share: float = 0.05) -> np.ndarray:
        """(V, V) chord-change probabilities for the decoder: bigram corpus statistics in
        ``key``, shared equally among classes with the same token; zero diagonal.

        Tokens are key-relative root + major/minor family, so C -> C7 or Cmaj7 -> C6 is no
        change for the n-gram. In larger vocabularies those same-token changes get
        ``same_token_share`` of each row (5 % of Billboard's chord changes keep the root and
        family and only change the chord type)."""
        labels = vocabulary.labels
        tokens = [token(label, key) if label != vocab.NO_CHORD else None for label in labels]
        per_token = Counter(t for t in tokens if t)
        size = len(labels)
        matrix = np.zeros((size, size))
        for i, ti in enumerate(tokens):
            if ti is None:
                matrix[i] = 1.0
            else:
                dist = self.corpus_distribution([ti])
                for j, tj in enumerate(tokens):
                    matrix[i, j] = 0.02 if tj is None else dist.get(tj, 1e-3) / per_token[tj]
            matrix[i, i] = 0.0
            matrix[i] /= matrix[i].sum()
            same = [j for j, tj in enumerate(tokens) if ti is not None and tj == ti and j != i]
            if same:
                others = [j for j in range(size) if j != i and j not in same]
                matrix[i, others] *= (1 - same_token_share) / matrix[i, others].sum()
                matrix[i, same] = same_token_share / len(same)
        return matrix


# ---------------------------------------------------------------------------
# High-level prediction with explanations
# ---------------------------------------------------------------------------

@dataclass
class Prediction:
    label: str
    display: str
    roman: str | None
    p: float
    reason: str
    cache_count: int = 0
    expected_beats: float | None = None

    def to_dict(self) -> dict:
        return {k: v for k, v in self.__dict__.items() if v is not None}


def predict_next(model: NgramProgressionModel, history: Sequence[str], key: Key, k: int = 3,
                 history_beats: Sequence[float] | None = None) -> tuple[list[Prediction], list[dict]]:
    """Top-k next chords after ``history`` (absolute Harte labels, chord changes in order).

    Returns (predictions, corpus-only prior as [{"roman", "p"}]) so a UI can show how
    much the song's own history changed the answer. When a predicted chord already
    occurred in the song, its full label (e.g. Bm7 rather than Bm) is used, and
    ``expected_beats`` is its median length so far when ``history_beats`` is given.
    """
    history = [h for h in history if vocab.parse(h).is_chord]
    tokens = [token(h, key) for h in history]
    seen: dict[str, str] = {}
    lengths: dict[str, list[float]] = {}
    for i, (label, tok) in enumerate(zip(history, tokens)):
        seen.setdefault(tok, label)
        if history_beats is not None and history_beats[i] > 0:
            lengths.setdefault(tok, []).append(float(history_beats[i]))
    candidates = model.top(tokens, k)
    last = history[-1] if history else None
    predictions = []
    for cand in candidates:
        label = seen.get(cand.token) or token_label(cand.token, key)
        reasons = []
        if cand.cache_count:
            reasons.append(f"followed this context {cand.cache_count}× earlier in the song")
        if last:
            why = theory.transition_reason(last, label, key)
            if why:
                reasons.append(why)
        if not reasons:
            reasons.append("common continuation in the training corpus")
        beats = float(np.median(lengths[cand.token])) if cand.token in lengths else None
        predictions.append(Prediction(label, vocab.display_name(label, flats=key.flats), theory.roman(label, key),
                                      round(cand.p, 4), "; ".join(reasons), cand.cache_count, beats))
    corpus = model.corpus_distribution(tokens)
    prior = [{"roman": theory.roman(token_label(t, key), key), "p": round(p, 4)}
             for t, p in sorted(corpus.items(), key=lambda kv: -kv[1])[:k]]
    return predictions, prior
