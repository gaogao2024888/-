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

    private var ballParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

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

                if (data != null) {
                    // Android 14+ 要求先把前台服务切到 mediaProjection 类型
                    startAsForeground(mediaProjection = true)
                    projectionReady = capture?.start(code, data) == true
                    panelView?.updateStatus()
                    if (projectionReady) {
                        Logger.i("Service", "识别功能已就绪")
                    } else {
                        Logger.e("Service", "截屏通道建立失败，详见上方日志")
                    }
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

    private fun startAsForeground(mediaProjection: Boolean) {
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

        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (t: Throwable) {
            Logger.e("Service", "startForeground 失败(type=$type)", t)
            runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notif, 0) }
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
        attachDrag(pv, p, dragThresholdDp = 10f, onTap = null, onMoved = { x, y ->
            Prefs.panelX = x.toFloat() / screenW()
            Prefs.panelY = y.toFloat() / screenH()
        })

        runCatching { wm.addView(pv, p) }
            .onFailure { Logger.e("Overlay", "添加面板失败", it) }
    }

    override fun onCollapse() {
        // 只收面板，服务、悬浮球、把手都留着
        removeView(panelView)
        panelView = null
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

    override fun onToggleCalibrate(on: Boolean) {
        calibrating = on
        if (!on) {
            removeView(guideView)
            guideView = null
            Logger.i("Region", "已退出区域校准")
            return
        }

        removeView(guideView)
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
        Logger.i("Region", "进入区域校准：拖动方框或四角调整，松手自动保存")
    }

    override fun isCalibrating(): Boolean = calibrating

    // ---------------- 识别 ----------------

    override fun onRequestProjection() {
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun isProjectionReady(): Boolean = projectionReady

    override fun onRecognize(region: String) {
        doRecognize(region, silent = false)
    }

    private fun doRecognize(region: String, silent: Boolean) {
        if (!projectionReady) {
            if (!silent) {
                Logger.w("Recognize", "尚未授权截屏，请先在「识别」页点授权")
                panelView?.setAdvice("⚠ 还没授权截屏。\n请到「识别」页点「① 授权截屏权限」。")
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
