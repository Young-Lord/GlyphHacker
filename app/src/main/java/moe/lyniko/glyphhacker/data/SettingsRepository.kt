package moe.lyniko.glyphhacker.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.NodePatch
import moe.lyniko.glyphhacker.glyph.NodePosition
import moe.lyniko.glyphhacker.glyph.ReadyBoxProfile
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refresh()
    }
    private val _settingsFlow = MutableStateFlow(loadFromPrefs())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    suspend fun updateRecognitionMode(mode: RecognitionMode) {
        prefs.edit().putString(KEY_RECOGNITION_MODE, mode.name).apply()
        refresh()
    }

    suspend fun updateUseAccessibilityScreenshotCapture(enabled: Boolean) {
        val safeEnabled = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        prefs.edit().putBoolean(KEY_USE_ACCESSIBILITY_SCREENSHOT_CAPTURE, safeEnabled).apply()
        refresh()
    }

    suspend fun updateAutoGrantAccessibilityViaShizukuOnLaunch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_GRANT_ACCESSIBILITY_VIA_SHIZUKU_ON_LAUNCH, enabled).apply()
        refresh()
    }

    suspend fun updateIdleFrameIntervalMs(value: Long) {
        prefs.edit().putLong(KEY_IDLE_FRAME_INTERVAL_MS, value.coerceIn(120L, 5000L)).apply()
        refresh()
    }

    suspend fun updateNonIdleFrameIntervalMs(value: Long) {
        prefs.edit().putLong(KEY_NON_IDLE_FRAME_INTERVAL_MS, value.coerceIn(30L, 1000L)).apply()
        refresh()
    }

    suspend fun updateDebugPlaybackSpeed(value: Float) {
        prefs.edit().putFloat(KEY_DEBUG_PLAYBACK_SPEED, value.coerceIn(0.25f, 4.0f)).apply()
        refresh()
    }

    suspend fun updateEdgeActivationThreshold(value: Float) {
        prefs.edit().putFloat(KEY_EDGE_THRESHOLD, value.coerceIn(5f, 120f)).apply()
        refresh()
    }

    suspend fun updateMinimumLineBrightness(value: Float) {
        prefs.edit().putFloat(KEY_MIN_LINE_BRIGHTNESS, value.coerceIn(5f, 255f)).apply()
        refresh()
    }

    suspend fun updateMinimumMatchScore(value: Float) {
        prefs.edit().putFloat(KEY_MIN_MATCH_SCORE, value.coerceIn(0.3f, 0.99f)).apply()
        refresh()
    }

    suspend fun updateStartTemplateThreshold(value: Float) {
        prefs.edit().putFloat(KEY_TEMPLATE_THRESHOLD, value.coerceIn(0.5f, 0.99f)).apply()
        refresh()
    }

    suspend fun updateCommandOpenMaxLuma(value: Float) {
        prefs.edit().putFloat(KEY_COMMAND_OPEN_MAX_LUMA, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateGlyphDisplayMinLuma(value: Float) {
        prefs.edit().putFloat(KEY_GLYPH_DISPLAY_MIN_LUMA, value.coerceIn(0f, 80f)).apply()
        refresh()
    }

    suspend fun updateGlyphDisplayTopBarsMinLuma(value: Float) {
        prefs.edit().putFloat(KEY_GLYPH_DISPLAY_TOP_BARS_MIN_LUMA, value.coerceIn(0f, 40f)).apply()
        refresh()
    }

    suspend fun updateGoColorDeltaThreshold(value: Float) {
        prefs.edit().putFloat(KEY_GO_COLOR_DELTA, value.coerceIn(0.5f, 30f)).apply()
        refresh()
    }

    suspend fun updateCountdownVisibleThreshold(value: Float) {
        prefs.edit().putFloat(KEY_COUNTDOWN_VISIBLE_THRESHOLD, value.coerceIn(1f, 40f)).apply()
        refresh()
    }

    suspend fun updateProgressVisibleThreshold(value: Float) {
        prefs.edit().putFloat(KEY_PROGRESS_VISIBLE_THRESHOLD, value.coerceIn(1f, 80f)).apply()
        refresh()
    }

    suspend fun updateFirstBoxTopPercent(value: Float) {
        prefs.edit().putFloat(KEY_FIRST_BOX_TOP_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateFirstBoxBottomPercent(value: Float) {
        prefs.edit().putFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateCountdownTopPercent(value: Float) {
        prefs.edit().putFloat(KEY_COUNTDOWN_TOP_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateCountdownBottomPercent(value: Float) {
        prefs.edit().putFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateProgressTopPercent(value: Float) {
        prefs.edit().putFloat(KEY_PROGRESS_TOP_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateProgressBottomPercent(value: Float) {
        prefs.edit().putFloat(KEY_PROGRESS_BOTTOM_PERCENT, value.coerceIn(0f, 30f)).apply()
        refresh()
    }

    suspend fun updateDrawEdgeDurationMs(value: Long) {
        prefs.edit().putLong(KEY_DRAW_EDGE_MS, value.coerceIn(15L, 500L)).apply()
        refresh()
    }

    suspend fun updateDrawGlyphGapMs(value: Long) {
        prefs.edit().putLong(KEY_DRAW_GAP_MS, value.coerceIn(0L, 1000L)).apply()
        refresh()
    }

    suspend fun updateDrawTerminalDwellMs(value: Long) {
        prefs.edit().putLong(KEY_DRAW_TERMINAL_DWELL_MS, value.coerceIn(0L, 200L)).apply()
        refresh()
    }

    suspend fun updateCommandOpenPrimaryAction(value: CommandOpenPrimaryAction) {
        prefs.edit().putString(KEY_COMMAND_OPEN_PRIMARY_ACTION, value.name).apply()
        refresh()
    }

    suspend fun updateCommandOpenSecondaryAction(value: CommandOpenSecondaryAction) {
        val hideSlow = _settingsFlow.value.commandOpenHideSlowOption
        val safeValue = if (hideSlow && value == CommandOpenSecondaryAction.SLOW) {
            CommandOpenSecondaryAction.MEDIUM
        } else {
            value
        }
        prefs.edit().putString(KEY_COMMAND_OPEN_SECONDARY_ACTION, safeValue.name).apply()
        refresh()
    }

    suspend fun updateCommandOpenHideSlowOption(hide: Boolean) {
        val current = _settingsFlow.value
        val secondary = if (hide && current.commandOpenSecondaryAction == CommandOpenSecondaryAction.SLOW) {
            CommandOpenSecondaryAction.MEDIUM
        } else {
            current.commandOpenSecondaryAction
        }
        prefs.edit().apply {
            putBoolean(KEY_COMMAND_OPEN_HIDE_SLOW_OPTION, hide)
            putString(KEY_COMMAND_OPEN_SECONDARY_ACTION, secondary.name)
            apply()
        }
        refresh()
    }

    suspend fun updateDoneButtonXPercent(value: Float) {
        prefs.edit().putFloat(KEY_DONE_BUTTON_X_PERCENT, value.coerceIn(60f, 100f)).apply()
        refresh()
    }

    suspend fun updateDoneButtonYPercent(value: Float) {
        prefs.edit().putFloat(KEY_DONE_BUTTON_Y_PERCENT, value.coerceIn(90f, 100f)).apply()
        refresh()
    }

    suspend fun updateAutoTapDoneAfterInput(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TAP_DONE_AFTER_INPUT, enabled).apply()
        refresh()
    }

    suspend fun updateOverlayXRatio(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_X_RATIO, value.coerceIn(0f, 1f)).apply()
        refresh()
    }

    suspend fun updateOverlayYRatio(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_Y_RATIO, value.coerceIn(0f, 1f)).apply()
        refresh()
    }

    suspend fun updateBlankReferenceUri(uri: String?) {
        prefs.edit().putNullableString(KEY_BLANK_URI, uri).apply()
        refresh()
    }

    suspend fun updateStartTemplateUri(uri: String?) {
        prefs.edit().putNullableString(KEY_TEMPLATE_URI, uri).apply()
        refresh()
    }

    suspend fun updateStartTemplateBase64(base64: String?) {
        prefs.edit().putNullableString(KEY_TEMPLATE_BASE64, base64).apply()
        refresh()
    }

    suspend fun updateReadyBoxProfile(profile: ReadyBoxProfile?) {
        prefs.edit().putNullableString(KEY_READY_BOX_PROFILE, profile?.toJson()).apply()
        refresh()
    }

    suspend fun updateCalibrationProfile(profile: CalibrationProfile?) {
        prefs.edit().putNullableString(KEY_CALIBRATION, profile?.toJson()).apply()
        refresh()
    }

    suspend fun updateNodePatchSize(value: Int) {
        prefs.edit().putInt(KEY_NODE_PATCH_SIZE, value.coerceIn(0, 80)).apply()
        refresh()
    }

    suspend fun updateNodePatchMaxMae(value: Float) {
        prefs.edit().putFloat(KEY_NODE_PATCH_MAX_MAE, value.coerceIn(1f, 80f)).apply()
        refresh()
    }

    suspend fun updateWaitGoTimeoutMs(value: Long) {
        prefs.edit().putLong(KEY_WAIT_GO_TIMEOUT_MS, value.coerceIn(0L, 30000L)).apply()
        refresh()
    }

    suspend fun updateOverlayScaleFactor(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_SCALE_FACTOR, value.coerceIn(0.5f, 3.0f)).apply()
        refresh()
    }

    suspend fun updateOverlayGlyphSizeDp(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_GLYPH_SIZE_DP, value.coerceIn(12f, 80f)).apply()
        refresh()
    }

    suspend fun updateOverlayShowGlyphSequence(show: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_SHOW_GLYPH_SEQUENCE, show).apply()
        refresh()
    }

    suspend fun updateOverlaySequenceHideDelayAfterAutoDrawSec(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_AUTO_DRAW_SEC, value.coerceIn(0f, 20f)).apply()
        refresh()
    }

    suspend fun updateOverlaySequenceHideDelayAfterRecognitionOnlySec(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_RECOGNITION_ONLY_SEC, value.coerceIn(0f, 20f)).apply()
        refresh()
    }

    suspend fun updateOverlayVerticalSpacingDp(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_VERTICAL_SPACING_DP, value.coerceIn(0f, 40f)).apply()
        refresh()
    }

    suspend fun updateOverlayOpacityPercent(value: Float) {
        prefs.edit().putFloat(KEY_OVERLAY_OPACITY_PERCENT, value.coerceIn(0f, 100f)).apply()
        refresh()
    }

    suspend fun updateOverlayHideCommandButtons(hide: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_HIDE_COMMAND_BUTTONS, hide).apply()
        refresh()
    }

    suspend fun updateInputEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INPUT_ENABLED, enabled).apply()
        refresh()
    }

    suspend fun updateTemplateImport(
        sourceUri: String?,
        base64: String?,
        readyBoxProfile: ReadyBoxProfile?,
    ) {
        prefs.edit().apply {
            putNullableString(KEY_TEMPLATE_URI, sourceUri)
            putNullableString(KEY_TEMPLATE_BASE64, base64)
            putNullableString(KEY_READY_BOX_PROFILE, readyBoxProfile?.toJson())
            readyBoxProfile?.let { profile ->
                putFloat(KEY_FIRST_BOX_TOP_PERCENT, (profile.firstBoxTopNorm * 100f).coerceIn(0f, 30f))
                putFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, (profile.firstBoxBottomNorm * 100f).coerceIn(0f, 30f))
                putFloat(KEY_COUNTDOWN_TOP_PERCENT, (profile.countdownTopNorm * 100f).coerceIn(0f, 30f))
                putFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, (profile.countdownBottomNorm * 100f).coerceIn(0f, 30f))
                putFloat(KEY_PROGRESS_TOP_PERCENT, (profile.progressTopNorm * 100f).coerceIn(0f, 30f))
                putFloat(KEY_PROGRESS_BOTTOM_PERCENT, (profile.progressBottomNorm * 100f).coerceIn(0f, 30f))
            }
            apply()
        }
        refresh()
    }

    suspend fun clearImportedFileRefs() {
        prefs.edit()
            .remove(KEY_BLANK_URI)
            .remove(KEY_TEMPLATE_URI)
            .apply()
        refresh()
    }

    fun buildExportJson(
        appName: String,
        appVersion: String,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        exportTime: String = Instant.now().toString(),
    ): String {
        val settings = _settingsFlow.value
        val root = JSONObject()
        root.put("appName", appName)
        root.put("appVersion", appVersion)
        root.put("deviceModel", deviceModel)
        root.put("exportTime", exportTime)
        root.put("config", settings.toJson())
        return root.toString(2)
    }

    suspend fun importFromJson(jsonText: String): Result<Unit> {
        return runCatching {
            val root = JSONObject(jsonText)
            val config = root.optJSONObject("config") ?: root
            val mode = config.optString("recognitionMode", RecognitionMode.EDGE_SET.name).toRecognitionMode()
            val useAccessibilityScreenshotCapture = config.optBoolean("useAccessibilityScreenshotCapture", false)
            val autoGrantAccessibilityViaShizukuOnLaunch = config.optBoolean(
                "autoGrantAccessibilityViaShizukuOnLaunch",
                false,
            )
            val idleFrameInterval = config.optLong(
                "idleFrameIntervalMs",
                config.optLong("frameIntervalMs", 500L),
            )
            val nonIdleFrameInterval = config.optLong(
                "nonIdleFrameIntervalMs",
                config.optLong("goCheckIntervalMs", config.optLong("frameIntervalMs", 120L)),
            )
            val debugPlaybackSpeed = config.optDouble("debugPlaybackSpeed", 1.0).toFloat()
            val edgeThreshold = config.optDouble("edgeActivationThreshold", 26.0).toFloat()
            val minLineBrightness = config.optDouble("minimumLineBrightness", 70.0).toFloat()
            val minMatchScore = config.optDouble("minimumMatchScore", 0.68).toFloat()
            val templateThreshold = config.optDouble("startTemplateThreshold", 0.84).toFloat()
            val commandOpenMaxLuma = config.optDouble("commandOpenMaxLuma", 1.0).toFloat()
            val glyphDisplayMinLuma = config.optDouble("glyphDisplayMinLuma", 10.0).toFloat()
            val glyphDisplayTopBarsMinLuma = config.optDouble("glyphDisplayTopBarsMinLuma", 1.0).toFloat()
            val goColorDelta = config.optDouble("goColorDeltaThreshold", 3.0).toFloat()
            val countdownVisibleThreshold = config.optDouble(
                "countdownVisibleThreshold",
                config.optDouble("readyBlackThresholdLuma", 5.0),
            ).toFloat()
            val progressVisibleThreshold = config.optDouble(
                "progressVisibleThreshold",
                config.optDouble("readyBlackThresholdLuma", 20.0),
            ).toFloat()
            val drawEdgeMs = config.optLong("drawEdgeDurationMs", 250L)
            val drawGapMs = config.optLong("drawGlyphGapMs", 700L)
            val drawTerminalDwellMs = config.optLong("drawTerminalDwellMs", 80L)
            val commandOpenPrimaryAction = config.optString(
                "commandOpenPrimaryAction",
                CommandOpenPrimaryAction.SEND_SPEED.name,
            ).toCommandOpenPrimaryAction()
            val commandOpenSecondaryAction = config.optString(
                "commandOpenSecondaryAction",
                CommandOpenSecondaryAction.MEDIUM.name,
            ).toCommandOpenSecondaryAction()
            val commandOpenHideSlowOption = config.optBoolean("commandOpenHideSlowOption", false)
            val safeCommandOpenSecondaryAction = if (
                commandOpenHideSlowOption && commandOpenSecondaryAction == CommandOpenSecondaryAction.SLOW
            ) {
                CommandOpenSecondaryAction.MEDIUM
            } else {
                commandOpenSecondaryAction
            }
            val doneButtonXPercent = config.optDouble("doneButtonXPercent", 75.56).toFloat()
            val doneButtonYPercent = config.optDouble("doneButtonYPercent", 92.29).toFloat()
            val autoTapDoneAfterInput = config.optBoolean("autoTapDoneAfterInput", true)
            val overlayXRatio = config.optDouble("overlayXRatio", 0.62).toFloat()
            val overlayYRatio = config.optDouble("overlayYRatio", 0.07).toFloat()
            val blankUri = config.optNullableString("blankReferenceUri")
            val templateUri = config.optNullableString("startTemplateUri")
            val templateBase64 = config.optNullableString("startTemplateBase64")
            val calibration = config.optJSONObject("calibrationProfile")?.toCalibrationProfile()
            val readyBoxProfile = config.optJSONObject("readyBoxProfile")?.toReadyBoxProfile()
            val firstBoxTopPercent = config.optDouble(
                "firstBoxTopPercent",
                (readyBoxProfile?.firstBoxTopNorm?.times(100f) ?: 9f).toDouble(),
            ).toFloat()
            val firstBoxBottomPercent = config.optDouble(
                "firstBoxBottomPercent",
                (readyBoxProfile?.firstBoxBottomNorm?.times(100f) ?: 16.5f).toDouble(),
            ).toFloat()
            val countdownTopPercent = config.optDouble(
                "countdownTopPercent",
                (readyBoxProfile?.countdownTopNorm?.times(100f) ?: 5.5f).toDouble(),
            ).toFloat()
            val countdownBottomPercent = config.optDouble(
                "countdownBottomPercent",
                (readyBoxProfile?.countdownBottomNorm?.times(100f) ?: 9f).toDouble(),
            ).toFloat()
            val progressTopPercent = config.optDouble(
                "progressTopPercent",
                (readyBoxProfile?.progressTopNorm?.times(100f) ?: 9.3f).toDouble(),
            ).toFloat()
            val progressBottomPercent = config.optDouble(
                "progressBottomPercent",
                (readyBoxProfile?.progressBottomNorm?.times(100f) ?: 10.5f).toDouble(),
            ).toFloat()
            val nodePatchSize = config.optInt("nodePatchSize", 0)
            val nodePatchMaxMae = config.optDouble("nodePatchMaxMae", 12.0).toFloat()
            val waitGoTimeoutMs = config.optLong("waitGoTimeoutMs", 5000L)
            val overlayScaleFactor = config.optDouble("overlayScaleFactor", 1.0).toFloat()
            val overlayGlyphSizeDp = config.optDouble("overlayGlyphSizeDp", 28.0).toFloat()
            val overlayShowGlyphSequence = config.optBoolean("overlayShowGlyphSequence", true)
            val overlaySequenceHideDelayAfterAutoDrawSec = config.optDouble(
                "overlaySequenceHideDelayAfterAutoDrawSec",
                0.0,
            ).toFloat()
            val overlaySequenceHideDelayAfterRecognitionOnlySec = config.optDouble(
                "overlaySequenceHideDelayAfterRecognitionOnlySec",
                15.0,
            ).toFloat()
            val overlayVerticalSpacingDp = config.optDouble("overlayVerticalSpacingDp", 0.0).toFloat()
            val overlayOpacityPercent = config.optDouble("overlayOpacityPercent", 100.0).toFloat()
            val overlayHideCommandButtons = config.optBoolean("overlayHideCommandButtons", false)
            val inputEnabled = config.optBoolean("inputEnabled", true)

            prefs.edit()
                .putString(KEY_RECOGNITION_MODE, mode.name)
                .putBoolean(
                    KEY_USE_ACCESSIBILITY_SCREENSHOT_CAPTURE,
                    useAccessibilityScreenshotCapture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                )
                .putBoolean(
                    KEY_AUTO_GRANT_ACCESSIBILITY_VIA_SHIZUKU_ON_LAUNCH,
                    autoGrantAccessibilityViaShizukuOnLaunch,
                )
                .putLong(KEY_IDLE_FRAME_INTERVAL_MS, idleFrameInterval.coerceIn(120L, 5000L))
                .putLong(KEY_NON_IDLE_FRAME_INTERVAL_MS, nonIdleFrameInterval.coerceIn(30L, 1000L))
                .putFloat(KEY_DEBUG_PLAYBACK_SPEED, debugPlaybackSpeed.coerceIn(0.25f, 4.0f))
                .putFloat(KEY_EDGE_THRESHOLD, edgeThreshold.coerceIn(5f, 120f))
                .putFloat(KEY_MIN_LINE_BRIGHTNESS, minLineBrightness.coerceIn(5f, 255f))
                .putFloat(KEY_MIN_MATCH_SCORE, minMatchScore.coerceIn(0.3f, 0.99f))
                .putFloat(KEY_TEMPLATE_THRESHOLD, templateThreshold.coerceIn(0.5f, 0.99f))
                .putFloat(KEY_COMMAND_OPEN_MAX_LUMA, commandOpenMaxLuma.coerceIn(0f, 30f))
                .putFloat(KEY_GLYPH_DISPLAY_MIN_LUMA, glyphDisplayMinLuma.coerceIn(0f, 80f))
                .putFloat(KEY_GLYPH_DISPLAY_TOP_BARS_MIN_LUMA, glyphDisplayTopBarsMinLuma.coerceIn(0f, 40f))
                .putFloat(KEY_GO_COLOR_DELTA, goColorDelta.coerceIn(0.5f, 30f))
                .putFloat(KEY_COUNTDOWN_VISIBLE_THRESHOLD, countdownVisibleThreshold.coerceIn(1f, 40f))
                .putFloat(KEY_PROGRESS_VISIBLE_THRESHOLD, progressVisibleThreshold.coerceIn(1f, 80f))
                .putFloat(KEY_FIRST_BOX_TOP_PERCENT, firstBoxTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, firstBoxBottomPercent.coerceIn(0f, 30f))
                .putFloat(KEY_COUNTDOWN_TOP_PERCENT, countdownTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, countdownBottomPercent.coerceIn(0f, 30f))
                .putFloat(KEY_PROGRESS_TOP_PERCENT, progressTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_PROGRESS_BOTTOM_PERCENT, progressBottomPercent.coerceIn(0f, 30f))
                .putLong(KEY_DRAW_EDGE_MS, drawEdgeMs.coerceIn(15L, 500L))
                .putLong(KEY_DRAW_GAP_MS, drawGapMs.coerceIn(0L, 1000L))
                .putLong(KEY_DRAW_TERMINAL_DWELL_MS, drawTerminalDwellMs.coerceIn(0L, 200L))
                .putString(KEY_COMMAND_OPEN_PRIMARY_ACTION, commandOpenPrimaryAction.name)
                .putString(KEY_COMMAND_OPEN_SECONDARY_ACTION, safeCommandOpenSecondaryAction.name)
                .putBoolean(KEY_COMMAND_OPEN_HIDE_SLOW_OPTION, commandOpenHideSlowOption)
                .putFloat(KEY_DONE_BUTTON_X_PERCENT, doneButtonXPercent.coerceIn(60f, 100f))
                .putFloat(KEY_DONE_BUTTON_Y_PERCENT, doneButtonYPercent.coerceIn(90f, 100f))
                .putBoolean(KEY_AUTO_TAP_DONE_AFTER_INPUT, autoTapDoneAfterInput)
                .putFloat(KEY_OVERLAY_X_RATIO, overlayXRatio.coerceIn(0f, 1f))
                .putFloat(KEY_OVERLAY_Y_RATIO, overlayYRatio.coerceIn(0f, 1f))
                .putNullableString(KEY_BLANK_URI, blankUri)
                .putNullableString(KEY_TEMPLATE_URI, templateUri)
                .putNullableString(KEY_TEMPLATE_BASE64, templateBase64)
                .putNullableString(KEY_CALIBRATION, calibration?.toJson())
                .putNullableString(KEY_READY_BOX_PROFILE, readyBoxProfile?.toJson())
                .putInt(KEY_NODE_PATCH_SIZE, nodePatchSize.coerceIn(0, 80))
                .putFloat(KEY_NODE_PATCH_MAX_MAE, nodePatchMaxMae.coerceIn(1f, 80f))
                .putLong(KEY_WAIT_GO_TIMEOUT_MS, waitGoTimeoutMs.coerceIn(0L, 30000L))
                .putFloat(KEY_OVERLAY_SCALE_FACTOR, overlayScaleFactor.coerceIn(0.5f, 3.0f))
                .putFloat(KEY_OVERLAY_GLYPH_SIZE_DP, overlayGlyphSizeDp.coerceIn(12f, 80f))
                .putBoolean(KEY_OVERLAY_SHOW_GLYPH_SEQUENCE, overlayShowGlyphSequence)
                .putFloat(
                    KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_AUTO_DRAW_SEC,
                    overlaySequenceHideDelayAfterAutoDrawSec.coerceIn(0f, 20f),
                )
                .putFloat(
                    KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_RECOGNITION_ONLY_SEC,
                    overlaySequenceHideDelayAfterRecognitionOnlySec.coerceIn(0f, 20f),
                )
                .putFloat(KEY_OVERLAY_VERTICAL_SPACING_DP, overlayVerticalSpacingDp.coerceIn(0f, 40f))
                .putFloat(KEY_OVERLAY_OPACITY_PERCENT, overlayOpacityPercent.coerceIn(0f, 100f))
                .putBoolean(KEY_OVERLAY_HIDE_COMMAND_BUTTONS, overlayHideCommandButtons)
                .putBoolean(KEY_INPUT_ENABLED, inputEnabled)
                .apply()
            refresh()
        }
    }

    private fun refresh() {
        _settingsFlow.value = loadFromPrefs()
    }

    private fun loadFromPrefs(): AppSettings {
        val commandOpenHideSlowOption = prefs.getBoolean(KEY_COMMAND_OPEN_HIDE_SLOW_OPTION, false)
        val commandOpenSecondaryAction = prefs
            .getString(KEY_COMMAND_OPEN_SECONDARY_ACTION, CommandOpenSecondaryAction.MEDIUM.name)
            .toCommandOpenSecondaryAction()
            .let { action ->
                if (commandOpenHideSlowOption && action == CommandOpenSecondaryAction.SLOW) {
                    CommandOpenSecondaryAction.MEDIUM
                } else {
                    action
                }
            }
        return AppSettings(
            recognitionMode = prefs.getString(KEY_RECOGNITION_MODE, RecognitionMode.EDGE_SET.name)
                .toRecognitionMode(),
            useAccessibilityScreenshotCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                prefs.getBoolean(KEY_USE_ACCESSIBILITY_SCREENSHOT_CAPTURE, false)
            } else {
                false
            },
            autoGrantAccessibilityViaShizukuOnLaunch = prefs.getBoolean(
                KEY_AUTO_GRANT_ACCESSIBILITY_VIA_SHIZUKU_ON_LAUNCH,
                false,
            ),
            idleFrameIntervalMs = prefs.getLong(
                KEY_IDLE_FRAME_INTERVAL_MS,
                prefs.getLong(KEY_FRAME_INTERVAL_MS, 500L),
            ).coerceIn(120L, 5000L),
            nonIdleFrameIntervalMs = prefs.getLong(
                KEY_NON_IDLE_FRAME_INTERVAL_MS,
                prefs.getLong(KEY_GO_CHECK_INTERVAL_MS, prefs.getLong(KEY_FRAME_INTERVAL_MS, 120L)),
            ).coerceIn(30L, 1000L),
            debugPlaybackSpeed = prefs.getFloat(KEY_DEBUG_PLAYBACK_SPEED, 1.0f),
            edgeActivationThreshold = prefs.getFloat(KEY_EDGE_THRESHOLD, 26f),
            minimumLineBrightness = prefs.getFloat(KEY_MIN_LINE_BRIGHTNESS, 70f),
            minimumMatchScore = prefs.getFloat(KEY_MIN_MATCH_SCORE, 0.68f),
            startTemplateThreshold = prefs.getFloat(KEY_TEMPLATE_THRESHOLD, 0.84f),
            commandOpenMaxLuma = prefs.getFloat(KEY_COMMAND_OPEN_MAX_LUMA, 1f),
            glyphDisplayMinLuma = prefs.getFloat(KEY_GLYPH_DISPLAY_MIN_LUMA, 10f),
            glyphDisplayTopBarsMinLuma = prefs.getFloat(KEY_GLYPH_DISPLAY_TOP_BARS_MIN_LUMA, 1f),
            goColorDeltaThreshold = prefs.getFloat(KEY_GO_COLOR_DELTA, 3f),
            countdownVisibleThreshold = prefs.getFloat(KEY_COUNTDOWN_VISIBLE_THRESHOLD, 5f),
            progressVisibleThreshold = prefs.getFloat(KEY_PROGRESS_VISIBLE_THRESHOLD, 20f),
            firstBoxTopPercent = prefs.getFloat(KEY_FIRST_BOX_TOP_PERCENT, 9f).coerceIn(0f, 30f),
            firstBoxBottomPercent = prefs.getFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, 16.5f).coerceIn(0f, 30f),
            countdownTopPercent = prefs.getFloat(KEY_COUNTDOWN_TOP_PERCENT, 5.5f).coerceIn(0f, 30f),
            countdownBottomPercent = prefs.getFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, 9f).coerceIn(0f, 30f),
            progressTopPercent = prefs.getFloat(KEY_PROGRESS_TOP_PERCENT, 9.3f).coerceIn(0f, 30f),
            progressBottomPercent = prefs.getFloat(KEY_PROGRESS_BOTTOM_PERCENT, 10.5f).coerceIn(0f, 30f),
            drawEdgeDurationMs = prefs.getLong(KEY_DRAW_EDGE_MS, 250L),
            drawGlyphGapMs = prefs.getLong(KEY_DRAW_GAP_MS, 700L),
            drawTerminalDwellMs = prefs.getLong(KEY_DRAW_TERMINAL_DWELL_MS, 80L).coerceIn(0L, 200L),
            commandOpenPrimaryAction = prefs
                .getString(KEY_COMMAND_OPEN_PRIMARY_ACTION, CommandOpenPrimaryAction.SEND_SPEED.name)
                .toCommandOpenPrimaryAction(),
            commandOpenSecondaryAction = commandOpenSecondaryAction,
            commandOpenHideSlowOption = commandOpenHideSlowOption,
            doneButtonXPercent = prefs.getFloat(KEY_DONE_BUTTON_X_PERCENT, 75.56f).coerceIn(60f, 100f),
            doneButtonYPercent = prefs.getFloat(KEY_DONE_BUTTON_Y_PERCENT, 92.29f).coerceIn(90f, 100f),
            autoTapDoneAfterInput = prefs.getBoolean(KEY_AUTO_TAP_DONE_AFTER_INPUT, true),
            overlayXRatio = prefs.getFloat(KEY_OVERLAY_X_RATIO, 0.62f),
            overlayYRatio = prefs.getFloat(KEY_OVERLAY_Y_RATIO, 0.07f),
            blankReferenceUri = prefs.getString(KEY_BLANK_URI, null),
            startTemplateUri = prefs.getString(KEY_TEMPLATE_URI, null),
            startTemplateBase64 = prefs.getString(KEY_TEMPLATE_BASE64, null),
            readyBoxProfile = prefs.getString(KEY_READY_BOX_PROFILE, null).toReadyBoxProfile(),
            calibrationProfile = prefs.getString(KEY_CALIBRATION, null).toCalibrationProfile(),
            nodePatchSize = prefs.getInt(KEY_NODE_PATCH_SIZE, 0).coerceIn(0, 80),
            nodePatchMaxMae = prefs.getFloat(KEY_NODE_PATCH_MAX_MAE, 12f).coerceIn(1f, 80f),
            waitGoTimeoutMs = prefs.getLong(KEY_WAIT_GO_TIMEOUT_MS, 5000L).coerceIn(0L, 30000L),
            overlayScaleFactor = prefs.getFloat(KEY_OVERLAY_SCALE_FACTOR, 1.0f).coerceIn(0.5f, 3.0f),
            overlayGlyphSizeDp = prefs.getFloat(KEY_OVERLAY_GLYPH_SIZE_DP, 28f).coerceIn(12f, 80f),
            overlayShowGlyphSequence = prefs.getBoolean(KEY_OVERLAY_SHOW_GLYPH_SEQUENCE, true),
            overlaySequenceHideDelayAfterAutoDrawSec = prefs
                .getFloat(KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_AUTO_DRAW_SEC, 0f)
                .coerceIn(0f, 20f),
            overlaySequenceHideDelayAfterRecognitionOnlySec = prefs
                .getFloat(KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_RECOGNITION_ONLY_SEC, 15f)
                .coerceIn(0f, 20f),
            overlayVerticalSpacingDp = prefs.getFloat(KEY_OVERLAY_VERTICAL_SPACING_DP, 0f).coerceIn(0f, 40f),
            overlayOpacityPercent = prefs.getFloat(KEY_OVERLAY_OPACITY_PERCENT, 100f).coerceIn(0f, 100f),
            overlayHideCommandButtons = prefs.getBoolean(KEY_OVERLAY_HIDE_COMMAND_BUTTONS, false),
            inputEnabled = prefs.getBoolean(KEY_INPUT_ENABLED, true),
        )
    }

    private companion object {
        const val FILE_NAME = "glyph_hacker_settings"
        const val KEY_RECOGNITION_MODE = "recognition_mode"
        const val KEY_USE_ACCESSIBILITY_SCREENSHOT_CAPTURE = "use_accessibility_screenshot_capture"
        const val KEY_AUTO_GRANT_ACCESSIBILITY_VIA_SHIZUKU_ON_LAUNCH = "auto_grant_accessibility_via_shizuku_on_launch"
        const val KEY_IDLE_FRAME_INTERVAL_MS = "idle_frame_interval_ms"
        const val KEY_NON_IDLE_FRAME_INTERVAL_MS = "non_idle_frame_interval_ms"
        const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"
        const val KEY_GO_CHECK_INTERVAL_MS = "go_check_interval_ms"
        const val KEY_DEBUG_PLAYBACK_SPEED = "debug_playback_speed"
        const val KEY_EDGE_THRESHOLD = "edge_activation_threshold"
        const val KEY_MIN_LINE_BRIGHTNESS = "minimum_line_brightness"
        const val KEY_MIN_MATCH_SCORE = "minimum_match_score"
        const val KEY_TEMPLATE_THRESHOLD = "start_template_threshold"
        const val KEY_COMMAND_OPEN_MAX_LUMA = "command_open_max_luma"
        const val KEY_GLYPH_DISPLAY_MIN_LUMA = "glyph_display_min_luma"
        const val KEY_GLYPH_DISPLAY_TOP_BARS_MIN_LUMA = "glyph_display_top_bars_min_luma"
        const val KEY_GO_COLOR_DELTA = "go_color_delta_threshold"
        const val KEY_COUNTDOWN_VISIBLE_THRESHOLD = "countdown_visible_threshold"
        const val KEY_PROGRESS_VISIBLE_THRESHOLD = "progress_visible_threshold"
        const val KEY_FIRST_BOX_TOP_PERCENT = "first_box_top_percent"
        const val KEY_FIRST_BOX_BOTTOM_PERCENT = "first_box_bottom_percent"
        const val KEY_COUNTDOWN_TOP_PERCENT = "countdown_top_percent"
        const val KEY_COUNTDOWN_BOTTOM_PERCENT = "countdown_bottom_percent"
        const val KEY_PROGRESS_TOP_PERCENT = "progress_top_percent"
        const val KEY_PROGRESS_BOTTOM_PERCENT = "progress_bottom_percent"
        const val KEY_DRAW_EDGE_MS = "draw_edge_duration_ms"
        const val KEY_DRAW_GAP_MS = "draw_glyph_gap_ms"
        const val KEY_DRAW_TERMINAL_DWELL_MS = "draw_terminal_dwell_ms"
        const val KEY_COMMAND_OPEN_PRIMARY_ACTION = "command_open_primary_action"
        const val KEY_COMMAND_OPEN_SECONDARY_ACTION = "command_open_secondary_action"
        const val KEY_COMMAND_OPEN_HIDE_SLOW_OPTION = "command_open_hide_slow_option"
        const val KEY_DONE_BUTTON_X_PERCENT = "done_button_x_percent"
        const val KEY_DONE_BUTTON_Y_PERCENT = "done_button_y_percent"
        const val KEY_AUTO_TAP_DONE_AFTER_INPUT = "auto_tap_done_after_input"
        const val KEY_OVERLAY_X_RATIO = "overlay_x_ratio"
        const val KEY_OVERLAY_Y_RATIO = "overlay_y_ratio"
        const val KEY_BLANK_URI = "blank_reference_uri"
        const val KEY_TEMPLATE_URI = "start_template_uri"
        const val KEY_TEMPLATE_BASE64 = "start_template_base64"
        const val KEY_READY_BOX_PROFILE = "ready_box_profile_json"
        const val KEY_CALIBRATION = "calibration_profile_json"
        const val KEY_NODE_PATCH_SIZE = "node_patch_size"
        const val KEY_NODE_PATCH_MAX_MAE = "node_patch_max_mae"
        const val KEY_WAIT_GO_TIMEOUT_MS = "wait_go_timeout_ms"
        const val KEY_OVERLAY_SCALE_FACTOR = "overlay_scale_factor"
        const val KEY_OVERLAY_GLYPH_SIZE_DP = "overlay_glyph_size_dp"
        const val KEY_OVERLAY_SHOW_GLYPH_SEQUENCE = "overlay_show_glyph_sequence"
        const val KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_AUTO_DRAW_SEC = "overlay_sequence_hide_delay_after_auto_draw_sec"
        const val KEY_OVERLAY_SEQUENCE_HIDE_DELAY_AFTER_RECOGNITION_ONLY_SEC = "overlay_sequence_hide_delay_after_recognition_only_sec"
        const val KEY_OVERLAY_VERTICAL_SPACING_DP = "overlay_vertical_spacing_dp"
        const val KEY_OVERLAY_OPACITY_PERCENT = "overlay_opacity_percent"
        const val KEY_OVERLAY_HIDE_COMMAND_BUTTONS = "overlay_hide_command_buttons"
        const val KEY_INPUT_ENABLED = "input_enabled"
    }
}

private fun android.content.SharedPreferences.Editor.putNullableString(
    key: String,
    value: String?,
): android.content.SharedPreferences.Editor {
    return if (value == null) remove(key) else putString(key, value)
}

private fun String?.toRecognitionMode(): RecognitionMode {
    if (this.isNullOrBlank()) return RecognitionMode.EDGE_SET
    return RecognitionMode.entries.firstOrNull { it.name == this } ?: RecognitionMode.EDGE_SET
}

private fun String?.toCommandOpenPrimaryAction(): CommandOpenPrimaryAction {
    if (this.isNullOrBlank()) return CommandOpenPrimaryAction.SEND_SPEED
    return CommandOpenPrimaryAction.entries.firstOrNull { it.name == this } ?: CommandOpenPrimaryAction.SEND_SPEED
}

private fun String?.toCommandOpenSecondaryAction(): CommandOpenSecondaryAction {
    if (this.isNullOrBlank()) return CommandOpenSecondaryAction.MEDIUM
    return CommandOpenSecondaryAction.entries.firstOrNull { it.name == this } ?: CommandOpenSecondaryAction.MEDIUM
}

private fun String?.toCalibrationProfile(): CalibrationProfile? {
    if (this.isNullOrBlank()) return null
    return runCatching {
        JSONObject(this).toCalibrationProfile()
    }.getOrNull()
}

private fun JSONObject.toCalibrationProfile(): CalibrationProfile {
    val nodes = getJSONArray("nodes").toNodePositions()
    val patches = optJSONArray("nodePatches")?.toNodePatches() ?: emptyList()
    return CalibrationProfile(
        sourceWidth = getInt("sourceWidth"),
        sourceHeight = getInt("sourceHeight"),
        nodeRadiusPx = getDouble("nodeRadiusPx").toFloat(),
        nodes = nodes,
        roiLeft = optDouble("roiLeft", 0.0).toFloat(),
        roiTop = optDouble("roiTop", 0.0).toFloat(),
        roiRight = optDouble("roiRight", 1.0).toFloat(),
        roiBottom = optDouble("roiBottom", 1.0).toFloat(),
        nodePatches = patches,
    )
}

private fun JSONArray.toNodePositions(): List<NodePosition> {
    val result = ArrayList<NodePosition>(length())
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        result.add(
            NodePosition(
                index = item.getInt("index"),
                x = item.getDouble("x").toFloat(),
                y = item.getDouble("y").toFloat(),
            )
        )
    }
    return result.sortedBy { it.index }
}

private fun CalibrationProfile.toJson(): String {
    val json = JSONObject()
    json.put("sourceWidth", sourceWidth)
    json.put("sourceHeight", sourceHeight)
    json.put("nodeRadiusPx", nodeRadiusPx.toDouble())
    json.put("roiLeft", roiLeft.toDouble())
    json.put("roiTop", roiTop.toDouble())
    json.put("roiRight", roiRight.toDouble())
    json.put("roiBottom", roiBottom.toDouble())
    val nodes = JSONArray()
    this.nodes.sortedBy { it.index }.forEach { node ->
        val item = JSONObject()
        item.put("index", node.index)
        item.put("x", node.x.toDouble())
        item.put("y", node.y.toDouble())
        nodes.put(item)
    }
    json.put("nodes", nodes)
    if (nodePatches.isNotEmpty()) {
        val patchesArray = JSONArray()
        nodePatches.sortedBy { it.nodeIndex }.forEach { patch ->
            val item = JSONObject()
            item.put("nodeIndex", patch.nodeIndex)
            item.put("size", patch.size)
            item.put("luma", floatArrayToBase64(patch.luma))
            patchesArray.put(item)
        }
        json.put("nodePatches", patchesArray)
    }
    return json.toString()
}

private fun JSONArray.toNodePatches(): List<NodePatch> {
    val result = ArrayList<NodePatch>(length())
    for (i in 0 until length()) {
        val item = getJSONObject(i)
        val nodeIndex = item.getInt("nodeIndex")
        val size = item.getInt("size")
        val luma = base64ToFloatArray(item.getString("luma"))
        if (luma.size == size * size) {
            result.add(NodePatch(nodeIndex = nodeIndex, size = size, luma = luma))
        }
    }
    return result.sortedBy { it.nodeIndex }
}

private fun floatArrayToBase64(array: FloatArray): String {
    val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    array.forEach { buffer.putFloat(it) }
    return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
}

private fun base64ToFloatArray(encoded: String): FloatArray {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buffer.getFloat() }
}

private fun String?.toReadyBoxProfile(): ReadyBoxProfile? {
    if (this.isNullOrBlank()) return null
    return runCatching {
        JSONObject(this).toReadyBoxProfile()
    }.getOrNull()
}

private fun JSONObject.toReadyBoxProfile(): ReadyBoxProfile {
    val firstTop = getDouble("firstBoxTopNorm").toFloat()
    val firstBottom = getDouble("firstBoxBottomNorm").toFloat()
    val boxHeight = optDouble("boxHeightNorm", (firstBottom - firstTop).toDouble()).toFloat()
    val defaultCountdownTop = firstTop - boxHeight * 0.43f
    val defaultCountdownBottom = firstTop - boxHeight * 0.18f
    val defaultProgressTop = firstTop - boxHeight * 0.16f
    val defaultProgressBottom = firstTop - boxHeight * 0.06f
    return ReadyBoxProfile(
        firstBoxLeftNorm = getDouble("firstBoxLeftNorm").toFloat(),
        firstBoxTopNorm = firstTop,
        firstBoxRightNorm = getDouble("firstBoxRightNorm").toFloat(),
        firstBoxBottomNorm = firstBottom,
        boxHeightNorm = boxHeight,
        countdownLeftNorm = optDouble("countdownLeftNorm", getDouble("firstBoxLeftNorm")).toFloat(),
        countdownTopNorm = optDouble("countdownTopNorm", defaultCountdownTop.toDouble()).toFloat(),
        countdownRightNorm = optDouble("countdownRightNorm", getDouble("firstBoxRightNorm") + boxHeight.toDouble() * 3.0).toFloat(),
        countdownBottomNorm = optDouble("countdownBottomNorm", defaultCountdownBottom.toDouble()).toFloat(),
        progressLeftNorm = optDouble("progressLeftNorm", getDouble("firstBoxLeftNorm")).toFloat(),
        progressTopNorm = optDouble("progressTopNorm", defaultProgressTop.toDouble()).toFloat(),
        progressRightNorm = optDouble("progressRightNorm", getDouble("firstBoxRightNorm") + boxHeight.toDouble() * 3.0).toFloat(),
        progressBottomNorm = optDouble("progressBottomNorm", defaultProgressBottom.toDouble()).toFloat(),
    )
}

private fun ReadyBoxProfile.toJson(): String {
    val json = JSONObject()
    json.put("firstBoxLeftNorm", firstBoxLeftNorm.toDouble())
    json.put("firstBoxTopNorm", firstBoxTopNorm.toDouble())
    json.put("firstBoxRightNorm", firstBoxRightNorm.toDouble())
    json.put("firstBoxBottomNorm", firstBoxBottomNorm.toDouble())
    json.put("boxHeightNorm", boxHeightNorm.toDouble())
    json.put("countdownLeftNorm", countdownLeftNorm.toDouble())
    json.put("countdownTopNorm", countdownTopNorm.toDouble())
    json.put("countdownRightNorm", countdownRightNorm.toDouble())
    json.put("countdownBottomNorm", countdownBottomNorm.toDouble())
    json.put("progressLeftNorm", progressLeftNorm.toDouble())
    json.put("progressTopNorm", progressTopNorm.toDouble())
    json.put("progressRightNorm", progressRightNorm.toDouble())
    json.put("progressBottomNorm", progressBottomNorm.toDouble())
    return json.toString()
}

private fun AppSettings.toJson(): JSONObject {
    val json = JSONObject()
    json.put("recognitionMode", recognitionMode.name)
    json.put("useAccessibilityScreenshotCapture", useAccessibilityScreenshotCapture)
    json.put("autoGrantAccessibilityViaShizukuOnLaunch", autoGrantAccessibilityViaShizukuOnLaunch)
    json.put("idleFrameIntervalMs", idleFrameIntervalMs)
    json.put("nonIdleFrameIntervalMs", nonIdleFrameIntervalMs)
    json.put("frameIntervalMs", idleFrameIntervalMs)
    json.put("goCheckIntervalMs", nonIdleFrameIntervalMs)
    json.put("debugPlaybackSpeed", debugPlaybackSpeed.toDouble())
    json.put("edgeActivationThreshold", edgeActivationThreshold.toDouble())
    json.put("minimumLineBrightness", minimumLineBrightness.toDouble())
    json.put("minimumMatchScore", minimumMatchScore.toDouble())
    json.put("startTemplateThreshold", startTemplateThreshold.toDouble())
    json.put("commandOpenMaxLuma", commandOpenMaxLuma.toDouble())
    json.put("glyphDisplayMinLuma", glyphDisplayMinLuma.toDouble())
    json.put("glyphDisplayTopBarsMinLuma", glyphDisplayTopBarsMinLuma.toDouble())
    json.put("goColorDeltaThreshold", goColorDeltaThreshold.toDouble())
    json.put("countdownVisibleThreshold", countdownVisibleThreshold.toDouble())
    json.put("progressVisibleThreshold", progressVisibleThreshold.toDouble())
    json.put("firstBoxTopPercent", firstBoxTopPercent.toDouble())
    json.put("firstBoxBottomPercent", firstBoxBottomPercent.toDouble())
    json.put("countdownTopPercent", countdownTopPercent.toDouble())
    json.put("countdownBottomPercent", countdownBottomPercent.toDouble())
    json.put("progressTopPercent", progressTopPercent.toDouble())
    json.put("progressBottomPercent", progressBottomPercent.toDouble())
    json.put("drawEdgeDurationMs", drawEdgeDurationMs)
    json.put("drawGlyphGapMs", drawGlyphGapMs)
    json.put("drawTerminalDwellMs", drawTerminalDwellMs)
    json.put("commandOpenPrimaryAction", commandOpenPrimaryAction.name)
    json.put("commandOpenSecondaryAction", commandOpenSecondaryAction.name)
    json.put("commandOpenHideSlowOption", commandOpenHideSlowOption)
    json.put("doneButtonXPercent", doneButtonXPercent.toDouble())
    json.put("doneButtonYPercent", doneButtonYPercent.toDouble())
    json.put("autoTapDoneAfterInput", autoTapDoneAfterInput)
    json.put("overlayXRatio", overlayXRatio.toDouble())
    json.put("overlayYRatio", overlayYRatio.toDouble())
    json.put("blankReferenceUri", blankReferenceUri)
    json.put("startTemplateUri", startTemplateUri)
    json.put("startTemplateBase64", startTemplateBase64)
    calibrationProfile?.let {
        json.put("calibrationProfile", JSONObject(it.toJson()))
    }
    readyBoxProfile?.let {
        json.put("readyBoxProfile", JSONObject(it.toJson()))
    }
    json.put("nodePatchSize", nodePatchSize)
    json.put("nodePatchMaxMae", nodePatchMaxMae.toDouble())
    json.put("waitGoTimeoutMs", waitGoTimeoutMs)
    json.put("overlayScaleFactor", overlayScaleFactor.toDouble())
    json.put("overlayGlyphSizeDp", overlayGlyphSizeDp.toDouble())
    json.put("overlayShowGlyphSequence", overlayShowGlyphSequence)
    json.put(
        "overlaySequenceHideDelayAfterAutoDrawSec",
        overlaySequenceHideDelayAfterAutoDrawSec.toDouble(),
    )
    json.put(
        "overlaySequenceHideDelayAfterRecognitionOnlySec",
        overlaySequenceHideDelayAfterRecognitionOnlySec.toDouble(),
    )
    json.put("overlayVerticalSpacingDp", overlayVerticalSpacingDp.toDouble())
    json.put("overlayOpacityPercent", overlayOpacityPercent.toDouble())
    json.put("overlayHideCommandButtons", overlayHideCommandButtons)
    json.put("inputEnabled", inputEnabled)
    return json
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key)) return null
    if (isNull(key)) return null
    val value = optString(key, "")
    return value.takeIf { it.isNotBlank() }
}
