// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import android.content.Context
import android.os.Process
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Identifies one lifetime of the app process. A pid alone is reused across boots and restarts;
 * boot count plus process start time is not.
 */
object ProcessIdentity {
    fun current(context: Context): JsonObject {
        val pid = Process.myPid()
        val startUptime = Process.getStartUptimeMillis()
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        return buildJsonObject {
            put("process_instance_id", "boot${bootCount ?: "?"}-pid$pid-start$startUptime")
            put("pid", pid)
            put("process_start_uptime_ms", startUptime)
            put("boot_count", bootCount)
        }
    }
}
