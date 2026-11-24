package com.clashprojects.lastwave.innertube.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val thumbnailUrl: String?,
)
