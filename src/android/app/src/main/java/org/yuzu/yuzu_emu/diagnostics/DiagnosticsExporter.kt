// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.DirectoryInitialization

/** Gathers device-side inputs and turns a session (or a snapshot) into a shareable bundle. */
object DiagnosticsExporter {
    private const val MAX_EXIT_REASONS = 5

    fun redactor(context: Context): DiagnosticsRedactor {
        val known = linkedMapOf<String, String>()
        DirectoryInitialization.userDirectory?.let { known[it] = "<eden_user_dir>" }
        context.getExternalFilesDir(null)?.canonicalPath?.let { known[it] = "<eden_user_dir>" }
        context.externalCacheDir?.canonicalPath?.let { known[it] = "<app_external_cache>" }
        context.filesDir.canonicalPath.let { known[it] = "<app_files>" }
        context.cacheDir.canonicalPath.let { known[it] = "<app_cache>" }
        context.applicationInfo.dataDir?.let { known[it] = "<app_data>" }
        context.applicationInfo.nativeLibraryDir?.let { known[it] = "<app_native_libs>" }
        context.applicationInfo.sourceDir?.let { File(it).parent }
            ?.let { known[it] = "<app_install_dir>" }
        return DiagnosticsRedactor(known)
    }

    fun inputs(context: Context, session: SessionInfo?, game: Game? = null): ExportInputs {
        val logDir = File(DirectoryInitialization.userDirectory!!, "log")
        // The log file is written through a buffer; without a flush the newest lines are missing.
        runCatching { NativeDiagnostics.flushLog() }
        val snapshot = if (session == null) {
            buildJsonObject {
                put("collected_at_utc", DiagnosticsSession.utcNow())
                put("app", DeviceInfoCollector.app())
                put(
                    "device",
                    runCatching { DeviceInfoCollector.device(context) }.getOrNull()
                        ?: buildJsonObject {}
                )
                put(
                    "graphics_driver",
                    runCatching { DeviceInfoCollector.graphicsDriver() }.getOrNull()
                        ?: buildJsonObject {}
                )
                put(
                    "game",
                    runCatching { DeviceInfoCollector.game(game) }.getOrNull() ?: buildJsonObject {}
                )
                put(
                    "native",
                    DiagnosticsSession.parseNative(
                        runCatching { NativeDiagnostics.staticInfo() }.getOrDefault("{}")
                    )
                )
                put("metric_definitions", AndroidMetricsSampler.METRIC_DEFINITIONS)
            }
        } else {
            null
        }
        return ExportInputs(
            sessionDir = session?.directory,
            edenLog = File(logDir, "eden_log.txt").takeIf { it.isFile },
            gpuLog = File(logDir, "eden_gpu.log").takeIf { it.isFile },
            gpuCrashDumps = File(logDir, "gpu_crashes").listFiles()
                ?.sortedBy { it.lastModified() }.orEmpty(),
            exitReasons = exitReasons(context),
            snapshotStatic = snapshot,
            generatedAtUtc = DiagnosticsSession.utcNow()
        )
    }

    fun plan(
        context: Context,
        session: SessionInfo?,
        options: ExportOptions,
        game: Game? = null
    ): BundlePlan =
        DiagnosticsReportBuilder(redactor(context), options).build(inputs(context, session, game))

    fun write(plan: BundlePlan, session: SessionInfo?): BundleResult {
        val name = "eden-diagnostics-${session?.id ?: "snapshot-" + System.currentTimeMillis()}.zip"
        return DiagnosticsBundleWriter.write(plan, File(DiagnosticsSession.exportsDirectory, name))
    }

    fun shareIntent(context: Context, bundle: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", bundle)
        return Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun exitReasons(context: Context): JsonElement {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DiagnosticsReportBuilder.unavailable("ApplicationExitInfo requires API 30")
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val reasons = runCatching {
            activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_EXIT_REASONS
            )
        }.getOrElse { return DiagnosticsReportBuilder.unavailable(it.javaClass.simpleName) }
        return buildJsonArray {
            reasons.forEach { info ->
                add(
                    buildJsonObject {
                        put("timestamp_ms", info.timestamp)
                        put("reason", exitReasonName(info.reason))
                        put("status", info.status)
                        put("importance", info.importance)
                        put("pss_kb", info.pss)
                        put("rss_kb", info.rss)
                        put("description", info.description)
                    }
                )
            }
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        else -> "unknown_$reason"
    }
}
