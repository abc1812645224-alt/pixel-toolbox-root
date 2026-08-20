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
import java.io.File

class SystemUiHooks(
    private val x: XposedModule,
    private val hideGesture: Boolean,
    private val lockChargePower: Boolean,
    private val classLoader: ClassLoader
) {
    private val logTag = "SystemUiHooks"

    fun apply() {
        if (hideGesture) {
            hookNavigationHandleCtor()
            hookSetVisibilityLock()
            hookSetVertical()
            hookAddViewFallback()
        }
        if (lockChargePower) {
            hookLockChargePower()
        }
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

    // ============================================================
    // 锁屏充电功率（PixelXpert KeyguardMods / BatteryDataProvider 同源方案）
    // 数据源：BatteryStatus Intent extras「max_charging_current」(µA)、
    //        「max_charging_voltage」(µV)、「temperature」(0.1℃)；功率 = 电流 × 电压。
    // 展示：hook KeyguardIndicationController(.Google).computePowerIndication，
    //       在锁屏充电提示文案后追加功率信息。目标类双候选（Google 变体 + AOSP）容错。
    // ============================================================

    /** 充电功率状态缓存（跨 hook 回调共享）。 */
    private object ChargePowerState {
        @Volatile var charging = false
        @Volatile var maxChargingCurrentA = 0f
        @Volatile var maxChargingVoltageV = 0f
        @Volatile var temperatureC = 0f
        // 实时电流 EMA 平滑（指数移动平均），抑制 sysfs 瞬时电流噪声导致的功率跳变
        @Volatile var smoothCurrentA = 0f
    }

    private fun hookLockChargePower() {
        // ① 电池状态缓存：BatteryControllerImpl.onReceive 更新充电标志位
        try {
            val batteryController = classLoader.loadClass("com.android.systemui.statusbar.policy.BatteryControllerImpl")
            batteryController.declaredMethods.filter { it.name == "onReceive" }.forEach { m ->
                x.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            // 优先从广播 Intent 直接解析（Android 17 字段名/时序不可靠），
                            // intent 取不到再回退字段反射
                            val intent = runCatching {
                                (chain.getArg(1) as? android.content.Intent)
                            }.getOrNull()
                            if (intent != null) {
                                val status = intent.getIntExtra(
                                    android.os.BatteryManager.EXTRA_STATUS,
                                    android.os.BatteryManager.BATTERY_STATUS_UNKNOWN
                                )
                                val plugged = intent.getBooleanExtra(android.os.BatteryManager.EXTRA_PLUGGED, false)
                                ChargePowerState.charging = plugged &&
                                    (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                        status == android.os.BatteryManager.BATTERY_STATUS_FULL)
                            } else {
                                val inst = chain.getThisObject()
                                if (inst != null) {
                                    ChargePowerState.charging =
                                        fieldBool(inst, "mPluggedIn") ||
                                            fieldBool(inst, "mCharging") ||
                                            fieldBool(inst, "mWirelessCharging")
                                }
                            }
                        } catch (t: Throwable) {
                            x.log(6, logTag, "battery onReceive err: ${t.message}", t)
                        }
                        r
                    }
            }
        } catch (t: Throwable) {
            x.log(6, logTag, "BatteryControllerImpl hook failed: ${t.message}", t)
        }

        // ② 功率数据源：BatteryStatus 构造器（首个参数为电池状态 Intent）解析电流/电压/温度
        try {
            val batteryStatus = classLoader.loadClass("com.android.settingslib.fuelgauge.BatteryStatus")
            batteryStatus.declaredConstructors.forEach { c ->
                x.hook(c)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        val inst = chain.proceed()
                        try {
                            val intent = chain.getArg(0) as? android.content.Intent
                            if (intent != null) {
                                ChargePowerState.maxChargingCurrentA = intent.getIntExtra("max_charging_current", 0) / 1_000_000f
                                ChargePowerState.maxChargingVoltageV = intent.getIntExtra("max_charging_voltage", 0) / 1_000_000f
                                ChargePowerState.temperatureC = intent.getIntExtra("temperature", 0) / 10f
                            }
                        } catch (t: Throwable) {
                            x.log(6, logTag, "BatteryStatus ctor err: ${t.message}", t)
                        }
                        inst
                    }
            }
            x.log(3, logTag, "charge power data provider hooked")
        } catch (t: Throwable) {
            x.log(6, logTag, "BatteryStatus hook failed: ${t.message}", t)
        }

        // ③ 锁屏指示文案追加功率：computePowerIndication（Google 变体优先，AOSP 兜底）
        // 数据源优化（准确性问题）：max_charging_current 是电池「最大充电电流限制值」而非实时电流，
        // 快充场景下不等于真实功率。改为优先实时读取 sysfs
        //   /sys/class/power_supply/battery/current_now(µA) × voltage_now(µV) 计算真实功率，
        //   温度读 temp(0.1℃)；sysfs 读取失败时回退到 ② 缓存的 Intent extras。
        var hookedAny = false
        val candidates = listOf(
            "com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle",
            "com.android.systemui.statusbar.KeyguardIndicationController"
        )
        for (cn in candidates) {
            try {
                val cls = classLoader.loadClass(cn)
                cls.declaredMethods.filter { it.name == "computePowerIndication" }.forEach { m ->
                    x.hook(m)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val text = result as? CharSequence
                                val st = ChargePowerState
                                if (text != null) {
                                    val rt = readRealtimeBattery()
                                    val currentA: Float
                                    val voltageV: Float
                                    val tempC: Float
                                    val show: Boolean
                                    if (rt.ok) {
                                        // sysfs 实时值：须处于充电态才显示（current_now 未充电时也可能为正，
                                        // 仅按 >0 判定会在未充电时误显功率）
                                        currentA = rt.currentA
                                        voltageV = rt.voltageV
                                        tempC = if (rt.tempOk) rt.temperatureC else st.temperatureC
                                        show = (isChargingNow() || st.charging) && currentA > 0f && voltageV > 0f
                                    } else {
                                        // 回退缓存（Intent extras：max_charging_current/voltage）
                                        currentA = st.maxChargingCurrentA
                                        voltageV = st.maxChargingVoltageV
                                        tempC = st.temperatureC
                                        show = (isChargingNow() || st.charging) && currentA > 0f && voltageV > 0f
                                    }
                                    if (show) {
                                        val power = currentA * voltageV
                                        String.format(
                                            java.util.Locale.US,
                                            "%s\n%.1fW (%.1fV, %.2fA) • %.0f°C",
                                            text, power, voltageV, currentA, tempC
                                        )
                                    } else {
                                        result
                                    }
                                } else {
                                    result
                                }
                            } catch (t: Throwable) {
                                x.log(6, logTag, "power indication err: ${t.message}", t)
                                result
                            }
                        }
                    hookedAny = true
                }
            } catch (t: Throwable) {
                x.log(6, logTag, "keyguard class not found: $cn - ${t.message}")
            }
        }
        if (hookedAny) {
            x.log(3, logTag, "computePowerIndication hooked")
        } else {
            x.log(6, logTag, "computePowerIndication hook failed (no candidate matched)")
        }
    }

    /** sysfs 实时电池读数（充电功率准确性优化）。 */
    private class RealtimeBattery(
        val currentA: Float,
        val voltageV: Float,
        val temperatureC: Float,
        val tempOk: Boolean,
        val ok: Boolean
    )

    private fun readRealtimeBattery(): RealtimeBattery {
        var current = 0f
        var voltage = 0f
        var temp = 0f
        var hasCurrent = false
        var hasVoltage = false
        var hasTemp = false
        try {
            val c = File("/sys/class/power_supply/battery/current_now").readText().trim().toFloatOrNull()
            if (c != null) {
                val raw = c / 1_000_000f
                // EMA 平滑：抑制瞬时电流噪声导致功率跳变；历史值为 0 或本次为负（放电）时直接取本次
                val prev = ChargePowerState.smoothCurrentA
                current = if (prev > 0f && raw > 0f) prev * 0.65f + raw * 0.35f else raw
                ChargePowerState.smoothCurrentA = current
                hasCurrent = true
            }
        } catch (_: Throwable) {}
        try {
            val v = File("/sys/class/power_supply/battery/voltage_now").readText().trim().toFloatOrNull()
            if (v != null) { voltage = v / 1_000_000f; hasVoltage = true }
        } catch (_: Throwable) {}
        try {
            val t = File("/sys/class/power_supply/battery/temp").readText().trim().toFloatOrNull()
            if (t != null) { temp = t / 10f; hasTemp = true }
        } catch (_: Throwable) {}
        return RealtimeBattery(current, voltage, temp, hasTemp, hasCurrent && hasVoltage)
    }

    /**
     * 实时充电状态判定：读 sysfs status（Charging/Full 视为充电中）。
     * 不受 onReceive 广播缓存时序影响，未插充电器时即刻返回 false，杜绝未充电误显功率。
     */
    private fun isChargingNow(): Boolean {
        return try {
            val st = File("/sys/class/power_supply/battery/status").readText().trim()
            st == "Charging" || st == "Full"
        } catch (_: Throwable) {
            false
        }
    }

    /** 反射读取布尔字段（沿类继承链查找）。 */
    private fun fieldBool(obj: Any, name: String): Boolean {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.getBoolean(obj)
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            } catch (t: Throwable) {
                return false
            }
        }
        return false
    }
}
