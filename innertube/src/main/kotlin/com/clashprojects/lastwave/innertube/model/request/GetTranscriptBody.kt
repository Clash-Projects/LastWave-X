package com.clashprojects.lastwave.innertube.model.request

import com.clashprojects.lastwave.innertube.model.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class GetTranscriptBody(
    val context: Context,
    val params: String,
)
