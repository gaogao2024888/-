package com.wxq.assist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明中转页：只负责弹系统的"开始投屏"对话框，
 * 拿到结果立刻回传给 OverlayService 并关闭，不打断游戏。
 */
class ProjectionRequestActivity : Activity() {

    companion object {
        private const val REQ = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
        } catch (t: Throwable) {
            Logger.e("Projection", "无法弹出截屏授权对话框", t)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) {
            finish(); return
        }

        if (resultCode == RESULT_OK && data != null) {
            Logger.i("Projection", "用户已授予截屏权限")
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_PROJECTION_GRANTED
                    putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                }
            )
        } else {
            Logger.w("Projection", "用户拒绝了截屏权限，识别功能不可用")
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_PROJECTION_DENIED
                }
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
