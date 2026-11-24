/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp.direct

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clashprojects.lastwave.audiodsp.PcmEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectTrackStream(
    private val attributes: AudioAttributes,
    private val usbBitPerfect: UsbBitPerfectOutput? = null,
) {
    private var track: AudioTrack? = null
    var encoding: Int = -1
        private set
    var sampleRateHz: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var isBitPerfectMixerActive: Boolean = false
        private set
    private var bytesPerFrame = 0

    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer? = null
    private var bytesWritten = 0L
    private var lastHead = 0L
    private var headWraps = 0L
    @Volatile private var dead = false
    @Volatile private var routedDeviceId: Int? = null
    private val routingListener = android.media.AudioRouting.OnRoutingChangedListener { router ->
        val id = runCatching { router.routedDevice?.id }.getOrNull()
        val previous = routedDeviceId
        routedDeviceId = id
        if (previous != null && id != previous) {
            Log.i(TAG, "rerouted $previous -> $id; re-verifying direct output")
            dead = true
        }
    }

    fun open(
        rateHz: Int,
        channels: Int,
        wireEncoding: Int,
        preferredUsbDevice: AudioDeviceInfo? = null,
        requestBitPerfectMixer: Boolean = false,
    ): Boolean {
        release()
        val androidEncoding = androidEncodingOf(wireEncoding) ?: return false
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false
        }
        return try {
            val format = AudioFormat.Builder()
                .setSampleRate(rateHz)
                .setEncoding(androidEncoding)
                .setChannelMask(mask)
                .build()

            // Must be applied before AudioTrack.Builder.build() so AudioPolicyManager picks the BIT_PERFECT path.
            if (usbBitPerfect != null) {
                usbBitPerfect.setAttributes(attributes)
                usbBitPerfect.setDevice(preferredUsbDevice)
                usbBitPerfect.setFormat(if (requestBitPerfectMixer) format else null)
                usbBitPerfect.setEnabled(requestBitPerfectMixer && preferredUsbDevice != null)
            }
            val bitPerfectGranted = requestBitPerfectMixer && usbBitPerfect?.isConfigured() == true

            val frameBytes = bytesPerSampleOf(wireEncoding) * channels
            val minBytes = AudioTrack.getMinBufferSize(rateHz, mask, androidEncoding)
            if (minBytes <= 0) {
                usbBitPerfect?.setEnabled(false)
                return false
            }
            val size = maxOf(minBytes * 4, rateHz / 2 * frameBytes)
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                usbBitPerfect?.setEnabled(false)
                return false
            }
            if (preferredUsbDevice != null) {
                runCatching { created.preferredDevice = preferredUsbDevice }
            }
            track = created
            encoding = wireEncoding
            sampleRateHz = rateHz
            channelCount = channels
            bytesPerFrame = frameBytes
            isBitPerfectMixerActive = bitPerfectGranted
            dead = false
            routedDeviceId = null
            runCatching { created.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper())) }
            resetCounters()
            true
        } catch (error: Exception) {
            Log.w(TAG, "AudioTrack ${rateHz}Hz enc=$wireEncoding refused", error)
            release()
            false
        }
    }

    fun probeDirect(): Boolean? {
        if (isBitPerfectMixerActive) return true
        val target = track ?: return null
        return try {
            target.playbackParams = PlaybackParams().setSpeed(1.01f).setPitch(1.0f)
            target.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(1.0f)
            false
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: IllegalStateException) {
            true
        } catch (_: Throwable) {
            null
        }
    }

    fun routedDeviceName(): String? = runCatching { track?.routedDevice?.productName?.toString() }.getOrNull()

    fun routedDeviceType(): Int? = runCatching { track?.routedDevice?.type }.getOrNull()

    fun isAlive(): Boolean = track != null && !dead && track?.state == AudioTrack.STATE_INITIALIZED

    fun write(src: ByteBuffer, srcEncoding: Int, srcChannels: Int): Boolean {
        val target = track ?: return false
        if (dead) return false
        if (!drainPending()) return false
        if (!src.hasRemaining()) return true
        if (srcChannels != channelCount) return false
        if (srcEncoding == encoding) {
            val written = target.write(src, src.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                onWriteError(written)
                return false
            }
            bytesWritten += written
            return !src.hasRemaining()
        }
        val out = convert(src, srcEncoding, encoding, scratchFor(src.remaining(), srcEncoding))
            ?: return false
        pending = out
        drainPending()
        return true
    }

    fun play() {
        runCatching { track?.play() }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun flush() {
        val target = track ?: return
        val wasPlaying = runCatching { target.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)
        runCatching { target.pause() }
        runCatching { target.flush() }
        pending = null
        resetCounters()
        if (wasPlaying) runCatching { target.play() }
    }

    fun playedFrames(): Long {
        val target = track ?: return 0L
        val head = runCatching { target.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }.getOrDefault(lastHead)
        if (head < lastHead) headWraps += 1L shl 32
        lastHead = head
        return headWraps + head
    }

    fun writtenFrames(): Long = if (bytesPerFrame > 0) bytesWritten / bytesPerFrame else 0L

    fun hasPendingData(): Boolean {
        drainPending()
        return pending != null || writtenFrames() > playedFrames()
    }

    fun underruns(): Long = runCatching { track?.underrunCount?.toLong() ?: 0L }.getOrDefault(0L)

    fun release() {
        val target = track
        track = null
        pending = null
        isBitPerfectMixerActive = false
        if (target != null) {
            runCatching { target.removeOnRoutingChangedListener(routingListener) }
            runCatching { target.pause() }
            runCatching { target.flush() }
            runCatching { target.release() }
        }
        usbBitPerfect?.setEnabled(false)
        resetCounters()
    }

    private fun resetCounters() {
        bytesWritten = 0L
        lastHead = 0L
        headWraps = 0L
    }

    private fun drainPending(): Boolean {
        val target = track ?: return false
        val buffered = pending ?: return true
        if (buffered.hasRemaining()) {
            val written = target.write(buffered, buffered.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                onWriteError(written)
                return false
            }
            bytesWritten += written
        }
        if (buffered.hasRemaining()) return false
        pending = null
        return true
    }

    private fun onWriteError(code: Int) {
        Log.w(TAG, "AudioTrack write error $code")
        if (code == AudioTrack.ERROR_DEAD_OBJECT) dead = true
    }

    private fun scratchFor(srcBytes: Int, srcEncoding: Int): ByteBuffer {
        val srcSample = bytesPerSampleOf(srcEncoding).coerceAtLeast(1)
        val need = srcBytes / srcSample * bytesPerSampleOf(encoding)
        val existing = scratch
        if (existing != null && existing.capacity() >= need) return existing
        return ByteBuffer.allocateDirect(maxOf(need, 64 * 1024)).order(ByteOrder.LITTLE_ENDIAN).also { scratch = it }
    }

    companion object {
        private const val TAG = "DirectTrack"

        fun bytesPerSampleOf(wireEncoding: Int): Int = when (wireEncoding) {
            PcmEncoding.PCM_16BIT.nativeId -> 2
            PcmEncoding.PCM_24BIT_PACKED.nativeId -> 3
            PcmEncoding.PCM_32BIT.nativeId, PcmEncoding.PCM_FLOAT.nativeId -> 4
            else -> 0
        }

        fun androidEncodingOf(wireEncoding: Int): Int? = when (wireEncoding) {
            PcmEncoding.PCM_16BIT.nativeId -> AudioFormat.ENCODING_PCM_16BIT
            PcmEncoding.PCM_24BIT_PACKED.nativeId ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_24BIT_PACKED else null
            PcmEncoding.PCM_32BIT.nativeId ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_32BIT else null
            PcmEncoding.PCM_FLOAT.nativeId -> AudioFormat.ENCODING_PCM_FLOAT
            else -> null
        }

        fun wireEncodingFor(srcEncoding: Int, effectiveBits: Int): Int = when (srcEncoding) {
            PcmEncoding.PCM_32BIT.nativeId ->
                if (effectiveBits <= 24) PcmEncoding.PCM_24BIT_PACKED.nativeId else PcmEncoding.PCM_32BIT.nativeId
            else -> PcmEncoding.PCM_24BIT_PACKED.nativeId
        }

        fun convert(src: ByteBuffer, srcEncoding: Int, dstEncoding: Int, dst: ByteBuffer): ByteBuffer? {
            val input = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dst.clear()
            when {
                srcEncoding == PcmEncoding.PCM_16BIT.nativeId && dstEncoding == PcmEncoding.PCM_24BIT_PACKED.nativeId -> {
                    while (input.remaining() >= 2) {
                        val s = input.short.toInt()
                        dst.put(0.toByte())
                        dst.put(s.toByte())
                        dst.put((s shr 8).toByte())
                    }
                }
                srcEncoding == PcmEncoding.PCM_32BIT.nativeId && dstEncoding == PcmEncoding.PCM_24BIT_PACKED.nativeId -> {
                    while (input.remaining() >= 4) {
                        val s = input.int
                        dst.put((s shr 8).toByte())
                        dst.put((s shr 16).toByte())
                        dst.put((s shr 24).toByte())
                    }
                }
                srcEncoding == PcmEncoding.PCM_FLOAT.nativeId && dstEncoding == PcmEncoding.PCM_24BIT_PACKED.nativeId -> {
                    while (input.remaining() >= 4) {
                        val clamped = input.float.coerceIn(-1.0f, 1.0f)
                        val scaled = (clamped * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
                        dst.put(scaled.toByte())
                        dst.put((scaled shr 8).toByte())
                        dst.put((scaled shr 16).toByte())
                    }
                }
                else -> return null
            }
            dst.flip()
            return dst
        }
    }
}
