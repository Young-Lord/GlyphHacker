package moe.lyniko.glyphhacker.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GlyphAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(LOG_TAG, "[DRAW] accessibility service connected")
        AccessibilityScreenshotBus.setServiceConnected(true)
        serviceScope.launch {
            AccessibilityScreenshotBus.requests.collect { request ->
                processScreenshotRequest(request)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "[DRAW] accessibility service destroyed")
        AccessibilityScreenshotBus.setServiceConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun processScreenshotRequest(request: ScreenshotCaptureRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            request.reply.complete(
                ScreenshotCaptureResult(
                    bitmap = null,
                    error = "api_not_supported",
                )
            )
            return
        }

        val dispatched = runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val capturedAtElapsedMs = SystemClock.elapsedRealtime()
                        val bitmap = screenshotResultToBitmap(screenshot)
                        if (bitmap == null) {
                            request.reply.complete(
                                ScreenshotCaptureResult(
                                    bitmap = null,
                                    capturedAtElapsedMs = capturedAtElapsedMs,
                                    error = "bitmap_convert_failed",
                                )
                            )
                            return
                        }
                        val completed = request.reply.complete(
                            ScreenshotCaptureResult(
                                bitmap = bitmap,
                                capturedAtElapsedMs = capturedAtElapsedMs,
                            )
                        )
                        if (!completed && !bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        request.reply.complete(
                            ScreenshotCaptureResult(
                                bitmap = null,
                                error = screenshotErrorLabel(errorCode),
                            )
                        )
                    }
                },
            )
        }.isSuccess

        if (!dispatched) {
            request.reply.complete(
                ScreenshotCaptureResult(
                    bitmap = null,
                    error = "take_screenshot_dispatch_failed",
                )
            )
        }
    }

    private fun screenshotResultToBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        val hardwareBuffer = screenshot.hardwareBuffer
        return try {
            runCatching {
                val colorSpace = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                val wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                val outputBitmap = wrappedBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                wrappedBitmap?.recycle()
                outputBitmap
            }.getOrNull()
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun screenshotErrorLabel(errorCode: Int): String {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval_too_short"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid_display"
            else -> "unknown_$errorCode"
        }
    }

    companion object {
        private const val LOG_TAG = "GlyphHacker"
    }
}
