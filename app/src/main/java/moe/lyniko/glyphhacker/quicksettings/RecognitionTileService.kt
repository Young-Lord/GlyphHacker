package moe.lyniko.glyphhacker.quicksettings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.lyniko.glyphhacker.MainActivity
import moe.lyniko.glyphhacker.R
import moe.lyniko.glyphhacker.capture.CaptureForegroundService
import moe.lyniko.glyphhacker.data.AppSettings
import moe.lyniko.glyphhacker.data.RuntimeState
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.data.SettingsRepository
import moe.lyniko.glyphhacker.overlay.OverlayControlService

class RecognitionTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private var runtimeJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(RuntimeStateBus.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        observeRuntimeState()
        updateTile(RuntimeStateBus.state.value)
    }

    override fun onStopListening() {
        runtimeJob?.cancel()
        runtimeJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val active = isRecognitionActive(RuntimeStateBus.state.value)
            if (active) {
                stopRecognition()
            } else {
                startRecognition()
            }
        }
    }

    override fun onDestroy() {
        runtimeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRuntimeState() {
        runtimeJob?.cancel()
        runtimeJob = serviceScope.launch {
            RuntimeStateBus.state.collectLatest { state ->
                updateTile(state)
            }
        }
    }

    private fun startRecognition() {
        RuntimeStateBus.setRecognitionEnabled(true)
        OverlayControlService.start(this)

        val settings = settingsRepository.settingsFlow.value
        val startedDirectly = if (shouldUseAccessibilityScreenshotCapture(settings) && isAccessibilityServiceEnabled()) {
            CaptureForegroundService.startAccessibility(this)
            true
        } else {
            launchMainForQuickStart()
            false
        }

        updateTile(
            RuntimeStateBus.state.value.copy(
                captureRunning = startedDirectly || RuntimeStateBus.state.value.captureRunning,
                recognitionEnabled = true,
            )
        )
    }

    private fun stopRecognition() {
        RuntimeStateBus.setRecognitionEnabled(false)
        OverlayControlService.stop(this)
        CaptureForegroundService.stop(this)
        updateTile(RuntimeState(captureRunning = false, recognitionEnabled = false))
    }

    private fun launchMainForQuickStart() {
        val quickStartIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_PROJECTION_ACTION, MainActivity.PROJECTION_ACTION_QUICK_START)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                3101,
                quickStartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(quickStartIntent)
        }
    }

    private fun updateTile(runtime: RuntimeState) {
        val tile = qsTile ?: return
        val active = isRecognitionActive(runtime)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_glyph)
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = if (active) getString(R.string.status_running) else getString(R.string.status_stopped)
            tile.subtitle = status
            tile.contentDescription = "${getString(R.string.app_name)} $status"
        }
        tile.updateTile()
    }

    private fun shouldUseAccessibilityScreenshotCapture(settings: AppSettings): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return settings.useAccessibilityScreenshotCapture
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(this, moe.lyniko.glyphhacker.accessibility.GlyphAccessibilityService::class.java).flattenToString()
        return services.any { info ->
            val serviceName = ComponentName(info.resolveInfo.serviceInfo.packageName, info.resolveInfo.serviceInfo.name).flattenToString()
            serviceName == target
        }
    }

    private fun isRecognitionActive(runtime: RuntimeState): Boolean {
        return runtime.captureRunning && runtime.recognitionEnabled
    }
}
