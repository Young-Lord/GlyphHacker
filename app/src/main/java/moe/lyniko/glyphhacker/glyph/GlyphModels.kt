package moe.lyniko.glyphhacker.glyph

import kotlin.math.max
import kotlin.math.min

data class NodePosition(
    val index: Int,
    val x: Float,
    val y: Float,
)

/**
 * 标定帧中某个节点周围的亮度 patch，用于 command channel open 的逐像素相似度匹配。
 *
 * [size] 为正方形边长（像素），[luma] 按行优先存储，长度 = size * size。
 * 坐标原点为节点质心向左上偏移 size/2 的位置。
 */
data class NodePatch(
    val nodeIndex: Int,
    val size: Int,
    val luma: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodePatch) return false
        return nodeIndex == other.nodeIndex && size == other.size && luma.contentEquals(other.luma)
    }

    override fun hashCode(): Int {
        var result = nodeIndex
        result = 31 * result + size
        result = 31 * result + luma.contentHashCode()
        return result
    }
}

/**
 * 从 command channel open 截图（标定帧）中提取的节点布局信息。
 *
 * 标定帧即 Ingress 长按 HACK 后 command channel 刚打开时的画面：纯黑背景 + 11 个高亮节点。
 * [sourceWidth]/[sourceHeight] 对应该截图的原始分辨率，[nodes] 中的坐标均基于该分辨率。
 */
data class CalibrationProfile(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val nodeRadiusPx: Float,
    val nodes: List<NodePosition>,
    val roiLeft: Float,
    val roiTop: Float,
    val roiRight: Float,
    val roiBottom: Float,
    /** 标定帧中每个节点周围的亮度 patch，用于 IDLE->COMMAND_OPEN 的逐像素相似度验证。 */
    val nodePatches: List<NodePatch> = emptyList(),
) {
    fun scaledNodes(targetWidth: Int, targetHeight: Int): List<NodePosition> {
        val sx = targetWidth / sourceWidth.toFloat()
        val sy = targetHeight / sourceHeight.toFloat()
        return nodes.map { node ->
            node.copy(x = node.x * sx, y = node.y * sy)
        }
    }

    fun scaledNodeRadius(targetWidth: Int, targetHeight: Int): Float {
        val sx = targetWidth / sourceWidth.toFloat()
        val sy = targetHeight / sourceHeight.toFloat()
        return nodeRadiusPx * ((sx + sy) * 0.5f)
    }
}

data class GlyphEdge(val a: Int, val b: Int) {
    init {
        require(a < b)
    }

    companion object {
        fun of(first: Int, second: Int): GlyphEdge {
            val low = min(first, second)
            val high = max(first, second)
            return GlyphEdge(low, high)
        }
    }
}

data class ProbeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class ReadyBoxProfile(
    val firstBoxLeftNorm: Float,
    val firstBoxTopNorm: Float,
    val firstBoxRightNorm: Float,
    val firstBoxBottomNorm: Float,
    val boxHeightNorm: Float,
    val countdownLeftNorm: Float,
    val countdownTopNorm: Float,
    val countdownRightNorm: Float,
    val countdownBottomNorm: Float,
    val progressLeftNorm: Float,
    val progressTopNorm: Float,
    val progressRightNorm: Float,
    val progressBottomNorm: Float,
) {
    fun firstBoxRect(): ProbeRect {
        return ProbeRect(
            left = firstBoxLeftNorm,
            top = firstBoxTopNorm,
            right = firstBoxRightNorm,
            bottom = firstBoxBottomNorm,
        )
    }

    fun countdownRect(): ProbeRect {
        return ProbeRect(
            left = countdownLeftNorm,
            top = countdownTopNorm,
            right = countdownRightNorm,
            bottom = countdownBottomNorm,
        )
    }

    fun progressRect(): ProbeRect {
        return ProbeRect(
            left = progressLeftNorm,
            top = progressTopNorm,
            right = progressRightNorm,
            bottom = progressBottomNorm,
        )
    }
}

enum class GlyphPhase {
    IDLE,
    COMMAND_OPEN,
    GLYPH_DISPLAY,
    WAIT_GO,
    AUTO_DRAW,
}

data class GlyphDefinition(
    val canonicalName: String,
    val aliases: Set<String>,
    val edges: Set<GlyphEdge>,
    val strokePlan: List<Int>? = null,
)

data class GlyphMatch(
    val definition: GlyphDefinition,
    val score: Float,
)

data class EdgeEvidence(
    val edge: GlyphEdge,
    val score: Float,
    val lineBrightness: Float,
)

data class GlyphSnapshot(
    val phase: GlyphPhase,
    val currentGlyph: String?,
    val currentConfidence: Float,
    val sequence: List<String>,
    val activeEdges: Set<GlyphEdge>,
    val edgeEvidence: List<EdgeEvidence>,
    val goMatched: Boolean,
    val drawRequested: Boolean,
    val debugNodes: List<NodePosition>,
    val debugFrameWidth: Int,
    val debugFrameHeight: Int,
    val firstBoxRect: ProbeRect?,
    val firstBoxLuma: Float,
    val firstBoxBaselineLuma: Float,
    val countdownRect: ProbeRect?,
    val countdownLuma: Float,
    val progressRect: ProbeRect?,
    val progressLuma: Float,
    val readyIndicatorsVisible: Boolean,
)
