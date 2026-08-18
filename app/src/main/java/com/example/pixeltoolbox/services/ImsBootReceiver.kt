package com.example.pixeltoolbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.pixeltoolbox.utils.RootUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 开机自动重注入 IMS / 5G CarrierConfig。
 *
 * 背景：Android 17 上 overrideConfig 已被 CVE-2025-48617 封堵，注入改为 Root 直改
 * carrier config XML（见 ImsModifier）并 killall 重载，写磁盘天然持久。开机重注入作为
 * 双保险兜底：防止 carrier app 重启时重新生成 XML 覆盖注入值。故在「应用配置」时把开关
 * 快照存入 SharedPreferences（见 RootUtils.saveImsConfig），本 Receiver 在开机后据此重新注入。
 *
 * 触发源（A+B 方案）：
 *  - BOOT_COMPLETED：多轮可靠兜底，20s / 60s / 120s 三个时间点依次尝试，成功即停；
 *  - SIM_STATE_CHANGED(LOADED)：等 SIM 就绪后再注入，5s 后触发，解决开机早期 SIM 未就绪
 *    导致注入后仍 NOT_READY 的问题。
 * 两者通过 SharedPreferences 记录上次成功注入时间做冷却，避免重复 killall com.android.phone。
 */
class ImsBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ImsBootReceiver"
        private const val PREF = "boot_inject_state"
        private const val KEY_LAST_INJECT = "last_inject_success"
        private const val COOLDOWN_MS = 5 * 60 * 1000L   // boot 触发源冷却 5 分钟
        private const val SIM_DEBOUNCE_MS = 5_000L       // SIM LOADED 短防抖（仅防双卡/重复广播，不拦 SIM 就绪补注入）
        private const val KEY_LAST_BASE = "last_base_config"
        private const val BASE_COOLDOWN_MS = 30_000L     // 固定基础配置冷却 30s（幂等，防 BOOT 多轮 + SIM 重复刷写）

        // SDK 中 TelephonyManager 的 SIM 常量已被 @hide，stub 不可解析，用字面量兜底
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        private const val EXTRA_SIM_STATE = "ss"
        private const val SIM_STATE_LOADED = "LOADED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "BOOT_COMPLETED received")
                Thread {
                    try {
                        bootRetryLoop(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "boot retry error", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }

            ACTION_SIM_STATE_CHANGED -> {
                val state = intent.getStringExtra(EXTRA_SIM_STATE)
                Log.d(TAG, "SIM_STATE_CHANGED state=$state")
                if (state == SIM_STATE_LOADED) {
                    Thread {
                        try {
                            Thread.sleep(5_000)
                            applyFixedBaseConfig(context, "sim_loaded")
                            injectIfNeeded(context, "sim_loaded")
                        } catch (e: Exception) {
                            Log.e(TAG, "sim_loaded inject error", e)
                        } finally {
                            pending.finish()
                        }
                    }.start()
                } else {
                    pending.finish()
                }
            }

            else -> pending.finish()
        }
    }

    /** BOOT 多轮兜底：20s / 60s / 120s 三个时间点依次尝试，成功即停 */
    private fun bootRetryLoop(context: Context) {
        var elapsed = 0L
        for (deadline in longArrayOf(20_000L, 60_000L, 120_000L)) {
            val wait = deadline - elapsed
            if (wait > 0) Thread.sleep(wait)
            elapsed = deadline
            applyFixedBaseConfig(context, "boot")
            if (injectIfNeeded(context, "boot_retry")) break
        }
    }

    /**
     * 固定基础网络配置：开启 radio 层 VoNR + 恢复全制式。
     * 与注入开关快照完全解耦——无论有无注入、注入是否成功，开机/SIM 就绪都固定执行。
     * 带独立 30s 冷却：失败不记 last，后续轮次继续重试，确保最终生效。
     */
    private fun applyFixedBaseConfig(context: Context, source: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_BASE, 0L)
        val now = System.currentTimeMillis()
        if (now - last < BASE_COOLDOWN_MS) {
            Log.d(TAG, "skip base config ($source): cooldown, last=$last")
            return
        }
        val res = RootUtils.applyFixedRadioConfig()
        if (res.isSuccess) {
            prefs.edit().putLong(KEY_LAST_BASE, now).apply()
            Log.d(TAG, "base config ($source) applied")
        } else {
            Log.w(TAG, "base config ($source) failed: ${res.exceptionOrNull()?.message}")
        }
    }

    /**
     * 注入入口（带冷却/防抖）。返回 true 表示无需继续后续重试（已注入或明确跳过）。
     */
    private fun injectIfNeeded(context: Context, source: String): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_INJECT, 0L)
        val now = System.currentTimeMillis()

        // 无快照：仍执行语音兜底注入（保证「永久默认有效」能接打电话），
        // 只注入 VoLTE/ViLTE/UT/VoNR 语音能力，不动 5G 优化开关
        if (!RootUtils.hasImsConfig(context)) {
            Log.d(TAG, "no snapshot ($source): apply voice-only fallback")
            val latch = CountDownLatch(1)
            var voiceOk = false
            RootUtils.applyVoiceOnly(context) { success, _ ->
                voiceOk = success
                if (success) {
                    prefs.edit().putLong(KEY_LAST_INJECT, System.currentTimeMillis()).apply()
                }
                latch.countDown()
            }
            latch.await(45, TimeUnit.SECONDS)
            return voiceOk
        }

        // boot 触发源：冷却期内直接跳过（SIM 就绪由 SIM_STATE_CHANGED 路径负责）
        if (source == "boot_retry" && now - last < COOLDOWN_MS) {
            Log.d(TAG, "skip inject ($source): cooldown active, last=$last")
            return true
        }
        // SIM LOADED 触发源：仅短防抖，冷却不拦截——SIM 就绪是更强的注入时机
        if (source == "sim_loaded" && now - last < SIM_DEBOUNCE_MS) {
            Log.d(TAG, "skip inject ($source): short debounce, last=$last")
            return true
        }

        val toggleMap = RootUtils.loadImsConfig(context)
        Log.d(TAG, "inject ($source) enabled=${toggleMap.filterValues { it }.keys}")

        val latch = CountDownLatch(1)
        var ok = false
        RootUtils.applyCarrierConfig(context, -1, toggleMap) { success, msg ->
            ok = success
            Log.d(TAG, "inject ($source) result ok=$success msg=$msg")
            if (success) {
                prefs.edit().putLong(KEY_LAST_INJECT, System.currentTimeMillis()).apply()
            }
            latch.countDown()
        }
        latch.await(45, TimeUnit.SECONDS)
        return ok
    }
}
