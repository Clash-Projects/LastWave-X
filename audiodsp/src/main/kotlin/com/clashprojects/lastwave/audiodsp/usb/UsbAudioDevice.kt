/*
 * Copyright (c) 2026 Marcelo Silva
 * SPDX-License-Identifier: MIT
 */

package com.clashprojects.lastwave.audiodsp.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null
    private var claimedControlInterface: UsbInterface? = null

    @Volatile private var playbackInterfaceId: Int = -1
    @Volatile private var audioControlInterfaceId: Int = 0

    @Volatile var currentUacVersion: Int = 0
        private set

    private var parsedAltRates: Map<Int, AltRates> = emptyMap()
    private var parsedAltChannels: Map<Int, Int> = emptyMap()
    @Volatile var lastRateWriteTrusted: Boolean = false
        private set

    private class AltRates(val discrete: List<Int>, val min: Int, val max: Int) {
        fun accepts(rate: Int): Boolean =
            if (discrete.isNotEmpty()) rate in discrete else min in 1..rate && rate <= max
        override fun toString(): String =
            if (discrete.isNotEmpty()) discrete.joinToString("/") else "$min-$max"
    }

    private val trace = StringBuilder()
    private fun trace(line: String) {
        synchronized(trace) {
            if (trace.length > 6000) trace.setLength(0)
            trace.append(line).append('\n')
        }
        Log.i(TAG, line)
    }

    @Volatile var lastOpenError: String? = null
        private set

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"
        private const val CONTROL_TIMEOUT_MS = 100

        @Volatile
        private var instance: UsbAudioDevice? = null

        private val CONTINUOUS_FEEDBACK_DEVICES = setOf(
            0x2fc6 to 0xf82d,
            0x2d87 to 0x003b,
        )

        fun needsContinuousFeedback(vendorId: Int, productId: Int): Boolean =
            (vendorId to productId) in CONTINUOUS_FEEDBACK_DEVICES

        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }

    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)

                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2) {
                    Log.i(TAG, "Found USB audio device: ${device.productName} " +
                            "(vendor=0x${device.vendorId.toString(16)}, " +
                            "product=0x${device.productId.toString(16)})")
                    return device
                }
            }
        }
        Log.d(TAG, "No USB audio device found")
        return null
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Permission already granted for ${device.productName}")
            callback(true)
            return
        }

        val intent = Intent(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        intent.setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                intent,
                PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == context.packageName + ACTION_USB_PERMISSION_SUFFIX) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result: granted=$granted for ${device.productName}")
                    context.unregisterReceiver(this)
                    callback(granted)
                }
            }
        }

        val filter = IntentFilter(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "Permission requested for ${device.productName}")
    }

    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null

    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {

        val cached = cachedDeviceInfo
        if (cached != null && connection != null) {
            Log.i(TAG, "Device already open, reusing fd=${cached.fd}")
            return cached
        }

        closeDevice()
        lastOpenError = null
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.productName}")
            lastOpenError = "Android refused to open the device"
            return null
        }

        playbackInterfaceId = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { iface ->
                iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2 &&
                    (0 until iface.endpointCount).any { e ->
                        val ep = iface.getEndpoint(e)
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT
                    }
            }?.id ?: -1
        Log.i(TAG, "Playback AudioStreaming interface: $playbackInterfaceId")

        if (currentDevice?.vendorId != device.vendorId || currentDevice?.productId != device.productId) {
            cachedSupportedRates = emptyList()
        }

        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 && isPlaybackId(iface.id)) {
                altSettingCount++

                if (iface.endpointCount > 0 && streamingInterface == null) {
                    streamingInterface = iface

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        when {

                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> {
                                endpointOut = ep.address
                                maxPacketSize = ep.maxPacketSize
                                Log.i(TAG, "Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }

                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Log.i(TAG, "Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming interface/endpoint found")
            lastOpenError = "no playback endpoint in the USB descriptors"
            conn.close()
            return null
        }

        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            audioControlInterfaceId = controlInterface.id
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
            claimedControlInterface = if (claimed) controlInterface else null
        } else {
            audioControlInterfaceId = 0
            claimedControlInterface = null
        }

        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
            lastOpenError = "Android kept the DAC (interface ${streamingInterface.id} could not be claimed)"
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 && isPlaybackId(it.id) }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Log.i(TAG, "Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Log.d(TAG, "  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Log.i(TAG, "Device opened: ${device.productName}, fd=$fd, " +
                "iface=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, " +
                "maxPacket=$maxPacketSize, altSettings=$altSettingCount")

        connection = conn
        currentDevice = device

        val uacVersion = parseUacVersion(conn)
        currentUacVersion = uacVersion
        synchronized(trace) { trace.setLength(0) }
        trace("device=${device.productName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} uac=$uacVersion playbackIface=$playbackInterfaceId speed=${UsbAudioStream.usbSpeedName(conn.fileDescriptor)}")
        val clockSourceId = parseClockSourceId(conn)
        val (bestAlt, bestBits) = parseBestAltSetting(conn, uacVersion)
        val bestAltInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    it.alternateSetting == bestAlt &&
                    isPlaybackId(it.id)
            }
        val bestAltOut = bestAltInterface?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }
        val bestAltFeedback = bestAltInterface?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        if (bestAltOut != null) {
            endpointOut = bestAltOut.address
            maxPacketSize = bestAltOut.maxPacketSize
        }
        if (bestAltFeedback != null) endpointFeedback = bestAltFeedback.address
        val dataInterval = bestAltOut?.interval ?: 1
        val feedbackPacketSize = bestAltFeedback?.maxPacketSize ?: 4
        val feedbackInterval = bestAltFeedback?.interval ?: dataInterval
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "bestAlt=$bestAlt, bestBits=$bestBits, uacVersion=$uacVersion, " +
                "epOut=0x${endpointOut.toString(16)}, maxPacket=$maxPacketSize, " +
                "dataInterval=$dataInterval, epFb=0x${endpointFeedback.toString(16)}, " +
                "fbPacket=$feedbackPacketSize, fbInterval=$feedbackInterval")

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                dataInterval = dataInterval,
                feedbackPacketSize = feedbackPacketSize,
                feedbackInterval = feedbackInterval,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits,
                uacVersion = uacVersion,
        )
        cachedDeviceInfo = info
        return info
    }

    fun resetAndReopen() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        Log.i(TAG, "Performing REAL USBDEVFS_RESET on fd=$fd...")

        val ret = UsbAudioStream.nativeUsbReset(fd)
        Log.i(TAG, "USBDEVFS_RESET result: $ret")

        cachedDeviceInfo = null
        claimedInterface = null
        claimedControlInterface = null

    }

    private var parsedClockSourceIds: List<Int> = emptyList()

    private var parsedClockSelectorId: Int = -1
    private var parsedClockSelectorPins: List<Int> = emptyList()

    private var playbackClockSourceIds: List<Int> = emptyList()

    @Volatile private var activeClockSourceId: Int = -1

    private val clockSourceRateMap = mutableMapOf<Int, Set<Int>>()

    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1
        val sources = mutableListOf<Int>()
        var selectorId = -1
        val selectorPins = mutableListOf<Int>()

        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF

                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF

                if (bDescriptorSubtype == 0x0A && bLength >= 5) {
                    val bClockID = raw[i + 3].toInt() and 0xFF
                    if (bClockID > 0 && bClockID !in sources) {
                        sources += bClockID
                        Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                    }
                } else if (bDescriptorSubtype == 0x0B && bLength >= 5 && selectorId < 0) {

                    selectorId = raw[i + 3].toInt() and 0xFF
                    val nrPins = raw[i + 4].toInt() and 0xFF
                    for (p in 0 until nrPins) {
                        val off = i + 5 + p
                        if (off < i + bLength) {
                            selectorPins += raw[off].toInt() and 0xFF
                        }
                    }
                    Log.i(TAG, "parseClockSourceId: found CLOCK_SELECTOR id=0x${selectorId.toString(16)} pins=$selectorPins")
                }
            }

            i += bLength
        }

        val playbackClocks = resolvePlaybackClockSources(raw, sources)

        parsedClockSourceIds = (playbackClocks + sources).distinct()
        playbackClockSourceIds = playbackClocks
        parsedClockSelectorId = selectorId
        parsedClockSelectorPins = selectorPins
        if (playbackClocks.isNotEmpty()) {
            Log.i(TAG, "parseClockSourceId: playback clock(s)=${playbackClocks.map { "0x" + it.toString(16) }} of ${sources.map { "0x" + it.toString(16) }}")
            trace("playbackClock=${playbackClocks.joinToString(",") { "0x" + it.toString(16) }} allClocks=${sources.joinToString(",") { "0x" + it.toString(16) }}")
        }
        val primary = parsedClockSourceIds.firstOrNull() ?: -1
        activeClockSourceId = primary
        if (primary < 0) {
            Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        }
        return primary
    }

    private fun resolvePlaybackClockSources(raw: ByteArray, allSources: List<Int>): List<Int> = runCatching {
        if (allSources.size <= 1) return@runCatching allSources
        val inputTerminalClock = mutableMapOf<Int, Int>()
        val multiplierSource = mutableMapOf<Int, Int>()
        val selectorInputs = mutableMapOf<Int, List<Int>>()
        var terminalLink = -1
        var inAudioControl = false
        var currentIface = -1
        var i = 0
        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x04 && bLength >= 9) {
                currentIface = raw[i + 2].toInt() and 0xFF
                val cls = raw[i + 5].toInt() and 0xFF
                val sub = raw[i + 6].toInt() and 0xFF
                inAudioControl = cls == 1 && sub == 1
            } else if (type == 0x24 && bLength >= 4) {
                val subtype = raw[i + 2].toInt() and 0xFF
                if (inAudioControl) {
                    val id = raw[i + 3].toInt() and 0xFF
                    when (subtype) {

                        0x02 -> if (bLength >= 8) inputTerminalClock[id] = raw[i + 7].toInt() and 0xFF

                        0x0B -> if (bLength >= 5) {
                            val n = raw[i + 4].toInt() and 0xFF
                            selectorInputs[id] = (0 until n).mapNotNull { p ->
                                (i + 5 + p).takeIf { it < i + bLength }?.let { raw[it].toInt() and 0xFF }
                            }
                        }

                        0x0C -> if (bLength >= 5) multiplierSource[id] = raw[i + 4].toInt() and 0xFF
                    }
                } else if (subtype == 0x01 && bLength >= 4 && terminalLink < 0 &&
                    playbackInterfaceId >= 0 && currentIface == playbackInterfaceId
                ) {

                    terminalLink = raw[i + 3].toInt() and 0xFF
                }
            }
            i += bLength
        }
        val start = inputTerminalClock[terminalLink] ?: return@runCatching emptyList()
        val result = linkedSetOf<Int>()
        val seen = mutableSetOf<Int>()
        fun walk(id: Int) {
            if (!seen.add(id)) return
            when {
                id in allSources -> result += id
                id in multiplierSource -> walk(multiplierSource.getValue(id))
                id in selectorInputs -> selectorInputs.getValue(id).forEach { walk(it) }
            }
        }
        walk(start)
        result.toList()
    }.getOrDefault(emptyList())

    private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    private fun parseBestAltSetting(conn: UsbDeviceConnection, uacVersion: Int): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()
        val altRates = HashMap<Int, AltRates>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceNumber = raw[i + 2].toInt() and 0xFF
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2) &&
                    isPlaybackId(bInterfaceNumber)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x01 && uacVersion != 100 && currentAlt > 0 && bLength >= 11) {
                    val nrCh = raw[i + 10].toInt() and 0xFF
                    if (nrCh in 1..16) altChannels[currentAlt] = nrCh
                } else if (bDescriptorSubtype == 0x02) {
                    if (uacVersion == 100 && currentAlt > 0) {
                        val nrCh = raw[i + 4].toInt() and 0xFF
                        if (nrCh in 1..16) altChannels[currentAlt] = nrCh
                    }
                    val bSubslotSize: Int
                    val bBitResolution: Int
                    if (uacVersion == 100) {

                        bSubslotSize = if (bLength >= 7) raw[i + 5].toInt() and 0xFF else 2
                        bBitResolution = if (bLength >= 7) raw[i + 6].toInt() and 0xFF else 16
                    } else {

                        bSubslotSize = raw[i + 4].toInt() and 0xFF
                        bBitResolution = raw[i + 5].toInt() and 0xFF
                    }
                    val containerBits = bSubslotSize * 8
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution containerBits=$containerBits")
                    if (uacVersion == 100 && currentAlt > 0 && bLength >= 8) {
                        val samFreqType = raw[i + 7].toInt() and 0xFF
                        val discrete = mutableListOf<Int>()
                        var lo = 0
                        var hi = 0
                        if (samFreqType == 0 && bLength >= 14) {
                            lo = readLeInt24(raw, i + 8)
                            hi = readLeInt24(raw, i + 11)
                        } else {
                            for (k in 0 until samFreqType) {
                                val off = i + 8 + k * 3
                                if (off + 2 < i + bLength) discrete += readLeInt24(raw, off)
                            }
                        }
                        altRates[currentAlt] = AltRates(discrete, lo, hi)
                    }
                    trace("alt=$currentAlt bits=$bBitResolution container=$containerBits rates=${altRates[currentAlt] ?: "-"}")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, containerBits))
                    }
                    if (containerBits > bestBits && currentAlt > 0) {
                        bestBits = containerBits
                        bestAlt = currentAlt
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        parsedAltRates = altRates
        parsedAltChannels = altChannels
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
        return Pair(bestAlt, bestBits)
    }

    fun parseUacVersion(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return 0
        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass    = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 5) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x01) {

                    val bcdLo = raw[i + 3].toInt() and 0xFF
                    val bcdHi = raw[i + 4].toInt() and 0xFF
                    val bcd = (bcdHi shl 8) or bcdLo
                    val version = when (bcdHi) {
                        1 -> 100
                        2 -> 200
                        3 -> 300
                        else -> when (bcd) {
                            0x0100 -> 100
                            0x0200 -> 200
                            0x0300 -> 300
                            else   -> 0
                        }
                    }
                    Log.i(TAG, "parseUacVersion: bcdADC=0x${bcd.toString(16).padStart(4,'0')} -> UAC $version")
                    return version
                }
            }

            i += bLength
        }
        Log.w(TAG, "parseUacVersion: no AC Header descriptor found, returning 0")
        return 0
    }

    fun findAltSetting(targetBitDepth: Int, rateHz: Int, channelCount: Int = 2): Pair<Int, Int> {
        val channelFiltered = if (parsedAltChannels.isNotEmpty() && channelCount > 0) {
            parsedAltSettings.filter { (alt, _) -> (parsedAltChannels[alt] ?: channelCount) == channelCount }
                .ifEmpty { parsedAltSettings }
        } else {
            parsedAltSettings
        }
        val rated = parsedAltRates
        val usable = if (rated.isNotEmpty() && rateHz > 0) {
            channelFiltered.filter { (alt, _) -> rated[alt]?.accepts(rateHz) ?: true }
                .ifEmpty {
                    trace("findAltSetting: no alt states $rateHz Hz for ${channelCount}ch; falling back")
                    channelFiltered
                }
        } else {
            channelFiltered
        }
        val pick = if (usable.isNotEmpty()) {
            usable.firstOrNull { it.second == targetBitDepth }
                ?: usable.filter { it.second > targetBitDepth }.minByOrNull { it.second }
                ?: usable.maxByOrNull { it.second }!!
        } else {
            findAltSettingForBitDepth(targetBitDepth)
        }
        trace("findAltSetting(bits=$targetBitDepth, rate=$rateHz, ch=$channelCount) -> alt=${pick.first} wireBits=${pick.second}")
        return pick
    }

    fun statedRatesFor(alt: Int): String = parsedAltRates[alt]?.toString() ?: ""

    fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> {
        if (parsedAltSettings.isEmpty()) {
            val info = cachedDeviceInfo ?: return Pair(1, 16)
            return Pair(info.bestAltSetting, info.bestBitDepth)
        }

        val exact = parsedAltSettings.firstOrNull { it.second == targetBitDepth }
        if (exact != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
        return best
    }

    private var cachedSupportedRates: List<Int> = emptyList()

    private fun isPlaybackId(interfaceNumber: Int): Boolean =
        playbackInterfaceId < 0 || interfaceNumber == playbackInterfaceId

    fun closeDevice() {
        cachedDeviceInfo = null
        clockSourceRateMap.clear()
        parsedClockSourceIds = emptyList()
        playbackClockSourceIds = emptyList()
        parsedClockSelectorId = -1
        parsedClockSelectorPins = emptyList()
        activeClockSourceId = -1
        parsedAltChannels = emptyMap()
        lastRateWriteTrusted = false
        val conn = connection
        val fd = conn?.fileDescriptor ?: -1
        claimedControlInterface?.let { iface ->
            runCatching { conn?.releaseInterface(iface) }
            if (fd >= 0) {
                runCatching { UsbAudioStream.nativeUsbAttachKernelDriver(fd, iface.id) }
            }
            claimedControlInterface = null
        }
        claimedInterface?.let { iface ->
            runCatching { conn?.releaseInterface(iface) }
            if (fd >= 0) {
                runCatching { UsbAudioStream.nativeUsbAttachKernelDriver(fd, iface.id) }
            }
            claimedInterface = null
        }
        runCatching { conn?.close() }
        connection = null
        currentDevice = null
        playbackInterfaceId = -1
        audioControlInterfaceId = 0
        Log.i(TAG, "USB device closed")
    }

    fun querySupportedSampleRates(): List<Int> {
        if (cachedSupportedRates.isNotEmpty()) return cachedSupportedRates
        val conn = connection ?: return emptyList()
        val rates = linkedSetOf<Int>()

        // 1. Probe UAC 2.0 Clock Source GET_RANGE (bRequest = 0x02, wValue = 0x0100).
        //    Never on UAC1: those IDs would address unrelated units.
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val candidateIds = if (currentUacVersion == 100) emptyList() else buildList {
            if (parsedClockSourceIds.isNotEmpty()) {
                addAll(parsedClockSourceIds)
            } else if (detectedId > 0) {
                add(detectedId)
            } else {
                addAll(listOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29, 0x06, 0x07, 0x08, 0x10, 0x20))
            }
        }.distinct()
        val rangeBuf = ByteArray(258)
        val stopOnFirstHit = parsedClockSourceIds.size <= 1
        for (csId in candidateIds) {
            val wIndex = (csId shl 8) or (audioControlInterfaceId and 0xFF)
            val ret = conn.controlTransfer(
                0xA1,
                0x02,   // GET_RANGE
                0x0100,
                wIndex,
                rangeBuf,
                rangeBuf.size,
                CONTROL_TIMEOUT_MS,
            )
            if (ret >= 14) {
                val csRates = linkedSetOf<Int>()
                val count = (rangeBuf[0].toInt() and 0xFF) or ((rangeBuf[1].toInt() and 0xFF) shl 8)
                val maxEntries = ((ret - 2) / 12).coerceAtMost(count)
                for (idx in 0 until maxEntries) {
                    val base = 2 + idx * 12
                    val dMin = readLeInt32(rangeBuf, base)
                    val dMax = readLeInt32(rangeBuf, base + 4)
                    val dRes = readLeInt32(rangeBuf, base + 8)
                    if (dMin in 8000..1536000 && dMax >= dMin) {
                        csRates += supportedStandardRatesInRange(dMin, dMax, dRes)
                    }
                }
                if (csRates.isNotEmpty()) {
                    clockSourceRateMap[csId] = csRates
                    rates.addAll(csRates)
                    if (stopOnFirstHit && csId !in parsedClockSourceIds) break
                }
            }
        }

        val raw = conn.rawDescriptors
        if (raw != null) {
            var i = 0
            var inAudioStreaming = false
            while (i + 1 < raw.size) {
                val bLength = raw[i].toInt() and 0xFF
                if (bLength < 2 || i + bLength > raw.size) break
                val bDescriptorType = raw[i + 1].toInt() and 0xFF
                if (bDescriptorType == 0x04 && bLength >= 9) {
                    val num = raw[i + 2].toInt() and 0xFF
                    val cls = raw[i + 5].toInt() and 0xFF
                    val sub = raw[i + 6].toInt() and 0xFF
                    inAudioStreaming = (cls == 1 && sub == 2) && isPlaybackId(num)
                } else if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 8) {
                    val subtype = raw[i + 2].toInt() and 0xFF
                    if (subtype == 0x02) {
                        val samFreqType = raw[i + 7].toInt() and 0xFF
                        if (samFreqType == 0 && bLength >= 14) {
                            val lower = readLeInt24(raw, i + 8)
                            val upper = readLeInt24(raw, i + 11)
                            if (lower in 8000..1536000) rates += lower
                            if (upper in 8000..1536000) rates += upper
                            rates += supportedStandardRatesInRange(lower, upper, 0)
                        } else if (samFreqType > 0) {
                            for (k in 0 until samFreqType) {
                                val off = i + 8 + k * 3
                                if (off + 2 < i + bLength) {
                                    val freq = readLeInt24(raw, off)
                                    if (freq in 8000..1536000) rates += freq
                                }
                            }
                        }
                    }
                }
                i += bLength
            }
        }

        val hasDedicated441Crystal = parsedClockSelectorId > 0 ||
            parsedClockSourceIds.size >= 2 ||
            clockSourceRateMap.size >= 2 ||
            rates.none { it % 48000 == 0 }
        if (!hasDedicated441Crystal) {
            val removed = rates.filter { it > 44100 && it % 44100 == 0 }
            if (removed.isNotEmpty()) {
                rates.removeAll(removed.toSet())
                Log.i(
                    TAG,
                    "querySupportedSampleRates: single-clock DAC lacks dedicated 44.1k crystal " +
                        "(sources=${parsedClockSourceIds.size}, selector=$parsedClockSelectorId); " +
                        "routing $removed to 48k-family hardware clock via soxr",
                )
            }
        }

        val sorted = rates.sorted()
        if (sorted.isNotEmpty()) {
            cachedSupportedRates = sorted
            Log.i(TAG, "querySupportedSampleRates: DAC hardware clock rates=$sorted (perClock=$clockSourceRateMap)")
        }
        return sorted
    }

    private fun readLeInt24(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16)

    private fun readLeInt32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun selectClockSelectorPinIfNeeded(conn: UsbDeviceConnection, targetClockSourceId: Int) {
        if (parsedClockSelectorId <= 0 || parsedClockSelectorPins.isEmpty()) return
        val pinZeroIndex = parsedClockSelectorPins.indexOf(targetClockSourceId)
        if (pinZeroIndex < 0) return
        val pinNumber = (pinZeroIndex + 1).toByte() // UAC2 Clock Selector pins are 1-based
        val pinData = byteArrayOf(pinNumber)
        val wIndex = (parsedClockSelectorId shl 8) or (audioControlInterfaceId and 0xFF)
        val ret = conn.controlTransfer(
            0x21,
            0x01,   // SET_CUR
            0x0100,
            wIndex,
            pinData,
            pinData.size,
            CONTROL_TIMEOUT_MS,
        )
        Log.i(TAG, "selectClockSelectorPin: selector=0x${parsedClockSelectorId.toString(16)} -> pin=$pinNumber (csId=0x${targetClockSourceId.toString(16)}, ret=$ret)")
    }

    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        if (currentUacVersion == 100) {
            // UAC1 has no Clock Source entities. Blasting UAC2 SET_CUR at

            // never sets the rate. UAC1 sets it on the streaming endpoint.
            val ok = setUac1EndpointRate(conn, sampleRateHz)
            lastRateWriteTrusted = ok
            trace("setSampleRate($sampleRateHz) UAC1 endpoint: ${if (ok) "OK" else "FAILED"}")
            return ok
        }

        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()

        // Prioritize the Clock Source entity whose GET_RANGE explicitly includes sampleRateHz

        val matchingClockSources = clockSourceRateMap.entries
            .filter { sampleRateHz in it.value }
            .sortedByDescending { it.value.size }
            .map { it.key }
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val playbackClocks = playbackClockSourceIds
        val clockSourceIds = buildList {
            if (playbackClocks.isNotEmpty()) {

                addAll(matchingClockSources.filter { it in playbackClocks })
                addAll(playbackClocks)
            } else if (matchingClockSources.isNotEmpty()) {
                addAll(matchingClockSources)
            } else if (parsedClockSourceIds.isNotEmpty()) {
                addAll(parsedClockSourceIds)
            } else if (detectedId > 0) {
                add(detectedId)
            } else {
                addAll(
                    listOf(
                        0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                        0x28, 0x29, 0x2A, 0x06, 0x07, 0x08,
                        0x10, 0x11, 0x12, 0x20, 0x21, 0x22,
                    )
                )
            }
        }.distinct()

        for (csId in clockSourceIds) {
            selectClockSelectorPinIfNeeded(conn, csId)
            val wIndex = (csId shl 8) or (audioControlInterfaceId and 0xFF)
            val ret = conn.controlTransfer(
                    0x21,    // bmRequestType: Host-to-Device, Class, Interface
                    0x01,    // bRequest: SET_CUR
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    CONTROL_TIMEOUT_MS
            )
            if (ret == data.size) {
                activeClockSourceId = csId
                lastRateWriteTrusted = csId in parsedClockSourceIds ||
                    csId in playbackClockSourceIds ||
                    clockSourceRateMap[csId]?.contains(sampleRateHz) == true
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS with clockSourceId=0x${csId.toString(16)} (wIndex=0x${wIndex.toString(16)}, ret=$ret, trusted=$lastRateWriteTrusted)")
                return true
            }
        }

        // UAC 1.0 fallback: Endpoint SAMPLING_FREQ_CONTROL (bmRequestType=0x22, bRequest=0x01, wValue=0x0100)
        val epOut = cachedDeviceInfo?.endpointOutAddress ?: -1
        if (epOut > 0) {
            val uac1Data = byteArrayOf(
                (sampleRateHz and 0xFF).toByte(),
                ((sampleRateHz shr 8) and 0xFF).toByte(),
                ((sampleRateHz shr 16) and 0xFF).toByte(),
            )
            val ret = conn.controlTransfer(
                0x22,
                0x01,
                0x0100,
                epOut,
                uac1Data,
                uac1Data.size,
                CONTROL_TIMEOUT_MS,
            )
            if (ret == uac1Data.size) {
                lastRateWriteTrusted = true
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS via UAC1 endpoint 0x${epOut.toString(16)}")
                return true
            }
        }

        Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
        return false
    }

    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        if (currentUacVersion == 100) {
            val rate = readUac1EndpointRate(conn)
            trace("readSampleRate UAC1 endpoint: ${if (rate > 0) "$rate Hz" else "not supported by the DAC"}")
            return rate
        }
        val data = ByteArray(4)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = buildList {
            if (activeClockSourceId > 0) add(activeClockSourceId)
            if (parsedClockSourceIds.isNotEmpty()) {
                addAll(parsedClockSourceIds)
            } else if (detectedId > 0) {
                add(detectedId)
            } else {
                addAll(listOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29))
            }
        }.distinct()
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or (audioControlInterfaceId and 0xFF)
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR (actually CUR is 0x01 for both)
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    CONTROL_TIMEOUT_MS
            )
            if (ret >= 4) {
                val rate = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} " +
                        "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
                return rate
            }
        }

        val epOut = cachedDeviceInfo?.endpointOutAddress ?: -1
        if (epOut > 0) {
            val uac1Data = ByteArray(3)
            val ret = conn.controlTransfer(
                0xA2,
                0x81, // UAC1 GET_CUR
                0x0100,
                epOut,
                uac1Data,
                uac1Data.size,
                CONTROL_TIMEOUT_MS,
            )
            if (ret >= 3) {
                val rate = readLeInt24(uac1Data, 0)
                if (rate in 8000..1536000) {
                    Log.i(TAG, "readSampleRate: UAC1 endpoint 0x${epOut.toString(16)} returned $rate Hz")
                    return rate
                }
            }
        }

        Log.w(TAG, "readSampleRate: all GET_CUR attempts failed")
        return -1
    }

    private fun setUac1EndpointRate(conn: UsbDeviceConnection, rate: Int): Boolean {
        val epOut = cachedDeviceInfo?.endpointOutAddress ?: return false
        if (epOut <= 0) return false
        val data = byteArrayOf(
            (rate and 0xFF).toByte(),
            ((rate shr 8) and 0xFF).toByte(),
            ((rate shr 16) and 0xFF).toByte(),
        )
        // bmRequestType 0x22 = Host-to-Device | Class | Endpoint, SET_CUR (0x01),
        // wValue = SAMPLING_FREQ_CONTROL (0x01) << 8, wIndex = endpoint address.
        return conn.controlTransfer(0x22, 0x01, 0x0100, epOut, data, data.size, CONTROL_TIMEOUT_MS) == data.size
    }

    private fun readUac1EndpointRate(conn: UsbDeviceConnection): Int {
        val epOut = cachedDeviceInfo?.endpointOutAddress ?: return -1
        if (epOut <= 0) return -1
        val data = ByteArray(3)
        val ret = conn.controlTransfer(0xA2, 0x81, 0x0100, epOut, data, data.size, CONTROL_TIMEOUT_MS)
        if (ret < 3) return -1
        val rate = readLeInt24(data, 0)
        return if (rate in 8000..1536000) rate else -1
    }

    fun diagnosticReport(): String = buildString {
        synchronized(trace) { append(trace) }
        val raw = connection?.rawDescriptors
        if (raw != null) {
            append("descriptors(${raw.size} B):\n")
            raw.toList().chunked(32).forEach { row ->
                append(row.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).append('\n')
            }
        }
    }

    fun readClockValid(): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(1)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or (audioControlInterfaceId and 0xFF)
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR
                    0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                    wIndex,
                    data,
                    data.size,
                    CONTROL_TIMEOUT_MS
            )
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Log.i(TAG, "readClockValid: clockSourceId=0x${csId.toString(16)} valid=$valid")
                return valid == 1
            }
        }
        Log.w(TAG, "readClockValid: all GET_CUR attempts failed")
        return false
    }

    fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting &&
                isPlaybackId(iface.id)) {
                val result = conn.setInterface(iface)
                Log.i(TAG, "setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Log.d(TAG, "  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}

fun supportedStandardRatesInRange(minimumHz: Int, maximumHz: Int, resolutionHz: Int): Set<Int> {
    if (minimumHz <= 0 || maximumHz < minimumHz) return emptySet()
    val standardRates = intArrayOf(
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000,
    )
    return buildSet {
        add(minimumHz)
        add(maximumHz)
        for (rate in standardRates) {
            if (rate in minimumHz..maximumHz &&
                (resolutionHz <= 0 || (rate - minimumHz) % resolutionHz == 0)
            ) {
                add(rate)
            }
        }
    }
}
