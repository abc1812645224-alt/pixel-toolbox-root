/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * SystemUI hook：隐藏底部手势导航小白条（gesture navigation hint bar）。
 *
 * 修复记录：旧版仅 hook NavigationHandle.setVertical 归零宽高，但该回调只在方向
 * 变化时触发，注册时 handle 已初始化，故不生效；addView 兜底受 handleHidden 标志位
 * 限制，一旦置 true 就不再处理被 SystemUI 重新显示（setVisibility(VISIBLE)）的 handle。
 *
 * 本版改为四路组合，覆盖 handle 全生命周期：
 *   1. 构造器 hook：NavigationHandle 实例化即置 GONE（初始化即隐藏）；
 *   2. View.setVisibility 锁死：过滤 NavigationHandle 实例，非 GONE 一律改 GONE（防恢复）；
 *   3. setVertical 归零：宽高归零兜底（AOSPMods 同款）；
 *   4. addView 遍历兜底：无状态遍历（带深度限制），按类名隐藏 HomeHandle/GestureHandle 等。
 */
package com.example.pixeltoolbox.xposed

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class SystemUiHooks(
    private val x: XposedModule,
    private val hideGesture: Boolean,
    private val classLoader: ClassLoader
) {
    private val logTag = "SystemUiHooks"

    fun apply() {
        if (!hideGesture) return

        hookNavigationHandleCtor()
        hookSetVisibilityLock()
        hookSetVertical()
        hookAddViewFallback()
    }

    /** 1. 构造器 hook：NavigationHandle 实例化即隐藏。 */
    private fun hookNavigationHandleCtor() {
        try {
            val cls = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")
            val ctors = cls.declaredConstructors
            if (ctors.isEmpty()) {
                x.log(6, logTag, "NavigationHandle ctor not found")
                return
            }
            for (ctor in ctors) {
                x.hook(ctor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            val v = chain.getThisObject() as? View
                            if (v != null && v.visibility != View.GONE) {
                                x.log(3, logTag, "hide on ctor: ${v.javaClass.name}")
                                v.visibility = View.GONE
                                v.post { v.visibility = View.GONE }
                            }
                        } catch (t: Throwable) {
                            x.log(6, logTag, "ctor hook error: ${t.message}", t)
                        }
                    }
            }
            x.log(3, logTag, "NavigationHandle ctor hooked (${ctors.size})")
        } catch (t: Throwable) {
            x.log(6, logTag, "NavigationHandle ctor hook failed: ${t.message}", t)
        }
    }

    /** 2. View.setVisibility 锁死：NavigationHandle 实例非 GONE 一律强制 GONE。 */
    private fun hookSetVisibilityLock() {
        try {
            val targetCls = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")
            val m = View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType)
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    var forced = false
                    try {
                        val thisView = chain.getThisObject()
                        val vis = chain.getArg(0) as? Int ?: View.VISIBLE
                        if (thisView != null && targetCls.isInstance(thisView) && vis != View.GONE) {
                            forced = true
                        }
                    } catch (t: Throwable) {
                        x.log(6, logTag, "setVisibility lock error: ${t.message}", t)
                    }
                    if (forced) chain.proceed(arrayOf<Any?>(View.GONE)) else chain.proceed()
                }
            x.log(3, logTag, "View.setVisibility hooked (lock NavigationHandle GONE)")
        } catch (t: Throwable) {
            x.log(6, logTag, "setVisibility lock hook failed: ${t.message}", t)
        }
    }

    /** 3. setVertical 归零：宽高归零兜底（AOSPMods 同款）。 */
    private fun hookSetVertical() {
        try {
            val cls = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")
            val methods = cls.declaredMethods.filter { it.name == "setVertical" }
            if (methods.isNotEmpty()) {
                for (m in methods) {
                    x.hook(m)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val v = chain.getThisObject() as? View
                                v?.layoutParams?.let { lp ->
                                    lp.width = 0
                                    lp.height = 0
                                }
                            } catch (t: Throwable) {
                                x.log(6, logTag, "setVertical hook error: ${t.message}", t)
                            }
                            result
                        }
                }
                x.log(3, logTag, "NavigationHandle.setVertical hooked (${methods.size} overloads)")
            } else {
                x.log(6, logTag, "NavigationHandle.setVertical not found")
            }
        } catch (t: Throwable) {
            x.log(6, logTag, "NavigationHandle hook failed: ${t.message}")
        }
    }

    /** 4. addView 兜底：无状态遍历视图树，按类名隐藏手势小白条。 */
    private fun hookAddViewFallback() {
        try {
            val m = ViewGroup::class.java.getDeclaredMethod(
                "addView",
                View::class.java,
                Int::class.java,
                ViewGroup.LayoutParams::class.java
            )
            x.hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val parent = chain.getThisObject() as? ViewGroup
                        if (parent != null && parent.context?.packageName == "com.android.systemui") {
                            hideGestureHandleIn(parent)
                        }
                    } catch (t: Throwable) {
                        x.log(6, logTag, "addView hook error: ${t.message}", t)
                    }
                    result
                }
            x.log(3, logTag, "ViewGroup.addView hooked (fallback)")
        } catch (t: Throwable) {
            x.log(6, logTag, "addView hook failed: ${t.message}", t)
        }
    }

    private fun hideGestureHandleIn(group: ViewGroup) {
        fun walk(v: View, depth: Int) {
            if (depth > 12) return
            if (isGestureHandle(v)) {
                if (v.visibility != View.GONE) {
                    x.log(3, logTag, "hiding gesture handle: ${v.javaClass.name}")
                    v.visibility = View.GONE
                }
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(group, 0)
    }

    private fun isGestureHandle(v: View): Boolean {
        var cls: Class<*>? = v.javaClass
        while (cls != null) {
            val name = cls.name
            if (name.contains("HomeHandle") || name.contains("GestureHandle") || name.contains("NavigationHandle")) return true
            cls = cls.superclass
        }
        return false
    }
}
