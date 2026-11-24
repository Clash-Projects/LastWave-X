package com.clashprojects.lastwave.innertube.model.request

import com.clashprojects.lastwave.innertube.model.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class LikeBody(
    val context: Context,
    val target: Target,
) {
    @Serializable
    data class Target(
        val videoId: String? = null,
        val playlistId: String? = null,
    ) {
        companion object {
            fun video(id: String) = Target(videoId = id)

            fun playlist(id: String) = Target(playlistId = id)
        }
    }
}
