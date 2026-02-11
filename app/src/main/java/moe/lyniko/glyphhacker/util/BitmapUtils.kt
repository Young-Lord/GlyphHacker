package moe.lyniko.glyphhacker.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

fun decodeBitmapFromUri(
    context: Context,
    uri: Uri,
    mutable: Boolean = false,
): Bitmap? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = mutable
            }
        } else {
            @Suppress("DEPRECATION")
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            if (mutable) {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }.getOrNull()
}

fun decodeVideoFrameFromUri(
    context: Context,
    uri: Uri,
    timeMs: Long = 0L,
): Bitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 100): String {
    val output = ByteArrayOutputStream()
    bitmap.compress(format, quality, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

fun base64ToBitmap(base64: String): Bitmap? {
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

fun resizeBitmapToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= maxDimension) {
        return bitmap
    }
    val ratio = maxDimension / maxSide.toFloat()
    val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

fun uriExists(contentResolver: ContentResolver, uri: Uri): Boolean {
    return runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}

fun ContentResolver.takePersistableReadPermission(uri: Uri) {
    runCatching {
        takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
