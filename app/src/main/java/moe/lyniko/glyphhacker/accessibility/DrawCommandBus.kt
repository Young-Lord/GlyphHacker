package moe.lyniko.glyphhacker.accessibility

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import moe.lyniko.glyphhacker.data.RecognitionMode
import moe.lyniko.glyphhacker.glyph.CalibrationProfile

data class DrawCommand(
    val recognitionMode: RecognitionMode,
    val glyphNames: List<String>,
    val calibrationProfile: CalibrationProfile,
    val frameWidth: Int,
    val frameHeight: Int,
    val edgeDurationMs: Long,
    val glyphGapMs: Long,
    val doneButtonXPercent: Float,
    val doneButtonYPercent: Float,
    val sourceFrameId: Long = -1L,
    val sourceFrameCapturedAtElapsedMs: Long = 0L,
    val sourceFrameAnalyzedAtElapsedMs: Long = 0L,
    val emittedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
)

data class DrawCompletion(
    val sourceFrameId: Long,
    val doneButtonTapped: Boolean,
    val completedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
)

object DrawCommandBus {
    private val _commands = MutableSharedFlow<DrawCommand>(extraBufferCapacity = 8)
    private val _completions = MutableSharedFlow<DrawCompletion>(extraBufferCapacity = 8)
    val commands = _commands.asSharedFlow()
    val completions = _completions.asSharedFlow()

    fun tryEmit(command: DrawCommand): Boolean {
        return _commands.tryEmit(command)
    }

    fun tryEmitCompletion(completion: DrawCompletion): Boolean {
        return _completions.tryEmit(completion)
    }
}
