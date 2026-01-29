package com.hht.oemscreenrecoder.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import com.hht.oemscreenrecoder.utils.HHTTouchHelper

open class FloatingLayout : ConstraintLayout {
    private var screenHeight: Int = 0
    private var screenWidth: Int = 0
    private var boundaryWidth:Int = 0
    private var boundaryHeight:Int = 0
    private var listener: OnFloatingLayoutListener? = null
    private val DETERMINE_DRAG_DISTANCE = 15

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var lastX: Int = 0
    private var lastY: Int = 0
    private var isDrag: Boolean = false
    private var isEverDrag: Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                isEverDrag = false
                isDrag = false
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = rawX
                lastY = rawY
                listener?.notifyLayoutTouchDown(rawX, rawY)
                // 触摸时立即禁用 OPS 触摸，防止透传
                HHTTouchHelper.setOpsTouch(false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (screenHeight > 0 && screenWidth > 0) {
                    isDrag = true
                }
                val dx = rawX - lastX
                val dy = rawY - lastY
                // 拖动过程中持续禁用 OPS 触摸
                HHTTouchHelper.setOpsTouch(false)
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
                if (distance < DETERMINE_DRAG_DISTANCE) {
                    isDrag = false
                } else {
                    isEverDrag = true
                    listener?.notifyPositionUpdate(rawX, rawY)
                    lastX = rawX
                    lastY = rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isNotDrag()) {
                    isEverDrag = false
                    isDrag = false
                    // 拖动结束后重新启用 OPS 触摸
                    HHTTouchHelper.setOpsTouch(true)
                    // 通知拖动结束，重新设置防透传区域
                    listener?.notifyLayoutTouchUp(rawX, rawY)
                }
            }
        }
        return !isNotDrag() || super.onTouchEvent(event)
    }

    fun setScreenSize(width: Int?, height: Int?) {
        screenHeight = height!!
        screenWidth = width!!
    }

    fun setBoundary(width: Int, height: Int){
        boundaryWidth = width
        boundaryHeight = height
    }

    private fun isNotDrag(): Boolean {
        return !isDrag && (x == 0f || x == (screenWidth - width).toFloat())
    }

    fun registerListener(listener: OnFloatingLayoutListener) {
        this.listener = listener
    }

    interface OnFloatingLayoutListener {
        fun notifyLayoutTouchDown(x: Int, y: Int)
        fun notifyPositionUpdate(x: Int, y: Int)
        fun notifyLayoutTouchUp(x: Int, y: Int)
    }
}