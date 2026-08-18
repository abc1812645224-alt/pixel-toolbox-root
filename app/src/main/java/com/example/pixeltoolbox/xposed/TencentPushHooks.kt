/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker

/**
 * 微信/QQ 统一推送环境 Hook。
 * 在微信进程中伪装厂商为 Xiaomi (MIUI)，强制微信开启 MiPushSDK 注册与后台唤醒通道。
 */
internal class TencentPushHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader
) {

    fun apply() {
        try {
            // Hook Build.MANUFACTURER 和 Build.BRAND
            val buildClass = classLoader.loadClass("android.os.Build")
            val manufacturerField = buildClass.getDeclaredField("MANUFACTURER")
            manufacturerField.isAccessible = true
            manufacturerField.set(null, "Xiaomi")

            val brandField = buildClass.getDeclaredField("BRAND")
            brandField.isAccessible = true
            brandField.set(null, "Xiaomi")

            module.log(3, "TencentPushHooks", "Successfully spoofed Build.MANUFACTURER and BRAND to Xiaomi for WeChat")
        } catch (t: Throwable) {
            module.log(6, "TencentPushHooks", "Failed to apply TencentPushHooks: ${t.message}", t)
        }
    }
}
