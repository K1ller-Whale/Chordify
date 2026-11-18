# The Python reference for the Android engine's audio golden files: chordify_core with the
# NNLS Chroma Vamp plugin, set up as the CI job `python` sets it up (.github/workflows/ci.yml).
# Built and run by tools/mobile_goldens.sh.
FROM ubuntu:24.04

RUN apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends \
        build-essential ca-certificates ffmpeg git libboost-dev libsndfile1 python3 python3-dev python3-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /venv
ENV PATH=/venv/bin:$PATH VAMP_PATH=/opt/vamp

# soxr and vamp are pinned: the engine compiles the libsoxr and Vamp SDK sources these
# releases bundle (Mobile/engine/src/main/cpp/third_party/README.md).
RUN pip install -q --upgrade pip setuptools wheel numpy \
    && pip install -q scipy librosa==1.0.1 soundfile soxr==1.1.0 onnxruntime==1.30.0 pytest \
    && pip install -q --no-build-isolation vamp==1.1.0

COPY install_nnls_chroma.sh /tmp/install_nnls_chroma.sh
RUN sed -i 's/\r$//' /tmp/install_nnls_chroma.sh \
    && PREFIX=/opt/vamp bash /tmp/install_nnls_chroma.sh \
    && python -c "import vamp; assert 'nnls-chroma:nnls-chroma' in vamp.list_plugins()"
