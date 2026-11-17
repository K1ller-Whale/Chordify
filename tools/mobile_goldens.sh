#!/usr/bin/env bash
# Regenerate the Android engine's features.json.gz in a Linux container that runs NNLS Chroma
# exactly as CI does (tools/mobile_goldens.Dockerfile), then check every golden file. Needs Docker.
#
#   tools/mobile_goldens.sh                  # features.json.gz from the committed audio inputs
#   tools/mobile_goldens.sh --render-audio   # re-render the audio inputs too (rarely needed)
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -q -t chordify-mobile-goldens -f tools/mobile_goldens.Dockerfile tools >/dev/null
repo="$(pwd)"
if [ -n "${MSYSTEM:-}" ]; then  # Git Bash on Windows: hand Docker a Windows path, unconverted
  repo="$(pwd -W)"
  export MSYS_NO_PATHCONV=1
fi
docker run --rm -v "$repo:/repo" -w /repo chordify-mobile-goldens sh -c \
  "python -m chordify_ai.export.mobile_goldens --only features $* && python -m pytest -q -p no:cacheprovider tests/ai/test_mobile_goldens.py"
