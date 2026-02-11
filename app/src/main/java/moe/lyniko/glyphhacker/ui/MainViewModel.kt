package moe.lyniko.glyphhacker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val settings = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = moe.lyniko.glyphhacker.data.AppSettings(),
    )

    val runtimeState = RuntimeStateBus.state

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _calibrating = MutableStateFlow(false)
    val calibrating = _calibrating.asStateFlow()

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

    fun setFrameIntervalMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateFrameIntervalMs(value) }
    }

    fun setGoCheckIntervalMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateGoCheckIntervalMs(value) }
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

    fun setStableFrameCount(value: Int) {
        viewModelScope.launch { settingsRepository.updateStableFrameCount(value) }
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

    fun setGlyphDisplayMinLuma(value: Float) {
        viewModelScope.launch { settingsRepository.updateGlyphDisplayMinLuma(value) }
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

    fun setDoneButtonXPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateDoneButtonXPercent(value) }
    }

    fun setDoneButtonYPercent(value: Float) {
        viewModelScope.launch { settingsRepository.updateDoneButtonYPercent(value) }
    }

    fun setOverlayXRatio(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayXRatio(value) }
    }

    fun setOverlayYRatio(value: Float) {
        viewModelScope.launch { settingsRepository.updateOverlayYRatio(value) }
    }

    fun setBlankReferenceUri(uri: Uri?) {
        viewModelScope.launch {
            settingsRepository.updateBlankReferenceUri(uri?.toString())
        }
    }

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
                _message.value = "空白截图导入并标定成功"
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

    fun runCalibrationFromBlank() {
        val uriString = settings.value.blankReferenceUri
        if (uriString.isNullOrBlank()) {
            _message.value = "请先选择空白截图"
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
