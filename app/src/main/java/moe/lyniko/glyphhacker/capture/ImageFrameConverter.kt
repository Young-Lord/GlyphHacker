package moe.lyniko.glyphhacker.capture

import android.graphics.Bitmap
import android.media.Image

object ImageFrameConverter {

    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) {
            return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }
}
