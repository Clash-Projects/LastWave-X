package com.clashprojects.lastwave.innertube.extract

import com.clashprojects.lastwave.innertube.extract.AudioQuality

public interface StreamExtractor {
    suspend fun prewarm()

    public suspend fun extract(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String> = emptySet(),
        audioQuality: AudioQuality = AudioQuality.AUTO,
        clientPlaybackNonce: String = generateClientPlaybackNonce(),
    ): ExtractedStream?
}
