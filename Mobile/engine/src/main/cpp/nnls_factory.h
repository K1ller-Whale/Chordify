// Creates the NNLS Chroma plugin. Kept in its own file because plugin headers (vamp-sdk) and
// host headers (vamp-hostsdk) must not meet in one source file; the plugin is compiled into
// the host's namespace (_VAMP_PLUGIN_IN_HOST_NAMESPACE), so the host can wrap it directly.
#pragma once

namespace _VampHost {
namespace Vamp {
class Plugin;
}
}

namespace chordify {

_VampHost::Vamp::Plugin *createNnlsChroma(float sampleRate);

}
