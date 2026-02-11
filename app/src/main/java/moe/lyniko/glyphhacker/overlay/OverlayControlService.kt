package moe.lyniko.glyphhacker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.lyniko.glyphhacker.MainActivity
import moe.lyniko.glyphhacker.R
import moe.lyniko.glyphhacker.capture.CaptureForegroundService
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.data.SettingsRepository
import moe.lyniko.glyphhacker.glyph.GlyphPhase

class OverlayControlService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var statusView: TextView? = null
    private var toggleView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var captureRunning: Boolean = false
    private var recognitionEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        RuntimeStateBus.setOverlayVisible(true)
        createNotificationChannel()
        startForeground(
            OVERLAY_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.overlay_notification_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        2001,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setOngoing(true)
                .build()
        )
        buildOverlay()
        observeOverlayPosition()
        observeRuntimeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RuntimeStateBus.setOverlayVisible(false)
        rootView?.let { view ->
            windowManager?.removeView(view)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val padding = resources.displayMetrics.density.times(6f).toInt()
        val toggleMinWidth = resources.displayMetrics.density.times(34f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xB30F1420.toInt())
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_VERTICAL
        }

        val status = TextView(this).apply {
            text = "闲0"
            setTextColor(0xFFE8F0FF.toInt())
            textSize = 14f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val toggleButton = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            minWidth = toggleMinWidth
            gravity = Gravity.CENTER
            setPadding(padding / 2, 0, padding, 0)
            setOnClickListener {
                if (!captureRunning) {
                    if (shouldUseAccessibilityScreenshotCapture()) {
                        RuntimeStateBus.setRecognitionEnabled(true)
                        CaptureForegroundService.startAccessibility(this@OverlayControlService)
                    } else {
                        requestProjectionPermission(MainActivity.PROJECTION_ACTION_START_CAPTURE)
                    }
                } else {
                    RuntimeStateBus.setRecognitionEnabled(!recognitionEnabled)
                }
            }
            setOnLongClickListener {
                restartCaptureService()
                true
            }
        }
        applyToggleState(toggleButton, running = false)

        val closeButton = TextView(this).apply {
            text = " X "
            textSize = 12f
            setTextColor(0xFFFFB4B4.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(padding, 0, 0, 0)
            setOnClickListener {
                stopSelf()
            }
            setOnLongClickListener {
                CaptureForegroundService.resetToIdle(this@OverlayControlService)
                true
            }
        }

        root.addView(toggleButton)
        root.addView(status)
        root.addView(closeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.07f).toInt()
        }

        wm.addView(root, params)
        rootView = root
        statusView = status
        toggleView = toggleButton
        overlayParams = params
    }

    private fun observeRuntimeState() {
        serviceScope.launch {
            RuntimeStateBus.state.collectLatest { state ->
                val text = if (!state.captureRunning) {
                    "停0"
                } else if (!state.recognitionEnabled) {
                    "停1"
                } else {
                    val phaseCode = phaseCode(state.phase)
                    val countCode = if (state.phase == GlyphPhase.AUTO_DRAW) {
                        state.drawRemainingCount.coerceIn(0, 9)
                    } else {
                        state.sequence.size.coerceIn(0, 9)
                    }
                    "$phaseCode$countCode"
                }
                statusView?.text = text
                captureRunning = state.captureRunning
                recognitionEnabled = state.recognitionEnabled
                val running = state.captureRunning && state.recognitionEnabled
                toggleView?.let { applyToggleState(it, running) }
            }
        }
    }

    private fun applyToggleState(view: TextView, running: Boolean) {
        view.text = if (running) "✓" else "✗"
        view.setTextColor(if (running) 0xFF9FFFC4.toInt() else 0xFFFFD08A.toInt())
    }

    private fun requestProjectionPermission(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_PROJECTION_ACTION, action)
        }
        startActivity(intent)
    }

    private fun restartCaptureService() {
        if (shouldUseAccessibilityScreenshotCapture()) {
            RuntimeStateBus.setRecognitionEnabled(true)
            CaptureForegroundService.restartAccessibility(this)
        } else {
            requestProjectionPermission(MainActivity.PROJECTION_ACTION_RESTART_CAPTURE)
        }
    }

    private fun shouldUseAccessibilityScreenshotCapture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return settingsRepository.settingsFlow.value.useAccessibilityScreenshotCapture
    }

    private fun observeOverlayPosition() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                val params = overlayParams ?: return@collectLatest
                val wm = windowManager ?: return@collectLatest
                val root = rootView ?: return@collectLatest

                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                val targetWidth = if (root.width > 0) root.width else (width * 0.25f).toInt()
                val targetHeight = if (root.height > 0) root.height else (height * 0.07f).toInt()

                params.x = (settings.overlayXRatio * width).toInt().coerceIn(0, (width - targetWidth).coerceAtLeast(0))
                params.y = (settings.overlayYRatio * height).toInt().coerceIn(0, (height - targetHeight).coerceAtLeast(0))
                wm.updateViewLayout(root, params)
            }
        }
    }

    private fun phaseCode(phase: GlyphPhase): String {
        return when (phase) {
            GlyphPhase.IDLE -> "闲"
            GlyphPhase.COMMAND_OPEN -> "令"
            GlyphPhase.GLYPH_DISPLAY -> "识"
            GlyphPhase.WAIT_GO -> "备"
            GlyphPhase.AUTO_DRAW -> "绘"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "glyph_overlay"
        private const val OVERLAY_NOTIFICATION_ID = 3202

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayControlService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayControlService::class.java))
        }
    }
}
