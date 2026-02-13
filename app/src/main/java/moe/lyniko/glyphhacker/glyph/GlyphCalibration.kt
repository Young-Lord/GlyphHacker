package moe.lyniko.glyphhacker.glyph

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从 command channel open 截图（即"空白帧"）中检测 11 个 glyph 节点位置，生成 [CalibrationProfile]。
 *
 * "标定帧"就是 Ingress 中长按 HACK 后 command channel 刚打开时的截图——此时屏幕背景为纯黑，
 * 11 个 glyph 节点以高亮圆点形式显示，尚未开始绘制任何 glyph。
 * 这也是状态机中 [GlyphPhase.COMMAND_OPEN] 阶段对应的画面。
 */
object GlyphCalibration {

    private data class Blob(
        val area: Int,
        val centroidX: Float,
        val centroidY: Float,
        val radius: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val meanLuma: Float,
    )

    /**
     * 从 command channel open 帧中提取 11 个节点的位置。
     *
     * @param bitmap command channel open 时的屏幕截图（纯黑背景 + 11 个高亮节点）。
     * @return 标定结果，包含节点坐标和 ROI；若未能检测到恰好 11 个节点则返回 null。
     */
    fun calibrateFromBlankFrame(bitmap: Bitmap): CalibrationProfile? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val value = luminance(pixels[i])
            luma[i] = value
            histogram[value]++
        }

        val threshold = quantileThreshold(histogram, total = pixels.size, quantile = 0.984f)
        val yStart = (height * 0.2f).toInt().coerceIn(0, height - 1)
        val yEnd = (height * 0.92f).toInt().coerceIn(1, height)
        val mask = BooleanArray(pixels.size)
        for (y in yStart until yEnd) {
            var row = y * width
            for (x in 0 until width) {
                if (luma[row + x] >= threshold) {
                    mask[row + x] = true
                }
            }
        }

        val blobs = collectBlobs(mask, luma, width, height)
            .filter { blob ->
                val widthSpan = blob.maxX - blob.minX + 1
                val heightSpan = blob.maxY - blob.minY + 1
                val ratio = widthSpan / max(heightSpan, 1).toFloat()
                blob.area >= 30 &&
                    blob.area <= (width * height / 16) &&
                    ratio in 0.45f..1.85f &&
                    blob.centroidY in yStart.toFloat()..yEnd.toFloat()
            }
            .sortedByDescending { it.area * it.meanLuma }

        if (blobs.isEmpty()) {
            return null
        }

        val selectedBlobs = selectElevenDistinct(blobs, width, height)
        if (selectedBlobs.size != 11) {
            return null
        }

        val rawNodes = selectedBlobs.mapIndexed { index, blob ->
            NodePosition(
                index = index,
                x = blob.centroidX,
                y = blob.centroidY,
            )
        }

        val indexedNodes = assignNodeIndices(rawNodes) ?: return null
        val estimatedRadius = selectedBlobs.map { it.radius }.average().toFloat().coerceIn(6f, min(width, height) * 0.08f)
        val roi = GlyphGeometry.normalizedRoi(
            GlyphGeometry.estimateRoi(indexedNodes, estimatedRadius * 1.8f),
            width,
            height,
        )

        val patchSize = (estimatedRadius * 2).toInt().coerceIn(4, min(width, height) / 4)
        val nodePatches = extractNodePatches(indexedNodes, luma, width, height, patchSize)

        return CalibrationProfile(
            sourceWidth = width,
            sourceHeight = height,
            nodeRadiusPx = estimatedRadius,
            nodes = indexedNodes.sortedBy { it.index },
            roiLeft = roi[0],
            roiTop = roi[1],
            roiRight = roi[2],
            roiBottom = roi[3],
            nodePatches = nodePatches,
        )
    }

    private fun selectElevenDistinct(blobs: List<Blob>, width: Int, height: Int): List<Blob> {
        val selected = ArrayList<Blob>(11)
        val minDistance = min(width, height) * 0.06f
        blobs.forEach { blob ->
            val tooClose = selected.any { existing ->
                hypot(existing.centroidX - blob.centroidX, existing.centroidY - blob.centroidY) < minDistance
            }
            if (!tooClose) {
                selected += blob
            }
            if (selected.size == 11) {
                return selected
            }
        }
        return selected
    }

    private fun collectBlobs(mask: BooleanArray, luma: IntArray, width: Int, height: Int): List<Blob> {
        val visited = BooleanArray(mask.size)
        val queueX = IntArray(mask.size)
        val queueY = IntArray(mask.size)
        val blobs = ArrayList<Blob>()
        val neighbors = intArrayOf(-1, 0, 1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val startIndex = y * width + x
                if (!mask[startIndex] || visited[startIndex]) {
                    continue
                }

                var head = 0
                var tail = 0
                queueX[tail] = x
                queueY[tail] = y
                tail++
                visited[startIndex] = true

                var area = 0
                var sumX = 0f
                var sumY = 0f
                var sumLuma = 0f
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++
                    val index = cy * width + cx
                    area++
                    sumX += cx
                    sumY += cy
                    sumLuma += luma[index]
                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    neighbors.forEach { dy ->
                        neighbors.forEach { dx ->
                            if (dx == 0 && dy == 0) {
                                return@forEach
                            }
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                                return@forEach
                            }
                            val nIndex = ny * width + nx
                            if (mask[nIndex] && !visited[nIndex]) {
                                visited[nIndex] = true
                                queueX[tail] = nx
                                queueY[tail] = ny
                                tail++
                            }
                        }
                    }
                }

                if (area > 0) {
                    val radius = sqrt(area.toFloat() / PI.toFloat())
                    blobs += Blob(
                        area = area,
                        centroidX = sumX / area,
                        centroidY = sumY / area,
                        radius = radius,
                        minX = minX,
                        maxX = maxX,
                        minY = minY,
                        maxY = maxY,
                        meanLuma = sumLuma / area,
                    )
                }
            }
        }
        return blobs
    }

    private fun assignNodeIndices(points: List<NodePosition>): List<NodePosition>? {
        if (points.size != 11) {
            return null
        }
        val centroid = GlyphGeometry.centroid(points)
        val center = points.minByOrNull { point ->
            val dx = point.x - centroid.x
            val dy = point.y - centroid.y
            (dx * dx) + (dy * dy)
        } ?: return null

        val withoutCenter = points.filter { it !== center }
        val top = withoutCenter.minByOrNull { it.y } ?: return null
        val bottom = withoutCenter.maxByOrNull { it.y } ?: return null
        val sideCandidates = withoutCenter.filter { it !== top && it !== bottom }
        if (sideCandidates.size != 8) {
            return null
        }

        val (leftRaw, rightRaw) = GlyphGeometry.splitLeftRight(sideCandidates, centroid.x)
        if (leftRaw.size != 4 || rightRaw.size != 4) {
            return null
        }

        val left = GlyphGeometry.classifySide(
            points = leftRaw,
            isLeft = true,
            outerTopIndex = 5,
            outerBottomIndex = 4,
            innerTopIndex = 9,
            innerBottomIndex = 8,
        )
        val right = GlyphGeometry.classifySide(
            points = rightRaw,
            isLeft = false,
            outerTopIndex = 1,
            outerBottomIndex = 2,
            innerTopIndex = 6,
            innerBottomIndex = 7,
        )
        if (left.size != 4 || right.size != 4) {
            return null
        }

        val result = ArrayList<NodePosition>(11)
        result += NodePosition(index = 0, x = top.x, y = top.y)
        result += NodePosition(index = 3, x = bottom.x, y = bottom.y)
        result += NodePosition(index = 10, x = center.x, y = center.y)
        result += left
        result += right

        return if (result.map { it.index }.distinct().size == 11) {
            result.sortedBy { it.index }
        } else {
            null
        }
    }

    private fun quantileThreshold(histogram: IntArray, total: Int, quantile: Float): Int {
        val target = (total * quantile).toInt().coerceIn(0, total)
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) {
                return max(120, value)
            }
        }
        return 190
    }

    /**
     * 从标定帧的亮度数组中，为每个节点裁出 [patchSize] x [patchSize] 的亮度 patch。
     * patch 中心对齐节点质心，超出图像边界的像素填 0。
     */
    private fun extractNodePatches(
        nodes: List<NodePosition>,
        luma: IntArray,
        width: Int,
        height: Int,
        patchSize: Int,
    ): List<NodePatch> {
        val half = patchSize / 2
        return nodes.sortedBy { it.index }.map { node ->
            val cx = node.x.toInt()
            val cy = node.y.toInt()
            val patch = FloatArray(patchSize * patchSize)
            for (py in 0 until patchSize) {
                val srcY = cy - half + py
                for (px in 0 until patchSize) {
                    val srcX = cx - half + px
                    patch[py * patchSize + px] = if (
                        srcX in 0 until width && srcY in 0 until height
                    ) {
                        luma[srcY * width + srcX].toFloat()
                    } else {
                        0f
                    }
                }
            }
            NodePatch(nodeIndex = node.index, size = patchSize, luma = patch)
        }
    }

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return ((0.299f * r) + (0.587f * g) + (0.114f * b)).toInt()
    }
}
