#include "nnls_host.h"

#include <vamp-hostsdk/PluginInputDomainAdapter.h>
#include <vamp-hostsdk/RealTime.h>

#include <algorithm>
#include <stdexcept>

#include "nnls_factory.h"

namespace chordify {

using _VampHost::Vamp::Plugin;
using _VampHost::Vamp::RealTime;
using _VampHost::Vamp::HostExt::PluginInputDomainAdapter;

// Appends the values of every feature of `output` to `rows`.
static void collect(const Plugin::FeatureSet &features, int output, std::vector<std::vector<float>> &rows) {
    auto found = features.find(output);
    if (found == features.end()) return;
    for (const Plugin::Feature &feature : found->second) rows.push_back(feature.values);
}

NnlsHost::NnlsHost(float sampleRate, const std::string &output)
    : m_plugin(new PluginInputDomainAdapter(createNnlsChroma(sampleRate))), m_sampleRate(sampleRate) {
    // vamp.load.load_and_configure, then vamp.process.process_with_initialised_plugin's reset().
    if (!m_plugin->initialise(1, kStep, kBlock)) {
        throw std::runtime_error("NNLS Chroma did not accept 1 channel, step 2048, block 16384");
    }
    m_plugin->reset();
    Plugin::OutputList outputs = m_plugin->getOutputDescriptors();
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        if (outputs[i].identifier == output) m_output = int(i);
    }
    if (m_output < 0) throw std::invalid_argument("NNLS Chroma has no output named " + output);
}

NnlsHost::~NnlsHost() = default;

void NnlsHost::write(const float *samples, std::size_t count) {
    if (m_finished) throw std::logic_error("write() after finish()");
    m_pending.insert(m_pending.end(), samples, samples + count);
    while (m_pending.size() - m_head >= std::size_t(kBlock)) {
        process(m_pending.data() + m_head);
        m_head += kStep;
    }
    if (m_head > std::size_t(4 * kBlock)) {  // drop consumed samples now and then, not every frame
        m_pending.erase(m_pending.begin(), m_pending.begin() + long(m_head));
        m_head = 0;
    }
}

std::vector<std::vector<float>> NnlsHost::finish() {
    if (m_finished) throw std::logic_error("finish() called twice");
    m_finished = true;
    std::vector<float> frame(kBlock);
    while (m_head < m_pending.size()) {  // frames_from_array: while i < n, zero-padded
        std::size_t available = std::min(m_pending.size() - m_head, std::size_t(kBlock));
        std::fill(std::copy(m_pending.begin() + long(m_head), m_pending.begin() + long(m_head + available),
                            frame.begin()), frame.end(), 0.0f);
        process(frame.data());
        m_head += kStep;
    }
    collect(m_plugin->getRemainingFeatures(), m_output, m_rows);
    return std::move(m_rows);
}

void NnlsHost::process(const float *frame) {
    RealTime timestamp = RealTime::frame2RealTime(m_frameStart, (unsigned int)(m_sampleRate));
    collect(m_plugin->process(&frame, timestamp), m_output, m_rows);
    m_frameStart += kStep;
}

}
