/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 验证码悬浮窗：显示「验证码 XXX 已复制」，3 秒后自动消失，点击立即关闭。
 */
package com.example.pixeltoolbox.xposed

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object SmsCodeOverlay {

    private const val AUTO_DISMISS_MS = 3000L

    fun show(context: Context, code: String) {
        if (!Settings.canDrawOverlays(context)) return
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(context).apply {
                    text = "验证码 $code 已复制"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(0xCC1C1C1E.toInt())
                    }
                    setPadding(dp(22), dp(13), dp(22), dp(13))
                    setOnClickListener {
                        try { wm.removeView(this) } catch (_: Throwable) {}
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(90)
                }
                wm.addView(tv, params)
                Handler(Looper.getMainLooper()).postDelayed({
                    try { wm.removeView(tv) } catch (_: Throwable) {}
                }, AUTO_DISMISS_MS)
            } catch (_: Throwable) {
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * Resources.getSystem().displayMetrics.density).toInt()
}
