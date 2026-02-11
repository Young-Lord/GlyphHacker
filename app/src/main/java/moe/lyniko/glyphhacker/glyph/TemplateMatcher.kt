package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TemplateMatcher {

    private companion object {
        private const val LOG_TAG = "GlyphHacker"
    }

    private var matchCounter: Long = 0L

    data class MatchResult(
        val score: Float,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    fun match(
        frame: Bitmap,
        template: Bitmap,
        searchRoi: FloatArray? = null,
        scanStep: Int = 4,
    ): MatchResult {
        val matchId = ++matchCounter
        val startNs = SystemClock.elapsedRealtimeNanos()
        val reducedFrame = frame.downsampled(maxDimension = 420)
        val reducedTemplate = template.downsampled(maxDimension = 180)
        if (reducedTemplate.width > reducedFrame.width || reducedTemplate.height > reducedFrame.height) {
            Log.w(
                LOG_TAG,
                "[TEMPLATE][M$matchId] template larger than frame frame=${reducedFrame.width}x${reducedFrame.height} template=${reducedTemplate.width}x${reducedTemplate.height}",
            )
            return MatchResult(0f, 0, 0, reducedTemplate.width, reducedTemplate.height)
        }

        val frameLuma = reducedFrame.toLuma()
        val templateLuma = reducedTemplate.toLuma()
        val frameWidth = reducedFrame.width
        val frameHeight = reducedFrame.height
        val templateWidth = reducedTemplate.width
        val templateHeight = reducedTemplate.height
        val step = max(1, scanStep)

        val roi = searchRoi ?: floatArrayOf(0f, 0f, 1f, 1f)
        val minX = (roi[0] * frameWidth).toInt().coerceIn(0, frameWidth - templateWidth)
        val minY = (roi[1] * frameHeight).toInt().coerceIn(0, frameHeight - templateHeight)
        val maxX = (roi[2] * frameWidth).toInt().coerceIn(templateWidth, frameWidth) - templateWidth
        val maxY = (roi[3] * frameHeight).toInt().coerceIn(templateHeight, frameHeight) - templateHeight

        val scanColumns = if (maxX >= minX) ((maxX - minX) / step) + 1 else 0
        val scanRows = if (maxY >= minY) ((maxY - minY) / step) + 1 else 0
        val candidateCount = scanColumns * scanRows

        var bestScore = 0f
        var bestX = minX
        var bestY = minY
        val pixelCount = templateWidth * templateHeight

        for (top in minY..maxY step step) {
            for (left in minX..maxX step step) {
                var diffSum = 0f
                var ti = 0
                var fy = top
                while (fy < top + templateHeight) {
                    var fx = left
                    val rowOffset = fy * frameWidth
                    while (fx < left + templateWidth) {
                        val framePixel = frameLuma[rowOffset + fx]
                        val templatePixel = templateLuma[ti]
                        diffSum += abs(framePixel - templatePixel)
                        ti++
                        fx++
                    }
                    fy++
                }
                val score = 1f - (diffSum / (pixelCount * 255f))
                if (score > bestScore) {
                    bestScore = score
                    bestX = left
                    bestY = top
                }
            }
        }

        val xScale = frame.width / reducedFrame.width.toFloat()
        val yScale = frame.height / reducedFrame.height.toFloat()
        val result = MatchResult(
            score = bestScore.coerceIn(0f, 1f),
            x = (bestX * xScale).toInt(),
            y = (bestY * yScale).toInt(),
            width = (templateWidth * xScale).toInt(),
            height = (templateHeight * yScale).toInt(),
        )
        val durationMs = elapsedMs(startNs)
        Log.d(
            LOG_TAG,
            "[TEMPLATE][M$matchId] total=${durationMs}ms frame=${frame.width}x${frame.height} template=${template.width}x${template.height} reducedFrame=${reducedFrame.width}x${reducedFrame.height} reducedTemplate=${reducedTemplate.width}x${reducedTemplate.height} step=$step candidates=$candidateCount score=${result.score} roi=${roi.joinToString(",")}",
        )
        if (durationMs >= 120L) {
            Log.w(LOG_TAG, "[TEMPLATE][M$matchId] template match is slow: ${durationMs}ms")
        }
        return result
    }

    private fun Bitmap.toLuma(): FloatArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return FloatArray(pixels.size) { index ->
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            ((0.299f * r) + (0.587f * g) + (0.114f * b))
        }
    }

    private fun Bitmap.downsampled(maxDimension: Int): Bitmap {
        val biggest = max(width, height)
        if (biggest <= maxDimension) {
            return this
        }
        val ratio = maxDimension / biggest.toFloat()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun elapsedMs(startNs: Long, endNs: Long = SystemClock.elapsedRealtimeNanos()): Long {
        return ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
    }
}
