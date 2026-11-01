package com.wxq.assist

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 统一的视觉与尺寸工具。
 * 所有尺寸都乘 Prefs.uiScale，所以一个滑块就能整体放大缩小。
 */
object UiKit {

    // 深色紧凑配色，横屏游戏里不刺眼
    const val BG_PANEL = 0xFF151A23.toInt()
    const val BG_ELEV = 0xFF1E2430.toInt()
    const val BG_BTN = 0xFF2A3342.toInt()
    const val BG_BTN_ON = 0xFF3B82F6.toInt()
    const val ACCENT = 0xFFFFC94A.toInt()
    const val TEXT_MAIN = 0xFFE8EDF5.toInt()
    const val TEXT_DIM = 0xFF93A0B4.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val WARN = 0xFFFFB84D.toInt()
    const val OK = 0xFF4ADE80.toInt()

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 带整体缩放的 dp */
    fun sdp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density * Prefs.uiScale).toInt()

    /** 带整体缩放和字体比例的 sp */
    fun ssp(v: Float): Float = v * Prefs.uiScale * Prefs.fontScale

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    fun roundedStroke(color: Int, strokeColor: Int, strokeW: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            setStroke(strokeW, strokeColor)
        }

    fun ripple(base: GradientDrawable, rippleColor: Int = 0x33FFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)

    /**
     * 构建一个自适应按钮：尺寸由 LayoutParams 决定，
     * 内部文字自动换行+自动缩放，绝不把按钮撑大。
     */
    fun button(
        ctx: Context,
        label: String,
        active: Boolean = false,
        onClick: (() -> Unit)? = null
    ): AutoFitTextView {
        val radius = Prefs.cornerDp * ctx.resources.displayMetrics.density * 0.6f
        return AutoFitTextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else TEXT_MAIN)
            maxTextSp = ssp(13f)
            minTextSp = ssp(7f)
            maxLines = 2
            val padH = sdp(ctx, 6f)
            val padV = sdp(ctx, 4f)
            setPadding(padH, padV, padH, padV)
            background = ripple(rounded(if (active) BG_BTN_ON else BG_BTN, radius))
            isClickable = true
            onClick?.let { cb -> setOnClickListener { cb() } }
        }
    }

    fun label(ctx: Context, text: String, size: Float = 12f, color: Int = TEXT_MAIN): AutoFitTextView =
        AutoFitTextView(ctx).apply {
            this.text = text
            setTextColor(color)
            maxTextSp = ssp(size)
            minTextSp = ssp(7f)
            maxLines = 3
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** 等宽填充的行内子项 */
    fun weight(v: Float = 1f, h: Int = LinearLayout.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(0, h, v)

    fun wrapRow(h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)

    fun spacer(ctx: Context, sizeDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(sdp(ctx, sizeDp), sdp(ctx, sizeDp))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
        ).apply { topMargin = sdp(ctx, 4f); bottomMargin = sdp(ctx, 4f) }
        setBackgroundColor(0x22FFFFFF)
    }

    fun frameParams(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) =
        FrameLayout.LayoutParams(w, h, gravity)
}
