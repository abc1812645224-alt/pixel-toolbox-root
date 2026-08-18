/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 验证码自动填写（自研）：在 com.android.phone 进程 hook InboundSmsHandler.dispatchIntent，
 * 短信到达时从 Intent 的 pdus 字段解码正文并提取验证码，再通过显式广播发给工具箱 App，
 * 由 App 侧写入系统剪贴板并弹出悬浮窗提示。
 *
 * 原理参考社区 XposedSmsCode（GPL-3.0，tianma8023/magisk317）：其拦截思路是 hook
 * dispatchIntent 这道短信接收的最后关卡、从 SMS_DELIVER Intent 的 pdus 解码正文。
 * 本模块看懂其原理后重写，未搬运其代码。
 *
 * 宽容降级：dispatchIntent 重载在不同 Android 版本间漂移，采用「反射枚举首个参数为 Intent
 * 的重载 + 每路独立 try-catch」策略，hook 不到就跳过不崩溃（不阻断短信正常接收）。
 */
package com.example.pixeltoolbox.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import io.github.libxposed.api.XposedModule

class SmsCodeHooks(
    private val x: XposedModule,
    private val classLoader: ClassLoader
) {
    private val logTag = "SmsCodeHooks"

    /** 验证码关键词（按出现顺序优先匹配）。 */
    private val codeKeywords = listOf(
        "验证码", "校验码", "动态码", "激活码", "安全码", "验证编号", "验证 code"
    )

    fun apply() {
        hookDispatchIntent()
    }

    private fun hookDispatchIntent() {
        try {
            val cls = classLoader.loadClass("com.android.internal.telephony.InboundSmsHandler")
            val m = cls.declaredMethods.firstOrNull {
                it.name == "dispatchIntent" &&
                    it.parameterTypes.size >= 1 &&
                    it.parameterTypes[0] == Intent::class.java
            }
            if (m == null) {
                x.log(6, logTag, "dispatchIntent(Intent...) not found, skip")
                return
            }
            x.hook(m).intercept { chain ->
                val intent = chain.getArg(0) as? Intent
                if (intent != null) handleSmsIntent(intent)
                chain.proceed()
            }
            x.log(3, logTag, "dispatchIntent hooked: ${m.parameterTypes.joinToString { it.simpleName ?: it.name }}")
        } catch (t: Throwable) {
            x.log(6, logTag, "dispatchIntent hook failed: ${t.message}", t)
        }
    }

    private fun handleSmsIntent(intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_DELIVER") return
        try {
            val (body, address) = parseSms(intent)
            if (body.isBlank()) return
            val code = extractCode(body) ?: return
            broadcastCode(address, code)
            x.log(3, logTag, "code extracted: $code (from $address)")
        } catch (t: Throwable) {
            x.log(6, logTag, "handle sms failed: ${t.message}", t)
        }
    }

    /** 从 Intent 的 pdus 字段解码短信正文与发件人。 */
    private fun parseSms(intent: Intent): Pair<String, String> {
        val format = intent.getStringExtra("format")
        val pdusObj = intent.getSerializableExtra("pdus")
        val pdus: List<ByteArray> = when (pdusObj) {
            is Array<*> -> pdusObj.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        val body = StringBuilder()
        var address = ""
        for (pdu in pdus) {
            val msg = if (format != null) SmsMessage.createFromPdu(pdu, format)
            else SmsMessage.createFromPdu(pdu)
            body.append(msg.displayMessageBody)
            if (address.isEmpty()) address = msg.originatingAddress ?: ""
        }
        return body.toString() to address
    }

    /** 提取验证码：关键词附近 4-8 位数字优先，兜底全文 6 位/4 位数字。 */
    private fun extractCode(body: String): String? {
        if (body.isBlank()) return null
        for (kw in codeKeywords) {
            val idx = body.indexOf(kw, ignoreCase = true)
            if (idx >= 0) {
                val tail = body.substring(idx + kw.length)
                val m = Regex("""[^\d]{0,12}(\d{4,8})""").find(tail)
                if (m != null) return m.groupValues[1]
            }
        }
        val nums = Regex("""\d{4,8}""").findAll(body).map { it.value }.toList()
        return nums.firstOrNull { it.length == 6 }
            ?: nums.firstOrNull { it.length == 4 }
            ?: nums.firstOrNull()
    }

    /** 通过显式广播把验证码发给工具箱 App 的 SmsCodeReceiver。 */
    private fun broadcastCode(address: String, code: String) {
        try {
            val ctx = systemContext() ?: return
            val intent = Intent().apply {
                component = ComponentName(TOOLBOX_PKG, "$TOOLBOX_PKG.xposed.SmsCodeReceiver")
                putExtra("code", code)
                putExtra("address", address)
            }
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            x.log(6, logTag, "broadcast failed: ${t.message}", t)
        }
    }

    private fun systemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread", false, classLoader)
            val main = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(main) as? Context
        } catch (t: Throwable) {
            x.log(6, logTag, "system context failed: ${t.message}", t)
            null
        }
    }
}
