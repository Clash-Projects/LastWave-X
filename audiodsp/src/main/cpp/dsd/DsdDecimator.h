/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace lastwave::dsd {

class DsdDecimator final {
public:
    DsdDecimator(std::int32_t channelCount, std::int32_t decimationFactor, bool lsbFirst);

    void reset() noexcept;

    std::size_t processInterleaved(
        const std::uint8_t* input,
        std::size_t frameBytes,
        float* outPcm) noexcept;

    std::size_t processPlanar(
        const std::uint8_t* const* channelPlanes,
        std::size_t blockBytes,
        float* outPcm) noexcept;

    [[nodiscard]] std::size_t maxOutputFrames(std::size_t inputByteFrames) const noexcept {
        const std::size_t stepBytes = static_cast<std::size_t>(decimationFactor_ / 8);
        if (stepBytes == 0) return 0;
        return (inputByteFrames + bytePhase_ + stepBytes - 1) / stepBytes;
    }

    [[nodiscard]] std::int32_t channelCount() const noexcept { return channelCount_; }
    [[nodiscard]] std::int32_t decimationFactor() const noexcept { return decimationFactor_; }

private:
    static constexpr std::size_t kLutGroupsPer16x = 16;
    static constexpr float kSacdGain = 1.65f;

    std::int32_t channelCount_{2};
    std::int32_t decimationFactor_{16};
    bool lsbFirst_{true};
    std::size_t stepBytes_{2};
    std::size_t filterBytes_{16};

    std::vector<float> lut_;
    std::vector<std::uint8_t> history_;
    std::size_t ringWritePos_{0};
    std::size_t bytePhase_{0};

    void buildFilterTables();
    float convolveChannel(std::size_t ch) const noexcept;
};

}
