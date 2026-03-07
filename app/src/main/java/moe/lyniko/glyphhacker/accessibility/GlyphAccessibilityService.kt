package moe.lyniko.glyphhacker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.SystemClock
import android.util.Log
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.lyniko.glyphhacker.data.RecognitionMode
import moe.lyniko.glyphhacker.data.RuntimeStateBus
import moe.lyniko.glyphhacker.glyph.GlyphDictionary
import moe.lyniko.glyphhacker.glyph.NodePosition
import moe.lyniko.glyphhacker.glyph.GlyphPathPlanner
import kotlin.math.abs
import kotlin.coroutines.resume

class GlyphAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(LOG_TAG, "[DRAW] accessibility service connected")
        AccessibilityScreenshotBus.setServiceConnected(true)
        serviceScope.launch {
            DrawCommandBus.commands.collectLatest { command ->
                executeDrawCommand(command)
            }
        }
        serviceScope.launch {
            AccessibilityScreenshotBus.requests.collect { request ->
                processScreenshotRequest(request)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op
    }

    override fun onInterrupt() {
        // no-op
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
                val colorSpace = screenshot.colorSpace
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

    private suspend fun executeDrawCommand(command: DrawCommand) {
        if (!RuntimeStateBus.state.value.inputEnabled) {
            Log.i(LOG_TAG, "[DRAW][F${command.sourceFrameId}] input disabled; skipping draw command")
            DrawCommandBus.tryEmitCompletion(
                DrawCompletion(
                    sourceFrameId = command.sourceFrameId,
                    doneButtonTapped = false,
                    isCommandOpenPreset = command.isCommandOpenPreset,
                    sequenceDrawCompleted = false,
                    autoTapDoneAfterDraw = command.tapDoneButtonAfterDraw,
                )
            )
            return
        }
        val commandStartElapsedMs = SystemClock.elapsedRealtime()
        RuntimeStateBus.setDrawRemainingCount(command.glyphNames.size)
        val queueDelayMs = (commandStartElapsedMs - command.emittedAtElapsedMs).coerceAtLeast(0L)
        val captureToDispatchMs = if (command.sourceFrameCapturedAtElapsedMs > 0L) {
            (commandStartElapsedMs - command.sourceFrameCapturedAtElapsedMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        Log.i(
            LOG_TAG,
            "[DRAW][F${command.sourceFrameId}] command received queueDelay=${queueDelayMs}ms captureToDispatch=${captureToDispatchMs}ms glyphCount=${command.glyphNames.size} seq=${formatSequence(command.glyphNames)}",
        )

        if (command.recognitionMode == RecognitionMode.STROKE_SEQUENCE) {
            // Reserved path: fallback to EDGE_SET drawing for now.
            Log.w(LOG_TAG, "[DRAW][F${command.sourceFrameId}] STROKE_SEQUENCE mode requested; using EDGE_SET fallback")
        }
        val nodeMap = command.calibrationProfile
            .scaledNodes(command.frameWidth, command.frameHeight)
            .associateBy { it.index }
        val terminalDwellMs = command.terminalDwellMs.coerceAtLeast(0L)

        var dispatchedStrokeCount = 0
        var failedStrokeCount = 0
        var allGlyphsDrawn = command.glyphNames.isNotEmpty()

        command.glyphNames.forEachIndexed { glyphIndex, glyphName ->
            val definition = GlyphDictionary.findByName(glyphName)
            if (definition == null) {
                allGlyphsDrawn = false
                Log.w(
                    LOG_TAG,
                    "[DRAW][F${command.sourceFrameId}] glyph not found in dictionary: $glyphName",
                )
                RuntimeStateBus.setDrawRemainingCount((command.glyphNames.size - glyphIndex - 1).coerceAtLeast(0))
                return@forEachIndexed
            }
            val segments = GlyphPathPlanner.buildStrokeSegments(definition)
            if (segments.isEmpty()) {
                allGlyphsDrawn = false
            }
            Log.d(
                LOG_TAG,
                "[DRAW][F${command.sourceFrameId}] glyph[$glyphIndex]=$glyphName segments=${segments.size}",
            )
            segments.forEachIndexed { segmentIndex, segment ->
                if (segment.size < 2) {
                    allGlyphsDrawn = false
                    Log.d(
                        LOG_TAG,
                        "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex] skipped: size=${segment.size}",
                    )
                    return@forEachIndexed
                }
                val gesturePath = buildGesturePath(
                    glyphName = glyphName,
                    segment = segment,
                    nodeMap = nodeMap,
                    frameWidth = command.frameWidth,
                    frameHeight = command.frameHeight,
                )
                if (gesturePath == null) {
                    allGlyphsDrawn = false
                    Log.w(
                        LOG_TAG,
                        "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex] missing first node=${segment.first()}",
                    )
                    return@forEachIndexed
                }
                val edgeCount = (segment.size - 1).coerceAtLeast(1)
                val moveDurationMs = (edgeCount * command.edgeDurationMs).coerceAtLeast(command.edgeDurationMs)
                val strokeStartNs = SystemClock.elapsedRealtimeNanos()
                val accepted = if (terminalDwellMs > 0L) {
                    val moveStroke = GestureDescription.StrokeDescription(
                        gesturePath.path,
                        0L,
                        moveDurationMs,
                        true,
                    )
                    val moveGesture = GestureDescription.Builder()
                        .addStroke(moveStroke)
                        .build()
                    val moveAccepted = dispatchGestureAwait(moveGesture)
                    if (!moveAccepted) {
                        false
                    } else {
                        val holdPath = Path().apply {
                            moveTo(gesturePath.endX, gesturePath.endY)
                        }
                        val holdStroke = moveStroke.continueStroke(
                            holdPath,
                            0L,
                            terminalDwellMs,
                            false,
                        )
                        val holdGesture = GestureDescription.Builder()
                            .addStroke(holdStroke)
                            .build()
                        dispatchGestureAwait(holdGesture)
                    }
                } else {
                    val stroke = GestureDescription.StrokeDescription(gesturePath.path, 0L, moveDurationMs)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()
                    dispatchGestureAwait(gesture)
                }
                val strokeElapsedMs = elapsedMs(strokeStartNs)
                if (accepted) {
                    dispatchedStrokeCount += 1
                } else {
                    failedStrokeCount += 1
                    allGlyphsDrawn = false
                }
                Log.d(
                    LOG_TAG,
                    "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex/${segments.lastIndex}] nodes=${segment.size} planned=${moveDurationMs + terminalDwellMs}ms move=${moveDurationMs}ms dwell=${terminalDwellMs}ms actual=${strokeElapsedMs}ms result=$accepted",
                )
                if (command.glyphGapMs > 0) {
                    delay(command.glyphGapMs)
                }
            }
            RuntimeStateBus.setDrawRemainingCount((command.glyphNames.size - glyphIndex - 1).coerceAtLeast(0))
        }

        val sequenceDrawCompleted = allGlyphsDrawn && failedStrokeCount == 0 && dispatchedStrokeCount > 0
        val shouldTapDoneButton = command.tapDoneButtonAfterDraw && sequenceDrawCompleted
        if (command.tapDoneButtonAfterDraw && !shouldTapDoneButton) {
            Log.w(
                LOG_TAG,
                "[DRAW][F${command.sourceFrameId}] done-button tap skipped: allGlyphsDrawn=$allGlyphsDrawn strokes=$dispatchedStrokeCount failed=$failedStrokeCount",
            )
        }
        val doneButtonTapped = if (shouldTapDoneButton) {
            tapDoneButton(command)
        } else {
            false
        }
        DrawCommandBus.tryEmitCompletion(
            DrawCompletion(
                sourceFrameId = command.sourceFrameId,
                doneButtonTapped = doneButtonTapped,
                isCommandOpenPreset = command.isCommandOpenPreset,
                sequenceDrawCompleted = sequenceDrawCompleted,
                autoTapDoneAfterDraw = command.tapDoneButtonAfterDraw,
            )
        )
        RuntimeStateBus.setDrawRemainingCount(0)
        val totalElapsedMs = (SystemClock.elapsedRealtime() - commandStartElapsedMs).coerceAtLeast(0L)
        Log.i(
            LOG_TAG,
            "[DRAW][F${command.sourceFrameId}] command finished total=${totalElapsedMs}ms strokes=$dispatchedStrokeCount failed=$failedStrokeCount doneTapped=$doneButtonTapped",
        )
    }

    private suspend fun tapDoneButton(command: DrawCommand): Boolean {
        if (command.frameWidth <= 1 || command.frameHeight <= 1) {
            Log.w(LOG_TAG, "[DRAW][F${command.sourceFrameId}] done button tap skipped: invalid frame size")
            return false
        }
        val x = (command.frameWidth * (command.doneButtonXPercent / 100f))
            .coerceIn(1f, (command.frameWidth - 1).toFloat())
        val y = (command.frameHeight * (command.doneButtonYPercent / 100f))
            .coerceIn(1f, (command.frameHeight - 1).toFloat())
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        val startNs = SystemClock.elapsedRealtimeNanos()
        val accepted = dispatchGestureAwait(gesture)
        val elapsedMs = elapsedMs(startNs)
        Log.d(
            LOG_TAG,
            "[DRAW][F${command.sourceFrameId}] done-button tap x=${x.toInt()} y=${y.toInt()} result=$accepted elapsed=${elapsedMs}ms",
        )
        return accepted
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        Log.w(LOG_TAG, "[DRAW] gesture callback cancelled")
                        continuation.resume(false)
                    }
                }
            }
            val accepted = dispatchGesture(gesture, callback, null)
            if (!accepted && continuation.isActive) {
                Log.w(LOG_TAG, "[DRAW] dispatchGesture rejected immediately")
                continuation.resume(false)
            }
        }
    }

    private fun buildGesturePath(
        glyphName: String,
        segment: List<Int>,
        nodeMap: Map<Int, NodePosition>,
        frameWidth: Int,
        frameHeight: Int,
    ): GesturePath? {
        val first = nodeMap[segment.first()] ?: return null
        val path = Path().apply {
            moveTo(first.x, first.y)
        }
        var endNode = first
        for (index in 1 until segment.size) {
            val fromIndex = segment[index - 1]
            val toIndex = segment[index]
            val fromNode = nodeMap[fromIndex] ?: continue
            val toNode = nodeMap[toIndex] ?: continue
            if (isCrowdedHorizontalRowLink(fromIndex, toIndex)) {
                appendCrowdedHorizontalRowDetour(path, fromNode, toNode, goUp = isRow3Link(fromIndex, toIndex), frameWidth, frameHeight)
            } else if (isImperfectDirectLink(glyphName, fromIndex, toIndex)) {
                appendImperfectDirectLinkDetour(path, toNode, nodeMap, frameWidth, frameHeight)
            } else {
                path.lineTo(toNode.x, toNode.y)
            }
            endNode = toNode
        }
        return GesturePath(
            path = path,
            endX = endNode.x,
            endY = endNode.y,
        )
    }

    private data class GesturePath(
        val path: Path,
        val endX: Float,
        val endY: Float,
    )

    private fun isCrowdedHorizontalRowLink(fromIndex: Int, toIndex: Int): Boolean {
        return (fromIndex == ROW3_LEFT_NODE && toIndex == ROW3_RIGHT_NODE) ||
            (fromIndex == ROW3_RIGHT_NODE && toIndex == ROW3_LEFT_NODE) ||
            (fromIndex == ROW5_LEFT_NODE && toIndex == ROW5_RIGHT_NODE) ||
            (fromIndex == ROW5_RIGHT_NODE && toIndex == ROW5_LEFT_NODE)
    }

    /** Row 3 (nodes 9↔6) 在中心上方，绕行向上；Row 5 (nodes 8↔7) 在中心下方，绕行向下。 */
    private fun isRow3Link(fromIndex: Int, toIndex: Int): Boolean {
        return (fromIndex == ROW3_LEFT_NODE && toIndex == ROW3_RIGHT_NODE) ||
            (fromIndex == ROW3_RIGHT_NODE && toIndex == ROW3_LEFT_NODE)
    }

    private fun appendCrowdedHorizontalRowDetour(
        path: Path,
        fromNode: NodePosition,
        toNode: NodePosition,
        goUp: Boolean,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        val maxX = (frameWidth - 1).toFloat().coerceAtLeast(1f)
        val maxY = (frameHeight - 1).toFloat().coerceAtLeast(1f)
        val apexX = (frameWidth * 0.5f).coerceIn(1f, maxX)
        val baseY = (fromNode.y + toNode.y) * 0.5f
        val riseFrom = abs(apexX - fromNode.x)
        val riseTo = abs(apexX - toNode.x)
        val rise = maxOf(riseFrom, riseTo)
        val apexY = if (goUp) {
            (baseY - rise).coerceIn(1f, maxY)
        } else {
            (baseY + rise).coerceIn(1f, maxY)
        }

        path.lineTo(apexX, apexY)
        path.lineTo(toNode.x, toNode.y)
    }

    private fun isImperfectDirectLink(glyphName: String, fromIndex: Int, toIndex: Int): Boolean {
        if (!glyphName.equals(IMPERFECT_NAME, ignoreCase = true)) {
            return false
        }
        return (fromIndex == IMPERFECT_ROW5_LEFT_NODE && toIndex == IMPERFECT_ROW3_RIGHT_NODE) ||
            (fromIndex == IMPERFECT_ROW3_RIGHT_NODE && toIndex == IMPERFECT_ROW5_LEFT_NODE)
    }

    private fun appendImperfectDirectLinkDetour(
        path: Path,
        toNode: NodePosition,
        nodeMap: Map<Int, NodePosition>,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        val row3RightNode = nodeMap[IMPERFECT_ROW3_RIGHT_NODE] ?: run {
            path.lineTo(toNode.x, toNode.y)
            return
        }
        val row4RightNode = nodeMap[IMPERFECT_ROW4_RIGHT_NODE] ?: run {
            path.lineTo(toNode.x, toNode.y)
            return
        }
        val row5LeftNode = nodeMap[IMPERFECT_ROW5_LEFT_NODE] ?: run {
            path.lineTo(toNode.x, toNode.y)
            return
        }

        val midpointX = (row3RightNode.x + row4RightNode.x) * 0.5f
        val midpointY = (row3RightNode.y + row4RightNode.y) * 0.5f
        val maxX = (frameWidth - 1).toFloat().coerceAtLeast(1f)
        val maxY = (frameHeight - 1).toFloat().coerceAtLeast(1f)
        val turnX = (frameWidth * 0.5f).coerceIn(1f, maxX)
        val delta = abs(row3RightNode.x - turnX)
        val turnY = (row3RightNode.y + delta).coerceIn(1f, maxY)

        val route = if (toNode.index == IMPERFECT_ROW5_LEFT_NODE) {
            listOf(
                NodePosition(index = -1, x = midpointX, y = midpointY),
                NodePosition(index = -1, x = turnX, y = turnY),
                row5LeftNode,
            )
        } else {
            listOf(
                NodePosition(index = -1, x = turnX, y = turnY),
                NodePosition(index = -1, x = midpointX, y = midpointY),
                row3RightNode,
            )
        }

        route.forEach { waypoint ->
            path.lineTo(waypoint.x, waypoint.y)
        }
    }

    private fun elapsedMs(startNs: Long, endNs: Long = SystemClock.elapsedRealtimeNanos()): Long {
        return ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun formatSequence(values: List<String>): String {
        return if (values.isEmpty()) "-" else values.joinToString(">")
    }

    companion object {
        private const val LOG_TAG = "GlyphHacker"
        private const val IMPERFECT_NAME = "Imperfect"
        private const val ROW3_LEFT_NODE = 9
        private const val ROW3_RIGHT_NODE = 6
        private const val ROW5_LEFT_NODE = 8
        private const val ROW5_RIGHT_NODE = 7
        private const val IMPERFECT_ROW3_RIGHT_NODE = 6
        private const val IMPERFECT_ROW4_RIGHT_NODE = 7
        private const val IMPERFECT_ROW5_LEFT_NODE = 8
    }
}
