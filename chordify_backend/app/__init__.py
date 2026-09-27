import sys

if sys.version_info < (3, 10):  # the API and its schemas use X | Y type unions at runtime
    raise RuntimeError(
        f"Chordify's server needs Python 3.10 or newer, but this is Python {sys.version.split()[0]} "
        f"({sys.executable}). Recreate the virtual environment with python3.11: "
        "see 'One-time setup' in chordify_ai/TRAINING.md."
    )
