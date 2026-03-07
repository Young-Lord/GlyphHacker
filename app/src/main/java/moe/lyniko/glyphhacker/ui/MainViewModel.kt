package moe.lyniko.glyphhacker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.lyniko.glyphhacker.data.CommandOpenPrimaryAction
import moe.lyniko.glyphhacker.data.CommandOpenSecondaryAction
import moe.lyniko.glyphhacker.data.RecognitionMode
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.data.SettingsRepository
import moe.lyniko.glyphhacker.glyph.GlyphCalibration
import moe.lyniko.glyphhacker.glyph.ReadyBoxDetector
import moe.lyniko.glyphhacker.util.bitmapToBase64
import moe.lyniko.glyphhacker.util.decodeBitmapFromUri
import moe.lyniko.glyphhacker.util.resizeBitmapToMax

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application
    private val settingsRepository = SettingsRepository(application)

    val settings = settingsRepository.settingsFlow

    val runtimeState = RuntimeStateBus.state

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _calibrating = MutableStateFlow(false)
    val calibrating = _calibrating.asStateFlow()

    init {
        // Restore persisted inputEnabled into RuntimeStateBus
        RuntimeStateBus.setInputEnabled(settingsRepository.settingsFlow.value.inputEnabled)
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setRecognitionMode(mode: RecognitionMode) {
        viewModelScope.launch {
            settingsRepository.updateRecognitionMode(mode)
            _message.value = if (mode == RecognitionMode.EDGE_SET) {
                "当前模式：边集合匹配"
            } else {
                "手工序列模式已预留，核心识别待实现"
            }
        }
    }

    fun setUseAccessibilityScreenshotCapture(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUseAccessibilityScreenshotCapture(enabled)
        }
    }

    fun setAutoGrantAccessibilityViaShizukuOnLaunch(
        enabled: Boolean,
        onUpdated: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            settingsRepository.updateAutoGrantAccessibilityViaShizukuOnLaunch(enabled)
            onUpdated?.invoke()
        }
    }

    fun setAutoQuickStartOnColdLaunch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoQuickStartOnColdLaunch(enabled)
        }
    }

    fun setIdleFrameIntervalMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateIdleFrameIntervalMs(value) }
    }

    fun setNonIdleFrameIntervalMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateNonIdleFrameIntervalMs(value) }
    }

    fun setDebugPlaybackSpeed(value: Float) {
        viewModelScope.launch { settingsRepository.updateDebugPlaybackSpeed(value) }
    }

    fun setEdgeActivationThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateEdgeActivationThreshold(value) }
    }

    fun setMinimumLineBrightness(value: Float) {
        viewModelScope.launch { settingsRepository.updateMinimumLineBrightness(value) }
    }

    fun setMinimumMatchScore(value: Float) {
        viewModelScope.launch { settingsRepository.updateMinimumMatchScore(value) }
    }

    fun setStartTemplateThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateStartTemplateThreshold(value) }
    }

    fun setCommandOpenMaxLuma(value: Float) {
        viewModelScope.launch { settingsRepository.updateCommandOpenMaxLuma(value) }
    }

    fun setNodePatchSize(value: Int) {
        viewModelScope.launch { settingsRepository.updateNodePatchSize(value) }
    }

    fun setNodePatchMaxMae(value: Float) {
        viewModelScope.launch { settingsRepository.updateNodePatchMaxMae(value) }
    }

    fun setWaitGoTimeoutMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateWaitGoTimeoutMs(value) }
    }

    fun setGlyphDisplayMinLuma(value: Float) {
        viewModelScope.launch { settingsRepository.updateGlyphDisplayMinLuma(value) }
    }

    fun setGlyphDisplayTopBarsMinLuma(value: Float) {
        viewModelScope.launch { settingsRepository.updateGlyphDisplayTopBarsMinLuma(value) }
    }

    fun setGoColorDeltaThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateGoColorDeltaThreshold(value) }
    }

    fun setCountdownVisibleThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateCountdownVisibleThreshold(value) }
    }

    fun setProgressVisibleThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateProgressVisibleThreshold(value) }
    }

    fun setFirstBoxTopPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateFirstBoxTopPercent(value) }
    }

    fun setFirstBoxBottomPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateFirstBoxBottomPercent(value) }
    }

    fun setCountdownTopPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateCountdownTopPercent(value) }
    }

    fun setCountdownBottomPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateCountdownBottomPercent(value) }
    }

    fun setProgressTopPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateProgressTopPercent(value) }
    }

    fun setProgressBottomPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateProgressBottomPercent(value) }
    }

    fun setDrawEdgeDurationMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateDrawEdgeDurationMs(value) }
    }

    fun setDrawGlyphGapMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateDrawGlyphGapMs(value) }
    }

    fun setDrawTerminalDwellMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateDrawTerminalDwellMs(value) }
    }

    fun setCommandOpenPrimaryAction(value: CommandOpenPrimaryAction) {
        viewModelScope.launch { settingsRepository.updateCommandOpenPrimaryAction(value) }
    }

    fun setCommandOpenSecondaryAction(value: CommandOpenSecondaryAction) {
        viewModelScope.launch { settingsRepository.updateCommandOpenSecondaryAction(value) }
    }

    fun setCommandOpenHideSlowOption(hide: Boolean) {
        viewModelScope.launch { settingsRepository.updateCommandOpenHideSlowOption(hide) }
    }

    fun setDoneButtonXPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateDoneButtonXPercent(value) }
    }

    fun setDoneButtonYPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateDoneButtonYPercent(value) }
    }

    fun setAutoTapDoneAfterInput(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoTapDoneAfterInput(enabled) }
    }

    fun setOverlayXRatio(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayXRatio(value) }
    }

    fun setOverlayYRatio(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayYRatio(value) }
    }

    fun setOverlayScaleFactor(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayScaleFactor(value) }
    }

    fun setOverlayGlyphSizeDp(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayGlyphSizeDp(value) }
    }

    fun setOverlayShowGlyphSequence(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateOverlayShowGlyphSequence(show) }
    }

    fun setOverlaySequenceHideDelayAfterAutoDrawSec(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlaySequenceHideDelayAfterAutoDrawSec(value) }
    }

    fun setOverlaySequenceHideDelayAfterRecognitionOnlySec(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlaySequenceHideDelayAfterRecognitionOnlySec(value) }
    }

    fun setOverlayVerticalSpacingDp(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayVerticalSpacingDp(value) }
    }

    fun setOverlayOpacityPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayOpacityPercent(value) }
    }

    fun setOverlayHideCommandButtons(hide: Boolean) {
        viewModelScope.launch { settingsRepository.updateOverlayHideCommandButtons(hide) }
    }

    fun setInputEnabled(enabled: Boolean) {
        RuntimeStateBus.setInputEnabled(enabled)
        viewModelScope.launch { settingsRepository.updateInputEnabled(enabled) }
    }

    fun setBlankReferenceUri(uri: Uri?) {
        viewModelScope.launch {
            settingsRepository.updateBlankReferenceUri(uri?.toString())
        }
    }

    /** 导入 command channel open 截图（标定帧）并自动执行节点标定。 */
    fun importBlankAndCalibrate(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.updateBlankReferenceUri(uri.toString())
            _calibrating.value = true
            val bitmap = withContext(Dispatchers.IO) {
                decodeBitmapFromUri(appContext, uri)
            }
            val calibration = withContext(Dispatchers.Default) {
                bitmap?.let { GlyphCalibration.calibrateFromBlankFrame(it) }
            }
            _calibrating.value = false
            if (calibration == null) {
                _message.value = "标定失败：未检测到11个节点"
            } else {
                settingsRepository.updateCalibrationProfile(calibration)
                _message.value = "Command Channel截图导入并标定成功"
            }
        }
    }

    fun importGetReadyTemplate(uri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                decodeBitmapFromUri(appContext, uri)
            }

            if (loaded == null) {
                _message.value = "读取 Get Ready 截图失败"
                return@launch
            }

            val processed = withContext(Dispatchers.Default) {
                resizeBitmapToMax(loaded, 640)
            }
            val readyBoxProfile = withContext(Dispatchers.Default) {
                ReadyBoxDetector.detect(processed)
            }
            val matchingTemplate = withContext(Dispatchers.Default) {
                resizeBitmapToMax(processed, 280)
            }
            val base64 = withContext(Dispatchers.Default) {
                bitmapToBase64(matchingTemplate)
            }

            settingsRepository.updateTemplateImport(
                sourceUri = uri.toString(),
                base64 = base64,
                readyBoxProfile = readyBoxProfile,
            )
            _message.value = "Get Ready 截图导入成功"
        }
    }

    /** 使用已导入的 command channel open 截图重新执行节点标定。 */
    fun runCalibrationFromBlank() {
        val uriString = settings.value.blankReferenceUri
        if (uriString.isNullOrBlank()) {
            _message.value = "请先导入 command channel open 截图"
            return
        }

        viewModelScope.launch {
            _calibrating.value = true
            val uri = Uri.parse(uriString)
            val bitmap = withContext(Dispatchers.IO) {
                decodeBitmapFromUri(appContext, uri)
            }
            val calibration = withContext(Dispatchers.Default) {
                bitmap?.let { GlyphCalibration.calibrateFromBlankFrame(it) }
            }
            _calibrating.value = false
            if (calibration == null) {
                _message.value = "标定失败：未检测到11个节点"
            } else {
                settingsRepository.updateCalibrationProfile(calibration)
                _message.value = "标定成功：已保存节点坐标"
            }
        }
    }

    fun clearImportedCache() {
        viewModelScope.launch {
            settingsRepository.clearImportedFileRefs()
            _message.value = "已清除导入文件缓存（参数配置保留）"
        }
    }

    suspend fun buildExportConfigJson(): String {
        val appName = "Glyph Hacker"
        val appVersion = getAppVersion()
        return settingsRepository.buildExportJson(
            appName = appName,
            appVersion = appVersion,
        )
    }

    suspend fun importConfigJson(json: String): Result<Unit> {
        return settingsRepository.importFromJson(json)
    }

    private fun getAppVersion(): String {
        return runCatching {
            val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            pkg.versionName ?: "unknown"
        }.getOrElse {
            runCatching {
                @Suppress("DEPRECATION")
                val legacy = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                legacy.versionName ?: "unknown"
            }.getOrDefault("unknown")
        }
    }
}
