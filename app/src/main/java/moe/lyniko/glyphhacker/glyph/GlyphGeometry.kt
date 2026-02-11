package moe.lyniko.glyphhacker.glyph

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object GlyphGeometry {

    fun buildAtomicEdges(nodes: List<NodePosition>, nodeRadius: Float): List<GlyphEdge> {
        if (nodes.size < 2) {
            return emptyList()
        }
        val indexToNode = nodes.associateBy { it.index }
        val indices = nodes.map { it.index }.sorted()
        val edges = ArrayList<GlyphEdge>()
        for (i in 0 until indices.lastIndex) {
            for (j in i + 1 until indices.size) {
                val a = indices[i]
                val b = indices[j]
                val start = indexToNode[a] ?: continue
                val end = indexToNode[b] ?: continue
                var blocked = false
                nodes.forEach { node ->
                    if (node.index != a && node.index != b) {
                        val projection = projectionRatioOnSegment(start, end, node)
                        if (projection in 0.15f..0.85f) {
                            val distance = distanceToSegment(start, end, node)
                            if (distance < nodeRadius * 0.8f) {
                                blocked = true
                            }
                        }
                    }
                }
                if (!blocked) {
                    edges += GlyphEdge.of(a, b)
                }
            }
        }
        return edges
    }

    fun distanceToSegment(start: NodePosition, end: NodePosition, point: NodePosition): Float {
        val projection = projectionRatioOnSegment(start, end, point)
        val clamped = projection.coerceIn(0f, 1f)
        val projectedX = lerp(start.x, end.x, clamped)
        val projectedY = lerp(start.y, end.y, clamped)
        return hypot(point.x - projectedX, point.y - projectedY)
    }

    fun projectionRatioOnSegment(start: NodePosition, end: NodePosition, point: NodePosition): Float {
        val vx = end.x - start.x
        val vy = end.y - start.y
        val wx = point.x - start.x
        val wy = point.y - start.y
        val denominator = (vx * vx) + (vy * vy)
        if (denominator < 1e-5f) {
            return 0f
        }
        return ((wx * vx) + (wy * vy)) / denominator
    }

    fun lerp(start: Float, end: Float, ratio: Float): Float {
        return start + (end - start) * ratio
    }

    fun estimateNodeRadius(nodes: List<NodePosition>): Float {
        if (nodes.size < 2) {
            return 8f
        }
        val nearestDistances = nodes.map { node ->
            nodes.filter { other -> other.index != node.index }
                .minOf { other -> hypot(node.x - other.x, node.y - other.y) }
        }
        val median = nearestDistances.sorted()[nearestDistances.size / 2]
        return max(6f, median * 0.18f)
    }

    fun estimateRoi(nodes: List<NodePosition>, padding: Float): FloatArray {
        val left = nodes.minOf { it.x } - padding
        val top = nodes.minOf { it.y } - padding
        val right = nodes.maxOf { it.x } + padding
        val bottom = nodes.maxOf { it.y } + padding
        return floatArrayOf(left, top, right, bottom)
    }

    fun clampRoiToSize(roi: FloatArray, width: Int, height: Int): FloatArray {
        return floatArrayOf(
            roi[0].coerceIn(0f, width.toFloat()),
            roi[1].coerceIn(0f, height.toFloat()),
            roi[2].coerceIn(0f, width.toFloat()),
            roi[3].coerceIn(0f, height.toFloat()),
        )
    }

    fun normalizedRoi(roi: FloatArray, width: Int, height: Int): FloatArray {
        val clamped = clampRoiToSize(roi, width, height)
        return floatArrayOf(
            clamped[0] / width,
            clamped[1] / height,
            clamped[2] / width,
            clamped[3] / height,
        )
    }

    fun sortNodesByIndex(nodes: List<NodePosition>): List<NodePosition> {
        return nodes.sortedBy { it.index }
    }

    fun distance(a: NodePosition, b: NodePosition): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    fun centroid(nodes: List<NodePosition>): NodePosition {
        val cx = nodes.sumOf { it.x.toDouble() }.toFloat() / max(nodes.size, 1)
        val cy = nodes.sumOf { it.y.toDouble() }.toFloat() / max(nodes.size, 1)
        return NodePosition(index = -1, x = cx, y = cy)
    }

    fun splitLeftRight(nodes: List<NodePosition>, centerX: Float): Pair<List<NodePosition>, List<NodePosition>> {
        var left = nodes.filter { it.x < centerX }
        var right = nodes.filter { it.x >= centerX }
        if (left.size != 4 || right.size != 4) {
            val sorted = nodes.sortedBy { it.x }
            left = sorted.take(4)
            right = sorted.takeLast(4)
        }
        return left to right
    }

    fun classifySide(
        points: List<NodePosition>,
        isLeft: Boolean,
        outerTopIndex: Int,
        outerBottomIndex: Int,
        innerTopIndex: Int,
        innerBottomIndex: Int,
    ): List<NodePosition> {
        if (points.size != 4) {
            return emptyList()
        }
        val xSorted = if (isLeft) {
            points.sortedBy { it.x }
        } else {
            points.sortedByDescending { it.x }
        }
        val outer = xSorted.take(2).sortedBy { it.y }
        val inner = xSorted.drop(2).sortedBy { it.y }
        if (outer.size != 2 || inner.size != 2) {
            return emptyList()
        }
        return listOf(
            NodePosition(outerTopIndex, outer[0].x, outer[0].y),
            NodePosition(outerBottomIndex, outer[1].x, outer[1].y),
            NodePosition(innerTopIndex, inner[0].x, inner[0].y),
            NodePosition(innerBottomIndex, inner[1].x, inner[1].y),
        )
    }

    fun absoluteRoi(profile: CalibrationProfile, width: Int, height: Int): FloatArray {
        return floatArrayOf(
            profile.roiLeft * width,
            profile.roiTop * height,
            profile.roiRight * width,
            profile.roiBottom * height,
        )
    }

    fun roiContains(roi: FloatArray, x: Float, y: Float): Boolean {
        return x >= min(roi[0], roi[2]) && x <= max(roi[0], roi[2]) && y >= min(roi[1], roi[3]) && y <= max(roi[1], roi[3])
    }

    fun axisDistance(a: NodePosition, b: NodePosition): Float {
        return abs(a.x - b.x) + abs(a.y - b.y)
    }
}
