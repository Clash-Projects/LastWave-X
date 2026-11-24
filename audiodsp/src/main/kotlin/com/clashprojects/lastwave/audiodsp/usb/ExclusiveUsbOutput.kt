/*
 * Copyright (C) 2026 LastWave Authors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.clashprojects.lastwave.audiodsp.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.clashprojects.lastwave.audiodsp.PcmAudioFormat
import com.clashprojects.lastwave.audiodsp.PcmEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExclusiveUsbOutput(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val configLock = Any()
    private val stateLock = Any()

    @Volatile private var wanted = false
    @Volatile private var active = false
    @Volatile private var clockMatched = false
    @Volatile private var hardwareVolume = false
    @Volatile private var configuredRateHz = 0
    @Volatile private var listeningGain = 1f
    @Volatile private var softwareGainValue = 1f
    @Volatile private var lastHardwareRate = 0

    private val usbAudio = UsbAudioDevice.getInstance(appContext)
    private var stream: UsbAudioStream? = null
    private val pcmLock = Object()
    private val pcmQueue = ArrayDeque<QueuedPcm>()
    // Arrays the writer has finished with (guarded by pcmLock). Media3 hands over

    private val spareFloats = ArrayDeque<FloatArray>()
    private val spareBytes = ArrayDeque<ByteArray>()
    @Volatile private var queuedBytes = 0
    @Volatile private var flushRequested = false
    @Volatile private var writerStop = false
    private var writer: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var sourceEncoding = 0
    private var activeWireBits = 0
    private var activeChannels = 2

    @Volatile private var lastPcmGain = 1f
    private var useFloatWrite = false
    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startMediaTimeNeedsInit = true
    @Volatile private var mediaTimeBaseFrames = 0L
    @Volatile private var lastWriteElapsedMs = 0L
    @Volatile private var writingInProgress = false
    @Volatile private var paused = false
    private var pendingVolume = 1f
    private var featureVolume: UacFeatureVolume? = null
    private var clockRechecked = false
    private var volumeReceiverRegistered = false

    @Volatile var lastFailureReason: String? = null
        private set
    @Volatile var lastFailureDefinitive: Boolean = false
        private set
    @Volatile private var lastClockWatchdogMs = 0L
    @Volatile private var lastAppliedListening = Float.NaN
    private var volumeProbed = false
    @Volatile private var lastNonMaxListeningGain = Float.NaN
    @Volatile private var ignoreStreamMusicMax = false

    @Volatile private var forceSoftwareVolume = false

    private val sessionBlockedDevices = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var currentDeviceKey: String? = null
    @Volatile private var sessionSummary: String = ""

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val volumePrefs =
        appContext.getSharedPreferences("lastwave_exclusive_usb", Context.MODE_PRIVATE)

    init {
        clearBlockedDevices()
        lastNonMaxListeningGain = volumePrefs.getFloat(KEY_LAST_NON_MAX_GAIN, Float.NaN)
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (streamType != AudioManager.STREAM_MUSIC) return
            val extraVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1).takeIf { it >= 0 }
            syncListeningGain(extraVol)
        }
    }

    fun setWanted(enabled: Boolean) {
        if (wanted == enabled) {
            if (enabled) {
                synchronized(stateLock) {
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
            }
            return
        }
        synchronized(configLock) {
            if (wanted == enabled) return
            wanted = enabled
            if (!enabled) {
                teardownLocked(closeDevice = true)
            } else {
                clearBlockedDevices()
                synchronized(stateLock) {
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
            }
        }
    }

    fun isWanted(): Boolean = wanted

    fun setForceSoftwareVolume(value: Boolean) {
        synchronized(stateLock) {
            if (forceSoftwareVolume == value) return
            forceSoftwareVolume = value
            if (value && active && hardwareVolume) {
                val fv = featureVolume
                hardwareVolume = false
                lastAppliedListening = Float.NaN
                applyVolumeLocked()
                runCatching { fv?.setNormalized(1f) }
                Log.i(TAG, "volume forced to software; Feature Unit parked at 0 dB")
            }
        }
    }

    fun isForceSoftwareVolume(): Boolean = forceSoftwareVolume

    fun hasHardwareVolumeControl(): Boolean = active && featureVolume != null && (hardwareVolume || featureVolumeAttached)

    @Volatile private var featureVolumeAttached = false

    fun isActive(): Boolean = active

    fun isClockMatched(): Boolean = active && clockMatched

    fun isClockFallbackActive(): Boolean = active && currentFallbackNegotiated

    fun usesHardwareVolume(): Boolean = active && hardwareVolume

    fun softwareGain(): Float = if (!active) 1f else softwareGainValue

    fun currentRateHz(): Int = if (active) configuredRateHz else 0

    fun activeWireBits(): Int = if (active) activeWireBits else 0

    fun lastHardwareRateHz(): Int = lastHardwareRate

    fun supportedHardwareRatesHz(): List<Int> = usbAudio.querySupportedSampleRates()

    private fun pickPlayableHardwareRate(sourceHz: Int, supportedHz: List<Int>): Int? {
        val supported = supportedHz.filter { it > 0 }.toSet()
        if (supported.isEmpty()) return null
        if (sourceHz in supported) return sourceHz

        if (sourceHz > 44100 && sourceHz % 44100 == 0) {
            val family48 = supported.filter { it % 48000 == 0 }
            if (family48.isNotEmpty()) {
                val hiRes48 = family48.filter { it >= sourceHz }.minOrNull()
                    ?: family48.maxOrNull()
                if (hiRes48 != null) return hiRes48
            }
        }

        val divisors = supported.filter { it < sourceHz && sourceHz % it == 0 }
        divisors.maxOrNull()?.let { return it }

        val multiples = supported.filter { it > sourceHz && it % sourceHz == 0 }
        multiples.minOrNull()?.let { return it }

        val is441 = sourceHz % 44100 == 0
        val is48 = sourceHz % 48000 == 0
        val sameFamily = supported.filter { (is441 && it % 44100 == 0) || (is48 && it % 48000 == 0) }
        sameFamily.minByOrNull { kotlin.math.abs(it - sourceHz) }?.let { return it }

        val belowOrEqual = supported.filter { it <= sourceHz }
        belowOrEqual.maxOrNull()?.let { return it }
        return supported.minByOrNull { kotlin.math.abs(it - sourceHz) }
    }

    fun framesWritten(): Long = stream?.framesWritten ?: 0L

    fun isPaused(): Boolean = paused

    fun setPaused(value: Boolean) {
        val wasPaused = paused
        paused = value
        if (value) {
            lastWriteElapsedMs = 0L
        } else if (wasPaused && active) {
            verifyClockWatchdog(force = true)
        }
        synchronized(pcmLock) {
            pcmLock.notifyAll()
        }
    }

    fun isStreamAlive(): Boolean = active && stream?.isAlive == true

    fun isStreamingAudio(): Boolean {
        if (!active || paused || stream?.isAlive != true) return false
        if (writingInProgress || queuedBytes > 0) return true
        val last = lastWriteElapsedMs
        return last > 0L && (SystemClock.elapsedRealtime() - last) in 0L..750L
    }

    fun noteSeek(positionUs: Long) {
        val timeUs = positionUs.coerceAtLeast(0L)
        val rate = configuredRateHz.coerceAtLeast(1)
        val targetFrames = timeUs * rate / MICROS_PER_SECOND
        Log.i(
            TAG,
            "seek timeUs=$timeUs targetFrames=$targetFrames rate=$rate " +
                "clock=${stream?.framesWritten} alive=${stream?.isAlive}",
        )
        synchronized(pcmLock) {
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true
            lastWriteElapsedMs = 0L
        }
    }

    fun syncListeningGain(explicitVol: Int? = null) {
        val stream = readStreamMusicGain(explicitVol) ?: return

        if (ignoreStreamMusicMax && stream >= ATTACH_MAX_PIN_GAIN) return
        if (stream < ATTACH_MAX_PIN_GAIN) ignoreStreamMusicMax = false

        if (active) rememberStreamGain(stream)
        val next = stream
        if (lastAppliedListening.isFinite() &&
            kotlin.math.abs(next - listeningGain) < 1e-4f
        ) {
            listeningGain = next
            return
        }
        synchronized(stateLock) {
            listeningGain = next
            applyVolumeLocked()
        }
    }

    fun configure(
        format: PcmAudioFormat,
        rateOverrideHz: Int? = null,
        bitDepthHint: Int? = null,
    ): Boolean {
        if (!wanted) return false
        if (format.sampleRateHz <= 0 || format.channelCount !in 1..MAX_CHANNELS) {
            lastFailureDefinitive = true
            return false
        }
        val targetRate = rateOverrideHz?.takeIf { it > 0 } ?: format.sampleRateHz
        val floatSource = format.encoding == PcmEncoding.PCM_FLOAT || rateOverrideHz != null
        val sourceBits = format.encoding.bitDepth
        val resolvedBits = when {
            targetRate > 192_000 -> 32
            targetRate > 48_000 -> 24
            bitDepthHint != null && bitDepthHint > 0 -> bitDepthHint
            sourceBits > 0 -> sourceBits
            rateOverrideHz != null -> 24
            else -> 16
        }
        synchronized(configLock) {
            if (!wanted) return false
            return runCatching {
                configureLocked(
                    targetRate,
                    format.channelCount,
                    resolvedBits,
                    floatSource,
                    if (rateOverrideHz != null) PcmEncoding.PCM_FLOAT.rawWireCode else format.encoding.rawWireCode,
                    rateOverrideHz,
                )
            }.onFailure { error ->
                Log.w(TAG, "Exclusive USB configure failed", error)
                teardownLocked(closeDevice = true)
            }.getOrDefault(false)
        }
    }

    fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        isFloatBuffer: Boolean = useFloatWrite,
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        if (paused) return false
        val running = stream
        if (!wanted || !active || running == null || !running.isAlive) return false
        val size = buffer.remaining()

        synchronized(pcmLock) {
            if (queuedBytes > 0 && queuedBytes + size > MAX_QUEUED_BYTES) return false
        }
        if (startMediaTimeNeedsInit) {
            mediaTimeBaseFrames = running.framesWritten
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            startMediaTimeNeedsInit = false
        }
        recheckClockLocked()

        val chunk = if (isFloatBuffer) {
            val count = size / Float.SIZE_BYTES
            val floats = synchronized(pcmLock) { spareFloats.removeFirstOrNull() }
                ?.takeIf { it.size == count } ?: FloatArray(count)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            buffer.position(buffer.limit())
            QueuedPcm(floats, null, 0, size)
        } else {
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = synchronized(pcmLock) { spareBytes.removeFirstOrNull() }
                ?.takeIf { it.size == size } ?: ByteArray(size)
            buffer.get(bytes)
            QueuedPcm(null, bytes, encoding, size)
        }
        synchronized(pcmLock) {
            if (paused || writerStop) return false
            pcmQueue.addLast(chunk)
            queuedBytes += size
            pcmLock.notifyAll()
        }
        return true
    }

    fun getCurrentPositionUs(): Long {
        val running = stream ?: return POSITION_NOT_SET
        if (!active || configuredRateHz <= 0 || startMediaTimeNeedsInit) {
            return POSITION_NOT_SET
        }
        val frames = (running.framesWritten - mediaTimeBaseFrames).coerceAtLeast(0L)
        return startMediaTimeUs + frames * MICROS_PER_SECOND / configuredRateHz
    }

    fun hasPendingData(): Boolean =
        active && (queuedBytes > 0 || (!paused && stream?.isAlive == true))

    fun flush() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            lastWriteElapsedMs = 0L
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true

            paused = false
            pcmLock.notifyAll()
        }
    }

    fun restartIfStopped(): Boolean {
        val running = stream ?: return false
        if (running.isAlive) return false
        if (!running.start()) return false
        synchronized(stateLock) {
            paused = false
            mediaTimeBaseFrames = running.framesWritten
        }
        Log.i(TAG, "seek rearmed clock=${running.framesWritten} startUs=$startMediaTimeUs")
        return true
    }

    fun handleDiscontinuity() {

    }

    fun prepareForNextItem() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            paused = false
            pcmLock.notifyAll()
        }
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        currentFallbackNegotiated = false
        mediaTimeBaseFrames = stream?.framesWritten ?: 0L
    }

    fun reset() {
        synchronized(configLock) {
            teardownLocked(closeDevice = true)
        }
    }

    fun release() {
        synchronized(configLock) {
            wanted = false
            teardownLocked(closeDevice = true)
        }
    }

    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        synchronized(stateLock) {
            pendingVolume = normalized
            applyVolumeLocked()
        }
    }

    private fun configureLocked(
        sampleRate: Int,
        channelCount: Int,
        sourceBits: Int,
        floatSource: Boolean,
        pcmEncoding: Int,
        rateOverrideHz: Int? = null,
    ): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return failLocked("no USB audio device")
        if (!manager.hasPermission(usbDevice)) return failLocked("USB permission missing")
        blockReasonFor(usbDevice.vendorId, usbDevice.productId)?.let { reason ->
            return failLocked("disabled for this DAC after a failed test ($reason) — turn USB Exclusive off and on to retry", definitive = true)
        }
        currentDeviceKey = "${usbDevice.vendorId}:${usbDevice.productId}"

        val bits = if (sourceBits > 0) sourceBits else (if (floatSource) 24 else 16)
        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            configuredRateHz == sampleRate &&
            activeChannels == channelCount &&
            active &&
            sourceEncoding == pcmEncoding &&
            useFloatWrite == floatSource
        ) {
            currentFallbackNegotiated = rateOverrideHz != null
            synchronized(stateLock) {
                paused = false
                startMediaTimeNeedsInit = true
                mediaTimeBaseFrames = reuse.framesWritten
                ensureVolumeObserverLocked()
                syncListeningGainLocked()
            }
            return true
        }

        stopStreamLocked()
        usbAudio.closeDevice()
        connection = null
        val info = usbAudio.openDevice(usbDevice)
            ?: return failLocked("openDevice failed" + (usbAudio.lastOpenError?.let { ": $it" } ?: ""))
        val hwRates = usbAudio.querySupportedSampleRates()

        var effectiveRate = sampleRate
        var effectiveBits = bits
        var effectiveFloatSource = floatSource
        var effectiveEncoding = pcmEncoding
        var autoNegotiatedFallback = rateOverrideHz != null

        if (rateOverrideHz == null && hwRates.isNotEmpty() && effectiveRate !in hwRates) {
            val fallback = pickPlayableHardwareRate(effectiveRate, hwRates)
            if (fallback != null && fallback != effectiveRate) {
                Log.i(
                    TAG,
                    "DAC hardware descriptor lacks ${effectiveRate}Hz (supports $hwRates); " +
                        "switching exclusive clock to ${fallback}Hz for soxr resampling",
                )
                effectiveRate = fallback
                effectiveBits = 24
                effectiveFloatSource = true
                effectiveEncoding = PcmEncoding.PCM_FLOAT.rawWireCode
                autoNegotiatedFallback = true
            }
        }

        val requestBits = if (effectiveBits in 1..16) 24 else effectiveBits
        var (alt, wireBits) = usbAudio.findAltSetting(requestBits, effectiveRate, channelCount)
        var rateSetBefore = usbAudio.setSampleRate(effectiveRate)
        if (!usbAudio.setAltSetting(alt)) {
            val fallbackAlt = usbAudio.findAltSetting(24, effectiveRate, channelCount)
            if (fallbackAlt.first != alt && usbAudio.setAltSetting(fallbackAlt.first)) {
                alt = fallbackAlt.first
                wireBits = fallbackAlt.second
            } else {
                return failLocked("setAltSetting $alt failed", definitive = true)
            }
        }
        var reported = usbAudio.readSampleRate()
        if (!rateSetBefore || (reported > 0 && reported != effectiveRate)) {
            rateSetBefore = usbAudio.setSampleRate(effectiveRate)
            reported = usbAudio.readSampleRate()
        }

        if (reported > 0 && reported != effectiveRate) {
            val candidates = (hwRates + reported).distinct()
            val candidateRate = pickPlayableHardwareRate(sampleRate, candidates) ?: reported
            Log.w(
                TAG,
                "DAC internal clock reported ${reported}Hz instead of ${effectiveRate}Hz; " +
                    "negotiating fallback clock ${candidateRate}Hz",
            )
            val candidateSet = if (candidateRate != reported) {
                usbAudio.setSampleRate(candidateRate)
            } else {
                true
            }
            val afterFallback = usbAudio.readSampleRate().takeIf { it > 0 } ?: candidateRate
            effectiveRate = if (candidateSet && afterFallback == candidateRate) {
                candidateRate
            } else {
                afterFallback
            }
            effectiveBits = 24
            effectiveFloatSource = true
            effectiveEncoding = PcmEncoding.PCM_FLOAT.rawWireCode
            autoNegotiatedFallback = true
            val updatedAlt = usbAudio.findAltSetting(effectiveBits, effectiveRate, channelCount)
            if (updatedAlt.first != alt) {
                if (usbAudio.setAltSetting(updatedAlt.first)) {
                    alt = updatedAlt.first
                    wireBits = updatedAlt.second
                }
            }
            reported = usbAudio.readSampleRate()
        }

        val selected = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&

                    it.id == info.interfaceId &&
                    it.alternateSetting == alt &&
                    it.endpointCount > 0
            }
        val selectedOut = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }
        if (selectedOut == null) return failLocked("alt $alt has no isochronous OUT endpoint", definitive = true)
        val selectedFeedback = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        val maxPacket = isoPacketBytes(selectedOut.maxPacketSize)
        val dataInterval = selectedOut.interval.coerceAtLeast(1)
        val feedbackPacketSize = selectedFeedback?.maxPacketSize?.let { isoPacketBytes(it) }
            ?: info.feedbackPacketSize
        val feedbackInterval = selectedFeedback?.interval?.coerceAtLeast(1) ?: dataInterval
        val pollFeedback = selectedFeedback != null &&
            UsbAudioDevice.needsContinuousFeedback(usbDevice.vendorId, usbDevice.productId)
        if (pollFeedback) waitForClockValid()
        val created = UsbAudioStream(
            info.fd,
            info.interfaceId,
            selectedOut.address,
            selectedFeedback?.address ?: 0,
            effectiveRate,
            channelCount,
            wireBits,
            maxPacket,
            dataInterval,
            feedbackPacketSize,
            feedbackInterval,
            pollFeedback,
        )
        if (!created.isReady || !created.start()) {
            created.release()
            return failLocked("exclusive USB start failed")
        }
        connection = info.connection
        stream = created
        sourceEncoding = effectiveEncoding
        activeWireBits = wireBits
        activeChannels = channelCount.coerceAtLeast(1)
        lastPcmGain = softwareGain()
        useFloatWrite = effectiveFloatSource
        configuredRateHz = effectiveRate
        currentFallbackNegotiated = autoNegotiatedFallback
        active = true
        lastFailureReason = null
        lastFailureDefinitive = false
        lastClockWatchdogMs = SystemClock.elapsedRealtime()
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        if (reported > 0) {
            lastHardwareRate = reported
            clockMatched = reported == effectiveRate && !autoNegotiatedFallback
        } else {
            clockMatched = rateSetBefore && usbAudio.lastRateWriteTrusted && !autoNegotiatedFallback
        }
        clockRechecked = true
        if (!forceSoftwareVolume) {
            val controlId = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1
                }?.id ?: 0
            val volumeControl = UacFeatureVolume(info.connection, controlId)
            featureVolume = volumeControl
            val attached = volumeControl.attach()
            featureVolumeAttached = attached
            hardwareVolume = attached
        } else {
            featureVolume = null
            featureVolumeAttached = false
            hardwareVolume = false
        }
        Log.i(
            TAG,
            "exclusive USB started ${usbDevice.productName} ${sampleRate}Hz " +
                "alt=$alt wireBits=$wireBits reported=$reported clockMatched=$clockMatched " +
                "hardwareVolume=$hardwareVolume",
        )

        synchronized(stateLock) {
            ensureVolumeObserverLocked()
            listeningGain = listeningGainForDac()
            lastAppliedListening = Float.NaN
            applyVolumeLocked()
        }
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume listening=$listeningGain softwareGain=$softwareGainValue",
        )
        sessionSummary = "started rate=${effectiveRate}Hz alt=$alt wireBits=$wireBits reported=$reported " +
            "clockMatched=$clockMatched fallback=$autoNegotiatedFallback hardwareVolume=$hardwareVolume " +
            "maxPacket=$maxPacket interval=$dataInterval feedback=${selectedFeedback != null} feedbackPolled=$pollFeedback"
        startWriterLocked(created)
        return true
    }

    private fun waitForClockValid() {
        repeat(CLOCK_VALID_TRIES) {
            if (usbAudio.readClockValid()) return
            Thread.sleep(CLOCK_VALID_STEP_MS)
        }
        Log.w(TAG, "DAC clock not reported valid; starting stream anyway")
    }

    private fun blockReasonFor(vendorId: Int, productId: Int): String? =
        sessionBlockedDevices[vendorId.toString() + ":" + productId]

    fun clearBlockedDevices() {
        sessionBlockedDevices.clear()
        val prefs = volumePrefs ?: return
        val editor = prefs.edit()
        prefs.all?.keys?.filter { it.startsWith(KEY_BLOCK_PREFIX) }?.forEach { editor.remove(it) }
        editor.apply()
    }

    fun abortUnhealthy(reason: String) {
        synchronized(configLock) {
            if (!active) return
            val key = currentDeviceKey
            Log.w(TAG, "Exclusive USB aborted: $reason (device $key)")
            if (key != null) {
                sessionBlockedDevices[key] = reason
            }
            failLocked("stopped for safety: $reason")
        }
    }

    fun diagnosticReport(): String = buildString {
        append("LastWave USB diagnostic\n")
        append("active=$active wanted=$wanted rate=$configuredRateHz clockMatched=$clockMatched ")
        append("hardwareVolume=$hardwareVolume forceSoftwareVolume=$forceSoftwareVolume\n")
        if (sessionSummary.isNotEmpty()) append(sessionSummary).append('\n')
        lastFailureReason?.let { append("lastFailure=").append(it).append('\n') }
        append(runCatching { usbAudio.diagnosticReport() }.getOrDefault(""))
    }

    @Volatile private var currentFallbackNegotiated = false

    private fun recheckClockLocked() {
        if (clockRechecked) return
        val reported = usbAudio.readSampleRate()
        if (reported > 0) {
            lastHardwareRate = reported
            clockMatched = reported == configuredRateHz && !currentFallbackNegotiated
        }
        clockRechecked = true
    }

    private fun ensureVolumeObserverLocked() {
        if (volumeReceiverRegistered) return
        volumeReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                volumeReceiver,
                IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterVolumeObserverLocked() {
        if (!volumeReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
    }

    @Volatile private var cachedMaxVolume = -1
    @Volatile private var lastKnownStreamGain: Float = 1f
    @Volatile private var lastStreamGainReadMs = 0L

    private fun readStreamMusicGain(explicitVol: Int? = null): Float? {
        val manager = audioManager ?: return null
        val max = if (cachedMaxVolume > 0) cachedMaxVolume else {
            runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0).also {
                if (it > 0) cachedMaxVolume = it
            }
        }
        if (max <= 0) return null
        val now = SystemClock.elapsedRealtime()
        val vol = explicitVol ?: if (now - lastStreamGainReadMs < 1500L) {

            kotlin.math.round(lastKnownStreamGain * max).toInt()
        } else {
            lastStreamGainReadMs = now
            runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        }
        val gain = vol.coerceIn(0, max).toFloat() / max
        lastKnownStreamGain = gain
        return gain
    }

    private fun rememberStreamGain(gain: Float?) {
        if (gain != null && gain in MIN_REMEMBERED_GAIN..ATTACH_MAX_PIN_GAIN) {
            lastNonMaxListeningGain = gain
            runCatching { volumePrefs.edit().putFloat(KEY_LAST_NON_MAX_GAIN, gain).apply() }
        }
    }

    private fun listeningGainForDac(): Float {
        val stream = readStreamMusicGain() ?: return listeningGain
        attachMaxPinFallbackGain(stream, lastNonMaxListeningGain)?.let { remembered ->
            ignoreStreamMusicMax = true
            Log.i(
                TAG,
                "STREAM_MUSIC pinned at max on attach; using remembered listening gain $remembered",
            )
            return remembered
        }
        ignoreStreamMusicMax = false
        return stream
    }

    private fun syncListeningGainLocked() {
        listeningGain = listeningGainForDac()
        applyVolumeLocked()
    }

    private fun applyVolumeLocked() {
        val listening = listeningGain.coerceIn(0f, 1f)
        val unchanged = lastAppliedListening.isFinite() && kotlin.math.abs(listening - lastAppliedListening) < 1e-4f
        lastAppliedListening = listening
        if (hardwareVolume && !unchanged && featureVolume?.setNormalized(listening) != true) {
            hardwareVolume = false
            Log.w(TAG, "Feature Unit SET_CUR failed; falling back to perceptual software gain")
        }
        val app = pendingVolume.coerceIn(0f, 1f)
        softwareGainValue = if (hardwareVolume) app else UacFeatureVolume.perceptualLinearGain(listening) * app
    }

    private fun startWriterLocked(target: UsbAudioStream) {
        stopWriterLocked()
        writerStop = false
        flushRequested = false
        val thread = Thread({ writeLoop(target) }, "ExclusiveUsbWriter")
        writer = thread
        thread.start()
    }

    private fun stopWriterLocked() {
        writerStop = true
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            pcmLock.notifyAll()
        }
        writer?.join()
        writer = null
    }

    private fun writeLoop(target: UsbAudioStream) {
        while (!writerStop) {
            val next = synchronized(pcmLock) {
                if (!writerStop && !flushRequested && (paused || pcmQueue.isEmpty())) {
                    pcmLock.wait(20)
                }
                if (writerStop) return@synchronized null
                if (flushRequested) {
                    flushRequested = false
                    return@synchronized FLUSH_MARKER
                }
                if (paused || pcmQueue.isEmpty()) return@synchronized IDLE_MARKER
                val chunk = pcmQueue.removeFirst()
                queuedBytes = (queuedBytes - chunk.byteSize).coerceAtLeast(0)
                chunk
            } ?: break
            if (next === FLUSH_MARKER) {
                runCatching { target.flush() }
                mediaTimeBaseFrames = target.framesWritten
                continue
            }
            if (next === IDLE_MARKER) {
                runCatching { target.pump() }
                continue
            }
            if (!target.isAlive) break
            if (!verifyClockWatchdog(force = false)) break
            writingInProgress = true
            lastWriteElapsedMs = SystemClock.elapsedRealtime()
            try {
                writeScaled(target, next)
            } finally {
                writingInProgress = false
                lastWriteElapsedMs = SystemClock.elapsedRealtime()
            }
            synchronized(pcmLock) {
                next.floats?.let { if (spareFloats.size < MAX_SPARE_CHUNKS) spareFloats.addLast(it) }
                next.raw?.let { if (spareBytes.size < MAX_SPARE_CHUNKS) spareBytes.addLast(it) }
            }
        }
    }

    private fun writeScaled(target: UsbAudioStream, chunk: QueuedPcm) {
        val gain = softwareGain()
        val fromGain = lastPcmGain
        lastPcmGain = gain
        val channels = activeChannels

        val rampFrames = (configuredRateHz / 100).coerceAtLeast(1)
        chunk.floats?.let { floats ->
            ExclusivePcmGain.applyRamped(floats, fromGain, gain, channels, rampFrames)
            target.write(floats)
            return
        }
        val bytes = chunk.raw ?: return
        val attenuate = gain >= 0f && (gain < 1f - 1e-6f || fromGain < 1f - 1e-6f)
        if (attenuate && chunk.encoding == RAW_PCM16 && activeWireBits > 16) {

            target.writeRaw(scalePcm16To24(bytes, fromGain, gain, channels, rampFrames), RAW_PCM24)
            return
        }
        if (attenuate) scalePcmRamped(bytes, chunk.encoding, fromGain, gain, channels, rampFrames)
        target.writeRaw(bytes, chunk.encoding)
    }

    private fun stopStreamLocked() {
        val running = stream

        runCatching { running?.stop() }
        stopWriterLocked()

        // safe. Releasing it while the writer was still alive was a use-after-free.
        if (running != null) {
            runCatching { running.drainUrbs() }
            runCatching { running.release() }
        }
        stream = null
        synchronized(stateLock) {
            active = false
        }
    }

    private fun teardownLocked(closeDevice: Boolean) {
        stopStreamLocked()
        if (closeDevice) {
            runCatching { featureVolume?.setNormalized(1.0f) }
            runCatching { usbAudio.closeDevice() }
            connection = null
        }
        synchronized(stateLock) {
            featureVolume = null
            featureVolumeAttached = false
            volumeProbed = false
            ignoreStreamMusicMax = false
            hardwareVolume = false
            clockMatched = false
            currentFallbackNegotiated = false
            configuredRateHz = 0
            active = false
            softwareGainValue = 1f
            lastAppliedListening = Float.NaN
            startMediaTimeNeedsInit = true
            startMediaTimeUs = 0L
            mediaTimeBaseFrames = 0L
            paused = false
            if (closeDevice) unregisterVolumeObserverLocked()
        }
    }

    private class QueuedPcm(
        val floats: FloatArray?,
        val raw: ByteArray?,
        val encoding: Int,
        val byteSize: Int,
    )

    private fun verifyClockWatchdog(force: Boolean): Boolean {
        val expected = configuredRateHz
        if (!active || expected <= 0) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastClockWatchdogMs < CLOCK_WATCHDOG_MS) return true
        lastClockWatchdogMs = now
        val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
        if (reported > 0 && reported != expected) {
            Log.w(TAG, "DAC clock drifted: expected=${expected}Hz reported=${reported}Hz; stopping exclusive stream")
            lastFailureReason = "DAC clock drifted to ${reported}Hz"
            lastFailureDefinitive = true
            runCatching { stream?.stop() }
            active = false
            return false
        }
        return true
    }

    private fun failLocked(reason: String, definitive: Boolean = false): Boolean {
        Log.w(TAG, "Exclusive USB fail-open: $reason")
        lastFailureReason = reason
        lastFailureDefinitive = definitive
        teardownLocked(closeDevice = true)
        return false
    }

    private companion object {
        const val TAG = "ExclusiveUsb"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val KEY_LAST_NON_MAX_GAIN = "last_non_max_stream_gain"
        const val KEY_BLOCK_PREFIX = "blocked_dac_"
        const val PLL_SETTLE_MS = 50L
        const val CLOCK_VALID_TRIES = 40
        const val CLOCK_VALID_STEP_MS = 5L
        const val RAW_PCM16 = 2
        const val RAW_PCM24 = 0x15
        const val RAW_PCM32 = 0x16
        const val MAX_QUEUED_BYTES = 512 * 1024
        const val MAX_CHANNELS = 8
        const val MICROS_PER_SECOND = 1_000_000L
        const val POSITION_NOT_SET = Long.MIN_VALUE
        const val CLOCK_WATCHDOG_MS = 5_000L
        const val MAX_SPARE_CHUNKS = 4
        val FLUSH_MARKER = QueuedPcm(null, null, -1, 0)
        val IDLE_MARKER = QueuedPcm(null, null, -2, 0)

        fun isoPacketBytes(raw: Int): Int {
            if (raw <= 0) return 0
            val extra = (raw shr 11) and 0x3
            val size = raw and 0x7FF
            return if (extra > 0) size * (1 + extra) else raw
        }

        fun minIsoPacketBytes(rateHz: Int, channels: Int, bitDepth: Int, interval: Int = 1): Int {
            val bpf = ((bitDepth + 7) / 8).coerceAtLeast(1) * channels.coerceIn(1, 8)
            val microframes = if (interval > 1) 1 shl (interval - 1).coerceAtMost(4) else 1
            val frames = ((rateHz + 7999) / 8000) * microframes + 1
            return frames * bpf
        }

        fun sourceBitDepth(encoding: Int): Int = when (encoding) {
            RAW_PCM16, RAW_PCM16 -> 16
            RAW_PCM24, RAW_PCM24 -> 24
            RAW_PCM32, RAW_PCM32 -> 32
            else -> 0
        }

        fun writeRawEncoding(encoding: Int): Int = when (encoding) {
            RAW_PCM16, RAW_PCM16 -> RAW_PCM16
            RAW_PCM24, RAW_PCM24 -> RAW_PCM24
            RAW_PCM32, RAW_PCM32 -> RAW_PCM32
            else -> -1
        }

        internal fun scalePcm16To24(
            src: ByteArray,
            fromGain: Float,
            toGain: Float,
            channels: Int,
            rampFrames: Int,
        ): ByteArray {
            val samples = src.size / 2
            val out = ByteArray(samples * 3)
            val a = fromGain.coerceIn(0f, 1f).toDouble() * 256.0
            val b = toGain.coerceIn(0f, 1f).toDouble() * 256.0
            val ch = channels.coerceAtLeast(1)
            var i = 0
            var o = 0
            var sampleIndex = 0
            while (i + 1 < src.size) {
                val frame = sampleIndex / ch
                val g = if (a == b || frame >= rampFrames) b else a + (b - a) * frame / rampFrames
                sampleIndex++
                val sample = ((src[i].toInt() and 0xFF) or (src[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = kotlin.math.round(sample * g).toInt().coerceIn(-0x800000, 0x7FFFFF)
                out[o] = (scaled and 0xFF).toByte()
                out[o + 1] = ((scaled shr 8) and 0xFF).toByte()
                out[o + 2] = ((scaled shr 16) and 0xFF).toByte()
                i += 2
                o += 3
            }
            return out
        }

        private fun scalePcmRamped(
            bytes: ByteArray,
            rawEncoding: Int,
            fromGain: Float,
            toGain: Float,
            channels: Int,
            rampFrames: Int,
        ) {
            val bytesPerSample = when (rawEncoding) {
                RAW_PCM16 -> 2
                RAW_PCM24 -> 3
                RAW_PCM32 -> 4
                else -> return
            }
            val frameBytes = bytesPerSample * channels.coerceAtLeast(1)
            val totalFrames = bytes.size / frameBytes
            if (fromGain == toGain || totalFrames == 0) {
                if (toGain < 1f - 1e-6f) scalePcmBytes(bytes, rawEncoding, toGain)
                return
            }
            val rampEnd = minOf(totalFrames, rampFrames)
            var frame = 0
            while (frame < totalFrames) {
                val inRamp = frame < rampEnd
                val n = if (inRamp) minOf(RAMP_BLOCK_FRAMES, rampEnd - frame) else totalFrames - frame
                val g = if (inRamp) {
                    fromGain + (toGain - fromGain) * ((frame + n / 2f) / rampFrames)
                } else {
                    toGain
                }
                if (g < 1f - 1e-6f) {
                    val start = frame * frameBytes
                    val end = (frame + n) * frameBytes
                    val segment = bytes.copyOfRange(start, end)
                    scalePcmBytes(segment, rawEncoding, g)
                    segment.copyInto(bytes, start)
                }
                frame += n
            }
        }

        private const val RAMP_BLOCK_FRAMES = 32

        private fun scalePcmBytes(bytes: ByteArray, rawEncoding: Int, gain: Float) {
            if (gain <= 0f) {
                bytes.fill(0)
                return
            }
            when (rawEncoding) {
                RAW_PCM16 -> {
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8))
                            .toShort().toInt()
                        val scaled = (sample * gain + if (sample >= 0) 0.5f else -0.5f).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        i += 2
                    }
                }
                RAW_PCM24 -> {
                    var i = 0
                    while (i + 2 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        var sample = (b2 shl 16) or (b1 shl 8) or b0
                        if ((sample and 0x800000) != 0) {
                            sample = (sample shl 8) shr 8
                        }
                        val scaled = (sample * gain.toDouble() + if (sample >= 0) 0.5 else -0.5).toInt()
                            .coerceIn(-0x800000, 0x7FFFFF)
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        i += 3
                    }
                }
                RAW_PCM32 -> {
                    var i = 0
                    while (i + 3 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        val b3 = bytes[i + 3].toInt() and 0xFF
                        val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        val scaled = (sample.toDouble() * gain.toDouble()).toLong()
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        bytes[i + 3] = ((scaled shr 24) and 0xFF).toByte()
                        i += 4
                    }
                }
            }
        }
    }
}

internal fun attachMaxPinFallbackGain(streamGain: Float, rememberedGain: Float): Float? {
    if (streamGain < ATTACH_MAX_PIN_GAIN) return null
    if (!rememberedGain.isFinite()) return null
    if (rememberedGain < MIN_REMEMBERED_GAIN || rememberedGain > ATTACH_MAX_PIN_GAIN) return null
    return rememberedGain
}

private const val ATTACH_MAX_PIN_GAIN = 0.999f

private const val MIN_REMEMBERED_GAIN = 0.01f
