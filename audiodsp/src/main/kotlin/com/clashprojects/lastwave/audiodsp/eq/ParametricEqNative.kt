/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp.eq

import android.util.Log
import com.clashprojects.lastwave.audiodsp.PcmEncoding
import java.nio.ByteBuffer

object ParametricEqNative {
    private const val TAG = "ParametricEqNative"

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lastwave_audio")
        true
    }.getOrElse { error ->
        Log.w(TAG, "Native parametric EQ unavailable", error)
        false
    }

    fun publish(enabled: Boolean, preampDb: Float, filters: List<NativeFilter>) {
        if (!isAvailable) return
        runCatching {
            nativePublish(
                enabled,
                preampDb,
                IntArray(filters.size) { filters[it].type },
                FloatArray(filters.size) { filters[it].frequencyHz },
                FloatArray(filters.size) { filters[it].gainDb },
                FloatArray(filters.size) { filters[it].q },
                BooleanArray(filters.size) { filters[it].enabled },
            )
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    fun isEngaged(): Boolean = isAvailable && runCatching { nativeIsEngaged() }.getOrDefault(false)

    data class NativeFilter(
        val type: Int,
        val frequencyHz: Float,
        val gainDb: Float,
        val q: Float,
        val enabled: Boolean,
    )

    fun nativeEncodingOf(encoding: PcmEncoding): Int = encoding.nativeId

    @JvmStatic external fun nativePublish(
        enabled: Boolean,
        preampDb: Float,
        types: IntArray,
        frequencies: FloatArray,
        gains: FloatArray,
        qs: FloatArray,
        filterEnabled: BooleanArray,
    )
    @JvmStatic external fun nativeIsEngaged(): Boolean
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeConfigure(handle: Long, sampleRate: Int, channels: Int)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeProcessDirect(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
        encoding: Int,
    ): Boolean
    @JvmStatic external fun nativeProcessArray(
        handle: Long,
        array: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
    ): Boolean
}

class DirectPcmEq {
    private var handle = 0L
    private var encoding: Int? = null
    private var frameBytes = 0

    fun configure(sampleRate: Int, channels: Int, pcmEncoding: PcmEncoding) {
        if (!ParametricEqNative.isAvailable) return
        if (handle == 0L) handle = runCatching { ParametricEqNative.nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) return
        encoding = ParametricEqNative.nativeEncodingOf(pcmEncoding)
        frameBytes = when (encoding) {
            0 -> 2
            1 -> 3
            2, 3 -> 4
            else -> 0
        } * channels.coerceAtLeast(1)
        runCatching { ParametricEqNative.nativeConfigure(handle, sampleRate, channels.coerceIn(1, 2)) }
        if (channels !in 1..2) encoding = null
    }

    fun reset() {
        if (handle != 0L) runCatching { ParametricEqNative.nativeReset(handle) }
    }

    fun process(buffer: ByteBuffer): Boolean {
        val enc = encoding ?: return false
        if (handle == 0L || frameBytes <= 0 || !buffer.hasRemaining()) return false
        val start = buffer.position()
        val length = (buffer.remaining() / frameBytes) * frameBytes
        if (length <= 0) return false
        return runCatching {
            when {
                // Never write through a read-only view: the memory behind it can be
                // write-protected (decoder output), and writing there is a SIGSEGV.
                buffer.isReadOnly -> false
                buffer.isDirect -> ParametricEqNative.nativeProcessDirect(handle, buffer, start, length, enc)
                buffer.hasArray() && !buffer.isReadOnly -> ParametricEqNative.nativeProcessArray(
                    handle,
                    buffer.array(),
                    buffer.arrayOffset() + start,
                    length,
                    enc,
                )
                else -> false
            }
        }.getOrDefault(false)
    }

    fun release() {
        if (handle != 0L) {
            runCatching { ParametricEqNative.nativeDestroy(handle) }
            handle = 0L
        }
    }
}
