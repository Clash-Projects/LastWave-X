/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp.dsd

import com.clashprojects.lastwave.audiodsp.NativeAudioEngine
import java.io.Closeable

class DsdNativeDecimator(
    val channelCount: Int,
    val dsdSampleRateHz: Int,
    val lsbFirst: Boolean,
) : Closeable {
    val decimationFactor: Int = chooseDecimationFactor(dsdSampleRateHz)
    val outputSampleRateHz: Int = (dsdSampleRateHz / decimationFactor).coerceAtLeast(44_100)

    private var handle: Long = if (isAvailable && channelCount in 1..8) {
        try {
            nativeCreate(channelCount, decimationFactor, lsbFirst)
        } catch (_: LinkageError) {
            0L
        }
    } else {
        0L
    }

    val isReady: Boolean
        get() = handle != 0L

    fun maxOutputBytes(inputLengthBytes: Int, planar: Boolean, blockSizePerChannel: Int): Int {
        val stepBytes = (decimationFactor / 8).coerceAtLeast(1)
        val byteFrames = if (planar) blockSizePerChannel else inputLengthBytes / channelCount.coerceAtLeast(1)
        val maxFrames = (byteFrames + stepBytes) / stepBytes + 1
        return maxFrames * channelCount.coerceAtLeast(1) * 4
    }

    fun reset() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    fun process(
        input: ByteArray,
        offset: Int,
        length: Int,
        planar: Boolean,
        blockSizePerChannel: Int,
        outputPcmBytes: ByteArray,
    ): Int {
        val h = handle
        if (h == 0L || length <= 0) return 0
        return nativeProcess(h, input, offset, length, planar, blockSizePerChannel, outputPcmBytes)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDestroy(h)
        }
    }

    private external fun nativeCreate(channelCount: Int, decimationFactor: Int, lsbFirst: Boolean): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcess(
        handle: Long,
        inputBytes: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        planar: Boolean,
        blockSizePerChannel: Int,
        outputPcmBytes: ByteArray,
    ): Int

    companion object {
        val isAvailable: Boolean
            get() = NativeAudioEngine.libraryLoaded

        fun chooseDecimationFactor(dsdRateHz: Int): Int = when {
            dsdRateHz <= 0 -> 16
            dsdRateHz <= 1_800_000 -> 8
            dsdRateHz <= 4_000_000 -> 16
            dsdRateHz <= 8_000_000 -> 32
            dsdRateHz <= 16_000_000 -> 64
            else -> 128
        }

        fun dsdBadgeForRate(dsdRateHz: Int): String = when {
            dsdRateHz >= 20_000_000 -> "DSD512"
            dsdRateHz >= 10_000_000 -> "DSD256"
            dsdRateHz >= 5_000_000 -> "DSD128"
            else -> "DSD64"
        }
    }
}
