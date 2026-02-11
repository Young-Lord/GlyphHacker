package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap

object ReadyBoxDetector {

    fun detect(bitmap: Bitmap): ReadyBoxProfile {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return fallbackProfile()
        }
        return fallbackProfile()
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
