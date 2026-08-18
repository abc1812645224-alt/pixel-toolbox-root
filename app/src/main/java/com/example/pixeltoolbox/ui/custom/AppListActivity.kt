/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.ui.custom

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.pixeltoolbox.shizuku.ShizukuUtils
import com.example.pixeltoolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isExtractMode = intent.getStringExtra("MODE") == "EXTRACT"
        setContent {
            PixelToolboxTheme {
                AppListScreen(isExtractMode = isExtractMode, onBack = { finish() })
            }
        }
    }
}

data class AppItem(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    val isSystem: Boolean,
    var isFrozen: Boolean,
    val icon: Drawable?
)

enum class AppFilterTab(val label: String) {
    ALL("全部应用"),
    USER("第三方"),
    SYSTEM("系统应用"),
    FROZEN("已冻结 ❄️")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(isExtractMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pm = context.packageManager

    var fullAppList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(AppFilterTab.ALL) }
    var processingApp by remember { mutableStateOf<String?>(null) }
    var isBatchProcessing by remember { mutableStateOf(false) }

    fun loadApps() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val list = mutableListOf<AppItem>()
            for (appInfo in installedApps) {
                if (appInfo.packageName == context.packageName) continue

                val name = appInfo.loadLabel(pm).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { null }

                val enabledSetting = try {
                    pm.getApplicationEnabledSetting(appInfo.packageName)
                } catch (e: Exception) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                val isFrozen = !appInfo.enabled || enabledSetting in listOf(
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                )

                list.add(AppItem(name, appInfo.packageName, appInfo.sourceDir, isSystem, isFrozen, icon))
            }

            list.sortWith(compareBy({ !it.isFrozen }, { it.isSystem }, { it.name.lowercase() }))

            withContext(Dispatchers.Main) {
                fullAppList = list
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    val filteredList = remember(fullAppList, searchQuery, activeTab) {
        fullAppList.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesTab = when (activeTab) {
                AppFilterTab.ALL -> true
                AppFilterTab.USER -> !app.isSystem
                AppFilterTab.SYSTEM -> app.isSystem
                AppFilterTab.FROZEN -> app.isFrozen
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
        // Top Bar
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
                text = if (isExtractMode) "应用 APK 提取器" else "极客冰箱",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = iOSLabel,
                modifier = Modifier.weight(1f)
            )
            if (!isExtractMode) {
                TextButton(
                    onClick = {
                        isBatchProcessing = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val frozenList = fullAppList.filter { it.isFrozen }
                            if (frozenList.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "没有已冻结的应用", Toast.LENGTH_SHORT).show()
                                    isBatchProcessing = false
                                }
                                return@launch
                            }
                            val cmds = frozenList.joinToString("; ") { "pm enable --user 0 ${it.packageName}" }
                            ShizukuUtils.executeCommand(cmds)
                            withContext(Dispatchers.Main) {
                                isBatchProcessing = false
                                Toast.makeText(context, "已一键解冻 ${frozenList.size} 个应用", Toast.LENGTH_LONG).show()
                                loadApps()
                            }
                        }
                    },
                    enabled = !isBatchProcessing
                ) {
                    Text(if (isBatchProcessing) "解冻中..." else "一键全解冻", color = iOSBlue, fontSize = 14.sp)
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = { Text("搜索应用名称或包名...", color = iOSSecondaryLabel) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = iOSSecondaryLabel) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        // Filter Tabs
        if (!isExtractMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppFilterTab.values().forEach { tab ->
                    val isSelected = activeTab == tab
                    if (isSelected) {
                        iOSButton(
                            modifier = Modifier.weight(1f),
                            onClick = { activeTab = tab }
                        ) {
                            Text(tab.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        iOSOutlineButton(
                            modifier = Modifier.weight(1f),
                            onClick = { activeTab = tab }
                        ) {
                            Text(tab.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = iOSBlue)
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无匹配的应用", style = MaterialTheme.typography.bodyMedium, color = iOSSecondaryLabel)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
            ) {
                items(filteredList, key = { it.packageName }) { app ->
                    val isProcessing = processingApp == app.packageName

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .alpha(if (!isExtractMode && app.isFrozen) 0.6f else 1.0f)
                            .clickable(enabled = !isProcessing && !isBatchProcessing) {
                                if (isExtractMode) {
                                    processingApp = app.packageName
                                    coroutineScope.launch {
                                        val cleanName = app.name.replace(" ", "_").replace("/", "_")
                                        val dest = "/sdcard/Download/${cleanName}_${app.packageName}.apk"
                                        val cmd = "cp '${app.sourceDir}' '$dest' && chmod 644 '$dest'"

                                        val result = withContext(Dispatchers.IO) {
                                            ShizukuUtils.executeCommand(cmd)
                                        }
                                        processingApp = null
                                        if (result.isSuccess) {
                                            Toast.makeText(context, "已提取到 Download 目录", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "提取失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    processingApp = app.packageName
                                    coroutineScope.launch {
                                        val targetFrozenState = !app.isFrozen
                                        val cmd = "pm ${if (targetFrozenState) "disable-user" else "enable"} --user 0 ${app.packageName}"

                                        val result = withContext(Dispatchers.IO) {
                                            ShizukuUtils.executeCommand(cmd)
                                        }
                                        processingApp = null
                                        if (result.isSuccess) {
                                            val newList = fullAppList.toMutableList()
                                            val index = newList.indexOfFirst { it.packageName == app.packageName }
                                            if (index != -1) {
                                                newList[index] = newList[index].copy(isFrozen = targetFrozenState)
                                                fullAppList = newList
                                            }
                                            Toast.makeText(context, "${app.name} ${if (targetFrozenState) "已冻结 ❄️" else "已解冻 ☀️"}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "操作失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon.toBitmap(96, 96).asImageBitmap(),
                                    contentDescription = app.name,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(iOSSeparator, RoundedCornerShape(8.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = iOSLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${if (app.isSystem) "系统" else "第三方"} · ${app.packageName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = iOSSecondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = iOSBlue, strokeWidth = 2.dp)
                            } else {
                                val statusText = if (isExtractMode) "提取" else if (app.isFrozen) "已冻结 ❄️" else "正常"
                                val statusColor = if (isExtractMode) iOSBlue else if (app.isFrozen) Color(0xFF1976D2) else iOSGreen
                                val bgColor = if (isExtractMode) Color(0xFFE6F2FA) else if (app.isFrozen) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)

                                Box(
                                    modifier = Modifier
                                        .background(bgColor, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = statusColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
