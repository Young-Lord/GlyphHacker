package moe.lyniko.glyphhacker.debug

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lyniko.glyphhacker.data.AppSettings
import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.GlyphPhase
import moe.lyniko.glyphhacker.glyph.GlyphRecognitionEngine
import moe.lyniko.glyphhacker.glyph.GlyphSnapshot
import moe.lyniko.glyphhacker.util.resizeBitmapToMax
import java.util.TreeMap

data class DebugFrameResult(
    val timestampMs: Long,
    val durationMs: Long,
    val frame: Bitmap,
    val snapshot: GlyphSnapshot,
    /** AUTO_DRAW 开始时的视频时间戳；-1 表示尚未触发。 */
    val drawStartVideoMs: Long = -1L,
)

class VideoDebugAnalyzer(
    private val recognitionEngine: GlyphRecognitionEngine = GlyphRecognitionEngine(),
) {

    private companion object {
        const val CHECKPOINT_INTERVAL_MS = 300L
        const val MAX_CHECKPOINT_COUNT = 360
    }

    private var retriever: MediaMetadataRetriever? = null
    private var sourceUri: String? = null
    private var durationMs: Long = 0L
    private val sessionCheckpoints = TreeMap<Long, Pair<GlyphRecognitionEngine.SessionState, Long>>()
    /** AUTO_DRAW 开始时的视频时间戳；-1 表示尚未触发。 */
    private var drawStartVideoMs: Long = -1L

    suspend fun prepare(context: Context, videoUri: Uri): Long {
        return withContext(Dispatchers.IO) {
            val uriText = videoUri.toString()
            if (retriever == null || sourceUri != uriText) {
                releaseInternal()
                retriever = MediaMetadataRetriever().also {
                    it.setDataSource(context, videoUri)
                    durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L
                }
                sourceUri = uriText
            }
            durationMs
        }
    }

    fun resetSession(clearCheckpoints: Boolean = false) {
        recognitionEngine.resetSession()
        drawStartVideoMs = -1L
        if (clearCheckpoints) {
            sessionCheckpoints.clear()
        }
    }

    fun release() {
        recognitionEngine.resetSession()
        drawStartVideoMs = -1L
        sessionCheckpoints.clear()
        releaseInternal()
    }

    suspend fun replayToTimestamp(
        context: Context,
        videoUri: Uri,
        targetTimestampMs: Long,
        stepMs: Long,
        settings: AppSettings,
        calibrationProfile: CalibrationProfile,
        startTemplate: Bitmap?,
        maxWarmupMs: Long = 6_000L,
    ): DebugFrameResult? {
        val totalDuration = prepare(context, videoUri)
        if (totalDuration <= 0L) {
            return null
        }

        val clampedTarget = targetTimestampMs.coerceIn(0L, totalDuration)
        val safeStep = stepMs.coerceIn(40L, 1_200L)
        val warmupWindow = maxWarmupMs.coerceIn(safeStep, 20_000L)

        var checkpointEntry = sessionCheckpoints.floorEntry(clampedTarget)
        if (checkpointEntry != null && checkpointEntry.key == clampedTarget) {
            checkpointEntry = sessionCheckpoints.lowerEntry(clampedTarget)
        }

        val canUseCheckpoint = checkpointEntry != null &&
            (clampedTarget - checkpointEntry.key) <= warmupWindow

        val replayStart = if (canUseCheckpoint) {
            val (engineState, savedDrawStart) = checkpointEntry.value
            recognitionEngine.restoreSessionState(engineState)
            drawStartVideoMs = savedDrawStart
            checkpointEntry.key
        } else {
            recognitionEngine.resetSession()
            drawStartVideoMs = -1L
            (clampedTarget - warmupWindow).coerceAtLeast(0L)
        }

        var ts = replayStart
        var lastResult: DebugFrameResult? = null
        val engineSettings = settings.toEngineSettings()

        if (!canUseCheckpoint) {
            lastResult = analyzePreparedFrame(
                timestampMs = ts,
                totalDuration = totalDuration,
                engineSettings = engineSettings,
                calibrationProfile = calibrationProfile,
                readyBoxProfile = settings.readyBoxProfile,
                startTemplate = startTemplate,
            )
        }

        while (ts < clampedTarget) {
            ts = (ts + safeStep).coerceAtMost(clampedTarget)
            lastResult = analyzePreparedFrame(
                timestampMs = ts,
                totalDuration = totalDuration,
                engineSettings = engineSettings,
                calibrationProfile = calibrationProfile,
                readyBoxProfile = settings.readyBoxProfile,
                startTemplate = startTemplate,
            )
        }

        return lastResult
    }

    suspend fun analyzeFrame(
        context: Context,
        videoUri: Uri,
        timestampMs: Long,
        settings: AppSettings,
        calibrationProfile: CalibrationProfile,
        startTemplate: Bitmap?,
    ): DebugFrameResult? {
        val totalDuration = prepare(context, videoUri)
        if (totalDuration <= 0L) {
            return null
        }
        return analyzePreparedFrame(
            timestampMs = timestampMs,
            totalDuration = totalDuration,
            engineSettings = settings.toEngineSettings(),
            calibrationProfile = calibrationProfile,
            readyBoxProfile = settings.readyBoxProfile,
            startTemplate = startTemplate,
        )
    }

    private suspend fun analyzePreparedFrame(
        timestampMs: Long,
        totalDuration: Long,
        engineSettings: GlyphRecognitionEngine.EngineSettings,
        calibrationProfile: CalibrationProfile,
        readyBoxProfile: moe.lyniko.glyphhacker.glyph.ReadyBoxProfile?,
        startTemplate: Bitmap?,
    ): DebugFrameResult? {
        val safeTimestamp = timestampMs.coerceIn(0L, totalDuration)

        val rawFrame = withContext(Dispatchers.IO) {
            retriever?.getFrameAtTime(safeTimestamp * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null

        val frame = withContext(Dispatchers.Default) {
            val resized = resizeBitmapToMax(rawFrame, 960)
            if (resized !== rawFrame) {
                rawFrame.recycle()
            }
            resized
        }

        val snapshot = withContext(Dispatchers.Default) {
            recognitionEngine.processFrame(
                bitmap = frame,
                calibrationProfile = calibrationProfile,
                settings = engineSettings,
                readyBoxProfile = readyBoxProfile,
            )
        }

        // 追踪 draw 开始的视频时间戳
        if (snapshot.drawRequested) {
            drawStartVideoMs = safeTimestamp
        } else if (snapshot.phase == GlyphPhase.IDLE) {
            drawStartVideoMs = -1L
        }

        rememberCheckpoint(safeTimestamp)

        return DebugFrameResult(
            timestampMs = safeTimestamp,
            durationMs = totalDuration,
            frame = frame,
            snapshot = snapshot,
            drawStartVideoMs = drawStartVideoMs,
        )
    }

    private fun rememberCheckpoint(timestampMs: Long) {
        val lower = sessionCheckpoints.floorKey(timestampMs)
        val higher = sessionCheckpoints.ceilingKey(timestampMs)
        val hasNearbyLower = lower != null && (timestampMs - lower) < CHECKPOINT_INTERVAL_MS
        val hasNearbyHigher = higher != null && (higher - timestampMs) < CHECKPOINT_INTERVAL_MS
        if (hasNearbyLower || hasNearbyHigher) {
            return
        }
        sessionCheckpoints[timestampMs] = recognitionEngine.snapshotSessionState() to drawStartVideoMs
        while (sessionCheckpoints.size > MAX_CHECKPOINT_COUNT) {
            sessionCheckpoints.pollFirstEntry()
        }
    }

    private fun AppSettings.toEngineSettings(): GlyphRecognitionEngine.EngineSettings {
        return GlyphRecognitionEngine.EngineSettings(
            edgeActivationThreshold = edgeActivationThreshold,
            minimumLineBrightness = minimumLineBrightness,
            minimumMatchScore = minimumMatchScore,
            commandOpenMaxLuma = commandOpenMaxLuma,
            glyphDisplayMinLuma = glyphDisplayMinLuma,
            glyphDisplayTopBarsMinLuma = glyphDisplayTopBarsMinLuma,
            goColorDeltaThreshold = goColorDeltaThreshold,
            countdownVisibleThreshold = countdownVisibleThreshold,
            progressVisibleThreshold = progressVisibleThreshold,
            firstBoxTopPercent = firstBoxTopPercent,
            firstBoxBottomPercent = firstBoxBottomPercent,
            countdownTopPercent = countdownTopPercent,
            countdownBottomPercent = countdownBottomPercent,
            progressTopPercent = progressTopPercent,
            progressBottomPercent = progressBottomPercent,
            suppressGlyphTracking = false,
            nodePatchSize = 0, // 录屏压缩导致逐像素比对失败，跳过
            nodePatchMaxMae = nodePatchMaxMae,
            waitGoTimeoutMs = waitGoTimeoutMs,
        )
    }

    private fun releaseInternal() {
        runCatching { retriever?.release() }
        retriever = null
        sourceUri = null
        durationMs = 0L
    }
}
