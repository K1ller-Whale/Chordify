#!/usr/bin/env bash
# Build the NNLS Chroma / Chordino Vamp plugin (Mauch & Dixon) from source and install it
# where the `vamp` Python host finds it. Billboard's features were made with this plugin,
# so chordify_core.features.NNLS_BOTHCHROMA needs it; without it the code falls back to
# the plugin-free CQT_BOTHCHROMA features.
#
#   tools/install_nnls_chroma.sh            # installs into ~/vamp
#   PREFIX=/usr/local/lib/vamp tools/install_nnls_chroma.sh
#
# Then export VAMP_PATH=<prefix> (not needed for ~/vamp on Linux).
# Needs: git, g++, make and Boost headers (Debian/Ubuntu: apt-get install libboost-dev).
set -euo pipefail

PREFIX="${PREFIX:-$HOME/vamp}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone -q --depth 1 https://github.com/vamp-plugins/vamp-plugin-sdk.git "$WORK/vamp-plugin-sdk"
git clone -q --depth 1 https://github.com/c4dm/nnls-chroma.git "$WORK/nnls-chroma"

(cd "$WORK/vamp-plugin-sdk" && ./configure --disable-programs >/dev/null && make -j"$(nproc)" sdkstatic >/dev/null)
(cd "$WORK/nnls-chroma" && make -f Makefile.linux BOOST_ROOT=/usr/include -j"$(nproc)" >/dev/null)

mkdir -p "$PREFIX"
cp "$WORK/nnls-chroma/nnls-chroma.so" "$WORK/nnls-chroma/nnls-chroma.cat" "$WORK/nnls-chroma/nnls-chroma.n3" "$PREFIX/"
echo "Installed nnls-chroma into $PREFIX"
