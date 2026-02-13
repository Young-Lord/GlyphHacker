package moe.lyniko.glyphhacker.data

import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.ReadyBoxProfile

enum class RecognitionMode {
    EDGE_SET,
    STROKE_SEQUENCE,
}

enum class CommandOpenPrimaryAction(
    val label: String,
    val glyphName: String?,
) {
    MORE_DISABLED("多", null),
    SEND_MORE("勿", "More"),
    SEND_LESS("仅", "Less"),
    SEND_SPEED("普", "Speed"),
    ;

    fun next(): CommandOpenPrimaryAction {
        return when (this) {
            SEND_SPEED -> MORE_DISABLED
            MORE_DISABLED -> SEND_MORE
            SEND_MORE -> SEND_LESS
            SEND_LESS -> SEND_SPEED
        }
    }
}

enum class CommandOpenSecondaryAction(
    val label: String,
    val glyphName: String?,
) {
    FAST("快", "Complex"),
    MEDIUM("中", null),
    SLOW("慢", "Simple"),
    ;

    fun next(hideSlow: Boolean): CommandOpenSecondaryAction {
        if (hideSlow) {
            return when (this) {
                FAST -> MEDIUM
                MEDIUM -> FAST
                SLOW -> FAST
            }
        }
        return when (this) {
            FAST -> MEDIUM
            MEDIUM -> SLOW
            SLOW -> FAST
        }
    }
}

data class AppSettings(
    val recognitionMode: RecognitionMode = RecognitionMode.EDGE_SET,
    val useAccessibilityScreenshotCapture: Boolean = false,
    val autoGrantAccessibilityViaShizukuOnLaunch: Boolean = false,
    val idleFrameIntervalMs: Long = 500L,
    val nonIdleFrameIntervalMs: Long = 120L,
    val debugPlaybackSpeed: Float = 1.0f,
    val edgeActivationThreshold: Float = 26f,
    val minimumLineBrightness: Float = 70f,
    val minimumMatchScore: Float = 0.68f,
    val startTemplateThreshold: Float = 0.84f,
    val commandOpenMaxLuma: Float = 1f,
    val glyphDisplayMinLuma: Float = 10f,
    val glyphDisplayTopBarsMinLuma: Float = 1f,
    val goColorDeltaThreshold: Float = 18f,
    val countdownVisibleThreshold: Float = 5f,
    val progressVisibleThreshold: Float = 20f,
    val firstBoxTopPercent: Float = 9.0f,
    val firstBoxBottomPercent: Float = 16.5f,
    val countdownTopPercent: Float = 5.5f,
    val countdownBottomPercent: Float = 9.0f,
    val progressTopPercent: Float = 9.3f,
    val progressBottomPercent: Float = 10.5f,
    val drawEdgeDurationMs: Long = 250L,
    val drawGlyphGapMs: Long = 700L,
    val commandOpenPrimaryAction: CommandOpenPrimaryAction = CommandOpenPrimaryAction.SEND_SPEED,
    val commandOpenSecondaryAction: CommandOpenSecondaryAction = CommandOpenSecondaryAction.MEDIUM,
    val commandOpenHideSlowOption: Boolean = false,
    val doneButtonXPercent: Float = 75.56f,
    val doneButtonYPercent: Float = 92.29f,
    val overlayXRatio: Float = 0.62f,
    val overlayYRatio: Float = 0.07f,
    val blankReferenceUri: String? = null,
    val startTemplateUri: String? = null,
    val startTemplateBase64: String? = null,
    val readyBoxProfile: ReadyBoxProfile? = null,
    val calibrationProfile: CalibrationProfile? = null,
    /** 节点 patch 匹配边长（像素），0 = 禁用 patch 匹配。 */
    val nodePatchSize: Int = 0,
    /** 节点 patch MAE 阈值，低于此值视为匹配成功。 */
    val nodePatchMaxMae: Float = 12f,
    /** WAIT_GO 超时时间（毫秒），超时后重置回 IDLE。0 = 不超时。 */
    val waitGoTimeoutMs: Long = 5000L,
)
