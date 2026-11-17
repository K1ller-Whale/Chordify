"""The Android engine's golden files (Mobile/engine/src/test/resources/goldens) match what
chordify_core does now. When this fails, regenerate them and commit the result:

    python -m chordify_ai.export.mobile_goldens
    tools/mobile_goldens.sh        # features.json.gz, which needs the NNLS Chroma plugin
"""
import pytest

from chordify_ai.export import mobile_goldens
from chordify_core import features

# (rel, abs) per file. NNLS Chroma is built with -ffast-math, so two compilers or two numpy
# builds agree on its features only to about 1e-6; everything else is exact arithmetic.
TOLERANCE = {"features": (1e-4, 1e-5)}


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
    if name in mobile_goldens.NEEDS_NNLS and not features.nnls_available():
        pytest.skip("needs the NNLS Chroma Vamp plugin (tools/install_nnls_chroma.sh)")
    mobile_goldens.main(["--out", str(tmp_path), "--only", name])
    fresh = mobile_goldens.read_golden(tmp_path, name)
    committed = mobile_goldens.read_golden(mobile_goldens.DEFAULT_OUT, name)
    fresh.pop("environment", None)  # library versions are recorded, not compared
    committed.pop("environment", None)
    _same(fresh, committed, *TOLERANCE.get(name, (1e-9, 1e-12)))
