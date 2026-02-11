package moe.lyniko.glyphhacker.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.NodePosition
import moe.lyniko.glyphhacker.glyph.ReadyBoxProfile
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val _settingsFlow = MutableStateFlow(loadFromPrefs())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    suspend fun updateRecognitionMode(mode: RecognitionMode) {
        prefs.edit().putString(KEY_RECOGNITION_MODE, mode.name).apply()
        refresh()
    }

    suspend fun updateFrameIntervalMs(value: Long) {
        prefs.edit().putLong(KEY_FRAME_INTERVAL_MS, value.coerceIn(120L, 1000L)).apply()
        refresh()
    }

    suspend fun updateGoCheckIntervalMs(value: Long) {
        prefs.edit().putLong(KEY_GO_CHECK_INTERVAL_MS, value.coerceIn(30L, 300L)).apply()
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

    suspend fun updateStableFrameCount(value: Int) {
        prefs.edit().putInt(KEY_STABLE_FRAME_COUNT, value.coerceIn(1, 8)).apply()
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

    suspend fun updateGoColorDeltaThreshold(value: Float) {
        prefs.edit().putFloat(KEY_GO_COLOR_DELTA, value.coerceIn(1f, 80f)).apply()
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
        prefs.edit().putLong(KEY_DRAW_EDGE_MS, value.coerceIn(15L, 220L)).apply()
        refresh()
    }

    suspend fun updateDrawGlyphGapMs(value: Long) {
        prefs.edit().putLong(KEY_DRAW_GAP_MS, value.coerceIn(0L, 300L)).apply()
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
            val frameInterval = config.optLong("frameIntervalMs", 300L)
            val goInterval = config.optLong("goCheckIntervalMs", 90L)
            val debugPlaybackSpeed = config.optDouble("debugPlaybackSpeed", 1.0).toFloat()
            val edgeThreshold = config.optDouble("edgeActivationThreshold", 26.0).toFloat()
            val minLineBrightness = config.optDouble("minimumLineBrightness", 70.0).toFloat()
            val stableFrameCount = config.optInt("stableFrameCount", 1)
            val minMatchScore = config.optDouble("minimumMatchScore", 0.68).toFloat()
            val templateThreshold = config.optDouble("startTemplateThreshold", 0.84).toFloat()
            val commandOpenMaxLuma = config.optDouble("commandOpenMaxLuma", 1.0).toFloat()
            val glyphDisplayMinLuma = config.optDouble("glyphDisplayMinLuma", 10.0).toFloat()
            val goColorDelta = config.optDouble("goColorDeltaThreshold", 18.0).toFloat()
            val countdownVisibleThreshold = config.optDouble(
                "countdownVisibleThreshold",
                config.optDouble("readyBlackThresholdLuma", 5.0),
            ).toFloat()
            val progressVisibleThreshold = config.optDouble(
                "progressVisibleThreshold",
                config.optDouble("readyBlackThresholdLuma", 20.0),
            ).toFloat()
            val drawEdgeMs = config.optLong("drawEdgeDurationMs", 70L)
            val drawGapMs = config.optLong("drawGlyphGapMs", 55L)
            val doneButtonXPercent = config.optDouble("doneButtonXPercent", 75.56).toFloat()
            val doneButtonYPercent = config.optDouble("doneButtonYPercent", 92.29).toFloat()
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

            prefs.edit()
                .putString(KEY_RECOGNITION_MODE, mode.name)
                .putLong(KEY_FRAME_INTERVAL_MS, frameInterval.coerceIn(120L, 1000L))
                .putLong(KEY_GO_CHECK_INTERVAL_MS, goInterval.coerceIn(30L, 300L))
                .putFloat(KEY_DEBUG_PLAYBACK_SPEED, debugPlaybackSpeed.coerceIn(0.25f, 4.0f))
                .putFloat(KEY_EDGE_THRESHOLD, edgeThreshold.coerceIn(5f, 120f))
                .putFloat(KEY_MIN_LINE_BRIGHTNESS, minLineBrightness.coerceIn(5f, 255f))
                .putInt(KEY_STABLE_FRAME_COUNT, stableFrameCount.coerceIn(1, 8))
                .putFloat(KEY_MIN_MATCH_SCORE, minMatchScore.coerceIn(0.3f, 0.99f))
                .putFloat(KEY_TEMPLATE_THRESHOLD, templateThreshold.coerceIn(0.5f, 0.99f))
                .putFloat(KEY_COMMAND_OPEN_MAX_LUMA, commandOpenMaxLuma.coerceIn(0f, 30f))
                .putFloat(KEY_GLYPH_DISPLAY_MIN_LUMA, glyphDisplayMinLuma.coerceIn(0f, 80f))
                .putFloat(KEY_GO_COLOR_DELTA, goColorDelta.coerceIn(1f, 80f))
                .putFloat(KEY_COUNTDOWN_VISIBLE_THRESHOLD, countdownVisibleThreshold.coerceIn(1f, 40f))
                .putFloat(KEY_PROGRESS_VISIBLE_THRESHOLD, progressVisibleThreshold.coerceIn(1f, 80f))
                .putFloat(KEY_FIRST_BOX_TOP_PERCENT, firstBoxTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, firstBoxBottomPercent.coerceIn(0f, 30f))
                .putFloat(KEY_COUNTDOWN_TOP_PERCENT, countdownTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, countdownBottomPercent.coerceIn(0f, 30f))
                .putFloat(KEY_PROGRESS_TOP_PERCENT, progressTopPercent.coerceIn(0f, 30f))
                .putFloat(KEY_PROGRESS_BOTTOM_PERCENT, progressBottomPercent.coerceIn(0f, 30f))
                .putLong(KEY_DRAW_EDGE_MS, drawEdgeMs.coerceIn(15L, 220L))
                .putLong(KEY_DRAW_GAP_MS, drawGapMs.coerceIn(0L, 300L))
                .putFloat(KEY_DONE_BUTTON_X_PERCENT, doneButtonXPercent.coerceIn(60f, 100f))
                .putFloat(KEY_DONE_BUTTON_Y_PERCENT, doneButtonYPercent.coerceIn(90f, 100f))
                .putFloat(KEY_OVERLAY_X_RATIO, overlayXRatio.coerceIn(0f, 1f))
                .putFloat(KEY_OVERLAY_Y_RATIO, overlayYRatio.coerceIn(0f, 1f))
                .putNullableString(KEY_BLANK_URI, blankUri)
                .putNullableString(KEY_TEMPLATE_URI, templateUri)
                .putNullableString(KEY_TEMPLATE_BASE64, templateBase64)
                .putNullableString(KEY_CALIBRATION, calibration?.toJson())
                .putNullableString(KEY_READY_BOX_PROFILE, readyBoxProfile?.toJson())
                .apply()
            refresh()
        }
    }

    private fun refresh() {
        _settingsFlow.value = loadFromPrefs()
    }

    private fun loadFromPrefs(): AppSettings {
        return AppSettings(
            recognitionMode = prefs.getString(KEY_RECOGNITION_MODE, RecognitionMode.EDGE_SET.name)
                .toRecognitionMode(),
            frameIntervalMs = prefs.getLong(KEY_FRAME_INTERVAL_MS, 300L),
            goCheckIntervalMs = prefs.getLong(KEY_GO_CHECK_INTERVAL_MS, 90L),
            debugPlaybackSpeed = prefs.getFloat(KEY_DEBUG_PLAYBACK_SPEED, 1.0f),
            edgeActivationThreshold = prefs.getFloat(KEY_EDGE_THRESHOLD, 26f),
            minimumLineBrightness = prefs.getFloat(KEY_MIN_LINE_BRIGHTNESS, 70f),
            stableFrameCount = prefs.getInt(KEY_STABLE_FRAME_COUNT, 1),
            minimumMatchScore = prefs.getFloat(KEY_MIN_MATCH_SCORE, 0.68f),
            startTemplateThreshold = prefs.getFloat(KEY_TEMPLATE_THRESHOLD, 0.84f),
            commandOpenMaxLuma = prefs.getFloat(KEY_COMMAND_OPEN_MAX_LUMA, 1f),
            glyphDisplayMinLuma = prefs.getFloat(KEY_GLYPH_DISPLAY_MIN_LUMA, 10f),
            goColorDeltaThreshold = prefs.getFloat(KEY_GO_COLOR_DELTA, 18f),
            countdownVisibleThreshold = prefs.getFloat(KEY_COUNTDOWN_VISIBLE_THRESHOLD, 5f),
            progressVisibleThreshold = prefs.getFloat(KEY_PROGRESS_VISIBLE_THRESHOLD, 20f),
            firstBoxTopPercent = prefs.getFloat(KEY_FIRST_BOX_TOP_PERCENT, 9f).coerceIn(0f, 30f),
            firstBoxBottomPercent = prefs.getFloat(KEY_FIRST_BOX_BOTTOM_PERCENT, 16.5f).coerceIn(0f, 30f),
            countdownTopPercent = prefs.getFloat(KEY_COUNTDOWN_TOP_PERCENT, 5.5f).coerceIn(0f, 30f),
            countdownBottomPercent = prefs.getFloat(KEY_COUNTDOWN_BOTTOM_PERCENT, 9f).coerceIn(0f, 30f),
            progressTopPercent = prefs.getFloat(KEY_PROGRESS_TOP_PERCENT, 9.3f).coerceIn(0f, 30f),
            progressBottomPercent = prefs.getFloat(KEY_PROGRESS_BOTTOM_PERCENT, 10.5f).coerceIn(0f, 30f),
            drawEdgeDurationMs = prefs.getLong(KEY_DRAW_EDGE_MS, 70L),
            drawGlyphGapMs = prefs.getLong(KEY_DRAW_GAP_MS, 55L),
            doneButtonXPercent = prefs.getFloat(KEY_DONE_BUTTON_X_PERCENT, 75.56f).coerceIn(60f, 100f),
            doneButtonYPercent = prefs.getFloat(KEY_DONE_BUTTON_Y_PERCENT, 92.29f).coerceIn(90f, 100f),
            overlayXRatio = prefs.getFloat(KEY_OVERLAY_X_RATIO, 0.62f),
            overlayYRatio = prefs.getFloat(KEY_OVERLAY_Y_RATIO, 0.07f),
            blankReferenceUri = prefs.getString(KEY_BLANK_URI, null),
            startTemplateUri = prefs.getString(KEY_TEMPLATE_URI, null),
            startTemplateBase64 = prefs.getString(KEY_TEMPLATE_BASE64, null),
            readyBoxProfile = prefs.getString(KEY_READY_BOX_PROFILE, null).toReadyBoxProfile(),
            calibrationProfile = prefs.getString(KEY_CALIBRATION, null).toCalibrationProfile(),
        )
    }

    private companion object {
        const val FILE_NAME = "glyph_hacker_settings"
        const val KEY_RECOGNITION_MODE = "recognition_mode"
        const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"
        const val KEY_GO_CHECK_INTERVAL_MS = "go_check_interval_ms"
        const val KEY_DEBUG_PLAYBACK_SPEED = "debug_playback_speed"
        const val KEY_EDGE_THRESHOLD = "edge_activation_threshold"
        const val KEY_MIN_LINE_BRIGHTNESS = "minimum_line_brightness"
        const val KEY_STABLE_FRAME_COUNT = "stable_frame_count"
        const val KEY_MIN_MATCH_SCORE = "minimum_match_score"
        const val KEY_TEMPLATE_THRESHOLD = "start_template_threshold"
        const val KEY_COMMAND_OPEN_MAX_LUMA = "command_open_max_luma"
        const val KEY_GLYPH_DISPLAY_MIN_LUMA = "glyph_display_min_luma"
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
        const val KEY_DONE_BUTTON_X_PERCENT = "done_button_x_percent"
        const val KEY_DONE_BUTTON_Y_PERCENT = "done_button_y_percent"
        const val KEY_OVERLAY_X_RATIO = "overlay_x_ratio"
        const val KEY_OVERLAY_Y_RATIO = "overlay_y_ratio"
        const val KEY_BLANK_URI = "blank_reference_uri"
        const val KEY_TEMPLATE_URI = "start_template_uri"
        const val KEY_TEMPLATE_BASE64 = "start_template_base64"
        const val KEY_READY_BOX_PROFILE = "ready_box_profile_json"
        const val KEY_CALIBRATION = "calibration_profile_json"
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

private fun String?.toCalibrationProfile(): CalibrationProfile? {
    if (this.isNullOrBlank()) return null
    return runCatching {
        JSONObject(this).toCalibrationProfile()
    }.getOrNull()
}

private fun JSONObject.toCalibrationProfile(): CalibrationProfile {
    val nodes = getJSONArray("nodes").toNodePositions()
    return CalibrationProfile(
        sourceWidth = getInt("sourceWidth"),
        sourceHeight = getInt("sourceHeight"),
        nodeRadiusPx = getDouble("nodeRadiusPx").toFloat(),
        nodes = nodes,
        roiLeft = optDouble("roiLeft", 0.0).toFloat(),
        roiTop = optDouble("roiTop", 0.0).toFloat(),
        roiRight = optDouble("roiRight", 1.0).toFloat(),
        roiBottom = optDouble("roiBottom", 1.0).toFloat(),
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
    return json.toString()
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
    json.put("frameIntervalMs", frameIntervalMs)
    json.put("goCheckIntervalMs", goCheckIntervalMs)
    json.put("debugPlaybackSpeed", debugPlaybackSpeed.toDouble())
    json.put("edgeActivationThreshold", edgeActivationThreshold.toDouble())
    json.put("minimumLineBrightness", minimumLineBrightness.toDouble())
    json.put("stableFrameCount", stableFrameCount)
    json.put("minimumMatchScore", minimumMatchScore.toDouble())
    json.put("startTemplateThreshold", startTemplateThreshold.toDouble())
    json.put("commandOpenMaxLuma", commandOpenMaxLuma.toDouble())
    json.put("glyphDisplayMinLuma", glyphDisplayMinLuma.toDouble())
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
    json.put("doneButtonXPercent", doneButtonXPercent.toDouble())
    json.put("doneButtonYPercent", doneButtonYPercent.toDouble())
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
    return json
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key)) return null
    if (isNull(key)) return null
    val value = optString(key, "")
    return value.takeIf { it.isNotBlank() }
}
