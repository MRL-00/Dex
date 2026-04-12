package com.remodex.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageAttachment(
    val id: String,
    val thumbnailBase64: String,
    val payloadDataUrl: String? = null,
    val sourceUri: String? = null,
)

fun ImageAttachment.sanitizedForMessage(): ImageAttachment = copy(
    payloadDataUrl = null,
    sourceUri = null,
)
