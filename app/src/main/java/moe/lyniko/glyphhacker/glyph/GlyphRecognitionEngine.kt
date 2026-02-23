package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class GlyphRecognitionEngine {

    private companion object {
        private const val LOG_TAG = "GlyphHacker"
        private const val WAIT_GO_EMPTY_SEQUENCE_IDLE_LUMA = 18f
    }

    data class SessionState(
        val phase: GlyphPhase,
        val sequence: List<String>,
        val trackedGlyph: String?,
        val drawTriggered: Boolean,
        val quietFramesAfterDraw: Int,
        val commandOpenSeen: Boolean,
        val glyphDisplaySeen: Boolean,
        val waitGoSeen: Boolean,
        val glyphDisplayLatched: Boolean,
        val glyphGapFrames: Int,
        val firstBoxBaselineLuma: Float,
    )

    private var phase: GlyphPhase = GlyphPhase.IDLE
    private val sequence = mutableListOf<String>()
    private var trackedGlyph: String? = null
    private var drawTriggered: Boolean = false
    private var quietFramesAfterDraw: Int = 0
    private var commandOpenSeen: Boolean = false
    private var glyphDisplaySeen: Boolean = false
    private var waitGoSeen: Boolean = false
    private var glyphDisplayLatched: Boolean = false
    private var glyphGapFrames: Int = 0
    private var processedFrameCount: Long = 0L
    private var waitGoEnteredAtNs: Long = 0L
    /** COMMAND_OPEN 末期首框亮度突跃值，作为 go 检测的基准。 */
    private var firstBoxBaselineLuma: Float = 0f

    fun resetSession() {
        phase = GlyphPhase.IDLE
        sequence.clear()
        trackedGlyph = null
        drawTriggered = false
        quietFramesAfterDraw = 0
        commandOpenSeen = false
        glyphDisplaySeen = false
        waitGoSeen = false
        glyphDisplayLatched = false
        glyphGapFrames = 0
        processedFrameCount = 0L
        waitGoEnteredAtNs = 0L
        firstBoxBaselineLuma = 0f
    }

    fun snapshotSessionState(): SessionState {
        return SessionState(
            phase = phase,
            sequence = sequence.toList(),
            trackedGlyph = trackedGlyph,
            drawTriggered = drawTriggered,
            quietFramesAfterDraw = quietFramesAfterDraw,
            commandOpenSeen = commandOpenSeen,
            glyphDisplaySeen = glyphDisplaySeen,
            waitGoSeen = waitGoSeen,
            glyphDisplayLatched = glyphDisplayLatched,
            glyphGapFrames = glyphGapFrames,
            firstBoxBaselineLuma = firstBoxBaselineLuma,
        )
    }

    fun restoreSessionState(state: SessionState) {
        phase = state.phase
        sequence.clear()
        sequence.addAll(state.sequence)
        trackedGlyph = state.trackedGlyph
        drawTriggered = state.drawTriggered
        quietFramesAfterDraw = state.quietFramesAfterDraw
        commandOpenSeen = state.commandOpenSeen
        glyphDisplaySeen = state.glyphDisplaySeen
        waitGoSeen = state.waitGoSeen
        glyphDisplayLatched = state.glyphDisplayLatched
        glyphGapFrames = state.glyphGapFrames
        firstBoxBaselineLuma = state.firstBoxBaselineLuma
    }

    fun processFrame(
        bitmap: Bitmap,
        calibrationProfile: CalibrationProfile,
        settings: EngineSettings,
        readyBoxProfile: ReadyBoxProfile?,
    ): GlyphSnapshot {
        val frameId = ++processedFrameCount
        val frameStartNs = SystemClock.elapsedRealtimeNanos()
        val previousPhase = phase
        val previousCommandOpenSeen = commandOpenSeen
        val previousGlyphDisplaySeen = glyphDisplaySeen

        val prepStartNs = SystemClock.elapsedRealtimeNanos()
        val nodes = calibrationProfile.scaledNodes(bitmap.width, bitmap.height).sortedBy { it.index }
        val nodeRadius = calibrationProfile.scaledNodeRadius(bitmap.width, bitmap.height)
        val nodeByIndex = nodes.associateBy { it.index }

        val atomicEdges = GlyphGeometry.buildAtomicEdges(nodes, nodeRadius)
        val candidateEdges = if (atomicEdges.isNotEmpty()) {
            atomicEdges.filter { GlyphDictionary.allKnownEdges.contains(it) }
        } else {
            GlyphDictionary.allKnownEdges.toList()
        }.ifEmpty { GlyphDictionary.allKnownEdges.toList() }
        val prepDurationMs = elapsedMs(prepStartNs)

        val lumaStartNs = SystemClock.elapsedRealtimeNanos()
        val luma = bitmap.toLumaFrame()
        val lumaDurationMs = elapsedMs(lumaStartNs)

        val edgeStartNs = SystemClock.elapsedRealtimeNanos()
        val edgeEvidence = candidateEdges.mapNotNull { edge ->
            val start = nodeByIndex[edge.a] ?: return@mapNotNull null
            val end = nodeByIndex[edge.b] ?: return@mapNotNull null
            analyzeEdge(luma, start, end, nodeRadius, edge)
        }

        val activeEdges = edgeEvidence
            .filter { evidence ->
                evidence.score >= settings.edgeActivationThreshold &&
                    evidence.lineBrightness >= settings.minimumLineBrightness
            }
            .map { it.edge }
            .toSet()

        val bestMatch = GlyphDictionary.findBestMatch(activeEdges, settings.minimumMatchScore)
        val candidateGlyphName = bestMatch?.definition?.canonicalName
        val candidateConfidence = bestMatch?.score ?: 0f
        val edgeDurationMs = elapsedMs(edgeStartNs)

        var firstBoxRect: ProbeRect? = null
        var firstBoxLuma = 0f
        var countdownRect: ProbeRect? = null
        var countdownLuma = 0f
        var progressRect: ProbeRect? = null
        var progressLuma = 0f

        val probeStartNs = SystemClock.elapsedRealtimeNanos()
        if (readyBoxProfile != null) {
            firstBoxRect = buildConfiguredRect(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                topPercent = settings.firstBoxTopPercent,
                bottomPercent = settings.firstBoxBottomPercent,
            )
            firstBoxLuma = sampleRectAverage(luma, firstBoxRect)
            countdownRect = buildConfiguredRect(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                topPercent = settings.countdownTopPercent,
                bottomPercent = settings.countdownBottomPercent,
            )
            countdownLuma = sampleRectAverage(luma, countdownRect)
            progressRect = buildConfiguredRect(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                topPercent = settings.progressTopPercent,
                bottomPercent = settings.progressBottomPercent,
            )
            progressLuma = sampleRectAverage(luma, progressRect)
        }
        val probeDurationMs = elapsedMs(probeStartNs)

        val readyIndicatorsVisible = countdownRect != null &&
            progressRect != null &&
            countdownLuma >= settings.countdownVisibleThreshold &&
            progressLuma >= settings.progressVisibleThreshold
        val lumaCommandOpen = firstBoxLuma < settings.commandOpenMaxLuma &&
            countdownLuma < settings.commandOpenMaxLuma &&
            progressLuma < settings.commandOpenMaxLuma
        val glyphDisplayTransitionFrame = firstBoxLuma > settings.glyphDisplayMinLuma &&
            activeEdges.isNotEmpty()

        // 亮度预判通过后，可选地进行节点 patch 逐像素相似度匹配
        val patchMatchEnabled = settings.nodePatchSize > 0 &&
            calibrationProfile.nodePatches.isNotEmpty()
        var patchMae = -1f
        val commandOpenDetected = if (lumaCommandOpen && patchMatchEnabled) {
            patchMae = computeNodePatchMae(
                luma, calibrationProfile, nodes, settings.nodePatchSize, settings.nodePatchMaxMae,
            )
            val matched = patchMae <= settings.nodePatchMaxMae
            if (frameId % 5L == 0L || (!commandOpenSeen && matched)) {
                Log.d(
                    LOG_TAG,
                    "[ENGINE][F$frameId][PATCH] mae=%.2f threshold=%.2f matched=$matched".format(
                        patchMae, settings.nodePatchMaxMae,
                    ),
                )
            }
            matched
        } else {
            lumaCommandOpen
        }

        if (commandOpenDetected) {
            commandOpenSeen = true
        }
        if (commandOpenSeen && glyphDisplayTransitionFrame) {
            glyphDisplaySeen = true
        }
        // COMMAND_OPEN 末期：首框亮度从 <1 突跃到 >5 时，锁定为 baseline
        if (commandOpenSeen && firstBoxBaselineLuma == 0f && firstBoxLuma > 5f) {
            firstBoxBaselineLuma = firstBoxLuma
            Log.i(
                LOG_TAG,
                "[ENGINE][F$frameId] firstBox baseline latched=$firstBoxBaselineLuma",
            )
        }
        if (!previousCommandOpenSeen && commandOpenSeen) {
            Log.i(
                LOG_TAG,
                "[ENGINE][F$frameId] command-open latched firstBox=${firstBoxLuma} countdown=${countdownLuma} progress=${progressLuma}",
            )
        }
        if (!previousGlyphDisplaySeen && glyphDisplaySeen) {
            Log.i(
                LOG_TAG,
                "[ENGINE][F$frameId] glyph-display latched firstBox=${firstBoxLuma} activeEdges=${activeEdges.size}",
            )
        }

        if (commandOpenSeen && glyphDisplaySeen && activeEdges.isEmpty() && frameId % 5L == 0L) {
            val strongest = edgeEvidence.maxByOrNull { it.score }
            if (strongest != null) {
                Log.d(
                    LOG_TAG,
                    "[ENGINE][F$frameId] no active edges strongest=${strongest.edge.a}-${strongest.edge.b} score=${strongest.score} line=${strongest.lineBrightness} thresholds(score=${settings.edgeActivationThreshold}, line=${settings.minimumLineBrightness})",
                )
            }
        }

        val waitGoEligible = commandOpenSeen && glyphDisplaySeen && readyIndicatorsVisible
        if (waitGoEligible && !waitGoSeen) {
            waitGoSeen = true
            waitGoEnteredAtNs = SystemClock.elapsedRealtimeNanos()
        }

        // WAIT_GO 超时检测：超过配置时长未触发绘制则重置回 IDLE
        val waitGoTimedOut = waitGoSeen && !drawTriggered &&
            settings.waitGoTimeoutMs > 0L &&
            waitGoEnteredAtNs > 0L &&
            elapsedMs(waitGoEnteredAtNs) >= settings.waitGoTimeoutMs
        if (waitGoTimedOut) {
            Log.w(
                LOG_TAG,
                "[ENGINE][F$frameId] WAIT_GO timed out after ${elapsedMs(waitGoEnteredAtNs)}ms, resetting to IDLE",
            )
            resetForNextRound()
            // resetForNextRound 已将 phase 设为 IDLE，直接返回快照
            return GlyphSnapshot(
                phase = phase,
                currentGlyph = null,
                currentConfidence = 0f,
                sequence = emptyList(),
                activeEdges = activeEdges,
                edgeEvidence = edgeEvidence,
                goMatched = false,
                drawRequested = false,
                debugNodes = nodes,
                debugFrameWidth = bitmap.width,
                debugFrameHeight = bitmap.height,
                firstBoxRect = firstBoxRect,
                firstBoxLuma = firstBoxLuma,
                firstBoxBaselineLuma = firstBoxBaselineLuma,
                countdownRect = countdownRect,
                countdownLuma = countdownLuma,
                progressRect = progressRect,
                progressLuma = progressLuma,
                readyIndicatorsVisible = readyIndicatorsVisible,
            )
        }

        val glyphDisplayLatching = commandOpenSeen && glyphDisplaySeen && activeEdges.isNotEmpty()
        if (glyphDisplayLatching) {
            glyphDisplayLatched = true
            glyphGapFrames = 0
        } else if (glyphDisplayLatched && !drawTriggered && !waitGoEligible) {
            glyphGapFrames += 1
        } else {
            glyphGapFrames = 0
        }

        if (glyphDisplayLatched && glyphGapFrames > 12 && sequence.isEmpty()) {
            glyphDisplayLatched = false
            glyphGapFrames = 0
        }

        // 先计算 phase，再决定是否收集 glyph
        var drawRequested = false
        phase = when {
            drawTriggered -> GlyphPhase.AUTO_DRAW
            waitGoSeen -> GlyphPhase.WAIT_GO
            commandOpenSeen && (glyphDisplaySeen || sequence.isNotEmpty() || glyphDisplayLatched) -> GlyphPhase.GLYPH_DISPLAY
            commandOpenSeen -> GlyphPhase.COMMAND_OPEN
            else -> GlyphPhase.IDLE
        }

        // 严格限制：只在 GLYPH_DISPLAY 阶段收集 glyph
        if (!drawTriggered && phase == GlyphPhase.GLYPH_DISPLAY && !settings.suppressGlyphTracking) {
            updateSequence(candidateGlyphName)
        } else {
            resetGlyphTrackingOnly()
        }

        if (!drawTriggered) {
            if (waitGoSeen && firstBoxRect != null) {
                if (sequence.isEmpty() && firstBoxLuma >= WAIT_GO_EMPTY_SEQUENCE_IDLE_LUMA) {
                    Log.w(
                        LOG_TAG,
                        "[ENGINE][F$frameId] WAIT_GO firstBoxLuma=$firstBoxLuma but sequence is empty; reset to IDLE",
                    )
                    resetForNextRound()
                } else if (firstBoxLuma >= firstBoxBaselineLuma + settings.goColorDeltaThreshold) {
                    if (sequence.isNotEmpty()) {
                        drawRequested = true
                        drawTriggered = true
                        phase = GlyphPhase.AUTO_DRAW
                    } else {
                        Log.w(
                            LOG_TAG,
                            "[ENGINE][F$frameId] go detected but sequence is empty; keep waiting",
                        )
                        phase = GlyphPhase.WAIT_GO
                    }
                } else {
                    phase = GlyphPhase.WAIT_GO
                }
            }
        }

        if (drawTriggered && sequence.isEmpty()) {
            Log.w(
                LOG_TAG,
                "[ENGINE][F$frameId] AUTO_DRAW with empty sequence; reset to IDLE",
            )
            resetForNextRound()
        } else if (!drawTriggered && quietFramesAfterDraw != 0) {
            quietFramesAfterDraw = 0
        }

        val goMatched = waitGoSeen && readyIndicatorsVisible

        val snapshot = GlyphSnapshot(
            phase = phase,
            currentGlyph = if (drawTriggered) null else candidateGlyphName,
            currentConfidence = if (drawTriggered) 0f else candidateConfidence,
            sequence = sequence.toList(),
            activeEdges = activeEdges,
            edgeEvidence = edgeEvidence,
            goMatched = goMatched,
            drawRequested = drawRequested,
            debugNodes = nodes,
            debugFrameWidth = bitmap.width,
            debugFrameHeight = bitmap.height,
            firstBoxRect = firstBoxRect,
            firstBoxLuma = firstBoxLuma,
            firstBoxBaselineLuma = firstBoxBaselineLuma,
            countdownRect = countdownRect,
            countdownLuma = countdownLuma,
            progressRect = progressRect,
            progressLuma = progressLuma,
            readyIndicatorsVisible = readyIndicatorsVisible,
        )

        val totalDurationMs = elapsedMs(frameStartNs)
        val shouldLogFrame =
            totalDurationMs >= 120L ||
                drawRequested ||
                snapshot.phase != previousPhase ||
                frameId % 10L == 0L
        if (shouldLogFrame) {
            Log.d(
                LOG_TAG,
                "[ENGINE][F$frameId] total=${totalDurationMs}ms prep=${prepDurationMs}ms luma=${lumaDurationMs}ms edge=${edgeDurationMs}ms probe=${probeDurationMs}ms phase=${snapshot.phase} glyph=${snapshot.currentGlyph ?: "-"} conf=${snapshot.currentConfidence} activeEdges=${snapshot.activeEdges.size} seq=${formatSequence(snapshot.sequence)} waitGo=$waitGoSeen go=${snapshot.goMatched} draw=${snapshot.drawRequested}",
            )
        }
        if (snapshot.phase != previousPhase) {
            Log.i(
                LOG_TAG,
                "[ENGINE][F$frameId] phase $previousPhase -> ${snapshot.phase} seq=${formatSequence(snapshot.sequence)}",
            )
        }
        if (snapshot.drawRequested) {
            Log.i(
                LOG_TAG,
                "[ENGINE][F$frameId] draw requested with sequence=${formatSequence(snapshot.sequence)}",
            )
        }
        if (totalDurationMs >= 180L) {
            Log.w(LOG_TAG, "[ENGINE][F$frameId] frame processing is slow: ${totalDurationMs}ms")
        }

        return snapshot
    }

    private fun buildConfiguredRect(
        frameWidth: Int,
        frameHeight: Int,
        topPercent: Float,
        bottomPercent: Float,
    ): ProbeRect {
        val left = 0f
        val right = frameWidth.toFloat()
        val normalizedTop = (topPercent / 100f).coerceIn(0f, 1f)
        val normalizedBottom = (bottomPercent / 100f).coerceIn(0f, 1f)
        val top = minOf(normalizedTop, normalizedBottom) * frameHeight
        val bottom = maxOf(normalizedTop, normalizedBottom) * frameHeight
        return ProbeRect(
            left = left.coerceIn(0f, frameWidth.toFloat()),
            top = top.coerceIn(0f, frameHeight.toFloat()),
            right = right.coerceIn(0f, frameWidth.toFloat()),
            bottom = bottom.coerceIn(0f, frameHeight.toFloat()),
        )
    }

    private fun sampleRectAverage(frame: LumaFrame, rect: ProbeRect): Float {
        val left = rect.left.toInt().coerceIn(0, frame.width - 1)
        val right = rect.right.toInt().coerceIn(left + 1, frame.width)
        val top = rect.top.toInt().coerceIn(0, frame.height - 1)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, frame.height)
        val stepX = max(1, (right - left) / 16)
        val stepY = max(1, (bottom - top) / 6)

        var sum = 0f
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                sum += frame.sample(x.toFloat(), y.toFloat())
                count += 1
                x += stepX
            }
            y += stepY
        }
        return sum / max(1, count)
    }

    private fun updateSequence(candidateGlyphName: String?) {
        if (candidateGlyphName == null) {
            trackedGlyph = null
            return
        }

        val lastGlyph = sequence.lastOrNull()
        if (candidateGlyphName != lastGlyph) {
            sequence += candidateGlyphName
            Log.i(
                LOG_TAG,
                "[ENGINE][F$processedFrameCount] glyph committed=$candidateGlyphName sequence=${formatSequence(sequence)}",
            )
        }
        trackedGlyph = candidateGlyphName
    }

    private fun resetGlyphTrackingOnly() {
        trackedGlyph = null
    }

    private fun resetForNextRound() {
        val oldSequence = sequence.toList()
        val quietFrames = quietFramesAfterDraw
        sequence.clear()
        trackedGlyph = null
        drawTriggered = false
        quietFramesAfterDraw = 0
        commandOpenSeen = false
        glyphDisplaySeen = false
        waitGoSeen = false
        glyphDisplayLatched = false
        glyphGapFrames = 0
        waitGoEnteredAtNs = 0L
        firstBoxBaselineLuma = 0f
        phase = GlyphPhase.IDLE
        Log.i(
            LOG_TAG,
            "[ENGINE][F$processedFrameCount] reset for next round quietFramesAfterDraw=$quietFrames oldSequence=${formatSequence(oldSequence)}",
        )
    }

    private fun elapsedMs(startNs: Long, endNs: Long = SystemClock.elapsedRealtimeNanos()): Long {
        return ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun formatSequence(values: List<String>): String {
        return if (values.isEmpty()) "-" else values.joinToString(">")
    }

    private fun analyzeEdge(
        frame: LumaFrame,
        start: NodePosition,
        end: NodePosition,
        nodeRadius: Float,
        edge: GlyphEdge,
    ): EdgeEvidence {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        val directionY = dy / length
        val normalX = -directionY
        val normalY = dx / length

        val margin = (nodeRadius * 0.5f).coerceAtLeast(4f)
        val startT = (margin / length).coerceIn(0f, 0.4f)
        val endT = 1f - startT
        val sampleCount = max(8, (length / (nodeRadius * 0.7f)).toInt())
        val sideOffset = (nodeRadius * 0.7f).coerceAtLeast(4f)

        var lineBrightness = 0f
        var backgroundBrightness = 0f
        var usedSamples = 0

        for (i in 0 until sampleCount) {
            val t = startT + ((endT - startT) * (i + 0.5f) / sampleCount)
            val x = start.x + (dx * t)
            val y = start.y + (dy * t)
            val center = frame.sample(x, y)
            val left = frame.sample(x + normalX * sideOffset, y + normalY * sideOffset)
            val right = frame.sample(x - normalX * sideOffset, y - normalY * sideOffset)
            lineBrightness += center
            backgroundBrightness += (left + right) * 0.5f
            usedSamples += 1
        }

        val avgLineBrightness = lineBrightness / max(usedSamples, 1)
        val avgBackground = backgroundBrightness / max(usedSamples, 1)
        val score = avgLineBrightness - avgBackground
        return EdgeEvidence(
            edge = edge,
            score = score,
            lineBrightness = avgLineBrightness,
        )
    }

    /**
     * 对 11 个节点的 patch 区域做逐像素 MAE（Mean Absolute Error）匹配。
     *
     * 逐节点计算，任一节点 MAE 超过 [maxMae] 即提前返回 [Float.MAX_VALUE]，避免无谓计算。
     * 使用 [requestedSize] 作为采样边长：如果标定 patch 的原始边长不同，
     * 则按 min(requestedSize, patch.size) 取中心区域进行比较。
     */
    private fun computeNodePatchMae(
        frame: LumaFrame,
        calibrationProfile: CalibrationProfile,
        scaledNodes: List<NodePosition>,
        requestedSize: Int,
        maxMae: Float,
    ): Float {
        val patches = calibrationProfile.nodePatches
        if (patches.isEmpty()) return Float.MAX_VALUE

        var totalMae = 0f
        var matchedCount = 0

        for (patch in patches) {
            val node = scaledNodes.firstOrNull { it.index == patch.nodeIndex } ?: continue
            val useSize = minOf(requestedSize, patch.size)
            val half = useSize / 2
            val patchOffset = (patch.size - useSize) / 2

            var sumAbsDiff = 0f
            var pixelCount = 0
            for (py in 0 until useSize) {
                for (px in 0 until useSize) {
                    val frameX = node.x - half + px
                    val frameY = node.y - half + py
                    val frameLuma = frame.sample(frameX, frameY)
                    val patchLuma = patch.luma[(patchOffset + py) * patch.size + (patchOffset + px)]
                    sumAbsDiff += abs(frameLuma - patchLuma)
                    pixelCount++
                }
            }
            if (pixelCount > 0) {
                val nodeMae = sumAbsDiff / pixelCount
                if (nodeMae > maxMae) return Float.MAX_VALUE
                totalMae += nodeMae
                matchedCount++
            }
        }

        return if (matchedCount > 0) totalMae / matchedCount else Float.MAX_VALUE
    }

    data class EngineSettings(
        val edgeActivationThreshold: Float,
        val minimumLineBrightness: Float,
        val minimumMatchScore: Float,
        val commandOpenMaxLuma: Float,
        val glyphDisplayMinLuma: Float,
        val glyphDisplayTopBarsMinLuma: Float,
        val goColorDeltaThreshold: Float,
        val countdownVisibleThreshold: Float,
        val progressVisibleThreshold: Float,
        val firstBoxTopPercent: Float,
        val firstBoxBottomPercent: Float,
        val countdownTopPercent: Float,
        val countdownBottomPercent: Float,
        val progressTopPercent: Float,
        val progressBottomPercent: Float,
        val suppressGlyphTracking: Boolean,
        /** 节点 patch 匹配时使用的正方形边长（像素）。0 表示禁用 patch 匹配。 */
        val nodePatchSize: Int,
        /** 节点 patch MAE 阈值：平均绝对误差低于此值视为匹配。 */
        val nodePatchMaxMae: Float,
        /** WAIT_GO 阶段超时时间（毫秒），超时后重置回 IDLE。0 = 不超时。 */
        val waitGoTimeoutMs: Long,
    )

    private data class LumaFrame(
        val width: Int,
        val height: Int,
        val values: FloatArray,
    ) {
        fun sample(x: Float, y: Float): Float {
            val fx = x.coerceIn(0f, (width - 1).toFloat())
            val fy = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val tx = fx - x0
            val ty = fy - y0

            val c00 = values[y0 * width + x0]
            val c10 = values[y0 * width + x1]
            val c01 = values[y1 * width + x0]
            val c11 = values[y1 * width + x1]

            val a = c00 + (c10 - c00) * tx
            val b = c01 + (c11 - c01) * tx
            return a + (b - a) * ty
        }
    }

    private fun Bitmap.toLumaFrame(): LumaFrame {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val values = FloatArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            values[index] = (0.299f * r) + (0.587f * g) + (0.114f * b)
        }
        return LumaFrame(width = width, height = height, values = values)
    }
}
