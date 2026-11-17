"""The Android engine's golden files (Mobile/engine/src/test/resources/goldens) match what
chordify_core does now. When this fails, regenerate them and commit the result:

    python -m chordify_ai.export.mobile_goldens
"""
import json

import pytest

from chordify_ai.export import mobile_goldens


def _same(fresh, committed, path="$"):
    """Equal, except that floats may differ in their last bits between numpy builds."""
    if isinstance(fresh, float) or isinstance(committed, float):
        assert fresh == pytest.approx(committed, rel=1e-9, abs=1e-12), path
    elif isinstance(fresh, dict):
        assert isinstance(committed, dict) and fresh.keys() == committed.keys(), path
        for key in fresh:
            _same(fresh[key], committed[key], f"{path}.{key}")
    elif isinstance(fresh, list):
        assert isinstance(committed, list) and len(fresh) == len(committed), path
        for i, (a, b) in enumerate(zip(fresh, committed)):
            _same(a, b, f"{path}[{i}]")
    else:
        assert fresh == committed, path


@pytest.mark.parametrize("name", sorted(mobile_goldens.FILES))
def test_committed_goldens_match_chordify_core(tmp_path, name):
    mobile_goldens.main(["--out", str(tmp_path), "--only", name])
    fresh = json.loads((tmp_path / f"{name}.json").read_text(encoding="utf-8"))
    committed = json.loads((mobile_goldens.DEFAULT_OUT / f"{name}.json").read_text(encoding="utf-8"))
    _same(fresh, committed)
