/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.example.pixeltoolbox.ui.system

import java.io.File
import java.io.FileOutputStream
import com.example.pixeltoolbox.ui.signal.ImsGroupSwitchRow
import com.example.pixeltoolbox.ExecutionLogCard
import com.example.pixeltoolbox.ui.signal.SignalScreen
import com.example.pixeltoolbox.ui.theme.AutoSizeText
import com.example.pixeltoolbox.ui.about.installDesktopLauncher
import com.example.pixeltoolbox.ui.about.createLockScreenShortcut
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.core.app.ActivityCompat
import com.example.pixeltoolbox.shizuku.ShizukuUtils
import com.example.pixeltoolbox.system.permissions.PermissionChecks
import com.example.pixeltoolbox.utils.RootUtils
import com.example.pixeltoolbox.shizuku.SimSlotInfo
import com.example.pixeltoolbox.ui.theme.GlassCard
import com.example.pixeltoolbox.ui.theme.iOSButton
import com.example.pixeltoolbox.ui.theme.iOSOutlineButton
import com.example.pixeltoolbox.ui.theme.iOSBackground
import com.example.pixeltoolbox.ui.theme.iOSLabel
import com.example.pixeltoolbox.ui.theme.iOSSecondaryLabel
import com.example.pixeltoolbox.ui.theme.iOSNavUnselected
import com.example.pixeltoolbox.ui.theme.iOSBlue
import com.example.pixeltoolbox.ui.theme.iOSGreen
import com.example.pixeltoolbox.ui.theme.iOSRed
import com.example.pixeltoolbox.ui.theme.iOSCardBackground
import com.example.pixeltoolbox.ui.theme.iOSSeparator
import com.example.pixeltoolbox.ui.theme.PixelToolboxTheme
import com.example.pixeltoolbox.signal.SignalMonitor
import com.example.pixeltoolbox.signal.SignalDashboardState
import com.example.pixeltoolbox.signal.SignalMetrics
import com.example.pixeltoolbox.signal.NetworkMetrics
import com.example.pixeltoolbox.signal.DeviceMetrics
import com.example.pixeltoolbox.signal.TrafficMetrics
import com.example.pixeltoolbox.signal.SystemMetrics
import com.example.pixeltoolbox.ui.signal.SignalDashboard
import com.example.pixeltoolbox.ui.geektools.GeekToolsCard
import com.example.pixeltoolbox.ui.geektools.SectionTitle
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.PrintWriter
import java.io.StringWriter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.pixeltoolbox.data.AppPreferences
import com.example.pixeltoolbox.services.recording.ManageOngoingCalls
import com.example.pixeltoolbox.ui.custom.CallRecordingSettingsActivity
import com.example.pixeltoolbox.services.KeepAliveService
import com.example.pixeltoolbox.ui.system.AppItem
import com.example.pixeltoolbox.ui.system.loadInstalledApps
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import android.os.VibratorManager
import androidx.core.graphics.drawable.toBitmap

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    executionLogs: List<String>,
    batTemp: Float, batVolt: Int, batteryStatus: Int, batCurrentNA: Int,
    dpiInput: String, setDpiInput: (String) -> Unit,
    context: Context, coroutineScope: CoroutineScope, addLog: (String) -> Unit,
    onOpenGpsTest: () -> Unit,
    onOpenBarometerTest: () -> Unit
) {
    ExecutionLogCard(executionLogs)
    val iOSOrange = androidx.compose.ui.graphics.Color(0xFFFF9500)
    var hasShizuku by remember { mutableStateOf(ShizukuUtils.hasShizukuPermission()) }
    Spacer(modifier = Modifier.height(16.dp))
    // 电池状态信息
    BatteryInfoCard(batTemp, batVolt, batteryStatus, batCurrentNA)
    Spacer(modifier = Modifier.height(16.dp))
    // ========== 暴力清理 ==========
    Spacer(modifier = Modifier.height(20.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("暴力清理", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("一键清理后台非系统进程与应用缓存", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 按钮 1：一键清后台
                Surface(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val launcherResult = ShizukuUtils.executeCommand(
                                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1"
                            )
                            val launcherPkg = launcherResult.getOrNull()?.trim().orEmpty()
                            val imeResult = ShizukuUtils.executeCommand("settings get secure default_input_method")
                            val imePkg = imeResult.getOrNull()?.trim()?.substringBefore("/").orEmpty()
                            val myPid = Process.myPid()
                            val psResult = ShizukuUtils.executeCommand("ps -A")
                            val psRaw = psResult.getOrNull() ?: ""
                            val psLines = if (psRaw.isNotBlank())
                                psResult.getOrNull()!!.lines().drop(1).filter { it.isNotBlank() }
                            else
                                emptyList()
                            if (psLines.isEmpty()) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, "无法获取进程列表，请确认 Shizuku 已授权", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            val whitelistNames = setOf(
                                "system_server", "zygote", "zygote64", "zygote32",
                                "surfaceflinger", "servicemanager", "hwservicemanager",
                                "audioserver", "cameraserver", "mediaserver", "drmserver",
                                "netd", "vold", "installd", "keystore",
                                "logd", "lmkd", "statsd", "storaged", "healthd"
                            )
                            fun isWhitelisted(name: String): Boolean = when {
                                name in whitelistNames -> true
                                name.startsWith("thermal") -> true
                                name.startsWith("android.") -> true
                                name.contains("com.android.systemui") -> true
                                name.contains("com.android.phone") -> true
                                name.contains("com.android.settings") -> true
                                name.contains("com.example.pixeltoolbox") -> true
                                name.contains("moe.shizuku") -> true
                                name.contains("com.android.bluetooth") -> true
                                name.contains("com.google.android.gms") -> true
                                name.contains("com.google.android.gsf") -> true
                                name.contains("com.android.providers.media") -> true
                                imePkg.isNotEmpty() && name.contains(imePkg) -> true
                                launcherPkg.isNotEmpty() && name.contains(launcherPkg) -> true
                                else -> false
                            }
                            val packagesToKill = linkedSetOf<String>()
                            for (line in psLines) {
                                val cols = line.trim().split(Regex("\\s+"))
                                if (cols.size < 9) continue
                                val pid = cols[1]
                                val name = cols.last()
                                if (pid.toIntOrNull() == myPid) continue
                                if (isWhitelisted(name)) continue
                                val basePkg = name.substringBefore(":")
                                if (basePkg.contains(".")) {
                                    packagesToKill.add(basePkg)
                                }
                            }
                            if (packagesToKill.isEmpty()) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, "没有可清理的进程", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            val killCmd = packagesToKill.joinToString(" & ") { "am force-stop $it" } + " & wait"
                            ShizukuUtils.executeCommand(killCmd)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "已清理 ${packagesToKill.size} 个应用", Toast.LENGTH_SHORT).show()
                                addLog("暴力清后台：已清理 ${packagesToKill.size} 个应用")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE53E3E)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("清理后台", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
                // 按钮 2：一键清缓存
                Surface(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val result = ShizukuUtils.executeCommand("pm trim-caches 999G")
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (result.isSuccess) {
                                    Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                                    addLog("缓存已清理")
                                } else {
                                    Toast.makeText(context, "清理缓存失败，请确认 Shizuku 已授权", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFED8936)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("清理缓存", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    // GPS 测试
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("GPS 测试", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("实时查看卫星分布、信号强度与定位数据", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            iOSButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenGpsTest
            ) { Text("GPS 测试", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    var currentCpuMode by remember { mutableStateOf("default") }
    var currentVibLevel by remember { mutableStateOf(2) }
    
    LaunchedEffect(Unit) {
        if (com.example.pixeltoolbox.shizuku.ShizukuUtils.hasShizukuPermission()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 读取振动级别
                val res = com.example.pixeltoolbox.shizuku.ShizukuUtils.executeCommand("settings get system hardware_haptic_feedback_intensity").getOrNull()?.trim()
                val level = res?.toIntOrNull()
                if (level != null && level in 0..3) {
                    currentVibLevel = level
                }
                
                // 读取真实CPU模式：以系统真实状态为准。
                val perfModeRes = com.example.pixeltoolbox.shizuku.ShizukuUtils.executeCommand("cmd power get-fixed-performance-mode-enabled 2>/dev/null").getOrNull()?.trim()
                val cpuModeRes = com.example.pixeltoolbox.shizuku.ShizukuUtils.executeCommand("cat /data/local/tmp/pixel_cpu_mode 2>/dev/null").getOrNull()?.trim()
                val perfRealOn = perfModeRes == "true" || perfModeRes == "1" || perfModeRes == "enabled"
                currentCpuMode = when {
                    perfRealOn -> "performance"
                    cpuModeRes == "saver" -> "saver"
                    cpuModeRes == "performance" -> "performance"
                    else -> "default"
                }
            }
        }
    }
    // CuprumTurbo 铜引擎性能调度模式
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("CuprumTurbo 铜引擎性能调度", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("结合 CuprumTurbo 开源算法，三挡智能调优 CPU 调频器、uclamp 与 EAS 能量调度", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "saver" to "省电挡 🍀",
                    "default" to "默认挡 ⚖️",
                    "performance" to "性能挡 🚀"
                ).forEach { (mode, label) ->
                    val cmd = when (mode) {
                        "saver" -> "for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo powersave > \$g 2>/dev/null || echo schedutil > \$g 2>/dev/null; done; echo -15 > /dev/stune/top-app/schedtune.boost 2>/dev/null; echo 0 > /dev/uclamp/top-app/uclamp.min 2>/dev/null; echo 512 > /dev/uclamp/top-app/uclamp.max 2>/dev/null; cutoolbox mode powersave 2>/dev/null; cuprumturbo -m powersave 2>/dev/null; echo 'saver' > /data/local/tmp/pixel_cpu_mode"
                        "performance" -> "cmd power set-mode 0 2>/dev/null; cmd power set-fixed-performance-mode-enabled true 2>/dev/null; for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo performance > \$g 2>/dev/null || echo schedutil > \$g 2>/dev/null; done; for r in /sys/devices/system/cpu/cpufreq/policy*/schedutil/rate_limit_us; do echo 0 > \$r 2>/dev/null; done; echo 100 > /dev/stune/top-app/schedtune.boost 2>/dev/null; echo 1024 > /dev/uclamp/top-app/uclamp.min 2>/dev/null; echo 1024 > /dev/uclamp/top-app/uclamp.max 2>/dev/null; cutoolbox mode performance 2>/dev/null; cuprumturbo -m performance 2>/dev/null; echo 'performance' > /data/local/tmp/pixel_cpu_mode"
                        else -> "cmd power set-mode 0 2>/dev/null; cmd power set-fixed-performance-mode-enabled false 2>/dev/null; for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo schedutil > \$g 2>/dev/null || echo sugov_ext > \$g 2>/dev/null; done; echo 0 > /dev/stune/top-app/schedtune.boost 2>/dev/null; echo 0 > /dev/uclamp/top-app/uclamp.min 2>/dev/null; echo 1024 > /dev/uclamp/top-app/uclamp.max 2>/dev/null; cutoolbox mode balance 2>/dev/null; cuprumturbo -m balance 2>/dev/null; echo 'default' > /data/local/tmp/pixel_cpu_mode"
                    }
                    val successMsg = when (mode) {
                        "saver" -> "CuprumTurbo 省电挡 (限制前台 CPU 抢占 & 降频省电)"
                        "performance" -> "CuprumTurbo 性能挡 (满血锁频 + 1024 uclamp 高能调度)"
                        else -> "CuprumTurbo 默认挡 (EAS 动态均衡调度)"
                    }
                    val onClick = fun() {
                        if (!RootUtils.hasRootPermission()) {
                            Toast.makeText(context, "请先授予 Root 权限", Toast.LENGTH_LONG).show()
                            return
                        }
                        currentCpuMode = mode
                        coroutineScope.launch(Dispatchers.IO) {
                            val result = RootUtils.executeCommand(cmd)
                            withContext(Dispatchers.Main) {
                                result.onSuccess {
                                    addLog(successMsg)
                                    Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                                }.onFailure { e ->
                                    val errMsg = e.message ?: "未知错误"
                                    addLog("失败: $errMsg")
                                    Toast.makeText(context, "执行失败: $errMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    if (currentCpuMode == mode) {
                        iOSButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onClick() }
                        ) { AutoSizeText(label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        iOSOutlineButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onClick() }
                        ) { AutoSizeText(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // DNS 优化
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("DNS 网络加密加速", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("一键防 DNS 劫持，加速域名解析与防弹窗", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                iOSButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier dns.alidns.com"
                            handleResult(context, ShizukuUtils.executeCommand(cmd), "阿里 DNS 已开启", addLog)
                        }
                    }
                ) { Text("阿里\nDNS", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                iOSButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier dot.pub"
                            handleResult(context, ShizukuUtils.executeCommand(cmd), "腾讯 DNS 已开启", addLog)
                        }
                    }
                ) { Text("腾讯\nDNS", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                iOSButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier dns.adguard.com"
                            handleResult(context, ShizukuUtils.executeCommand(cmd), "去广告 DNS 已开启", addLog)
                        }
                    }
                ) { Text("全局\n去广", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                iOSButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            val cmd = "settings put global private_dns_mode opportunistic"
                            handleResult(context, ShizukuUtils.executeCommand(cmd), "默认 DNS 已恢复", addLog)
                        }
                    }
                ) { Text("恢复\n默认", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // LSPosed 桌面定制
    var dt2sEnabled by remember { mutableStateOf(false) }
    var hideSearchEnabled by remember { mutableStateOf(false) }
    var hideGestureLineEnabled by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("xposed_prefs", Context.MODE_PRIVATE)
        dt2sEnabled = prefs.getBoolean("dt2s", false)
        hideSearchEnabled = prefs.getBoolean("hide_search_bar", false)
        hideGestureLineEnabled = prefs.getBoolean("hide_gesture_line", false)
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("LSPosed 原生桌面定制", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("仅支持 Vector / LSPosed 框架（Zygisk 刷入），适配 Android 17 Pixel", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Text("原版 Xposed / EdXposed / Dreamland 等框架不生效", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val onDt2sClick = {
                    val newValue = !dt2sEnabled
                    dt2sEnabled = newValue
                    persistXposedToggle(context, "dt2s", newValue)
                    Toast.makeText(context, if (newValue) "双击锁屏已开启，桌面已自动重启" else "双击锁屏已关闭，桌面已自动重启", Toast.LENGTH_LONG).show()
                }
                if (dt2sEnabled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onDt2sClick) {
                        Text("双击锁屏\n(已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onDt2sClick) {
                        Text("双击锁屏\n(未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }

                val onHideSearchClick = {
                    val newValue = !hideSearchEnabled
                    hideSearchEnabled = newValue
                    persistXposedToggle(context, "hide_search_bar", newValue)
                    Toast.makeText(context, if (newValue) "隐藏搜索框已开启，桌面已自动重启" else "隐藏搜索框已关闭，桌面已自动重启", Toast.LENGTH_LONG).show()
                }
                if (hideSearchEnabled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onHideSearchClick) {
                        Text("去搜索框\n(已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onHideSearchClick) {
                        Text("去搜索框\n(未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }

                val onHideGestureLineClick = {
                    val newValue = !hideGestureLineEnabled
                    hideGestureLineEnabled = newValue
                    persistXposedToggle(context, "hide_gesture_line", newValue, restart = false)
                    showRebootDialog = true
                }
                if (hideGestureLineEnabled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onHideGestureLineClick) {
                        Text("去小白条\n(已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onHideGestureLineClick) {
                        Text("去小白条\n(未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text("需重启手机生效") },
            text = { Text("「去小白条」开关已写入，需重启手机后才会生效。") },
            confirmButton = {
                TextButton(onClick = {
                    showRebootDialog = false
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        RootUtils.executeCommand("reboot")
                    }
                }) { Text("立即重启") }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text("取消") }
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    // 微信 / 支付宝指纹支付 (FingerprintPay)
    var isWeChatPayActive by remember { mutableStateOf(false) }
    var isAlipayActive by remember { mutableStateOf(false) }
    var showFingerprintRebootDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val wechatRes = com.example.pixeltoolbox.utils.RootUtils.executeCommand("ls /data/adb/modules/ 2>/dev/null | grep xfingerprint-pay-wechat; cat /data/system/pixeltoolbox_wechat_fp.xml 2>/dev/null")
            val alipayRes = com.example.pixeltoolbox.utils.RootUtils.executeCommand("ls /data/adb/modules/ 2>/dev/null | grep xfingerprint-pay-alipay; cat /data/system/pixeltoolbox_alipay_fp.xml 2>/dev/null")
            isWeChatPayActive = wechatRes.getOrDefault("").isNotEmpty()
            isAlipayActive = alipayRes.getOrDefault("").isNotEmpty()
        }
    }

    var isWeChatModuleInstalled by remember { mutableStateOf(false) }
    var isAlipayModuleInstalled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            isWeChatModuleInstalled = RootUtils.isWeChatModuleInstalled()
            isAlipayModuleInstalled = RootUtils.isAlipayModuleInstalled()
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("微信 / 支付宝指纹支付 (v6.1.0)", style = MaterialTheme.typography.titleLarge, color = iOSLabel, modifier = Modifier.weight(1f))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = if (isWeChatModuleInstalled || isAlipayModuleInstalled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ) {
                    Text(
                        if (isWeChatModuleInstalled || isAlipayModuleInstalled) "Zygisk 硬件级 🟢" else "未激活 🔴",
                        color = if (isWeChatModuleInstalled || isAlipayModuleInstalled) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text("支持最新 FingerprintPay v6.1.0 驱动，实时检测 Magisk / KernelSU / APatch 模块状态！兼具 Toolbox 内置 Xposed 自动兜底", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            
            Spacer(modifier = Modifier.height(14.dp))
            Text("步骤一：刷入底层 Zygisk 指纹支付驱动", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = iOSBlue)
            Spacer(modifier = Modifier.height(6.dp))

            // 两个独立模块刷入按钮：微信独立模块与支付宝独立模块（根据底层模块目录存在与否实时高亮）
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val onDeployWeChatModule = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val res = RootUtils.installZygiskPayModuleFromAssets(context, "zygisk_pay_wechat.zip")
                        withContext(Dispatchers.Main) {
                            if (res.isSuccess) {
                                isWeChatModuleInstalled = true
                                Toast.makeText(context, res.getOrNull(), Toast.LENGTH_LONG).show()
                                showFingerprintRebootDialog = true
                            } else {
                                Toast.makeText(context, "安装失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                if (isWeChatModuleInstalled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = { onDeployWeChatModule() }) {
                        Text("🟢 微信指纹模块\n(已刷入/点击覆盖刷入)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = { onDeployWeChatModule() }) {
                        Text("⚡ 刷入微信指纹模块", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }

                val onDeployAlipayModule = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val res = RootUtils.installZygiskPayModuleFromAssets(context, "zygisk_pay_alipay.zip")
                        withContext(Dispatchers.Main) {
                            if (res.isSuccess) {
                                isAlipayModuleInstalled = true
                                Toast.makeText(context, res.getOrNull(), Toast.LENGTH_LONG).show()
                                showFingerprintRebootDialog = true
                            } else {
                                Toast.makeText(context, "安装失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                if (isAlipayModuleInstalled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = { onDeployAlipayModule() }) {
                        Text("🟢 支付宝指纹模块\n(已刷入/点击覆盖刷入)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = { onDeployAlipayModule() }) {
                        Text("⚡ 刷入支付宝指纹模块", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("步骤二：开启应用指纹环境 (兼具 Xposed 自动兜底)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = iOSBlue)
            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val onToggleWeChatFP = {
                    val target = !isWeChatPayActive
                    isWeChatPayActive = target
                    coroutineScope.launch(Dispatchers.IO) {
                        if (target) {
                            RootUtils.executeCommand("echo 'enabled=true' > /data/system/pixeltoolbox_wechat_fp.xml")
                        } else {
                            RootUtils.executeCommand("rm -f /data/system/pixeltoolbox_wechat_fp.xml")
                        }
                    }
                    showFingerprintRebootDialog = true
                }
                if (isWeChatPayActive) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onToggleWeChatFP) {
                        Text("微信指纹 (已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onToggleWeChatFP) {
                        Text("微信指纹 (未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }

                val onToggleAlipayFP = {
                    val target = !isAlipayActive
                    isAlipayActive = target
                    coroutineScope.launch(Dispatchers.IO) {
                        if (target) {
                            RootUtils.executeCommand("echo 'enabled=true' > /data/system/pixeltoolbox_alipay_fp.xml")
                        } else {
                            RootUtils.executeCommand("rm -f /data/system/pixeltoolbox_alipay_fp.xml")
                        }
                    }
                    showFingerprintRebootDialog = true
                }
                if (isAlipayActive) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onToggleAlipayFP) {
                        Text("支付宝指纹 (已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onToggleAlipayFP) {
                        Text("支付宝指纹 (未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    if (showFingerprintRebootDialog) {
        AlertDialog(
            onDismissRequest = { showFingerprintRebootDialog = false },
            title = { Text("需重启手机以应用指纹环境", fontWeight = FontWeight.Bold) },
            text = { Text("指纹支付环境已成功写入！请在【重启手机】后，分别进入微信 (设置->通用->开启指纹支付) 与支付宝 (设置->安全->指纹设置) 开通指纹付款。") },
            confirmButton = {
                TextButton(onClick = {
                    showFingerprintRebootDialog = false
                    coroutineScope.launch(Dispatchers.IO) {
                        RootUtils.executeCommand("reboot")
                    }
                }) { Text("立即重启", color = iOSBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showFingerprintRebootDialog = false }) { Text("稍后手动重启") }
            }
        )
    }


    Spacer(modifier = Modifier.height(16.dp))
    // 验证码自动填写
    var smsCodeEnabled by remember { mutableStateOf(false) }
    var smsCodeOverlayGranted by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("xposed_prefs", Context.MODE_PRIVATE)
        smsCodeEnabled = prefs.getBoolean("sms_code", false)
        smsCodeOverlayGranted = PermissionChecks.hasOverlayPermission(context)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("验证码自动填写", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("收到验证码短信时自动复制到剪贴板并弹悬浮窗提示", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Text("需重启手机后生效（hook 注入系统短信进程）", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val onSmsCodeClick = {
                    val newValue = !smsCodeEnabled
                    smsCodeEnabled = newValue
                    persistXposedToggle(context, "sms_code", newValue, restart = false)
                    Toast.makeText(context, if (newValue) "验证码自动填写已开启，重启手机后生效" else "验证码自动填写已关闭，重启手机后生效", Toast.LENGTH_LONG).show()
                }
                if (smsCodeEnabled) {
                    iOSButton(modifier = Modifier.weight(1f), onClick = onSmsCodeClick) {
                        Text("验证码填写\n(已开启)", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = onSmsCodeClick) {
                        Text("验证码填写\n(未开启)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
                if (smsCodeOverlayGranted) {
                    iOSOutlineButton(modifier = Modifier.weight(1f), onClick = {}) {
                        Text("悬浮窗\n(已授权)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                } else {
                    iOSButton(modifier = Modifier.weight(1f), onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }) {
                        Text("授权悬浮窗", color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // Pixel 震动反馈
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Pixel 触觉震动强度调校", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Text("调整打字、触摸与通知系统级触感震动百分比", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "关闭", 1 to "柔和", 2 to "标准", 3 to "强劲").forEach { (level, label) ->
                    val onSelect = fun() {
                        if (!ShizukuUtils.hasShizukuPermission()) {
                            Toast.makeText(context, "请先授权 Shizuku 权限", Toast.LENGTH_LONG).show()
                            return
                        }
                        if (level > 0) {
                            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                            } else {
                                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                            }
                            if (vib == null) {
                                Toast.makeText(context, "设备无震动器", Toast.LENGTH_LONG).show()
                                return
                            }
                            if (!vib.hasVibrator()) {
                                Toast.makeText(context, "震动器不支持", Toast.LENGTH_LONG).show()
                                return
                            }
                            try {
                                when (level) {
                                    1 -> vib.vibrate(VibrationEffect.createOneShot(30L, 128))
                                    2 -> vib.vibrate(VibrationEffect.createWaveform(
                                        longArrayOf(30, 40, 60), intArrayOf(128, 0, 192), -1))
                                    3 -> vib.vibrate(VibrationEffect.createWaveform(
                                        longArrayOf(30, 40, 80, 50, 120), intArrayOf(128, 0, 200, 0, 255), -1))
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "震动失败: ${e.message}", Toast.LENGTH_LONG).show()
                                return
                            }
                        }
                        currentVibLevel = level
                        coroutineScope.launch {
                            Toast.makeText(context, "正在设置 $label...", Toast.LENGTH_SHORT).show()
                            val cmd = "settings put secure haptic_feedback_intensity $level 2>/dev/null; " +
                                      "settings put system haptic_feedback_intensity $level 2>/dev/null; " +
                                      "settings put system hardware_haptic_feedback_intensity $level 2>/dev/null"
                            handleResult(context, ShizukuUtils.executeCommand(cmd), "触控强度已设置: $label", addLog)
                        }
                    }
                    if (currentVibLevel == level) {
                        iOSButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect() }
                        ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        iOSOutlineButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect() }
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // 气密性测试
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("气密性测试", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Spacer(modifier = Modifier.height(12.dp))
            iOSButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenBarometerTest
            ) { Text("进入测试页面", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // DPI 定制
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("屏幕深度定制", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dpiInput,
                    onValueChange = setDpiInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入新 DPI (如 420)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(modifier = Modifier.width(8.dp))
                iOSButton(
                    onClick = {
                        coroutineScope.launch {
                            val dpi = dpiInput.toIntOrNull()
                            if (dpi != null && dpi in 100..1000) {
                                handleResult(context, ShizukuUtils.executeCommand("wm density $dpi"), "DPI 已修改", addLog)
                            } else {
                                Toast.makeText(context, "请输入有效的 DPI 值 (100 -> 1000)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text("应用", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            iOSOutlineButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    coroutineScope.launch {
                        handleResult(context, ShizukuUtils.executeCommand("wm density reset"), "DPI 已重置", addLog)
                    }
                }
            ) { Text("恢复默认", fontWeight = FontWeight.SemiBold) }
        }
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通话录音", style = MaterialTheme.typography.titleLarge, color = iOSLabel)
                    Text("Root 通话录音 · 原生 HD 48kHz 双向清晰画质", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
                }
                IconButton(onClick = {
                    context.startActivity(Intent(context, CallRecordingSettingsActivity::class.java))
                }) {
                    Icon(Icons.Filled.Settings, contentDescription = "通话录音设置", tint = iOSBlue)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val prefs = remember { AppPreferences(context) }
            var recorderEnabled by remember { mutableStateOf(prefs.isCallRecorderEnabled()) }
            var autoIncoming by remember { mutableStateOf(prefs.isAutoRecordIncomingEnabled()) }
            var autoOutgoing by remember { mutableStateOf(prefs.isAutoRecordOutgoingEnabled()) }
            var rootReady by remember { mutableStateOf(ManageOngoingCalls.isGranted(context)) }
            var granting by remember { mutableStateOf(false) }
            ImsGroupSwitchRow(
                label = "录音总开关",
                checked = recorderEnabled,
                textColor = iOSBlue,
                onCheckedChange = { enable ->
                    recorderEnabled = enable
                    prefs.setCallRecorderEnabled(enable)
                    if (enable && !rootReady) {
                        coroutineScope.launch {
                            rootReady = ManageOngoingCalls.grant(context)
                        }
                    }
                }
            )
            ImsGroupSwitchRow(
                label = "来电自动录音",
                checked = autoIncoming,
                textColor = iOSBlue,
                onCheckedChange = { enable ->
                    autoIncoming = enable
                    prefs.setAutoRecordIncomingEnabled(enable)
                }
            )
            ImsGroupSwitchRow(
                label = "去电自动录音",
                checked = autoOutgoing,
                textColor = iOSBlue,
                onCheckedChange = { enable ->
                    autoOutgoing = enable
                    prefs.setAutoRecordOutgoingEnabled(enable)
                }
            )
            if (recorderEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                if (rootReady) {
                    Text(
                        "Root 通话录音权限已就绪（HD 48kHz 原生超清音质）",
                        color = iOSBlue, style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "需要 Root 授权「管理进行中的通话」(manage_ongoing_calls) 与底层录音权限",
                        color = iOSOrange, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    iOSButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            granting = true
                            coroutineScope.launch {
                                val ok = ManageOngoingCalls.grant(context)
                                granting = false
                                rootReady = ok
                                Toast.makeText(context, if (ok) "已通过 Root 授权，自动录音已就绪" else "授权失败，请确认已授予 Root 权限", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text(if (granting) "授权中..." else "一键 Root 授权", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

fun isServiceRunning(context: android.content.Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

/**
 * 写入 LSPosed 开关并用 root 同步到 /data/system/pixeltoolbox_xposed.xml，
 * 让 system 进程（SystemUI/桌面）可跨进程文件直读。
 */
private fun persistXposedToggle(context: Context, key: String, value: Boolean, restart: Boolean = true) {
    val prefs = context.getSharedPreferences("xposed_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(key, value).commit()

    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val vectorMsg = com.example.pixeltoolbox.utils.RootUtils.ensureVectorModule()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when {
                vectorMsg.startsWith("未检测到 Vector") ->
                    android.widget.Toast.makeText(
                        context,
                        "此功能需要先安装 Vector 框架（Magisk 模块）并重启手机才能生效",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                vectorMsg.startsWith("Vector 已就绪") ->
                    android.widget.Toast.makeText(context, vectorMsg, android.widget.Toast.LENGTH_LONG).show()
                else -> android.widget.Toast.makeText(context, vectorMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val restartTarget = if (key == "hide_gesture_line") "com.android.systemui"
                        else "com.google.android.apps.nexuslauncher"
    val effectiveTarget = if (key == "sms_code") null else (if (restart) restartTarget else null)
    syncXposedConfig(context, effectiveTarget)
}

/**
 * 把 xposed_prefs 里所有开关状态（三个桌面开关 + 验证码 + 强制小窗）统一写入
 * /data/system/pixeltoolbox_xposed.xml（key=value 逐行），并可选强停目标进程。
 */
private fun syncXposedConfig(context: Context, restartTarget: String?) {
    val prefs = context.getSharedPreferences("xposed_prefs", Context.MODE_PRIVATE)
    val dt2s = prefs.getBoolean("dt2s", false)
    val hideSearch = prefs.getBoolean("hide_search_bar", false)
    val hideGesture = prefs.getBoolean("hide_gesture_line", false)
    val smsCode = prefs.getBoolean("sms_code", false)

    val lines = mutableListOf<String>()
    lines += "dt2s=$dt2s"
    lines += "hide_search_bar=$hideSearch"
    lines += "hide_gesture_line=$hideGesture"
    lines += "sms_code=$smsCode"

    val echoParts = lines.mapIndexed { i, line ->
        val op = if (i == 0) ">" else ">>"
        "echo '$line' $op /data/system/pixeltoolbox_xposed.xml"
    }
    val cmd = (echoParts + listOf(
        "chmod 644 /data/system/pixeltoolbox_xposed.xml",
        "chcon u:object_r:system_file:s0 /data/system/pixeltoolbox_xposed.xml"
    )).joinToString("; ")

    val fullCmd = if (restartTarget != null) "$cmd; am force-stop $restartTarget" else cmd
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        RootUtils.executeCommand(fullCmd)
    }
}

@Composable
fun BatteryData(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = iOSSecondaryLabel)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = iOSLabel)
    }
}

@Composable
fun BatteryInfoCard(
    batTemp: Float, batVolt: Int, batteryStatus: Int, batCurrentNA: Int
) {
    var localVoltage by remember { mutableStateOf(batVolt) }
    var localCurrent by remember { mutableStateOf(batCurrentNA) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val voltResult = RootUtils.executeCommand("cat /sys/class/power_supply/battery/voltage_now")
                voltResult.onSuccess { v ->
                    val uv = v.trim().toIntOrNull()
                    if (uv != null && uv > 0) localVoltage = uv
                }
                val curResult = RootUtils.executeCommand("cat /sys/class/power_supply/battery/current_now")
                curResult.onSuccess { c ->
                    val ua = c.trim().toIntOrNull()
                    if (ua != null) localCurrent = ua
                }
            }
        }
    }

    LaunchedEffect(batVolt) {
        if (batVolt > 0) localVoltage = batVolt * 1000
    }

    LaunchedEffect(batCurrentNA) {
        if (batCurrentNA != 0) localCurrent = batCurrentNA / 1000
    }

    val voltageV: String = if (localVoltage > 0) {
        String.format(java.util.Locale.US, "%.3fV", localVoltage / 1000000f)
    } else "--"

    val currentStr: String = if (localCurrent != 0) {
        val ma = kotlin.math.abs(localCurrent / 1000f)
        if (ma >= 1000f) String.format(java.util.Locale.US, "%.2fA", ma / 1000f) else String.format(java.util.Locale.US, "%.0fmA", ma)
    } else "--"

    val powerW: String = if (localVoltage > 0 && localCurrent != 0) {
        val v = localVoltage / 1000000f
        val a = kotlin.math.abs(localCurrent / 1000f) / 1000f
        String.format(java.util.Locale.US, "%.2fW", v * a)
    } else "--"

    val tempStr = "$batTemp °C"
    val statusStr = when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "已充满"
        else -> "未知($batteryStatus)"
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("电池实时信息", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = iOSLabel)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryData("温度", tempStr)
                BatteryData("状态", statusStr)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryData("电压", voltageV)
                BatteryData("电流", currentStr)
                BatteryData("功率", powerW)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("每 2 秒刷新", fontSize = 10.sp, color = iOSSecondaryLabel, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun handleResult(context: Context, result: Result<String>, successMsg: String, addLog: (String) -> Unit) {
    result.onSuccess {
        addLog(successMsg)
        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
    }.onFailure { e ->
        val errorMsg = "操作失败: ${e.message}"
        addLog(errorMsg)
        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
    }
}
