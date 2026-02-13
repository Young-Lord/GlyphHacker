package moe.lyniko.glyphhacker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import moe.lyniko.glyphhacker.glyph.GlyphDictionary
import moe.lyniko.glyphhacker.glyph.GlyphEdge
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 在悬浮窗底部横向绘制 glyph 序列的自定义 View。
 *
 * 每个 glyph 以标准 11 节点布局 + 对应边集合的方式绘制。
 * 调用 [updateSequence] 更新要显示的 glyph 名称列表，
 * 调用 [setGlyphSizePx] 设置每个 glyph 图标的像素边长。
 */
class GlyphSequenceView(context: Context) : View(context) {

    private var glyphNames: List<String> = emptyList()
    private var glyphSizePx: Int = (28 * context.resources.displayMetrics.density).toInt()
    private val internalPadding: Int = (context.resources.displayMetrics.density * 3f).toInt()

    private val bgPaint = Paint().apply {
        color = 0xB30F1420.toInt()
        style = Paint.Style.FILL
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0E0FF.toInt()
        style = Paint.Style.FILL
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val dimNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55B0E0FF
        style = Paint.Style.FILL
    }

    fun updateSequence(names: List<String>) {
        if (names == glyphNames) return
        glyphNames = names
        requestLayout()
        invalidate()
    }

    fun setGlyphSizePx(sizePx: Int) {
        if (sizePx == glyphSizePx) return
        glyphSizePx = sizePx.coerceAtLeast(8)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = glyphNames.size
        if (count == 0) {
            setMeasuredDimension(0, 0)
            return
        }
        val gap = (glyphSizePx * 0.15f).toInt().coerceAtLeast(2)
        val contentWidth = count * glyphSizePx + (count - 1).coerceAtLeast(0) * gap
        setMeasuredDimension(contentWidth + internalPadding * 2, glyphSizePx + internalPadding * 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glyphNames.isEmpty()) return

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val size = glyphSizePx.toFloat()
        val gap = (size * 0.15f).coerceAtLeast(2f)
        val nodeRadius = size * 0.055f
        val edgeWidth = (size * 0.06f).coerceAtLeast(1.2f)
        edgePaint.strokeWidth = edgeWidth

        var offsetX = internalPadding.toFloat()
        for (name in glyphNames) {
            val definition = GlyphDictionary.findByName(name)
            drawSingleGlyph(canvas, offsetX, internalPadding.toFloat(), size, nodeRadius, definition?.edges ?: emptySet())
            offsetX += size + gap
        }
    }

    private fun drawSingleGlyph(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        nodeRadius: Float,
        edges: Set<GlyphEdge>,
    ) {
        val padding = size * 0.1f
        val drawSize = size - padding * 2
        val cx = left + size / 2f
        val cy = top + size / 2f

        // 标准 11 节点归一化坐标 (基于 Ingress glyph 六边形布局)
        // 节点索引: 0=top, 1=右外上, 2=右外下, 3=bottom, 4=左外下, 5=左外上
        //           6=右内上, 7=右内下, 8=左内下, 9=左内上, 10=center(a)
        val nodePositions = computeNodePositions(cx, cy, drawSize / 2f)

        // 先画边
        for (edge in edges) {
            val ax = nodePositions[edge.a * 2]
            val ay = nodePositions[edge.a * 2 + 1]
            val bx = nodePositions[edge.b * 2]
            val by = nodePositions[edge.b * 2 + 1]
            canvas.drawLine(ax, ay, bx, by, edgePaint)
        }

        // 再画节点
        val activeNodes = mutableSetOf<Int>()
        for (edge in edges) {
            activeNodes.add(edge.a)
            activeNodes.add(edge.b)
        }
        for (i in 0 until NODE_COUNT) {
            val nx = nodePositions[i * 2]
            val ny = nodePositions[i * 2 + 1]
            val paint = if (activeNodes.contains(i)) nodePaint else dimNodePaint
            canvas.drawCircle(nx, ny, nodeRadius, paint)
        }
    }

    private companion object {
        const val NODE_COUNT = 11

        /**
         * 计算 11 个节点的像素坐标，返回 FloatArray(22)，
         * 偶数下标为 x，奇数下标为 y。
         *
         * 布局基于正六边形：
         * - 外圈 6 个节点 (0-5) 在半径 R 的正六边形顶点
         * - 内圈 4 个节点 (6-9) 在半径 R*0.5 的位置
         * - 中心 1 个节点 (10)
         *
         * 六边形顶点从正上方开始顺时针排列。
         */
        fun computeNodePositions(cx: Float, cy: Float, radius: Float): FloatArray {
            val positions = FloatArray(NODE_COUNT * 2)
            val outerR = radius
            val innerR = radius * 0.5f

            // 外圈 6 个节点: 0=top, 1=右上, 2=右下, 3=bottom, 4=左下, 5=左上
            // 角度从 -90° 开始 (正上方)，每 60° 一个
            for (i in 0 until 6) {
                val angle = Math.toRadians((-90.0 + i * 60.0))
                positions[i * 2] = cx + outerR * cos(angle).toFloat()
                positions[i * 2 + 1] = cy + outerR * sin(angle).toFloat()
            }

            // 内圈 4 个节点: 6=右上, 7=右下, 8=左下, 9=左上
            // 对应外圈的 1,2,4,5 方向
            val innerAngles = intArrayOf(1, 2, 4, 5)
            for (j in 0 until 4) {
                val angle = Math.toRadians((-90.0 + innerAngles[j] * 60.0))
                val idx = (6 + j) * 2
                positions[idx] = cx + innerR * cos(angle).toFloat()
                positions[idx + 1] = cy + innerR * sin(angle).toFloat()
            }

            // 中心节点 10
            positions[20] = cx
            positions[21] = cy

            return positions
        }
    }
}
