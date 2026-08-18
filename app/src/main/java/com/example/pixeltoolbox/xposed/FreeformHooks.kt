/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 全局强制小窗（自研，方案 A）：在 system_server 进程内，让所有 App 强制支持
 * 小窗（freeform windowing mode，可自由缩放），配合桌面「上滑多任务 → 点应用名 → 小窗」入口。
 *
 * 根因（Android 17 Pixel 反编译 services.jar 确认）：
 *   Pixel 系统无 android.software.freeform_window_management 特性，小窗能力由
 *   ActivityTaskManagerService（ATMS）两个 public 布尔字段控制：
 *     - mSupportsFreeformWindowManagement：由 WMS.updateFreeformWindowManagement()
 *       依据 hasSystemFeature + enable_freeform_support 写入（Pixel 恒 false）；
 *     - mForceResizableActivities：由 WMS.updateForceResizableTasks() 依据
 *       force_resizable_activities setting 写入（默认 false）。
 *   Task.isResizeable() 在 mForceResizableActivities=true 且 activityType==STANDARD 时
 *   返回 true，使任务可进小窗；Launcher 菜单「小窗」项点击走 FreeformSystemShortcut
 *   （setLaunchWindowingMode(5)）原生实现。
 *
 * 方案 A 实现（模块卸载即还原，不持久化任何系统 setting）：
 *   1. hook ATMS 构造器 after：system_server 启动即把两字段强制 true（启动兜底）；
 *   2. hook WMS.updateFreeformWindowManagement() after：防 settings 回写，强制
 *      mSupportsFreeformWindowManagement=true；
 *   3. hook WMS.updateForceResizableTasks() after：防 settings 回写，强制
 *      mForceResizableActivities=true。
 *   两 update* 方法只在 settings 变化时被 SettingsObserver.onChange 触发（不覆盖
 *   启动初值），故必须补构造器兜底，否则开关开启后首次不生效。
 *
 * 宽容降级：字段/方法均 public 且 A15/16/17 稳定，但仍逐 hook 独立 try-catch，
 * hook 不到跳过不崩溃。
 */
package com.example.pixeltoolbox.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class FreeformHooks(
    private val x: XposedModule,
    private val enable: Boolean,
    private val classLoader: ClassLoader
) {
    private val logTag = "FreeformHooks"

    private var supportsField: java.lang.reflect.Field? = null
    private var forceField: java.lang.reflect.Field? = null
    private var atmServiceField: java.lang.reflect.Field? = null

    fun apply() {
        if (!enable) return
        resolveFields()
        hookAtmsConstructor()
        hookUpdateFreeformWindowManagement()
        hookUpdateForceResizableTasks()
    }

    /** 预解析 ATMS 两字段 + WMS.mAtmService，失败则对应 hook 静默降级。 */
    private fun resolveFields() {
        try {
            val atms = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
            supportsField = atms.getDeclaredField("mSupportsFreeformWindowManagement").apply { isAccessible = true }
            forceField = atms.getDeclaredField("mForceResizableActivities").apply { isAccessible = true }
            x.log(3, logTag, "resolved ATMS fields")
        } catch (t: Throwable) {
            x.log(6, logTag, "resolve ATMS fields failed: ${t.message}", t)
        }
        try {
            val wms = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            atmServiceField = wms.getDeclaredField("mAtmService").apply { isAccessible = true }
        } catch (t: Throwable) {
            x.log(6, logTag, "resolve mAtmService failed: ${t.message}", t)
        }
    }

    /** 对 ATMS 实例强制两字段 true；字段未解析到则静默跳过。 */
    private fun forceOnAtms(atms: Any?) {
        if (atms == null) return
        try {
            supportsField?.setBoolean(atms, true)
            forceField?.setBoolean(atms, true)
        } catch (t: Throwable) {
            x.log(6, logTag, "force fields failed: ${t.message}", t)
        }
    }

    /** 1. 启动兜底：ATMS 构造后强制两字段 true（system_server 启动即生效）。 */
    private fun hookAtmsConstructor() {
        try {
            val atms = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
            val ctors = atms.declaredConstructors
            if (ctors.isEmpty()) {
                x.log(6, logTag, "ATMS ctor not found")
                return
            }
            for (ctor in ctors) {
                x.hook(ctor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            forceOnAtms(chain.getThisObject())
                            x.log(3, logTag, "ATMS ctor: freeform forced true")
                        } catch (t: Throwable) {
                            x.log(6, logTag, "ATMS ctor hook error: ${t.message}", t)
                        }
                    }
            }
            x.log(3, logTag, "ActivityTaskManagerService ctor hooked (${ctors.size})")
        } catch (t: Throwable) {
            x.log(6, logTag, "ATMS ctor hook failed: ${t.message}", t)
        }
    }

    /** 2. 防回写：updateFreeformWindowManagement after 强制 mSupportsFreeformWindowManagement=true。 */
    private fun hookUpdateFreeformWindowManagement() {
        try {
            val wms = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val m = wms.getDeclaredMethod("updateFreeformWindowManagement")
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    chain.proceed()
                    try {
                        val atms = atmServiceField?.get(chain.getThisObject())
                        supportsField?.setBoolean(atms, true)
                        x.log(3, logTag, "updateFreeformWindowManagement: supports forced true")
                    } catch (t: Throwable) {
                        x.log(6, logTag, "updateFreeformWindowManagement force error: ${t.message}", t)
                    }
                }
            x.log(3, logTag, "updateFreeformWindowManagement hooked")
        } catch (t: Throwable) {
            x.log(6, logTag, "updateFreeformWindowManagement hook failed: ${t.message}", t)
        }
    }

    /** 3. 防回写：updateForceResizableTasks after 强制 mForceResizableActivities=true。 */
    private fun hookUpdateForceResizableTasks() {
        try {
            val wms = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val m = wms.getDeclaredMethod("updateForceResizableTasks")
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    chain.proceed()
                    try {
                        val atms = atmServiceField?.get(chain.getThisObject())
                        forceField?.setBoolean(atms, true)
                        x.log(3, logTag, "updateForceResizableTasks: force resizable forced true")
                    } catch (t: Throwable) {
                        x.log(6, logTag, "updateForceResizableTasks force error: ${t.message}", t)
                    }
                }
            x.log(3, logTag, "updateForceResizableTasks hooked")
        } catch (t: Throwable) {
            x.log(6, logTag, "updateForceResizableTasks hook failed: ${t.message}", t)
        }
    }
}
