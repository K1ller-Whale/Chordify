// Compiled with the NNLS Chroma sources and _VAMP_PLUGIN_IN_HOST_NAMESPACE (see CMakeLists.txt).
#include "nnls_factory.h"

#include "NNLSChroma.h"

namespace chordify {

_VampHost::Vamp::Plugin *createNnlsChroma(float sampleRate) {
    return new NNLSChroma(sampleRate);
}

}
