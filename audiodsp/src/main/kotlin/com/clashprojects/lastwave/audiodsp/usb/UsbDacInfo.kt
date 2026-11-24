/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp.usb

data class UsbDacInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val sampleRatesHz: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val usbPermissionGranted: Boolean = false,
    val hasUsbPeripheral: Boolean = false,
    val deviceId: Int = -1,
)
