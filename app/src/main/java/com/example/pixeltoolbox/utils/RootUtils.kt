/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.utils

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import com.example.pixeltoolbox.shizuku.SimSlotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.io.DataOutputStream

private object PersistentRootShell {
    private var process: Process? = null
    private var out: DataOutputStream? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

    private var lastFailureTime = 0L
    private val COOLDOWN_MS = 15_000L

    private fun isInCooldown(): Boolean =
        lastFailureTime > 0 && System.currentTimeMillis() - lastFailureTime < COOLDOWN_MS

    private fun markFailure() {
        lastFailureTime = System.currentTimeMillis()
    }

    private fun isProcessAlive(p: Process?): Boolean {
        if (p == null) return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    private fun initShell() {
        if (process != null && isProcessAlive(process)) return
        try {
            val suCmd = if (File("/system/bin/su").exists()) "/system/bin/su" else "su"
            process = Runtime.getRuntime().exec(arrayOf(suCmd, "--mount-master"))
            out = DataOutputStream(process!!.outputStream)
            reader = process!!.inputStream.bufferedReader()
            lastFailureTime = 0L
        } catch (e: Exception) {
            try {
                val suCmd = if (File("/system/bin/su").exists()) "/system/bin/su" else "su"
                process = Runtime.getRuntime().exec(suCmd)
                out = DataOutputStream(process!!.outputStream)
                reader = process!!.inputStream.bufferedReader()
                lastFailureTime = 0L
            } catch (e2: Exception) {
                process = null
                out = null
                reader = null
                markFailure()
            }
        }
    }

    fun exec(cmd: String): String? {
        synchronized(lock) {
            if (isInCooldown()) return null
            initShell()
            val o = out ?: return null
            val r = reader ?: return null
            try {
                o.writeBytes("$cmd\n")
                o.writeBytes("echo \"---EOF---\"\n")
                o.flush()
                val sb = java.lang.StringBuilder()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line == "---EOF---") break
                    sb.append(line).append("\n")
                }
                return sb.toString().trim()
            } catch (e: Exception) {
                try { process?.destroy() } catch (_: Exception) {}
                process = null
                out = null
                reader = null
                markFailure()
                return null
            }
        }
    }
}


object RootUtils {

    private const val TAG = "RootUtils"

    private fun getSuBinary(): String {
        return if (File("/system/bin/su").exists()) "/system/bin/su" else "su"
    }

    /**
     * 精准获取当前运行的 Root 引擎名称 (Magisk 30.7 / APatch / KernelSU)
     */
    fun getRootEngineName(): String {
        if (!hasRootPermission()) return "未检测到 Root 权限"
        val magiskVer = executeCommandOrNull("magisk -v")?.trim() ?: executeCommandOrNull("su -v")?.trim()
        if (magiskVer?.contains("MAGISK", ignoreCase = true) == true || magiskVer?.contains("30.7") == true) {
            val cleanVer = magiskVer.replace(":MAGISK", "").trim()
            return "Magisk $cleanVer (Root 已获取)"
        }
        if (File("/data/adb/ap").exists()) return "APatch 引擎 (Root 已获取)"
        if (File("/data/adb/ksu").exists()) return "KernelSU 引擎 (Root 已获取)"
        return "原生 Root (su) 已获取"
    }

    private var cachedRootStatus: Boolean = false
    private var lastRootCheckTime: Long = 0
    private var lastRootCheckResult: Boolean = false

    /**
     * 检查设备是否拥有 Root (su) 授权 (带缓存机制避免 UI 频繁唤醒 su 导致弹窗)
     * 冷却期内返回上一次探测的真实结果，避免硬编码 false 误判跳过固定配置
     */
    fun hasRootPermission(): Boolean {
        if (cachedRootStatus) return true
        val now = System.currentTimeMillis()
        if (now - lastRootCheckTime < 5000) return lastRootCheckResult

        val out = PersistentRootShell.exec("id")
        val hasRoot = out?.contains("uid=0") == true || out?.contains("root") == true
        if (hasRoot) {
            cachedRootStatus = true
        }
        lastRootCheckTime = now
        lastRootCheckResult = hasRoot
        return hasRoot
    }

    /**
     * 请求 Root 授权
     */
    fun requestRootPermission(context: Context? = null): Boolean {
        val ok = hasRootPermission()
        if (!ok && context != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "请打开 APatch / SukiSU 管理器 -> 【超级用户】 -> 开启【像素工具箱】的 Root 权限",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        return ok
    }

    /**
     * 在 Root 上下文 (su -c) 下同步执行 Shell 指令（优先单例持久化管道，极大消除 UI 卡顿与授权 Toast 弹窗）
     */
    fun executeCommand(command: String): Result<String> {
        val out = PersistentRootShell.exec(command)
        return if (out != null) {
            Result.success(out)
        } else {
            try {
                val suCmd = getSuBinary()
                val process = Runtime.getRuntime().exec(arrayOf(suCmd, "--mount-master", "-c", command))
                val stdout = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                process.waitFor()

                if (process.exitValue() == 0) {
                    Result.success(stdout)
                } else {
                    Result.failure(Exception(if (stderr.isNotEmpty()) stderr else stdout.ifEmpty { "退出码: ${process.exitValue()}" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun executeCommandOrNull(command: String): String? = executeCommand(command).getOrNull()

    /**
     * 带完整日志的执行：把 stdout/stderr/退出码全部打进 logcat，用于排查 IMS 注入等 root 命令失败原因
     */
    fun executeCommandVerbose(command: String): Result<String> {
        return try {
            val suCmd = getSuBinary()
            val process = Runtime.getRuntime().exec(arrayOf(suCmd, "--mount-master", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            Log.d(TAG, "exec exit=$exitCode\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr")
            if (exitCode == 0) {
                Result.success(stdout)
            } else {
                Result.failure(Exception(if (stderr.isNotEmpty()) stderr else stdout.ifEmpty { "退出码: $exitCode" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec exception", e)
            Result.failure(e)
        }
    }

    /**
     * 在持久化 Root Shell 中执行指令，避免频繁调用 su 触发 Root 管理器弹窗
     */
    fun executeCommandPersistent(command: String): String? {
        return PersistentRootShell.exec(command)
    }


    /**
     * 大文件流式 pipe 写入（支持 Root shell）
     */
    fun streamFileTo(command: String, inputFile: File): Result<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            val output = StringBuilder()
            val errorOut = StringBuilder()

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }

            val stderrThread = Thread {
                try {
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))
                    var errLine: String?
                    while (errReader.readLine().also { errLine = it } != null) {
                        errorOut.append(errLine).append("\n")
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val os = process.outputStream
            FileInputStream(inputFile).use { fis ->
                val buf = ByteArray(65536)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    os.write(buf, 0, read)
                }
            }
            os.flush()
            os.close()

            process.waitFor()
            stdoutThread.join(5000)
            stderrThread.join(5000)

            if (process.exitValue() == 0) {
                Result.success(output.toString().trim())
            } else {
                Result.failure(Exception(errorOut.toString().trim().ifEmpty { "退出码: ${process.exitValue()}" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 管道写入 Byte 数组
     */
    fun executeCommandWithStdin(command: String, stdinData: ByteArray, useShell: Boolean = true): Result<String> {
        return try {
            val cmdArray = if (useShell) arrayOf("su", "-c", command) else arrayOf("su", "-c", command)
            val process = Runtime.getRuntime().exec(cmdArray)

            val os = process.outputStream
            os.write(stdinData)
            os.flush()
            os.close()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()

            process.waitFor()
            if (process.exitValue() == 0) {
                Result.success(stdout)
            } else {
                Result.failure(Exception(if (stderr.isNotEmpty()) stderr else "退出码: ${process.exitValue()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 强行安装/降级安装 APK
     */
    fun installApk(apkPath: String): Result<String> {
        val cmd = "pm install -r -d '$apkPath'"
        return executeCommand(cmd)
    }

    /**
     * 强制锁定全局屏幕刷新率
     */
    fun setRefreshRate(rate: Float): Result<String> {
        val r = rate.toInt()
        val cmd1 = "settings put system peak_refresh_rate $rate"
        val cmd2 = "settings put system min_refresh_rate $rate"
        executeCommand(cmd1)
        val res = executeCommand(cmd2)
        return if (res.isSuccess) Result.success("刷新率已锁定为 ${r}Hz") else res
    }

    // ===== IMS 注入配置持久化 =====
    // 注入已改为 Root 直改 carrier config XML（见 ImsModifier）+ killall，写磁盘持久。
    // 此处开关快照用于开机重注入兜底：防止 carrier app 重启重新生成 XML 覆盖注入值。
    // 「应用配置」时 saveImsConfig 存快照，开机后 ImsBootReceiver 据此重注入。

    private const val IMS_PREFS = "ims_injection_config"
    private const val IMS_ENABLED_KEYS = "enabled_keys"

    // 参与注入的 16 个开关 key
    private val IMS_ALL_KEYS = listOf(
        "volte", "vilte", "ut", "vowifi", "nr_5g", "vonr", "cross_sim",
        "lte_4g", "5g_signal", "5ga_icon",
        "nr_sa_fast_camp", "5g_ca_enable", "dynamic_sar", "smart_data_switch",
        "unlock_network_types", "net_optimize"
    )

    /**
     * 语音兜底 key：VoLTE / ViLTE / UT / VoNR。
     * 与 16 个 5G 优化开关解耦——「应用配置」无条件强制开启这组语音能力，
     * 保证用户无论如何勾选，至少能接打电话（4G 走 VoLTE、5G 走 VoNR）。
     */
    private val VOICE_FALLBACK_KEYS = setOf("volte", "vilte", "ut", "vonr")

    /**
     * settings global 残留 key 全集：旧版 settings 方案（ImsConfigServiceImpl）与第三方工具
     * 可能写入的 IMS/5G 相关 key。一键还原时全部清除，避免「UI 与系统状态不一致」的残留问题。
     */
    private val IMS_RESIDUE_SETTINGS_KEYS = listOf(
        "volte_vt_enabled", "vonr_enabled", "vt_ims_enabled",
        "wfc_ims_enabled", "wfc_ims_mode", "wfc_ims_roaming_enabled",
        "nr_advanced_threshold_bandwidth_khz", "nr_ssrsrp_thresholds",
        "nr_sa_disable_policy_int", "cross_sim_ims_available",
        "ss_over_ut_enabled", "show_4g_for_lte_data_icon",
        "carrier_config_version", "enhanced_5g_service_icon", "show_5ga_icon"
    )

    fun saveImsConfig(context: Context, toggleMap: Map<String, Boolean>) {
        val enabled = toggleMap.filterValues { it }.keys
        context.getSharedPreferences(IMS_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(IMS_ENABLED_KEYS, enabled).apply()
    }

    fun clearImsConfig(context: Context) {
        context.getSharedPreferences(IMS_PREFS, Context.MODE_PRIVATE)
            .edit().remove(IMS_ENABLED_KEYS).apply()
    }

    fun loadImsConfig(context: Context): Map<String, Boolean> {
        val enabled = context.getSharedPreferences(IMS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(IMS_ENABLED_KEYS, null)
        return IMS_ALL_KEYS.associateWith { enabled?.contains(it) == true }
    }

    fun hasImsConfig(context: Context): Boolean {
        val enabled = context.getSharedPreferences(IMS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(IMS_ENABLED_KEYS, null)
        return !enabled.isNullOrEmpty()
    }

    /**
     * 固定基础网络配置：开启 radio 层 VoNR + 恢复全制式（5G NR + 4G LTE）。
     * 与注入开关快照完全解耦——无论有无注入、注入是否成功，开机/SIM 就绪都固定执行，
     * 保证重启后语音承载正常（5G 走 VoNR、4G 走 VoLTE）。
     *
     * 逐条执行并容忍空槽失败：单卡设备上 `-s 1`（无订阅槽位）会报
     * "No valid subscription found." 并退出非 0，若用 && 串联会拖垮整条命令，
     * 导致 UI 误报「radio 层 VoNR 写入失败」。实际 VoNR setprop 已成功，
     * 制式命令按槽位独立执行、不参与成败判定。
     */
    fun applyFixedRadioConfig(): Result<String> {
        // VoNR prop 两条是硬要求（语音承载核心），必须成功
        val vonr0 = executeCommand("setprop persist.radio.is_vonr_enabled_0 true")
        val vonr1 = executeCommand("setprop persist.radio.is_vonr_enabled_1 true")
        // 制式设置按槽位独立执行：容忍失败（空槽 / 权限），不参与成败判定
        executeCommand("cmd phone set-allowed-network-types-for-users -s 0 11001111101111111111")
        executeCommand("cmd phone set-allowed-network-types-for-users -s 1 11001111101111111111")
        return if (vonr0.isSuccess && vonr1.isSuccess) {
            Result.success("VoNR(radio层)与全制式已就位")
        } else {
            Result.failure(Exception("setprop persist.radio.is_vonr_enabled_* 写入失败"))
        }
    }

    /**
     * 自动开关一次飞行模式（先开 3 秒再关），让 radio 层配置（VoNR / 网络制式）即时生效。
     * 阻塞式，调用方需在 IO 线程执行。
     */
    fun toggleAirplaneMode(): Result<String> {
        return try {
            val enable = executeCommand("cmd connectivity airplane-mode enable")
            if (enable.isFailure) return enable
            Thread.sleep(3000)
            val disable = executeCommand("cmd connectivity airplane-mode disable")
            if (disable.isFailure) return disable
            Result.success("飞行模式已自动切换")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 应用 5G & IMS CarrierConfig 注入
     */
    fun applyCarrierConfig(context: Context, subId: Int, toggleMap: Map<String, Boolean>, onResult: (Boolean, String) -> Unit) {
        if (!hasRootPermission()) {
            onResult(false, "未获取 Root 权限")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // ---- D 组：基带调优（setprop，独立开关）----
                // SA 快速选网：移动中快速驻留 5G
                executeCommand("setprop persist.vendor.radio.nr_sa_fast_camp ${if (toggleMap["nr_sa_fast_camp"] == true) "1" else "0"}")
                // 5G 载波聚合：多载波聚合提速
                executeCommand("setprop persist.vendor.radio.5g_ca_enable ${if (toggleMap["5g_ca_enable"] == true) "1" else "0"}")
                // 解除射频省电压制：dynamic_sar=0 关闭 SAR 省电（解除压制），=1 恢复省电
                executeCommand("setprop persist.vendor.radio.dynamic_sar ${if (toggleMap["dynamic_sar"] == true) "0" else "1"}")
                // 智能数据切换：主副卡自动切换
                executeCommand("setprop persist.vendor.radio.smart_data_switch ${if (toggleMap["smart_data_switch"] == true) "1" else "0"}")

                // 解锁全部网络制式：开放 2G/3G/4G/5G 所有制式
                if (toggleMap["unlock_network_types"] == true) {
                    executeCommand("cmd phone set-allowed-network-types-for-users -s 0 11001111101111111111")
                    executeCommand("cmd phone set-allowed-network-types-for-users -s 1 11001111101111111111")
                }

                // 网络优化：大缓冲区 + TCP Fast Open（不锁制式，制式保持全制式由系统按信号自动选择 5G/4G，避免锁 NR 断语音）
                if (toggleMap["net_optimize"] == true) {
                    // 调大收发缓冲区
                    executeCommand("sysctl -w net.core.rmem_max=16777216")
                    executeCommand("sysctl -w net.core.wmem_max=16777216")
                    executeCommand("sysctl -w net.ipv4.tcp_rmem=\"4096 87380 16777216\"")
                    executeCommand("sysctl -w net.ipv4.tcp_wmem=\"4096 65536 16777216\"")
                    // TCP Fast Open + 关闭空闲后慢启动
                    executeCommand("sysctl -w net.ipv4.tcp_fastopen=3")
                    executeCommand("sysctl -w net.ipv4.tcp_slow_start_after_idle=0")
                } else {
                    // 关闭时还原：缓冲区回默认 + FastOpen 回默认（制式保持全制式，不随开关变化）
                    executeCommand("cmd phone set-allowed-network-types-for-users -s 0 11001111101111111111")
                    executeCommand("sysctl -w net.core.rmem_max=8388608")
                    executeCommand("sysctl -w net.core.wmem_max=8388608")
                    executeCommand("sysctl -w net.ipv4.tcp_rmem=\"2097152 6291456 16777216\"")
                    executeCommand("sysctl -w net.ipv4.tcp_wmem=\"512000 2097152 8388608\"")
                    executeCommand("sysctl -w net.ipv4.tcp_fastopen=1")
                    executeCommand("sysctl -w net.ipv4.tcp_slow_start_after_idle=1")
                }

                // VoNR 全局开关（语音兜底：无条件开启，与勾选解耦）
                executeCommand("settings put global vonr_enabled 1")

                // ---- A/B/C 组：CarrierConfig 细粒度注入（key=value 字符串）----
                // 语音兜底：volte/vilte/ut/vonr 无条件置 1（4G VoLTE / 5G VoNR 语音底线），
                // 其余 5G 优化 key 按用户勾选注入
                val carrierKeys = listOf("volte", "vilte", "ut", "vowifi", "nr_5g", "vonr", "cross_sim", "lte_4g", "5g_signal", "5ga_icon")
                val toggleStr = carrierKeys.joinToString(",") { key ->
                    val forced = key in VOICE_FALLBACK_KEYS // 语音兜底：无条件 1
                    "$key=${if (forced || toggleMap[key] == true) "1" else "0"}"
                }

                val pkgName = context.packageName
                val codePath = context.packageCodePath
                val classPath = if (codePath.isNotBlank()) codePath else "/data/app/$pkgName/base.apk"
                val imsCmd = "app_process -Djava.class.path=$classPath /system/bin com.example.pixeltoolbox.ims.ImsModifier \"$toggleStr\" $subId"

                Log.d(TAG, "applyCarrierConfig: subId=$subId imsCmd=$imsCmd")
                val res = executeCommandVerbose(imsCmd)
                if (res.isSuccess) {
                    onResult(true, "CarrierConfig 注入成功")
                } else {
                    onResult(false, "CarrierConfig 注入失败: ${res.exceptionOrNull()?.message ?: "未知错误"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyCarrierConfig error", e)
                onResult(false, "写入失败: ${e.message}")
            }
        }
    }

    /**
     * 还原 CarrierConfig 设置
     */
    fun restoreCarrierConfig(context: Context, subId: Int, onResult: (Boolean, String) -> Unit) {
        if (!hasRootPermission()) {
            onResult(false, "未获取 Root 权限")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // ===== 1. 清 settings global 残留 key 全集（旧版 settings 方案 / 第三方工具写入）=====
                IMS_RESIDUE_SETTINGS_KEYS.forEach { key ->
                    executeCommand("settings delete global $key")
                }

                // ===== 2. 还原 persist prop（删除即恢复官方默认）=====
                // radio 层 VoNR 状态（applyFixedRadioConfig 写入，还原时一并清掉避免不对称残留）
                executeCommand("setprop persist.radio.is_vonr_enabled_0 ''")
                executeCommand("setprop persist.radio.is_vonr_enabled_1 ''")
                // 基带调优 prop
                executeCommand("setprop persist.vendor.radio.nr_sa_fast_camp ''")
                executeCommand("setprop persist.vendor.radio.5g_ca_enable ''")
                executeCommand("setprop persist.vendor.radio.dynamic_sar ''")
                executeCommand("setprop persist.vendor.radio.smart_data_switch ''")

                // 还原网络制式限制（恢复运营商默认）
                executeCommand("cmd phone set-allowed-network-types-for-users -s 0 11001111101111111111")
                executeCommand("cmd phone set-allowed-network-types-for-users -s 1 11001111101111111111")

                // 还原网络优化：缓冲区回默认 + FastOpen 回默认（制式已在上面还原）
                executeCommand("sysctl -w net.core.rmem_max=8388608")
                executeCommand("sysctl -w net.core.wmem_max=8388608")
                executeCommand("sysctl -w net.ipv4.tcp_rmem=\"2097152 6291456 16777216\"")
                executeCommand("sysctl -w net.ipv4.tcp_wmem=\"512000 2097152 8388608\"")
                executeCommand("sysctl -w net.ipv4.tcp_fastopen=1")
                executeCommand("sysctl -w net.ipv4.tcp_slow_start_after_idle=1")

                // 还原 CarrierConfig XML：用首次注入前备份覆盖，ImsModifier 内部会 killall 重载
                val pkgName = context.packageName
                val codePath = context.packageCodePath
                val classPath = if (codePath.isNotBlank()) codePath else "/data/app/$pkgName/base.apk"
                val imsCmd = "app_process -Djava.class.path=$classPath /system/bin com.example.pixeltoolbox.ims.ImsModifier \"restore\" $subId"
                val res = executeCommand(imsCmd)
                if (res.isSuccess) {
                    onResult(true, "网络配置已还原为官方默认")
                } else {
                    onResult(false, "还原失败: ${res.exceptionOrNull()?.message ?: "未知错误"}")
                }
            } catch (e: Exception) {
                onResult(false, "还原失败: ${e.message}")
            }
        }
    }

    /**
     * 仅语音兜底注入：不依赖任何快照，无条件注入 VoLTE / ViLTE / UT / VoNR 语音能力。
     * 用于开机无快照场景（用户从未点过「应用配置」或已还原），保证「永久默认有效」——
     * 任何设备状态下至少能接打电话（4G 走 VoLTE、5G 走 VoNR）。
     * 不动 D 组 setprop / 制式 / sysctl，避免污染 5G 优化开关状态。
     */
    fun applyVoiceOnly(context: Context, onResult: (Boolean, String) -> Unit) {
        if (!hasRootPermission()) {
            onResult(false, "未获取 Root 权限")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pkgName = context.packageName
                val codePath = context.packageCodePath
                val classPath = if (codePath.isNotBlank()) codePath else "/data/app/$pkgName/base.apk"
                val toggleStr = VOICE_FALLBACK_KEYS.joinToString(",") { "$it=1" }
                val imsCmd = "app_process -Djava.class.path=$classPath /system/bin com.example.pixeltoolbox.ims.ImsModifier \"$toggleStr\" -1"
                Log.d(TAG, "applyVoiceOnly: $imsCmd")
                val res = executeCommandVerbose(imsCmd)
                if (res.isSuccess) {
                    onResult(true, "语音兜底注入成功（VoLTE/ViLTE/UT/VoNR）")
                } else {
                    onResult(false, "语音兜底注入失败: ${res.exceptionOrNull()?.message ?: "未知错误"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyVoiceOnly error", e)
                onResult(false, "语音兜底注入失败: ${e.message}")
            }
        }
    }

    /**
     * 回读 CarrierConfig XML 中 A/B/C 组开关真实状态（Root 直读，不依赖 Shizuku）
     * 经 app_process 调 ImsModifier "read" 模式，解析 "STATES:key=1,key=0,..." 输出
     */
    fun readCarrierConfigStates(context: Context): Map<String, Boolean> {
        if (!hasRootPermission()) return emptyMap()
        return try {
            val pkgName = context.packageName
            val codePath = context.packageCodePath
            val classPath = if (codePath.isNotBlank()) codePath else "/data/app/$pkgName/base.apk"
            val cmd = "app_process -Djava.class.path=$classPath /system/bin com.example.pixeltoolbox.ims.ImsModifier \"read\" -1"
            val out = executeCommandOrNull(cmd) ?: return emptyMap()
            val line = out.lines().firstOrNull { it.startsWith("STATES:") } ?: return emptyMap()
            val payload = line.removePrefix("STATES:")
            val map = mutableMapOf<String, Boolean>()
            payload.split(',').forEach { kv ->
                val p = kv.split('=')
                if (p.size == 2) map[p[0]] = p[1] == "1"
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "readCarrierConfigStates failed", e)
            emptyMap()
        }
    }

    /**
     * 回读基带调优 prop 的当前系统状态（用于 IMS 注入卡 D 组开关高亮）
     * 返回 key -> 是否已开启；无 Root 或读取失败返回空 Map
     */
    fun readNetworkPropStates(): Map<String, Boolean> {
        if (!hasRootPermission()) return emptyMap()
        return try {
            val saFast = executeCommandOrNull("getprop persist.vendor.radio.nr_sa_fast_camp")?.trim() == "1"
            val caEnable = executeCommandOrNull("getprop persist.vendor.radio.5g_ca_enable")?.trim() == "1"
            // dynamic_sar=0 表示关闭 SAR 省电（即"解除射频省电压制"已开启）
            val sarOff = executeCommandOrNull("getprop persist.vendor.radio.dynamic_sar")?.trim() == "0"
            val smartSwitch = executeCommandOrNull("getprop persist.vendor.radio.smart_data_switch")?.trim() == "1"
            // 网络优化：仅看 FastOpen=3 视为已开启（不再锁制式，制式始终全制式由系统按信号自动选择）
            val netOptimize = executeCommandOrNull("cat /proc/sys/net/ipv4/tcp_fastopen")?.trim() == "3"
            // 解锁全部网络制式：get-allowed-network-types 输出含 NR 视为已解锁（还原后为运营商默认）
            val unlockNet = executeCommandOrNull("cmd phone get-allowed-network-types-for-users -s 0")?.trim()?.contains("NR") == true
            mapOf(
                "nr_sa_fast_camp" to saFast,
                "5g_ca_enable" to caEnable,
                "dynamic_sar" to sarOff,
                "smart_data_switch" to smartSwitch,
                "unlock_network_types" to unlockNet,
                "net_optimize" to netOptimize
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 获取可用 SIM 卡槽列表
     */
    fun getAvailableSimSlots(context: Context): List<SimSlotInfo> {
        val slots = mutableListOf<SimSlotInfo>()
        try {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()

            val subInfoList = subscriptionManager.activeSubscriptionInfoList
            subInfoList?.forEach { info ->
                if (info != null) {
                    val subId = info.subscriptionId
                    val slotIndex = info.simSlotIndex
                    val carrierName = info.carrierName?.toString() ?: ""
                    val mccMnc = if (info.mcc > 0) String.format("%03d%02d", info.mcc, info.mnc) else ""
                    val embedded = info.isEmbedded

                    if (subId > 0 && slotIndex >= 0) {
                        slots.add(SimSlotInfo(slotIndex, subId, carrierName.ifEmpty { "SIM ${slotIndex + 1}" }, mccMnc, embedded))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return slots.sortedBy { it.slotIndex }
    }

    /**
     * 自动读取收件箱中最新的运营商流量短信（全能模糊兼容 +86、10086001、体包含"流量"关键词短信）
     */
    fun fetchLatestCarrierSms(context: Context): String? {
        // 1. 优先通过 ContentResolver 查询最近 30 条收件箱短信
        try {
            val uri = android.net.Uri.parse("content://sms/inbox")
            context.contentResolver.query(
                uri,
                arrayOf("address", "body"),
                null,
                null,
                "date DESC LIMIT 30"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                while (cursor.moveToNext()) {
                    val addr = if (addrIdx >= 0) cursor.getString(addrIdx) ?: "" else ""
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx) ?: "" else ""

                    val isCarrierAddr = addr.contains("10086") || addr.contains("10010") || 
                                        addr.contains("10000") || addr.contains("10001") || addr.contains("10099")
                    val hasTrafficKeyword = body.contains("流量") || body.contains("GB", ignoreCase = true) || 
                                            body.contains("MB", ignoreCase = true) || body.contains("已用") || body.contains("剩余")

                    if ((isCarrierAddr || hasTrafficKeyword) && body.isNotBlank()) {
                        return body
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val cmd = "content query --uri content://sms/inbox --projection address:body --sort \"date DESC\""
            val output = executeCommandPersistent(cmd)
            if (!output.isNullOrBlank()) {
                val lines = output.lines()
                var currentBody = StringBuilder()
                var collecting = false
                
                for (line in lines) {
                    if (line.startsWith("Row: ")) {
                        // 检查上一条收集的完整短信
                        val b = currentBody.toString().trim()
                        if (b.isNotEmpty() && (b.contains("流量") || b.contains("GB", ignoreCase = true) || b.contains("MB", ignoreCase = true))) {
                            return b
                        }
                        
                        // 开始新的一条短信
                        currentBody = StringBuilder()
                        if (line.contains("body=")) {
                            currentBody.append(line.substringAfter("body=")).append(" ")
                            collecting = true
                        }
                    } else if (collecting) {
                        currentBody.append(line).append(" ")
                    }
                }
                
                // 检查最后一条收集的短信
                val b = currentBody.toString().trim()
                if (b.isNotEmpty() && (b.contains("流量") || b.contains("GB", ignoreCase = true) || b.contains("MB", ignoreCase = true))) {
                    return b
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // ===== Vector（LSPosed 兼容框架）自动配置 =====
    // LSP 三功能（去小白条/去搜索框/双击锁屏）必须注入 SystemUI 与 Launcher 进程才生效。
    // 新用户只点开关不配 Vector 会静默失效（模块未启用/作用域为空）。
    // 此封装在点开关时一键自愈：检测 cli → 启用模块 → 配置作用域。

    /**
     * Vector CLI 路径。Vector 是当前唯一支持 Android 17 的 libxposed 框架，
     * 作为 Magisk 模块挂在 /data/adb/modules/zygisk_vector/。
     */
    private fun findVectorCli(): String? {
        val cli = "/data/adb/modules/zygisk_vector/cli"
        val ok = executeCommandOrNull("test -x $cli && echo yes")?.contains("yes") == true
        return if (ok) cli else null
    }

    /** Vector 框架是否就绪（CLI 存在 = 框架载体已装）。 */
    fun isVectorReady(): Boolean = findVectorCli() != null

    /**
     * 一键自愈 Vector 模块：启用「像素工具箱」模块 + 配置作用域（SystemUI + Launcher）。
     * 新用户点 LSP 开关时调用，确保三个功能（去小白条/去搜索框/双击锁屏）真正注入生效。
     * @return 用户可读的结果描述
     */
    fun ensureVectorModule(): String {
        val cli = findVectorCli()
            ?: return "未检测到 Vector 框架。此功能需要先安装 Vector（Magisk 模块）并重启手机。"
        // 1. 启用模块（幂等）
        executeCommand("$cli modules enable com.example.pixeltoolbox")
        // 2. 配置作用域：SystemUI（去小白条）+ Launcher（去搜索框/双击锁屏）
        executeCommand("$cli scope add com.example.pixeltoolbox com.android.systemui/0 com.google.android.apps.nexuslauncher/0")
        // 3. 验证
        val scopeOut = executeCommandOrNull("$cli scope ls com.example.pixeltoolbox") ?: ""
        val hasSystemUi = scopeOut.contains("com.android.systemui")
        val hasLauncher = scopeOut.contains("com.google.android.apps.nexuslauncher")
        val enabled = executeCommandOrNull("$cli modules ls")?.contains("com.example.pixeltoolbox") == true
        return if (enabled && hasSystemUi && hasLauncher) {
            "Vector 已就绪：模块已启用，作用域已包含 SystemUI/Launcher。重启手机后生效。"
        } else {
            "Vector 配置未完全生效（enabled=$enabled systemui=$hasSystemUi launcher=$hasLauncher）。请检查 Vector 管理器。"
        }
    }

    // ===== Zygisk FingerprintPay 官方指纹模块一键部署 =====

    enum class ZygiskPayDriverMode {
        ZYGISK_ALL,       // 多合一全功能版 (微信/支付宝/QQ/淘宝/云闪付)
        ZYGISK_WECHAT,    // 微信独立版
        ZYGISK_ALIPAY,    // 支付宝独立版
        XPOSED_FALLBACK,  // Xposed 自研兜底模式
        NOT_ACTIVE        // 未激活
    }

    /**
     * 检测设备上是否已安装微信 Zygisk 指纹模块 (实时检测 Magisk / KSU / APatch 模块目录)
     */
    fun isWeChatModuleInstalled(): Boolean {
        val res = executeCommandOrNull("ls -d /data/adb/modules/*wechat* /data/adb/modules_update/*wechat* 2>/dev/null")
        return !res.isNullOrBlank() && res.contains("wechat")
    }

    /**
     * 检测设备上是否已安装支付宝 Zygisk 指纹模块 (实时检测 Magisk / KSU / APatch 模块目录)
     */
    fun isAlipayModuleInstalled(): Boolean {
        val res = executeCommandOrNull("ls -d /data/adb/modules/*alipay* /data/adb/modules_update/*alipay* 2>/dev/null")
        return !res.isNullOrBlank() && res.contains("alipay")
    }

    /**
     * 从 assets 部署指定 Zygisk 指纹模块 ZIP 到设备 Root 框架
     * @param assetName 如 "zygisk_pay_all.zip", "zygisk_pay_wechat.zip", "zygisk_pay_alipay.zip"
     */
    fun installZygiskPayModuleFromAssets(context: Context, assetName: String): Result<String> {
        return try {
            val cacheFile = File(context.cacheDir, assetName)
            context.assets.open(assetName).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val apkPath = cacheFile.absolutePath
            val magiskCmd = "magisk --install-module $apkPath"
            val ksuCmd = "ksud module install $apkPath"
            val apatchCmd = "apatch module install $apkPath"

            val res = executeCommand("$ksuCmd || $magiskCmd || $apatchCmd")
            cacheFile.delete()

            if (res.isSuccess) {
                Result.success("✅ 模块安装成功！请重启手机使 Zygisk 指纹管道生效。")
            } else {
                Result.failure(Exception("模块安装命令执行失败: ${res.exceptionOrNull()?.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

