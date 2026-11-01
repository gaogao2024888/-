package com.wxq.assist

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 截屏管线。
 * 一次授权，长期复用；每次识别抓当前帧，按区域裁剪。
 *
 * 注意：Android 14+ 必须先把前台服务(type=mediaProjection)跑起来，
 * 再调 getMediaProjection，否则直接抛异常。
 */
class ScreenCapture(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var capW = 0
    private var capH = 0
    private var dpi = 0

    private val handler = Handler(Looper.getMainLooper())

    val isReady: Boolean get() = projection != null && imageReader != null

    /** 权限失效时回调，让服务更新状态并提示用户重新授权 */
    var onProjectionLost: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.w("Capture", "截屏权限已失效（投屏被停止或令牌作废）")
            release()
            onProjectionLost?.invoke()
        }
    }

    /** 用授权结果初始化。resultData 来自 ProjectionRequestActivity */
    fun start(resultCode: Int, resultData: Intent): Boolean {
        return try {
            release()
            Logger.d("Capture", "开始建立截屏通道 resultCode=$resultCode")

            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(resultCode, resultData)
            if (p == null) {
                Logger.e("Capture", "getMediaProjection 返回 null，授权可能已失效或已被用过一次")
                return false
            }
            Logger.d("Capture", "getMediaProjection 成功")

            // Android 14 起必须先注册 Callback 再创建 VirtualDisplay
            p.registerCallback(projectionCallback, handler)
            projection = p

            val metrics = currentMetrics()
            capW = metrics.widthPixels
            capH = metrics.heightPixels
            dpi = metrics.densityDpi

            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            virtualDisplay = p.createVirtualDisplay(
                "WxqCapture",
                capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            Logger.i("Capture", "截屏通道已建立 ${capW}x${capH} @${dpi}dpi")
            true
        } catch (t: Throwable) {
            Logger.e("Capture", "建立截屏通道失败", t)
            release()
            false
        }
    }

    /**
     * 屏幕旋转后分辨率变了。
     *
     * 注意：Android 14+ 禁止在同一个 MediaProjection 上第二次调用 createVirtualDisplay，
     * 否则抛 SecurityException 并直接作废整个授权（"Don't take multiple captures by
     * invoking MediaProjection#createVirtualDisplay multiple times on the same instance"）。
     * 所以这里必须用 resize + setSurface 原地改，绝不能重建。
     */
    fun onConfigChanged() {
        val vd = virtualDisplay ?: return
        if (projection == null) return

        val metrics = currentMetrics()
        if (metrics.widthPixels == capW && metrics.heightPixels == capH) return

        Logger.i(
            "Capture",
            "分辨率变化 ${capW}x$capH -> ${metrics.widthPixels}x${metrics.heightPixels}，原地调整"
        )
        try {
            val oldReader = imageReader

            capW = metrics.widthPixels
            capH = metrics.heightPixels
            dpi = metrics.densityDpi

            val newReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            // 顺序要紧：先改尺寸，再换 Surface，最后才关旧的
            vd.resize(capW, capH, dpi)
            vd.surface = newReader.surface
            imageReader = newReader
            runCatching { oldReader?.close() }

            Logger.i("Capture", "通道已适配新分辨率 ${capW}x$capH")
        } catch (t: Throwable) {
            Logger.e("Capture", "调整截屏通道失败", t)
        }
    }

    @SuppressLint("WrongConstant")
    private fun currentMetrics(): DisplayMetrics {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /**
     * 抓当前帧并按比例区域裁剪。
     * @param region 0~1 的相对矩形；null = 整屏
     */
    fun grab(region: RectF? = null): Bitmap? {
        val reader = imageReader ?: run {
            Logger.w("Capture", "尚未授权截屏，忽略本次抓取")
            return null
        }

        var image = reader.acquireLatestImage()
        // 刚建好通道时第一帧可能还没来，小退几次
        var tries = 0
        while (image == null && tries < 3) {
            Thread.sleep(35)
            image = reader.acquireLatestImage()
            tries++
        }
        if (image == null) {
            Logger.w("Capture", "未取到画面帧（游戏可能处于安全界面或尚未渲染）")
            return null
        }

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
            if (full != cropped) full.recycle()

            if (region == null) return cropped

            val l = (region.left * cropped.width).toInt().coerceIn(0, cropped.width - 1)
            val t = (region.top * cropped.height).toInt().coerceIn(0, cropped.height - 1)
            val r = (region.right * cropped.width).toInt().coerceIn(l + 1, cropped.width)
            val b = (region.bottom * cropped.height).toInt().coerceIn(t + 1, cropped.height)

            val sub = Bitmap.createBitmap(cropped, l, t, r - l, b - t)
            if (sub != cropped) cropped.recycle()
            sub
        } catch (t: Throwable) {
            Logger.e("Capture", "解析画面帧失败", t)
            null
        } finally {
            runCatching { image.close() }
        }
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
    }
}
