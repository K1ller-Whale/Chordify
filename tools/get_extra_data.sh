#!/usr/bin/env bash
# Download the extra training data for the large-vocabulary chord model (Linux and macOS):
#   - GuitarSet: annotations + microphone recordings, CC BY 4.0 (~700 MB)
#   - POP909: MIDI arrangements with chord labels, MIT licence (~150 MB)
#   - FluidR3_GM General MIDI soundfont, MIT licence (148 MB), to render MIDI to audio
# Rendering also needs the fluidsynth program: apt-get install fluidsynth (Linux) or
# brew install fluid-synth (macOS).
# Usage: bash tools/get_extra_data.sh [DEST]      (default DEST: data/raw, which git ignores)
set -euo pipefail

DEST="${1:-data/raw}"
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"
ZENODO="https://zenodo.org/api/records/3371780/files"

GS="$DEST/guitarset"
if [ ! -d "$GS/audio" ] || [ ! -d "$GS/annotation" ]; then
  echo "==> GuitarSet -> $GS"
  mkdir -p "$GS"
  curl -fL --retry 3 -o "$GS/annotation.zip" "$ZENODO/annotation.zip/content"
  curl -fL --retry 3 -o "$GS/audio_mono-mic.zip" "$ZENODO/audio_mono-mic.zip/content"
  unzip -q -o "$GS/annotation.zip" -d "$GS/annotation"
  unzip -q -o "$GS/audio_mono-mic.zip" -d "$GS/audio"
  rm "$GS/annotation.zip" "$GS/audio_mono-mic.zip"
else
  echo "==> GuitarSet already in $GS"
fi

if [ ! -d "$DEST/pop909/POP909" ]; then
  echo "==> POP909 -> $DEST/pop909"
  rm -rf "$DEST/pop909"
  git clone --depth 1 https://github.com/music-x-lab/POP909-Dataset.git "$DEST/pop909"
else
  echo "==> POP909 already in $DEST/pop909"
fi

SF="$DEST/soundfonts/FluidR3_GM.sf2"
if [ ! -f "$SF" ]; then
  echo "==> FluidR3_GM soundfont -> $SF"
  mkdir -p "$DEST/soundfonts"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  curl -fL --retry 3 -o "$WORK/sf.deb" \
    "https://deb.debian.org/debian/pool/main/f/fluid-soundfont/fluid-soundfont-gm_3.1-5.3_all.deb"
  (cd "$WORK" && ar x sf.deb && tar -xf data.tar.xz ./usr/share/sounds/sf2/FluidR3_GM.sf2)
  mv "$WORK/usr/share/sounds/sf2/FluidR3_GM.sf2" "$SF"
else
  echo "==> Soundfont already at $SF"
fi

takes=$(find "$GS/annotation" -name "*_comp.jams" | wc -l | tr -d ' ')
songs=$(find "$DEST/pop909/POP909" -name chord_midi.txt | wc -l | tr -d ' ')
echo "==> Done: $takes GuitarSet accompaniment takes (expected 180), $songs POP909 songs (expected 909)"
if ! command -v fluidsynth >/dev/null; then
  echo "    fluidsynth is not installed yet: apt-get install fluidsynth (Linux) or brew install fluid-synth (macOS)"
fi
echo "    --guitarset $GS --pop909 $DEST/pop909 --soundfont $SF"
