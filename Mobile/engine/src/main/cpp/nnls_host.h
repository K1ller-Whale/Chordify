// Runs NNLS Chroma the way the Python `vamp` host (vamp 1.1.0, vamp.collect) runs it, so the
// features match chordify_core.features.nnls_bothchroma:
//
//   * one channel, step 2048, block 16384, default parameters, wrapped in the Vamp SDK 2.6
//     PluginInputDomainAdapter (Hann window, FFT shift, built-in FFT; timestamps shifted by
//     half a block);
//   * frame k holds samples [2048k, 2048k + 16384), zero-padded at the end, and there is one
//     frame for every k with 2048k < n (vamp/frames.py, frames_from_array);
//   * frame k is stamped RealTime::frame2RealTime(2048k, sampleRate).
//
// Samples arrive in chunks of any size; the features of one output come out of finish().
#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace _VampHost {
namespace Vamp {
class Plugin;
}
}

namespace chordify {

class NnlsHost {
public:
    static constexpr int kBlock = 16384;
    static constexpr int kStep = 2048;

    // `output` is a plugin output identifier: "bothchroma" for the features, or another one
    // ("logfreqspec") when checking the host against Python step by step.
    NnlsHost(float sampleRate, const std::string &output);
    ~NnlsHost();

    void write(const float *samples, std::size_t count);

    // Processes the zero-padded last frames and returns one row per frame. Call once.
    std::vector<std::vector<float>> finish();

private:
    void process(const float *frame);

    std::unique_ptr<_VampHost::Vamp::Plugin> m_plugin;
    float m_sampleRate;
    int m_output = -1;
    long m_frameStart = 0;  // sample index of the next frame's first sample
    std::vector<float> m_pending;  // samples from m_frameStart on (m_head marks the start)
    std::size_t m_head = 0;
    bool m_finished = false;
    std::vector<std::vector<float>> m_rows;
};

}
