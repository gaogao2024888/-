package com.wxq.assist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 全屏虚线区域标定层。
 *
 * 作用：把屏幕划成三块互不重叠的识别区，识别时只截对应那块，
 * 所以在商店页面点"识别阵容"不会把商店里的牌算进阵容。
 *
 * 阵容区下边界是一条加粗的黄色虚线，就是阵容和商店的分界。
 */
class RegionGuideView(ctx: Context) : View(ctx) {

    /** true = 可拖动编辑；false = 只读显示，不吃触摸事件 */
    var editable: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    var onRegionChanged: ((String, RectF) -> Unit)? = null

    private val regions = linkedMapOf(
        Prefs.REGION_BOARD to Prefs.region(Prefs.REGION_BOARD),
        Prefs.REGION_SHOP to Prefs.region(Prefs.REGION_SHOP),
        Prefs.REGION_TALENT to Prefs.region(Prefs.REGION_TALENT)
    )

    private val colors = mapOf(
        Prefs.REGION_BOARD to 0xFFFFC94A.toInt(),
        Prefs.REGION_SHOP to 0xFF4ADE80.toInt(),
        Prefs.REGION_TALENT to 0xFF60A5FA.toInt()
    )

    private val names = mapOf(
        Prefs.REGION_BOARD to "阵容区",
        Prefs.REGION_SHOP to "商店区",
        Prefs.REGION_TALENT to "天赋区"
    )

    /** 当前只显示这一个区域；null = 全显示 */
    var focusRegion: String? = null
        set(v) {
            field = v
            invalidate()
        }

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = UiKit.dp(ctx, 1.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val boundaryDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = UiKit.dp(ctx, 2.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(22f, 12f), 0f)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = UiKit.dp(ctx, 11f).toFloat()
        isFakeBoldText = true
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 拖拽状态
    private var dragName: String? = null
    private var dragCorner = -1     // 0=整体平移 1=左上 2=右上 3=左下 4=右下
    private var downX = 0f
    private var downY = 0f
    private var startRect = RectF()

    private val handleR get() = UiKit.dp(context, 11f).toFloat()

    fun reload() {
        regions.keys.forEach { regions[it] = Prefs.region(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        regions.forEach { (name, rf) ->
            if (focusRegion != null && focusRegion != name) return@forEach

            val c = colors[name] ?: Color.WHITE
            val px = RectF(rf.left * w, rf.top * h, rf.right * w, rf.bottom * h)

            // 淡淡的填充，让区域一眼可见但不挡视线
            fill.color = (c and 0x00FFFFFF) or 0x14000000
            canvas.drawRect(px, fill)

            dash.color = c
            canvas.drawRect(px, dash)

            // 阵容区下边界画加粗虚线 —— 阵容 / 商店 的分界线
            if (name == Prefs.REGION_BOARD) {
                boundaryDash.color = c
                canvas.drawLine(px.left, px.bottom, px.right, px.bottom, boundaryDash)
                textPaint.color = c
                canvas.drawText(
                    "▲ 以上为阵容  ▼ 以下为商店",
                    px.left + UiKit.dp(context, 6f),
                    px.bottom + UiKit.dp(context, 14f),
                    textPaint
                )
            }

            textPaint.color = c
            canvas.drawText(
                names[name] ?: name,
                px.left + UiKit.dp(context, 6f),
                px.top + UiKit.dp(context, 15f),
                textPaint
            )

            if (editable) {
                handlePaint.color = c
                listOf(
                    px.left to px.top, px.right to px.top,
                    px.left to px.bottom, px.right to px.bottom
                ).forEach { (cx, cy) -> canvas.drawCircle(cx, cy, handleR, handlePaint) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                val hit = hitTest(event.x, event.y, w, h) ?: return false
                val name = hit.first
                dragName = name
                dragCorner = hit.second
                startRect = RectF(regions[name] ?: return false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val name = dragName ?: return false
                val dx = (event.x - downX) / w
                val dy = (event.y - downY) / h
                val r = RectF(startRect)

                when (dragCorner) {
                    0 -> r.offset(dx, dy)
                    1 -> { r.left += dx; r.top += dy }
                    2 -> { r.right += dx; r.top += dy }
                    3 -> { r.left += dx; r.bottom += dy }
                    4 -> { r.right += dx; r.bottom += dy }
                }

                // 夹在屏幕内，且不能小于 8%
                r.left = r.left.coerceIn(0f, 0.92f)
                r.top = r.top.coerceIn(0f, 0.92f)
                r.right = r.right.coerceIn(r.left + 0.08f, 1f)
                r.bottom = r.bottom.coerceIn(r.top + 0.08f, 1f)

                regions[name] = r
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val name = dragName
                val r = if (name != null) regions[name] else null
                if (name != null && r != null) {
                    Prefs.setRegion(name, r)
                    onRegionChanged?.invoke(name, r)
                    Logger.i("Region", "$name 已保存: ${fmt(r)}")
                }
                dragName = null
                dragCorner = -1
                return true
            }
        }
        return false
    }

    private fun fmt(r: RectF) =
        "L%.3f T%.3f R%.3f B%.3f".format(r.left, r.top, r.right, r.bottom)

    private fun hitTest(x: Float, y: Float, w: Float, h: Float): Pair<String, Int>? {
        val candidates = regions.entries.filter { focusRegion == null || it.key == focusRegion }
        // 先判角点，再判区域内部
        for ((name, rf) in candidates.reversed()) {
            val px = RectF(rf.left * w, rf.top * h, rf.right * w, rf.bottom * h)
            val corners = listOf(
                1 to (px.left to px.top), 2 to (px.right to px.top),
                3 to (px.left to px.bottom), 4 to (px.right to px.bottom)
            )
            for ((idx, c) in corners) {
                if (abs(x - c.first) < handleR * 1.8f && abs(y - c.second) < handleR * 1.8f) {
                    return name to idx
                }
            }
        }
        for ((name, rf) in candidates.reversed()) {
            val px = RectF(rf.left * w, rf.top * h, rf.right * w, rf.bottom * h)
            if (px.contains(x, y)) return name to 0
        }
        return null
    }
}
