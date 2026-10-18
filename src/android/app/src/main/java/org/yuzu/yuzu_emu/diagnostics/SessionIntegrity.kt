// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure helpers that keep a report tied to its own capture: event scoping, process exit linking,
 * end classification and identity changes between capture start and end.
 */
object SessionIntegrity {
    /** Native events split into those recorded during this capture and everything else. */
    class ScopedEvents(val capture: List<JsonObject>, val context: List<JsonObject>)

    /**
     * Uses the native capture sequence when the session recorded it (schema 2). Older sessions
     * only have the monotonic capture interval; events are then attributed by time.
     */
    fun scopeNativeEvents(
        events: List<JsonObject>,
        captureSeq: Long?,
        startMonoMs: Long?,
        stopMonoMs: Long?
    ): ScopedEvents {
        val capture = mutableListOf<JsonObject>()
        val context = mutableListOf<JsonObject>()
        events.forEach { event ->
            val seq = event.num("capture_seq")?.toLong()
            val mono = event.num("mono_ms")?.toLong()
            val inCapture = when {
                captureSeq != null && seq != null -> seq == captureSeq
                startMonoMs != null && mono != null ->
                    mono >= startMonoMs && (stopMonoMs == null || mono <= stopMonoMs)
                else -> false
            }
            val method = if (captureSeq != null && seq != null) "capture_seq" else "mono_interval"
            val scoped = JsonObject(
                event + mapOf(
                    "during_capture" to JsonPrimitive(inCapture),
                    "scope_method" to JsonPrimitive(method)
                )
            )
            if (inCapture) capture += scoped else context += scoped
        }
        return ScopedEvents(capture, context)
    }

    /** Coarse category of an ApplicationExitInfo reason; status 6 is SIGABRT (abort/assert). */
    fun exitCategory(reason: String?, status: Long?): String = when (reason) {
        "crash_native" -> "native_crash"
        "crash" -> "java_crash"
        "anr" -> "anr"
        "low_memory" -> "low_memory_kill"
        "signaled" -> if (status == 6L) "abort" else "killed_by_signal"
        "exit_self" -> if (status == 0L) "normal_exit" else "exit_with_error"
        "user_requested", "user_stopped" -> "stopped_by_user_or_system"
        "excessive_resource_usage" -> "excessive_resource_usage"
        null -> "unknown"
        else -> "other"
    }

    /**
     * Marks exit records that belong to the process that ran the capture (same pid, ended after
     * the capture started). Everything else is history and is not attributed to this capture.
     */
    fun linkExitReasons(exits: JsonElement, capturePid: Long?, captureStartWallMs: Long?):
        JsonElement {
        val array = exits as? JsonArray ?: return exits
        return JsonArray(
            array.map { element ->
                val exit = element.asObject() ?: return@map element
                val pid = exit.num("pid")?.toLong()
                val timestamp = exit.num("timestamp_ms")?.toLong()
                val relation = when {
                    capturePid == null || pid == null || timestamp == null -> "unlinked_history"
                    pid == capturePid && captureStartWallMs != null &&
                        timestamp >= captureStartWallMs -> "capture_process"
                    else -> "history"
                }
                JsonObject(
                    exit + mapOf(
                        "category" to JsonPrimitive(
                            exitCategory(exit.str("reason"), exit.num("status")?.toLong())
                        ),
                        "relation" to JsonPrimitive(relation)
                    )
                )
            }
        )
    }

    /** How the capture ended, keeping what was observed apart from what is inferred. */
    fun endClassification(
        meta: JsonObject?,
        criticalReasons: List<String>,
        linkedExits: JsonElement
    ): JsonObject {
        val endReason = meta?.str("end_reason")
        val captureExit = (linkedExits as? JsonArray)?.mapNotNull { it.asObject() }
            ?.firstOrNull { it.str("relation") == "capture_process" }
        val (classification, evidence) = when {
            // Native markers are named "vulkan_device_lost".
            criticalReasons.any { it.contains("device_lost") } ->
                "vk_error_device_lost" to "native critical marker"
            endReason == "user" -> "user_stopped_capture" to "session end_reason"
            endReason == "emulation_stopped" -> "game_exit" to "session end_reason"
            endReason == "duration_limit" || endReason == "size_limit" ->
                "capture_limit_reached" to "session end_reason"
            endReason == "insufficient_space" || endReason == "write_failed" ->
                "capture_write_error" to "session end_reason"
            endReason == "process_ended_during_capture" && captureExit != null ->
                "process_ended:${captureExit.str("category")}" to "ApplicationExitInfo (same pid)"
            endReason == "process_ended_during_capture" ->
                "process_ended:unknown" to "no exit record linked to the capture process"
            criticalReasons.isNotEmpty() ->
                "critical:${criticalReasons.first()}" to "native critical marker"
            meta == null -> "no_capture" to "snapshot export"
            else -> "unknown" to "end_reason=${endReason ?: "missing"}"
        }
        return buildJsonObject {
            put("classification", classification)
            put("evidence", evidence)
            put("end_reason", meta?.get("end_reason") ?: JsonNull)
            put(
                "critical_markers",
                buildJsonArray { criticalReasons.forEach { add(JsonPrimitive(it)) } }
            )
        }
    }

    /**
     * Differences between the identity frozen at capture start and the state at capture end.
     * The report keeps the start values; changes are listed, never applied retroactively.
     */
    fun identityChanges(start: JsonObject?, end: JsonObject?): JsonArray {
        if (start == null || end == null) {
            return JsonArray(emptyList())
        }
        val changes = mutableListOf<JsonObject>()
        fun compare(field: String, a: JsonElement?, b: JsonElement?) {
            if (a != null && b != null && a != b) {
                changes += buildJsonObject {
                    put("field", field)
                    put("at_start", a)
                    put("at_end", b)
                }
            }
        }
        val startGame = start["game"]?.asObject()
        val endGame = end["game"]?.asObject()
        compare("game.program_id", startGame?.get("program_id"), endGame?.get("program_id"))
        compare("game.build_id", startGame?.get("build_id"), endGame?.get("build_id"))
        compare("game_run_id", start["game_run_id"], end["game_run_id"])
        compare("cpu_backend", start["cpu_backend"], end["cpu_backend"])
        compare(
            "build.revision",
            start["build"]?.asObject()?.get("revision"),
            end["build"]?.asObject()?.get("revision")
        )
        val startDriver = start["sections"]?.asObject()?.get("vulkan_device")?.asObject()
        val endDriver = end["sections"]?.asObject()?.get("vulkan_device")?.asObject()
        compare("vulkan.driver", startDriver?.get("driver"), endDriver?.get("driver"))
        compare(
            "vulkan.device_name",
            startDriver?.get("device_name"),
            endDriver?.get("device_name")
        )
        val startSettings = settingsMap(start["settings"])
        val endSettings = settingsMap(end["settings"])
        startSettings.forEach { (name, value) ->
            compare("settings.$name", value, endSettings[name])
        }
        return JsonArray(changes)
    }

    private fun settingsMap(settings: JsonElement?): Map<String, JsonElement> =
        (settings as? JsonArray).orEmpty().mapNotNull { it.asObject() }
            .associate { it.str("name") to (it["value"] ?: JsonNull) }
}
