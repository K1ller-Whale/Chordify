#!/usr/bin/env bash
# Download what ChordNet training needs (Linux and macOS):
#   - McGill Billboard chord annotations, keys, metre and bars, via ChoCo (git, ~60 MB)
#   - McGill's NNLS chroma features, billboard-2.0-chordino (263 MB download, ~1 GB extracted)
# Usage: bash tools/get_billboard_data.sh [DEST]      (default DEST: data/raw, which git ignores)
set -euo pipefail

DEST="${1:-data/raw}"
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"

if [ ! -d "$DEST/choco/partitions/billboard/choco" ]; then
  echo "==> ChoCo Billboard annotations -> $DEST/choco"
  rm -rf "$DEST/choco"
  git clone --depth 1 --filter=blob:none --no-checkout https://github.com/smashub/choco.git "$DEST/choco"
  git -C "$DEST/choco" sparse-checkout set --no-cone 'partitions/billboard/choco/*' 'partitions/billboard/raw/original/*'
  git -C "$DEST/choco" checkout HEAD
else
  echo "==> ChoCo already in $DEST/choco"
fi

FEATURES="$DEST/billboard-features"
if [ ! -d "$FEATURES/McGill-Billboard" ]; then
  echo "==> McGill Billboard NNLS features -> $FEATURES"
  mkdir -p "$FEATURES"
  curl -fL --retry 3 -o "$FEATURES/billboard-2.0-chordino.tar.xz" \
    "https://www.dropbox.com/s/e9dm23vbawg9dsw/billboard-2.0-chordino.tar.xz?dl=1"
  tar -xJf "$FEATURES/billboard-2.0-chordino.tar.xz" -C "$FEATURES"
  rm "$FEATURES/billboard-2.0-chordino.tar.xz"
else
  echo "==> Features already in $FEATURES"
fi

songs=$(find "$FEATURES/McGill-Billboard" -name bothchroma.csv | wc -l | tr -d ' ')
echo "==> Done: $songs songs with features (expected 890)"
echo "    --choco $DEST/choco --chroma $FEATURES"
