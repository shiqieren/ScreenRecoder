package com.hht.oemscreenrecoder.utils

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.Window
import com.hht.android.sdk.touch.HHTTouchManager

/**
 * <pre>
 *     author : VeiZhang
 *     blog   : http://tiimor.cn
 *     time   : 2025/4/29/029
 *     desc   :
 * </pre>
 */
object HHTTouchHelper {

    private val TAG = HHTTouchHelper::class.java.simpleName

    /**
     * HHTTouchService.java 得知scale
     */

    // 32767*32767
    private const val PANEL_WIDTH = 0x7fff
    private const val PANEL_HEIGHT = 0x7fff

    private const val WIDTH_2K = 1920
    private const val HEIGHT_2K = 1080

    private const val WIDTH_4K = 3840
    private const val HEIGHT_4K = 2160

    private const val scaleX = 1.0f * PANEL_WIDTH / WIDTH_4K // 17.067
    private const val scaleY = 1.0f * PANEL_HEIGHT / HEIGHT_4K // 30.34

    /**
     * 平台兼容性标志位
     * null: 未检测
     * true: 支持 HHTTouchManager
     * false: 不支持（失败后不再尝试）
     */
    @Volatile
    private var isHHTTouchSupported: Boolean? = null

    /**
     * 检查并缓存平台是否支持 HHTTouchManager
     */
    private fun checkHHTTouchSupport(): Boolean {
        // 已检测过，直接返回缓存结果
        isHHTTouchSupported?.let { return it }

        return try {
            // 尝试获取实例，检查是否可用
            HHTTouchManager.getInstance()
            isHHTTouchSupported = true
            Log.i(TAG, "HHTTouchManager is supported on this platform")
            true
        } catch (e: SecurityException) {
            isHHTTouchSupported = false
            Log.w(TAG, "HHTTouchManager not supported: SecurityException - ${e.message}")
            false
        } catch (e: UnsatisfiedLinkError) {
            isHHTTouchSupported = false
            Log.w(TAG, "HHTTouchManager not supported: UnsatisfiedLinkError - ${e.message}")
            false
        } catch (e: Exception) {
            isHHTTouchSupported = false
            Log.w(TAG, "HHTTouchManager not supported: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    fun enableOpsTouchRect(opsTouchRect: OpsTouchRect?) {
        if (opsTouchRect == null) {
            Log.e(TAG, "enableOpsTouchRect: rect is null")
            return
        }

        // 检查平台是否支持，不支持则直接返回
        if (!checkHHTTouchSupport()) {
            Log.d(TAG, "enableOpsTouchRect: HHTTouchManager not supported, skip")
            return
        }

        try {
            val ret = HHTTouchManager.getInstance().setTheLimitedRect(
                (opsTouchRect.rect.left * scaleX).toInt(),
                (opsTouchRect.rect.top * scaleY).toInt(),
                (opsTouchRect.rect.width() * scaleX).toInt(),
                (opsTouchRect.rect.height() * scaleY).toInt(),
                "${opsTouchRect.pkg}_${opsTouchRect.rectTitle}"
            )

            // 这里是窗体变化时才进行设置，滑动时，可以使用另外一个接口disableOps，这样就不需要频繁设置窗体大小限制了
            // HHTTouchManager.getInstance().setOpsTouch(false)
            Log.w(TAG, "enableOpsTouchRect: result = $ret")
        } catch (e: SecurityException) {
            // Binder 接口调用失败，标记为不支持
            isHHTTouchSupported = false
            Log.e(TAG, "enableOpsTouchRect failed: SecurityException - ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "enableOpsTouchRect failed: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }

    fun disableOpsTouchRect(opsTouchRect: OpsTouchRect?) {
        if (opsTouchRect == null) {
            Log.e(TAG, "disableOpsTouchRect: rect = null")
            return
        }

        // 检查平台是否支持，不支持则直接返回
        if (!checkHHTTouchSupport()) {
            Log.d(TAG, "disableOpsTouchRect: HHTTouchManager not supported, skip")
            return
        }

        try {
            val ret = HHTTouchManager.getInstance().recoverTheLimitedRect(
                (opsTouchRect.rect.left * scaleX).toInt(),
                (opsTouchRect.rect.top * scaleY).toInt(),
                (opsTouchRect.rect.width() * scaleX).toInt(),
                (opsTouchRect.rect.height() * scaleY).toInt(),
                "${opsTouchRect.pkg}_${opsTouchRect.rectTitle}"
            )

            Log.w(TAG, "disableOpsTouchRect: result = $ret")
        } catch (e: SecurityException) {
            // Binder 接口调用失败，标记为不支持
            isHHTTouchSupported = false
            Log.e(TAG, "disableOpsTouchRect failed: SecurityException - ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "disableOpsTouchRect failed: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }

    fun disableOpsTouchRect(window: Window?, title: String) {
        if (window == null) {
            Log.e(TAG, "disableOpsTouchRect: rect = null")
            return
        }

        val rect = createOpsTouchRect(window, title)
        disableOpsTouchRect(rect)
    }

    /**
     * 设置 OPS 触摸开关（带平台兼容性检查）
     * @param enabled true: 启用触摸透传, false: 禁用触摸透传
     */
    fun setOpsTouch(enabled: Boolean) {
        // 检查平台是否支持，不支持则直接返回
        if (!checkHHTTouchSupport()) {
            Log.v(TAG, "setOpsTouch($enabled): HHTTouchManager not supported, skip")
            return
        }

        try {
            HHTTouchManager.getInstance().setOpsTouch(enabled)
            Log.d(TAG, "setOpsTouch($enabled): success")
        } catch (e: SecurityException) {
            // Binder 接口调用失败，标记为不支持
            isHHTTouchSupported = false
            Log.e(TAG, "setOpsTouch($enabled) failed: SecurityException - ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "setOpsTouch($enabled) failed: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }

    fun createOpsTouchRect(view: View? = null, title: String = "MainWindow"): OpsTouchRect? {
        if (view == null) {
            Log.e(TAG, "createOpsTouchRect: failed")
            return null
        }

        val width = view.measuredWidth
        val height = view.measuredHeight

        val l = IntArray(2)
        view.getLocationOnScreen(l)

        val rect = Rect(l[0]-20, l[1]-20, l[0] + width+20, l[1] + height+20)
        // 全屏
//        val rect = Rect(0, 0, -1, -1)

        return OpsTouchRect(view.context.packageName, rect, title)
    }

    fun createOpsTouchRect(window: Window?, title: String): OpsTouchRect? {
        if (window == null) {
            Log.e(TAG, "createOpsTouchRect: failed")
            return null
        }

        return createOpsTouchRect(window.decorView, title)
    }
}