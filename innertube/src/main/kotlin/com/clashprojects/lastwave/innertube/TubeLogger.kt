package com.clashprojects.lastwave.innertube

enum class TubeLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class TubeLogEvent(
    val level: TubeLogLevel,
    val tag: String,
    val message: String,
    val mediaId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

fun interface TubeLogger {
    fun log(event: TubeLogEvent)

    companion object {
        val NONE = TubeLogger {}
    }
}

internal fun TubeLogger.d(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(TubeLogEvent(TubeLogLevel.DEBUG, tag, message, mediaId, details))

internal fun TubeLogger.i(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(TubeLogEvent(TubeLogLevel.INFO, tag, message, mediaId, details))

internal fun TubeLogger.w(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(TubeLogEvent(TubeLogLevel.WARN, tag, message, mediaId, details))

internal fun TubeLogger.e(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(TubeLogEvent(TubeLogLevel.ERROR, tag, message, mediaId, details))
