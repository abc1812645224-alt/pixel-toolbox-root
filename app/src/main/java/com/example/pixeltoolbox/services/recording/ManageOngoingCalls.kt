/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * Ported from ShizuCallRecorder (GPL-3.0) - Root-based manage_ongoing_calls AppOp check/grant.
 * The InCallService detection path (Android 12+) requires MANAGE_ONGOING_CALLS AppOps
 * to be granted to the app, otherwise Telecom will not bind our non-UI InCallService.
 */
package com.example.pixeltoolbox.services.recording

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.pixeltoolbox.utils.AppLogger
import com.example.pixeltoolbox.utils.RootUtils

/**
 * Helper for checking / granting `android:manage_ongoing_calls` and audio permissions via Root.
 */
object ManageOngoingCalls {

    private const val OP_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls"

    /** True when no AppOp is needed (API < 31) or the op is already allowed. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.checkOpNoThrow(OP_MANAGE_ONGOING_CALLS, Process.myUid(), context.packageName) ==
                    AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /**
     * Attempts to grant the op and permissions directly using Root shell execution.
     * @return true if the op is granted after execution, false otherwise.
     */
    suspend fun grant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (isGranted(context)) return true

        return try {
            val pkg = context.packageName
            val cmd = "cmd appops set $pkg MANAGE_ONGOING_CALLS allow; pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT; pm grant $pkg android.permission.CONTROL_INCALL_EXPERIENCE; pm grant $pkg android.permission.RECORD_AUDIO"
            RootUtils.executeCommand(cmd)
            val granted = isGranted(context)
            if (granted) {
                AppLogger.i("ManageOngoingCalls: granted via Root shell execution")
            } else {
                AppLogger.e("ManageOngoingCalls: Root grant command executed but isGranted is false")
            }
            granted
        } catch (e: Exception) {
            AppLogger.e("ManageOngoingCalls: exception during root grant", e)
            false
        }
    }
}
