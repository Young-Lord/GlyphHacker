package moe.lyniko.glyphhacker.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
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
import android.widget.Toast
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
import moe.lyniko.glyphhacker.data.CommandOpenPrimaryAction
import moe.lyniko.glyphhacker.data.CommandOpenSecondaryAction
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
    private var commandRow: LinearLayout? = null
    private var commandOpenPrimaryView: TextView? = null
    private var commandOpenSecondaryView: TextView? = null
    private var glyphSequenceView: GlyphSequenceView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var captureRunning: Boolean = false
    private var recognitionEnabled: Boolean = true
    private var commandOpenPrimaryAction: CommandOpenPrimaryAction = CommandOpenPrimaryAction.SEND_SPEED
    private var commandOpenSecondaryAction: CommandOpenSecondaryAction = CommandOpenSecondaryAction.MEDIUM
    private var commandOpenHideSlowOption: Boolean = false

    // Sequence persistence state
    private var previousPhase: GlyphPhase = GlyphPhase.IDLE
    private var persistedSequence: List<String> = emptyList()
    private var sequencePersistedAtMs: Long = 0L

    // Cached scale-dependent base values (at scale=1.0)
    private var baseDensity: Float = 1f

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

        baseDensity = resources.displayMetrics.density
        val scale = settingsRepository.settingsFlow.value.overlayScaleFactor
        val density = baseDensity * scale
        val padding = density.times(6f).toInt()
        val toggleMinWidth = density.times(34f).toInt()
        val verticalSpacingPx = (settingsRepository.settingsFlow.value.overlayVerticalSpacingDp * baseDensity * scale).toInt()

        // Root: vertical LinearLayout, children left-aligned so widths are independent
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        // --- Control panel (top rows) with its own background ---
        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB30F1420.toInt())
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val status = TextView(this).apply {
            text = "闲0"
            setTextColor(0xFFE8F0FF.toInt())
            textSize = 14f * scale
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val toggleButton = TextView(this).apply {
            textSize = 12f * scale
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            minWidth = toggleMinWidth
            gravity = Gravity.CENTER
            setPadding(padding / 2, 0, padding, 0)
            setOnClickListener {
                if (!captureRunning) {
                    if (shouldUseAccessibilityScreenshotCapture()) {
                        if (isAccessibilityServiceEnabled()) {
                            RuntimeStateBus.setRecognitionEnabled(true)
                            CaptureForegroundService.startAccessibility(this@OverlayControlService)
                        } else {
                            Toast.makeText(
                                this@OverlayControlService,
                                "辅助功能未授权，无法开始识别，请先在系统设置中开启",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
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
            textSize = 12f * scale
            setTextColor(0xFFFFB4B4.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(padding, 0, 0, 0)
            setOnClickListener {
                stopSelf()
            }
            setOnLongClickListener {
                exitApp()
                true
            }
        }

        topRow.addView(toggleButton)
        topRow.addView(status)
        topRow.addView(closeButton)

        val cmdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, 0)
        }

        val commandPrimaryButton = TextView(this).apply {
            textSize = 12f * scale
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(padding, padding / 3, padding, padding / 3)
            setOnClickListener {
                serviceScope.launch {
                    val next = commandOpenPrimaryAction.next()
                    settingsRepository.updateCommandOpenPrimaryAction(next)
                }
            }
        }

        val commandSecondaryButton = TextView(this).apply {
            textSize = 12f * scale
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(padding, padding / 3, padding, padding / 3)
            setOnClickListener {
                serviceScope.launch {
                    val next = commandOpenSecondaryAction.next(commandOpenHideSlowOption)
                    settingsRepository.updateCommandOpenSecondaryAction(next)
                }
            }
        }

        val commandItemWeight = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
        cmdRow.addView(commandPrimaryButton, commandItemWeight)
        cmdRow.addView(commandSecondaryButton, LinearLayout.LayoutParams(commandItemWeight))

        controlPanel.addView(topRow)
        controlPanel.addView(cmdRow)

        // Hide command row if setting is on
        val hideCommands = settingsRepository.settingsFlow.value.overlayHideCommandButtons
        cmdRow.visibility = if (hideCommands) View.GONE else View.VISIBLE

        // --- Glyph sequence row: separate view with its own background ---
        val glyphSeqView = GlyphSequenceView(this).apply {
            setOnClickListener {
                clearPersistedSequence()
            }
        }

        root.addView(controlPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(glyphSeqView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = verticalSpacingPx.coerceAtLeast((baseDensity * scale * 2f).toInt())
        })

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
        commandRow = cmdRow
        commandOpenPrimaryView = commandPrimaryButton
        commandOpenSecondaryView = commandSecondaryButton
        glyphSequenceView = glyphSeqView
        overlayParams = params
        applyCommandOpenActionState(commandPrimaryButton, commandOpenPrimaryAction.label)
        applyCommandOpenActionState(commandSecondaryButton, commandOpenSecondaryAction.label)
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

                val displaySequence = resolveDisplaySequence(state.phase, state.sequence)
                glyphSequenceView?.updateSequence(displaySequence)

                previousPhase = state.phase
            }
        }
    }

    /**
     * Resolve which glyph sequence to display in the overlay.
     *
     * When transitioning from AUTO_DRAW to IDLE, persist the last sequence for up to 20s.
     * Clear persisted sequence when entering COMMAND_OPEN or after 20s timeout.
     */
    private fun resolveDisplaySequence(currentPhase: GlyphPhase, currentSequence: List<String>): List<String> {
        if (currentSequence.isNotEmpty()) {
            if (currentPhase == GlyphPhase.AUTO_DRAW || currentPhase == GlyphPhase.GLYPH_DISPLAY || currentPhase == GlyphPhase.WAIT_GO) {
                persistedSequence = currentSequence
                sequencePersistedAtMs = System.currentTimeMillis()
            }
            return currentSequence
        }

        // Transition from AUTO_DRAW to IDLE: start persisting
        if (previousPhase == GlyphPhase.AUTO_DRAW && currentPhase == GlyphPhase.IDLE && persistedSequence.isNotEmpty()) {
            sequencePersistedAtMs = System.currentTimeMillis()
        }

        // Entering COMMAND_OPEN: clear persisted sequence (new hack cycle)
        if (currentPhase == GlyphPhase.COMMAND_OPEN) {
            clearPersistedSequence()
            return emptyList()
        }

        // Check if persisted sequence is still valid (within 20s)
        if (persistedSequence.isNotEmpty() && currentPhase == GlyphPhase.IDLE) {
            val elapsed = System.currentTimeMillis() - sequencePersistedAtMs
            if (elapsed < SEQUENCE_PERSIST_DURATION_MS) {
                return persistedSequence
            }
            clearPersistedSequence()
        }

        return emptyList()
    }

    private fun clearPersistedSequence() {
        persistedSequence = emptyList()
        sequencePersistedAtMs = 0L
        glyphSequenceView?.updateSequence(emptyList())
    }

    private fun applyToggleState(view: TextView, running: Boolean) {
        view.text = if (running) "✓" else "✗"
        view.setTextColor(if (running) 0xFF9FFFC4.toInt() else 0xFFFFD08A.toInt())
    }

    private fun applyCommandOpenActionState(view: TextView, label: String) {
        view.text = label
        view.setTextColor(0xFFB8E2FF.toInt())
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
            if (isAccessibilityServiceEnabled()) {
                RuntimeStateBus.setRecognitionEnabled(true)
                CaptureForegroundService.restartAccessibility(this)
            } else {
                Toast.makeText(
                    this,
                    "辅助功能未授权，无法开始识别，请先在系统设置中开启",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            requestProjectionPermission(MainActivity.PROJECTION_ACTION_RESTART_CAPTURE)
        }
    }

    private fun exitApp() {
        CaptureForegroundService.stop(this)
        stopSelf()
    }

    private fun shouldUseAccessibilityScreenshotCapture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return settingsRepository.settingsFlow.value.useAccessibilityScreenshotCapture
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(this, moe.lyniko.glyphhacker.accessibility.GlyphAccessibilityService::class.java).flattenToString()
        return services.any { info ->
            val serviceName = ComponentName(info.resolveInfo.serviceInfo.packageName, info.resolveInfo.serviceInfo.name).flattenToString()
            serviceName == target
        }
    }

    private fun observeOverlayPosition() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                val params = overlayParams ?: return@collectLatest
                val wm = windowManager ?: return@collectLatest
                val root = rootView ?: return@collectLatest

                commandOpenPrimaryAction = settings.commandOpenPrimaryAction
                commandOpenSecondaryAction = settings.commandOpenSecondaryAction
                commandOpenHideSlowOption = settings.commandOpenHideSlowOption
                commandOpenPrimaryView?.let { applyCommandOpenActionState(it, commandOpenPrimaryAction.label) }
                commandOpenSecondaryView?.let { applyCommandOpenActionState(it, commandOpenSecondaryAction.label) }

                // Apply scale by adjusting text sizes and paddings (not scaleX/scaleY)
                val scale = settings.overlayScaleFactor
                val density = baseDensity * scale
                val padding = density.times(6f).toInt()
                val toggleMinWidth = density.times(34f).toInt()

                statusView?.textSize = 14f * scale
                toggleView?.apply {
                    textSize = 12f * scale
                    minWidth = toggleMinWidth
                    setPadding(padding / 2, 0, padding, 0)
                }
                // closeButton is the 3rd child of topRow
                val topRow = (root as? LinearLayout)?.getChildAt(0)
                    ?.let { (it as? LinearLayout)?.getChildAt(0) } as? LinearLayout
                topRow?.getChildAt(2)?.let { closeBtn ->
                    (closeBtn as? TextView)?.textSize = 12f * scale
                    closeBtn.setPadding(padding, 0, 0, 0)
                }

                commandOpenPrimaryView?.apply {
                    textSize = 12f * scale
                    setPadding(padding, padding / 3, padding, padding / 3)
                }
                commandOpenSecondaryView?.apply {
                    textSize = 12f * scale
                    setPadding(padding, padding / 3, padding, padding / 3)
                }

                // Update control panel padding
                val controlPanel = (root as? LinearLayout)?.getChildAt(0) as? LinearLayout
                controlPanel?.setPadding(padding, padding, padding, padding)

                // Command row padding and visibility
                commandRow?.apply {
                    setPadding(0, padding / 2, 0, 0)
                    visibility = if (settings.overlayHideCommandButtons) View.GONE else View.VISIBLE
                }

                val glyphSizePx = (settings.overlayGlyphSizeDp * baseDensity).toInt()
                glyphSequenceView?.setGlyphSizePx(glyphSizePx)

                // Update glyph sequence view top margin (vertical spacing)
                val verticalSpacingPx = (settings.overlayVerticalSpacingDp * baseDensity * scale).toInt()
                glyphSequenceView?.let { seqView ->
                    val lp = seqView.layoutParams as? LinearLayout.LayoutParams
                    lp?.topMargin = verticalSpacingPx.coerceAtLeast((baseDensity * scale * 2f).toInt())
                    seqView.layoutParams = lp
                }

                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels

                params.x = (settings.overlayXRatio * width).toInt().coerceIn(0, width)
                params.y = (settings.overlayYRatio * height).toInt().coerceIn(0, height)
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
        private const val SEQUENCE_PERSIST_DURATION_MS = 20_000L

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
