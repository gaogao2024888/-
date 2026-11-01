package com.wxq.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.app.ServiceCompat
import kotlin.math.abs

class OverlayService : Service(), PanelView.Host {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PROJECTION_GRANTED = "projection_granted"
        const val ACTION_PROJECTION_DENIED = "projection_denied"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL = "wxq_assist_overlay"
        private const val NOTIF_ID = 42
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var ballView: View? = null
    private var handleView: View? = null
    private var panelView: PanelView? = null
    private var guideView: RegionGuideView? = null
    private var calibDoneView: View? = null
    private var gripView: View? = null

    private var ballParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var gripParams: WindowManager.LayoutParams? = null

    private var capture: ScreenCapture? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile
    private var busy = false
    private var liveOn = false
    private var calibrating = false
    private var projectionReady = false

    private val liveTask = object : Runnable {
        override fun run() {
            if (!liveOn) return
            runCatching { doRecognize(Prefs.REGION_BOARD, silent = true) }
                .onFailure { Logger.e("Live", "实时建议循环异常", it) }
            handler.postDelayed(this, Prefs.liveIntervalMs.toLong())
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Logger.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        capture = ScreenCapture(this)
        worker.execute { GameData.load(applicationContext) }
        startAsForeground(mediaProjection = false)
        showBall()
        showHandle()
        Logger.i("Service", "悬浮窗服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i("Service", "收到退出指令")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PROJECTION_GRANTED -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                Logger.i("Service", "收到授权回传: code=$code data=${if (data == null) "null" else "有"}")
                if (data != null) {
                    // Android 14+ 要求先把前台服务切到 mediaProjection 类型，
                    // 这一步没成功的话 getMediaProjection 必定抛 SecurityException
                    val fgOk = startAsForeground(mediaProjection = true)
                    Logger.i("Service", "切换前台服务类型为 mediaProjection: ${if (fgOk) "成功" else "失败"}")
                    projectionReady = capture?.start(code, data) == true
                    panelView?.updateStatus()
                    if (projectionReady) {
                        Logger.i("Service", "✅ 识别功能已就绪")
                    } else {
                        Logger.e("Service", "❌ 截屏通道建立失败，详见上方日志")
                    }
                } else {
                    Logger.e("Service", "授权回传里没有 data，无法建立通道")
                }
            }

            ACTION_PROJECTION_DENIED -> {
                projectionReady = false
                panelView?.updateStatus()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        liveOn = false
        handler.removeCallbacksAndMessages(null)
        runCatching { worker.shutdownNow() }
        capture?.release()
        removeView(ballView); ballView = null
        removeView(handleView); handleView = null
        removeView(panelView); panelView = null
        removeView(guideView); guideView = null
        removeView(calibDoneView); calibDoneView = null
        removeView(gripView); gripView = null
        Logger.i("Service", "悬浮窗服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Logger.i("Service", "屏幕方向变化 -> " +
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏")
        capture?.onConfigChanged()
        // 位置按比例存的，重新按新尺寸摆一遍
        handler.post {
            repositionAll()
            guideView?.reload()
        }
    }

    // ---------------- 前台通知 ----------------

    private fun startAsForeground(mediaProjection: Boolean): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "助手悬浮窗", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("万象棋助手运行中")
            .setContentText("点击通知栏的「退出」可完全关闭")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "退出", stopIntent
                ).build()
            )
            .build()

        val type = if (mediaProjection)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
            true
        } catch (t: Throwable) {
            Logger.e("Service", "startForeground 失败(type=$type)", t)
            // 退回无类型。注意：这种情况下 getMediaProjection 一定会被系统拒绝
            runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notif, 0) }
                .onFailure { Logger.e("Service", "降级 startForeground 也失败", it) }
            false
        }
    }

    // ---------------- 窗口基础 ----------------

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 刘海屏下也能铺到边
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun screenW(): Int {
        val m = resources.displayMetrics
        return m.widthPixels
    }

    private fun screenH(): Int {
        val m = resources.displayMetrics
        return m.heightPixels
    }

    private fun removeView(v: View?) {
        if (v == null) return
        runCatching { wm.removeView(v) }
    }

    // ---------------- 悬浮球 ----------------

    private fun showBall() {
        removeView(ballView)
        val size = UiKit.sdp(this, 44f)
        val radius = size / 2f

        val ball = FrameLayout(this).apply {
            background = UiKit.roundedStroke(
                0xE6151A23.toInt(), UiKit.ACCENT, UiKit.dp(this@OverlayService, 1.5f), radius
            )
            alpha = Prefs.opacity
            addView(
                UiKit.label(this@OverlayService, "棋", 16f, UiKit.ACCENT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val p = baseParams(size, size).apply {
            x = (Prefs.ballX * screenW()).toInt()
            y = (Prefs.ballY * screenH()).toInt()
        }
        ballParams = p
        ballView = ball

        attachDrag(ball, p, onTap = { togglePanel() }, onMoved = { x, y ->
            Prefs.ballX = x.toFloat() / screenW()
            Prefs.ballY = y.toFloat() / screenH()
        })

        runCatching { wm.addView(ball, p) }
            .onFailure { Logger.e("Overlay", "添加悬浮球失败（是否未授予悬浮窗权限？）", it) }
    }

    // ---------------- 侧边上下滑动把手 ----------------

    private fun showHandle() {
        removeView(handleView)
        val w = UiKit.sdp(this, 14f)
        val h = UiKit.sdp(this, 72f)
        val radius = w / 2f

        val handle = FrameLayout(this).apply {
            background = UiKit.rounded(0xCC2A3342.toInt(), radius)
            alpha = Prefs.opacity * 0.85f
            addView(
                UiKit.label(this@OverlayService, "⋮", 14f, UiKit.ACCENT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val p = baseParams(w, h).apply {
            x = if (Prefs.handleLeft) 0 else screenW() - w
            y = (Prefs.handleY * screenH()).toInt()
        }
        handleParams = p
        handleView = handle

        // 只允许上下滑动；横向滑过中线则换边吸附
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        var moved = false

        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startY = p.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downRawY
                    val dx = ev.rawX - downRawX
                    if (abs(dy) > 8 || abs(dx) > 8) moved = true
                    p.y = (startY + dy).toInt().coerceIn(0, screenH() - h)
                    if (abs(dx) > screenW() * 0.25f) {
                        val toLeft = dx < 0
                        if (toLeft != Prefs.handleLeft) {
                            Prefs.handleLeft = toLeft
                            p.x = if (toLeft) 0 else screenW() - w
                        }
                    }
                    runCatching { wm.updateViewLayout(handle, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.handleY = p.y.toFloat() / screenH()
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(handle, p) }
            .onFailure { Logger.e("Overlay", "添加侧边把手失败", it) }
    }

    // ---------------- 面板 ----------------

    private fun togglePanel() {
        if (panelView == null) showPanel() else onCollapse()
    }

    private fun showPanel() {
        removeView(panelView)
        val pv = PanelView(this, this)
        val w = (screenW() * Prefs.widthRatio).toInt().coerceAtLeast(UiKit.dp(this, 180f))
        val h = (screenH() * Prefs.heightRatio).toInt().coerceAtLeast(UiKit.dp(this, 160f))

        val p = baseParams(w, h).apply {
            x = (Prefs.panelX * screenW()).toInt().coerceIn(0, maxOf(0, screenW() - w))
            y = (Prefs.panelY * screenH()).toInt().coerceIn(0, maxOf(0, screenH() - h))
        }
        panelParams = p
        panelView = pv

        // 整个面板可拖；按在滑块/按钮上时交给子控件处理
        attachDrag(
            pv, p, dragThresholdDp = 10f, onTap = null,
            onMoving = { positionGrip() },
            onMoved = { x, y ->
                Prefs.panelX = x.toFloat() / screenW()
                Prefs.panelY = y.toFloat() / screenH()
            }
        )

        runCatching { wm.addView(pv, p) }
            .onFailure { Logger.e("Overlay", "添加面板失败", it) }

        showResizeGrip()
    }

    /**
     * 右下角的缩放手柄。直接拖就能改面板大小，不用再去设置里拉滑块。
     * 做成独立窗口是因为面板本身要响应内部控件的触摸，混在一起容易打架。
     */
    private fun showResizeGrip() {
        removeView(gripView)
        val size = UiKit.sdp(this, 30f)

        val grip = FrameLayout(this).apply {
            background = UiKit.rounded(0xCC3B82F6.toInt(), size / 2f)
            alpha = Prefs.opacity
            addView(
                UiKit.label(this@OverlayService, "⇲", 15f, 0xFFFFFFFF.toInt()).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val gp = baseParams(size, size)
        gripParams = gp
        gripView = grip
        positionGrip()

        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0

        grip.setOnTouchListener { _, ev ->
            val pp = panelParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startW = pp.width; startH = pp.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minW = UiKit.dp(this, 170f)
                    val minH = UiKit.dp(this, 150f)
                    pp.width = (startW + (ev.rawX - downX)).toInt()
                        .coerceIn(minW, screenW())
                    pp.height = (startH + (ev.rawY - downY)).toInt()
                        .coerceIn(minH, screenH())
                    panelView?.let { v -> runCatching { wm.updateViewLayout(v, pp) } }
                    positionGrip()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 存成比例，横竖屏切换后还能还原
                    Prefs.widthRatio = pp.width.toFloat() / screenW()
                    Prefs.heightRatio = pp.height.toFloat() / screenH()
                    Logger.d(
                        "Overlay",
                        "面板尺寸 ${pp.width}x${pp.height} " +
                            "(%.2f/%.2f)".format(Prefs.widthRatio, Prefs.heightRatio)
                    )
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(grip, gp) }
            .onFailure { Logger.e("Overlay", "添加缩放手柄失败", it) }
    }

    /** 把手柄钉在面板右下角 */
    private fun positionGrip() {
        val pp = panelParams ?: return
        val gp = gripParams ?: return
        val g = gripView ?: return
        gp.x = pp.x + pp.width - gp.width / 2
        gp.y = pp.y + pp.height - gp.height / 2
        runCatching { wm.updateViewLayout(g, gp) }
    }

    override fun onCollapse() {
        // 只收面板，服务、悬浮球、把手都留着
        removeView(panelView)
        panelView = null
        removeView(gripView); gripView = null
        if (calibrating) onToggleCalibrate(false)
        Logger.d("Overlay", "面板已收起（助手仍在后台运行）")
    }

    override fun onExitApp() {
        stopSelf()
    }

    override fun onSettingsChanged() {
        handler.post {
            val wasOpen = panelView != null
            removeView(panelView); panelView = null
            removeView(gripView); gripView = null
            showBall()
            showHandle()
            if (wasOpen) showPanel()
        }
    }

    private fun repositionAll() {
        showBall()
        showHandle()
        if (panelView != null) {
            removeView(panelView); panelView = null
            removeView(gripView); gripView = null
            showPanel()
        }
        if (calibrating) {
            removeView(guideView); guideView = null
            onToggleCalibrate(true)
        }
    }

    // ---------------- 拖动 ----------------

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        dragThresholdDp: Float = 6f,
        onTap: (() -> Unit)?,
        onMoving: (() -> Unit)? = null,
        onMoved: (Int, Int) -> Unit
    ) {
        val threshold = UiKit.dp(this, dragThresholdDp)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    false   // 不拦截，子控件照常能点
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > threshold || abs(dy) > threshold)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                            .coerceIn(-v.width / 3, screenW() - v.width * 2 / 3)
                        params.y = (startY + dy).toInt()
                            .coerceIn(0, maxOf(0, screenH() - v.height / 3))
                        runCatching { wm.updateViewLayout(v, params) }
                        onMoving?.invoke()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onMoved(params.x, params.y)
                        true
                    } else {
                        onTap?.invoke()
                        onTap != null
                    }
                }
                else -> false
            }
        }
    }

    // ---------------- 区域校准 ----------------

    private var panelWasOpenBeforeCalibrate = false

    override fun onToggleCalibrate(on: Boolean) {
        val wasCalibrating = calibrating
        calibrating = on
        if (!on) {
            removeView(guideView); guideView = null
            removeView(calibDoneView); calibDoneView = null
            Logger.i("Region", "已退出区域校准")
            // 校准前面板是开着的话，完成后自动弹回来
            if (panelWasOpenBeforeCalibrate && panelView == null) {
                panelWasOpenBeforeCalibrate = false
                showPanel()
            }
            return
        }

        // 校准层是全屏的，面板留在下面既点不到又碍眼，先收起。
        // 切换区域时会重入本函数，那时面板早已收起，不能把状态冲掉。
        if (!wasCalibrating) {
            panelWasOpenBeforeCalibrate = panelView != null
        }
        removeView(panelView); panelView = null
        removeView(gripView); gripView = null
        removeView(guideView)
        removeView(calibDoneView)

        val gv = RegionGuideView(this).apply {
            editable = true
            onRegionChanged = { name, _ -> Logger.d("Region", "$name 区域已更新") }
        }
        guideView = gv

        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        runCatching { wm.addView(gv, p) }
            .onFailure { Logger.e("Overlay", "添加校准层失败", it) }

        // 一次只编辑一个区域。三个框同时可拖的话，想调这个会抓到那个，
        // 结果就是三个框叠在一起，分区彻底失效。
        gv.focusRegion = calibTarget

        // 校准层是全屏的，会盖住面板，所以工具条必须单独开一个窗口放在它上面。
        val bar = UiKit.row(this).apply {
            val radius = UiKit.sdp(this@OverlayService, 10f).toFloat()
            background = UiKit.rounded(0xF2151A23.toInt(), radius)
            val pad = UiKit.sdp(this@OverlayService, 4f)
            setPadding(pad, pad, pad, pad)
        }

        fun tab(region: String, label: String, color: Int) {
            val on = calibTarget == region
            val b = UiKit.button(this, label, active = on) {
                calibTarget = region
                onToggleCalibrate(true)   // 重建工具条与焦点
            }.apply {
                maxLines = 1
                val r = UiKit.sdp(this@OverlayService, 8f).toFloat()
                background = UiKit.ripple(UiKit.rounded(if (on) color else UiKit.BG_BTN, r))
                setTextColor(if (on) 0xFF0E1116.toInt() else UiKit.TEXT_MAIN)
            }
            bar.addView(
                b,
                LinearLayout.LayoutParams(
                    UiKit.sdp(this@OverlayService, 58f),
                    UiKit.sdp(this@OverlayService, 34f)
                ).apply { marginEnd = UiKit.sdp(this@OverlayService, 4f) }
            )
        }

        tab(Prefs.REGION_BOARD, "阵容", 0xFFFFC94A.toInt())
        tab(Prefs.REGION_SHOP, "商店", 0xFF4ADE80.toInt())
        tab(Prefs.REGION_TALENT, "天赋", 0xFF60A5FA.toInt())

        val done = UiKit.button(this, "✓ 完成") { onToggleCalibrate(false) }.apply {
            maxLines = 1
            val r = UiKit.sdp(this@OverlayService, 8f).toFloat()
            background = UiKit.ripple(UiKit.rounded(0xFF3B82F6.toInt(), r))
            setTextColor(0xFFFFFFFF.toInt())
        }
        bar.addView(
            done,
            LinearLayout.LayoutParams(
                UiKit.sdp(this, 62f), UiKit.sdp(this, 34f)
            )
        )

        val barW = UiKit.sdp(this, 252f)
        val dp = baseParams(barW, UiKit.sdp(this, 44f)).apply {
            x = (screenW() - barW) / 2
            y = UiKit.sdp(this@OverlayService, 8f)
        }
        calibDoneView = bar
        runCatching { wm.addView(bar, dp) }
            .onFailure { Logger.e("Overlay", "添加校准工具条失败", it) }

        val cn = when (calibTarget) {
            Prefs.REGION_BOARD -> "阵容"
            Prefs.REGION_SHOP -> "商店"
            Prefs.REGION_TALENT -> "天赋"
            else -> calibTarget
        }
        Logger.i("Region", "校准中：当前编辑【$cn】区，其他区域已隐藏避免误拖")
    }

    /** 当前正在校准哪个区域 */
    private var calibTarget: String = Prefs.REGION_BOARD

    override fun isCalibrating(): Boolean = calibrating

    // ---------------- 识别 ----------------

    override fun onRequestProjection() {
        Logger.i("Service", "请求截屏授权，拉起中转页")
        runCatching {
            startActivity(
                Intent(this, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Logger.e("Service", "拉起授权页失败", it) }
    }

    override fun isProjectionReady(): Boolean = projectionReady

    override fun onRecognize(region: String) {
        doRecognize(region, silent = false)
    }

    private fun doRecognize(region: String, silent: Boolean) {
        if (!projectionReady) {
            if (!silent) {
                // 别让用户自己去找按钮，直接把系统授权框拉起来
                Logger.w("Recognize", "尚未授权截屏，自动拉起授权流程")
                panelView?.setAdvice("还没授权截屏，正在弹出系统授权框…\n\n选「整个屏幕」并确认，然后再点一次识别。")
                onRequestProjection()
            }
            return
        }

        val rect = Prefs.region(region)
        val name = when (region) {
            Prefs.REGION_BOARD -> "阵容区"
            Prefs.REGION_SHOP -> "商店区"
            Prefs.REGION_TALENT -> "天赋区"
            else -> region
        }

        if (busy) {
            Logger.d("Recognize", "上一次识别还没跑完，跳过本次")
            return
        }
        busy = true
        if (!silent) panelView?.setAdvice("正在识别$name…")

        worker.execute {
            try {
                val t0 = System.currentTimeMillis()
                val raw = runCatching { capture?.grab(rect) }
                    .onFailure { Logger.e("Recognize", "截取 $name 失败", it) }
                    .getOrNull()

                if (raw == null) {
                    if (!silent) panelView?.setAdvice("截图失败，详见「日志」页。")
                    return@execute
                }

                val grabMs = System.currentTimeMillis() - t0
                val prepped = runCatching { OcrEngine.preprocess(raw) }.getOrDefault(raw)
                val ocr = OcrEngine.recognize(prepped)

                if (prepped !== raw) prepped.recycle()
                raw.recycle()

                if (ocr.error != null) {
                    if (!silent) panelView?.setAdvice("OCR 失败：${ocr.error}\n详见「日志」页。")
                    return@execute
                }

                Logger.i(
                    "Recognize",
                    "$name 截图${grabMs}ms OCR${ocr.costMs}ms，原始文本: " +
                        ocr.fullText.replace("\n", " | ").take(300)
                )

                if (!GameData.loaded) {
                    panelView?.setAdvice("游戏数据未加载，详见「日志」页。")
                    return@execute
                }

                val advice = when (region) {
                    Prefs.REGION_BOARD -> Advisor.adviseBoard(ocr.fullText)
                    Prefs.REGION_SHOP -> Advisor.adviseShop(ocr.fullText)
                    Prefs.REGION_TALENT -> Advisor.adviseTalent(ocr.fullText)
                    else -> ocr.fullText
                }
                panelView?.setAdvice(advice)
            } catch (t: Throwable) {
                Logger.e("Recognize", "识别流程异常", t)
                if (!silent) panelView?.setAdvice("识别出错：${t.javaClass.simpleName}\n详见「日志」页。")
            } finally {
                busy = false
            }
        }
    }

    // ---------------- 实时建议 ----------------

    override fun onToggleLive(on: Boolean) {
        liveOn = on
        handler.removeCallbacks(liveTask)
        if (on) {
            Logger.i("Live", "实时建议已开启，间隔 ${Prefs.liveIntervalMs}ms")
            handler.post(liveTask)
        } else {
            Logger.i("Live", "实时建议已关闭")
        }
    }

    override fun isLiveOn(): Boolean = liveOn
}
