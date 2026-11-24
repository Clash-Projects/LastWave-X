package com.clashprojects.lastwave.innertube.extract

import com.clashprojects.lastwave.innertube.model.response.PlayerResponse.StreamingData.Format

interface StreamCipherEngine {
    suspend fun initialize()

    suspend fun preloadPlayerCode(playerUrl: String)

    suspend fun prewarmEjs()

    suspend fun processFormats(
        playerUrl: String,
        formats: List<Format>,
    ): List<Format>
}
