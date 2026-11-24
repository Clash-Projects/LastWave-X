/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp

import android.media.AudioFormat

enum class PcmEncoding(
    val nativeId: Int,
    val bytesPerSample: Int,
    val bitDepth: Int,
    val rawWireCode: Int,
) {
    PCM_16BIT(nativeId = 0, bytesPerSample = 2, bitDepth = 16, rawWireCode = 2),
    PCM_24BIT_PACKED(nativeId = 1, bytesPerSample = 3, bitDepth = 24, rawWireCode = 0x15),
    PCM_32BIT(nativeId = 2, bytesPerSample = 4, bitDepth = 32, rawWireCode = 0x16),
    PCM_FLOAT(nativeId = 3, bytesPerSample = 4, bitDepth = 32, rawWireCode = 4),
    ;

    fun toAndroidAudioFormatEncoding(): Int =
        when (this) {
            PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            PCM_24BIT_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
            PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        }

    companion object {
        fun fromRawCode(code: Int): PcmEncoding? =
            when (code) {
                2 -> PCM_16BIT
                0x15 -> PCM_24BIT_PACKED
                0x16 -> PCM_32BIT
                4 -> PCM_FLOAT
                else -> null
            }
    }
}

data class PcmAudioFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: PcmEncoding,
) {
    val bytesPerFrame: Int
        get() = encoding.bytesPerSample * channelCount.coerceAtLeast(1)
}
