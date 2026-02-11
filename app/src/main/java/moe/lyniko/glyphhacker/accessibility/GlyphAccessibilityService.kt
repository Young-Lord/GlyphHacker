package moe.lyniko.glyphhacker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import android.util.Log
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        serviceScope.launch {
            DrawCommandBus.commands.collectLatest { command ->
                executeDrawCommand(command)
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
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun executeDrawCommand(command: DrawCommand) {
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

        var dispatchedStrokeCount = 0
        var failedStrokeCount = 0

        command.glyphNames.forEachIndexed { glyphIndex, glyphName ->
            val definition = GlyphDictionary.findByName(glyphName)
            if (definition == null) {
                Log.w(
                    LOG_TAG,
                    "[DRAW][F${command.sourceFrameId}] glyph not found in dictionary: $glyphName",
                )
                RuntimeStateBus.setDrawRemainingCount((command.glyphNames.size - glyphIndex - 1).coerceAtLeast(0))
                return@forEachIndexed
            }
            val segments = GlyphPathPlanner.buildStrokeSegments(definition)
            Log.d(
                LOG_TAG,
                "[DRAW][F${command.sourceFrameId}] glyph[$glyphIndex]=$glyphName segments=${segments.size}",
            )
            segments.forEachIndexed { segmentIndex, segment ->
                if (segment.size < 2) {
                    Log.d(
                        LOG_TAG,
                        "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex] skipped: size=${segment.size}",
                    )
                    return@forEachIndexed
                }
                val path = buildGesturePath(
                    glyphName = glyphName,
                    segment = segment,
                    nodeMap = nodeMap,
                    frameWidth = command.frameWidth,
                    frameHeight = command.frameHeight,
                )
                if (path == null) {
                    Log.w(
                        LOG_TAG,
                        "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex] missing first node=${segment.first()}",
                    )
                    return@forEachIndexed
                }
                val edgeCount = (segment.size - 1).coerceAtLeast(1)
                val duration = (edgeCount * command.edgeDurationMs).coerceAtLeast(command.edgeDurationMs)
                val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()
                val strokeStartNs = SystemClock.elapsedRealtimeNanos()
                val accepted = dispatchGestureAwait(gesture)
                val strokeElapsedMs = elapsedMs(strokeStartNs)
                if (accepted) {
                    dispatchedStrokeCount += 1
                } else {
                    failedStrokeCount += 1
                }
                Log.d(
                    LOG_TAG,
                    "[DRAW][F${command.sourceFrameId}] glyph=$glyphName segment[$segmentIndex/${segments.lastIndex}] nodes=${segment.size} planned=${duration}ms actual=${strokeElapsedMs}ms result=$accepted",
                )
                if (command.glyphGapMs > 0) {
                    delay(command.glyphGapMs)
                }
            }
            RuntimeStateBus.setDrawRemainingCount((command.glyphNames.size - glyphIndex - 1).coerceAtLeast(0))
        }

        val doneButtonTapped = tapDoneButton(command)
        DrawCommandBus.tryEmitCompletion(
            DrawCompletion(
                sourceFrameId = command.sourceFrameId,
                doneButtonTapped = doneButtonTapped,
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
    ): Path? {
        val first = nodeMap[segment.first()] ?: return null
        val path = Path().apply {
            moveTo(first.x, first.y)
        }
        for (index in 1 until segment.size) {
            val fromIndex = segment[index - 1]
            val toIndex = segment[index]
            val toNode = nodeMap[toIndex] ?: continue
            if (isImperfectDirectLink(glyphName, fromIndex, toIndex)) {
                appendImperfectDirectLinkDetour(path, toNode, nodeMap, frameWidth, frameHeight)
            } else {
                path.lineTo(toNode.x, toNode.y)
            }
        }
        return path
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
        val rayDx = midpointX - row3RightNode.x
        val rayDy = midpointY - row3RightNode.y
        val maxX = (frameWidth - 1).toFloat().coerceAtLeast(1f)
        val maxY = (frameHeight - 1).toFloat().coerceAtLeast(1f)
        val turnX = (frameWidth * 0.5f).coerceIn(1f, maxX)
        val turnY = if (abs(rayDx) < 1e-3f) {
            midpointY
        } else {
            val t = (turnX - row3RightNode.x) / rayDx
            (row3RightNode.y + (rayDy * t)).coerceIn(1f, maxY)
        }

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
        private const val IMPERFECT_ROW3_RIGHT_NODE = 6
        private const val IMPERFECT_ROW4_RIGHT_NODE = 7
        private const val IMPERFECT_ROW5_LEFT_NODE = 8
    }
}
