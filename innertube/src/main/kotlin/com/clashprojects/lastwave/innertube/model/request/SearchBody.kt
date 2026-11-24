package com.clashprojects.lastwave.innertube.model.request

import com.clashprojects.lastwave.innertube.model.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class SearchBody(
    val context: Context,
    val query: String? = null,
    val params: String? = null,
)
