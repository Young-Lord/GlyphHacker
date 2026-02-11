package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

class GlyphRecognitionEngine {

    private companion object {
        private const val LOG_TAG = "GlyphHacker"
    }

    data class SessionState(
        val phase: GlyphPhase,
        val sequence: List<String>,
        val trackedGlyph: String?,
        val trackedFrames: Int,
        val trackedCommitted: Boolean,
        val blankFrames: Int,
        val drawTriggered: Boolean,
        val quietFramesAfterDraw: Int,
        val commandOpenSeen: Boolean,
        val glyphDisplaySeen: Boolean,
        val glyphDisplayLatched: Boolean,
        val glyphGapFrames: Int,
    )

    private var phase: GlyphPhase = GlyphPhase.IDLE
    private val sequence = mutableListOf<String>()
    private var trackedGlyph: String? = null
    private var trackedFrames: Int = 0
    private var trackedCommitted: Boolean = false
    private var blankFrames: Int = 0
    private var drawTriggered: Boolean = false
    private var quietFramesAfterDraw: Int = 0
    private var commandOpenSeen: Boolean = false
    private var glyphDisplaySeen: Boolean = false
    private var glyphDisplayLatched: Boolean = false
    private var glyphGapFrames: Int = 0
    private var processedFrameCount: Long = 0L

    fun resetSession() {
        phase = GlyphPhase.IDLE
        sequence.clear()
        trackedGlyph = null
        trackedFrames = 0
        trackedCommitted = false
        blankFrames = 0
        drawTriggered = false
        quietFramesAfterDraw = 0
        commandOpenSeen = false
        glyphDisplaySeen = false
        glyphDisplayLatched = false
        glyphGapFrames = 0
        processedFrameCount = 0L
    }

    fun snapshotSessionState(): SessionState {
        return SessionState(
            phase = phase,
            sequence = sequence.toList(),
            trackedGlyph = trackedGlyph,
            trackedFrames = trackedFrames,
            trackedCommitted = trackedCommitted,
            blankFrames = blankFrames,
            drawTriggered = drawTriggered,
            quietFramesAfterDraw = quietFramesAfterDraw,
            commandOpenSeen = commandOpenSeen,
            glyphDisplaySeen = glyphDisplaySeen,
            glyphDisplayLatched = glyphDisplayLatched,
            glyphGapFrames = glyphGapFrames,
        )
    }

    fun restoreSessionState(state: SessionState) {
        phase = state.phase
        sequence.clear()
        sequence.addAll(state.sequence)
        trackedGlyph = state.trackedGlyph
        trackedFrames = state.trackedFrames
        trackedCommitted = state.trackedCommitted
        blankFrames = state.blankFrames
        drawTriggered = state.drawTriggered
        quietFramesAfterDraw = state.quietFramesAfterDraw
        commandOpenSeen = state.commandOpenSeen
        glyphDisplaySeen = state.glyphDisplaySeen
        glyphDisplayLatched = state.glyphDisplayLatched
        glyphGapFrames = state.glyphGapFrames
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
        val commandOpenDetected = firstBoxLuma < settings.commandOpenMaxLuma &&
            countdownLuma < settings.commandOpenMaxLuma &&
            progressLuma < settings.commandOpenMaxLuma
        val glyphDisplayDetected = firstBoxLuma > settings.glyphDisplayMinLuma
        if (commandOpenDetected) {
            commandOpenSeen = true
        }
        if (commandOpenSeen && glyphDisplayDetected) {
            glyphDisplaySeen = true
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

        if (!drawTriggered && commandOpenSeen && glyphDisplaySeen) {
            updateSequence(candidateGlyphName, settings.stableFrameCount)
        } else {
            resetGlyphTrackingOnly()
        }

        if (commandOpenSeen && activeEdges.isNotEmpty()) {
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

        var drawRequested = false
        phase = when {
            drawTriggered -> GlyphPhase.AUTO_DRAW
            waitGoEligible -> GlyphPhase.WAIT_GO
            commandOpenSeen && (glyphDisplaySeen || activeEdges.isNotEmpty() || sequence.isNotEmpty() || glyphDisplayLatched) -> GlyphPhase.GLYPH_DISPLAY
            commandOpenSeen -> GlyphPhase.COMMAND_OPEN
            else -> GlyphPhase.IDLE
        }

        if (!drawTriggered) {
            if (waitGoEligible && firstBoxRect != null) {
                if (firstBoxLuma >= settings.goColorDeltaThreshold) {
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
            } else {
                if (phase == GlyphPhase.WAIT_GO && !waitGoEligible) {
                    phase = GlyphPhase.GLYPH_DISPLAY
                }
            }
        }

        if (drawTriggered) {
            if (activeEdges.isEmpty()) {
                quietFramesAfterDraw += 1
                if (quietFramesAfterDraw > 20) {
                    resetForNextRound()
                }
            } else {
                quietFramesAfterDraw = 0
            }
        }

        val snapshot = GlyphSnapshot(
            phase = phase,
            currentGlyph = candidateGlyphName,
            currentConfidence = candidateConfidence,
            sequence = sequence.toList(),
            activeEdges = activeEdges,
            edgeEvidence = edgeEvidence,
            goMatched = waitGoEligible,
            drawRequested = drawRequested,
            debugNodes = nodes,
            debugFrameWidth = bitmap.width,
            debugFrameHeight = bitmap.height,
            firstBoxRect = firstBoxRect,
            firstBoxLuma = firstBoxLuma,
            firstBoxBaselineLuma = 0f,
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
                "[ENGINE][F$frameId] total=${totalDurationMs}ms prep=${prepDurationMs}ms luma=${lumaDurationMs}ms edge=${edgeDurationMs}ms probe=${probeDurationMs}ms phase=${snapshot.phase} glyph=${snapshot.currentGlyph ?: "-"} conf=${snapshot.currentConfidence} activeEdges=${snapshot.activeEdges.size} seq=${formatSequence(snapshot.sequence)} waitGo=$waitGoEligible go=${snapshot.goMatched} draw=${snapshot.drawRequested}",
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

    private fun updateSequence(candidateGlyphName: String?, stableFrameCount: Int) {
        if (candidateGlyphName == null) {
            if (trackedGlyph != null) {
                Log.d(
                    LOG_TAG,
                    "[ENGINE][F$processedFrameCount] glyph tracking reset because candidate is blank",
                )
            }
            trackedGlyph = null
            trackedFrames = 0
            trackedCommitted = false
            blankFrames += 1
            return
        }

        if (candidateGlyphName == trackedGlyph) {
            trackedFrames += 1
        } else {
            val previousTrackedGlyph = trackedGlyph
            trackedGlyph = candidateGlyphName
            trackedFrames = 1
            trackedCommitted = false
            if (previousTrackedGlyph != candidateGlyphName) {
                Log.d(
                    LOG_TAG,
                    "[ENGINE][F$processedFrameCount] tracking glyph switched to $candidateGlyphName",
                )
            }
        }

        val lastGlyph = sequence.lastOrNull()
        val glyphChanged = lastGlyph == null || lastGlyph != candidateGlyphName
        val stabilityReached = trackedFrames >= stableFrameCount
        val allowFastCommit = glyphChanged && (blankFrames >= 1 || sequence.isEmpty()) && trackedFrames >= 1

        if (!trackedCommitted && (stabilityReached || allowFastCommit)) {
            if (glyphChanged) {
                sequence += candidateGlyphName
            }
            trackedCommitted = true
            blankFrames = 0
            Log.i(
                LOG_TAG,
                "[ENGINE][F$processedFrameCount] glyph committed=$candidateGlyphName trackedFrames=$trackedFrames stability=$stabilityReached fastCommit=$allowFastCommit sequence=${formatSequence(sequence)}",
            )
        }
    }

    private fun resetGlyphTrackingOnly() {
        trackedGlyph = null
        trackedFrames = 0
        trackedCommitted = false
        blankFrames = 0
    }

    private fun resetForNextRound() {
        val oldSequence = sequence.toList()
        val quietFrames = quietFramesAfterDraw
        sequence.clear()
        trackedGlyph = null
        trackedFrames = 0
        trackedCommitted = false
        blankFrames = 0
        drawTriggered = false
        quietFramesAfterDraw = 0
        commandOpenSeen = false
        glyphDisplaySeen = false
        glyphDisplayLatched = false
        glyphGapFrames = 0
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

    data class EngineSettings(
        val edgeActivationThreshold: Float,
        val minimumLineBrightness: Float,
        val stableFrameCount: Int,
        val minimumMatchScore: Float,
        val commandOpenMaxLuma: Float,
        val glyphDisplayMinLuma: Float,
        val goColorDeltaThreshold: Float,
        val countdownVisibleThreshold: Float,
        val progressVisibleThreshold: Float,
        val firstBoxTopPercent: Float,
        val firstBoxBottomPercent: Float,
        val countdownTopPercent: Float,
        val countdownBottomPercent: Float,
        val progressTopPercent: Float,
        val progressBottomPercent: Float,
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
