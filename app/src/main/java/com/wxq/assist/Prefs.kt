package com.wxq.assist

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF

/**
 * 全局设置存储。所有坐标一律按屏幕比例(0~1)保存，
 * 这样横竖屏切换、换设备、换分辨率都不会错位。
 */
object Prefs {

    private const val FILE = "wxq_assist_prefs"

    // ---- 四项可调比例 ----
    const val KEY_UI_SCALE = "ui_scale"            // 整体缩放
    const val KEY_WIDTH_RATIO = "width_ratio"      // 面板宽度占屏幕比例
    const val KEY_HEIGHT_RATIO = "height_ratio"    // 面板高度占屏幕比例
    const val KEY_FONT_SCALE = "font_scale"        // 字体比例

    // ---- 外观 ----
    const val KEY_OPACITY = "opacity"              // 透明度
    const val KEY_CORNER = "corner_dp"             // 圆角半径(dp)

    // ---- 行为 ----
    const val KEY_LIVE_INTERVAL = "live_interval_ms"
    const val KEY_SHOW_REGION_GUIDE = "show_region_guide"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // 默认值针对一加 Ace2 Pro 横屏(2772x1240)调过，偏紧凑
    var uiScale: Float
        get() = sp.getFloat(KEY_UI_SCALE, 0.85f)
        set(v) = sp.edit().putFloat(KEY_UI_SCALE, v).apply()

    var widthRatio: Float
        get() = sp.getFloat(KEY_WIDTH_RATIO, 0.30f)
        set(v) = sp.edit().putFloat(KEY_WIDTH_RATIO, v).apply()

    var heightRatio: Float
        get() = sp.getFloat(KEY_HEIGHT_RATIO, 0.72f)
        set(v) = sp.edit().putFloat(KEY_HEIGHT_RATIO, v).apply()

    var fontScale: Float
        get() = sp.getFloat(KEY_FONT_SCALE, 0.90f)
        set(v) = sp.edit().putFloat(KEY_FONT_SCALE, v).apply()

    var opacity: Float
        get() = sp.getFloat(KEY_OPACITY, 0.92f)
        set(v) = sp.edit().putFloat(KEY_OPACITY, v).apply()

    var cornerDp: Float
        get() = sp.getFloat(KEY_CORNER, 14f)
        set(v) = sp.edit().putFloat(KEY_CORNER, v).apply()

    var liveIntervalMs: Int
        get() = sp.getInt(KEY_LIVE_INTERVAL, 1000)
        set(v) = sp.edit().putInt(KEY_LIVE_INTERVAL, v).apply()

    var showRegionGuide: Boolean
        get() = sp.getBoolean(KEY_SHOW_REGION_GUIDE, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_REGION_GUIDE, v).apply()

    // ---- 悬浮球 / 侧边把手位置(比例) ----
    var ballX: Float
        get() = sp.getFloat("ball_x", 0.02f)
        set(v) = sp.edit().putFloat("ball_x", v).apply()

    var ballY: Float
        get() = sp.getFloat("ball_y", 0.35f)
        set(v) = sp.edit().putFloat("ball_y", v).apply()

    var panelX: Float
        get() = sp.getFloat("panel_x", 0.66f)
        set(v) = sp.edit().putFloat("panel_x", v).apply()

    var panelY: Float
        get() = sp.getFloat("panel_y", 0.08f)
        set(v) = sp.edit().putFloat("panel_y", v).apply()

    /** 侧边上下滑动把手的纵向位置 */
    var handleY: Float
        get() = sp.getFloat("handle_y", 0.40f)
        set(v) = sp.edit().putFloat("handle_y", v).apply()

    /** 把手吸附在左边还是右边 */
    var handleLeft: Boolean
        get() = sp.getBoolean("handle_left", false)
        set(v) = sp.edit().putBoolean("handle_left", v).apply()

    // ---- 三个识别区域(屏幕比例) ----
    // 横屏下的经验初值，首次使用请在"区域校准"里拖一遍
    fun region(name: String): RectF {
        val d = defaultRegion(name)
        return RectF(
            sp.getFloat("rg_${name}_l", d.left),
            sp.getFloat("rg_${name}_t", d.top),
            sp.getFloat("rg_${name}_r", d.right),
            sp.getFloat("rg_${name}_b", d.bottom)
        )
    }

    fun setRegion(name: String, r: RectF) {
        sp.edit()
            .putFloat("rg_${name}_l", r.left)
            .putFloat("rg_${name}_t", r.top)
            .putFloat("rg_${name}_r", r.right)
            .putFloat("rg_${name}_b", r.bottom)
            .apply()
    }

    // 默认值取自 2026-11-01 在一加 Ace2 Pro (2772x1240) 上的实测校准结果。
    // 「恢复默认」会回到这一组，不用每次重新拖。
    private fun defaultRegion(name: String): RectF = when (name) {
        REGION_BOARD -> RectF(0.249f, 0.405f, 0.759f, 0.799f)
        REGION_SHOP -> RectF(0.073f, 0.074f, 0.792f, 0.614f)
        REGION_TALENT -> RectF(0.230f, 0.180f, 0.775f, 0.714f)
        else -> RectF(0.2f, 0.2f, 0.8f, 0.8f)
    }

    /** 两个区域的重叠面积占较小者的比例，用来提醒分区是否失效 */
    fun overlapRatio(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else inter / smaller
    }

    const val REGION_BOARD = "board"
    const val REGION_SHOP = "shop"
    const val REGION_TALENT = "talent"

    // ---- 阵容屏蔽 ----
    // 你明确不想玩的流派，建议引擎直接跳过，不再占推荐位
    fun blockedComps(): Set<String> =
        sp.getStringSet("blocked_comps", emptySet()) ?: emptySet()

    fun isCompBlocked(id: String): Boolean = blockedComps().contains(id)

    fun toggleCompBlocked(id: String): Boolean {
        val cur = blockedComps().toMutableSet()
        val nowBlocked = if (cur.contains(id)) { cur.remove(id); false } else { cur.add(id); true }
        // StringSet 必须存新集合实例，直接改引用不会落盘
        sp.edit().putStringSet("blocked_comps", HashSet(cur)).apply()
        return nowBlocked
    }

    fun clearBlockedComps() {
        sp.edit().putStringSet("blocked_comps", HashSet<String>()).apply()
    }

    fun resetAll() {
        sp.edit().clear().apply()
    }
}
