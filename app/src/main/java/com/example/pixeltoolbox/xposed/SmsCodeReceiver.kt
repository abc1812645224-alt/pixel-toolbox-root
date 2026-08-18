/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 验证码广播接收器：接收 SmsCodeHooks（com.android.phone 进程）发出的验证码广播，
 * 写入系统剪贴板并弹出悬浮窗提示。
 */
package com.example.pixeltoolbox.xposed

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

class SmsCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        if (!Regex("""^\d{4,8}$""").matches(code)) return

        // 仅接受来自 com.android.phone / android（system 级）进程的广播，防止第三方伪造。
        // getSendingUid() 是 @hide API，改用反射读取 mSendingUid 字段。
        val allowed = try {
            val f = BroadcastReceiver::class.java.getDeclaredField("mSendingUid")
            f.isAccessible = true
            val uid = f.getInt(this)
            context.packageManager.getPackagesForUid(uid)?.any {
                it == "com.android.phone" || it == "android"
            } ?: false
        } catch (_: Throwable) {
            false
        }
        if (!allowed) return

        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("verification_code", code))
        } catch (_: Throwable) {
        }

        SmsCodeOverlay.show(context, code)
    }
}
