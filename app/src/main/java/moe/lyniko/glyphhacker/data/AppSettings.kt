package moe.lyniko.glyphhacker.data

import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.ReadyBoxProfile

enum class RecognitionMode {
    EDGE_SET,
    STROKE_SEQUENCE,
}

data class AppSettings(
    val recognitionMode: RecognitionMode = RecognitionMode.EDGE_SET,
    val frameIntervalMs: Long = 300L,
    val goCheckIntervalMs: Long = 90L,
    val debugPlaybackSpeed: Float = 1.0f,
    val edgeActivationThreshold: Float = 26f,
    val minimumLineBrightness: Float = 70f,
    val stableFrameCount: Int = 1,
    val minimumMatchScore: Float = 0.68f,
    val startTemplateThreshold: Float = 0.84f,
    val commandOpenMaxLuma: Float = 1f,
    val glyphDisplayMinLuma: Float = 10f,
    val goColorDeltaThreshold: Float = 18f,
    val countdownVisibleThreshold: Float = 5f,
    val progressVisibleThreshold: Float = 20f,
    val firstBoxTopPercent: Float = 9.0f,
    val firstBoxBottomPercent: Float = 16.5f,
    val countdownTopPercent: Float = 5.5f,
    val countdownBottomPercent: Float = 9.0f,
    val progressTopPercent: Float = 9.3f,
    val progressBottomPercent: Float = 10.5f,
    val drawEdgeDurationMs: Long = 70L,
    val drawGlyphGapMs: Long = 55L,
    val doneButtonXPercent: Float = 75.56f,
    val doneButtonYPercent: Float = 92.29f,
    val overlayXRatio: Float = 0.62f,
    val overlayYRatio: Float = 0.07f,
    val blankReferenceUri: String? = null,
    val startTemplateUri: String? = null,
    val startTemplateBase64: String? = null,
    val readyBoxProfile: ReadyBoxProfile? = null,
    val calibrationProfile: CalibrationProfile? = null,
)
