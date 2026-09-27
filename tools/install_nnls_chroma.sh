#!/usr/bin/env bash
# Build the NNLS Chroma / Chordino Vamp plugin (Mauch & Dixon) from source and install it
# where the `vamp` Python host finds it. Billboard's features were made with this plugin,
# so chordify_core.features.NNLS_BOTHCHROMA (and the shipped ChordNet model) needs it;
# without it the code falls back to the plugin-free CQT_BOTHCHROMA features.
#
#   tools/install_nnls_chroma.sh            # Linux: ~/vamp; macOS: ~/Library/Audio/Plug-Ins/Vamp
#   PREFIX=/usr/local/lib/vamp tools/install_nnls_chroma.sh
#
# Needs git, a C++ compiler, make and Boost headers:
#   Debian/Ubuntu: apt-get install libboost-dev
#   macOS:         xcode-select --install; brew install boost
# On Linux, export VAMP_PATH=<prefix> unless it is ~/vamp. On macOS the default folder is
# already on the Vamp search path. The Python host then installs with
#   pip install --upgrade setuptools wheel numpy && pip install --no-build-isolation vamp
set -euo pipefail

OS="$(uname -s)"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
if [ "$OS" = "Darwin" ]; then
  PREFIX="${PREFIX:-$HOME/Library/Audio/Plug-Ins/Vamp}"
else
  PREFIX="${PREFIX:-$HOME/vamp}"
fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone -q --depth 1 https://github.com/vamp-plugins/vamp-plugin-sdk.git "$WORK/vamp-plugin-sdk"
git clone -q --depth 1 https://github.com/c4dm/nnls-chroma.git "$WORK/nnls-chroma"

if [ "$OS" = "Darwin" ]; then
  BOOST_INCLUDE="$(brew --prefix boost 2>/dev/null)/include"
  if [ ! -d "$BOOST_INCLUDE/boost" ]; then
    echo "Boost headers not found: run 'brew install boost' first" >&2
    exit 1
  fi
  # The projects' own macOS makefiles target Intel and OS X 10.7; build for this machine instead.
  ARCH="-mmacosx-version-min=11.0 -arch $(uname -m)"
  (cd "$WORK/vamp-plugin-sdk" && make -f otherbuilds/Makefile.osx ARCHFLAGS="$ARCH" -j"$JOBS" sdkstatic >/dev/null)
  (cd "$WORK/nnls-chroma" && make -f Makefile.osx VAMP_SDK_DIR=../vamp-plugin-sdk BOOST_ROOT="$BOOST_INCLUDE" \
      ARCHFLAGS="$ARCH" -j"$JOBS" >/dev/null)
  LIBRARY="nnls-chroma.dylib"
else
  (cd "$WORK/vamp-plugin-sdk" && ./configure --disable-programs >/dev/null && make -j"$JOBS" sdkstatic >/dev/null)
  (cd "$WORK/nnls-chroma" && make -f Makefile.linux BOOST_ROOT=/usr/include -j"$JOBS" >/dev/null)
  LIBRARY="nnls-chroma.so"
fi

mkdir -p "$PREFIX"
cp "$WORK/nnls-chroma/$LIBRARY" "$WORK/nnls-chroma/nnls-chroma.cat" "$WORK/nnls-chroma/nnls-chroma.n3" "$PREFIX/"
echo "Installed nnls-chroma into $PREFIX"
