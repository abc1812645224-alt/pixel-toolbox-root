/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 桌面（NexusLauncher）hook（Android 17 反编译确认的 Hook 点）：
 *   - 隐藏搜索框：hook QsbWidgetFactory.createView，把创建出的搜索框 View 置 GONE。
 *   - 双击锁屏：hook Workspace.onTouchEvent，检测双击空白区后经 root 锁屏。
 */
package com.example.pixeltoolbox.xposed

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Hook 目标：com.google.android.apps.nexuslauncher
 */
class LauncherHooks(
    private val x: XposedModule,
    private val hideSearch: Boolean,
    private val dt2s: Boolean,
    private val hideGesture: Boolean,
    private val classLoader: ClassLoader
) {
    private val logTag = "LauncherHooks"

    // 双击检测状态
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    fun apply() {
        if (hideSearch) hookSearchBar()
        if (dt2s) hookDoubleTap()
        if (hideGesture) hookHideGestureLine()
    }

    /**
     * 隐藏桌面手势小白条（stashed handle pill）。
     *
     * 根因：桌面底部白色横条由 nexuslauncher 进程自绘，是悬浮在 Dock 上方的
     * StashedHandleView（布局 R.id.stashed_handle），不属于 SystemUI，故 SystemUI 侧
     * hook 对其无效。
     *
     * 修复记录（圈定即搜冲突）：旧版把 StashedHandleView 置 GONE 并锁死 setVisibility，
     * 会破坏 stashed handle 视图的状态链路（该 View 同时承载长按输入区域/触控状态机），
     * 导致圈定即搜（Circle to Search）无法触发。现改为 PixelXpert 同款方案：仅在
     * TaskbarActivityContext 构造时把 stashedHandleViewController.mStashedHandleWidth 归零，
     * 以宽度 0 做纯视觉隐藏，View 保持 VISIBLE，触控/状态链路完整，圈定即搜可正常触发。
     */
    private fun hookHideGestureLine() {
        hookTaskbarActivityContextCtor()
        hookTaskbarActivityContextInit()
    }

    /**
     * hook TaskbarActivityContext.init after：mStashedHandleWidth 归零。
     *
     * 修复记录：仅 hook 构造器不生效——反编译确认 init()（L659/662）会用资源尺寸
     * (nav_handle_width) 重置 mStashedHandleWidth，构造器 hook 在 init 之前执行、字段
     * 当时为 0（日志 "0 -> 0"），随后被 init 恢复，小白条重新可见。init 是构造器之后
     * 必然调用的初始化入口，此处 after 归零才能真正覆盖初始赋值，保留 View VISIBLE
     * 与触控/状态链路，圈定即搜可正常触发。
     */
    private fun hookTaskbarActivityContextInit() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.taskbar.TaskbarActivityContext")
            val fControllers = cls.getDeclaredField("mControllers").apply { isAccessible = true }
            val initMethods = cls.declaredMethods.filter { it.name == "init" }
            if (initMethods.isEmpty()) {
                x.log(6, logTag, "TaskbarActivityContext.init not found")
                return
            }
            for (m in initMethods) {
                x.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val controllers = fControllers.get(chain.getThisObject())
                            val shvc = controllers.javaClass
                                .getDeclaredField("stashedHandleViewController").apply { isAccessible = true }
                                .get(controllers)
                            val fWidth = shvc.javaClass.getDeclaredField("mStashedHandleWidth").apply { isAccessible = true }
                            val before = fWidth.getInt(shvc)
                            fWidth.setInt(shvc, 0)
                            x.log(3, logTag, "init: stashed handle width: $before -> 0")
                        } catch (t: Throwable) {
                            x.log(6, logTag, "hide gesture line init error: ${t.message}", t)
                        }
                        result
                    }
            }
            x.log(3, logTag, "TaskbarActivityContext.init hooked (${initMethods.size})")
        } catch (t: Throwable) {
            x.log(6, logTag, "hide gesture line init hook failed: ${t.message}", t)
        }
    }

    /** hook TaskbarActivityContext 构造器 after：mStashedHandleWidth 归零（纯视觉隐藏，不置 GONE）。 */
    private fun hookTaskbarActivityContextCtor() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.taskbar.TaskbarActivityContext")
            val ctors = cls.declaredConstructors
            if (ctors.isEmpty()) {
                x.log(6, logTag, "TaskbarActivityContext ctor not found")
                return
            }
            val fControllers = cls.getDeclaredField("mControllers").apply { isAccessible = true }
            for (ctor in ctors) {
                x.hook(ctor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            val controllers = fControllers.get(chain.getThisObject())
                            val shvc = controllers.javaClass
                                .getDeclaredField("stashedHandleViewController").apply { isAccessible = true }
                                .get(controllers)
                            // 仅宽度归零（PixelXpert 同款）：View 保持 VISIBLE，保留触控/状态链路，圈定即搜可用
                            val fWidth = shvc.javaClass.getDeclaredField("mStashedHandleWidth").apply { isAccessible = true }
                            val before = fWidth.getInt(shvc)
                            fWidth.setInt(shvc, 0)
                            x.log(3, logTag, "stashed handle width: $before -> 0")
                        } catch (t: Throwable) {
                            x.log(6, logTag, "hide gesture line error: ${t.message}", t)
                        }
                    }
            }
            x.log(3, logTag, "TaskbarActivityContext ctor hooked (${ctors.size})")
        } catch (t: Throwable) {
            x.log(6, logTag, "hide gesture line ctor hook failed: ${t.message}", t)
        }
    }

    /** 隐藏桌面搜索框：hook QsbWidgetFactory.createView 置 GONE，并锁定 setVisibility 防止被覆盖。 */
    private fun hookSearchBar() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.qsb.QsbWidgetFactory")
            val methods = cls.declaredMethods.filter { it.name == "createView" }
            if (methods.isEmpty()) {
                x.log(6, logTag, "QsbWidgetFactory.createView not found")
                return
            }
            for (m in methods) {
                x.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val v = result as? View
                            if (v != null && v.visibility != View.GONE) {
                                x.log(3, logTag, "hide search bar: ${v.javaClass.name}")
                                v.visibility = View.GONE
                            }
                        } catch (t: Throwable) {
                            x.log(6, logTag, "createView hook error: ${t.message}", t)
                        }
                        result
                    }
            }
            hookSetVisibility()
            hookGrid7()
            x.log(3, logTag, "QsbWidgetFactory.createView hooked (${methods.size} overloads)")
        } catch (t: Throwable) {
            x.log(6, logTag, "search bar hook failed: ${t.message}", t)
        }
    }

    /** 锁定 OseWidgetView 为 GONE：hook View.setVisibility，对 OseWidgetView 实例强制改 GONE。 */
    private fun hookSetVisibility() {
        try {
            val targetCls = classLoader.loadClass("com.android.launcher3.qsb.OseWidgetView")
            val viewCls = classLoader.loadClass("android.view.View")
            val m = viewCls.getMethod("setVisibility", Int::class.javaPrimitiveType)
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    var forced = false
                    try {
                        val thisView = chain.getThisObject()
                        val vis = chain.getArg(0) as? Int ?: View.VISIBLE
                        if (thisView != null && targetCls.isInstance(thisView) && vis != View.GONE) {
                            x.log(3, logTag, "force GONE on OseWidgetView (requested $vis)")
                            forced = true
                        }
                    } catch (t: Throwable) {
                        x.log(6, logTag, "setVisibility hook error: ${t.message}", t)
                    }
                    if (forced) chain.proceed(arrayOf<Any?>(View.GONE)) else chain.proceed()
                }
            x.log(3, logTag, "View.setVisibility hooked (filter OseWidgetView)")
        } catch (t: Throwable) {
            x.log(6, logTag, "setVisibility hook failed: ${t.message}", t)
        }
    }

    /**
     * 7×4 网格改造：搜索框 GONE 后，numRows 6→7，并释放 qsb 高度，让多出的一行图标占满原搜索框空白，
     * 保持图标尺寸不变（cellHeight 不变），替代旧的 hookSinkIcons 下沉 hack。
     */
    private fun hookGrid7() {
        hookNumRows()
        hookReleaseQsbHeight()
    }

    /** numRows 6→7：hook InvariantDeviceProfile.initGrid，proceed 后强制 numRows=7（列数 numColumns 不变）。 */
    private fun hookNumRows() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.InvariantDeviceProfile")
            val field = cls.getDeclaredField("numRows").apply { isAccessible = true }
            val m = cls.getDeclaredMethod("initGrid", String::class.java)
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    chain.proceed()
                    try {
                        val idp = chain.getThisObject()
                        val before = field.getInt(idp)
                        field.setInt(idp, 7)
                        val after = field.getInt(idp)
                        x.log(3, logTag, "numRows: before=$before after=$after")
                    } catch (t: Throwable) {
                        x.log(6, logTag, "set numRows error: ${t.message}")
                    }
                }
            x.log(3, logTag, "InvariantDeviceProfile.initGrid hooked (numRows=7)")
        } catch (t: Throwable) {
            x.log(6, logTag, "numRows hook failed: ${t.message}", t)
        }
    }

    /**
     * 释放 qsb 高度：hook HotseatProfileInitialValues 构造器，把 qsbVisualHeight / qsbSpace 归零，
     * barSizePx 与 workspace 底 padding 随之减少约 218px，腾给多出的一行图标。
     * 参数下标依据反编译构造器：(z10, i10..i24, z11)，qsbVisualHeight=i18→args[9]，qsbSpace=i23→args[14]。
     */
    private fun hookReleaseQsbHeight() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.deviceprofile.HotseatProfileInitialValues")
            val ctor = cls.declaredConstructors.first()
            x.hook(ctor)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    val args = chain.getArgs()
                    val before = "qsbVisualHeight=${args.getOrNull(9)} qsbSpace=${args.getOrNull(14)}"
                    val arr = args.toTypedArray()
                    try {
                        arr[9] = 0   // qsbVisualHeight
                        arr[14] = 0  // qsbSpace
                        x.log(3, logTag, "qsb release: $before -> 0/0")
                    } catch (t: Throwable) {
                        x.log(6, logTag, "patch qsb args error: ${t.message}")
                    }
                    chain.proceed(arr)
                }
            x.log(3, logTag, "HotseatProfileInitialValues ctor hooked (qsb height released)")
        } catch (t: Throwable) {
            x.log(6, logTag, "release qsb height hook failed: ${t.message}", t)
        }
    }

    /** 双击锁屏：hook Workspace.onTouchEvent，两次快速 DOWN 判定双击。 */
    private fun hookDoubleTap() {
        try {
            val cls = classLoader.loadClass("com.android.launcher3.Workspace")
            val method = cls.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            x.hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    try {
                        val ev = chain.getArg(0) as MotionEvent
                        if (ev.action == MotionEvent.ACTION_DOWN) {
                            val now = SystemClock.uptimeMillis()
                            val dx = ev.rawX - lastTapX
                            val dy = ev.rawY - lastTapY
                            val isDouble =
                                (now - lastTapTime) < 300L && dx * dx + dy * dy < 100f * 100f
                            lastTapTime = now
                            lastTapX = ev.rawX
                            lastTapY = ev.rawY
                            if (isDouble) {
                                x.log(3, logTag, "double tap detected")
                                lockScreen()
                            }
                        }
                    } catch (t: Throwable) {
                        x.log(6, logTag, "onTouchEvent hook error: ${t.message}", t)
                    }
                    chain.proceed()
                }
            x.log(3, logTag, "Workspace.onTouchEvent hooked")
        } catch (t: Throwable) {
            x.log(6, logTag, "double tap hook failed: ${t.message}", t)
        }
    }

    /** 锁屏：延迟执行 root input keyevent 223（KEYCODE_SLEEP），避开双击第二次 UP 触发 tap-to-wake。 */
    private fun lockScreen() {
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223"))
                    p.waitFor()
                } catch (t: Throwable) {
                    x.log(6, logTag, "su keyevent failed: ${t.message}", t)
                }
            }, 400)
        } catch (t: Throwable) {
            x.log(6, logTag, "lockScreen failed: ${t.message}", t)
        }
    }
}
