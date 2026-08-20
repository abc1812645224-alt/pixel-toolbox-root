/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.ims

import java.io.File

/**
 * CarrierConfig 注入器（Root 直改 XML 方案）。
 *
 * Android 17 上 ICarrierConfigLoader.overrideConfig() 已被 CVE-2025-48617 封堵，
 * persist.dbg.* setprop 也已失效。唯一稳定生效的路径是直接改写 carrier config 持久化文件
 * /data/user_de/0/com.android.phone/files/carrierconfig-<package>-<iccid>-<carrierId>.xml，
 * 然后 killall com.android.phone 触发重载。
 *
 * 因 root 进程（app_process）受 selinux 约束，无法经 SubscriptionManager 读取 ICCID，
 * 故采用「全卡处理」策略：列出目录下所有 carrierconfig-*.xml（排除 nosim），逐文件注入/还原。
 * 协议：args[0] = "key=value,key=value,..."（注入）或 "restore"（还原）；args[1] 预留 subId，当前忽略。
 */
object ImsModifier {
    private const val BACKUP_DIR = "/data/local/tmp/pixeltoolbox_carrier_backup"
    private const val LOG_FILE = "/data/local/tmp/pixeltoolbox_ims.log"
    /** 注入清单：记录本次真正写入的 key，回读只认此清单，避免运营商默认 true 被误判为已注入 */
    private const val INJECTED_FILE = "/data/local/tmp/pixeltoolbox_injected.txt"

    /**
     * 语音兜底 key：VoLTE / ViLTE / UT / VoNR。
     * 与 16 个 5G 优化开关解耦：一键还原、开机兜底都会无条件保留这组语音能力，
     * 保证任何情况下至少能接打电话（4G 走 VoLTE、5G 走 VoNR）。
     */
    private val VOICE_FALLBACK_KEYS = listOf("volte", "vilte", "ut", "vonr")
    private val VOICE_FALLBACK_TOGGLES: Map<String, Boolean> = VOICE_FALLBACK_KEYS.associateWith { true }

    /** 同时写 stdout（RootUtils 捕获进 logcat）与持久化日志文件，便于事后排查 */
    private fun log(msg: String) {
        val line = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg"
        println(line)
        try {
            File(LOG_FILE).appendText("$line\n")
        } catch (_: Exception) {}
    }

    private fun listXmlFiles(filesDir: File): List<File> {
        return try {
            filesDir.listFiles()?.filter {
                it.name.startsWith("carrierconfig-") &&
                    it.name.endsWith(".xml") &&
                    !it.name.contains("nosim")
            } ?: emptyList()
        } catch (e: Exception) {
            log("listFiles failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try { android.os.Looper.prepareMainLooper() } catch (_: Exception) {}

        try {
            val rawArg = args.getOrNull(0) ?: ""
            log("start arg0=$rawArg arg1=${args.getOrNull(1)}")

            val filesDir = File("/data/user_de/0/com.android.phone/files")
            log("filesDir exists=${filesDir.exists()} isDir=${filesDir.isDirectory}")

            // 就绪重试：phone 重建 carrier config 期间目录可能短暂读不到，等待重试
            var xmlFiles = listXmlFiles(filesDir)
            var attempts = 0
            while (xmlFiles.isEmpty() && attempts < 10) {
                attempts++
                log("no carrier config xml, retry $attempts/10")
                Thread.sleep(1000)
                xmlFiles = listXmlFiles(filesDir)
            }

            if (xmlFiles.isEmpty()) {
                val rawList = try { filesDir.list()?.toList() } catch (e: Exception) { "list failed: ${e.message}" }
                log("ERROR: no carrier config xml after $attempts retries; raw list=$rawList")
                println("ERROR: no carrier config xml found")
                System.exit(1)
                return
            }
            log("found ${xmlFiles.size} xml: ${xmlFiles.joinToString(", ") { it.name }}")

            val backupDir = File(BACKUP_DIR)
            if (!backupDir.exists()) backupDir.mkdirs()

            if (rawArg == "restore") {
                // 语音兜底：还原官方默认后自动叠加 VoLTE/ViLTE/UT/VoNR 语音能力。
                // 与 16 个 5G 优化开关解耦——无论用户开启哪些 5G 开关，还原后语音兜底始终保留，
                // 保证至少能接打电话（4G 走 VoLTE、5G 走 VoNR）。
                var restored = 0
                for (xmlFile in xmlFiles) {
                    val backup = File(backupDir, xmlFile.name)
                    if (backup.exists()) {
                        // 1. 备份覆盖：还原为注入前的官方原始配置（清掉 5G 优化 key 残留）
                        val base = backup.readText()
                        // 2. 叠加语音兜底 key（volte/vilte/ut/vonr），保证还原后语音能力仍在
                        val withVoice = applyToggles(base, VOICE_FALLBACK_TOGGLES)
                        xmlFile.writeText(withVoice)
                        restored++
                    }
                }
                if (restored > 0) {
                    killPhone()
                    // 注入清单只保留语音兜底 key（UI 回读显示语音已开、5G 优化开关为关）
                    File(INJECTED_FILE).writeText(VOICE_FALLBACK_KEYS.joinToString(","))
                    log("restored $restored xml + voice fallback keys, phone killed")
                    println("RESTORED:$restored")
                } else {
                    log("ERROR: no backup found in $backupDir")
                    println("ERROR: no backup found")
                    System.exit(1)
                    return
                }
            } else if (rawArg == "read") {
                // 回读模式：读注入清单，只认真正注入过的 key（避免运营商默认 true 被误判为已开启）
                val injectedKeys = try {
                    File(INJECTED_FILE).readText().trim().split(',').filter { it.isNotBlank() }.toSet()
                } catch (e: Exception) { emptySet() }
                val keys = listOf("volte", "vilte", "ut", "vowifi", "nr_5g", "vonr", "cross_sim", "lte_4g", "5g_signal", "5ga_icon")
                val out = keys.joinToString(",") { "$it=${if (it in injectedKeys) "1" else "0"}" }
                log("read states (from injected list): $out")
                println("STATES:$out")
                System.exit(0)
                return
            } else {
                val toggle = mutableMapOf<String, Boolean>()
                rawArg.split(',').forEach { kv ->
                    val p = kv.split('=')
                    if (p.size == 2) toggle[p[0]] = p[1] == "1"
                }

                var injected = 0
                for (xmlFile in xmlFiles) {
                    // 首次注入前备份原始 XML，供一键还原使用
                    val backup = File(backupDir, xmlFile.name)
                    if (!backup.exists()) {
                        backup.writeText(xmlFile.readText())
                        log("backup created: ${backup.absolutePath}")
                    }
                    val xml = applyToggles(xmlFile.readText(), toggle)
                    xmlFile.writeText(xml)
                    log("injected ${xmlFile.name}")
                    injected++
                }
                killPhone()
                // 落盘本次真正注入的 key 清单，供回读精确判断
                val injectedKeys = toggle.filterValues { it }.keys
                File(INJECTED_FILE).writeText(injectedKeys.joinToString(","))
                log("injected $injected xml (keys=${toggle.keys.joinToString(",")}), injected list saved")
                println("SUCCESS:$injected")
            }

            System.exit(0)
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            log("FATAL: ${t.javaClass.name}: ${t.message}\n$sw")
            println("ERROR: ${t.message ?: t.javaClass.simpleName}")
            t.printStackTrace()
            System.exit(1)
        }
    }

    private fun killPhone() {
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "killall com.android.phone")).waitFor()
        } catch (e: Exception) {}
    }

    private fun applyToggles(xml: String, toggle: Map<String, Boolean>): String {
        var x = xml
        fun on(key: String) = toggle[key] == true

        // A 组：通话类
        if (on("volte")) {
            x = setBool(x, "carrier_volte_available_bool", true)
            x = setBool(x, "editable_enhanced_4g_lte_bool", true)
            x = setBool(x, "hide_enhanced_4g_lte_bool", false)
            x = setBool(x, "hide_lte_plus_data_icon_bool", false)
        }
        if (on("vilte")) {
            x = setBool(x, "carrier_vt_available_bool", true)
        }
        if (on("ut")) {
            x = setBool(x, "carrier_supports_ss_over_ut_bool", true)
        }
        if (on("vowifi")) {
            x = setBool(x, "carrier_wfc_ims_available_bool", true)
            x = setBool(x, "carrier_wfc_supports_wifi_only_bool", true)
            x = setBool(x, "editable_wfc_mode_bool", true)
            x = setBool(x, "editable_wfc_roaming_mode_bool", true)
            x = setBool(x, "show_wifi_calling_icon_in_status_bar_bool", true)
            x = setInt(x, "wfc_spn_format_idx_int", 6)
        }

        // B 组：5G 核心
        if (on("nr_5g")) {
            x = setIntArray(x, "carrier_nr_availabilities_int_array", intArrayOf(1, 2))
            x = setInt(x, "nr_sa_disable_policy_int", 0)
            x = setBool(x, "carrier_supports_ss_ca_bool", true)
            x = setBool(x, "carrier_supports_tdd_ca_bool", true)
            x = setBool(x, "carrier_supports_fdd_ca_bool", true)
            x = setBool(x, "carrier_supports_nr_dc_bool", true)
            x = setBool(x, "perform_nr_sa_fast_camp_bool", true)
        }
        if (on("vonr")) {
            x = setBool(x, "vonr_enabled", true)
            x = setBool(x, "vonr_setting_visibility", true)
        }
        if (on("cross_sim")) {
            x = setBool(x, "carrier_cross_sim_ims_available_bool", true)
            x = setBool(x, "enable_cross_sim_calling_on_opportunistic_data_bool", true)
        }

        // C 组：显示增强
        if (on("lte_4g")) {
            x = setBool(x, "show_4g_for_lte_data_icon_bool", true)
        }
        if (on("5g_signal")) {
            x = setIntArray(x, "5g_nr_ssrsrp_thresholds_int_array", intArrayOf(-128, -118, -108, -98))
            x = setIntArray(x, "5g_nr_ssrsrq_thresholds_int_array", intArrayOf(-38, -28, -18, -8))
            x = setIntArray(x, "5g_nr_sssinr_thresholds_int_array", intArrayOf(-23, -13, -3, 7))
        }
        if (on("5ga_icon")) {
            // 阈值对齐厂商最宽松档（电信/联通 3CC ≥130MHz），原 100MHz 为 Android 早期旧默认
            x = setInt(x, "nr_advanced_threshold_bandwidth_khz_int", 130000)
            x = setBool(x, "include_lte_for_nr_advanced_threshold_bandwidth_bool", false)
            x = setIntArray(x, "additional_nr_advanced_bands_int_array", intArrayOf(1, 3, 8, 28, 41, 78, 79))
            x = setString(x, "5g_icon_configuration_string", "connected_mmwave:5G_Plus,connected:5G_Plus,connected_rrc_idle:5G,not_restricted_rrc_idle:5G,not_restricted_rrc_con:5G")
            x = setInt(x, "nr_advanced_capable_pco_id_int", 0)
        }

        x = setInt(x, "pixel_toolbox_config_version", 3)
        return x
    }

        private fun setBool(xml: String, key: String, value: Boolean): String {
        val re = Regex("""<boolean name="$key" value="[^"]*"\s*/>""")
        val rep = """<boolean name="$key" value="$value" />"""
        return if (re.containsMatchIn(xml)) re.replace(xml, rep)
        else xml.replace("</bundle>", "  $rep\n</bundle>")
    }

    private fun setInt(xml: String, key: String, value: Int): String {
        val re = Regex("""<int name="$key" value="[^"]*"\s*/>""")
        val rep = """<int name="$key" value="$value" />"""
        return if (re.containsMatchIn(xml)) re.replace(xml, rep)
        else xml.replace("</bundle>", "  $rep\n</bundle>")
    }

    private fun setString(xml: String, key: String, value: String): String {
        val re = Regex("""<string name="$key">.*?</string>""", RegexOption.DOT_MATCHES_ALL)
        val rep = """<string name="$key">$value</string>"""
        return if (re.containsMatchIn(xml)) re.replace(xml, rep)
        else xml.replace("</bundle>", "  $rep\n</bundle>")
    }

    private fun setIntArray(xml: String, key: String, values: IntArray): String {
        val re = Regex("""<int-array name="$key"[^>]*>.*?</int-array>""", RegexOption.DOT_MATCHES_ALL)
        val items = values.joinToString(" ") { """<item value="$it" />""" }
        val rep = """<int-array name="$key" num="${values.size}">$items</int-array>"""
        return if (re.containsMatchIn(xml)) re.replace(xml, rep)
        else xml.replace("</bundle>", "  $rep\n</bundle>")
    }
}
