/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "AudioEngine.h"

#include <soxr.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace lastwave::audio {
namespace {

constexpr double kPi = 3.1415926535897932384626433832795;

soxr_t asSoxr(void* handle) noexcept {
    return static_cast<soxr_t>(handle);
}

}

AudioEngine::~AudioEngine() {
    stop();
    std::lock_guard<std::mutex> processorLock(mediaProcessorMutex_);
    if (mediaResampler_ != nullptr) soxr_delete(asSoxr(mediaResampler_));
}

bool AudioEngine::start(std::int32_t preferredOutputSampleRate) {
    std::lock_guard<std::mutex> controlLock(controlMutex_);
    playbackEnabled_.store(false, std::memory_order_release);
    inputAlreadyProcessed_.store(false, std::memory_order_release);
    renderedFrames_.store(0, std::memory_order_release);
    underrunCount_.store(0, std::memory_order_release);
    restartAllowed_.store(true, std::memory_order_release);
    return startLocked(preferredOutputSampleRate);
}

bool AudioEngine::startLocked(std::int32_t preferredOutputSampleRate) {
    if (stream_ != nullptr) return true;

    const std::int32_t requestedRate = std::max(preferredOutputSampleRate, 0);
    requestedOutputSampleRate_.store(requestedRate, std::memory_order_release);

    std::shared_ptr<oboe::AudioStream> openedStream;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(kOutputChannels)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (requestedRate > 0) {
        builder.setSampleRate(requestedRate);
    }
    const auto openResult = builder.openStream(openedStream);
    if (openResult != oboe::Result::OK || openedStream == nullptr) {
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }

    const std::int32_t sampleRate = openedStream->getSampleRate();
    if (sampleRate <= 0 || sampleRate > 384000 ||
        openedStream->getChannelCount() != kOutputChannels ||
        openedStream->getFormat() != oboe::AudioFormat::Float) {

        openedStream->close();
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }

    if (!openedStream->usesAAudio()) {
        openedStream->close();
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }
    streamOpenCount_.fetch_add(1, std::memory_order_release);
    if (requestedRate > 0 && sampleRate != requestedRate) {

        rateAdaptationCount_.fetch_add(1, std::memory_order_release);
    }

    const auto framesPerBurst = std::max(openedStream->getFramesPerBurst(), 1);

    const auto targetDeviceBufferFrames = std::min(
        openedStream->getBufferCapacityInFrames(),
        std::max(
            framesPerBurst * 4,
            static_cast<std::int32_t>(
                static_cast<std::int64_t>(sampleRate) *
                kTargetDeviceBufferMilliseconds / 1000)));
    if (targetDeviceBufferFrames > 0) {
        (void) openedStream->setBufferSizeInFrames(targetDeviceBufferFrames);
    }

    {
        std::lock_guard<std::mutex> producerLock(producerMutex_);

        activeRingBuffer_.store(nullptr, std::memory_order_release);
        releaseProducerStateLocked();
        const auto capacitySamples = static_cast<std::size_t>(sampleRate) *
            kRingMilliseconds * kOutputChannels / 1000U;
        ringBuffer_ = std::make_unique<LockFreeRingBuffer<float>>(capacitySamples);
        rawScratch_.resize(kProducerChunkFrames * kOutputChannels);
        stereoScratch_.resize(kProducerChunkFrames * kOutputChannels);
        resampledScratch_.resize(kMaxResampledFrames * kOutputChannels);
    }

    oboeDsp_.setPeakProtectionEnabled(true);
    oboeDsp_.configure(sampleRate);
    buildFadeCurve(sampleRate);
    underrunFadePosition_ = fadeInCurve_.size();
    recoveryFadePosition_ = fadeInCurve_.size();
    underrunActive_ = false;
    recoveryFading_ = false;
    prebuffering_ = true;
    prebufferWaitCallbacks_ = kPrebufferMaxCallbacks;
    prebufferFrames_ = std::min(
        static_cast<std::size_t>(sampleRate) * kRingMilliseconds / 2000U,
        std::max(
            static_cast<std::size_t>(framesPerBurst * 4),
            static_cast<std::size_t>(sampleRate) * kPrebufferMilliseconds / 1000U));
    prebufferBaseFrames_ = prebufferFrames_;
    underrunDecayIntervalFrames_ =
        static_cast<std::size_t>(sampleRate) * kUnderrunDecaySeconds;
    cleanFrames_ = 0;
    underrunAnchor_.fill(0.0F);
    lastOutput_.fill(0.0F);
    currentOutputVolume_ = std::clamp(
        targetOutputVolume_.load(std::memory_order_acquire), 0.0F, 1.0F);
    outputVolumeRampStep_ = 1.0F /
        std::max(1.0F, static_cast<float>(sampleRate) * 0.005F);

    outputSampleRate_.store(sampleRate, std::memory_order_release);
    activeRingBuffer_.store(ringBuffer_.get(), std::memory_order_release);
    stream_ = std::move(openedStream);
    if (stream_->requestStart() != oboe::Result::OK) {
        activeRingBuffer_.store(nullptr, std::memory_order_release);
        stream_->close();
        stream_.reset();
        outputSampleRate_.store(0, std::memory_order_release);
        std::lock_guard<std::mutex> producerLock(producerMutex_);
        releaseProducerStateLocked();
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

void AudioEngine::stop() noexcept {
    std::lock_guard<std::mutex> controlLock(controlMutex_);
    playbackEnabled_.store(false, std::memory_order_release);
    restartAllowed_.store(false, std::memory_order_release);
    activeRingBuffer_.store(nullptr, std::memory_order_release);
    if (stream_ != nullptr) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    outputSampleRate_.store(0, std::memory_order_release);
    renderedFrames_.store(0, std::memory_order_release);
    std::lock_guard<std::mutex> producerLock(producerMutex_);
    releaseProducerStateLocked();
}

bool AudioEngine::isRunning() const noexcept {
    std::lock_guard<std::mutex> controlLock(controlMutex_);
    return stream_ != nullptr &&
        outputSampleRate_.load(std::memory_order_acquire) > 0;
}

void AudioEngine::flushOutput() noexcept {
    std::lock_guard<std::mutex> controlLock(controlMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    if (ring == nullptr || stream_ == nullptr) return;

    activeRingBuffer_.store(nullptr, std::memory_order_release);
    {
        std::lock_guard<std::mutex> producerLock(producerMutex_);
        ring->clear();
        resamplerFlushing_ = false;
    }
    underrunActive_ = false;
    recoveryFading_ = false;
    underrunFadePosition_ = fadeInCurve_.size();
    recoveryFadePosition_ = 0;
    prebuffering_ = true;
    prebufferWaitCallbacks_ = kPrebufferMaxCallbacks;
    lastOutput_.fill(0.0F);
    underrunAnchor_.fill(0.0F);
    cleanFrames_ = 0;
    activeRingBuffer_.store(ring, std::memory_order_release);
}

std::size_t AudioEngine::writePcm(
    const void* pcm,
    std::size_t frameCount,
    PcmFormat format,
    std::int32_t inputSampleRate,
    std::int32_t inputChannelCount) {
    if (pcm == nullptr || frameCount == 0 || inputSampleRate <= 0 ||
        (inputChannelCount != 1 && inputChannelCount != kOutputChannels) ||
        PcmConverter::bytesPerSample(format) == 0) {
        return 0;
    }

    std::lock_guard<std::mutex> producerLock(producerMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    const std::int32_t destinationRate = outputSampleRate_.load(std::memory_order_acquire);
    if (ring == nullptr || destinationRate <= 0 ||
        !configureResamplerLocked(inputSampleRate)) {
        return 0;
    }
    inputAlreadyProcessed_.store(false, std::memory_order_release);

    const auto* inputBytes = static_cast<const std::uint8_t*>(pcm);
    const std::size_t bytesPerFrame = PcmConverter::bytesPerSample(format) *
        static_cast<std::size_t>(inputChannelCount);
    const double ratio = static_cast<double>(destinationRate) / inputSampleRate;
    const std::size_t ratioSafeChunk = std::max<std::size_t>(
        1,
        static_cast<std::size_t>((kMaxResampledFrames - 256) /
            std::max(1.0, ratio)));

    std::size_t consumedFrames = 0;
    while (consumedFrames < frameCount) {
        const std::size_t chunkFrames = std::min({
            frameCount - consumedFrames,
            kProducerChunkFrames,
            ratioSafeChunk,
        });
        const void* chunkInput = inputBytes + consumedFrames * bytesPerFrame;
        const std::size_t inputSamples = chunkFrames *
            static_cast<std::size_t>(inputChannelCount);
        if (!PcmConverter::toFloat(chunkInput, inputSamples, format, rawScratch_.data())) {
            break;
        }

        if (inputChannelCount == 1) {
            for (std::size_t frame = 0; frame < chunkFrames; ++frame) {
                stereoScratch_[frame * 2U] = rawScratch_[frame];
                stereoScratch_[frame * 2U + 1U] = rawScratch_[frame];
            }
        } else {
            std::memcpy(
                stereoScratch_.data(),
                rawScratch_.data(),
                chunkFrames * kOutputChannels * sizeof(float));
        }

        if (resampler_ == nullptr) {
            const std::size_t writtenSamples = ring->write(
                stereoScratch_.data(),
                chunkFrames * kOutputChannels);
            const std::size_t writtenFrames = writtenSamples / kOutputChannels;
            consumedFrames += writtenFrames;
            if (writtenFrames != chunkFrames) break;
            continue;
        }

        const std::size_t availableOutputFrames = std::min(
            kMaxResampledFrames,
            ring->availableToWrite() / kOutputChannels);
        if (availableOutputFrames == 0) break;

        std::size_t inputDone = 0;
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(resampler_),
            stereoScratch_.data(),
            chunkFrames,
            &inputDone,
            resampledScratch_.data(),
            availableOutputFrames,
            &outputDone);
        if (error != nullptr) break;

        const std::size_t writtenSamples = ring->write(
            resampledScratch_.data(),
            outputDone * kOutputChannels);
        if (writtenSamples != outputDone * kOutputChannels) break;
        consumedFrames += inputDone;
        if (inputDone == 0) break;
    }
    return consumedFrames;
}

std::size_t AudioEngine::writeProcessedPcm(
    const float* pcm,
    std::size_t frameCount,
    std::int32_t inputSampleRate,
    std::int32_t inputChannelCount) {
    if (pcm == nullptr || frameCount == 0 || inputSampleRate <= 0 ||
        (inputChannelCount != 1 && inputChannelCount != kOutputChannels)) {
        return 0;
    }

    std::lock_guard<std::mutex> producerLock(producerMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    const auto destinationRate = outputSampleRate_.load(std::memory_order_acquire);
    if (ring == nullptr || inputSampleRate != destinationRate) return 0;
    inputAlreadyProcessed_.store(true, std::memory_order_release);

    const std::size_t writableFrames = std::min(
        frameCount,
        ring->availableToWrite() / kOutputChannels);
    if (writableFrames == 0) return 0;
    if (inputChannelCount == kOutputChannels) {
        return ring->write(pcm, writableFrames * kOutputChannels) / kOutputChannels;
    }

    std::size_t writtenFrames = 0;
    while (writtenFrames < writableFrames) {
        const std::size_t chunkFrames = std::min(
            writableFrames - writtenFrames,
            kProducerChunkFrames);
        for (std::size_t frame = 0; frame < chunkFrames; ++frame) {
            const float sample = pcm[writtenFrames + frame];
            stereoScratch_[frame * kOutputChannels] = sample;
            stereoScratch_[frame * kOutputChannels + 1U] = sample;
        }
        const std::size_t writtenSamples = ring->write(
            stereoScratch_.data(),
            chunkFrames * kOutputChannels);
        const std::size_t chunkWrittenFrames = writtenSamples / kOutputChannels;
        writtenFrames += chunkWrittenFrames;
        if (chunkWrittenFrames != chunkFrames) break;
    }
    return writtenFrames;
}

void AudioEngine::setPlaying(bool playing) noexcept {
    playbackEnabled_.store(playing, std::memory_order_release);
}

void AudioEngine::setOutputVolume(float volume) noexcept {
    if (!std::isfinite(volume)) volume = 1.0F;
    targetOutputVolume_.store(std::clamp(volume, 0.0F, 1.0F), std::memory_order_release);
}

void AudioEngine::flushResampler() {
    std::lock_guard<std::mutex> producerLock(producerMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    if (ring == nullptr || resampler_ == nullptr) return;
    resamplerFlushing_ = true;

    while (true) {
        const std::size_t outputCapacity = std::min(
            kMaxResampledFrames,
            ring->availableToWrite() / kOutputChannels);
        if (outputCapacity == 0) return;
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(resampler_),
            nullptr,
            0,
            nullptr,
            resampledScratch_.data(),
            outputCapacity,
            &outputDone);
        if (error != nullptr || outputDone == 0) {
            soxr_delete(asSoxr(resampler_));
            resampler_ = nullptr;
            resamplerInputRate_ = 0;
            resamplerFlushing_ = false;
            return;
        }
        if (ring->write(resampledScratch_.data(), outputDone * kOutputChannels) !=
            outputDone * kOutputChannels) {
            return;
        }
    }
}

void AudioEngine::setStudioMasterClarity(bool enabled) noexcept {
    oboeDsp_.setStudioMasterClarity(enabled);
    mediaDsp_.setStudioMasterClarity(enabled);
}

void AudioEngine::setBitPerfect(bool enabled) noexcept {
    oboeDsp_.setBitPerfect(enabled);
    mediaDsp_.setBitPerfect(enabled);
}

bool AudioEngine::isBitPerfect() const noexcept {
    return oboeDsp_.isBitPerfectEnabled() && mediaDsp_.isBitPerfectEnabled();
}

void AudioEngine::setEqualizer(
    bool enabled,
    const float* gainsDb,
    std::size_t gainCount) noexcept {
    oboeDsp_.setEqualizer(enabled, gainsDb, gainCount);
    mediaDsp_.setEqualizer(enabled, gainsDb, gainCount);
}

bool AudioEngine::configureMediaProcessor(
    std::int32_t inputSampleRate,
    std::int32_t outputSampleRate,
    std::int32_t channelCount) {
    if (inputSampleRate <= 0 || outputSampleRate <= 0 ||
        (channelCount != 1 && channelCount != 2)) {
        return false;
    }
    std::lock_guard<std::mutex> processorLock(mediaProcessorMutex_);
    if (mediaResampler_ != nullptr) {
        soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
    }
    mediaInputSampleRate_ = inputSampleRate;
    mediaOutputSampleRate_ = outputSampleRate;
    mediaChannelCount_ = channelCount;
    mediaScratch_.resize(kProducerChunkFrames * static_cast<std::size_t>(channelCount));

    mediaDsp_.setPeakProtectionEnabled(true);
    mediaDsp_.configure(outputSampleRate);
    return configureMediaResamplerLocked();
}

bool AudioEngine::processMediaPcm(
    const void* pcm,
    std::size_t frameCount,
    PcmFormat format,
    float* output,
    std::size_t outputCapacityFrames,
    std::size_t& outputFrameCount) {
    outputFrameCount = 0;
    if (pcm == nullptr || output == nullptr || frameCount == 0 ||
        outputCapacityFrames == 0) {
        return false;
    }
    std::lock_guard<std::mutex> processorLock(mediaProcessorMutex_);
    if (mediaInputSampleRate_ <= 0 || mediaOutputSampleRate_ <= 0 ||
        mediaChannelCount_ <= 0 || mediaScratch_.empty()) {
        return false;
    }
    const std::size_t channels = static_cast<std::size_t>(mediaChannelCount_);
    const std::size_t bytesPerFrame = PcmConverter::bytesPerSample(format) * channels;
    if (bytesPerFrame == 0) return false;

    if (mediaResampler_ == nullptr && mediaInputSampleRate_ == mediaOutputSampleRate_) {
        if (outputCapacityFrames < frameCount ||
            !PcmConverter::toFloat(pcm, frameCount * channels, format, output)) {
            return false;
        }
        mediaDsp_.process(output, static_cast<std::int32_t>(frameCount), mediaChannelCount_);
        outputFrameCount = frameCount;
        return true;
    }
    if (mediaResampler_ == nullptr) return false;

    const auto* inputBytes = static_cast<const std::uint8_t*>(pcm);
    std::size_t consumedFrames = 0;
    while (consumedFrames < frameCount) {
        const std::size_t chunkFrames = std::min(
            frameCount - consumedFrames,
            kProducerChunkFrames);
        if (!PcmConverter::toFloat(
                inputBytes + consumedFrames * bytesPerFrame,
                chunkFrames * channels,
                format,
                mediaScratch_.data())) {
            return false;
        }
        std::size_t chunkConsumed = 0;
        while (chunkConsumed < chunkFrames) {
            std::size_t inputDone = 0;
            std::size_t outputDone = 0;
            const std::size_t remainingOutput = outputCapacityFrames - outputFrameCount;
            if (remainingOutput == 0) break;
            const soxr_error_t error = soxr_process(
                asSoxr(mediaResampler_),
                mediaScratch_.data() + chunkConsumed * channels,
                chunkFrames - chunkConsumed,
                &inputDone,
                output + outputFrameCount * channels,
                remainingOutput,
                &outputDone);
            if (error != nullptr) return false;
            if (outputDone > 0) {
                mediaDsp_.process(
                    output + outputFrameCount * channels,
                    static_cast<std::int32_t>(outputDone),
                    mediaChannelCount_);
                outputFrameCount += outputDone;
            }
            if (inputDone == 0 && outputDone == 0) {

                break;
            }
            chunkConsumed += inputDone;
            consumedFrames += inputDone;
        }
        if (chunkConsumed < chunkFrames && outputCapacityFrames == outputFrameCount) {
            break;
        }
    }
    return true;
}

bool AudioEngine::flushMediaProcessor(
    float* output,
    std::size_t outputCapacityFrames,
    std::size_t& outputFrameCount) {
    outputFrameCount = 0;
    if (output == nullptr || outputCapacityFrames == 0) return false;
    std::lock_guard<std::mutex> processorLock(mediaProcessorMutex_);
    if (mediaResampler_ == nullptr) return true;
    const std::size_t channels = static_cast<std::size_t>(mediaChannelCount_);
    while (outputFrameCount < outputCapacityFrames) {
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(mediaResampler_),
            nullptr,
            0,
            nullptr,
            output + outputFrameCount * channels,
            outputCapacityFrames - outputFrameCount,
            &outputDone);
        if (error != nullptr) return false;
        mediaDsp_.process(
            output + outputFrameCount * channels,
            static_cast<std::int32_t>(outputDone),
            mediaChannelCount_);
        outputFrameCount += outputDone;
        if (outputDone == 0) break;
    }
    return true;
}

void AudioEngine::resetMediaProcessor() {
    std::lock_guard<std::mutex> processorLock(mediaProcessorMutex_);
    mediaDsp_.reset();
    if (mediaResampler_ != nullptr) {
        soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
    }
    (void) configureMediaResamplerLocked();
}

bool AudioEngine::configureMediaResamplerLocked() {
    if (mediaInputSampleRate_ <= 0 || mediaOutputSampleRate_ <= 0 ||
        (mediaChannelCount_ != 1 && mediaChannelCount_ != 2)) {
        return false;
    }
    if (mediaInputSampleRate_ == mediaOutputSampleRate_) return true;
    soxr_error_t error = nullptr;
    const soxr_io_spec_t ioSpec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);

    const soxr_quality_spec_t qualitySpec = soxr_quality_spec(SOXR_HQ, 0);
    const soxr_runtime_spec_t runtimeSpec = soxr_runtime_spec(1);
    mediaResampler_ = soxr_create(
        static_cast<double>(mediaInputSampleRate_),
        static_cast<double>(mediaOutputSampleRate_),
        static_cast<unsigned>(mediaChannelCount_),
        &error,
        &ioSpec,
        &qualitySpec,
        &runtimeSpec);
    if (error != nullptr || mediaResampler_ == nullptr) {
        if (mediaResampler_ != nullptr) soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
        return false;
    }
    return true;
}

std::size_t AudioEngine::bufferedFrames() const noexcept {
    std::lock_guard<std::mutex> producerLock(producerMutex_);
    const auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    return ring == nullptr ? 0 : ring->availableToRead() / kOutputChannels;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* ,
    void* audioData,
    std::int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    if (output == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;

    const std::size_t requestedFrames = static_cast<std::size_t>(numFrames);

    if (ring == nullptr) {
        std::memset(output, 0, requestedFrames * kOutputChannels * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    const bool playingNow = playbackEnabled_.load(std::memory_order_acquire);

    const bool drainingPauseTail =
        !playingNow && currentOutputVolume_ > 0.0F &&
        ring->availableToRead() >= kOutputChannels;
    if (!playingNow && !drainingPauseTail) {
        std::memset(output, 0, requestedFrames * kOutputChannels * sizeof(float));
        underrunActive_ = false;
        recoveryFading_ = false;
        prebuffering_ = true;
        prebufferWaitCallbacks_ = kPrebufferMaxCallbacks;
        lastOutput_.fill(0.0F);
        return oboe::DataCallbackResult::Continue;
    }
    const std::size_t availableFrames = ring->availableToRead() / kOutputChannels;
    if (prebuffering_ && playingNow &&
        (availableFrames == 0 ||
            (availableFrames < prebufferFrames_ && prebufferWaitCallbacks_-- > 0))) {
        std::memset(output, 0, requestedFrames * kOutputChannels * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }
    if (prebuffering_) {
        prebuffering_ = false;
        recoveryFading_ = true;
        recoveryFadePosition_ = 0;
    }
    const std::size_t readSamples = ring->read(output, requestedFrames * kOutputChannels);
    const std::size_t validFrames = readSamples / kOutputChannels;
    const bool wasUnderrun = underrunActive_;

    if (validFrames > 0) {
        if (!inputAlreadyProcessed_.load(std::memory_order_acquire)) {
            oboeDsp_.process(output, static_cast<std::int32_t>(validFrames));
        }
        if (wasUnderrun) {
            recoveryFading_ = true;
            recoveryFadePosition_ = 0;
        }

        const bool outputBitPerfect = oboeDsp_.isBitPerfectEnabled();
        for (std::size_t frame = 0; frame < validFrames; ++frame) {
            float recoveryGain = 1.0F;
            if (!outputBitPerfect && recoveryFading_) {
                if (recoveryFadePosition_ < fadeInCurve_.size()) {
                    recoveryGain = fadeInCurve_[recoveryFadePosition_++];
                } else {
                    recoveryFading_ = false;
                }
            }
            const std::size_t offset = frame * kOutputChannels;

            const float volumeTarget = playingNow
                ? targetOutputVolume_.load(std::memory_order_relaxed)
                : 0.0F;
            const float volumeDelta = volumeTarget - currentOutputVolume_;
            currentOutputVolume_ += std::clamp(
                volumeDelta,
                -outputVolumeRampStep_,
                outputVolumeRampStep_);
            const float gain = outputBitPerfect
                ? 1.0F
                : recoveryGain * currentOutputVolume_;
            output[offset] *= gain;
            output[offset + 1U] *= gain;

            if (!outputBitPerfect) {
                float s0 = output[offset];
                if (std::abs(s0) > 0.85F) s0 = std::tanh(s0);
                output[offset] = std::clamp(s0, -0.98F, 0.98F);

                float s1 = output[offset + 1U];
                if (std::abs(s1) > 0.85F) s1 = std::tanh(s1);
                output[offset + 1U] = std::clamp(s1, -0.98F, 0.98F);
            }
        }
        lastOutput_[0] = output[(validFrames - 1U) * kOutputChannels];
        lastOutput_[1] = output[(validFrames - 1U) * kOutputChannels + 1U];
        renderedFrames_.fetch_add(validFrames, std::memory_order_release);
    }

    if (validFrames < requestedFrames) {
        if (playingNow) {
            if (!wasUnderrun || validFrames > 0) {
                underrunCount_.fetch_add(1, std::memory_order_release);
            }
            if (!wasUnderrun || validFrames > 0) {
                underrunAnchor_ = lastOutput_;
                underrunFadePosition_ = 0;
            }
            recoveryFading_ = false;
            for (std::size_t frame = validFrames; frame < requestedFrames; ++frame) {
                const float fadeOut = underrunFadePosition_ < fadeInCurve_.size()
                    ? 1.0F - fadeInCurve_[underrunFadePosition_++]
                    : 0.0F;
                const std::size_t offset = frame * kOutputChannels;
                output[offset] = underrunAnchor_[0] * fadeOut;
                output[offset + 1U] = underrunAnchor_[1] * fadeOut;
            }
            underrunActive_ = true;
            prebuffering_ = true;
            prebufferWaitCallbacks_ = kPrebufferMaxCallbacks;
            cleanFrames_ = 0;

            const auto capacityFrames = ring->capacity() / kOutputChannels;
            prebufferFrames_ = std::max(
                prebufferBaseFrames_,
                std::min(prebufferFrames_ * 2U, capacityFrames / 2U));
        } else {

            std::memset(
                output + validFrames * kOutputChannels,
                0,
                (requestedFrames - validFrames) * kOutputChannels * sizeof(float));
            underrunActive_ = false;
            recoveryFading_ = false;
        }
    } else {
        underrunActive_ = false;

        cleanFrames_ += requestedFrames;
        while (cleanFrames_ >= underrunDecayIntervalFrames_) {
            cleanFrames_ -= underrunDecayIntervalFrames_;
            if (prebufferFrames_ > prebufferBaseFrames_) {
                prebufferFrames_ = std::max(
                    prebufferBaseFrames_, prebufferFrames_ / 2U);
            }
            auto current = underrunCount_.load(std::memory_order_acquire);
            if (current == 0) break;
            while (current > 0 && !underrunCount_.compare_exchange_weak(
                current, current - 1, std::memory_order_release)) {
            }
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(
    oboe::AudioStream* audioStream,
    oboe::Result ) {
    if (!restartAllowed_.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> controlLock(controlMutex_);
    if (!restartAllowed_.load(std::memory_order_acquire) ||
        stream_ == nullptr || stream_.get() != audioStream) {
        return;
    }

    streamRestartCount_.fetch_add(1, std::memory_order_release);
    activeRingBuffer_.store(nullptr, std::memory_order_release);
    stream_.reset();
    outputSampleRate_.store(0, std::memory_order_release);
    {
        std::lock_guard<std::mutex> producerLock(producerMutex_);
        releaseProducerStateLocked();
    }
    try {
        (void) startLocked(requestedOutputSampleRate_.load(std::memory_order_acquire));
    } catch (...) {

        restartAllowed_.store(false, std::memory_order_release);
        activeRingBuffer_.store(nullptr, std::memory_order_release);
        outputSampleRate_.store(0, std::memory_order_release);
    }
}

void AudioEngine::releaseProducerStateLocked() noexcept {
    if (resampler_ != nullptr) {
        soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
    }
    resamplerInputRate_ = 0;
    resamplerFlushing_ = false;
    ringBuffer_.reset();
    rawScratch_.clear();
    stereoScratch_.clear();
    resampledScratch_.clear();
}

bool AudioEngine::configureResamplerLocked(std::int32_t inputSampleRate) {
    const std::int32_t destinationRate = outputSampleRate_.load(std::memory_order_acquire);
    if (destinationRate <= 0) return false;

    if (resamplerFlushing_) {
        if (resampler_ != nullptr) soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
        resamplerInputRate_ = 0;
        resamplerFlushing_ = false;
    }
    if (resamplerInputRate_ == inputSampleRate) return true;

    if (resampler_ != nullptr) {
        soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
    }
    resamplerInputRate_ = inputSampleRate;
    if (inputSampleRate == destinationRate) return true;

    soxr_error_t error = nullptr;
    const soxr_io_spec_t ioSpec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
    const soxr_quality_spec_t qualitySpec = soxr_quality_spec(SOXR_HQ, 0);
    const soxr_runtime_spec_t runtimeSpec = soxr_runtime_spec(1);
    resampler_ = soxr_create(
        static_cast<double>(inputSampleRate),
        static_cast<double>(destinationRate),
        kOutputChannels,
        &error,
        &ioSpec,
        &qualitySpec,
        &runtimeSpec);
    if (error != nullptr || resampler_ == nullptr) {
        if (resampler_ != nullptr) soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
        resamplerInputRate_ = 0;
        return false;
    }
    return true;
}

void AudioEngine::buildFadeCurve(std::int32_t sampleRate) {
    const std::size_t fadeFrames = std::max<std::size_t>(
        1,
        static_cast<std::size_t>(std::llround(sampleRate * 0.002)));
    fadeInCurve_.resize(fadeFrames);
    for (std::size_t frame = 0; frame < fadeFrames; ++frame) {
        const double phase = kPi * static_cast<double>(frame + 1U) /
            static_cast<double>(fadeFrames);
        fadeInCurve_[frame] = static_cast<float>(0.5 * (1.0 - std::cos(phase)));
    }
}

}
