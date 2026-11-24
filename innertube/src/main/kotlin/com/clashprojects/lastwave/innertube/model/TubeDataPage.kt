package com.clashprojects.lastwave.innertube.model

import kotlinx.serialization.Serializable

@Serializable
data class TubeDataPage<T>(
    val items: List<T>,
    val continuation: String? = null,
)
