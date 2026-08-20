/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.ui.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pixeltoolbox.shizuku.ShizukuUtils
import com.example.pixeltoolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 方案 A（组件级硬裁撤）：核心系统服务保护白名单。
 * 强制过滤保护最核心的系统应用，防止误停导致掉网、锁屏崩溃或开机黑屏。
 */
private val CORE_SYSTEM_WHITELIST = setOf(
    // ==== 核心系统进程（禁用一个即可能引发 SystemUI/SystemServer 崩溃或开机黑屏）====
    "android",
    "com.android.systemui",
    "com.google.android.apps.nexuslauncher",
    "com.android.launcher3",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.settings",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.bluetooth",
    "com.example.pixeltoolbox",
    "moe.shizuku.privileged.api",

    // ==== 系统 ContentProvider 宿主（system_server 启动即绑定，禁用必崩/bootloop）====
    "com.android.providers.contacts",
    "com.android.providers.calendar",
    "com.android.providers.blockednumber",
    "com.android.providers.contactkeys",
    "com.android.providers.downloads",
    "com.android.providers.downloads.ui",
    "com.android.providers.partnerbookmarks",
    "com.android.providers.settings",
    "com.android.providers.telephony",
    "com.android.providers.userdictionary",
    "com.google.android.providers.media.module",

    // ==== 系统框架与核心服务 ====
    "com.android.location.fused",          // 定位服务
    "com.android.mms.service",             // 彩信服务
    "com.android.nfc",
    "com.google.android.nfc",
    "com.android.se",                      // Secure Element
    "com.android.carrierdefaultapp",       // 运营商默认应用
    "com.android.certinstaller",           // 证书安装
    "com.android.emergency",               // 紧急信息
    "com.android.externalstorage",         // 外部存储 Provider
    "com.android.keychain",                // 系统密钥链
    "com.android.managedprovisioning",     // 设备管理配置
    "com.android.printspooler",            // 打印服务
    "com.android.proxyhandler",            // 代理处理
    "com.android.sharedstoragebackup",     // 存储备份
    "com.android.simappdialog",            // SIM 应用对话框
    "com.android.stk",                     // SIM 工具包
    "com.android.vpndialogs",              // VPN 对话框
    "com.android.intentresolver",          // 意图解析器（系统 UI）
    "com.android.contactspicker",          // 联系人选择器
    "com.android.cellbroadcastreceiver",   // 小区广播/紧急警报
    "com.android.ons",                     // 运营商名称显示
    "com.android.qns",
    "com.android.telephony.imsmedia",      // IMS 媒体（VoLTE/VoNR 依赖）

    // ==== Google 核心组件 ====
    "com.google.android.contacts",         // 联系人
    "com.google.android.permissioncontroller", // 权限控制（禁用会导致系统异常）
    "com.google.android.packageinstaller", // 包安装器
    "com.google.android.setupwizard",      // 设置向导
    "com.google.android.inputmethod.latin",// Gboard 键盘
    "com.google.android.tts",              // 语音合成
    "com.google.android.documentsui",      // 文件选择器
    "com.google.android.ext.services",     // 系统扩展服务
    "com.google.android.ext.shared",
    "com.google.android.networkstack",     // 网络栈
    "com.google.android.networkstack.tethering",
    "com.google.android.telephony",        // 电话框架
    "com.google.android.euicc",            // eSIM
    "com.google.android.configupdater",
    "com.google.android.modulemetadata",
    "com.google.android.pixelsystemservice", // Pixel 系统服务
    "com.google.android.cellbroadcastreceiver",
    "com.google.android.cellbroadcastservice",
    "com.google.android.webview"           // WebView（禁用会导致大量应用崩溃）
)

/**
 * 一般系统应用：可显示但禁止一键禁用（含单条开关拦截）。
 * 禁用其自启广播可能影响系统体验（媒体库/智能服务/同步等），保留人工开关兜底。
 * 核心白名单之外的系统应用视为"不重要系统应用"，允许一键禁用。
 */
private val GENERAL_SYSTEM_WHITELIST = setOf(
    "com.android.mtp",                           // MTP 文件传输服务
    "com.google.android.mediaprovider",          // 媒体提供者（相册/媒体库依赖）
    "com.google.android.apps.wallpaper",         // 动态壁纸/锁屏壁纸服务
    "com.google.android.apps.subscriptions.red", // Play 订阅服务
    "com.google.android.syncadapters.contacts",  // 联系人同步
    "com.google.android.syncadapters.calendar",  // 日历同步
    "com.google.android.gms.location.history",   // 位置历史服务
    "com.google.android.as",                     // Android System Intelligence（智能功能依赖）
    "com.google.android.apps.restore",           // 云备份/恢复服务
    "com.google.android.pixel.setupwizard",      // Pixel 设置向导组件
    "com.google.android.dialer",                 // 电话（受保护：禁自启仅影响开机广播，可单条手动禁）
    "com.google.android.apps.messaging",         // 短信
    "com.google.android.GoogleCamera",           // 相机
    "com.google.android.googlequicksearchbox",   // 搜索助手
    "com.google.android.deskclock",              // 时钟/闹钟
    "com.google.android.apps.photos"             // 相册（Google Photos 自动同步依赖）
)

data class BootReceiverItem(
    val packageName: String,
    val appLabel: String,
    val fullComponent: String,
    val componentShort: String,
    val isSystem: Boolean,
    val isGeneralSystem: Boolean = false,
    var isDisabled: Boolean,
    val iconBitmap: android.graphics.Bitmap?
)

enum class BootFilterTab(val label: String) {
    ALL("全部"),
    USER("第三方"),
    SYSTEM("系统应用"),
    DISABLED_USER("第三方已限制 🚫"),
    DISABLED_SYSTEM("系统已限制 🚫")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootManagerScreen(context: Context, addLog: (String) -> Unit, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var receivers by remember { mutableStateOf<List<BootReceiverItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(BootFilterTab.ALL) }
    var processingComponent by remember { mutableStateOf<String?>(null) }
    var isBatchProcessing by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    fun reloadScan() {
        isLoading = true
        coroutineScope.launch {
            val items = withContext(Dispatchers.IO) { scanBootReceivers(context) }
            receivers = items
            isLoading = false
            val disabledCount = items.count { it.isDisabled }
            addLog("自启广播扫描完成：发现 ${items.size} 个接收组件，${disabledCount} 个已禁用自启")
        }
    }

    LaunchedEffect(Unit) {
        reloadScan()
    }

    val filteredList = remember(receivers, searchQuery, activeTab) {
        receivers.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                    item.appLabel.contains(searchQuery, ignoreCase = true) ||
                    item.packageName.contains(searchQuery, ignoreCase = true) ||
                    item.componentShort.contains(searchQuery, ignoreCase = true)

            val matchesTab = when (activeTab) {
                // 默认列表不显示已禁用的项，已禁用的统一收拢到"已限制"tab 展示
                BootFilterTab.ALL -> !item.isDisabled
                BootFilterTab.USER -> !item.isSystem && !item.isDisabled
                BootFilterTab.SYSTEM -> item.isSystem && !item.isDisabled
                BootFilterTab.DISABLED_USER -> !item.isSystem && item.isDisabled
                BootFilterTab.DISABLED_SYSTEM -> item.isSystem && item.isDisabled
            }

            matchesSearch && matchesTab
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(iOSBackground)
            .statusBarsPadding()
    ) {
        // 顶部导航栏 - iOS 风格
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = iOSLabel)
            }
            Text(
                "自启管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = iOSLabel,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { reloadScan() },
                enabled = !isLoading && !isBatchProcessing
            ) {
                Text("刷新", color = iOSBlue, fontSize = 14.sp)
            }
        }

        // 说明标语
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
            Text("组件级禁用开机自启广播 (pm disable)，不影响正常使用和消息推送", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Text("已安全排除 SystemUI、核心桌面、系统框架等关键核心服务", style = MaterialTheme.typography.labelSmall, color = iOSGreen)
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = { Text("搜索应用或接收器组件...", color = iOSSecondaryLabel) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = iOSSecondaryLabel) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        // 筛选 Segment 选项（可横向滑动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BootFilterTab.values().forEach { tab ->
                val isSelected = activeTab == tab
                if (isSelected) {
                    iOSButton(
                        onClick = { activeTab = tab }
                    ) {
                        Text(tab.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    iOSOutlineButton(
                        onClick = { activeTab = tab }
                    ) {
                        Text(tab.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 批量控制条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            iOSOutlineButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (isBatchProcessing || isLoading) {
                        Toast.makeText(context, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
                        return@iOSOutlineButton
                    }
                    // 可禁对象：未禁用的第三方 + 未禁用的"不重要系统应用"；一般系统应用自动跳过
                    val toDisable = filteredList.filter {
                        !it.isDisabled && (!it.isSystem || !it.isGeneralSystem)
                    }
                    val skippedGeneral = filteredList.count { !it.isDisabled && it.isSystem && it.isGeneralSystem }
                    if (toDisable.isEmpty()) {
                        val msg = if (skippedGeneral > 0)
                            "本页仅剩受保护的一般系统应用，无法一键禁用"
                        else
                            "当前视图中没有可禁用的应用"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        return@iOSOutlineButton
                    }
                    isBatchProcessing = true
                    coroutineScope.launch(Dispatchers.IO) {
                        var success = 0
                        var failure = 0
                        for (item in toDisable) {
                            // 组件级 pm disable-user 在 Android 15+ 静默失效（实测返回 new state: default 但未生效），
                            // 统一改用全局 pm disable，真机验证可正常写入 COMPONENT_ENABLED_STATE_DISABLED
                            val cmd = "pm disable '${item.fullComponent}'"
                            try {
                                val r = ShizukuUtils.executeCommand(cmd)
                                if (r.isSuccess) success++ else failure++
                            } catch (e: Exception) {
                                failure++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isBatchProcessing = false
                            val skipNote = if (skippedGeneral > 0) "，跳过 $skippedGeneral 个受保护系统应用" else ""
                            val msg = if (failure == 0)
                                "已批量禁用 $success 个应用开机自启$skipNote"
                            else
                                "批量禁用完成：成功 $success 个，失败 $failure 个$skipNote"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            reloadScan()
                        }
                    }
                }
            ) {
                Text("一键禁用本页", style = MaterialTheme.typography.labelMedium, color = iOSRed)
            }

            iOSButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (isBatchProcessing || isLoading) {
                        Toast.makeText(context, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
                        return@iOSButton
                    }
                    val toEnable = filteredList.filter { it.isDisabled }
                    if (toEnable.isEmpty()) {
                        Toast.makeText(context, "当前视图中没有已禁用的应用", Toast.LENGTH_SHORT).show()
                        return@iOSButton
                    }
                    isBatchProcessing = true
                    coroutineScope.launch(Dispatchers.IO) {
                        var success = 0
                        var failure = 0
                        for (item in toEnable) {
                            val cmd = "pm enable '${item.fullComponent}'"
                            try {
                                val r = ShizukuUtils.executeCommand(cmd)
                                if (r.isSuccess) success++ else failure++
                            } catch (e: Exception) {
                                failure++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isBatchProcessing = false
                            val msg = if (failure == 0)
                                "已批量恢复 $success 个应用开机自启"
                            else
                                "批量恢复完成：成功 $success 个，失败 $failure 个"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            reloadScan()
                        }
                    }
                }
            ) {
                Text("一键恢复本页", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 内容列表区
        if (isLoading || isBatchProcessing) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = iOSBlue, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if (isBatchProcessing) "正在批量写入自启控制指令..." else "正在安全扫描开机广播接收器...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = iOSSecondaryLabel
                    )
                }
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("未发现符合条件的自启应用", style = MaterialTheme.typography.bodyMedium, color = iOSSecondaryLabel)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
            ) {
                items(filteredList, key = { it.fullComponent }) { item ->
                    val isProcessing = processingComponent == item.fullComponent

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .alpha(if (item.isDisabled) 0.65f else 1.0f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 应用图标
                            if (item.iconBitmap != null) {
                                Image(
                                    bitmap = item.iconBitmap.asImageBitmap(),
                                    contentDescription = item.appLabel,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(iOSSeparator.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("?", style = MaterialTheme.typography.titleMedium, color = iOSSecondaryLabel)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.appLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (item.isDisabled) iOSRed else iOSLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when {
                                            item.isSystem && item.isGeneralSystem -> Color(0xFFFFF3E0)
                                            item.isSystem -> Color(0xFFFFE0B2)
                                            else -> Color(0xFFE8F5E9)
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                item.isSystem && item.isGeneralSystem -> "系统·受保护"
                                                item.isSystem -> "系统"
                                                else -> "第三方"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                item.isSystem -> Color(0xFFE65100)
                                                else -> Color(0xFF2E7D32)
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.componentShort,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.isDisabled) iOSRed.copy(alpha = 0.7f) else iOSSecondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = iOSBlue, strokeWidth = 2.dp)
                            } else {
                                Switch(
                                    checked = !item.isDisabled,
                                    onCheckedChange = { enable ->
                                        // 一般系统应用受保护：禁止通过单个开关禁用，避免误停影响系统体验
                                        if (!enable && item.isSystem && item.isGeneralSystem) {
                                            Toast.makeText(
                                                context,
                                                "一般系统应用受保护，无法禁用，避免影响系统体验",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                        processingComponent = item.fullComponent
                                        coroutineScope.launch {
                                            val cmd = if (enable)
                                                "pm enable '${item.fullComponent}'"
                                            else
                                                "pm disable '${item.fullComponent}'"

                                            val result = withContext(Dispatchers.IO) {
                                                ShizukuUtils.executeCommand(cmd)
                                            }
                                            processingComponent = null
                                            if (result.isSuccess) {
                                                val targetState = !enable
                                                receivers = receivers.map {
                                                    if (it.fullComponent == item.fullComponent) it.copy(isDisabled = targetState) else it
                                                }
                                                val msg = if (targetState) "已禁用 ${item.appLabel} 开机自启" else "已恢复 ${item.appLabel} 开机自启"
                                                addLog(msg)
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "操作失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = iOSBlue,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = iOSRed.copy(alpha = 0.6f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun scanBootReceivers(context: Context): List<BootReceiverItem> {
    val pm = context.packageManager

    // 查询所有注册了 BOOT_COMPLETED 的广播接收器组件（含已被禁用的组件）
    val bootIntent = Intent("android.intent.action.BOOT_COMPLETED")
    val resolveList = try {
        pm.queryBroadcastReceivers(
            bootIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DIRECT_BOOT_AWARE or
                    PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        )
    } catch (e: Exception) {
        emptyList()
    }

    val items = mutableListOf<BootReceiverItem>()
    val seen = mutableSetOf<String>()

    for (resolveInfo in resolveList) {
        val activityInfo = resolveInfo.activityInfo ?: continue
        val pkg = activityInfo.packageName
        val receiverName = activityInfo.name ?: continue

        // 排除核心系统保护白名单
        if (pkg in CORE_SYSTEM_WHITELIST || pkg.startsWith("android.") || pkg == "com.xiaomi.xmsf") continue
        // manifest 中已永久禁用的组件无自启能力，无需管理
        if (!activityInfo.enabled) continue

        val component = "$pkg/$receiverName"
        if (!seen.add(component)) continue

        val isSystem = (activityInfo.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM)) != 0
        val label = try {
            activityInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        } catch (e: Exception) { pkg }

        // 组件级 enabled 状态判断（pm disable-user 后此处返回 DISABLED_USER）
        var isDisabled = false
        try {
            val state = pm.getComponentEnabledSetting(ComponentName(pkg, receiverName))
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                isDisabled = true
            }
        } catch (_: Exception) {}

        val icon: android.graphics.Bitmap? = try {
            val drawable = activityInfo.applicationInfo?.loadIcon(pm)
            if (drawable != null) {
                val bmp = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, 72, 72)
                drawable.draw(canvas)
                bmp
            } else null
        } catch (e: Exception) { null }

        items.add(
            BootReceiverItem(
                packageName = pkg,
                appLabel = label,
                fullComponent = component,
                componentShort = receiverName.substringAfterLast('.'),
                isSystem = isSystem,
                isGeneralSystem = isSystem && pkg in GENERAL_SYSTEM_WHITELIST,
                isDisabled = isDisabled,
                iconBitmap = icon
            )
        )
    }

    items.sortWith(compareBy({ !it.isDisabled }, { it.isSystem }, { it.appLabel.lowercase() }))
    return items
}
