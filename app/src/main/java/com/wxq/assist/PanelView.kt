package com.wxq.assist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 主面板。横屏优先的紧凑布局：
 * 标题栏一行、标签页一行、内容区自适应剩余空间。
 */
@SuppressLint("ViewConstructor")
class PanelView(
    ctx: Context,
    private val host: Host
) : LinearLayout(ctx) {

    interface Host {
        fun onCollapse()                       // 点 X：只收起面板，服务继续跑
        fun onExitApp()                        // 真正退出
        fun onRecognize(region: String)        // 手动识别，按一次给一次
        fun onToggleLive(on: Boolean)          // 实时建议 1 秒一次
        fun onToggleCalibrate(on: Boolean)     // 区域校准虚线框
        fun onRequestProjection()
        fun onSettingsChanged()
        fun isLiveOn(): Boolean
        fun isCalibrating(): Boolean
        fun isProjectionReady(): Boolean
    }

    private enum class Tab { ADVICE, RECOGNIZE, COMPS, LOG, SETTINGS }

    companion object {
        /** 改设置会重建面板，用它记住当前停在哪一页，别每次都跳回「建议」 */
        private var lastTab = Tab.ADVICE
    }

    private var current = lastTab
    private val content = FrameLayout(ctx)
    private val tabButtons = mutableMapOf<Tab, AutoFitTextView>()

    private var adviceText: TextView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var statusDot: TextView? = null

    private val logListener: () -> Unit = {
        post { if (current == Tab.LOG) refreshLog() else updateLogBadge() }
    }

    /** 外部结束校准后，刷新「识别」页那个按钮的文字和高亮 */
    fun refreshCalibrateButton() {
        post { if (current == Tab.RECOGNIZE) showTab(Tab.RECOGNIZE) }
    }

    init {
        orientation = VERTICAL
        applySkin()
        buildHeader()
        buildTabs()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showTab(Tab.ADVICE)
        Logger.addListener(logListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Logger.removeListener(logListener)
    }

    fun applySkin() {
        val radius = Prefs.cornerDp * resources.displayMetrics.density
        background = UiKit.rounded(UiKit.BG_PANEL, radius)
        alpha = Prefs.opacity
        val p = UiKit.sdp(context, 6f)
        setPadding(p, p, p, p)
    }

    /** 设置改动后整表重建，保证所有尺寸/字号同步生效 */
    fun rebuild() {
        removeAllViews()
        tabButtons.clear()
        content.removeAllViews()
        applySkin()
        buildHeader()
        buildTabs()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showTab(current)
    }

    // ---------------- 标题栏 ----------------

    private fun buildHeader() {
        val bar = UiKit.row(context)
        bar.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 30f))

        val title = UiKit.label(context, "万象棋助手", 13f, UiKit.ACCENT).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        statusDot = UiKit.label(context, "", 10f, UiKit.TEXT_DIM).apply {
            maxLines = 1
            gravity = Gravity.CENTER
        }
        bar.addView(statusDot, LinearLayout.LayoutParams(
            UiKit.sdp(context, 54f), LayoutParams.MATCH_PARENT
        ))

        // 关键修复：X 只收起面板，服务和悬浮球继续存在
        val close = UiKit.button(context, "✕") { host.onCollapse() }
        bar.addView(close, LinearLayout.LayoutParams(
            UiKit.sdp(context, 28f), UiKit.sdp(context, 24f)
        ).apply { marginStart = UiKit.sdp(context, 4f) })

        addView(bar)
        updateStatus()
    }

    fun updateStatus() {
        statusDot?.text = if (host.isProjectionReady()) "● 已就绪" else "○ 未授权"
        statusDot?.setTextColor(if (host.isProjectionReady()) UiKit.OK else UiKit.TEXT_DIM)
    }

    // ---------------- 标签页 ----------------

    private fun buildTabs() {
        val bar = UiKit.row(context)
        bar.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 28f)).apply {
            topMargin = UiKit.sdp(context, 4f)
        }

        fun tab(t: Tab, label: String) {
            val b = UiKit.button(context, label, active = (t == current)) { showTab(t) }
            b.maxLines = 1
            tabButtons[t] = b
            bar.addView(b, UiKit.weight(1f).apply { marginEnd = UiKit.sdp(context, 3f) })
        }

        tab(Tab.ADVICE, "建议")
        tab(Tab.RECOGNIZE, "识别")
        tab(Tab.COMPS, "阵容")
        tab(Tab.LOG, "日志")
        tab(Tab.SETTINGS, "设置")

        addView(bar)
        addView(UiKit.divider(context))
    }

    private fun showTab(t: Tab) {
        current = t
        lastTab = t
        val radius = Prefs.cornerDp * resources.displayMetrics.density * 0.6f
        tabButtons.forEach { (k, v) ->
            val on = k == t
            v.background = UiKit.ripple(
                UiKit.rounded(if (on) UiKit.BG_BTN_ON else UiKit.BG_BTN, radius)
            )
            v.setTextColor(if (on) Color.WHITE else UiKit.TEXT_MAIN)
        }
        content.removeAllViews()
        val view = when (t) {
            Tab.ADVICE -> buildAdvice()
            Tab.RECOGNIZE -> buildRecognize()
            Tab.COMPS -> buildComps()
            Tab.LOG -> buildLog()
            Tab.SETTINGS -> buildSettings()
        }
        content.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        updateLogBadge()
    }

    private fun updateLogBadge() {
        val n = Logger.errorCount()
        tabButtons[Tab.LOG]?.text = if (n > 0) "日志($n)" else "日志"
        tabButtons[Tab.LOG]?.setTextColor(
            if (n > 0 && current != Tab.LOG) UiKit.WARN
            else if (current == Tab.LOG) Color.WHITE else UiKit.TEXT_MAIN
        )
    }

    // ---------------- 建议页 ----------------

    private fun buildAdvice(): View {
        val root = UiKit.col(context)

        val btnRow = UiKit.row(context)
        btnRow.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 32f))

        val liveBtn = UiKit.button(
            context,
            if (host.isLiveOn()) "实时建议 开" else "实时建议 关",
            active = host.isLiveOn()
        )
        liveBtn.setOnClickListener {
            val next = !host.isLiveOn()
            host.onToggleLive(next)
            liveBtn.text = if (next) "实时建议 开" else "实时建议 关"
            val radius = Prefs.cornerDp * resources.displayMetrics.density * 0.6f
            liveBtn.background = UiKit.ripple(
                UiKit.rounded(if (next) UiKit.BG_BTN_ON else UiKit.BG_BTN, radius)
            )
            liveBtn.setTextColor(if (next) Color.WHITE else UiKit.TEXT_MAIN)
        }
        btnRow.addView(liveBtn, UiKit.weight(1f).apply { marginEnd = UiKit.sdp(context, 4f) })

        // 按一次给一次
        val onceBtn = UiKit.button(context, "识别一次并给建议") {
            host.onRecognize(Prefs.REGION_BOARD)
        }
        btnRow.addView(onceBtn, UiKit.weight(1.4f))

        root.addView(btnRow)

        val row2 = UiKit.row(context)
        row2.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 26f)).apply {
            topMargin = UiKit.sdp(context, 4f)
        }
        val hint = UiKit.label(
            context,
            if (GameData.loaded)
                "数据 ${GameData.heroes.size}英雄/${GameData.comps.size}阵容 · ${GameData.generatedAt}"
            else "游戏数据加载中…",
            9.5f, UiKit.TEXT_DIM
        ).apply { maxLines = 2 }
        row2.addView(hint, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row2.addView(
            UiKit.button(context, "重置本局") {
                Advisor.resetRound()
                setAdvice("已重置本局状态。\n新的一局开始时点一下这里，避免上局的阵容影响建议。")
            },
            LinearLayout.LayoutParams(UiKit.sdp(context, 58f), LayoutParams.MATCH_PARENT)
        )
        root.addView(row2)

        val scroll = ScrollView(context)
        adviceText = TextView(context).apply {
            setTextColor(UiKit.TEXT_MAIN)
            textSize = UiKit.ssp(12f)
            setLineSpacing(0f, 1.15f)
            text = "等待识别。\n\n首次使用请先到「识别」页授权截屏，并校准三个区域的虚线框。"
            val p = UiKit.sdp(context, 6f)
            setPadding(p, p, p, p)
        }
        scroll.addView(adviceText)
        scroll.background = UiKit.rounded(
            UiKit.BG_ELEV, Prefs.cornerDp * resources.displayMetrics.density * 0.5f
        )
        root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = UiKit.sdp(context, 5f)
        })

        return root
    }

    fun setAdvice(text: String) {
        post { adviceText?.text = text }
    }

    // ---------------- 识别页 ----------------

    private fun buildRecognize(): View {
        val root = UiKit.col(context)

        if (!host.isProjectionReady()) {
            val grant = UiKit.button(context, "① 授权截屏权限") { host.onRequestProjection() }
            root.addView(grant, UiKit.wrapRow(UiKit.sdp(context, 32f)))
        }

        val r1 = UiKit.row(context)
        r1.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 34f)).apply {
            topMargin = UiKit.sdp(context, 5f)
        }
        r1.addView(
            UiKit.button(context, "识别当前阵容") { host.onRecognize(Prefs.REGION_BOARD) },
            UiKit.weight(1f).apply { marginEnd = UiKit.sdp(context, 4f) }
        )
        r1.addView(
            UiKit.button(context, "识别商店") { host.onRecognize(Prefs.REGION_SHOP) },
            UiKit.weight(1f).apply { marginEnd = UiKit.sdp(context, 4f) }
        )
        r1.addView(
            UiKit.button(context, "识别天赋") { host.onRecognize(Prefs.REGION_TALENT) },
            UiKit.weight(1f)
        )
        root.addView(r1)

        val calBtn = UiKit.button(
            context,
            if (host.isCalibrating()) "完成校准（点此保存）" else "② 区域校准（显示虚线框）",
            active = host.isCalibrating()
        )
        calBtn.setOnClickListener {
            val next = !host.isCalibrating()
            host.onToggleCalibrate(next)
            calBtn.text = if (next) "完成校准（点此保存）" else "② 区域校准（显示虚线框）"
            val radius = Prefs.cornerDp * resources.displayMetrics.density * 0.6f
            calBtn.background = UiKit.ripple(
                UiKit.rounded(if (next) UiKit.BG_BTN_ON else UiKit.BG_BTN, radius)
            )
        }
        root.addView(calBtn, UiKit.wrapRow(UiKit.sdp(context, 32f)).apply {
            topMargin = UiKit.sdp(context, 5f)
        })

        val tip = UiKit.label(
            context,
            "三个按钮各自只截对应区域，在商店页面点「识别当前阵容」不会把商店的牌算进去。\n" +
                "校准时面板会自动收起，调完点屏幕顶部绿色的「✓ 完成校准」即可，面板会自动回来。\n" +
                "黄色=阵容区，绿色=商店区，蓝色=天赋区；拖方框移动，拖四角改大小，松手即保存。",
            10f, UiKit.TEXT_DIM
        )
        tip.maxLines = 8
        root.addView(tip, UiKit.wrapRow().apply { topMargin = UiKit.sdp(context, 6f) })

        return root
    }

    // ---------------- 阵容页 ----------------

    /** 阵容页的两个子页：手动选上阵 / 屏蔽名单 */
    private var compsSubTab = 0

    private fun buildComps(): View {
        val root = UiKit.col(context)

        if (!GameData.loaded) {
            root.addView(UiKit.label(context, "游戏数据还在加载…", 11f, UiKit.TEXT_DIM))
            return root
        }

        val switcher = UiKit.row(context)
        switcher.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 28f))
        listOf("我的上阵", "屏蔽阵容").forEachIndexed { i, label ->
            val on = compsSubTab == i
            val b = UiKit.button(context, label, active = on) {
                compsSubTab = i
                showTab(Tab.COMPS)
            }
            b.maxLines = 1
            switcher.addView(b, UiKit.weight(1f).apply {
                marginEnd = if (i == 0) UiKit.sdp(context, 4f) else 0
            })
        }
        root.addView(switcher)
        root.addView(UiKit.divider(context))

        if (compsSubTab == 0) {
            root.addView(buildBoardPicker(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(buildBlockList(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return root
    }

    /**
     * 手动勾选上阵英雄。
     * 棋盘上的棋子是 3D 模型没有文字，OCR 读不到；手选虽然土，但 100% 准确，
     * 而且商店打分和天赋打分本来就只需要知道"场上有谁"。
     */
    private fun buildBoardPicker(): View {
        val root = UiKit.col(context)

        val bar = UiKit.row(context)
        bar.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 30f))
        val cnt = UiKit.label(
            context, "已选 ${Advisor.currentBoard().size} 人", 11f, UiKit.ACCENT
        ).apply { maxLines = 1 }
        bar.addView(cnt, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bar.addView(
            UiKit.button(context, "出建议") {
                setAdvice(Advisor.adviseFromBoard())
                showTab(Tab.ADVICE)
            },
            LinearLayout.LayoutParams(
                UiKit.sdp(context, 54f), LayoutParams.MATCH_PARENT
            ).apply { marginEnd = UiKit.sdp(context, 4f) }
        )
        bar.addView(
            UiKit.button(context, "清空") {
                Advisor.clearBoard()
                showTab(Tab.COMPS)
            },
            LinearLayout.LayoutParams(UiKit.sdp(context, 44f), LayoutParams.MATCH_PARENT)
        )
        root.addView(bar)

        val scroll = ScrollView(context)
        val list = UiKit.col(context)

        // 按阵营分组，凑羁绊时一眼能看出同阵营还差谁
        GameData.heroes.groupBy { it.camp }.toList()
            .sortedBy { it.first }
            .forEach { (camp, heroes) ->
                val picked = heroes.count { Advisor.isOnBoard(it.name) }
                val head = UiKit.label(
                    context,
                    "$camp  ($picked/${heroes.size})",
                    10.5f,
                    if (picked > 0) UiKit.ACCENT else UiKit.TEXT_DIM
                ).apply { maxLines = 1 }
                list.addView(head, UiKit.wrapRow().apply {
                    topMargin = UiKit.sdp(context, 5f)
                    bottomMargin = UiKit.sdp(context, 2f)
                })

                // 每行 4 个，横屏下刚好
                heroes.sortedBy { it.name }.chunked(4).forEach { chunk ->
                    val row = UiKit.row(context)
                    row.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 28f)).apply {
                        bottomMargin = UiKit.sdp(context, 3f)
                    }
                    chunk.forEach { h ->
                        val on = Advisor.isOnBoard(h.name)
                        val b = UiKit.button(context, "${h.name}·${h.quality}", active = on)
                        b.maxLines = 1
                        b.setOnClickListener {
                            Advisor.toggleHero(h.name)
                            showTab(Tab.COMPS)
                        }
                        row.addView(b, UiKit.weight(1f).apply {
                            marginEnd = UiKit.sdp(context, 3f)
                        })
                    }
                    // 补齐空位，保证最后一行按钮宽度和上面一致
                    repeat(4 - chunk.size) {
                        row.addView(View(context), UiKit.weight(1f).apply {
                            marginEnd = UiKit.sdp(context, 3f)
                        })
                    }
                    list.addView(row)
                }
            }

        scroll.addView(list)
        root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildBlockList(): View {
        val root = UiKit.col(context)

        val head = UiKit.row(context)
        head.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 28f))
        val countLbl = UiKit.label(
            context, "已屏蔽 ${Prefs.blockedComps().size} / ${GameData.comps.size}",
            10f, UiKit.TEXT_DIM
        ).apply { maxLines = 1 }
        head.addView(countLbl, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        head.addView(
            UiKit.button(context, "全部恢复") {
                Prefs.clearBlockedComps()
                Logger.i("Comps", "已清空阵容屏蔽名单")
                showTab(Tab.COMPS)
            },
            LinearLayout.LayoutParams(UiKit.sdp(context, 62f), LayoutParams.MATCH_PARENT)
        )
        root.addView(head)

        root.addView(
            UiKit.label(
                context,
                "点一行切换屏蔽。被屏蔽的阵容不会再出现在推荐里。",
                10f, UiKit.TEXT_DIM
            ).apply { maxLines = 2 },
            UiKit.wrapRow().apply { bottomMargin = UiKit.sdp(context, 4f) }
        )

        val scroll = ScrollView(context)
        val list = UiKit.col(context)

        GameData.comps.sortedBy { it.name }.forEach { comp ->
            val blocked = Prefs.isCompBlocked(comp.id)
            val radius = Prefs.cornerDp * resources.displayMetrics.density * 0.4f

            val rowView = UiKit.col(context).apply {
                background = UiKit.rounded(
                    if (blocked) 0xFF3A2328.toInt() else UiKit.BG_ELEV, radius
                )
                val p = UiKit.sdp(context, 5f)
                setPadding(p, p, p, p)
                isClickable = true
            }

            val title = UiKit.label(
                context,
                (if (blocked) "🚫 " else "") + comp.name,
                11f,
                if (blocked) UiKit.TEXT_DIM else UiKit.TEXT_MAIN
            ).apply { maxLines = 1 }
            rowView.addView(title)

            val sub = buildString {
                if (comp.tag.isNotEmpty()) append("[${comp.tag}] ")
                if (comp.camps.isNotEmpty()) append(comp.camps.joinToString("/")).append(' ')
                if (comp.core.isNotEmpty()) append("核心: ${comp.core.joinToString("、")}")
            }
            rowView.addView(
                UiKit.label(context, sub, 9.5f, UiKit.TEXT_DIM).apply { maxLines = 2 }
            )

            rowView.setOnClickListener {
                val nowBlocked = Prefs.toggleCompBlocked(comp.id)
                Logger.i("Comps", "${comp.name} ${if (nowBlocked) "已屏蔽" else "已恢复"}")
                showTab(Tab.COMPS)
            }

            list.addView(rowView, UiKit.wrapRow().apply {
                bottomMargin = UiKit.sdp(context, 3f)
            })
        }

        scroll.addView(list)
        root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------------- 日志页 ----------------

    private fun buildLog(): View {
        val root = UiKit.col(context)

        val bar = UiKit.row(context)
        bar.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 30f))
        bar.addView(
            UiKit.button(context, "复制全部日志") {
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("wxq-log", Logger.exportText())
                    )
                }.onSuccess {
                    android.widget.Toast.makeText(
                        context, "日志已复制，可直接粘贴反馈", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Logger.e("Log", "复制到剪贴板失败", it)
                }
            },
            UiKit.weight(1.4f).apply { marginEnd = UiKit.sdp(context, 4f) }
        )
        bar.addView(
            UiKit.button(context, "清空") {
                Logger.clear(); refreshLog()
            },
            UiKit.weight(0.7f)
        )
        root.addView(bar)

        logScroll = ScrollView(context)
        logText = TextView(context).apply {
            setTextColor(UiKit.TEXT_DIM)
            textSize = UiKit.ssp(9.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val p = UiKit.sdp(context, 5f)
            setPadding(p, p, p, p)
        }
        logScroll!!.addView(logText)
        logScroll!!.background = UiKit.rounded(
            0xFF0E1116.toInt(), Prefs.cornerDp * resources.displayMetrics.density * 0.5f
        )
        root.addView(logScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = UiKit.sdp(context, 5f)
        })

        refreshLog()
        return root
    }

    private fun refreshLog() {
        val tv = logText ?: return
        val sb = SpannableStringBuilder()
        Logger.snapshot().forEach { e ->
            val line = Logger.format(e) + "\n"
            val start = sb.length
            sb.append(line)
            val color = when (e.level) {
                Logger.Level.E -> UiKit.DANGER
                Logger.Level.W -> UiKit.WARN
                Logger.Level.I -> UiKit.TEXT_MAIN
                Logger.Level.D -> UiKit.TEXT_DIM
            }
            sb.setSpan(
                ForegroundColorSpan(color), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tv.text = sb
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        updateLogBadge()
    }

    // ---------------- 设置页 ----------------

    private fun buildSettings(): View {
        val scroll = ScrollView(context)
        val root = UiKit.col(context)

        slider(root, "整体缩放", Prefs.uiScale, 0.60f, 1.60f) {
            Prefs.uiScale = it; host.onSettingsChanged()
        }
        slider(root, "面板宽度比例", Prefs.widthRatio, 0.18f, 0.70f) {
            Prefs.widthRatio = it; host.onSettingsChanged()
        }
        slider(root, "面板高度比例", Prefs.heightRatio, 0.25f, 0.95f) {
            Prefs.heightRatio = it; host.onSettingsChanged()
        }
        slider(root, "字体比例", Prefs.fontScale, 0.65f, 1.50f) {
            Prefs.fontScale = it; host.onSettingsChanged()
        }

        root.addView(UiKit.divider(context))

        slider(root, "透明度", Prefs.opacity, 0.25f, 1.00f) {
            Prefs.opacity = it; alpha = it
        }
        slider(root, "圆角大小", Prefs.cornerDp, 0f, 36f, isDp = true) {
            Prefs.cornerDp = it; host.onSettingsChanged()
        }
        slider(
            root, "实时建议间隔(毫秒)",
            Prefs.liveIntervalMs.toFloat(), 300f, 3000f, isDp = true
        ) {
            Prefs.liveIntervalMs = it.toInt()
        }

        root.addView(UiKit.divider(context))

        val btnRow = UiKit.row(context)
        btnRow.layoutParams = UiKit.wrapRow(UiKit.sdp(context, 32f))
        btnRow.addView(
            UiKit.button(context, "恢复默认") {
                Prefs.resetAll()
                host.onSettingsChanged()
                Logger.i("Settings", "已恢复默认设置")
            },
            UiKit.weight(1f).apply { marginEnd = UiKit.sdp(context, 4f) }
        )
        val exit = UiKit.button(context, "退出助手") { host.onExitApp() }
        exit.setTextColor(UiKit.DANGER)
        btnRow.addView(exit, UiKit.weight(1f))
        root.addView(btnRow)

        val note = UiKit.label(
            context,
            "面板右下角有个蓝色圆点，直接拖它就能改大小，不用来这里拉滑块。\n" +
                "标题栏的 ✕ 只收起面板，助手仍在后台；要完全关掉用上面的「退出助手」。",
            10f, UiKit.TEXT_DIM
        )
        note.maxLines = 5
        root.addView(note, UiKit.wrapRow().apply { topMargin = UiKit.sdp(context, 6f) })

        scroll.addView(root)
        return scroll
    }

    private fun slider(
        parent: LinearLayout,
        name: String,
        value: Float,
        min: Float,
        max: Float,
        isDp: Boolean = false,
        onChange: (Float) -> Unit
    ) {
        val row = UiKit.col(context)
        row.layoutParams = UiKit.wrapRow().apply { topMargin = UiKit.sdp(context, 3f) }

        val head = UiKit.row(context)
        val lbl = UiKit.label(context, name, 11f, UiKit.TEXT_MAIN).apply { maxLines = 1 }
        head.addView(lbl, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val valueLbl = UiKit.label(
            context,
            if (isDp) value.toInt().toString() else "%.2f".format(value),
            11f, UiKit.ACCENT
        ).apply { maxLines = 1; gravity = Gravity.END }
        head.addView(valueLbl, LinearLayout.LayoutParams(
            UiKit.sdp(context, 44f), LayoutParams.WRAP_CONTENT
        ))
        row.addView(head)

        val bar = SeekBar(context).apply {
            this.max = 1000
            progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + (max - min) * (p / 1000f)
                    valueLbl.text = if (isDp) v.toInt().toString() else "%.2f".format(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(bar, UiKit.wrapRow(UiKit.sdp(context, 26f)))
        parent.addView(row)
    }
}
