package com.clashprojects.lastwave.innertube.model.request

import com.clashprojects.lastwave.innertube.model.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class GetQueueBody(
    val context: Context,
    val videoIds: List<String>?,
    val playlistId: String?,
)
