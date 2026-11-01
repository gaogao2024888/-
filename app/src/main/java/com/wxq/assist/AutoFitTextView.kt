package com.wxq.assist

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

/**
 * 按钮/标签内的自适应文字。
 *
 * 规则：
 *  1. 按钮尺寸由外部布局决定，文字永远不把按钮撑大
 *  2. 文字先按宽度自动换行
 *  3. 换行后若仍放不下，二分法缩小字号直到塞进去
 *  4. 空间富裕时自动放大回上限，不会一直停在小字号
 */
class AutoFitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 字号上限(sp)，由外部按整体缩放设置 */
    var maxTextSp: Float = 14f
        set(value) {
            field = value
            needRefit = true
            requestLayout()
        }

    /** 字号下限(sp)，再小就没法看了 */
    var minTextSp: Float = 6f
        set(value) {
            field = value
            needRefit = true
            requestLayout()
        }

    private val measurePaint = TextPaint()
    private var needRefit = true
    private var lastW = -1
    private var lastH = -1

    init {
        // 必须允许多行，否则没法自动换行
        setSingleLine(false)
        maxLines = 4
        includeFontPadding = false
    }

    override fun onTextChanged(
        text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        needRefit = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) needRefit = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (needRefit || w != lastW || h != lastH) {
            lastW = w
            lastH = h
            needRefit = false
            refit(w, h)
        }
    }

    private fun refit(viewW: Int, viewH: Int) {
        val text = text?.toString() ?: return
        if (text.isEmpty()) return

        val availW = viewW - paddingLeft - paddingRight
        val availH = viewH - paddingTop - paddingBottom
        if (availW <= 0 || availH <= 0) return

        val density = resources.displayMetrics.scaledDensity
        var lo = minTextSp
        var hi = maxTextSp
        var best = minTextSp

        // 二分 10 轮足够收敛到 0.05sp 精度
        repeat(10) {
            val mid = (lo + hi) / 2f
            if (fits(text, mid * density, availW, availH)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        // 直接改 Paint 再 requestLayout 会递归，这里只设一次并跳过重新测量
        if (kotlin.math.abs(textSize - best * density) > 0.5f) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, best)
        }
    }

    private fun fits(text: String, textSizePx: Float, availW: Int, availH: Int): Boolean {
        measurePaint.set(paint)
        measurePaint.textSize = textSizePx

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, measurePaint, availW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .build()

        if (layout.height > availH) return false
        // 单个词超宽时 StaticLayout 不会报错但会溢出，这里再核一遍
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > availW) return false
        }
        return true
    }
}
