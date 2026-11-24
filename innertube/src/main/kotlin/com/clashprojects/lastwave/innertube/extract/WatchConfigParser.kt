package com.clashprojects.lastwave.innertube.extract

public interface WatchConfigParser {

    public suspend fun fetchConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig

    public suspend fun fetchEmbeddedConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig = fetchConfig(videoId, useLoginCookies)
}
