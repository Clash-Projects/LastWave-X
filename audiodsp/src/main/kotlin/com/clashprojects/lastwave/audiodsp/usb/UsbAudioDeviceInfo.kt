/*
 * Copyright (c) 2026 Marcelo Silva
 * SPDX-License-Identifier: MIT
 */

package com.clashprojects.lastwave.audiodsp.usb

import android.hardware.usb.UsbDeviceConnection

data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val dataInterval: Int,
    val feedbackPacketSize: Int,
    val feedbackInterval: Int,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,

    val uacVersion: Int = 0,
)
