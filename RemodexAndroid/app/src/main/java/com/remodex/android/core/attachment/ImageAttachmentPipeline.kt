package com.remodex.android.core.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.remodex.android.core.model.ImageAttachment
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageAttachmentPipeline {
    const val maxComposerImages = 4
    private const val thumbnailSidePx = 70
    private const val maxPayloadDimensionPx = 1600
    private const val payloadQuality = 80
    private const val thumbnailQuality = 80
    private const val dataUrlPrefix = "data:image/jpeg;base64,"

    fun makeAttachment(
        sourceData: ByteArray,
        includePayloadDataUrl: Boolean = true,
    ): ImageAttachment? {
        val normalizedJpeg = normalizePayloadJpeg(sourceData) ?: return null
        val thumbnailBase64 = makeThumbnailBase64(normalizedJpeg) ?: return null
        return ImageAttachment(
            id = UUID.randomUUID().toString(),
            thumbnailBase64 = thumbnailBase64,
            payloadDataUrl = if (includePayloadDataUrl) {
                dataUrlPrefix + Base64.encodeToString(normalizedJpeg, Base64.NO_WRAP)
            } else {
                null
            },
        )
    }

    fun makeAttachmentFromDataUrl(
        dataUrl: String,
        includePayloadDataUrl: Boolean = false,
    ): ImageAttachment? {
        val imageData = decodeDataUrlImage(dataUrl) ?: return null
        val normalizedDataUrl = if (includePayloadDataUrl) dataUrl else null
        return makeAttachment(
            sourceData = imageData,
            includePayloadDataUrl = false,
        )?.copy(payloadDataUrl = normalizedDataUrl)
    }

    fun decodeThumbnailBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        val data = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    fun decodeDataUrlImage(dataUrl: String): ByteArray? {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) return null
        val header = dataUrl.substring(0, commaIndex)
        if (!header.startsWith("data:image", ignoreCase = true) || !header.contains(";base64", ignoreCase = true)) {
            return null
        }
        val payload = dataUrl.substring(commaIndex + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    private fun normalizePayloadJpeg(sourceData: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(sourceData, 0, sourceData.size) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val longestSide = max(width, height).toFloat()
        val scale = min(1f, maxPayloadDimensionPx / longestSide)
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())
        val scaled = if (targetWidth == width && targetHeight == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
        return compressJpeg(scaled, payloadQuality)
    }

    private fun makeThumbnailBase64(imageData: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return null
        val thumbnail = createCenterCropThumbnail(bitmap, thumbnailSidePx, thumbnailSidePx)
        val jpeg = compressJpeg(thumbnail, thumbnailQuality) ?: return null
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    private fun createCenterCropThumbnail(source: Bitmap, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val sourceRect = centerCropSourceRect(source.width, source.height, width, height)
        val destinationRect = Rect(0, 0, width, height)
        canvas.drawBitmap(source, sourceRect, destinationRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun centerCropSourceRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Rect {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        return if (sourceAspect > targetAspect) {
            val cropWidth = (sourceHeight * targetAspect).roundToInt()
            val left = max(0, (sourceWidth - cropWidth) / 2)
            Rect(left, 0, min(sourceWidth, left + cropWidth), sourceHeight)
        } else {
            val cropHeight = (sourceWidth / targetAspect).roundToInt()
            val top = max(0, (sourceHeight - cropHeight) / 2)
            Rect(0, top, sourceWidth, min(sourceHeight, top + cropHeight))
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            output.toByteArray()
        } else {
            null
        }
    }
}
