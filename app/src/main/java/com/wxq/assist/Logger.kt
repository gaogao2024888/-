package com.wxq.assist

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志系统。
 * - 内存环形缓冲供面板实时显示
 * - 同时落盘，闪退后重启也能看到上次的崩溃栈
 * - 支持一键复制全部(含设备信息头)，方便直接发回来排查
 */
object Logger {

    enum class Level { D, I, W, E }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val msg: String
    )

    private const val TAG = "WxqAssist"
    private const val MAX = 800

    private val buffer = ArrayDeque<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "runtime.log")
        // 超过 1MB 就轮转，避免无限增长
        logFile?.let { if (it.exists() && it.length() > 1_000_000) it.delete() }

        installCrashHandler()
        i("Logger", "日志系统启动 | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("CRASH", "线程 ${thread.name} 未捕获异常:\n${throwable.stackTraceToString()}")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String) = add(Level.E, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        add(Level.E, tag, "$msg\n${t.stackTraceToString()}")

    @Synchronized
    private fun add(level: Level, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(entry)
        while (buffer.size > MAX) buffer.removeFirst()

        when (level) {
            Level.D -> Log.d(TAG, "[$tag] $msg")
            Level.I -> Log.i(TAG, "[$tag] $msg")
            Level.W -> Log.w(TAG, "[$tag] $msg")
            Level.E -> Log.e(TAG, "[$tag] $msg")
        }

        try {
            logFile?.appendText(format(entry) + "\n")
        } catch (_: Throwable) {
        }

        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it() } }
    }

    @Synchronized
    fun snapshot(): List<Entry> = buffer.toList()

    @Synchronized
    fun errorCount(): Int = buffer.count { it.level == Level.E || it.level == Level.W }

    @Synchronized
    fun clear() {
        buffer.clear()
        runCatching { logFile?.writeText("") }
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it() } }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }

    fun format(e: Entry): String =
        "${fmt.format(Date(e.time))} ${e.level.name}/${e.tag}: ${e.msg}"

    /** 复制用的完整文本，带设备信息头 */
    @Synchronized
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("===== 万象棋助手 日志导出 =====\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("系统: Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        sb.append("构建: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("条目数: ${buffer.size}  异常/警告: ${errorCount()}\n")
        sb.append("================================\n\n")
        buffer.forEach { sb.append(format(it)).append('\n') }
        return sb.toString()
    }
}
