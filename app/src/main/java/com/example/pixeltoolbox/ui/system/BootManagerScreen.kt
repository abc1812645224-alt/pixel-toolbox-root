/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.ui.system

import android.content.Context
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
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.example.pixeltoolbox",
    "moe.shizuku.privileged.api"
)

data class BootReceiverItem(
    val packageName: String,
    val appLabel: String,
    val fullComponent: String,
    val componentShort: String,
    val isSystem: Boolean,
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
            addLog("自启广播扫描完成：发现 ${items.size} 个接收组件，${disabledCount} 个已限制自启")
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
                BootFilterTab.ALL -> true
                BootFilterTab.USER -> !item.isSystem
                BootFilterTab.SYSTEM -> item.isSystem
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
                "自启管理 (方案A 硬裁撤)",
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
            Text("硬裁撤组件 (pm disable) + AppOps 限制开机广播，不影响手动点击打开", style = MaterialTheme.typography.bodySmall, color = iOSSecondaryLabel)
            Text("已安全排除 SystemUI、电话、核心桌面、闹钟等关键核心服务", style = MaterialTheme.typography.labelSmall, color = iOSGreen)
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
                    if (isBatchProcessing || isLoading) return@iOSOutlineButton
                    val toDisable = filteredList.filter { !it.isDisabled }
                    if (toDisable.isEmpty()) {
                        Toast.makeText(context, "当前视图中没有可禁用的应用", Toast.LENGTH_SHORT).show()
                        return@iOSOutlineButton
                    }
                    isBatchProcessing = true
                    coroutineScope.launch(Dispatchers.IO) {
                        for (item in toDisable) {
                            val cmd = "pm disable '${item.fullComponent}' 2>/dev/null; cmd appops set ${item.packageName} RUN_IN_BACKGROUND deny"
                            ShizukuUtils.executeCommand(cmd)
                        }
                        withContext(Dispatchers.Main) {
                            isBatchProcessing = false
                            Toast.makeText(context, "已批量限制 ${toDisable.size} 个自启接收器", Toast.LENGTH_SHORT).show()
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
                    if (isBatchProcessing || isLoading) return@iOSButton
                    val toEnable = filteredList.filter { it.isDisabled }
                    if (toEnable.isEmpty()) {
                        Toast.makeText(context, "当前视图中没有已禁用的应用", Toast.LENGTH_SHORT).show()
                        return@iOSButton
                    }
                    isBatchProcessing = true
                    coroutineScope.launch(Dispatchers.IO) {
                        for (item in toEnable) {
                            val cmd = "pm enable '${item.fullComponent}' 2>/dev/null; cmd appops set ${item.packageName} RUN_IN_BACKGROUND allow"
                            ShizukuUtils.executeCommand(cmd)
                        }
                        withContext(Dispatchers.Main) {
                            isBatchProcessing = false
                            Toast.makeText(context, "已批量恢复 ${toEnable.size} 个自启接收器", Toast.LENGTH_SHORT).show()
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
                                        color = if (item.isSystem) Color(0xFFFFF3E0) else Color(0xFFE8F5E9)
                                    ) {
                                        Text(
                                            text = if (item.isSystem) "系统" else "第三方",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (item.isSystem) Color(0xFFE65100) else Color(0xFF2E7D32),
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
                                        processingComponent = item.fullComponent
                                        coroutineScope.launch {
                                            val cmd = if (enable)
                                                "cmd appops set ${item.packageName} RUN_IN_BACKGROUND allow; cmd appops set ${item.packageName} RUN_ANY_IN_BACKGROUND allow; pm enable '${item.packageName}' 2>/dev/null"
                                            else
                                                "cmd appops set ${item.packageName} RUN_IN_BACKGROUND deny; cmd appops set ${item.packageName} RUN_ANY_IN_BACKGROUND deny"

                                            val result = withContext(Dispatchers.IO) {
                                                ShizukuUtils.executeCommand(cmd)
                                            }
                                            processingComponent = null
                                            if (result.isSuccess) {
                                                val targetState = !enable
                                                receivers = receivers.map {
                                                    if (it.fullComponent == item.fullComponent) it.copy(isDisabled = targetState) else it
                                                }
                                                val msg = if (targetState) "已禁用 ${item.appLabel} 自启" else "已恢复 ${item.appLabel} 自启"
                                                addLog(msg)
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "操作失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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

    // 1. 查询当前 AppOps 后台自启限制列表 (RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND)
    val disabledAppOpsOutput = ShizukuUtils.executeCommand("cmd appops query-op RUN_IN_BACKGROUND ignore 2>/dev/null; cmd appops query-op RUN_IN_BACKGROUND deny 2>/dev/null; cmd appops query-op RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; cmd appops query-op RUN_ANY_IN_BACKGROUND deny 2>/dev/null")
        .getOrElse { "" }
    val appOpsDisabledPkgs = disabledAppOpsOutput.lines()
        .map { it.trim() }
        .filter { it.contains(".") && !it.contains(" ") }
        .toSet()

    // 2. 遍历手机上安装的所有应用包（第三方应用 + 非核心系统应用）
    val installedApps = try {
        pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (e: Exception) {
        emptyList()
    }

    val items = mutableListOf<BootReceiverItem>()
    for (appInfo in installedApps) {
        val pkg = appInfo.packageName

        // 排除核心系统保护白名单
        if (pkg in CORE_SYSTEM_WHITELIST || pkg.startsWith("android.") || pkg == "com.xiaomi.xmsf") continue

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = try { appInfo.loadLabel(pm).toString() } catch (e: Exception) { pkg }

        // 检查该应用后台自启是否处于限制状态
        var isDisabled = pkg in appOpsDisabledPkgs

        if (!isDisabled) {
            try {
                val state = pm.getApplicationEnabledSetting(pkg)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    isDisabled = true
                }
            } catch (_: Exception) {}
        }

        val icon: android.graphics.Bitmap? = try {
            val drawable = appInfo.loadIcon(pm)
            val bmp = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, 72, 72)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) { null }

        items.add(
            BootReceiverItem(
                packageName = pkg,
                appLabel = label,
                fullComponent = pkg,
                componentShort = pkg,
                isSystem = isSystem,
                isDisabled = isDisabled,
                iconBitmap = icon
            )
        )
    }

    items.sortWith(compareBy({ !it.isDisabled }, { it.isSystem }, { it.appLabel.lowercase() }))
    return items
}
