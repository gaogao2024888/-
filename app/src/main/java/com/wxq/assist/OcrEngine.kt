package com.wxq.assist

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 中文 OCR。用 ML Kit 的中文识别模型，模型打包进 APK，离线可用，不联网。
 */
object OcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    data class Block(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Result(
        val fullText: String,
        val blocks: List<Block>,
        val costMs: Long,
        val error: String? = null
    )

    /**
     * 同步识别。调用方已经在后台线程，这里用闩锁等结果。
     * @param timeoutMs 超时保护，避免实时模式下卡死循环
     */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 4000): Result {
        val t0 = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var text = ""
        var blocks: List<Block> = emptyList()
        var err: String? = null

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { r ->
                    text = r.text
                    blocks = r.textBlocks.mapNotNull { b ->
                        val bb = b.boundingBox ?: return@mapNotNull null
                        Block(b.text, bb.left, bb.top, bb.right, bb.bottom)
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    err = e.message ?: e.javaClass.simpleName
                    latch.countDown()
                }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                err = "识别超时(${timeoutMs}ms)"
            }
        } catch (t: Throwable) {
            err = "${t.javaClass.simpleName}: ${t.message}"
            Logger.e("OCR", "识别过程异常", t)
        }

        val cost = System.currentTimeMillis() - t0
        err?.let { Logger.w("OCR", "识别失败: $it") }
        return Result(text, blocks, cost, err)
    }

    /**
     * 预处理：游戏卡面是深底浅字，放大 + 提对比度能明显提升中文识别率。
     * 区域太小时放大，太大时不动（ML Kit 自己会缩）。
     */
    fun preprocess(src: Bitmap, minWidth: Int = 720): Bitmap {
        val scale = if (src.width < minWidth) minWidth.toFloat() / src.width else 1f
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else src

        // 灰度 + 线性拉伸对比度
        val w = scaled.width
        val h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)

        var min = 255
        var max = 0
        val gray = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            gray[i] = g
            if (g < min) min = g
            if (g > max) max = g
        }
        val range = (max - min).coerceAtLeast(1)
        for (i in px.indices) {
            val g = ((gray[i] - min) * 255 / range).coerceIn(0, 255)
            px[i] = Color.rgb(g, g, g)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        return out
    }
}
