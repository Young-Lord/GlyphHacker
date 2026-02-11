package moe.lyniko.glyphhacker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.lyniko.glyphhacker.MainActivity
import moe.lyniko.glyphhacker.R
import moe.lyniko.glyphhacker.accessibility.AccessibilityScreenshotBus
import moe.lyniko.glyphhacker.accessibility.DrawCommand
import moe.lyniko.glyphhacker.accessibility.DrawCommandBus
import moe.lyniko.glyphhacker.data.AppSettings
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.data.SettingsRepository
import moe.lyniko.glyphhacker.glyph.GlyphPhase
import moe.lyniko.glyphhacker.glyph.GlyphRecognitionEngine

class CaptureForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val recognitionEngine = GlyphRecognitionEngine()

    private var settings: AppSettings = AppSettings()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallbackRegistered: Boolean = false
    @Volatile
    private var internalStopInProgress: Boolean = false
    private var frameCounter: Long = 0L
    private var consecutiveNullImageCount: Int = 0
    private var calibrationMissingLogged: Boolean = false
    private var lastLoggedPhase: GlyphPhase = GlyphPhase.IDLE
    private var commandOpenPresetIssued: Boolean = false
    private var foregroundStarted: Boolean = false
    private var foregroundUsesAccessibilityType: Boolean = false
    private var recognitionPaused: Boolean = false
    private var useAccessibilityScreenshotMode: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (internalStopInProgress) {
                return
            }
            Log.w(LOG_TAG, "[CAPTURE] media projection stopped by system")
            RuntimeStateBus.setRecognitionEnabled(false)
            serviceScope.launch {
                stopSelfSafely()
            }
        }
    }

    private var settingsJob: Job? = null
    private var drawCompletionJob: Job? = null
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "[CAPTURE] service created")
        createNotificationChannel()
        observeSettings()
        observeDrawCompletions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(LOG_TAG, "[CAPTURE] onStartCommand action=${intent?.action ?: "<null>"} startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                useAccessibilityScreenshotMode = false
                if (captureJob?.isActive == true && mediaProjection != null && virtualDisplay != null) {
                    Log.i(LOG_TAG, "[CAPTURE] start ignored because capture is already active")
                    return START_STICKY
                }
                if (!startAsForeground(useAccessibilityScreenshot = false)) {
                    return START_NOT_STICKY
                }
                if (mediaProjection == null) {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                    val permissionData = intent.intentExtra(EXTRA_RESULT_DATA)
                    if (resultCode == Int.MIN_VALUE || permissionData == null) {
                        Log.e(LOG_TAG, "[CAPTURE] missing projection permission extras; stopping service")
                        stopSelfSafely()
                        return START_NOT_STICKY
                    }
                    if (!initProjection(resultCode, permissionData)) {
                        return START_NOT_STICKY
                    }
                }
                startCaptureLoop()
            }

            ACTION_START_ACCESSIBILITY -> {
                if (captureJob?.isActive == true && useAccessibilityScreenshotMode) {
                    Log.i(LOG_TAG, "[CAPTURE] start ignored because accessibility screenshot capture is already active")
                    return START_STICKY
                }
                if (!startAsForeground(useAccessibilityScreenshot = true)) {
                    return START_NOT_STICKY
                }
                useAccessibilityScreenshotMode = true
                stopCaptureResources()
                startAccessibilityScreenshotLoop()
            }

            ACTION_RESTART -> {
                if (!startAsForeground(useAccessibilityScreenshot = false)) {
                    return START_NOT_STICKY
                }
                useAccessibilityScreenshotMode = false
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val permissionData = intent.intentExtra(EXTRA_RESULT_DATA)
                if (resultCode == Int.MIN_VALUE || permissionData == null) {
                    Log.e(LOG_TAG, "[CAPTURE] missing projection permission extras for restart; stopping service")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                RuntimeStateBus.setRecognitionEnabled(true)
                stopCaptureResources()
                if (!initProjection(resultCode, permissionData)) {
                    return START_NOT_STICKY
                }
                startCaptureLoop()
                return START_STICKY
            }

            ACTION_RESTART_ACCESSIBILITY -> {
                if (!startAsForeground(useAccessibilityScreenshot = true)) {
                    return START_NOT_STICKY
                }
                RuntimeStateBus.setRecognitionEnabled(true)
                useAccessibilityScreenshotMode = true
                stopCaptureResources()
                startAccessibilityScreenshotLoop()
                return START_STICKY
            }

            ACTION_RESET_TO_IDLE -> {
                Log.i(LOG_TAG, "[CAPTURE] received reset-to-idle action")
                recognitionEngine.resetSession()
                commandOpenPresetIssued = false
                recognitionPaused = false
                RuntimeStateBus.setRecognitionEnabled(true)
                val running = captureJob?.isActive == true && foregroundStarted
                RuntimeStateBus.setIdle(captureRunning = running)
                return START_STICKY
            }

            ACTION_STOP -> {
                Log.i(LOG_TAG, "[CAPTURE] received explicit stop action")
                if (mediaProjection == null && !foregroundStarted && captureJob?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                RuntimeStateBus.setRecognitionEnabled(false)
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(LOG_TAG, "[CAPTURE] service destroyed")
        stopCaptureResources()
        settingsJob?.cancel()
        drawCompletionJob?.cancel()
        captureJob?.cancel()
        RuntimeStateBus.reset()
        foregroundStarted = false
        foregroundUsesAccessibilityType = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { next ->
                settings = next
                Log.d(
                    LOG_TAG,
                    "[CAPTURE] settings frameInterval=${next.frameIntervalMs}ms goCheck=${next.goCheckIntervalMs}ms stable=${next.stableFrameCount} edgeTh=${next.edgeActivationThreshold} minLine=${next.minimumLineBrightness} accessibilityShot=${next.useAccessibilityScreenshotCapture}",
                )
            }
        }
    }

    private fun observeDrawCompletions() {
        drawCompletionJob?.cancel()
        drawCompletionJob = serviceScope.launch {
            DrawCommandBus.completions.collectLatest { completion ->
                if (!completion.doneButtonTapped) {
                    return@collectLatest
                }
                recognitionEngine.resetSession()
                RuntimeStateBus.setIdle()
                lastLoggedPhase = GlyphPhase.IDLE
                commandOpenPresetIssued = false
                Log.i(
                    LOG_TAG,
                    "[CAPTURE][F${completion.sourceFrameId}] done tap confirmed; reset to IDLE immediately",
                )
            }
        }
    }

    private fun startAsForeground(useAccessibilityScreenshot: Boolean): Boolean {
        if (foregroundStarted && foregroundUsesAccessibilityType == useAccessibilityScreenshot) {
            return true
        }
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val foregroundType = if (useAccessibilityScreenshot) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(
                    CAPTURE_NOTIFICATION_ID,
                    notification,
                    foregroundType,
                )
            } else {
                startForeground(CAPTURE_NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (!started) {
            Log.e(LOG_TAG, "[CAPTURE] startForeground failed")
            if (!useAccessibilityScreenshot) {
                requestProjectionPermission()
            }
            stopSelf()
            return false
        }

        foregroundStarted = true
        foregroundUsesAccessibilityType = useAccessibilityScreenshot
        return true
    }

    private fun initProjection(resultCode: Int, permissionData: Intent): Boolean {
        stopCaptureLoopOnly()
        internalStopInProgress = false
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, permissionData)
        }.getOrNull()
        mediaProjection = projection
        if (projection == null) {
            Log.e(LOG_TAG, "[CAPTURE] getMediaProjection returned null")
            stopSelfSafely()
            return false
        }

        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projectionCallbackRegistered = true
        return true
    }

    private fun startCaptureLoop() {
        val projection = mediaProjection ?: run {
            Log.e(LOG_TAG, "[CAPTURE] mediaProjection unavailable while starting capture")
            stopSelfSafely()
            return
        }

        stopCaptureLoopOnly()
        recognitionEngine.resetSession()
        internalStopInProgress = false
        frameCounter = 0L
        consecutiveNullImageCount = 0
        calibrationMissingLogged = false
        lastLoggedPhase = GlyphPhase.IDLE
        recognitionPaused = false
        Log.i(
            LOG_TAG,
            "[CAPTURE] starting capture frameInterval=${settings.frameIntervalMs}ms goCheck=${settings.goCheckIntervalMs}ms",
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi
        Log.i(LOG_TAG, "[CAPTURE] projection metrics=${width}x$height densityDpi=$densityDpi")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                "GlyphHackCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null,
            )
        }.getOrElse {
            Log.e(LOG_TAG, "[CAPTURE] createVirtualDisplay failed; projection permission likely expired", it)
            requestProjectionPermission()
            stopSelfSafely()
            return
        }
        Log.i(LOG_TAG, "[CAPTURE] virtual display created successfully")

        captureJob?.cancel()
        captureJob = serviceScope.launch {
            RuntimeStateBus.setCaptureRunning(true)
            var nextDelayMs = settings.frameIntervalMs.coerceIn(120L, 1000L)
            while (isActive) {
                val frameId = ++frameCounter
                val loopStartNs = SystemClock.elapsedRealtimeNanos()
                val acquireStartNs = SystemClock.elapsedRealtimeNanos()
                val image = imageReader?.acquireLatestImage()
                val acquireDurationMs = elapsedMs(acquireStartNs)
                if (image != null) {
                    consecutiveNullImageCount = 0
                    val recognitionEnabled = RuntimeStateBus.state.value.recognitionEnabled
                    if (!recognitionEnabled) {
                        image.close()
                        if (!recognitionPaused) {
                            recognitionPaused = true
                            recognitionEngine.resetSession()
                            lastLoggedPhase = GlyphPhase.IDLE
                            RuntimeStateBus.setDrawRemainingCount(0)
                            Log.i(LOG_TAG, "[CAPTURE] recognition paused; keep projection alive")
                        }
                        nextDelayMs = settings.frameIntervalMs.coerceIn(120L, 1000L)
                        delay(nextDelayMs)
                        continue
                    }
                    if (recognitionPaused) {
                        recognitionPaused = false
                        recognitionEngine.resetSession()
                        lastLoggedPhase = GlyphPhase.IDLE
                        Log.i(LOG_TAG, "[CAPTURE] recognition resumed")
                    }
                    val convertStartNs = SystemClock.elapsedRealtimeNanos()
                    val bitmap = ImageFrameConverter.toBitmap(image)
                    image.close()
                    val convertDurationMs = elapsedMs(convertStartNs)
                    val frameCapturedAtElapsedMs = SystemClock.elapsedRealtime()
                    val analysisStartNs = SystemClock.elapsedRealtimeNanos()
                    val snapshot = runFrameAnalysis(
                        frame = bitmap,
                        frameId = frameId,
                        frameCapturedAtElapsedMs = frameCapturedAtElapsedMs,
                    )
                    val analysisDurationMs = elapsedMs(analysisStartNs)
                    nextDelayMs = if (snapshot?.goMatched == true && snapshot.phase == GlyphPhase.WAIT_GO) {
                        settings.goCheckIntervalMs.coerceIn(30L, 300L)
                    } else {
                        settings.frameIntervalMs.coerceIn(120L, 1000L)
                    }
                    val totalDurationMs = elapsedMs(loopStartNs)
                    Log.d(
                        LOG_TAG,
                        "[CAPTURE][F$frameId] acquire=${acquireDurationMs}ms convert=${convertDurationMs}ms analyze=${analysisDurationMs}ms total=${totalDurationMs}ms sleep=${nextDelayMs}ms phase=${snapshot?.phase ?: "NO_PROFILE"} glyph=${snapshot?.currentGlyph ?: "-"} conf=${snapshot?.currentConfidence ?: 0f} edges=${snapshot?.activeEdges?.size ?: 0} go=${snapshot?.goMatched ?: false} draw=${snapshot?.drawRequested ?: false} seq=${formatSequence(snapshot?.sequence.orEmpty())}",
                    )
                    if (totalDurationMs > nextDelayMs) {
                        Log.w(
                            LOG_TAG,
                            "[CAPTURE][F$frameId] frame work ${totalDurationMs}ms exceeds sleep ${nextDelayMs}ms; pipeline is back-pressured",
                        )
                    }
                } else {
                    consecutiveNullImageCount += 1
                    if (consecutiveNullImageCount <= 3 || consecutiveNullImageCount % 20 == 0) {
                        Log.d(
                            LOG_TAG,
                            "[CAPTURE][F$frameId] no image available (streak=$consecutiveNullImageCount) acquire=${acquireDurationMs}ms sleep=${nextDelayMs}ms",
                        )
                    }
                }
                delay(nextDelayMs)
            }
        }
    }

    private fun startAccessibilityScreenshotLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(LOG_TAG, "[CAPTURE] accessibility screenshot mode requires API 30+")
            stopSelfSafely()
            return
        }

        stopCaptureLoopOnly()
        recognitionEngine.resetSession()
        internalStopInProgress = false
        frameCounter = 0L
        consecutiveNullImageCount = 0
        calibrationMissingLogged = false
        lastLoggedPhase = GlyphPhase.IDLE
        recognitionPaused = false
        Log.i(
            LOG_TAG,
            "[CAPTURE] starting accessibility screenshot loop frameInterval=${settings.frameIntervalMs}ms goCheck=${settings.goCheckIntervalMs}ms",
        )

        captureJob?.cancel()
        captureJob = serviceScope.launch {
            RuntimeStateBus.setCaptureRunning(true)
            var nextDelayMs = settings.frameIntervalMs.coerceIn(120L, 1000L)
            while (isActive) {
                val frameId = ++frameCounter
                val loopStartNs = SystemClock.elapsedRealtimeNanos()
                val recognitionEnabled = RuntimeStateBus.state.value.recognitionEnabled
                if (!recognitionEnabled) {
                    if (!recognitionPaused) {
                        recognitionPaused = true
                        recognitionEngine.resetSession()
                        lastLoggedPhase = GlyphPhase.IDLE
                        RuntimeStateBus.setDrawRemainingCount(0)
                        Log.i(LOG_TAG, "[CAPTURE] recognition paused; keep accessibility screenshot loop alive")
                    }
                    nextDelayMs = settings.frameIntervalMs.coerceIn(120L, 1000L)
                    delay(nextDelayMs)
                    continue
                }
                if (recognitionPaused) {
                    recognitionPaused = false
                    recognitionEngine.resetSession()
                    lastLoggedPhase = GlyphPhase.IDLE
                    Log.i(LOG_TAG, "[CAPTURE] recognition resumed")
                }

                val screenshotStartNs = SystemClock.elapsedRealtimeNanos()
                val screenshotResult = AccessibilityScreenshotBus.requestFrame(
                    frameId = frameId,
                    timeoutMs = ACCESSIBILITY_SCREENSHOT_TIMEOUT_MS,
                )
                val screenshotDurationMs = elapsedMs(screenshotStartNs)
                val bitmap = screenshotResult?.bitmap

                if (bitmap != null) {
                    consecutiveNullImageCount = 0
                    val frameCapturedAtElapsedMs = screenshotResult.capturedAtElapsedMs
                    val analysisStartNs = SystemClock.elapsedRealtimeNanos()
                    val snapshot = runFrameAnalysis(
                        frame = bitmap,
                        frameId = frameId,
                        frameCapturedAtElapsedMs = frameCapturedAtElapsedMs,
                    )
                    val analysisDurationMs = elapsedMs(analysisStartNs)
                    nextDelayMs = if (snapshot?.goMatched == true && snapshot.phase == GlyphPhase.WAIT_GO) {
                        settings.goCheckIntervalMs.coerceIn(30L, 300L)
                    } else {
                        settings.frameIntervalMs.coerceIn(120L, 1000L)
                    }
                    val totalDurationMs = elapsedMs(loopStartNs)
                    Log.d(
                        LOG_TAG,
                        "[CAPTURE][F$frameId][ACC] screenshot=${screenshotDurationMs}ms analyze=${analysisDurationMs}ms total=${totalDurationMs}ms sleep=${nextDelayMs}ms phase=${snapshot?.phase ?: "NO_PROFILE"} glyph=${snapshot?.currentGlyph ?: "-"} conf=${snapshot?.currentConfidence ?: 0f} edges=${snapshot?.activeEdges?.size ?: 0} go=${snapshot?.goMatched ?: false} draw=${snapshot?.drawRequested ?: false} seq=${formatSequence(snapshot?.sequence.orEmpty())}",
                    )
                    if (totalDurationMs > nextDelayMs) {
                        Log.w(
                            LOG_TAG,
                            "[CAPTURE][F$frameId][ACC] frame work ${totalDurationMs}ms exceeds sleep ${nextDelayMs}ms; pipeline is back-pressured",
                        )
                    }
                } else {
                    consecutiveNullImageCount += 1
                    val error = screenshotResult?.error ?: if (!AccessibilityScreenshotBus.serviceConnected.value) {
                        "service_disconnected"
                    } else {
                        "timeout_or_no_frame"
                    }
                    if (consecutiveNullImageCount <= 3 || consecutiveNullImageCount % 20 == 0) {
                        Log.d(
                            LOG_TAG,
                            "[CAPTURE][F$frameId][ACC] screenshot unavailable (streak=$consecutiveNullImageCount error=$error) capture=${screenshotDurationMs}ms sleep=${nextDelayMs}ms",
                        )
                    }
                }
                delay(nextDelayMs)
            }
        }
    }

    private fun runFrameAnalysis(
        frame: Bitmap,
        frameId: Long,
        frameCapturedAtElapsedMs: Long,
    ): moe.lyniko.glyphhacker.glyph.GlyphSnapshot? {
        val profile = settings.calibrationProfile
        if (profile == null) {
            if (!calibrationMissingLogged) {
                calibrationMissingLogged = true
                Log.w(LOG_TAG, "[CAPTURE] calibration profile missing; frame analysis skipped")
            }
            frame.recycle()
            return null
        }
        calibrationMissingLogged = false

        val processStartNs = SystemClock.elapsedRealtimeNanos()
        val snapshot = recognitionEngine.processFrame(
            bitmap = frame,
            calibrationProfile = profile,
            settings = GlyphRecognitionEngine.EngineSettings(
                edgeActivationThreshold = settings.edgeActivationThreshold,
                minimumLineBrightness = settings.minimumLineBrightness,
                stableFrameCount = settings.stableFrameCount,
                minimumMatchScore = settings.minimumMatchScore,
                commandOpenMaxLuma = settings.commandOpenMaxLuma,
                glyphDisplayMinLuma = settings.glyphDisplayMinLuma,
                glyphDisplayTopBarsMinLuma = settings.glyphDisplayTopBarsMinLuma,
                goColorDeltaThreshold = settings.goColorDeltaThreshold,
                countdownVisibleThreshold = settings.countdownVisibleThreshold,
                progressVisibleThreshold = settings.progressVisibleThreshold,
                firstBoxTopPercent = settings.firstBoxTopPercent,
                firstBoxBottomPercent = settings.firstBoxBottomPercent,
                countdownTopPercent = settings.countdownTopPercent,
                countdownBottomPercent = settings.countdownBottomPercent,
                progressTopPercent = settings.progressTopPercent,
                progressBottomPercent = settings.progressBottomPercent,
            ),
            readyBoxProfile = settings.readyBoxProfile,
        )
        val processDurationMs = elapsedMs(processStartNs)
        RuntimeStateBus.updateFromSnapshot(snapshot, captureRunning = true)

        if (snapshot.phase != lastLoggedPhase) {
            Log.i(
                LOG_TAG,
                "[CAPTURE][F$frameId] phase $lastLoggedPhase -> ${snapshot.phase} glyph=${snapshot.currentGlyph ?: "-"} seq=${formatSequence(snapshot.sequence)}",
            )
            lastLoggedPhase = snapshot.phase
        }

        if (snapshot.phase == GlyphPhase.COMMAND_OPEN) {
            if (!commandOpenPresetIssued) {
                val presetGlyphs = buildCommandOpenPresetGlyphs(settings)
                commandOpenPresetIssued = true
                if (presetGlyphs.isNotEmpty()) {
                    val emittedAtElapsedMs = SystemClock.elapsedRealtime()
                    val emitted = DrawCommandBus.tryEmit(
                        DrawCommand(
                            recognitionMode = settings.recognitionMode,
                            glyphNames = presetGlyphs,
                            calibrationProfile = profile,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            edgeDurationMs = settings.drawEdgeDurationMs,
                            glyphGapMs = settings.drawGlyphGapMs,
                            doneButtonXPercent = settings.doneButtonXPercent,
                            doneButtonYPercent = settings.doneButtonYPercent,
                            sourceFrameId = frameId,
                            sourceFrameCapturedAtElapsedMs = frameCapturedAtElapsedMs,
                            sourceFrameAnalyzedAtElapsedMs = emittedAtElapsedMs,
                            emittedAtElapsedMs = emittedAtElapsedMs,
                        )
                    )
                    val captureToEmitMs = (emittedAtElapsedMs - frameCapturedAtElapsedMs).coerceAtLeast(0L)
                    Log.i(
                        LOG_TAG,
                        "[CAPTURE][F$frameId] command-open preset seq=${formatSequence(presetGlyphs)} emitted=$emitted process=${processDurationMs}ms captureToEmit=${captureToEmitMs}ms",
                    )
                    if (!emitted) {
                        Log.w(LOG_TAG, "[CAPTURE][F$frameId] command-open preset dropped: DrawCommandBus buffer full")
                    }
                }
            }
        } else if (snapshot.phase == GlyphPhase.IDLE) {
            commandOpenPresetIssued = false
        }

        if (snapshot.drawRequested && snapshot.sequence.isNotEmpty()) {
            val emittedAtElapsedMs = SystemClock.elapsedRealtime()
            val emitted = DrawCommandBus.tryEmit(
                DrawCommand(
                    recognitionMode = settings.recognitionMode,
                    glyphNames = snapshot.sequence,
                    calibrationProfile = profile,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    edgeDurationMs = settings.drawEdgeDurationMs,
                    glyphGapMs = settings.drawGlyphGapMs,
                    doneButtonXPercent = settings.doneButtonXPercent,
                    doneButtonYPercent = settings.doneButtonYPercent,
                    sourceFrameId = frameId,
                    sourceFrameCapturedAtElapsedMs = frameCapturedAtElapsedMs,
                    sourceFrameAnalyzedAtElapsedMs = emittedAtElapsedMs,
                    emittedAtElapsedMs = emittedAtElapsedMs,
                )
            )
            val captureToEmitMs = (emittedAtElapsedMs - frameCapturedAtElapsedMs).coerceAtLeast(0L)
            Log.i(
                LOG_TAG,
                "[CAPTURE][F$frameId] drawRequested seq=${formatSequence(snapshot.sequence)} emitted=$emitted process=${processDurationMs}ms captureToEmit=${captureToEmitMs}ms",
            )
            if (!emitted) {
                Log.w(LOG_TAG, "[CAPTURE][F$frameId] draw command dropped: DrawCommandBus buffer full")
            }
        } else if (processDurationMs >= 120L) {
            Log.w(
                LOG_TAG,
                "[CAPTURE][F$frameId] recognition processing took ${processDurationMs}ms without draw request",
            )
        }
        frame.recycle()
        return snapshot
    }

    private fun pauseCaptureLoop() {
        Log.i(LOG_TAG, "[CAPTURE] pausing capture loop but keeping projection")
        stopCaptureLoopOnly()
        recognitionEngine.resetSession()
        commandOpenPresetIssued = false
        RuntimeStateBus.reset()
    }

    private fun stopSelfSafely() {
        Log.i(LOG_TAG, "[CAPTURE] stopSelfSafely invoked")
        stopCaptureResources()
        RuntimeStateBus.reset()
        commandOpenPresetIssued = false
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            foregroundUsesAccessibilityType = false
        }
        stopSelf()
    }

    private fun stopCaptureLoopOnly() {
        captureJob?.cancel()
        captureJob = null
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        recognitionPaused = false
        RuntimeStateBus.setCaptureRunning(false)
    }

    private fun stopCaptureResources() {
        Log.i(LOG_TAG, "[CAPTURE] releasing capture resources")
        internalStopInProgress = true
        stopCaptureLoopOnly()

        if (projectionCallbackRegistered) {
            runCatching {
                mediaProjection?.unregisterCallback(projectionCallback)
            }
            projectionCallbackRegistered = false
        }
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun elapsedMs(startNs: Long, endNs: Long = SystemClock.elapsedRealtimeNanos()): Long {
        return ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun formatSequence(sequence: List<String>): String {
        return if (sequence.isEmpty()) "-" else sequence.joinToString(">")
    }

    private fun buildCommandOpenPresetGlyphs(settings: AppSettings): List<String> {
        val result = ArrayList<String>(2)
        settings.commandOpenPrimaryAction.glyphName?.let(result::add)
        settings.commandOpenSecondaryAction.glyphName?.let(result::add)
        return result
    }

    private fun requestProjectionPermission() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_PROJECTION_ACTION, MainActivity.PROJECTION_ACTION_START_CAPTURE)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun Intent.intentExtra(name: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    companion object {
        private const val LOG_TAG = "GlyphHacker"
        private const val CHANNEL_ID = "glyph_capture"
        private const val CAPTURE_NOTIFICATION_ID = 3201
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val ACCESSIBILITY_SCREENSHOT_TIMEOUT_MS = 2_000L
        const val ACTION_START = "moe.lyniko.glyphhacker.capture.START"
        const val ACTION_START_ACCESSIBILITY = "moe.lyniko.glyphhacker.capture.START_ACCESSIBILITY"
        const val ACTION_RESTART = "moe.lyniko.glyphhacker.capture.RESTART"
        const val ACTION_RESTART_ACCESSIBILITY = "moe.lyniko.glyphhacker.capture.RESTART_ACCESSIBILITY"
        const val ACTION_RESET_TO_IDLE = "moe.lyniko.glyphhacker.capture.RESET_TO_IDLE"
        const val ACTION_STOP = "moe.lyniko.glyphhacker.capture.STOP"

        fun start(context: Context, permission: ProjectionPermission) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, permission.resultCode)
                putExtra(EXTRA_RESULT_DATA, permission.data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startAccessibility(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START_ACCESSIBILITY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restart(context: Context, permission: ProjectionPermission) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_RESULT_CODE, permission.resultCode)
                putExtra(EXTRA_RESULT_DATA, permission.data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restartAccessibility(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_RESTART_ACCESSIBILITY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun resetToIdle(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_RESET_TO_IDLE
            }
            context.startService(intent)
        }
    }
}
