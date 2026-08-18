/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.services.push

import android.content.Context
import android.content.pm.PackageManager
import com.example.pixeltoolbox.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManagedPushApp(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean,
    val iconBitmap: android.graphics.Bitmap? = null
)

object UnifiedPushManager {

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"

    /**
     * 实时检测底层统一推送进程与服务是否真正处于运行中/已持久化开启
     */
    suspend fun isPushServiceRunning(): Boolean = withContext(Dispatchers.IO) {
        val xmlRes = RootUtils.executeCommand("cat /data/system/pixeltoolbox_mipush.xml 2>/dev/null")
        val isPersistentOn = xmlRes.isSuccess && xmlRes.getOrDefault("").contains("enabled=true")
        val psRes = RootUtils.executeCommand("ps -ef | grep $XMSF_PACKAGE | grep -v grep")
        val isProcessActive = psRes.isSuccess && psRes.getOrDefault("").lines().any { it.contains(XMSF_PACKAGE) }

        return@withContext isPersistentOn || isProcessActive
    }

    /**
     * 实时检测微信/QQ 厂商推送环境伪装是否已激活
     */
    suspend fun isTencentSpoofEnabled(): Boolean = withContext(Dispatchers.IO) {
        val result = RootUtils.executeCommand("cat /data/system/pixeltoolbox_tencent_mipush.xml 2>/dev/null")
        if (result.isSuccess) {
            return@withContext result.getOrDefault("").contains("tencent_mipush=true")
        }
        return@withContext false
    }

    /**
     * 实时查询设备上支持/已绑定统一推送的 App 列表
     */
    suspend fun getManagedApps(context: Context): List<ManagedPushApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val list = mutableListOf<ManagedPushApp>()

        val result = RootUtils.executeCommand("pm query-receivers -a com.xiaomi.mipush.RECEIVE_MESSAGE --brief")
        val lines = result.getOrDefault("").lines().map { it.trim() }.filter { it.contains("/") }

        val pkgs = lines.map { it.substringBefore("/") }.toMutableSet()

        // 若已开启微信/QQ 伪装，且设备安装了微信或 QQ，强行包含至接管列表
        val tencentSpoofed = isTencentSpoofEnabled()
        if (tencentSpoofed) {
            pkgs.add(WECHAT_PACKAGE)
            pkgs.add(QQ_PACKAGE)
        }

        for (pkg in pkgs) {
            if (pkg == context.packageName || pkg == XMSF_PACKAGE) continue
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val iconBitmap: android.graphics.Bitmap? = try {
                    val drawable = pm.getApplicationIcon(pkg)
                    val bmp = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, 72, 72)
                    drawable.draw(canvas)
                    bmp
                } catch (e: Exception) { null }

                list.add(ManagedPushApp(pkg, label, true, iconBitmap))
            } catch (e: Exception) {
                // 如果未安装则跳过
            }
        }

        list.sortBy { it.appName.lowercase() }
        return@withContext list
    }

    /**
     * 一键无感开启统一推送托管框架（包含挂载守护与状态双重持久化 + 核心功能组件自动补全双重保险）
     */
    suspend fun enablePushService(context: Context): Result<String> = withContext(Dispatchers.IO) {
        // 双重保险 1: 检测底层推送组件是否已安装，若未安装或丢失则自动从 assets 释放并 pm install 静默安装
        try {
            context.packageManager.getApplicationInfo(XMSF_PACKAGE, 0)
        } catch (e: Exception) {
            try {
                val apkFile = java.io.File(context.cacheDir, "xmsf.apk")
                context.assets.open("xmsf.apk").use { input ->
                    java.io.FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                RootUtils.executeCommand("pm install -r -g ${apkFile.absolutePath}")
                apkFile.delete()
            } catch (_: Exception) {}
        }

        // 双重保险 2: 解锁权限、挂载守护、加入电池白名单、启动推送守护服务
        val cmds = listOf(
            "pm enable $XMSF_PACKAGE 2>/dev/null",
            "cmd appops set $XMSF_PACKAGE RUN_IN_BACKGROUND allow 2>/dev/null",
            "cmd appops set $XMSF_PACKAGE WAKE_LOCK allow 2>/dev/null",
            "cmd appops set $XMSF_PACKAGE AUTO_START allow 2>/dev/null",
            "dumpsys deviceidle whitelist +$XMSF_PACKAGE 2>/dev/null",
            "am startservice -n $XMSF_PACKAGE/.push.service.XMPushService 2>/dev/null",
            "echo 'enabled=true' > /data/system/pixeltoolbox_mipush.xml",
            "chmod 644 /data/system/pixeltoolbox_mipush.xml",
            "chcon u:object_r:system_file:s0 /data/system/pixeltoolbox_mipush.xml"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("mipush_enabled", true).apply()

        return@withContext RootUtils.executeCommand(cmds)
    }

    /**
     * 一键无感关闭/移除统一推送托管框架
     */
    suspend fun disablePushService(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "pm disable $XMSF_PACKAGE 2>/dev/null",
            "am force-stop $XMSF_PACKAGE 2>/dev/null",
            "echo 'enabled=false' > /data/system/pixeltoolbox_mipush.xml",
            "chmod 644 /data/system/pixeltoolbox_mipush.xml"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("mipush_enabled", false).apply()

        return@withContext RootUtils.executeCommand(cmds)
    }

    /**
     * 一键开启 微信/QQ 厂商推送环境伪装与接收器激活
     */
    suspend fun enableTencentSpoof(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "resetprop ro.miui.ui.version.name V140 2>/dev/null",
            "resetprop ro.miui.ui.version.code 14 2>/dev/null",
            "resetprop ro.miui.has_cust_partition true 2>/dev/null",
            "setprop ro.miui.ui.version.name V140 2>/dev/null",
            "echo 'tencent_mipush=true' > /data/system/pixeltoolbox_tencent_mipush.xml",
            "chmod 644 /data/system/pixeltoolbox_tencent_mipush.xml",
            "chcon u:object_r:system_file:s0 /data/system/pixeltoolbox_tencent_mipush.xml"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("tencent_spoof_enabled", true).apply()

        return@withContext RootUtils.executeCommand(cmds)
    }

    /**
     * 一键关闭 微信/QQ 厂商推送环境伪装
     */
    suspend fun disableTencentSpoof(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "resetprop -n ro.miui.ui.version.name '' 2>/dev/null",
            "rm -f /data/system/pixeltoolbox_tencent_mipush.xml 2>/dev/null"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("tencent_spoof_enabled", false).apply()

        return@withContext RootUtils.executeCommand(cmds)
    }
}
