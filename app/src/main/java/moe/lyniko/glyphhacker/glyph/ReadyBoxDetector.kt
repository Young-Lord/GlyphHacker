package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ReadyBoxDetector {

    private val matcher = TemplateMatcher()

    fun detect(bitmap: Bitmap, hexTemplate: Bitmap?): ReadyBoxProfile {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || hexTemplate == null) {
            return fallbackProfile()
        }

        val match = findBestHexagonMatch(bitmap, hexTemplate) ?: return fallbackProfile()
        val scaledTemplate = Bitmap.createScaledBitmap(
            hexTemplate,
            match.width.coerceAtLeast(12),
            match.height.coerceAtLeast(12),
            true,
        )
        val rowMatches = findRowMatches(bitmap, scaledTemplate, match)

        val firstX = rowMatches.minOrNull() ?: match.x.toFloat()
        val rightMostX = rowMatches.maxOrNull() ?: match.x.toFloat()
        val boxWidth = match.width.toFloat()
        val boxHeight = match.height.toFloat()

        val firstLeft = firstX
        val firstTop = match.y.toFloat()
        val firstRight = firstLeft + boxWidth
        val firstBottom = firstTop + boxHeight

        val estimatedRowRight = if (rowMatches.size >= 2) {
            rightMostX + boxWidth
        } else {
            firstRight + boxWidth * 3.7f
        }

        val rowLeftNorm = (firstLeft / width).coerceIn(0f, 1f)
        val rowRightNorm = (estimatedRowRight / width).coerceIn(0f, 1f)

        val countdownTop = (firstTop - boxHeight * 0.43f) / height
        val countdownBottom = (firstTop - boxHeight * 0.18f) / height
        val progressTop = (firstTop - boxHeight * 0.16f) / height
        val progressBottom = (firstTop - boxHeight * 0.06f) / height

        return ReadyBoxProfile(
            firstBoxLeftNorm = (firstLeft / width).coerceIn(0f, 1f),
            firstBoxTopNorm = (firstTop / height).coerceIn(0f, 1f),
            firstBoxRightNorm = (firstRight / width).coerceIn(0f, 1f),
            firstBoxBottomNorm = (firstBottom / height).coerceIn(0f, 1f),
            boxHeightNorm = (boxHeight / height).coerceIn(0.01f, 0.25f),
            countdownLeftNorm = rowLeftNorm,
            countdownTopNorm = countdownTop.coerceIn(0f, 1f),
            countdownRightNorm = rowRightNorm,
            countdownBottomNorm = countdownBottom.coerceIn(0f, 1f),
            progressLeftNorm = rowLeftNorm,
            progressTopNorm = progressTop.coerceIn(0f, 1f),
            progressRightNorm = rowRightNorm,
            progressBottomNorm = progressBottom.coerceIn(0f, 1f),
        )
    }

    private fun findBestHexagonMatch(frame: Bitmap, template: Bitmap): TemplateMatcher.MatchResult? {
        val expectedWidth = frame.width * 0.16f
        val baseScale = expectedWidth / template.width.toFloat()
        val scales = floatArrayOf(0.68f, 0.78f, 0.88f, 1f, 1.12f, 1.24f)
        var best: TemplateMatcher.MatchResult? = null
        var bestScore = 0f
        for (ratio in scales) {
            val scale = (baseScale * ratio).coerceIn(0.3f, 1.65f)
            val w = (template.width * scale).toInt().coerceAtLeast(18)
            val h = (template.height * scale).toInt().coerceAtLeast(18)
            val resized = Bitmap.createScaledBitmap(template, w, h, true)
            val result = matcher.match(
                frame = frame,
                template = resized,
                searchRoi = floatArrayOf(0f, 0f, 1f, 0.45f),
                scanStep = 3,
            )
            if (result.score > bestScore) {
                bestScore = result.score
                best = result
            }
        }
        return best?.takeIf { it.score >= 0.45f }
    }

    private fun findRowMatches(
        frame: Bitmap,
        template: Bitmap,
        anchor: TemplateMatcher.MatchResult,
    ): List<Float> {
        val frameLuma = frame.toLumaFrame()
        val templateLuma = template.toLumaFrame()

        val yStart = (anchor.y - anchor.height * 0.18f).toInt().coerceIn(0, frame.height - template.height)
        val yEnd = (anchor.y + anchor.height * 0.18f).toInt().coerceIn(yStart, frame.height - template.height)

        var globalBest = 0f
        val candidates = mutableListOf<Pair<Float, Float>>()
        for (y in yStart..yEnd step 2) {
            var x = 0
            while (x <= frame.width - template.width) {
                val score = patchSimilarity(
                    frame = frameLuma,
                    template = templateLuma,
                    left = x,
                    top = y,
                )
                globalBest = max(globalBest, score)
                candidates += score to x.toFloat()
                x += 4
            }
        }

        if (globalBest <= 0f) {
            return emptyList()
        }

        val threshold = max(0.58f, globalBest * 0.82f)
        val sorted = candidates
            .filter { it.first >= threshold }
            .sortedByDescending { it.first }

        val selected = mutableListOf<Float>()
        val minDistance = anchor.width * 0.55f
        for ((_, x) in sorted) {
            if (selected.none { abs(it - x) < minDistance }) {
                selected += x
            }
        }
        return selected.sorted()
    }

    private fun patchSimilarity(
        frame: LumaFrame,
        template: LumaFrame,
        left: Int,
        top: Int,
    ): Float {
        val stepX = max(1, template.width / 22)
        val stepY = max(1, template.height / 22)
        var diff = 0f
        var samples = 0
        var y = 0
        while (y < template.height) {
            var x = 0
            while (x < template.width) {
                val framePixel = frame.sample(left + x, top + y)
                val templatePixel = template.sample(x, y)
                diff += abs(framePixel - templatePixel)
                samples += 1
                x += stepX
            }
            y += stepY
        }
        return 1f - diff / max(1, samples) / 255f
    }

    private data class LumaFrame(
        val width: Int,
        val height: Int,
        val values: FloatArray,
    ) {
        fun sample(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            return values[cy * width + cx]
        }
    }

    private fun Bitmap.toLumaFrame(): LumaFrame {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val values = FloatArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            values[index] = (0.299f * r) + (0.587f * g) + (0.114f * b)
        }
        return LumaFrame(width = width, height = height, values = values)
    }

    private fun fallbackProfile(): ReadyBoxProfile {
        return ReadyBoxProfile(
            firstBoxLeftNorm = 0.10f,
            firstBoxTopNorm = 0.09f,
            firstBoxRightNorm = 0.28f,
            firstBoxBottomNorm = 0.165f,
            boxHeightNorm = 0.075f,
            countdownLeftNorm = 0.10f,
            countdownTopNorm = 0.055f,
            countdownRightNorm = 0.86f,
            countdownBottomNorm = 0.09f,
            progressLeftNorm = 0.10f,
            progressTopNorm = 0.093f,
            progressRightNorm = 0.86f,
            progressBottomNorm = 0.105f,
        )
    }
}
