/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.shizuku

import android.content.Context
import com.example.pixeltoolbox.utils.RootUtils
import java.io.File

data class SimSlotInfo(
    val slotIndex: Int,
    val subId: Int,
    val carrierName: String,
    val mccMnc: String,
    val isEmbedded: Boolean = false
)

/**
 * ShizukuUtils 适配层：将传统 Shizuku 调用全面重定向至原生 Root (su) 接口。
 */
object ShizukuUtils {

    fun isShizukuInstalled(): Boolean = RootUtils.hasRootPermission()

    fun hasShizukuPermission(): Boolean = RootUtils.hasRootPermission()

    fun requestShizukuPermission(requestCode: Int) {
        RootUtils.requestRootPermission()
    }

    fun applyCarrierConfig(context: Context, subId: Int, toggleMap: Map<String, Boolean>, onResult: (Boolean, String) -> Unit) {
        RootUtils.applyCarrierConfig(context, subId, toggleMap, onResult)
    }

    fun readCarrierConfig(context: Context, subId: Int, onResult: (Map<String, Boolean>?, String?) -> Unit) {
        onResult(null, "已切换为 Root 原生模式")
    }

    fun readCarrierConfigStates(context: Context): Map<String, Boolean> {
        return RootUtils.readCarrierConfigStates(context)
    }

    fun restoreCarrierConfig(context: Context, subId: Int, onResult: (Boolean, String) -> Unit) {
        RootUtils.restoreCarrierConfig(context, subId, onResult)
    }

    fun readNetworkPropStates(): Map<String, Boolean> {
        return RootUtils.readNetworkPropStates()
    }

    fun getAvailableSimSlots(context: Context): List<SimSlotInfo> {
        return RootUtils.getAvailableSimSlots(context)
    }

    fun executeCommand(command: String): Result<String> {
        return RootUtils.executeCommand(command)
    }

    @JvmStatic
    fun executeCommandOrNull(command: String): String? {
        return RootUtils.executeCommandOrNull(command)
    }

    @JvmStatic
    fun executeCommandPersistent(command: String): String? {
        return RootUtils.executeCommandPersistent(command)
    }

    fun streamFileTo(command: String, inputFile: File): Result<String> {
        return RootUtils.streamFileTo(command, inputFile)
    }

    fun executeCommandWithStdin(command: String, stdinData: ByteArray, useShell: Boolean = true): Result<String> {
        return RootUtils.executeCommandWithStdin(command, stdinData, useShell)
    }

    fun installApk(apkPath: String): Result<String> {
        return RootUtils.installApk(apkPath)
    }

    fun setRefreshRate(rate: Float): Result<String> {
        return RootUtils.setRefreshRate(rate)
    }
}
