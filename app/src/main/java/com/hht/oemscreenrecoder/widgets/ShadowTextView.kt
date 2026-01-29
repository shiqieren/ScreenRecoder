package com.hht.oemscreenrecoder.widgets;

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

@SuppressLint("AppCompatCustomView")
class ShadowTextView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    AppCompatTextView(context!!, attrs, defStyleAttr) {
    private fun init() {
        this.isSingleLine = true
        this.ellipsize = TextUtils.TruncateAt.MARQUEE
        this.marqueeRepeatLimit = -1
    }

    override fun isFocused(): Boolean {
        return true
    }

/*
    override fun draw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        paint.setShadowLayer(density * AMBIENT_SHADOW_RADIUS, 0f, 0f, AMBIENT_SHADOW_COLOR)
        super.draw(canvas)
        paint.setShadowLayer(
            density * KEY_SHADOW_RADIUS, 0.0f, density * KEY_SHADOW_OFFSET, KEY_SHADOW_COLOR
        )
    }

    companion object {
        private const val AMBIENT_SHADOW_RADIUS = 2.5f
        private const val KEY_SHADOW_RADIUS = 1f
        private const val KEY_SHADOW_OFFSET = 0.5f
        private const val AMBIENT_SHADOW_COLOR = -0x1000000
        private const val KEY_SHADOW_COLOR = -0x1000000
    }
*/

    init {
        init()
    }
}