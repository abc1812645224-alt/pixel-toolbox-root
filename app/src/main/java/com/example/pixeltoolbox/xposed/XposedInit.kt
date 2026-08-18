/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * libxposed 模块入口（运行于 Vector / LSPosed 框架，注入目标进程）。
 *
 * 兼容性约束（重要）：
 *   1. 框架：本模块基于 libxposed API 102，仅支持 LSPosed / Vector 框架；原版 Xposed、
 *      EdXposed、Dreamland 等走旧 de.robv.android.xposed 接口，无法加载本模块。
 *   2. 系统：三个 hook 点（搜索框/双击锁屏/小白条）的类名、字段、方法均来自
 *      Android 17 (API 36) Pixel 反编译，仅适配 Pixel 9 Pro / Android 17；换机型或
 *      系统版本会导致 hook 失败或静默不生效。
 *
 * 与系统页「LSPosed 原生桌面定制」卡片配套：
 *   开关写入 App 的 xposed_prefs，并由 App 侧用 root 把三个开关状态同步到
 *   /data/system/pixeltoolbox_xposed.xml（key=value 逐行，chcon system_file），
 *   本模块在注入的目标进程里直接文件读取该配置后分发到具体 hook。
 *
 * 开关读取：文件直读 /data/system/pixeltoolbox_xposed.xml（system_file，无 MLS
 * 类别隔离，system 进程可读；App 数据目录 app_data_file 带 MLS 类别跨进程读不了，
 * 故不走 getRemotePreferences / App prefs 直读）。
 *
 * 支持的进程：
 *   - com.google.android.apps.nexuslauncher : 隐藏搜索框 / 双击锁屏
 *   - com.android.systemui                   : 隐藏底部手势小白条
 */
package com.example.pixeltoolbox.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

/** 工具 App 包名（开关 prefs 所在） */
internal const val TOOLBOX_PKG = "com.example.pixeltoolbox"
internal const val KEY_DT2S = "dt2s"
internal const val KEY_HIDE_SEARCH = "hide_search_bar"
internal const val KEY_HIDE_GESTURE_LINE = "hide_gesture_line"
internal const val KEY_SMS_CODE = "sms_code"
internal const val KEY_FREE_FORM = "freeform"

/** 目标进程名 */
private const val PROC_NEXUS_LAUNCHER = "com.google.android.apps.nexuslauncher"
private const val PROC_SYSTEMUI = "com.android.systemui"
private const val PROC_SYSTEM_SERVER = "android"
private const val PROC_PHONE = "com.android.phone"

/** 供各 Hook 类记录日志（复用 XposedInterface 成员 log，level 用 android.util.Log 数值：3=DEBUG 6=ERROR）。 */

/** 模块内读取到的开关状态 */
internal data class Toggles(
    val dt2s: Boolean,
    val hideSearch: Boolean,
    val hideGestureLine: Boolean,
    val smsCode: Boolean,
    val freeForm: Boolean
) {
    /** 是否有任意开关开启（用于 onPackageLoaded 早退判定）。 */
    val anyEnabled: Boolean
        get() = dt2s || hideSearch || hideGestureLine || smsCode || freeForm

    companion object {
        val NONE = Toggles(false, false, false, false, false)
    }
}

/**
 * libxposed 模块入口。Vector 通过 META-INF/xposed/java_init.list 找到本类并实例化。
 * onModuleLoaded 在每个被注入（scope 命中）的进程里调用一次。
 */
class XposedInit : XposedModule() {

    private var toggles: Toggles = Toggles.NONE
    private val hookedPkgs = HashSet<String>()
    private var permHookApplied = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        val process = param.processName ?: return
        log(3, "XposedInit", "loaded in process=$process")

        toggles = readToggles()
        log(
            3, "XposedInit",
            "toggles: dt2s=${toggles.dt2s} hideSearch=${toggles.hideSearch} hideGestureLine=${toggles.hideGestureLine}"
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName

        if (!toggles.anyEnabled) return
        if (!hookedPkgs.add(pkg)) return
        try {
            when (pkg) {
                PROC_NEXUS_LAUNCHER -> LauncherHooks(this, toggles.hideSearch, toggles.dt2s, toggles.hideGestureLine, param.defaultClassLoader).apply()
                PROC_SYSTEMUI -> SystemUiHooks(this, toggles.hideGestureLine, param.defaultClassLoader).apply()
                PROC_PHONE -> SmsCodeHooks(this, param.defaultClassLoader).apply()
            }
        } catch (t: Throwable) {
            log(6, "XposedInit", "hook failed for $pkg: ${t.message}", t)
        }
    }

    /**
     * system_server 专用回调（libxposed API 102）：scope.list 里加特殊虚拟包名 `system`
     * 后才会触发。system_server 由 zygote 直接拉起、不走 onPackageLoaded，此前用
     * pkg=="android" 判断从未命中，导致 CallRecorderHooks/FreeformHooks 从未生效。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(3, "XposedInit", "system server starting")
        // 通话录音权限放行：system_server 进程，无条件启用（仅对工具箱自身 uid 放行两个受保护权限，无安全风险），
        // 独立于功能开关，避免其它开关全关时早退导致录音权限无法放行。
        if (!permHookApplied) {
            permHookApplied = true
            try {
                CallRecorderHooks(this, param.getClassLoader()).apply()
            } catch (t: Throwable) {
                log(6, "XposedInit", "CallRecorderHooks apply failed: ${t.message}", t)
            }
        }
        // 自由窗口：hook system_server 的 ATMS/WMS（独立于桌面/SystemUI 开关）
        if (toggles.freeForm) {
            try {
                FreeformHooks(this, toggles.freeForm, param.getClassLoader()).apply()
            } catch (t: Throwable) {
                log(6, "XposedInit", "FreeformHooks apply failed: ${t.message}", t)
            }
        }
    }

    /**
     * 读取开关：直接读 /data/system/pixeltoolbox_xposed.xml（key=value 逐行）。
     * App 侧写开关时用 root 把三个开关状态同步到这里（system_data_file，无 MLS 类别隔离，
     * system 进程可读；App 数据目录 app_data_file 带 MLS 类别，system 进程跨类别读会被 mlsconstrain 拦截）。
     */
    private fun readToggles(): Toggles {
        return try {
            val file = File("/data/system/pixeltoolbox_xposed.xml")
            if (!file.exists() || !file.canRead()) {
                log(6, "XposedInit", "prefs file missing or unreadable: ${file.absolutePath}")
                return Toggles.NONE
            }
            val lines = file.readLines()
            fun readBool(key: String): Boolean = lines.any { it.trim() == "$key=true" }
            val t = Toggles(
                dt2s = readBool(KEY_DT2S),
                hideSearch = readBool(KEY_HIDE_SEARCH),
                hideGestureLine = readBool(KEY_HIDE_GESTURE_LINE),
                smsCode = readBool(KEY_SMS_CODE),
                freeForm = readBool(KEY_FREE_FORM)
            )
            log(3, "XposedInit", "read toggles via file: dt2s=${t.dt2s} hideSearch=${t.hideSearch} hideGestureLine=${t.hideGestureLine}")
            t
        } catch (t: Throwable) {
            log(6, "XposedInit", "read file error: ${t.message}", t)
            Toggles.NONE
        }
    }
}
