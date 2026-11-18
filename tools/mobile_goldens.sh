#!/usr/bin/env bash
# Regenerate the Android engine's features, beats, acoustic and analysis golden files in a Linux container
# with the pinned reference (tools/mobile_goldens.Dockerfile: NNLS Chroma, librosa, ONNX Runtime),
# then check every golden file. Needs Docker.
#
#   tools/mobile_goldens.sh                  # the four files, from the committed audio inputs
#   tools/mobile_goldens.sh --render-audio   # render any missing audio inputs first
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -q -t chordify-mobile-goldens -f tools/mobile_goldens.Dockerfile tools >/dev/null
repo="$(pwd)"
if [ -n "${MSYSTEM:-}" ]; then  # Git Bash on Windows: hand Docker a Windows path, unconverted
  repo="$(pwd -W)"
  export MSYS_NO_PATHCONV=1
fi
docker run --rm -v "$repo:/repo" -w /repo chordify-mobile-goldens sh -c \
  "python -m chordify_ai.export.mobile_goldens --only features --only beats --only acoustic --only analysis $* && python -m pytest -q -p no:cacheprovider tests/ai/test_mobile_goldens.py"
