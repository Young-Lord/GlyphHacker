package moe.lyniko.glyphhacker.accessibility

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenshotCaptureResult(
    val bitmap: Bitmap?,
    val capturedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    val error: String? = null,
)

data class ScreenshotCaptureRequest(
    val frameId: Long,
    val reply: CompletableDeferred<ScreenshotCaptureResult>,
)

object AccessibilityScreenshotBus {
    private val _requests = MutableSharedFlow<ScreenshotCaptureRequest>(extraBufferCapacity = 1)
    private val _serviceConnected = MutableStateFlow(false)

    val requests = _requests.asSharedFlow()
    val serviceConnected = _serviceConnected.asStateFlow()

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
    }

    suspend fun requestFrame(frameId: Long, timeoutMs: Long): ScreenshotCaptureResult? {
        if (!_serviceConnected.value) {
            return null
        }
        val reply = CompletableDeferred<ScreenshotCaptureResult>()
        if (!_requests.tryEmit(ScreenshotCaptureRequest(frameId = frameId, reply = reply))) {
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) {
            reply.await()
        }
        if (result == null) {
            reply.cancel()
        }
        return result
    }
}
