package com.remodex.android.core.model

import android.net.Uri

data class ImageAttachment(
    val id: String,
    val thumbnailBase64: String,
    val payloadDataUrl: String? = null,
    val sourceUri: Uri? = null,
)
