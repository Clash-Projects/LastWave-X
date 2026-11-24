package com.clashprojects.lastwave.innertube.model.request

import com.clashprojects.lastwave.innertube.model.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class CreatePlaylistBody(
    val context: Context,
    val title: String,
    val privacyStatus: String,
    val videoIds: List<String>? = null,
)
