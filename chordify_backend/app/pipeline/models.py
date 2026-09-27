"""Models loaded once per process and shared by all jobs."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

from chordify_core import features
from chordify_core.acoustic import OnnxChordModel, TemplateChordModel, load_acoustic_model
from chordify_core.lm import NgramProgressionModel

from ..config import Settings
from .beats import TRACKER_ID


def resolve_chord_model(reference: str, default_bundle: str | Path) -> str:
    """``auto`` means the shipped ChordNet bundle if its features can be extracted on this
    machine (NNLS needs the Vamp plugin), otherwise the training-free templates."""
    if reference != "auto":
        return reference
    bundle = Path(default_bundle) / "bundle.json"
    if bundle.exists():
        kind = json.loads(bundle.read_text())["features"]["kind"]
        if kind != "nnls_bothchroma" or features.nnls_available():
            return str(default_bundle)
    return "templates"


@dataclass
class Models:
    acoustic: OnnxChordModel | TemplateChordModel
    lm: NgramProgressionModel
    lm_id: str
    beats_id: str = TRACKER_ID

    @property
    def ids(self) -> dict[str, str]:
        return {"chord": self.acoustic.id, "lm": self.lm_id, "beats": self.beats_id,
                "features": self.acoustic.feature_spec.kind, "vocabulary": self.acoustic.vocabulary.name}

    @property
    def fingerprint(self) -> str:
        """Part of the analysis cache key: a new model or feature revision means re-analysis, never stale results."""
        key = self.ids | {"feature_revision": self.acoustic.feature_spec.revision}
        return hashlib.sha256(json.dumps(key, sort_keys=True).encode()).hexdigest()[:16]

    @classmethod
    def load(cls, settings: Settings) -> "Models":
        acoustic = load_acoustic_model(resolve_chord_model(settings.chord_model, settings.default_chord_bundle))
        lm_dir = Path(settings.lm_model)
        bundle = json.loads((lm_dir / "bundle.json").read_text())
        lm = NgramProgressionModel.load(lm_dir / bundle["files"]["model"])
        return cls(acoustic=acoustic, lm=lm, lm_id=bundle["id"])
