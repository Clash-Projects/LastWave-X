/*
 * Copyright (c) 2026 Marcelo Silva
 * SPDX-License-Identifier: MIT
 */

package com.clashprojects.lastwave.audiodsp.usb

sealed class UsbAudioException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DeviceNotFoundException : UsbAudioException("No USB audio device found")
    class PermissionDeniedException : UsbAudioException("USB device permission denied")
    class DeviceOpenFailedException(reason: String) : UsbAudioException("Failed to open USB device: $reason")
    class StreamCreationFailedException(reason: String) : UsbAudioException("Failed to create audio stream: $reason")
    class InterfaceClaimFailedException(interfaceId: Int) : UsbAudioException("Failed to claim USB interface $interfaceId")
}
