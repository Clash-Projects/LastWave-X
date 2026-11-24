/*
 * Copyright (c) 2026 Marcelo Silva
 * SPDX-License-Identifier: MIT
 */

package com.clashprojects.lastwave.audiodsp.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

object UsbAudioPermissionHelper {

    private const val TAG = "UsbAudioPermission"

    fun handleIntent(context: Context, intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null

        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return null
        Log.i(TAG, "USB_DEVICE_ATTACHED: ${device.productName}")

        val usbAudioDevice = UsbAudioDevice.getInstance(context)
        val audioDevice = usbAudioDevice.findUsbAudioDevice() ?: return null

        if (usbAudioDevice.hasPermission(audioDevice)) {
            val info = usbAudioDevice.openDevice(audioDevice)
            if (info != null) {
                Log.i(TAG, "USB audio device claimed: ${info.deviceName}")
                return audioDevice
            }
        } else {
            usbAudioDevice.requestPermission(audioDevice) { granted ->
                if (granted) {
                    val info = usbAudioDevice.openDevice(audioDevice)
                    Log.i(TAG, "Permission granted, device claimed: ${info?.deviceName}")
                }
            }
        }
        return null
    }
}
