// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ExportOptions(
    val includeEdenLog: Boolean = true,
    val includeGpuLog: Boolean = true,
    val includeDeviceIdentity: Boolean = true,
    val includeAdvancedDumps: Boolean = false
)

/** A file that will be written into the bundle. */
class BundleEntry(val name: String, val description: String, val content: ByteArray)

class BundlePlan(val entries: List<BundleEntry>, val report: JsonObject, val summary: String)

/** Inputs gathered from the device at export time; kept separate so the builder stays pure. */
class ExportInputs(
    val sessionDir: File?,
    val edenLog: File?,
    val gpuLog: File?,
    val gpuCrashDumps: List<File>,
    val exitReasons: JsonElement,
    val snapshotStatic: JsonObject? = null,
    val generatedAtUtc: String
)

/**
 * Builds report.json, summary.txt and the list of bundle entries from a session directory.
 * Contains no Android API calls so it can be unit tested on the JVM.
 */
class DiagnosticsReportBuilder(
    private val redactor: DiagnosticsRedactor,
    private val options: ExportOptions
) {
    private val pretty = Json { prettyPrint = true }

    fun build(inputs: ExportInputs): BundlePlan {
        val dir = inputs.sessionDir
        val meta = dir?.let { readJson(File(it, DiagnosticsSchema.SESSION_FILE)) }?.asObject()
        val staticStart = dir?.let { readJson(File(it, DiagnosticsSchema.STATIC_START_FILE)) }
            ?.asObject() ?: inputs.snapshotStatic
        val staticEnd = dir?.let { readJson(File(it, DiagnosticsSchema.STATIC_END_FILE)) }?.asObject()
        val samples = dir?.let { readJsonLines(File(it, DiagnosticsSchema.SAMPLES_FILE)) }.orEmpty()
        val events = dir?.let { readJsonLines(File(it, DiagnosticsSchema.EVENTS_FILE)) }.orEmpty()
        val criticalFiles = dir?.listFiles { f ->
            f.name.startsWith(DiagnosticsSchema.CRITICAL_PREFIX) && f.name.endsWith(".json")
        }?.sortedBy { it.name }.orEmpty()

        val native = (staticEnd?.get("native") ?: staticStart?.get("native"))?.asObject()
        val sections = native?.get("sections")?.asObject()
        val nativeSummary = staticEnd?.get("native_summary")
            ?: criticalFiles.lastOrNull()?.let { readJson(it)?.asObject()?.get("summary") }
            ?: unavailable("capture did not finish; summary reconstructed from samples only")

        val markers = events.filter { it.str("type") == "user_marker" }
        val androidEvents = events.filter { it.str("type") != "user_marker" }

        val entries = mutableListOf<BundleEntry>()
        val report = buildJsonObject {
            put("schema_version", DiagnosticsSchema.SCHEMA_VERSION)
            put("generated_at_utc", inputs.generatedAtUtc)
            put("generator", "eden-android-diagnostics")
            put("session", meta ?: unavailable("no capture; snapshot report"))
            put("app", staticStart?.get("app") ?: JsonNull)
            put("device", deviceSection(staticStart?.get("device")))
            put("graphics_driver", staticStart?.get("graphics_driver") ?: JsonNull)
            put("game", gameSection(staticStart?.get("game"), native?.get("game")))
            put("build", native?.get("build") ?: JsonNull)
            put("cpu_backend", native?.get("cpu_backend") ?: JsonNull)
            put("settings", native?.get("settings") ?: JsonNull)
            put(
                "vulkan",
                buildJsonObject {
                    put("device", sections?.get("vulkan_device") ?: unavailable("renderer not initialized"))
                    put("swapchain", sections?.get("vulkan_swapchain") ?: unavailable("no swapchain created"))
                    put("shader_cache", sections?.get("shader_cache") ?: unavailable("no title loaded"))
                    put("memory_budget_peak", memoryBudgetPeak(samples))
                }
            )
            put("native_summary", nativeSummary)
            put("android_summary", androidSummary(samples))
            put("frame_rate", frameRate(samples))
            put("markers", buildJsonArray { markers.forEach { add(markerWithContext(it, samples)) } })
            put(
                "events",
                buildJsonObject {
                    put("native", native?.get("events") ?: JsonArray(emptyList()))
                    put("android", JsonArray(androidEvents))
                    put("critical_markers", criticalFiles.size)
                }
            )
            put("exit_reasons", inputs.exitReasons)
            put("availability", availability(samples))
            put("metric_definitions", staticStart?.get("metric_definitions") ?: JsonNull)
        }

        val redactedReport = redactStructured(report).asObject()!!
        val summary = summaryText(redactedReport, samples.size, markers.size)

        if (samples.isNotEmpty()) {
            entries += BundleEntry(
                DiagnosticsSchema.SAMPLES_FILE,
                "Metric samples, one JSON object per line",
                redactor.redactPaths(File(dir, DiagnosticsSchema.SAMPLES_FILE).readText()).toByteArray()
            )
        }
        if (events.isNotEmpty()) {
            entries += BundleEntry(
                DiagnosticsSchema.EVENTS_FILE,
                "Capture, user marker and Android system events",
                redactor.redactText(File(dir, DiagnosticsSchema.EVENTS_FILE).readText()).toByteArray()
            )
        }
        criticalFiles.forEach {
            entries += BundleEntry(
                "native/${it.name}",
                "Native state written synchronously on a fatal error",
                redactor.redactPaths(it.readText()).toByteArray()
            )
        }
        if (options.includeEdenLog) {
            inputs.edenLog?.let { tail(it, MAX_LOG_BYTES) }?.let {
                entries += BundleEntry("logs/eden_log.txt", "Eden log (last ${MAX_LOG_BYTES / 1024 / 1024} MiB)", redactor.redactText(it).toByteArray())
            }
        }
        if (options.includeGpuLog) {
            inputs.gpuLog?.let { tail(it, MAX_LOG_BYTES) }?.let {
                entries += BundleEntry("logs/eden_gpu.log", "Eden GPU log", redactor.redactText(it).toByteArray())
            }
        }
        if (options.includeAdvancedDumps) {
            inputs.gpuCrashDumps.takeLast(MAX_DUMPS).forEach { dump ->
                tail(dump, MAX_LOG_BYTES)?.let {
                    entries += BundleEntry("advanced/gpu_crashes/${dump.name}", "GPU logger state dump", redactor.redactText(it).toByteArray())
                }
            }
        }

        val contents = buildJsonArray {
            add(fileJson(DiagnosticsSchema.REPORT_FILE, "Structured report (this file)", null))
            add(fileJson(DiagnosticsSchema.SUMMARY_FILE, "Human readable summary", null))
            entries.forEach { add(fileJson(it.name, it.description, it.content.size.toLong())) }
        }
        val finalReport = JsonObject(
            redactedReport + mapOf(
                "contents" to contents,
                "privacy" to privacySection()
            )
        )
        val all = listOf(
            BundleEntry(DiagnosticsSchema.REPORT_FILE, "", pretty.encodeToString(JsonElement.serializer(), finalReport).toByteArray()),
            BundleEntry(DiagnosticsSchema.SUMMARY_FILE, "", summary.toByteArray()),
            BundleEntry(DiagnosticsSchema.README_FILE, "", readme().toByteArray())
        ) + entries
        return BundlePlan(all, finalReport, summary)
    }

    private fun deviceSection(device: JsonElement?): JsonElement {
        val obj = device?.asObject() ?: return JsonNull
        if (options.includeDeviceIdentity) {
            return obj
        }
        return JsonObject(obj - "identity" + ("identity" to JsonPrimitive("removed_by_user")))
    }

    private fun gameSection(frontend: JsonElement?, native: JsonElement?): JsonElement = buildJsonObject {
        put("frontend", frontend ?: JsonNull)
        put("native", native ?: JsonNull)
    }

    private fun privacySection(): JsonObject = buildJsonObject {
        put("redaction_applied", true)
        put("redacted_values", redactor.replacements)
        put("device_identity_included", options.includeDeviceIdentity)
        put(
            "never_included",
            buildJsonArray {
                listOf("roms", "firmware", "keys", "saves", "credentials", "web_service_token", "memory_dumps")
                    .forEach { add(it) }
            }
        )
        put(
            "excluded_setting_categories",
            buildJsonArray {
                listOf("Paths", "DataStorage", "WebService", "Multiplayer", "Network", "Controls", "Shortcuts", "Ui*", "Screenshots")
                    .forEach { add(it) }
            }
        )
    }

    private fun androidSummary(samples: List<JsonObject>): JsonObject {
        val android = samples.mapNotNull { it["android"]?.asObject() }
        val process = android.mapNotNull { it["process"]?.asObject() }
        val memory = process.mapNotNull { it["memory"]?.asObject() }
        val system = android.mapNotNull { it["system"]?.asObject() }
        val gc = process.mapNotNull { it["gc"]?.asObject() }
        val categories = mutableMapOf<String, MutableList<Double>>()
        process.forEach { p ->
            p["thread_categories"]?.asObject()?.forEach { (key, value) ->
                value.num()?.let { categories.getOrPut(key) { mutableListOf() } += it }
            }
        }
        return buildJsonObject {
            put("samples", samples.size)
            put("process_cpu_pct_of_one_core", stats(process.mapNotNull { it.num("cpu_pct_of_one_core") }))
            put(
                "thread_category_cpu_pct_of_one_core",
                JsonObject(categories.mapValues { stats(it.value) })
            )
            put("rss_kb", stats(memory.mapNotNull { it.num("rss_kb") }))
            put("rss_peak_kb_max", memory.mapNotNull { it.num("rss_peak_kb") }.maxOrNull())
            put("pss_kb", stats(memory.mapNotNull { it.num("pss_kb") }))
            put("java_heap_used_kb", stats(memory.mapNotNull { it.num("java_heap_used_kb") }))
            put("native_heap_allocated_kb", stats(memory.mapNotNull { it.num("native_heap_allocated_kb") }))
            put("gc_count_total", gc.mapNotNull { it.num("gc_count") }.sum())
            put("gc_time_ms_total", gc.mapNotNull { it.num("gc_time_ms") }.sum())
            put("blocking_gc_count_total", gc.mapNotNull { it.num("blocking_gc_count") }.sum())
            put("system_mem_available_kb_min", system.mapNotNull { it.num("mem_available_kb") }.minOrNull())
            put("system_low_memory_samples", system.count { (it["low_memory"] as? JsonPrimitive)?.content == "true" })
            put("thermal_status_max", system.mapNotNull { it.num("thermal_status") }.maxOrNull())
            put("thermal_headroom_max", system.mapNotNull { it.num("thermal_headroom") }.maxOrNull())
            put("battery_temp_c_max", system.mapNotNull { it.num("battery_temp_c") }.maxOrNull())
            put("gpu_busy_pct_kgsl", stats(system.mapNotNull { it.num("gpu_busy_pct_kgsl") }))
            put("sampler_cost_ms", stats(android.mapNotNull { it.num("sampler_cost_ms") }))
        }
    }

    private fun frameRate(samples: List<JsonObject>): JsonObject {
        var windowMs = 0.0
        var guestFrames = 0.0
        var hostFrames = 0.0
        var gameFrames = 0.0
        val perWindowGuestFps = mutableListOf<Double>()
        samples.forEach { sample ->
            val native = sample["native"]?.asObject() ?: return@forEach
            val window = native.num("window_ms") ?: return@forEach
            if (window <= 0) return@forEach
            val frames = native["frames"]?.asObject()
            val guest = frames?.get("guest_present_interval")?.asObject()?.num("count") ?: 0.0
            windowMs += window
            guestFrames += guest
            hostFrames += frames?.get("host_present_interval")?.asObject()?.num("count") ?: 0.0
            gameFrames += native["counters"]?.asObject()?.num("game_frames") ?: 0.0
            perWindowGuestFps += guest * 1000.0 / window
        }
        if (windowMs <= 0) {
            return unavailable("no samples")
        }
        return buildJsonObject {
            put("source", "native frame interval counts / sample window length")
            put("guest_present_fps_avg", guestFrames * 1000.0 / windowMs)
            put("host_present_fps_avg", hostFrames * 1000.0 / windowMs)
            put("game_fps_avg", gameFrames * 1000.0 / windowMs)
            put("guest_present_fps_per_window", stats(perWindowGuestFps))
        }
    }

    private fun memoryBudgetPeak(samples: List<JsonObject>): JsonElement {
        val peaks = mutableMapOf<Long, Pair<Double, Double>>()
        var available = false
        samples.forEach { sample ->
            val budget = sample["native"]?.asObject()?.get("samplers")?.asObject()
                ?.get("vulkan_memory_budget")?.asObject() ?: return@forEach
            if ((budget["available"] as? JsonPrimitive)?.content != "true") return@forEach
            available = true
            (budget["heaps"] as? JsonArray)?.forEach { heap ->
                val h = heap.asObject() ?: return@forEach
                val index = h.num("index")?.toLong() ?: return@forEach
                val usage = h.num("usage_bytes") ?: 0.0
                val limit = h.num("budget_bytes") ?: 0.0
                val previous = peaks[index]
                if (previous == null || usage > previous.first) {
                    peaks[index] = usage to limit
                }
            }
        }
        if (!available) {
            return unavailable("VK_EXT_memory_budget not reported during the capture")
        }
        return buildJsonObject {
            put("source", "VK_EXT_memory_budget (driver reported)")
            put(
                "heaps",
                buildJsonArray {
                    peaks.toSortedMap().forEach { (index, value) ->
                        add(
                            buildJsonObject {
                                put("index", index)
                                put("peak_usage_bytes", value.first.toLong())
                                put("budget_bytes_at_peak", value.second.toLong())
                            }
                        )
                    }
                }
            )
        }
    }

    /** Aggregates the samples around a marker. Correlation only: no cause is inferred. */
    private fun markerWithContext(marker: JsonObject, samples: List<JsonObject>): JsonObject {
        val t = marker.num("t_ms") ?: return marker
        val nearby = samples.filter {
            val st = it.num("t_ms") ?: return@filter false
            st >= t - MARKER_BEFORE_MS && st <= t + MARKER_AFTER_MS
        }
        val timers = mutableMapOf<String, Pair<Double, Double>>()
        val counters = mutableMapOf<String, Double>()
        var maxFrame: Double? = null
        var over50 = 0.0
        nearby.forEach { sample ->
            val native = sample["native"]?.asObject() ?: return@forEach
            native["timers"]?.asObject()?.forEach { (name, value) ->
                val v = value.asObject() ?: return@forEach
                val previous = timers[name] ?: (0.0 to 0.0)
                timers[name] = (previous.first + (v.num("total_us") ?: 0.0)) to
                    maxOf(previous.second, v.num("max_us") ?: 0.0)
            }
            native["counters"]?.asObject()?.forEach { (name, value) ->
                counters[name] = (counters[name] ?: 0.0) + (value.num() ?: 0.0)
            }
            val guest = native["frames"]?.asObject()?.get("guest_present_interval")?.asObject()
            guest?.num("max_ms")?.let { maxFrame = maxOf(maxFrame ?: 0.0, it) }
            over50 += guest?.num("over_50ms") ?: 0.0
        }
        val note = (marker["note"] as? JsonPrimitive)?.content.orEmpty()
        return buildJsonObject {
            put("marker_id", marker["marker_id"] ?: JsonNull)
            put("category", marker["category"] ?: JsonNull)
            put("note", redactor.redactText(note))
            put("t_ms", t.toLong())
            put("utc", marker["utc"] ?: JsonNull)
            put(
                "context",
                buildJsonObject {
                    put("window_before_ms", MARKER_BEFORE_MS)
                    put("window_after_ms", MARKER_AFTER_MS)
                    put("samples", nearby.size)
                    put("guest_frame_interval_max_ms", maxFrame)
                    put("guest_frames_over_50ms", over50.toLong())
                    put(
                        "timers_total_us",
                        JsonObject(timers.filterValues { it.first > 0 }.mapValues { JsonPrimitive(it.value.first.toLong()) })
                    )
                    put(
                        "timers_max_us",
                        JsonObject(timers.filterValues { it.second > 0 }.mapValues { JsonPrimitive(it.value.second.toLong()) })
                    )
                    put("counters", JsonObject(counters.filterValues { it > 0 }.mapValues { JsonPrimitive(it.value.toLong()) }))
                    put("note", "metrics recorded near the marker; they are not a diagnosis")
                }
            )
        }
    }

    private fun availability(samples: List<JsonObject>): JsonObject {
        val result = sortedMapOf<String, String>()
        samples.forEach { sample ->
            sample["android"]?.asObject()?.get("availability")?.asObject()?.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.content ?: return@forEach
                // Keep the first "unavailable" reason; a metric that was available at least once
                // is reported as intermittently available.
                val previous = result[key]
                result[key] = when {
                    previous == null -> text
                    previous == text -> previous
                    else -> "intermittent"
                }
            }
        }
        return JsonObject(result.mapValues { JsonPrimitive(it.value) })
    }

    private fun summaryText(report: JsonObject, sampleCount: Int, markerCount: Int): String {
        val session = report["session"]?.asObject()
        val device = report["device"]?.asObject()
        val identity = device?.get("identity")?.asObject()
        val vulkan = report["vulkan"]?.asObject()?.get("device")?.asObject()
        val driver = vulkan?.get("driver")?.asObject()
        val swapchain = report["vulkan"]?.asObject()?.get("swapchain")?.asObject()
        val cache = report["vulkan"]?.asObject()?.get("shader_cache")?.asObject()
        val build = report["build"]?.asObject()
        val game = report["game"]?.asObject()?.get("frontend")?.asObject()
        val frames = report["native_summary"]?.asObject()?.get("frames")?.asObject()
            ?.get("guest_present_interval")?.asObject()
        val fps = report["frame_rate"]?.asObject()
        val counters = report["native_summary"]?.asObject()?.get("counters")?.asObject()
        val timers = report["native_summary"]?.asObject()?.get("timers")?.asObject()
        val android = report["android_summary"]?.asObject()

        return buildString {
            appendLine("Eden diagnostic report (schema ${DiagnosticsSchema.SCHEMA_VERSION})")
            appendLine("Generated: ${report.str("generated_at_utc")}")
            appendLine()
            appendLine("== Session ==")
            appendLine("State: ${session.str("state")}  End reason: ${session.str("end_reason")}")
            appendLine("Started: ${session.str("started_at_utc")}  Duration: ${session.num("duration_ms")?.let { fmt(it / 1000.0) + " s" } ?: "?"}")
            appendLine("Level: ${session.str("level")}  Samples: $sampleCount  Markers: $markerCount")
            session.str("stage_note").takeIf { it.isNotBlank() && it != "?" }?.let { appendLine("In-game stage: ${redactor.redactText(it)}") }
            appendLine()
            appendLine("== Build / device ==")
            appendLine("Eden: ${build.str("version")} (${build.str("description")}, ${build.str("branch")})")
            appendLine("Device: ${identity?.let { "${it.str("manufacturer")} ${it.str("model")}" } ?: "identity removed"}  SoC: ${device.str("soc_model")}")
            appendLine("Android: ${device.str("android_release")} (API ${device.str("android_api")})  Kernel: ${device.str("kernel")}")
            appendLine("CPU backend: ${report.str("cpu_backend")}")
            appendLine()
            appendLine("== Game ==")
            appendLine("${game.str("title")}  [${game.str("program_id")}]")
            appendLine()
            appendLine("== Vulkan ==")
            appendLine("GPU: ${vulkan.str("device_name")}  API ${vulkan.str("api_version")}")
            appendLine("Driver: ${driver.str("id")} ${driver.str("name")} ${driver.str("info")}")
            appendLine("Swapchain: ${swapchain.str("width")}x${swapchain.str("height")} ${swapchain.str("format")} ${swapchain.str("present_mode")} images=${swapchain.str("image_count")}")
            appendLine("Shader cache: in file=${cache.str("pipelines_in_file")} restored=${cache.str("pipelines_restored")} driver cache=${cache.str("vulkan_driver_cache_bytes")} B")
            appendLine()
            appendLine("== Frames (CPU wall clock) ==")
            appendLine("Guest present FPS avg: ${fps.num("guest_present_fps_avg")?.let(::fmt) ?: "n/a"}  Game FPS avg: ${fps.num("game_fps_avg")?.let(::fmt) ?: "n/a"}")
            appendLine("Guest frame interval p50/p90/p99/max ms: ${frames.str("p50_ms")} / ${frames.str("p90_ms")} / ${frames.str("p99_ms")} / ${frames.str("max_ms")}")
            appendLine()
            appendLine("== Shaders / pipelines ==")
            appendLine("Runtime graphics misses: ${counters.str("graphics_pipeline_cache_miss")}  compute misses: ${counters.str("compute_pipeline_cache_miss")}")
            appendLine("Draws skipped while pipelines build: ${counters.str("draw_skipped_pipeline_pending")}  create failures: ${counters.str("pipeline_create_failed")}  translate failures: ${counters.str("shader_translate_failed")}")
            listOf("pipeline_build_wait", "fence_wait", "queue_submit", "graphics_shader_translate", "graphics_pipeline_create").forEach { name ->
                val t = timers?.get(name)?.asObject()
                appendLine("  $name: count=${t.str("count")} total=${t.str("total_us")} µs max=${t.str("max_us")} µs")
            }
            appendLine()
            appendLine("== Process / system ==")
            val cpu = android?.get("process_cpu_pct_of_one_core")?.asObject()
            appendLine("Process CPU (% of one core) avg/max: ${cpu.str("avg")} / ${cpu.str("max")}")
            appendLine("RSS peak (VmHWM): ${android.str("rss_peak_kb_max")} KiB  Min available system memory: ${android.str("system_mem_available_kb_min")} KiB")
            appendLine("Max thermal status: ${android.str("thermal_status_max")}  Max battery temp: ${android.str("battery_temp_c_max")} °C")
            appendLine("Sampler cost avg/max ms: ${android?.get("sampler_cost_ms")?.asObject().str("avg")} / ${android?.get("sampler_cost_ms")?.asObject().str("max")}")
            appendLine()
            appendLine("== Markers ==")
            (report["markers"] as? JsonArray)?.forEach { m ->
                val marker = m.asObject() ?: return@forEach
                val context = marker["context"]?.asObject()
                appendLine("t=${marker.str("t_ms")} ms  ${marker.str("category")}: ${marker.str("note")}")
                appendLine("    nearby: max guest frame ${context.str("guest_frame_interval_max_ms")} ms, frames over 50 ms ${context.str("guest_frames_over_50ms")}, counters ${context?.get("counters") ?: "{}"}")
            }
            appendLine()
            appendLine("Times in this report are CPU-side measurements unless stated otherwise; they are not GPU execution times.")
        }
    }

    private fun readme(): String = """
        Eden diagnostic bundle

        report.json   Structured report. "schema_version" identifies the format.
        summary.txt   Human readable summary of report.json.
        samples.jsonl One JSON object per sample: native metrics ("native") and Android metrics ("android").
        events.jsonl  Capture lifecycle, user markers and Android system events.
        native/       Native state written on fatal errors such as Vulkan device loss.
        logs/         Eden logs, if included.
        advanced/     Optional GPU logger dumps, only when explicitly selected.

        Paths, URIs, e-mail and network addresses were redacted. ROMs, firmware, keys, saves,
        credentials and memory dumps are never included.
    """.trimIndent() + "\n"

    private fun redactStructured(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { redactStructured(it.value) })
        is JsonArray -> JsonArray(element.map { redactStructured(it) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redactor.redactPaths(element.content)) else element
        else -> element
    }

    private fun fileJson(name: String, description: String, size: Long?) = buildJsonObject {
        put("name", name)
        put("description", description)
        put("bytes", size)
    }

    companion object {
        const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        const val MAX_DUMPS = 3
        const val MARKER_BEFORE_MS = 10_000L
        const val MARKER_AFTER_MS = 5_000L

        fun unavailable(reason: String): JsonObject = buildJsonObject {
            put("available", false)
            put("reason", reason)
        }

        fun stats(values: List<Double>): JsonObject {
            if (values.isEmpty()) {
                return unavailable("no values")
            }
            return buildJsonObject {
                put("count", values.size)
                put("avg", values.average())
                put("min", values.min())
                put("max", values.max())
            }
        }

        /** Reads the last [maxBytes] of a text file, starting at a line boundary. */
        fun tail(file: File, maxBytes: Long): String? {
            if (!file.isFile) {
                return null
            }
            val length = file.length()
            if (length <= maxBytes) {
                return file.readText()
            }
            val bytes = ByteArray(maxBytes.toInt())
            java.io.RandomAccessFile(file, "r").use {
                it.seek(length - maxBytes)
                it.readFully(bytes)
            }
            val text = String(bytes, Charsets.UTF_8)
            return "[truncated: first ${length - maxBytes} bytes omitted]\n" + text.substringAfter('\n', text)
        }

        fun readJson(file: File): JsonElement? =
            if (file.isFile) runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() else null

        /** Reads JSON lines, skipping a truncated last line left by an interrupted capture. */
        fun readJsonLines(file: File): List<JsonObject> {
            if (!file.isFile) {
                return emptyList()
            }
            return file.useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    .toList()
            }
        }

        private fun fmt(value: Double) = String.format(Locale.ROOT, "%.2f", value)
    }
}

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement.num(): Double? = (this as? JsonPrimitive)?.let {
    if (it.isString) null else it.doubleOrNull ?: it.longOrNull?.toDouble()
}

internal fun JsonObject?.num(key: String): Double? = this?.get(key)?.num()

internal fun JsonObject?.str(key: String): String {
    val value = this?.get(key) ?: return "?"
    return when (value) {
        is JsonPrimitive -> if (value is JsonNull) "?" else value.content
        else -> value.toString()
    }
}
