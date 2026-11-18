"""The Android engine's golden files (Mobile/engine/src/test/resources/goldens) match what
chordify_core does now. When this fails, regenerate them and commit the result:

    python -m chordify_ai.export.mobile_goldens
    tools/mobile_goldens.sh        # features, beats and acoustic, with NNLS Chroma and the pinned reference
"""
import pytest

from chordify_ai.export import mobile_goldens

# (rel, abs) per file. NNLS Chroma is built with -ffast-math, so two compilers or two numpy
# builds agree on its features only to about 1e-6; everything else is exact arithmetic.
TOLERANCE = {"features": (1e-4, 1e-5), "beats": (1e-4, 1e-5), "acoustic": (1e-4, 1e-5)}


def _same(fresh, committed, rel, abs_, path="$"):
    """Equal, except that floats may differ in their last bits between builds."""
    if isinstance(fresh, float) or isinstance(committed, float):
        assert fresh == pytest.approx(committed, rel=rel, abs=abs_), path
    elif isinstance(fresh, dict):
        assert isinstance(committed, dict) and fresh.keys() == committed.keys(), path
        for key in fresh:
            _same(fresh[key], committed[key], rel, abs_, f"{path}.{key}")
    elif isinstance(fresh, list):
        assert isinstance(committed, list) and len(fresh) == len(committed), path
        for i, (a, b) in enumerate(zip(fresh, committed)):
            _same(a, b, rel, abs_, f"{path}[{i}]")
    else:
        assert fresh == committed, path


@pytest.mark.parametrize("name", sorted(mobile_goldens.FILES))
def test_committed_goldens_match_chordify_core(tmp_path, name):
    reason = mobile_goldens.missing(name)
    if reason:
        pytest.skip(f"{name} {reason}, as tools/mobile_goldens.Dockerfile provides")
    mobile_goldens.main(["--out", str(tmp_path), "--only", name])
    fresh = mobile_goldens.read_golden(tmp_path, name)
    committed = mobile_goldens.read_golden(mobile_goldens.DEFAULT_OUT, name)
    fresh.pop("environment", None)  # library versions are recorded, not compared
    committed.pop("environment", None)
    _same(fresh, committed, *TOLERANCE.get(name, (1e-9, 1e-12)))
