package moe.lyniko.glyphhacker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.lyniko.glyphhacker.glyph.GlyphEdge
import moe.lyniko.glyphhacker.glyph.GlyphPhase
import moe.lyniko.glyphhacker.glyph.GlyphSnapshot
import moe.lyniko.glyphhacker.glyph.NodePosition
import moe.lyniko.glyphhacker.glyph.ProbeRect

data class RuntimeState(
    val captureRunning: Boolean = false,
    val recognitionEnabled: Boolean = true,
    val inputEnabled: Boolean = true,
    val overlayVisible: Boolean = false,
    val phase: GlyphPhase = GlyphPhase.IDLE,
    val currentGlyph: String? = null,
    val sequence: List<String> = emptyList(),
    val drawRemainingCount: Int = 0,
    val activeEdges: Set<GlyphEdge> = emptySet(),
    val debugNodes: List<NodePosition> = emptyList(),
    val debugFrameWidth: Int = 0,
    val debugFrameHeight: Int = 0,
    val firstBoxRect: ProbeRect? = null,
    val firstBoxLuma: Float = 0f,
    val firstBoxBaselineLuma: Float = 0f,
    val countdownRect: ProbeRect? = null,
    val countdownLuma: Float = 0f,
    val progressRect: ProbeRect? = null,
    val progressLuma: Float = 0f,
    val readyIndicatorsVisible: Boolean = false,
    val confidence: Float = 0f,
    val goMatched: Boolean = false,
    val lastUpdatedAtMs: Long = 0L,
)

object RuntimeStateBus {
    private val _state = MutableStateFlow(RuntimeState())
    val state = _state.asStateFlow()

    fun updateFromSnapshot(snapshot: GlyphSnapshot, captureRunning: Boolean) {
        val previousState = _state.value
        if (!previousState.recognitionEnabled) {
            _state.value = previousState.copy(
                captureRunning = captureRunning,
                lastUpdatedAtMs = System.currentTimeMillis(),
            )
            return
        }
        val sequenceCount = snapshot.sequence.size
        val drawRemainingCount = when (snapshot.phase) {
            GlyphPhase.AUTO_DRAW -> {
                if (snapshot.drawRequested) {
                    sequenceCount
                } else {
                    previousState.drawRemainingCount.coerceIn(0, sequenceCount)
                }
            }

            else -> sequenceCount
        }
        _state.value = RuntimeState(
            captureRunning = captureRunning,
            recognitionEnabled = previousState.recognitionEnabled,
            inputEnabled = previousState.inputEnabled,
            overlayVisible = previousState.overlayVisible,
            phase = snapshot.phase,
            currentGlyph = snapshot.currentGlyph,
            sequence = snapshot.sequence,
            drawRemainingCount = drawRemainingCount,
            activeEdges = snapshot.activeEdges,
            debugNodes = snapshot.debugNodes,
            debugFrameWidth = snapshot.debugFrameWidth,
            debugFrameHeight = snapshot.debugFrameHeight,
            firstBoxRect = snapshot.firstBoxRect,
            firstBoxLuma = snapshot.firstBoxLuma,
            firstBoxBaselineLuma = snapshot.firstBoxBaselineLuma,
            countdownRect = snapshot.countdownRect,
            countdownLuma = snapshot.countdownLuma,
            progressRect = snapshot.progressRect,
            progressLuma = snapshot.progressLuma,
            readyIndicatorsVisible = snapshot.readyIndicatorsVisible,
            confidence = snapshot.currentConfidence,
            goMatched = snapshot.goMatched,
            lastUpdatedAtMs = System.currentTimeMillis(),
        )
    }

    fun setDrawRemainingCount(value: Int) {
        val current = _state.value
        if (!current.recognitionEnabled) {
            return
        }
        _state.value = current.copy(
            drawRemainingCount = value.coerceAtLeast(0),
            lastUpdatedAtMs = System.currentTimeMillis(),
        )
    }

    fun setRecognitionEnabled(enabled: Boolean) {
        val current = _state.value
        val now = System.currentTimeMillis()
        _state.value = if (enabled) {
            current.copy(recognitionEnabled = true, lastUpdatedAtMs = now)
        } else {
            current.copy(
                recognitionEnabled = false,
                phase = GlyphPhase.IDLE,
                currentGlyph = null,
                sequence = emptyList(),
                drawRemainingCount = 0,
                activeEdges = emptySet(),
                debugNodes = emptyList(),
                debugFrameWidth = 0,
                debugFrameHeight = 0,
                firstBoxRect = null,
                firstBoxLuma = 0f,
                firstBoxBaselineLuma = 0f,
                countdownRect = null,
                countdownLuma = 0f,
                progressRect = null,
                progressLuma = 0f,
                readyIndicatorsVisible = false,
                confidence = 0f,
                goMatched = false,
                lastUpdatedAtMs = now,
            )
        }
    }

    fun setCaptureRunning(running: Boolean) {
        _state.value = _state.value.copy(captureRunning = running, lastUpdatedAtMs = System.currentTimeMillis())
    }

    fun setOverlayVisible(visible: Boolean) {
        _state.value = _state.value.copy(overlayVisible = visible, lastUpdatedAtMs = System.currentTimeMillis())
    }

    fun setInputEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(inputEnabled = enabled, lastUpdatedAtMs = System.currentTimeMillis())
    }

    fun setIdle(captureRunning: Boolean = _state.value.captureRunning) {
        val current = _state.value
        _state.value = current.copy(
            captureRunning = captureRunning,
            phase = GlyphPhase.IDLE,
            currentGlyph = null,
            sequence = emptyList(),
            drawRemainingCount = 0,
            activeEdges = emptySet(),
            debugNodes = emptyList(),
            debugFrameWidth = 0,
            debugFrameHeight = 0,
            firstBoxRect = null,
            firstBoxLuma = 0f,
            firstBoxBaselineLuma = 0f,
            countdownRect = null,
            countdownLuma = 0f,
            progressRect = null,
            progressLuma = 0f,
            readyIndicatorsVisible = false,
            confidence = 0f,
            goMatched = false,
            lastUpdatedAtMs = System.currentTimeMillis(),
        )
    }

    fun reset() {
        val recognitionEnabled = _state.value.recognitionEnabled
        val inputEnabled = _state.value.inputEnabled
        val overlayVisible = _state.value.overlayVisible
        _state.value = RuntimeState(
            recognitionEnabled = recognitionEnabled,
            inputEnabled = inputEnabled,
            overlayVisible = overlayVisible,
            lastUpdatedAtMs = System.currentTimeMillis(),
        )
    }
}
