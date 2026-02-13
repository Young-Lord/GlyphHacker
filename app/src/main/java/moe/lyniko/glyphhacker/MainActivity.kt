package moe.lyniko.glyphhacker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.lyniko.glyphhacker.capture.CaptureForegroundService
import moe.lyniko.glyphhacker.capture.ProjectionPermission
import moe.lyniko.glyphhacker.data.AppSettings
import moe.lyniko.glyphhacker.data.RecognitionMode
import moe.lyniko.glyphhacker.data.RuntimeState
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.debug.DebugFrameResult
import moe.lyniko.glyphhacker.debug.VideoDebugAnalyzer
import moe.lyniko.glyphhacker.glyph.CalibrationProfile
import moe.lyniko.glyphhacker.glyph.GlyphDictionary
import moe.lyniko.glyphhacker.glyph.GlyphEdge
import moe.lyniko.glyphhacker.glyph.GlyphPathPlanner
import moe.lyniko.glyphhacker.glyph.GlyphPhase
import moe.lyniko.glyphhacker.glyph.GlyphSnapshot
import moe.lyniko.glyphhacker.glyph.NodePosition
import moe.lyniko.glyphhacker.glyph.ProbeRect
import moe.lyniko.glyphhacker.overlay.OverlayControlService
import moe.lyniko.glyphhacker.ui.MainViewModel
import moe.lyniko.glyphhacker.ui.theme.GlyphHackerTheme
import moe.lyniko.glyphhacker.util.base64ToBitmap
import moe.lyniko.glyphhacker.util.decodeBitmapFromUri
import moe.lyniko.glyphhacker.util.decodeVideoFrameFromUri
import moe.lyniko.glyphhacker.util.ShizukuAccessibilityHelper
import moe.lyniko.glyphhacker.util.takePersistableReadPermission
import moe.lyniko.glyphhacker.util.uriExists
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<MainViewModel>()
    private val permissionState = MutableStateFlow(PermissionSnapshot())
    private val externalProjectionAction = MutableStateFlow<ProjectionGrantAction?>(null)
    private var autoGrantInProgress = false
    private var autoGrantLaunchFinished = false
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        Log.d(LOG_TAG, "[AUTO_A11Y] Received Shizuku binder")
        attemptAutoGrantOnLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeProjectionActionIntent(intent)
    }

    private fun consumeProjectionActionIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_PROJECTION_ACTION) ?: return
        intent.removeExtra(EXTRA_PROJECTION_ACTION)
        externalProjectionAction.value = when (action) {
            PROJECTION_ACTION_START_CAPTURE -> ProjectionGrantAction.START_CAPTURE
            PROJECTION_ACTION_RESTART_CAPTURE -> ProjectionGrantAction.RESTART_CAPTURE
            PROJECTION_ACTION_START_OVERLAY -> ProjectionGrantAction.START_OVERLAY
            PROJECTION_ACTION_QUICK_START -> ProjectionGrantAction.QUICK_START
            else -> null
        }
    }

    companion object {
        private const val LOG_TAG = "GlyphHacker-Shizuku"
        const val EXTRA_PROJECTION_ACTION = "extra_projection_action"
        const val PROJECTION_ACTION_START_CAPTURE = "start_capture"
        const val PROJECTION_ACTION_RESTART_CAPTURE = "restart_capture"
        const val PROJECTION_ACTION_START_OVERLAY = "start_overlay"
        const val PROJECTION_ACTION_QUICK_START = "quick_start"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissionState()
        consumeProjectionActionIntent(intent)
        Log.d(LOG_TAG, "[AUTO_A11Y] onCreate, register binder listener")
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        attemptAutoGrantOnLaunch()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            GlyphHackerTheme {
                val settings by viewModel.settings.collectAsState()
                val runtime by viewModel.runtimeState.collectAsState()
                val message by viewModel.message.collectAsState()
                val calibrating by viewModel.calibrating.collectAsState()
                val permissions by permissionState.asStateFlow().collectAsState()
                val externalProjectionRequest by externalProjectionAction.collectAsState()

                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                var currentTab by rememberSaveable { mutableStateOf(RootTab.MAIN) }
                var settingsSubPage by rememberSaveable { mutableStateOf(SettingsSubPage.GENERAL) }

                var debugVideoUri by remember { mutableStateOf<Uri?>(null) }
                var debugResult by remember { mutableStateOf<DebugFrameResult?>(null) }
                var debugRunning by remember { mutableStateOf(false) }
                var debugJob by remember { mutableStateOf<Job?>(null) }
                var debugSeekJob by remember { mutableStateOf<Job?>(null) }
                var debugDurationMs by remember { mutableStateOf(0L) }
                var debugTimestampMs by remember { mutableStateOf(0L) }
                var debugPreparedVideoKey by remember { mutableStateOf<String?>(null) }
                var nodePreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
                val debugAnalyzer = remember { VideoDebugAnalyzer() }
                val latestSettings by rememberUpdatedState(settings)

                val getReadyPreview = rememberBitmapPreview(
                    uri = settings.startTemplateUri?.toUriOrNull(),
                    isVideo = false,
                    fallbackBase64 = settings.startTemplateBase64,
                )

                val openBlankImage = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    context.contentResolver.takePersistableReadPermission(uri)
                    if (!uriExists(context.contentResolver, uri)) {
                        context.toast("Command Channel截图不存在或不可访问")
                        return@rememberLauncherForActivityResult
                    }
                    viewModel.importBlankAndCalibrate(uri)
                }

                val openGetReadyImage = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    context.contentResolver.takePersistableReadPermission(uri)
                    if (!uriExists(context.contentResolver, uri)) {
                        context.toast("Get Ready 截图不存在或不可访问")
                        return@rememberLauncherForActivityResult
                    }
                    viewModel.importGetReadyTemplate(uri)
                }

                val openDebugVideo = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    context.contentResolver.takePersistableReadPermission(uri)
                    if (!uriExists(context.contentResolver, uri)) {
                        context.toast("录屏文件不存在或不可访问")
                        return@rememberLauncherForActivityResult
                    }
                    debugJob?.cancel()
                    debugSeekJob?.cancel()
                    debugRunning = false
                    debugJob = null
                    debugSeekJob = null
                    debugAnalyzer.release()
                    debugPreparedVideoKey = null
                    debugDurationMs = 0L
                    debugTimestampMs = 0L
                    val previousFrame = debugResult?.frame
                    debugResult = null
                    previousFrame?.let { frame ->
                        scope.launch(Dispatchers.Default) {
                            delay(250L)
                            if (!frame.isRecycled) {
                                runCatching { frame.recycle() }
                            }
                        }
                    }
                    debugVideoUri = uri
                }

                var pendingProjectionAction by remember { mutableStateOf<ProjectionGrantAction?>(null) }

                fun startOverlayInternal() {
                    OverlayControlService.start(context)
                }

                fun startCaptureInternal(permission: ProjectionPermission) {
                    RuntimeStateBus.setRecognitionEnabled(true)
                    CaptureForegroundService.start(context, permission)
                    refreshPermissionState()
                }

                fun startAccessibilityScreenshotCaptureInternal(): Boolean {
                    if (!isAccessibilityServiceEnabled(context)) {
                        refreshPermissionState()
                        context.toast("辅助功能未授权，无法开始识别，请先在系统设置中开启")
                        return false
                    }
                    RuntimeStateBus.setRecognitionEnabled(true)
                    CaptureForegroundService.startAccessibility(context)
                    refreshPermissionState()
                    return true
                }

                fun canUseAccessibilityScreenshotCapture(activeSettings: AppSettings = latestSettings): Boolean {
                    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeSettings.useAccessibilityScreenshotCapture
                }

                fun executeProjectionAction(action: ProjectionGrantAction, permission: ProjectionPermission) {
                    when (action) {
                        ProjectionGrantAction.START_OVERLAY -> startOverlayInternal()
                        ProjectionGrantAction.START_CAPTURE -> startCaptureInternal(permission)
                        ProjectionGrantAction.RESTART_CAPTURE -> {
                            RuntimeStateBus.setRecognitionEnabled(true)
                            CaptureForegroundService.restart(context, permission)
                            refreshPermissionState()
                        }
                        ProjectionGrantAction.QUICK_START -> {
                            startOverlayInternal()
                            startCaptureInternal(permission)
                        }
                    }
                }

                val requestProjection = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val pendingAction = pendingProjectionAction
                    pendingProjectionAction = null
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        val permission = ProjectionPermission(
                            resultCode = result.resultCode,
                            data = result.data!!,
                        )
                        refreshPermissionState()
                        if (pendingAction != null) {
                            executeProjectionAction(pendingAction, permission)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("录屏权限已授予")
                            }
                        }
                    } else if (pendingAction != null) {
                        scope.launch {
                            snackbarHostState.showSnackbar("未授予录屏权限，启动已取消")
                        }
                    }
                }

                fun requestProjectionFor(action: ProjectionGrantAction) {
                    pendingProjectionAction = action
                    requestProjection.launch(projectionManager.createScreenCaptureIntent())
                }

                LaunchedEffect(externalProjectionRequest) {
                    val action = externalProjectionRequest ?: return@LaunchedEffect
                    requestProjectionFor(action)
                    this@MainActivity.externalProjectionAction.value = null
                }

                val exportConfigLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val json = viewModel.buildExportConfigJson()
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    OutputStreamWriter(stream).use { writer ->
                                        writer.write(json)
                                        writer.flush()
                                    }
                                }
                            }.isSuccess
                        }
                        if (ok) {
                            snackbarHostState.showSnackbar("配置已导出")
                        } else {
                            snackbarHostState.showSnackbar("导出失败")
                        }
                    }
                }

                val importConfigLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    context.contentResolver.takePersistableReadPermission(uri)
                    if (!uriExists(context.contentResolver, uri)) {
                        context.toast("配置文件不存在或不可访问")
                        return@rememberLauncherForActivityResult
                    }
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    BufferedReader(InputStreamReader(stream)).readText()
                                }
                            }.getOrNull()
                        }
                        if (text.isNullOrBlank()) {
                            snackbarHostState.showSnackbar("读取配置失败")
                            return@launch
                        }
                        val result = viewModel.importConfigJson(text)
                        if (result.isSuccess) {
                            snackbarHostState.showSnackbar("配置导入成功")
                        } else {
                            snackbarHostState.showSnackbar("配置导入失败")
                        }
                    }
                }

                fun pauseDebugPlayback() {
                    debugRunning = false
                    debugJob?.cancel()
                    debugJob = null
                    debugSeekJob?.cancel()
                    debugSeekJob = null
                }

                fun setDebugResult(value: DebugFrameResult?) {
                    val previousFrame = if (debugResult !== value) debugResult?.frame else null
                    debugResult = value
                    previousFrame?.let { frame ->
                        scope.launch(Dispatchers.Default) {
                            delay(250L)
                            if (!frame.isRecycled) {
                                runCatching { frame.recycle() }
                            }
                        }
                    }
                }

                fun frameStepMs(activeSettings: AppSettings = latestSettings): Long {
                    return activeSettings.nonIdleFrameIntervalMs.coerceIn(30L, 1000L)
                }

                suspend fun loadDebugTemplateBitmap(): Bitmap? {
                    val activeSettings = latestSettings
                    return activeSettings.startTemplateBase64
                        ?.let { base64ToBitmap(it) }
                        ?: activeSettings.startTemplateUri
                            ?.toUriOrNull()
                            ?.takeIf { uriExists(context.contentResolver, it) }
                            ?.let { decodeBitmapFromUri(context, it) }
                }

                suspend fun ensureDebugPrepared(video: Uri): Boolean {
                    val key = video.toString()
                    if (debugPreparedVideoKey != key) {
                        debugAnalyzer.release()
                        debugAnalyzer.resetSession(clearCheckpoints = true)
                        debugPreparedVideoKey = key
                        debugDurationMs = 0L
                        debugTimestampMs = 0L
                        setDebugResult(null)
                    }
                    val duration = debugAnalyzer.prepare(context, video)
                    debugDurationMs = duration
                    return duration > 0L
                }

                suspend fun seekToTimestamp(
                    video: Uri,
                    calibration: CalibrationProfile,
                    targetTimestampMs: Long,
                    warmupFrames: Int = 30,
                ) {
                    if (!ensureDebugPrepared(video)) {
                        context.toast("视频时长异常，无法分析")
                        return
                    }

                    val clampedTarget = targetTimestampMs.coerceIn(0L, debugDurationMs)
                    debugTimestampMs = clampedTarget

                    val template = loadDebugTemplateBitmap()
                    try {
                        val activeSettings = latestSettings
                        val step = frameStepMs(activeSettings)
                        val warmupMs = (step * warmupFrames.toLong()).coerceIn(step, 12_000L)
                        val replayResult = withContext(Dispatchers.Default) {
                            debugAnalyzer.replayToTimestamp(
                                context = context,
                                videoUri = video,
                                targetTimestampMs = clampedTarget,
                                stepMs = step,
                                settings = activeSettings,
                                calibrationProfile = calibration,
                                startTemplate = template,
                                maxWarmupMs = warmupMs,
                            )
                        }

                        if (replayResult != null) {
                            setDebugResult(replayResult)
                            debugDurationMs = replayResult.durationMs
                            debugTimestampMs = replayResult.timestampMs
                        } else {
                            setDebugResult(null)
                            debugTimestampMs = clampedTarget
                        }
                    } finally {
                        template?.recycle()
                    }
                }

                LaunchedEffect(message) {
                    val value = message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(value)
                    viewModel.clearMessage()
                }

                LaunchedEffect(currentTab) {
                    refreshPermissionState()
                    if (currentTab == RootTab.MAIN) {
                        settingsSubPage = SettingsSubPage.GENERAL
                    } else {
                        Log.d(LOG_TAG, "[AUTO_A11Y] Enter settings tab, trigger attempt")
                        attemptAutoGrantOnLaunch(force = true)
                    }
                }

                LaunchedEffect(settings.blankReferenceUri, settings.calibrationProfile) {
                    val blankUri = settings.blankReferenceUri?.toUriOrNull()
                    val calibration = settings.calibrationProfile
                    if (blankUri == null || calibration == null || !uriExists(context.contentResolver, blankUri)) {
                        nodePreviewBitmap?.recycle()
                        nodePreviewBitmap = null
                    } else {
                        val baseBitmap = withContext(Dispatchers.IO) {
                            decodeBitmapFromUri(context, blankUri, mutable = true)
                        }
                        if (baseBitmap == null) {
                            nodePreviewBitmap?.recycle()
                            nodePreviewBitmap = null
                        } else {
                            val drawn = withContext(Dispatchers.Default) {
                                renderCurrentNodesOnBlank(
                                    baseBitmap = baseBitmap,
                                    runtime = RuntimeState(),
                                    calibrationProfile = calibration,
                                )
                            }
                            nodePreviewBitmap?.recycle()
                            nodePreviewBitmap = drawn
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        pauseDebugPlayback()
                        debugAnalyzer.release()
                        setDebugResult(null)
                        nodePreviewBitmap?.recycle()
                    }
                }

                BackHandler(enabled = currentTab == RootTab.SETTINGS && settingsSubPage == SettingsSubPage.DEBUG) {
                    settingsSubPage = SettingsSubPage.GENERAL
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF111824)) {
                            NavigationBarItem(
                                selected = currentTab == RootTab.MAIN,
                                onClick = { currentTab = RootTab.MAIN },
                                icon = { Text("主") },
                                label = { Text("主页") },
                            )
                            NavigationBarItem(
                                selected = currentTab == RootTab.SETTINGS,
                                onClick = { currentTab = RootTab.SETTINGS },
                                icon = { Text("设") },
                                label = { Text("设置") },
                            )
                        }
                    },
                ) { innerPadding ->
                    when (currentTab) {
                        RootTab.MAIN -> MainPage(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            permissions = permissions,
                            runtime = runtime,
                            overlayVisible = runtime.overlayVisible,
                            recognitionRunning = runtime.captureRunning && runtime.recognitionEnabled,
                            onRequestOverlayPermission = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                )
                                context.startActivity(intent)
                            },
                            onRequestAccessibility = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onToggleOverlay = {
                                if (runtime.overlayVisible) {
                                    OverlayControlService.stop(context)
                                } else {
                                    startOverlayInternal()
                                }
                            },
                            onToggleCapture = {
                                if (runtime.captureRunning && runtime.recognitionEnabled) {
                                    CaptureForegroundService.stop(context)
                                } else {
                                    if (canUseAccessibilityScreenshotCapture()) {
                                        startAccessibilityScreenshotCaptureInternal()
                                    } else {
                                        requestProjectionFor(ProjectionGrantAction.START_CAPTURE)
                                    }
                                }
                            },
                            onQuickStart = {
                                if (canUseAccessibilityScreenshotCapture()) {
                                    if (startAccessibilityScreenshotCaptureInternal()) {
                                        startOverlayInternal()
                                    }
                                } else {
                                    requestProjectionFor(ProjectionGrantAction.QUICK_START)
                                }
                            },
                        )

                        RootTab.SETTINGS -> {
                            if (settingsSubPage == SettingsSubPage.GENERAL) {
                                SettingsPage(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                                    settings = settings,
                                    runtime = runtime,
                                    calibrating = calibrating,
                                    nodePreviewBitmap = nodePreviewBitmap,
                                    getReadyPreview = getReadyPreview,
                                    onSetRecognitionMode = viewModel::setRecognitionMode,
                                    onSetUseAccessibilityScreenshotCapture = viewModel::setUseAccessibilityScreenshotCapture,
                                    onSetAutoGrantAccessibilityViaShizukuOnLaunch = viewModel::setAutoGrantAccessibilityViaShizukuOnLaunch,
                                    onSetIdleFrameInterval = viewModel::setIdleFrameIntervalMs,
                                    onSetNonIdleFrameInterval = viewModel::setNonIdleFrameIntervalMs,
                                    onSetEdgeThreshold = viewModel::setEdgeActivationThreshold,
                                    onSetMinLineBrightness = viewModel::setMinimumLineBrightness,
                                    onSetMinMatchScore = viewModel::setMinimumMatchScore,
                                    onSetTemplateThreshold = viewModel::setStartTemplateThreshold,
                                    onSetCommandOpenMaxLuma = viewModel::setCommandOpenMaxLuma,
                                    onSetNodePatchSize = viewModel::setNodePatchSize,
                                    onSetNodePatchMaxMae = viewModel::setNodePatchMaxMae,
                                    onSetGlyphDisplayMinLuma = viewModel::setGlyphDisplayMinLuma,
                                    onSetGlyphDisplayTopBarsMinLuma = viewModel::setGlyphDisplayTopBarsMinLuma,
                                    onSetGoColorDelta = viewModel::setGoColorDeltaThreshold,
                                    onSetWaitGoTimeoutMs = viewModel::setWaitGoTimeoutMs,
                                    onSetCountdownVisibleThreshold = viewModel::setCountdownVisibleThreshold,
                                    onSetProgressVisibleThreshold = viewModel::setProgressVisibleThreshold,
                                    onSetFirstBoxTopPercent = viewModel::setFirstBoxTopPercent,
                                    onSetFirstBoxBottomPercent = viewModel::setFirstBoxBottomPercent,
                                    onSetCountdownTopPercent = viewModel::setCountdownTopPercent,
                                    onSetCountdownBottomPercent = viewModel::setCountdownBottomPercent,
                                    onSetProgressTopPercent = viewModel::setProgressTopPercent,
                                    onSetProgressBottomPercent = viewModel::setProgressBottomPercent,
                                    onSetDrawEdgeMs = viewModel::setDrawEdgeDurationMs,
                                    onSetDrawGapMs = viewModel::setDrawGlyphGapMs,
                                    onSetCommandOpenHideSlowOption = viewModel::setCommandOpenHideSlowOption,
                                    onSetDoneButtonXPercent = viewModel::setDoneButtonXPercent,
                                    onSetDoneButtonYPercent = viewModel::setDoneButtonYPercent,
                                    onSetOverlayXRatio = viewModel::setOverlayXRatio,
                                    onSetOverlayYRatio = viewModel::setOverlayYRatio,
                                    onPickBlank = { openBlankImage.launch(arrayOf("image/*")) },
                                    onPickGetReady = { openGetReadyImage.launch(arrayOf("image/*")) },
                                    onOpenDebug = { settingsSubPage = SettingsSubPage.DEBUG },
                                    onClearImportCache = {
                                        viewModel.clearImportedCache()
                                        debugVideoUri = null
                                        setDebugResult(null)
                                        nodePreviewBitmap?.recycle()
                                        nodePreviewBitmap = null
                                    },
                                    onExportConfig = {
                                        exportConfigLauncher.launch("glyph-hacker-config-${System.currentTimeMillis()}.json")
                                    },
                                    onImportConfig = {
                                        importConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                    },
                                )
                            } else {
                                SettingsDebugPage(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                                    settings = settings,
                                    debugResult = debugResult,
                                    debugRunning = debugRunning,
                                    debugTimestampMs = debugTimestampMs,
                                    debugDurationMs = debugDurationMs,
                                    onBack = { settingsSubPage = SettingsSubPage.GENERAL },
                                    onSetDebugPlaybackSpeed = viewModel::setDebugPlaybackSpeed,
                                    onPickVideo = { openDebugVideo.launch(arrayOf("video/*")) },
                                    onSeekToTimestamp = { targetTimestampMs ->
                                        val video = debugVideoUri
                                        val calibration = settings.calibrationProfile
                                        if (video == null || calibration == null) {
                                            context.toast("请先选择录屏并完成节点标定")
                                        } else if (!uriExists(context.contentResolver, video)) {
                                            context.toast("录屏文件不存在，请重新导入")
                                        } else {
                                            pauseDebugPlayback()
                                            debugSeekJob = scope.launch {
                                                seekToTimestamp(video, calibration, targetTimestampMs, warmupFrames = 26)
                                            }
                                        }
                                    },
                                    onTogglePlayPause = {
                                        val video = debugVideoUri
                                        val calibration = settings.calibrationProfile
                                        if (video == null || calibration == null) {
                                            context.toast("请先选择录屏并完成节点标定")
                                        } else if (!uriExists(context.contentResolver, video)) {
                                            context.toast("录屏文件不存在，请重新导入")
                                        } else if (debugRunning) {
                                            pauseDebugPlayback()
                                        } else {
                                            pauseDebugPlayback()
                                            if (debugDurationMs > 0L && debugTimestampMs >= debugDurationMs) {
                                                debugAnalyzer.resetSession(clearCheckpoints = true)
                                                debugTimestampMs = 0L
                                                setDebugResult(null)
                                            }
                                            debugRunning = true
                                            debugJob = scope.launch {
                                                val template = loadDebugTemplateBitmap()
                                                try {
                                                    if (!ensureDebugPrepared(video)) {
                                                        context.toast("视频时长异常，无法分析")
                                                        return@launch
                                                    }
                                                    var anchorPosition = debugTimestampMs.coerceIn(0L, debugDurationMs)
                                                    var anchorRealtime = SystemClock.elapsedRealtime()
                                                    var anchorSpeed = latestSettings.debugPlaybackSpeed.coerceIn(0.25f, 4f)
                                                    var lastAnalyzedPosition = debugResult
                                                        ?.timestampMs
                                                        ?.coerceIn(0L, debugDurationMs)
                                                        ?: -1L

                                                    while (debugRunning) {
                                                        val activeSettings = latestSettings
                                                        val loopStart = SystemClock.elapsedRealtime()
                                                        val speed = activeSettings.debugPlaybackSpeed.coerceIn(0.25f, 4f)
                                                        if (kotlin.math.abs(speed - anchorSpeed) > 0.001f) {
                                                            val elapsedAtSwitch = loopStart - anchorRealtime
                                                            anchorPosition = (
                                                                anchorPosition + (elapsedAtSwitch.toDouble() * anchorSpeed.toDouble()).toLong()
                                                                ).coerceIn(0L, debugDurationMs)
                                                            anchorRealtime = loopStart
                                                            anchorSpeed = speed
                                                        }

                                                        val elapsedSinceAnchor = loopStart - anchorRealtime
                                                        val expectedPosition = (
                                                            anchorPosition + (elapsedSinceAnchor.toDouble() * anchorSpeed.toDouble()).toLong()
                                                            ).coerceIn(0L, debugDurationMs)
                                                        debugTimestampMs = expectedPosition

                                                        val step = frameStepMs(activeSettings)
                                                        val analyzeGap = (step / 2L).coerceAtLeast(80L)
                                                        val shouldAnalyze =
                                                            debugResult == null ||
                                                                expectedPosition >= debugDurationMs ||
                                                                (expectedPosition - lastAnalyzedPosition) >= analyzeGap

                                                        if (shouldAnalyze) {
                                                            val warmupMs = (step * 18L).coerceIn(step, 10_000L)
                                                            val frameResult = withContext(Dispatchers.Default) {
                                                                debugAnalyzer.replayToTimestamp(
                                                                    context = context,
                                                                    videoUri = video,
                                                                    targetTimestampMs = expectedPosition,
                                                                    stepMs = step,
                                                                    settings = activeSettings,
                                                                    calibrationProfile = calibration,
                                                                    startTemplate = template,
                                                                    maxWarmupMs = warmupMs,
                                                                )
                                                            }
                                                            if (frameResult != null) {
                                                                setDebugResult(frameResult)
                                                                debugDurationMs = frameResult.durationMs
                                                                lastAnalyzedPosition = frameResult.timestampMs
                                                            } else {
                                                                lastAnalyzedPosition = expectedPosition
                                                            }
                                                        }

                                                        if (expectedPosition >= debugDurationMs) {
                                                            debugRunning = false
                                                            break
                                                        }

                                                        val loopCost = SystemClock.elapsedRealtime() - loopStart
                                                        delay((16L - loopCost).coerceAtLeast(4L))
                                                    }
                                                } finally {
                                                    template?.recycle()
                                                    debugRunning = false
                                                    debugJob = null
                                                }
                                            }
                                        }
                                    },
                                    onStepForward = {
                                        val video = debugVideoUri
                                        val calibration = settings.calibrationProfile
                                        if (video == null || calibration == null) {
                                            context.toast("请先选择录屏并完成节点标定")
                                        } else if (!uriExists(context.contentResolver, video)) {
                                            context.toast("录屏文件不存在，请重新导入")
                                        } else {
                                            pauseDebugPlayback()
                                            debugSeekJob = scope.launch {
                                                val target = if (debugResult == null) {
                                                    0L
                                                } else {
                                                    (debugTimestampMs + 1000L).coerceAtMost(debugDurationMs)
                                                }
                                                seekToTimestamp(video, calibration, target, warmupFrames = 34)
                                            }
                                        }
                                    },
                                    onStepBackward = {
                                        val video = debugVideoUri
                                        val calibration = settings.calibrationProfile
                                        if (video == null || calibration == null) {
                                            context.toast("请先选择录屏并完成节点标定")
                                        } else if (!uriExists(context.contentResolver, video)) {
                                            context.toast("录屏文件不存在，请重新导入")
                                        } else {
                                            pauseDebugPlayback()
                                            debugSeekJob = scope.launch {
                                                val target = (debugTimestampMs - 1000L).coerceAtLeast(0L)
                                                seekToTimestamp(video, calibration, target, warmupFrames = 34)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        super.onDestroy()
    }

    private fun attemptAutoGrantOnLaunch(force: Boolean = false) {
        if ((!force && autoGrantLaunchFinished) || autoGrantInProgress) {
            Log.d(
                LOG_TAG,
                "[AUTO_A11Y] Skip attempt: force=$force finished=$autoGrantLaunchFinished inProgress=$autoGrantInProgress",
            )
            return
        }
        Log.d(LOG_TAG, "[AUTO_A11Y] Trigger attempt on launch force=$force")
        lifecycleScope.launch {
            val result = maybeAutoGrantAccessibilityViaShizuku(viewModel.settings.value)
            Log.d(LOG_TAG, "[AUTO_A11Y] Attempt result=$result")
            if (!force && result != null && result != ShizukuAccessibilityHelper.Result.SHIZUKU_NOT_READY) {
                autoGrantLaunchFinished = true
            }
        }
    }

    private suspend fun maybeAutoGrantAccessibilityViaShizuku(settings: AppSettings): ShizukuAccessibilityHelper.Result? {
        if (!settings.autoGrantAccessibilityViaShizukuOnLaunch) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Disabled by setting")
            return null
        }
        if (isAccessibilityServiceEnabled(this)) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Accessibility already enabled")
            return null
        }
        if (autoGrantInProgress) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Skip because attempt already in progress")
            return null
        }

        autoGrantInProgress = true
        try {
            val targetService = ComponentName(this, moe.lyniko.glyphhacker.accessibility.GlyphAccessibilityService::class.java)
            val result = ShizukuAccessibilityHelper.grantAndEnableAccessibility(this, targetService)
            when (result) {
                ShizukuAccessibilityHelper.Result.ENABLED -> {
                    refreshPermissionStateWithA11ySync()
                }

                ShizukuAccessibilityHelper.Result.SHIZUKU_NOT_READY -> Unit
                ShizukuAccessibilityHelper.Result.SHIZUKU_PERMISSION_REQUESTED -> toast("请在 Shizuku 弹窗中授权")
                ShizukuAccessibilityHelper.Result.SHIZUKU_PERMISSION_DENIED -> toast("Shizuku 授权被拒绝")
                ShizukuAccessibilityHelper.Result.GRANT_WRITE_SECURE_SETTINGS_FAILED -> {
                    toast("Shizuku 授权失败：无法授予 WRITE_SECURE_SETTINGS")
                }

                ShizukuAccessibilityHelper.Result.APPLY_SECURE_SETTINGS_FAILED -> {
                    toast("自动开启辅助功能失败，请手动授权")
                }
            }
            return result
        } finally {
            autoGrantInProgress = false
            refreshPermissionState()
        }
    }

    private suspend fun refreshPermissionStateWithA11ySync() {
        repeat(6) {
            refreshPermissionState()
            if (permissionState.value.accessibilityGranted) {
                return
            }
            delay(150L)
        }
    }

    private fun refreshPermissionState() {
        permissionState.value = PermissionSnapshot(
            overlayGranted = Settings.canDrawOverlays(this),
            accessibilityGranted = isAccessibilityServiceEnabled(this),
        )
    }
}

@Composable
private fun MainPage(
    modifier: Modifier,
    permissions: PermissionSnapshot,
    runtime: RuntimeState,
    overlayVisible: Boolean,
    recognitionRunning: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleCapture: () -> Unit,
    onQuickStart: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Glyph Hacker",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2030))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限与服务", color = Color(0xFFE5EEFF), fontWeight = FontWeight.Bold)
                PermissionRow("悬浮窗权限", permissions.overlayGranted, "去授权", onRequestOverlayPermission)
                PermissionRow("辅助功能", permissions.accessibilityGranted, "去设置", onRequestAccessibility)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onToggleOverlay) { Text(if (overlayVisible) "隐藏" else "显示") }
                    Button(onClick = onToggleCapture) { Text(if (recognitionRunning) "停止识别" else "开始识别") }
                }
                Button(onClick = onQuickStart, modifier = Modifier.fillMaxWidth()) { Text("一键启动（悬浮窗 + 识别）") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF11222C))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行状态", color = Color(0xFFCAF5FF), fontWeight = FontWeight.Bold)
                Text("状态: ${runtime.phase} | 运行: ${runtime.captureRunning}", color = Color(0xFFAEDBE8), fontSize = 12.sp)
                Text("当前: ${runtime.currentGlyph ?: "-"} | ${(runtime.confidence * 100).toInt()}%", color = Color(0xFFAEDBE8), fontSize = 12.sp)
                if (runtime.sequence.isNotEmpty()) {
                    Text("序列: ${runtime.sequence.joinToString(" > ")}", color = Color(0xFFE4FDFF), fontSize = 12.sp)
                }
                if (runtime.firstBoxRect != null) {
                    Text(
                        "首框亮度: ${runtime.firstBoxLuma.format1()} | 高度 ${runtime.firstBoxRect.height.format1()}px",
                        color = Color(0xFFE4FDFF),
                        fontSize = 12.sp,
                    )
                }
                if (runtime.countdownRect != null && runtime.progressRect != null) {
                    Text(
                        "倒计时/进度条: ${if (runtime.readyIndicatorsVisible) "存在" else "缺失"} | ${runtime.countdownLuma.format1()} / ${runtime.progressLuma.format1()}",
                        color = Color(0xFFE4FDFF),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    modifier: Modifier,
    settings: moe.lyniko.glyphhacker.data.AppSettings,
    runtime: RuntimeState,
    calibrating: Boolean,
    nodePreviewBitmap: Bitmap?,
    getReadyPreview: Bitmap?,
    onSetRecognitionMode: (RecognitionMode) -> Unit,
    onSetUseAccessibilityScreenshotCapture: (Boolean) -> Unit,
    onSetAutoGrantAccessibilityViaShizukuOnLaunch: (Boolean) -> Unit,
    onSetIdleFrameInterval: (Long) -> Unit,
    onSetNonIdleFrameInterval: (Long) -> Unit,
    onSetEdgeThreshold: (Float) -> Unit,
    onSetMinLineBrightness: (Float) -> Unit,
    onSetMinMatchScore: (Float) -> Unit,
    onSetTemplateThreshold: (Float) -> Unit,
    onSetCommandOpenMaxLuma: (Float) -> Unit,
    onSetNodePatchSize: (Int) -> Unit,
    onSetNodePatchMaxMae: (Float) -> Unit,
    onSetGlyphDisplayMinLuma: (Float) -> Unit,
    onSetGlyphDisplayTopBarsMinLuma: (Float) -> Unit,
    onSetGoColorDelta: (Float) -> Unit,
    onSetWaitGoTimeoutMs: (Long) -> Unit,
    onSetCountdownVisibleThreshold: (Float) -> Unit,
    onSetProgressVisibleThreshold: (Float) -> Unit,
    onSetFirstBoxTopPercent: (Float) -> Unit,
    onSetFirstBoxBottomPercent: (Float) -> Unit,
    onSetCountdownTopPercent: (Float) -> Unit,
    onSetCountdownBottomPercent: (Float) -> Unit,
    onSetProgressTopPercent: (Float) -> Unit,
    onSetProgressBottomPercent: (Float) -> Unit,
    onSetDrawEdgeMs: (Long) -> Unit,
    onSetDrawGapMs: (Long) -> Unit,
    onSetCommandOpenHideSlowOption: (Boolean) -> Unit,
    onSetDoneButtonXPercent: (Float) -> Unit,
    onSetDoneButtonYPercent: (Float) -> Unit,
    onSetOverlayXRatio: (Float) -> Unit,
    onSetOverlayYRatio: (Float) -> Unit,
    onPickBlank: () -> Unit,
    onPickGetReady: () -> Unit,
    onOpenDebug: () -> Unit,
    onClearImportCache: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
) {
    var previewExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("识别设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF162221))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "三个框用途：首框(紫)用于检测 GO 变色触发绘制；倒计时(黄)用于判断上方倒计时是否存在；进度条(青)用于判断上方进度条是否存在。",
                    color = Color(0xFFCAEFCF),
                    fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模式", modifier = Modifier.width(100.dp), color = Color(0xFFB6E9BE))
                    Button(onClick = { onSetRecognitionMode(RecognitionMode.EDGE_SET) }) {
                        Text(if (settings.recognitionMode == RecognitionMode.EDGE_SET) "边集合(当前)" else "边集合")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSetRecognitionMode(RecognitionMode.STROKE_SEQUENCE) }) {
                        Text(if (settings.recognitionMode == RecognitionMode.STROKE_SEQUENCE) "手工序列(预留)" else "手工序列")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    SettingSwitch(
                        label = "辅助功能截屏读屏",
                        checked = settings.useAccessibilityScreenshotCapture,
                        description = "API 30+ 可用。开启后改为辅助功能截图采样，不再使用录屏。",
                        onCheckedChange = onSetUseAccessibilityScreenshotCapture,
                    )
                }

                SettingSwitch(
                    label = "启动时自动用 Shizuku 开无障碍",
                    checked = settings.autoGrantAccessibilityViaShizukuOnLaunch,
                    description = "应用打开时自动调用 Shizuku 获取 WRITE_SECURE_SETTINGS 并尝试开启本应用无障碍。",
                    onCheckedChange = onSetAutoGrantAccessibilityViaShizukuOnLaunch,
                )

                SettingSlider(
                    label = "闲0采样间隔 ${settings.idleFrameIntervalMs}ms",
                    value = settings.idleFrameIntervalMs.toFloat(),
                    valueRange = 120f..1000f,
                    description = "仅在闲0（IDLE）阶段使用，默认500ms。",
                ) {
                    onSetIdleFrameInterval(it.toLong())
                }
                SettingSlider(
                    label = "非闲0采样间隔 ${settings.nonIdleFrameIntervalMs}ms",
                    value = settings.nonIdleFrameIntervalMs.toFloat(),
                    valueRange = 30f..1000f,
                    description = "在令/识/备/绘阶段统一使用，默认120ms。",
                ) {
                    onSetNonIdleFrameInterval(it.toLong())
                }
                SettingSlider(
                    label = "边激活分差阈值 ${settings.edgeActivationThreshold.format2()}",
                    value = settings.edgeActivationThreshold,
                    valueRange = 5f..80f,
                    description = "用于 activeEdges 判定；会影响 COMMAND_OPEN -> GLYPH_DISPLAY（同帧需有 activeEdges）和后续 glyph 识别。",
                    onChange = onSetEdgeThreshold,
                )
                SettingSlider(
                    label = "连线最小亮度 ${settings.minimumLineBrightness.format2()}",
                    value = settings.minimumLineBrightness,
                    valueRange = 20f..220f,
                    description = "用于 activeEdges 判定；会影响 COMMAND_OPEN -> GLYPH_DISPLAY（同帧需有 activeEdges）和后续 glyph 识别。",
                    onChange = onSetMinLineBrightness,
                )
                SettingSlider(
                    label = "glyph最小匹配分 ${settings.minimumMatchScore.format2()}",
                    value = settings.minimumMatchScore,
                    valueRange = 0.4f..0.95f,
                    description = "仅用于 glyph 名称识别，不直接参与阶段状态转移。",
                    onChange = onSetMinMatchScore,
                )
                SettingSlider(
                    label = "Get Ready图像匹配阈值 ${settings.startTemplateThreshold.format2()}",
                    value = settings.startTemplateThreshold,
                    valueRange = 0.5f..0.98f,
                    description = "仅用于模板调试匹配，不参与 COMMAND_OPEN/GLYPH_DISPLAY/WAIT_GO/AUTO_DRAW 状态转移。",
                    onChange = onSetTemplateThreshold,
                )
                SettingSlider(
                    label = "COMMAND_OPEN亮度上限 ${settings.commandOpenMaxLuma.format2()}",
                    value = settings.commandOpenMaxLuma,
                    valueRange = 0f..20f,
                    description = "用于 IDLE -> COMMAND_OPEN：首框/倒计时/进度条同帧都低于该值才进入 COMMAND_OPEN。",
                    onChange = onSetCommandOpenMaxLuma,
                )
                SettingSlider(
                    label = "节点Patch匹配边长 ${settings.nodePatchSize}px",
                    value = settings.nodePatchSize.toFloat(),
                    valueRange = 0f..60f,
                    description = "亮度通过后，对11个节点周围区域逐像素匹配标定帧。0=禁用。需重新标定才能生效。",
                    onChange = { onSetNodePatchSize(it.toInt()) },
                )
                SettingSlider(
                    label = "节点Patch MAE阈值 ${settings.nodePatchMaxMae.format2()}",
                    value = settings.nodePatchMaxMae,
                    valueRange = 1f..60f,
                    description = "节点区域平均绝对误差低于此值视为匹配成功（越小越严格）。",
                    onChange = onSetNodePatchMaxMae,
                )
                SettingSlider(
                    label = "进入GLYPH_DISPLAY首框亮度 ${settings.glyphDisplayMinLuma.format2()}",
                    value = settings.glyphDisplayMinLuma,
                    valueRange = 0f..40f,
                    description = "用于 COMMAND_OPEN -> GLYPH_DISPLAY：同一帧内首框亮度 > 该值，且必须有 activeEdges。",
                    onChange = onSetGlyphDisplayMinLuma,
                )
                SettingSlider(
                    label = "进入GLYPH_DISPLAY三栏最低亮度 ${settings.glyphDisplayTopBarsMinLuma.format2()}",
                    value = settings.glyphDisplayTopBarsMinLuma,
                    valueRange = 0f..20f,
                    description = "当前不参与阶段状态转移（保留调参位）。",
                    onChange = onSetGlyphDisplayTopBarsMinLuma,
                )
                SettingSlider(
                    label = "首框亮起阈值 ${settings.goColorDeltaThreshold.format2()}",
                    value = settings.goColorDeltaThreshold,
                    valueRange = 1f..60f,
                    description = "用于 WAIT_GO -> AUTO_DRAW：在 WAIT_GO 阶段首框亮度达到该值且序列非空时触发自动绘制。",
                    onChange = onSetGoColorDelta,
                )
                SettingSlider(
                    label = "WAIT_GO超时 ${settings.waitGoTimeoutMs / 1000f}s",
                    value = settings.waitGoTimeoutMs.toFloat(),
                    valueRange = 0f..15000f,
                    description = "WAIT_GO 阶段超过该时长未触发绘制则重置回 IDLE。0=不超时。",
                    onChange = { onSetWaitGoTimeoutMs(it.toLong()) },
                )
                SettingSlider(
                    label = "倒计时出现阈值 ${settings.countdownVisibleThreshold.format2()}",
                    value = settings.countdownVisibleThreshold,
                    valueRange = 1f..30f,
                    description = "用于 GLYPH_DISPLAY -> WAIT_GO：倒计时检测带亮度 >= 该值且进度条也达标，才进入 WAIT_GO。",
                    onChange = onSetCountdownVisibleThreshold,
                )
                SettingSlider(
                    label = "进度条出现阈值 ${settings.progressVisibleThreshold.format2()}",
                    value = settings.progressVisibleThreshold,
                    valueRange = 1f..60f,
                    description = "用于 GLYPH_DISPLAY -> WAIT_GO：进度条检测带亮度 >= 该值且倒计时也达标，才进入 WAIT_GO。",
                    onChange = onSetProgressVisibleThreshold,
                )
                SettingSlider(
                    label = "首框顶端 ${settings.firstBoxTopPercent.format1()}%",
                    value = settings.firstBoxTopPercent,
                    valueRange = 0f..30f,
                    description = "首框检测带顶端占屏幕高度百分比。",
                    onChange = onSetFirstBoxTopPercent,
                )
                SettingSlider(
                    label = "首框底端 ${settings.firstBoxBottomPercent.format1()}%",
                    value = settings.firstBoxBottomPercent,
                    valueRange = 0f..30f,
                    description = "首框检测带底端占屏幕高度百分比。",
                    onChange = onSetFirstBoxBottomPercent,
                )
                SettingSlider(
                    label = "倒计时顶端 ${settings.countdownTopPercent.format1()}%",
                    value = settings.countdownTopPercent,
                    valueRange = 0f..30f,
                    description = "倒计时检测带顶端占屏幕高度百分比。",
                    onChange = onSetCountdownTopPercent,
                )
                SettingSlider(
                    label = "倒计时底端 ${settings.countdownBottomPercent.format1()}%",
                    value = settings.countdownBottomPercent,
                    valueRange = 0f..30f,
                    description = "倒计时检测带底端占屏幕高度百分比。",
                    onChange = onSetCountdownBottomPercent,
                )
                SettingSlider(
                    label = "进度条顶端 ${settings.progressTopPercent.format1()}%",
                    value = settings.progressTopPercent,
                    valueRange = 0f..30f,
                    description = "进度条检测带顶端占屏幕高度百分比（最多30%）。",
                    onChange = onSetProgressTopPercent,
                )
                SettingSlider(
                    label = "进度条底端 ${settings.progressBottomPercent.format1()}%",
                    value = settings.progressBottomPercent,
                    valueRange = 0f..30f,
                    description = "进度条检测带底端占屏幕高度百分比（最多30%）。",
                    onChange = onSetProgressBottomPercent,
                )

                HorizontalDivider(color = Color(0x2244AA77))
                SettingSlider(
                    label = "每边绘制时长 ${settings.drawEdgeDurationMs}ms",
                    value = settings.drawEdgeDurationMs.toFloat(),
                    valueRange = 15f..500f,
                    description = "自动绘制时每一条线段耗时。",
                ) {
                    onSetDrawEdgeMs(it.toLong())
                }
                SettingSlider(
                    label = "绘制 Glyph 间隔 ${settings.drawGlyphGapMs}ms",
                    value = settings.drawGlyphGapMs.toFloat(),
                    valueRange = 0f..1000f,
                    description = "相邻 glyph 之间的停顿时长。",
                ) {
                    onSetDrawGapMs(it.toLong())
                }
                SettingSwitch(
                    label = "隐藏“慢”选项",
                    checked = settings.commandOpenHideSlowOption,
                    description = "开启后悬浮窗第二栏只在“快/中”之间循环。",
                    onCheckedChange = onSetCommandOpenHideSlowOption,
                )
                SettingSlider(
                    label = "DONE按钮X ${settings.doneButtonXPercent.format1()}%",
                    value = settings.doneButtonXPercent,
                    valueRange = 60f..100f,
                    description = "自动点击右下角 DONE 的横向位置（占屏幕宽度）。",
                    onChange = onSetDoneButtonXPercent,
                )
                SettingSlider(
                    label = "DONE按钮Y ${settings.doneButtonYPercent.format1()}%",
                    value = settings.doneButtonYPercent,
                    valueRange = 90f..100f,
                    description = "自动点击右下角 DONE 的纵向位置（占屏幕高度）。",
                    onChange = onSetDoneButtonYPercent,
                )

                HorizontalDivider(color = Color(0x2244AA77))
                SettingSlider(
                    label = "悬浮窗X ${(settings.overlayXRatio * 100f).format1()}%",
                    value = settings.overlayXRatio * 100f,
                    valueRange = 0f..100f,
                    description = "悬浮窗左上角X位置占屏幕宽度百分比。",
                ) {
                    onSetOverlayXRatio((it / 100f).coerceIn(0f, 1f))
                }
                SettingSlider(
                    label = "悬浮窗Y ${(settings.overlayYRatio * 100f).format1()}%",
                    value = settings.overlayYRatio * 100f,
                    valueRange = 0f..100f,
                    description = "悬浮窗左上角Y位置占屏幕高度百分比。",
                ) {
                    onSetOverlayYRatio((it / 100f).coerceIn(0f, 1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickBlank, enabled = !calibrating) {
                        Text(if (calibrating) "标定中..." else "导入Command Channel截图(自动标定)")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickGetReady) { Text("导入Get Ready截图") }
                    Button(onClick = onClearImportCache) { Text("清除导入缓存") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExportConfig) { Text("导出参数JSON") }
                    Button(onClick = onImportConfig) { Text("导入参数JSON") }
                }

                val calibration = settings.calibrationProfile
                Text(
                    text = if (calibration == null) {
                        "节点标定: 未完成"
                    } else {
                        "节点标定: 已完成 (${calibration.nodes.size} 点)"
                    },
                    color = Color(0xFFB4D8B7),
                    fontSize = 12.sp,
                )

                Button(onClick = { previewExpanded = !previewExpanded }) {
                    Text(if (previewExpanded) "收起预览" else "展开预览")
                }

                if (previewExpanded) {
                    nodePreviewBitmap?.let { bitmap ->
                        Text("Command Channel标定预览", color = Color(0xFFCAEFCF), fontSize = 12.sp)
                        PreviewImageFitWidth(
                            bitmap = bitmap,
                            highlightPointNorm = androidx.compose.ui.geometry.Offset(
                                x = (settings.doneButtonXPercent / 100f).coerceIn(0f, 1f),
                                y = (settings.doneButtonYPercent / 100f).coerceIn(0f, 1f),
                            ),
                        )
                        Text(
                            text = "当前点位来源: ${resolveNodeSourceLabel(runtime, settings.calibrationProfile)}",
                            color = Color(0xFFCAEFCF),
                            fontSize = 12.sp,
                        )
                    }

                    if (getReadyPreview != null) {
                        Text("Get Ready预览", color = Color(0xFFCAEFCF), fontSize = 12.sp)
                        val overlays = mutableListOf<Pair<ProbeRect, Color>>()
                        settings.readyBoxProfile?.firstBoxRect()?.let {
                            overlays +=
                                applyVerticalPercent(
                                    topPercent = settings.firstBoxTopPercent,
                                    bottomPercent = settings.firstBoxBottomPercent,
                                ) to Color.Magenta
                        }
                        settings.readyBoxProfile?.countdownRect()?.let {
                            overlays +=
                                applyVerticalPercent(
                                    topPercent = settings.countdownTopPercent,
                                    bottomPercent = settings.countdownBottomPercent,
                                ) to Color(0xFFFFC857)
                        }
                        settings.readyBoxProfile?.progressRect()?.let {
                            overlays +=
                                applyVerticalPercent(
                                    topPercent = settings.progressTopPercent,
                                    bottomPercent = settings.progressBottomPercent,
                                ) to Color(0xFF00E5FF)
                        }
                        PreviewImageFitWidth(
                            bitmap = getReadyPreview,
                            overlayRects = overlays,
                        )
                        settings.readyBoxProfile?.let {
                            Text(
                                "首框 ${settings.firstBoxTopPercent.format1()}%~${settings.firstBoxBottomPercent.format1()}% | 倒计时 ${settings.countdownTopPercent.format1()}%~${settings.countdownBottomPercent.format1()}% | 进度条 ${settings.progressTopPercent.format1()}%~${settings.progressBottomPercent.format1()}%",
                                color = Color(0xFFCAEFCF),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        Button(onClick = onOpenDebug) { Text("进入调试子页面") }
    }
}

@Composable
private fun SettingsDebugPage(
    modifier: Modifier,
    settings: moe.lyniko.glyphhacker.data.AppSettings,
    debugResult: DebugFrameResult?,
    debugRunning: Boolean,
    debugTimestampMs: Long,
    debugDurationMs: Long,
    onBack: () -> Unit,
    onSetDebugPlaybackSpeed: (Float) -> Unit,
    onPickVideo: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStepForward: () -> Unit,
    onStepBackward: () -> Unit,
) {
    var draggingProgress by remember { mutableStateOf(false) }
    var draggingProgressValue by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("返回设置") }
            Text("调试子页面", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1A2B))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("录屏分析", color = Color(0xFFE5DAFF), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickVideo) { Text("导入录屏") }
                    Button(onClick = onTogglePlayPause) { Text(if (debugRunning) "暂停" else "播放") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStepBackward) { Text("后退1s") }
                    Button(onClick = onStepForward) { Text("前进1s") }
                }

                SettingSlider(
                    label = "播放倍速 ${settings.debugPlaybackSpeed.format2()}x",
                    value = settings.debugPlaybackSpeed,
                    valueRange = 0.25f..4f,
                    description = "实时生效，越大播放推进越快。",
                    onChange = onSetDebugPlaybackSpeed,
                )

                if (debugDurationMs > 0L) {
                    val progress = (debugTimestampMs.toFloat() / debugDurationMs.toFloat()).coerceIn(0f, 1f)
                    val displayTimestampMs = if (draggingProgress) {
                        (draggingProgressValue * debugDurationMs.toFloat()).toLong().coerceIn(0L, debugDurationMs)
                    } else {
                        debugTimestampMs.coerceIn(0L, debugDurationMs)
                    }
                    LaunchedEffect(progress, debugDurationMs) {
                        if (!draggingProgress) {
                            draggingProgressValue = progress
                        }
                    }
                    Slider(
                        value = if (draggingProgress) draggingProgressValue else progress,
                        onValueChange = { value ->
                            draggingProgress = true
                            draggingProgressValue = value.coerceIn(0f, 1f)
                        },
                        onValueChangeFinished = {
                            draggingProgress = false
                            val target = (draggingProgressValue * debugDurationMs.toFloat()).toLong()
                            onSeekToTimestamp(target)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "进度: ${formatDuration(displayTimestampMs)} / ${formatDuration(debugDurationMs)}",
                        color = Color(0xFFE8DFFF),
                        fontSize = 12.sp,
                    )
                }

                debugResult?.let { result ->
                    Text(
                        text = "t=${result.timestampMs}ms | ${result.snapshot.phase} | ${result.snapshot.currentGlyph ?: "-"} | seq=${result.snapshot.sequence.joinToString(" > ")}",
                        color = Color(0xFFE8DFFF),
                        fontSize = 12.sp,
                    )
                    result.snapshot.firstBoxRect?.let { rect ->
                        Text(
                            text = "首框高度: ${rect.height.format1()}px | 当前亮度 ${result.snapshot.firstBoxLuma.format1()} | 触发阈值 ${settings.goColorDeltaThreshold.format1()}",
                            color = Color(0xFFE8DFFF),
                            fontSize = 12.sp,
                        )
                    }
                    if (result.snapshot.countdownRect != null && result.snapshot.progressRect != null) {
                        Text(
                            text = "倒计时/进度条亮度: ${result.snapshot.countdownLuma.format1()} / ${result.snapshot.progressLuma.format1()} | ${if (result.snapshot.readyIndicatorsVisible) "存在" else "缺失"}",
                            color = Color(0xFFE8DFFF),
                            fontSize = 12.sp,
                        )
                    }
                    DebugOverlay(result)
                }
            }
        }

    }
}

@Composable
private fun PreviewImageFitWidth(
    bitmap: Bitmap,
    overlayRects: List<Pair<ProbeRect, Color>> = emptyList(),
    highlightPointNorm: androidx.compose.ui.geometry.Offset? = null,
) {
    val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.3f, 3.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(Color.Black, RoundedCornerShape(10.dp)),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth,
        )

        if (overlayRects.isNotEmpty() || highlightPointNorm != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                overlayRects.forEach { (rect, color) ->
                    val left = rect.left * size.width
                    val top = rect.top * size.height
                    val width = rect.width * size.width
                    val height = rect.height * size.height
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(width, height),
                        style = Stroke(width = 2f.dp.toPx()),
                    )
                }
                highlightPointNorm?.let { point ->
                    val center = androidx.compose.ui.geometry.Offset(
                        x = point.x.coerceIn(0f, 1f) * size.width,
                        y = point.y.coerceIn(0f, 1f) * size.height,
                    )
                    drawCircle(
                        color = Color.Red,
                        radius = 5f.dp.toPx(),
                        center = center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    enabled: Boolean,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$title: ${if (enabled) "已授权" else "未授权"}",
            color = if (enabled) Color(0xFFAEEFC1) else Color(0xFFFFB7B7),
            fontSize = 13.sp,
        )
        Button(onClick = onClick) { Text(actionText) }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFFD5F4DA), fontSize = 12.sp)
            if (!description.isNullOrBlank()) {
                Text(description, color = Color(0xFF9EB5C0), fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, color = Color(0xFFD5F4DA), fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = valueRange, steps = steps)
        if (!description.isNullOrBlank()) {
            Text(description, color = Color(0xFF9EB5C0), fontSize = 11.sp)
        }
    }
}

@Composable
private fun DebugOverlay(result: DebugFrameResult) {
    val frame = result.frame
    val snapshot = result.snapshot
    val nodeMap = snapshot.debugNodes.associateBy { it.index }
    val autoDrawTrajectory = resolveAutoDrawTrajectory(snapshot)
    val ratio = (frame.width.toFloat() / frame.height.toFloat()).coerceIn(0.3f, 3.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(Color.Black, RoundedCornerShape(10.dp))
            .padding(4.dp),
    ) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth,
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / frame.width
            val sy = size.height / frame.height
            val activeEdgeColor = if (snapshot.phase == GlyphPhase.AUTO_DRAW) {
                Color(0x9900E5FF)
            } else {
                Color.Cyan
            }
            snapshot.activeEdges.forEach { edge: GlyphEdge ->
                val start = nodeMap[edge.a] ?: return@forEach
                val end = nodeMap[edge.b] ?: return@forEach
                drawLine(
                    color = activeEdgeColor,
                    start = androidx.compose.ui.geometry.Offset(start.x * sx, start.y * sy),
                    end = androidx.compose.ui.geometry.Offset(end.x * sx, end.y * sy),
                    strokeWidth = 2.5f.dp.toPx(),
                )
            }
            if (autoDrawTrajectory.isNotEmpty()) {
                val glowWidth = 10.dp.toPx()
                val strokeWidth = 5.2f.dp.toPx()
                val markerRadius = 4.4f.dp.toPx()
                val hueShift = ((result.timestampMs % 1_800L).toFloat() / 1_800f) * 360f
                autoDrawTrajectory.forEachIndexed { segmentIndex, segment ->
                    val edgePairs = segment.zipWithNext()
                    val segmentEdgeCount = edgePairs.size.coerceAtLeast(1)
                    edgePairs.forEachIndexed { edgeIndex, (startNode, endNode) ->
                        val progress = edgeIndex / segmentEdgeCount.toFloat()
                        val baseHue = (hueShift + segmentIndex * 67f + progress * 150f) % 360f
                        val start = androidx.compose.ui.geometry.Offset(startNode.x * sx, startNode.y * sy)
                        val end = androidx.compose.ui.geometry.Offset(endNode.x * sx, endNode.y * sy)

                        drawLine(
                            color = Color.White.copy(alpha = 0.32f),
                            start = start,
                            end = end,
                            strokeWidth = glowWidth,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.hsv(baseHue, 0.92f, 1f),
                                    Color.hsv((baseHue + 42f) % 360f, 0.98f, 1f),
                                    Color.hsv((baseHue + 88f) % 360f, 0.93f, 1f),
                                ),
                                start = start,
                                end = end,
                            ),
                            start = start,
                            end = end,
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    segment.firstOrNull()?.let { startNode ->
                        drawCircle(
                            color = Color(0xFFFFFF8A),
                            radius = markerRadius,
                            center = androidx.compose.ui.geometry.Offset(startNode.x * sx, startNode.y * sy),
                        )
                    }
                }
            }
            snapshot.debugNodes.forEach { node ->
                drawCircle(
                    color = Color(0xFFFFC857),
                    radius = 4.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(node.x * sx, node.y * sy),
                    style = Stroke(width = 1.2f.dp.toPx()),
                )
            }

            snapshot.firstBoxRect?.let { rect ->
                drawRect(
                    color = Color.Magenta,
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left * sx, rect.top * sy),
                    size = androidx.compose.ui.geometry.Size(rect.width * sx, rect.height * sy),
                    style = Stroke(width = 2f.dp.toPx()),
                )
            }
            snapshot.countdownRect?.let { rect ->
                drawRect(
                    color = Color(0xFFFFC857),
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left * sx, rect.top * sy),
                    size = androidx.compose.ui.geometry.Size(rect.width * sx, rect.height * sy),
                    style = Stroke(width = 2f.dp.toPx()),
                )
            }
            snapshot.progressRect?.let { rect ->
                drawRect(
                    color = Color(0xFF00E5FF),
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left * sx, rect.top * sy),
                    size = androidx.compose.ui.geometry.Size(rect.width * sx, rect.height * sy),
                    style = Stroke(width = 2f.dp.toPx()),
                )
            }
        }
    }
}

private fun resolveAutoDrawTrajectory(snapshot: GlyphSnapshot): List<List<NodePosition>> {
    if (snapshot.phase != GlyphPhase.AUTO_DRAW) {
        return emptyList()
    }
    val currentGlyph = snapshot.currentGlyph ?: snapshot.sequence.firstOrNull() ?: return emptyList()
    val definition = GlyphDictionary.findByName(currentGlyph) ?: return emptyList()
    if (snapshot.debugNodes.isEmpty()) {
        return emptyList()
    }
    val nodeMap = snapshot.debugNodes.associateBy { it.index }
    return GlyphPathPlanner.buildStrokeSegments(definition)
        .mapNotNull { segment ->
            val points = segment.mapNotNull(nodeMap::get)
            points.takeIf { it.size >= 2 }
        }
}

@Composable
private fun rememberBitmapPreview(
    uri: Uri?,
    isVideo: Boolean,
    fallbackBase64: String? = null,
): Bitmap? {
    val context = LocalContext.current
    val state = produceState<Bitmap?>(initialValue = null, uri, isVideo, fallbackBase64) {
        value = withContext(Dispatchers.IO) {
            if (uri != null && uriExists(context.contentResolver, uri)) {
                if (isVideo) {
                    decodeVideoFrameFromUri(context, uri)
                } else {
                    decodeBitmapFromUri(context, uri)
                }
            } else if (!fallbackBase64.isNullOrBlank()) {
                base64ToBitmap(fallbackBase64)
            } else {
                null
            }
        }
    }
    return state.value
}

private fun renderCurrentNodesOnBlank(
    baseBitmap: Bitmap,
    runtime: RuntimeState,
    calibrationProfile: CalibrationProfile?,
): Bitmap {
    val output = if (baseBitmap.isMutable) {
        baseBitmap
    } else {
        baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    val nodes = resolveNodesForBlank(runtime, calibrationProfile, output.width, output.height)
    val nodeMap = nodes.associateBy { it.index }

    val canvas = AndroidCanvas(output)
    val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = output.width.coerceAtMost(output.height) * 0.004f
    }
    val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = output.width.coerceAtMost(output.height) * 0.003f
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = output.width.coerceAtMost(output.height) * 0.035f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        style = Paint.Style.FILL
    }

    runtime.activeEdges.forEach { edge ->
        val start = nodeMap[edge.a] ?: return@forEach
        val end = nodeMap[edge.b] ?: return@forEach
        canvas.drawLine(start.x, start.y, end.x, end.y, edgePaint)
    }

    val radius = output.width.coerceAtMost(output.height) * 0.015f
    nodes.forEach { node ->
        canvas.drawCircle(node.x, node.y, radius, nodePaint)
        canvas.drawText(node.index.toString(), node.x + radius * 0.6f, node.y - radius * 0.6f, textPaint)
    }

    return output
}

private fun resolveNodesForBlank(
    runtime: RuntimeState,
    calibrationProfile: CalibrationProfile?,
    blankWidth: Int,
    blankHeight: Int,
): List<NodePosition> {
    if (runtime.debugNodes.isNotEmpty() && runtime.debugFrameWidth > 0 && runtime.debugFrameHeight > 0) {
        return runtime.debugNodes.map { node ->
            NodePosition(
                index = node.index,
                x = node.x / runtime.debugFrameWidth * blankWidth,
                y = node.y / runtime.debugFrameHeight * blankHeight,
            )
        }
    }

    if (calibrationProfile != null && calibrationProfile.sourceWidth > 0 && calibrationProfile.sourceHeight > 0) {
        return calibrationProfile.nodes.map { node ->
            NodePosition(
                index = node.index,
                x = node.x / calibrationProfile.sourceWidth * blankWidth,
                y = node.y / calibrationProfile.sourceHeight * blankHeight,
            )
        }
    }

    return emptyList()
}

private fun resolveNodeSourceLabel(runtime: RuntimeState, calibrationProfile: CalibrationProfile?): String {
    return when {
        runtime.debugNodes.isNotEmpty() && runtime.debugFrameWidth > 0 && runtime.debugFrameHeight > 0 -> {
            "实时识别 (${runtime.debugNodes.size} 点)"
        }

        calibrationProfile != null -> {
            "标定结果 (${calibrationProfile.nodes.size} 点)"
        }

        else -> {
            "无可用点位"
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val target = ComponentName(context, moe.lyniko.glyphhacker.accessibility.GlyphAccessibilityService::class.java).flattenToString()
    return services.any { info ->
        val serviceName = ComponentName(info.resolveInfo.serviceInfo.packageName, info.resolveInfo.serviceInfo.name).flattenToString()
        serviceName == target
    }
}

private fun Context.toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

private fun String?.toUriOrNull(): Uri? {
    if (this.isNullOrBlank()) return null
    return runCatching { Uri.parse(this) }.getOrNull()
}

private fun applyVerticalPercent(
    topPercent: Float,
    bottomPercent: Float,
): ProbeRect {
    val topNorm = (topPercent / 100f).coerceIn(0f, 1f)
    val bottomNorm = (bottomPercent / 100f).coerceIn(0f, 1f)
    val top = minOf(topNorm, bottomNorm)
    val bottom = maxOf(topNorm, bottomNorm)
    return ProbeRect(
        left = 0f,
        top = top,
        right = 1f,
        bottom = bottom,
    )
}

private fun Float.format2(): String = String.format("%.2f", this)
private fun Float.format1(): String = String.format("%.1f", this)

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

private enum class RootTab {
    MAIN,
    SETTINGS,
}

private enum class SettingsSubPage {
    GENERAL,
    DEBUG,
}

private enum class ProjectionGrantAction {
    START_OVERLAY,
    START_CAPTURE,
    RESTART_CAPTURE,
    QUICK_START,
}

private data class PermissionSnapshot(
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
)
