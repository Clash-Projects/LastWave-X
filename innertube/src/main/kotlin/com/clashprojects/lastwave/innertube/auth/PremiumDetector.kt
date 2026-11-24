package com.clashprojects.lastwave.innertube.auth

import com.clashprojects.lastwave.innertube.extract.ExtractedStream

object PremiumDetector {
    val PREMIUM_AUDIO_ITAGS = setOf(774, 141)
    private const val PREMIUM_MIN_BITRATE = 240_000

    private val AUTHENTICATED_PREMIUM_CLIENTS =
        setOf("WEB_REMIX", "WEB_CREATOR", "TVHTML5", "WEB", "MWEB")

    private val POSITIVE_MARKERS =
        listOf(
            "\"MUSIC_PREMIUM_LOGO\"",
            "\"YOUTUBE_MUSIC_PREMIUM_LOGO\"",
            "\"YOUTUBE_PREMIUM_LOGO\"",
        )

    private val NEGATIVE_MARKERS =
        listOf(
            "\"SPmusic_premium_upsell\"",
            "\"PApremium_upsell\"",
            "\"FEmusic_premium\"",
        )

    fun detectFromJson(rawJson: String): Boolean? {
        if (rawJson.isEmpty()) return null
        if (POSITIVE_MARKERS.any { rawJson.contains(it) }) return true
        if (NEGATIVE_MARKERS.any { rawJson.contains(it) }) return false
        return null
    }

    fun isPremiumStream(stream: ExtractedStream): Boolean =
        stream.itag in PREMIUM_AUDIO_ITAGS || (stream.bitrate ?: 0) >= PREMIUM_MIN_BITRATE

    fun isAuthenticatedPremiumClient(clientName: String): Boolean =
        clientName in AUTHENTICATED_PREMIUM_CLIENTS
}
