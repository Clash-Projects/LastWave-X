/*
 * Copyright (c) 2026 Marcelo Silva
 * SPDX-License-Identifier: MIT
 */

#pragma once

#include <atomic>
#include <cstdint>
#include <linux/usbdevice_fs.h>

#define USB_AUDIO_PACKETS_PER_URB 8

#define USB_AUDIO_NUM_URBS 80

#define USB_AUDIO_URB_BUFFER_SIZE 16384
#define USB_AUDIO_ENABLE_CONTINUOUS_FEEDBACK 0

struct UrbSlot {

    struct usbdevfs_urb *urb;

    uint8_t *buffer;

    int dataLength;
};

struct UsbAudioContext {
    int fd;
    int interfaceId;
    int endpointOut;
    int endpointFeedback;
    int32_t sampleRate;
    int32_t channelCount;
    int32_t bitDepth;
    int32_t bytesPerSample;
    int32_t bytesPerFrame;
    int32_t maxPacketSize;
    int32_t dataInterval;
    int32_t feedbackPacketSize;
    int32_t feedbackInterval;
    int32_t usbSpeed;

    std::atomic<bool> running;

    uint8_t *transferBuffer;
    int32_t transferBufferCapacity;

    int64_t framesWritten;
    bool interfaceClaimed;

    UrbSlot ring[USB_AUDIO_NUM_URBS];

    int submitIdx;

    int reapIdx;

    int urbsInFlight;

    bool ringAllocated;

    double frameAccumulator;

    double calibratedFpmf;

    uint8_t residualBuffer[USB_AUDIO_URB_BUFFER_SIZE];
    int residualBytes;

    struct usbdevfs_urb *feedbackUrb;
    uint8_t feedbackBuffer[4];
    bool feedbackInFlight;

    bool continuousFeedback;
};

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);

void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);

void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);

void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples);
