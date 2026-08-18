/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 通话录音权限放行（自研）：在 system_server 进程内 hook 权限检查链路，
 * 对工具箱自身 uid 放行两个受保护权限，使 App 侧 AudioRecord(VOICE_CALL)
 * 能绕过 audioserver 的 CAPTURE_AUDIO_OUTPUT 校验，无需 root 改 packages.xml。
 *
 * 原理：
 *   audioserver 是 native 进程，无法被 Xposed hook；但它查询权限是通过 binder 向
 *   system_server（可被 Xposed hook）发起，最终落到 ActivityManagerService /
 *   PermissionManagerService 的权限检查方法。在此拦截并直接返回 PERMISSION_GRANTED。
 *
 * 宽容降级：不同 Android 版本类名/方法签名可能漂移，采用多版本类名映射 + 每路 hook
 * 独立 try-catch，hook 不到就跳过不崩溃；uid 解析失败则整体跳过（绝不误放行）。
 */
package com.example.pixeltoolbox.xposed

import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class CallRecorderHooks(
    private val x: XposedModule,
    private val classLoader: ClassLoader
) {
    private val logTag = "CallRecorderHooks"

    /** 需要放行的受保护权限（仅对工具箱自身 uid 生效）。 */
    private val targetPermissions = setOf(
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CONTROL_INCALL_EXPERIENCE"
    )

    @Volatile
    private var toolboxUid: Int = Int.MIN_VALUE

    fun apply() {
        hookCheckComponentPermission()
        hookCheckUidPermission()
    }

    /**
     * 解析工具箱 App 的 uid（只解析一次，失败标记后不再重试）。
     * system_server 内通过 ActivityThread.systemMain().getSystemContext() 拿 PackageManager。
     */
    private fun resolveToolboxUid(): Int {
        if (toolboxUid != Int.MIN_VALUE) return toolboxUid
        try {
            val atClass = Class.forName("android.app.ActivityThread", false, classLoader)
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            val sysCtx = atClass.getMethod("getSystemContext").invoke(systemMain) as android.content.Context
            val uid = sysCtx.packageManager.getPackageUid(TOOLBOX_PKG, 0)
            toolboxUid = uid
            x.log(3, logTag, "resolved toolbox uid=$uid")
        } catch (t: Throwable) {
            x.log(6, logTag, "resolve toolbox uid failed: ${t.message}", t)
            toolboxUid = -1 // 失败标记，避免反复重试
        }
        return toolboxUid
    }

    /** 仅当权限命中目标集合、且 uid 精确等于工具箱 uid 时才放行。 */
    private fun isTarget(perm: String?, uid: Int): Boolean {
        if (perm == null || uid < 0) return false
        val myUid = resolveToolboxUid()
        return myUid > 0 && uid == myUid && perm in targetPermissions
    }

    /** 1. ActivityManagerService.checkComponentPermission(String,int,int,int,boolean) */
    private fun hookCheckComponentPermission() {
        try {
            val cls = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            val m = cls.getDeclaredMethod(
                "checkComponentPermission",
                String::class.java,
                Int::class.javaPrimitiveType, // pid
                Int::class.javaPrimitiveType, // uid
                Int::class.javaPrimitiveType, // owningUid
                Boolean::class.javaPrimitiveType // exported
            )
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    val perm = chain.getArg(0) as? String
                    val uid = (chain.getArg(2) as? Int) ?: -1
                    if (isTarget(perm, uid)) {
                        x.log(3, logTag, "grant $perm via checkComponentPermission uid=$uid")
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        chain.proceed()
                    }
                }
            x.log(3, logTag, "checkComponentPermission hooked")
        } catch (t: Throwable) {
            x.log(6, logTag, "checkComponentPermission hook failed: ${t.message}", t)
        }
    }

    /** 2. PermissionManagerService.checkUidPermission(String,int) 兜底 */
    private fun hookCheckUidPermission() {
        try {
            val cls = loadPmsClass()
            val m = cls.getDeclaredMethod(
                "checkUidPermission",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    val perm = chain.getArg(0) as? String
                    val uid = (chain.getArg(1) as? Int) ?: -1
                    if (isTarget(perm, uid)) {
                        x.log(3, logTag, "grant $perm via checkUidPermission uid=$uid")
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        chain.proceed()
                    }
                }
            x.log(3, logTag, "checkUidPermission hooked (${cls.name})")
        } catch (t: Throwable) {
            x.log(6, logTag, "checkUidPermission hook failed: ${t.message}", t)
        }
    }

    /** Android 11+ 类名迁移到 com.android.server.pm.permission 包，旧版本在 com.android.server.pm。 */
    private fun loadPmsClass(): Class<*> {
        return try {
            classLoader.loadClass("com.android.server.pm.permission.PermissionManagerService")
        } catch (t: Throwable) {
            classLoader.loadClass("com.android.server.pm.PermissionManagerService")
        }
    }
}
