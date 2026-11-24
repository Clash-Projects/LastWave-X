package com.clashprojects.lastwave.innertube.model.response

import kotlinx.serialization.Serializable

@Serializable
internal data class ImageUploadResponse(
    val encryptedBlobId: String,
)
