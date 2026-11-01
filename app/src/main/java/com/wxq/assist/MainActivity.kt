package com.wxq.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 启动页：只做三件事 —— 申请悬浮窗权限、申请通知权限、启动服务。
 * 之后所有操作都在悬浮窗里完成，不用再回到这个页面。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Logger.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1116.toInt())
            val p = UiKit.dp(this@MainActivity, 20f)
            setPadding(p, p + UiKit.dp(this@MainActivity, 24f), p, p)
        }

        root.addView(TextView(this).apply {
            text = "万象棋助手"
            setTextColor(UiKit.ACCENT)
            textSize = 24f
            gravity = Gravity.START
        })

        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} · 骨架版"
            setTextColor(UiKit.TEXT_DIM)
            textSize = 12f
            setPadding(0, UiKit.dp(this@MainActivity, 4f), 0, UiKit.dp(this@MainActivity, 18f))
        })

        statusView = TextView(this).apply {
            setTextColor(UiKit.TEXT_MAIN)
            textSize = 13f
            setLineSpacing(0f, 1.3f)
        }
        val sc = ScrollView(this)
        sc.addView(statusView)
        root.addView(sc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val btn1 = bigButton("① 授予悬浮窗权限") { requestOverlay() }
        val btn2 = bigButton("② 启动助手悬浮窗") { startOverlay() }
        val btn3 = bigButton("停止助手") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show()
        }
        root.addView(btn1); root.addView(btn2); root.addView(btn3)

        setContentView(root)
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun bigButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.BG_BTN, UiKit.dp(this@MainActivity, 10f).toFloat())
            val p = UiKit.dp(this@MainActivity, 14f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 10f) }
        }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.append(if (hasOverlay()) "✅ 悬浮窗权限：已授予\n" else "❌ 悬浮窗权限：未授予（必须）\n")
        sb.append("\n使用步骤：\n")
        sb.append("1. 授予悬浮窗权限\n")
        sb.append("2. 启动助手悬浮窗\n")
        sb.append("3. 进入游戏，点悬浮球或右侧把手展开面板\n")
        sb.append("4. 在「识别」页授权截屏，并做一次区域校准\n")
        sb.append("\n说明：\n")
        sb.append("· 面板标题栏的 ✕ 只收起面板，助手继续在后台；\n")
        sb.append("  要完全退出请用面板「设置」页的「退出助手」或通知栏的「退出」。\n")
        sb.append("· 所有尺寸、字号、透明度、圆角都能在「设置」页实时调。\n")
        sb.append("· 出问题到「日志」页点「复制全部日志」，粘贴反馈即可。\n")
        statusView.text = sb.toString()
    }

    private fun requestOverlay() {
        if (hasOverlay()) {
            Toast.makeText(this, "已经有权限了", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    private fun startOverlay() {
        if (!hasOverlay()) {
            Toast.makeText(this, "请先完成第 ① 步", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        )
        Toast.makeText(this, "助手已启动，可以切到游戏了", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }
}
