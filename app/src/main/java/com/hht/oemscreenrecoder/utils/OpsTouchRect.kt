package com.hht.oemscreenrecoder.utils

import android.graphics.Rect

/**
 * <pre>
 * author : VeiZhang
 * blog   : http://tiimor.cn
 * time   : 2025/4/23
 * desc   :
</pre> *
 */
data class OpsTouchRect(
    val pkg: String = "",
    val rect: Rect = Rect(),
    val rectTitle: String = ""
)