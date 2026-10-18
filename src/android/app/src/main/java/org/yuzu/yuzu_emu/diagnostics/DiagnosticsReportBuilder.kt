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
    val generatedAtUtc: String,
    /** eden_log.txt.old.txt, the log of the previous process lifetime. */
    val previousEdenLog: File? = null,
    val currentProcess: JsonObject? = null
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
        val staticEnd = dir?.let { readJson(File(it, DiagnosticsSchema.STATIC_END_FILE)) }
            ?.asObject()
        val samples = dir?.let { readJsonLines(File(it, DiagnosticsSchema.SAMPLES_FILE)) }.orEmpty()
        val events = dir?.let { readJsonLines(File(it, DiagnosticsSchema.EVENTS_FILE)) }.orEmpty()
        val criticalFiles = dir?.listFiles { f ->
            f.name.startsWith(DiagnosticsSchema.CRITICAL_PREFIX) && f.name.endsWith(".json")
        }?.sortedBy { it.name }.orEmpty()

        val nativeStart = staticStart?.get("native")?.asObject()
        val nativeEnd = staticEnd?.get("native")?.asObject()
        // Identity (build, game, settings, backend) is frozen at capture start; runtime state
        // such as the swapchain or shader cache is taken from the end of the capture.
        val identity = nativeStart ?: nativeEnd
        val native = nativeEnd ?: nativeStart
        val sections = native?.get("sections")?.asObject()
        val criticalDocs = criticalFiles.mapNotNull { readJson(it)?.asObject() }
        val nativeSummary = staticEnd?.get("native_summary")
            ?: criticalDocs.lastOrNull()?.get("summary")
            ?: unavailable("capture did not finish; summary reconstructed from samples only")

        val captureId = meta?.str("capture_id")?.takeIf { it != "?" }
            ?: meta?.str("session_id")?.takeIf { it != "?" }
        val captureSeq = meta.num("native_capture_seq")?.toLong()
            ?: nativeSummary.asObject().num("capture_seq")?.toLong()
        val startMono = nativeSummary.asObject().num("capture_start_mono_ms")?.toLong()
        val stopMono = nativeSummary.asObject().num("capture_stop_mono_ms")?.toLong()
            ?: meta.num("ended_mono_ms")?.toLong()
        val ringEvents = (
            (native?.get("events") as? JsonArray).orEmpty() +
                criticalDocs.flatMap { (it["events"] as? JsonArray).orEmpty() }
            )
            .mapNotNull { it.asObject() }
            .distinctBy { it.str("seq") + "/" + it.str("mono_ms") }
        val scoped = if (meta != null) {
            SessionIntegrity.scopeNativeEvents(ringEvents, captureSeq, startMono, stopMono)
        } else {
            SessionIntegrity.ScopedEvents(emptyList(), ringEvents)
        }
        fun sampleEventInCapture(event: JsonObject): Boolean =
            SessionIntegrity.scopeNativeEvents(listOf(event), captureSeq, startMono, stopMono)
                .capture.isNotEmpty()
        val captureSamples = samples.map { sample ->
            val n = sample["native"]?.asObject() ?: return@map sample
            val sampleEvents = n["events"] as? JsonArray ?: return@map sample
            val kept = sampleEvents.filter { e ->
                e.asObject()?.let(::sampleEventInCapture) ?: false
            }
            if (kept.size == sampleEvents.size) {
                sample
            } else {
                JsonObject(sample + ("native" to JsonObject(n + ("events" to JsonArray(kept)))))
            }
        }

        val linkedExits = SessionIntegrity.linkExitReasons(
            inputs.exitReasons,
            meta?.get("process")?.asObject().num("pid")?.toLong(),
            meta.num("started_wall_ms")?.toLong()
        )
        val sessionEnd = SessionIntegrity.endClassification(
            meta,
            criticalDocs.map { it.str("reason") },
            linkedExits
        )
        val logSegment = if (options.includeEdenLog) {
            LogSegmentExtractor.extract(
                listOfNotNull(
                    inputs.edenLog?.let { LogSource("current_process_log", it) },
                    inputs.previousEdenLog?.let { LogSource("previous_process_log", it) }
                ),
                if (meta != null) captureId else null
            )
        } else {
            LogSegment(null, unavailable("excluded by user"), unavailable("excluded by user"))
        }

        val markers = events.filter { it.str("type") == "user_marker" }
        val androidEvents = events.filter { it.str("type") != "user_marker" }

        val entries = mutableListOf<BundleEntry>()
        val report = buildJsonObject {
            put("schema_version", DiagnosticsSchema.SCHEMA_VERSION)
            put("generated_at_utc", inputs.generatedAtUtc)
            put("generator", "eden-android-diagnostics")
            put("session", meta ?: unavailable("no capture; snapshot report"))
            put(
                "identity",
                identitySection(meta, captureId, captureSeq, startMono, stopMono, inputs)
            )
            put("session_end", sessionEnd)
            put(
                "identity_changes",
                SessionIntegrity.identityChanges(nativeStart, nativeEnd)
            )
            put("app", staticStart?.get("app") ?: JsonNull)
            put("device", deviceSection(staticStart?.get("device")))
            put(
                "graphics_driver",
                buildJsonObject {
                    put(
                        "selected",
                        staticStart?.get("graphics_driver") ?: JsonNull
                    )
                    put(
                        "loaded",
                        staticStart?.get("driver_libraries")
                            ?: unavailable("not recorded by this build")
                    )
                    put(
                        "note",
                        "selected = package chosen in Eden; loaded = shared objects mapped by " +
                            "the process; the device that ran is vulkan.device.driver"
                    )
                }
            )
            put("game", gameSection(staticStart?.get("game"), identity?.get("game")))
            put("build", identity?.get("build") ?: JsonNull)
            put("cpu_backend", identity?.get("cpu_backend") ?: JsonNull)
            put("settings", filterSettings(identity?.get("settings")))
            put(
                "configuration_hashes",
                configurationHashes(
                    filterSettings(identity?.get("settings")),
                    staticStart?.get("game")?.asObject(),
                    identity?.get("game")?.asObject(),
                    native?.get("sections")?.asObject()?.get("shader_cache")?.asObject()
                )
            )
            put(
                "vulkan",
                buildJsonObject {
                    put(
                        "device",
                        nativeStart?.get("sections")?.asObject()?.get("vulkan_device")
                            ?: sections?.get("vulkan_device")
                            ?: unavailable("renderer not initialized")
                    )
                    put(
                        "swapchain",
                        sections?.get("vulkan_swapchain") ?: unavailable("no swapchain created")
                    )
                    put(
                        "shader_cache",
                        sections?.get("shader_cache") ?: unavailable("no title loaded")
                    )
                    put("memory_budget_peak", memoryBudgetPeak(samples))
                }
            )
            put("native_summary", nativeSummary)
            put("android_summary", androidSummary(captureSamples))
            put("frame_rate", frameRate(captureSamples))
            put("gpu_timing", gpuTiming(nativeSummary.asObject(), captureSamples))
            put("cpu_waits", cpuWaits(nativeSummary.asObject()))
            put("workload", workload(nativeSummary.asObject()))
            put("hitches", hitches(captureSamples))
            put(
                "markers",
                buildJsonArray { markers.forEach { add(markerWithContext(it, captureSamples)) } }
            )
            put(
                "events",
                buildJsonObject {
                    put("native", JsonArray(scoped.capture))
                    put("native_context", JsonArray(scoped.context))
                    put(
                        "native_scope",
                        buildJsonObject {
                            put("capture_seq", captureSeq)
                            put("capture_start_mono_ms", startMono)
                            put("capture_stop_mono_ms", stopMono)
                            put(
                                "note",
                                "native = recorded during this capture; native_context = " +
                                    "earlier captures, renderer init or time outside the capture"
                            )
                            put(
                                "ring_capacity",
                                nativeSummary.asObject()?.get("event_ring_capacity") ?: JsonNull
                            )
                            put(
                                "dropped_total",
                                nativeSummary.asObject()?.get("events_dropped") ?: JsonNull
                            )
                            put(
                                "dropped_this_capture",
                                nativeSummary.asObject()?.get("capture_events_dropped")
                                    ?: JsonNull
                            )
                        }
                    )
                    put("android", JsonArray(androidEvents))
                    put("critical_markers", criticalFiles.size)
                }
            )
            put("exit_reasons", linkedExits)
            put("log_coverage", logSegment.coverage)
            put("log_digest", logSegment.digest)
            put("availability", availability(samples))
            put("metric_definitions", staticStart?.get("metric_definitions") ?: JsonNull)
        }

        val redactedReport = redactStructured(report).asObject()!!
        val summary = summaryText(redactedReport, samples.size, markers.size)

        if (samples.isNotEmpty()) {
            entries += BundleEntry(
                DiagnosticsSchema.SAMPLES_FILE,
                "Metric samples, one JSON object per line",
                redactor.redactPaths(File(dir, DiagnosticsSchema.SAMPLES_FILE).readText())
                    .toByteArray()
            )
        }
        if (events.isNotEmpty()) {
            entries += BundleEntry(
                DiagnosticsSchema.EVENTS_FILE,
                "Capture, user marker and Android system events",
                redactor.redactText(File(dir, DiagnosticsSchema.EVENTS_FILE).readText())
                    .toByteArray()
            )
        }
        criticalFiles.forEach {
            entries += BundleEntry(
                "native/${it.name}",
                "Native state written synchronously on a fatal error",
                redactor.redactPaths(it.readText()).toByteArray()
            )
        }
        logSegment.text?.let {
            entries += BundleEntry(
                "logs/eden_log.txt",
                "Eden log lines of this capture with labelled context; see log_coverage",
                redactor.redactText(it).toByteArray()
            )
        }
        if (options.includeGpuLog) {
            inputs.gpuLog?.let { tail(it, MAX_LOG_BYTES) }?.let {
                entries += BundleEntry(
                    "logs/eden_gpu.log",
                    "Eden GPU log",
                    redactor.redactText(it).toByteArray()
                )
            }
        }
        if (options.includeAdvancedDumps) {
            inputs.gpuCrashDumps.takeLast(MAX_DUMPS).forEach { dump ->
                tail(dump, MAX_LOG_BYTES)?.let {
                    entries += BundleEntry(
                        "advanced/gpu_crashes/${dump.name}",
                        "GPU logger state dump",
                        redactor.redactText(it).toByteArray()
                    )
                }
            }
        }

        val contents = buildJsonArray {
            add(fileJson(DiagnosticsSchema.REPORT_FILE, "Structured report (this file)", null))
            add(fileJson(DiagnosticsSchema.SUMMARY_FILE, "Human readable summary", null))
            add(
                fileJson(
                    DiagnosticsSchema.MANIFEST_FILE,
                    "Sizes, SHA-256 and coverage of every file",
                    null
                )
            )
            entries.forEach { add(fileJson(it.name, it.description, it.content.size.toLong())) }
        }
        val finalReport = JsonObject(
            redactedReport + mapOf(
                "contents" to contents,
                "privacy" to privacySection()
            )
        )
        val reportBytes = pretty.encodeToString(JsonElement.serializer(), finalReport).toByteArray()
        val summaryBytes = summary.toByteArray()
        val readmeBytes = readme().toByteArray()
        val manifest = manifest(
            listOf(
                DiagnosticsSchema.REPORT_FILE to reportBytes,
                DiagnosticsSchema.SUMMARY_FILE to summaryBytes,
                DiagnosticsSchema.README_FILE to readmeBytes
            ) + entries.map { it.name to it.content },
            finalReport,
            captureSamples,
            meta
        )
        val all = listOf(
            BundleEntry(DiagnosticsSchema.REPORT_FILE, "", reportBytes),
            BundleEntry(DiagnosticsSchema.SUMMARY_FILE, "", summaryBytes),
            BundleEntry(DiagnosticsSchema.README_FILE, "", readmeBytes),
            BundleEntry(
                DiagnosticsSchema.MANIFEST_FILE,
                "",
                pretty.encodeToString(JsonElement.serializer(), manifest).toByteArray()
            )
        ) + entries
        return BundlePlan(all, finalReport, summary)
    }

    private fun identitySection(
        meta: JsonObject?,
        captureId: String?,
        captureSeq: Long?,
        startMono: Long?,
        stopMono: Long?,
        inputs: ExportInputs
    ): JsonObject = buildJsonObject {
        put("session_id", meta?.get("session_id") ?: JsonNull)
        put("capture_id", captureId)
        put("native_capture_seq", captureSeq)
        put("game_run_id", meta?.get("game_run_id") ?: JsonNull)
        val process = meta?.get("process")?.asObject()
        put("process_instance_id", process?.get("process_instance_id") ?: JsonNull)
        put(
            "exported_by_same_process",
            process?.str("process_instance_id")?.let { id ->
                inputs.currentProcess?.str("process_instance_id")?.let { it == id }
            }
        )
        put(
            "clock",
            buildJsonObject {
                put("domain", "CLOCK_MONOTONIC milliseconds (uptimeMillis / steady_clock)")
                put("capture_start_mono_ms", startMono)
                put("capture_stop_mono_ms", stopMono)
                put("log_clock_origin_mono_ms", meta?.get("log_clock_origin_mono_ms") ?: JsonNull)
                put(
                    "note",
                    "UTC fields are for presentation; correlate with mono_ms. A log line at " +
                        "[s] maps to log_clock_origin_mono_ms + s * 1000."
                )
            }
        )
    }

    /**
     * Canonical hashes to tell whether two reports ran the same configuration. Personal values
     * are already filtered out; wall-clock RTC settings are excluded as volatile.
     */
    private fun configurationHashes(
        settings: JsonElement,
        game: JsonObject?,
        nativeGame: JsonObject?,
        shaderCache: JsonObject?
    ): JsonObject {
        val entries = (settings as? JsonArray).orEmpty().mapNotNull { it.asObject() }
            .filter { it.str("name") !in VOLATILE_SETTING_NAMES }
            .map { "${it.str("category")}.${it.str("name")}=${it.str("value")}" to it }
            .sortedBy { it.first }
        fun hash(lines: List<String>) = sha256(lines.joinToString("\n").toByteArray())
        val patches = (game?.get("patches") as? JsonArray).orEmpty().mapNotNull { it.asObject() }
            .filter { it.str("enabled") == "true" }
            .map { "${it.str("type")}:${it.str("name")}:${it.str("version")}" }
            .sorted()
        val inFile = shaderCache.num("pipelines_in_file")
        return buildJsonObject {
            put("method", "SHA-256 of sorted category.name=value lines")
            put("effective_settings_sha256", hash(entries.map { it.first }))
            put(
                "per_game_settings_sha256",
                hash(entries.filter { it.second.str("per_game") == "true" }.map { it.first })
            )
            put("settings_count", entries.size)
            put(
                "excluded",
                buildJsonArray {
                    (EXCLUDED_SETTING_NAMES + VOLATILE_SETTING_NAMES).sorted().forEach { add(it) }
                }
            )
            put("enabled_patches_sha256", hash(patches))
            put("enabled_patches", patches.size)
            put("game_build_id", nativeGame?.get("build_id") ?: JsonNull)
            put(
                "shader_cache_state",
                buildJsonObject {
                    put(
                        "observed",
                        when {
                            inFile == null -> "unknown"
                            inFile > 0 -> "disk_cache_present"
                            else -> "disk_cache_empty"
                        }
                    )
                    put("pipelines_in_file", inFile)
                    put(
                        "source",
                        "pipeline cache file at title load; runtime misses alone do not " +
                            "prove a cold cache"
                    )
                }
            )
        }
    }

    private fun filterSettings(settings: JsonElement?): JsonElement {
        val array = settings as? JsonArray ?: return settings ?: JsonNull
        return JsonArray(
            array.filter { it.asObject()?.str("name") !in EXCLUDED_SETTING_NAMES }
        )
    }

    /** Integrity and coverage of the bundle: what was recorded, what is missing and why. */
    private fun manifest(
        files: List<Pair<String, ByteArray>>,
        report: JsonObject,
        samples: List<JsonObject>,
        meta: JsonObject?
    ): JsonObject {
        val interval = meta.num("interval_ms") ?: 0.0
        val times = samples.mapNotNull { it.num("t_ms") }
        val gaps = times.zipWithNext().filter { (a, b) -> interval > 0 && b - a > interval * 1.5 }
        val duration = meta.num("duration_ms")
        val incomplete = mutableListOf<String>()
        if (meta == null) incomplete += "no_capture"
        if (meta != null && meta.str("state") != DiagnosticsSchema.STATE_COMPLETED) {
            incomplete += "capture_not_completed"
        }
        if ((report["native_summary"]?.asObject()?.get("available") as? JsonPrimitive)
            ?.content == "false"
        ) {
            incomplete += "native_summary_missing"
        }
        val log = report["log_coverage"]?.asObject()
        if (options.includeEdenLog && (log?.get("complete") as? JsonPrimitive)?.content != "true") {
            incomplete += "log_incomplete"
        }
        val droppedEvents = report["events"]?.asObject()?.get("native_scope")?.asObject()
            .num("dropped_this_capture") ?: 0.0
        if (droppedEvents > 0) incomplete += "native_events_dropped"
        if (gaps.isNotEmpty()) incomplete += "sample_gaps"
        return buildJsonObject {
            put("schema_version", DiagnosticsSchema.SCHEMA_VERSION)
            put("capture_id", report["identity"]?.asObject()?.get("capture_id") ?: JsonNull)
            put("complete", incomplete.isEmpty())
            put("incomplete_reasons", buildJsonArray { incomplete.forEach { add(it) } })
            put(
                "files",
                buildJsonArray {
                    files.forEach { (name, bytes) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("bytes", bytes.size)
                                put("sha256", sha256(bytes))
                            }
                        )
                    }
                }
            )
            put(
                "samples",
                buildJsonObject {
                    put("count", samples.size)
                    put("interval_ms", interval)
                    put(
                        "expected_approx",
                        duration?.takeIf { interval > 0 }?.let { (it / interval).toLong() }
                    )
                    put("first_t_ms", times.minOrNull())
                    put("last_t_ms", times.maxOrNull())
                    put("gaps", gaps.size)
                    put("max_gap_ms", gaps.maxOfOrNull { it.second - it.first })
                    put(
                        "gap_list",
                        buildJsonArray {
                            gaps.take(20).forEach { (a, b) ->
                                add(
                                    buildJsonObject {
                                        put("after_t_ms", a)
                                        put("gap_ms", b - a)
                                    }
                                )
                            }
                        }
                    )
                }
            )
            put("log", log ?: JsonNull)
            put(
                "native_events",
                report["events"]?.asObject()?.get("native_scope") ?: JsonNull
            )
        }
    }

    private fun deviceSection(device: JsonElement?): JsonElement {
        val obj = device?.asObject() ?: return JsonNull
        if (options.includeDeviceIdentity) {
            return obj
        }
        return JsonObject(obj - "identity" + ("identity" to JsonPrimitive("removed_by_user")))
    }

    private fun gameSection(frontend: JsonElement?, native: JsonElement?): JsonElement =
        buildJsonObject {
            put("frontend", frontend ?: JsonNull)
            put("native", native ?: JsonNull)
        }

    private fun privacySection(): JsonObject = buildJsonObject {
        put("redaction_applied", true)
        put("redacted_values", redactor.replacements)
        put("device_identity_included", options.includeDeviceIdentity)
        put(
            "excluded_settings",
            buildJsonArray { EXCLUDED_SETTING_NAMES.forEach { add(it) } }
        )
        put(
            "never_included",
            buildJsonArray {
                listOf(
                    "roms",
                    "firmware",
                    "keys",
                    "saves",
                    "credentials",
                    "web_service_token",
                    "memory_dumps"
                )
                    .forEach { add(it) }
            }
        )
        put(
            "excluded_setting_categories",
            buildJsonArray {
                listOf(
                    "Paths",
                    "DataStorage",
                    "WebService",
                    "Multiplayer",
                    "Network",
                    "Controls",
                    "Shortcuts",
                    "Ui*",
                    "Screenshots"
                )
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
            put(
                "process_cpu_pct_of_one_core",
                stats(process.mapNotNull { it.num("cpu_pct_of_one_core") })
            )
            put(
                "thread_category_cpu_pct_of_one_core",
                JsonObject(categories.mapValues { stats(it.value) })
            )
            put("rss_kb", stats(memory.mapNotNull { it.num("rss_kb") }))
            put("rss_peak_kb_max", memory.mapNotNull { it.num("rss_peak_kb") }.maxOrNull())
            put("pss_kb", stats(memory.mapNotNull { it.num("pss_kb") }))
            put("java_heap_used_kb", stats(memory.mapNotNull { it.num("java_heap_used_kb") }))
            put(
                "native_heap_allocated_kb",
                stats(memory.mapNotNull { it.num("native_heap_allocated_kb") })
            )
            put("gc_count_total", gc.mapNotNull { it.num("gc_count") }.sum())
            put("gc_time_ms_total", gc.mapNotNull { it.num("gc_time_ms") }.sum())
            put("blocking_gc_count_total", gc.mapNotNull { it.num("blocking_gc_count") }.sum())
            put(
                "system_mem_available_kb_min",
                system.mapNotNull { it.num("mem_available_kb") }.minOrNull()
            )
            put(
                "system_low_memory_samples",
                system.count { (it["low_memory"] as? JsonPrimitive)?.content == "true" }
            )
            put("thermal_status_max", system.mapNotNull { it.num("thermal_status") }.maxOrNull())
            put(
                "thermal_headroom_max",
                system.mapNotNull { it.num("thermal_headroom") }.maxOrNull()
            )
            put("battery_temp_c_max", system.mapNotNull { it.num("battery_temp_c") }.maxOrNull())
            put("gpu_busy_pct_kgsl", stats(system.mapNotNull { it.num("gpu_busy_pct_kgsl") }))
            put("gpu_clock_mhz_kgsl", stats(system.mapNotNull { it.num("gpu_clock_mhz_kgsl") }))
            put(
                "gpu_max_clock_mhz_kgsl",
                stats(system.mapNotNull { it.num("gpu_max_clock_mhz_kgsl") })
            )
            put("sampler_cost_ms", stats(android.mapNotNull { it.num("sampler_cost_ms") }))
        }
    }

    /**
     * Sample windows with the longest guest frame intervals and what was measured in the same
     * window. Correlation only: a window can contain several unrelated events.
     */
    private fun hitches(samples: List<JsonObject>): JsonObject {
        val windows = samples.mapNotNull { sample ->
            val native = sample["native"]?.asObject() ?: return@mapNotNull null
            val guest = native["frames"]?.asObject()?.get("guest_present_interval")?.asObject()
            val maxMs = guest.num("max_ms") ?: return@mapNotNull null
            if (maxMs < HITCH_THRESHOLD_MS) {
                return@mapNotNull null
            }
            val timers = native["timers"]?.asObject()
            val counters = native["counters"]?.asObject()
            val window = native.num("window_ms") ?: 0.0
            fun timerMaxMs(name: String) = timers?.get(name)?.asObject().num("max_us")?.div(1000.0)
            val android = sample["android"]?.asObject()
            maxMs to buildJsonObject {
                put("t_s", (sample.num("t_ms") ?: 0.0) / 1000.0)
                put("window_ms", window)
                put("stage", native["stage"] ?: JsonNull)
                put("guest_frame_interval_max_ms", maxMs)
                put("guest_frames_over_50ms", guest.num("over_50ms"))
                put(
                    "game_fps",
                    counters.num("game_frames")?.takeIf { window > 0 }?.times(1000.0)?.div(window)
                )
                put("pipeline_build_wait_max_ms", timerMaxMs("pipeline_build_wait"))
                put("fence_wait_max_ms", timerMaxMs("fence_wait"))
                put(
                    "frame_gpu_max_ms",
                    native["gpu_timing"]?.asObject()?.get("frame_gpu")?.asObject().num("max_ms")
                )
                put("queue_submit_max_ms", timerMaxMs("queue_submit"))
                put("graphics_pipeline_create_max_ms", timerMaxMs("graphics_pipeline_create"))
                put("compute_pipeline_create_max_ms", timerMaxMs("compute_pipeline_create"))
                put("graphics_pipeline_cache_miss", counters.num("graphics_pipeline_cache_miss"))
                put("compute_pipeline_cache_miss", counters.num("compute_pipeline_cache_miss"))
                put("draw_skipped_pipeline_pending", counters.num("draw_skipped_pipeline_pending"))
                put(
                    "shaders_building",
                    android?.get("emulator")?.asObject().num("shaders_building")
                )
                put(
                    "process_cpu_pct_of_one_core",
                    android?.get("process")?.asObject().num("cpu_pct_of_one_core")
                )
                put(
                    "gpu_busy_pct_kgsl",
                    android?.get("system")?.asObject().num("gpu_busy_pct_kgsl")
                )
                put(
                    "gpu_clock_mhz_kgsl",
                    android?.get("system")?.asObject().num("gpu_clock_mhz_kgsl")
                )
                put(
                    "native_events",
                    JsonArray(
                        (native["events"] as? JsonArray).orEmpty().mapNotNull { event ->
                            val e = event.asObject() ?: return@mapNotNull null
                            buildJsonObject {
                                put("name", e["name"] ?: JsonNull)
                                put("severity", e["severity"] ?: JsonNull)
                                put("fields", e["fields"] ?: JsonNull)
                            }
                        }
                    )
                )
            }
        }
        val top = windows.sortedByDescending { it.first }.take(MAX_HITCHES)
            .map { it.second }
            .sortedBy { it.num("t_s") ?: 0.0 }
        return buildJsonObject {
            put("threshold_ms", HITCH_THRESHOLD_MS)
            put("windows_over_threshold", windows.size)
            put("windows", JsonArray(top))
            put("note", "longest windows by guest frame interval; metrics of the same window")
        }
    }

    /**
     * GPU execution time from Vulkan timestamps. frame_gpu is the union of the measured
     * scheduler submission intervals of one host frame; it can include GPU idle time inside a
     * command buffer and excludes the present blit, so it is neither GPU utilization nor
     * presentation latency.
     */
    private fun gpuTiming(summary: JsonObject?, samples: List<JsonObject>): JsonObject {
        val session = summary?.get("gpu_timing")?.asObject()
            ?: return unavailable("not recorded by this build")
        if ((session["available"] as? JsonPrimitive)?.content != "true") {
            return session
        }
        val busyPct = samples.mapNotNull { sample ->
            val native = sample["native"]?.asObject() ?: return@mapNotNull null
            val window = native.num("window_ms")?.takeIf { it > 0 } ?: return@mapNotNull null
            val gpu = native["gpu_timing"]?.asObject() ?: return@mapNotNull null
            gpu.num("submit_busy_union_ms")?.let { it * 100.0 / window }
        }
        val frameGpu = session["frame_gpu"]?.asObject()
        return buildJsonObject {
            put("available", true)
            put(
                "semantics",
                "frame_gpu: union of [TOP_OF_PIPE, BOTTOM_OF_PIPE] timestamps of every " +
                    "scheduler submission attributed to one host frame (frame id at submit " +
                    "time), only for frames whose submissions were all measured. Includes GPU " +
                    "idle time inside command buffers; excludes the present blit. Not GPU " +
                    "utilization, critical path or display latency"
            )
            put("session", session)
            put(
                "submit_busy_union_pct_of_window",
                stats(busyPct).let {
                    JsonObject(
                        it + (
                            "note" to JsonPrimitive(
                                "share of wall time covered by measured submissions; " +
                                    "overlap with other processes' GPU work is not seen"
                            )
                            )
                    )
                }
            )
            put(
                "cemu_aliases",
                buildJsonObject {
                    put("note", "for comparison with Cemu Fork Amaral reports; Eden names win")
                    put("gpuTimePerFrameMs", frameGpu?.get("p50_ms") ?: JsonNull)
                    put("gpuTimePerFrameMsP99", frameGpu?.get("p99_ms") ?: JsonNull)
                    put("gpuCoveragePct", session["coverage_pct"] ?: JsonNull)
                }
            )
        }
    }

    private fun cpuWaits(summary: JsonObject?): JsonObject {
        val byReason = summary?.get("fence_wait_by_reason")?.asObject()
            ?: return unavailable("not recorded by this build")
        val timers = summary["timers"]?.asObject()
        return buildJsonObject {
            put(
                "note",
                "CPU wall time threads spent blocked on GPU ticks, by caller-declared reason " +
                    "and by thread. Waits on different threads overlap and are not additive; " +
                    "a wait is not proof that the GPU was busy for that long"
            )
            put("fence_wait_total", timers?.get("fence_wait") ?: JsonNull)
            put("by_reason", byReason)
            put("by_thread", summary["fence_wait_by_thread"] ?: JsonNull)
            put(
                "presentation",
                buildJsonObject {
                    listOf(
                        "swapchain_acquire_cpu",
                        "swapchain_present_cpu",
                        "present_queue_submit",
                        "frame_pacing_throttle"
                    ).forEach { put(it, timers?.get(it) ?: JsonNull) }
                    put(
                        "note",
                        "time inside the calls on the calling CPU thread; not scanout or " +
                            "display latency (no present timing extension is used)"
                    )
                }
            )
        }
    }

    /** Work submitted per host frame. Counts, not costs. */
    private fun workload(summary: JsonObject?): JsonObject {
        val counters = summary?.get("counters")?.asObject()
            ?: return unavailable("no native summary")
        val hostFrames = summary["frames"]?.asObject()?.get("host_present_interval")?.asObject()
            .num("count")?.takeIf { it > 0 }
        val names = listOf(
            "scheduler_submits",
            "draw_calls",
            "indexed_draw_calls",
            "indirect_draw_calls",
            "compute_dispatches",
            "render_passes",
            "staging_upload_bytes",
            "staging_download_bytes"
        )
        if (names.none { counters.containsKey(it) }) {
            return unavailable("not recorded by this build")
        }
        return buildJsonObject {
            put("host_frames", hostFrames)
            put("totals", JsonObject(names.associateWith { counters[it] ?: JsonNull }))
            put(
                "per_host_frame",
                JsonObject(
                    names.associateWith { name ->
                        val total = counters.num(name)
                        if (total != null && hostFrames != null) {
                            JsonPrimitive(total / hostFrames)
                        } else {
                            JsonNull
                        }
                    }
                )
            )
            put(
                "note",
                "draws count guest draws that reached the rasterizer with a pipeline; staging " +
                    "bytes are requested sizes, not bytes copied by the GPU"
            )
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
        val t = marker.num("noticed_t_ms") ?: marker.num("t_ms") ?: return marker
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
            put("recorded_t_ms", marker["t_ms"] ?: JsonNull)
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
                        JsonObject(
                            timers.filterValues { it.first > 0 }.mapValues {
                                JsonPrimitive(
                                    it.value.first.toLong()
                                )
                            }
                        )
                    )
                    put(
                        "timers_max_us",
                        JsonObject(
                            timers.filterValues { it.second > 0 }.mapValues {
                                JsonPrimitive(
                                    it.value.second.toLong()
                                )
                            }
                        )
                    )
                    put(
                        "counters",
                        JsonObject(
                            counters.filterValues { it > 0 }.mapValues {
                                JsonPrimitive(
                                    it.value.toLong()
                                )
                            }
                        )
                    )
                    put("note", "metrics recorded near the marker; they are not a diagnosis")
                }
            )
        }
    }

    private fun availability(samples: List<JsonObject>): JsonObject {
        val result = sortedMapOf<String, String>()
        samples.forEach { sample ->
            val entries = sample["android"]?.asObject()?.get("availability")?.asObject()
            entries?.forEach { (key, value) ->
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
        val vulkan = report["vulkan"]?.asObject()
        val vkDevice = vulkan?.get("device")?.asObject()
        val driver = vkDevice?.get("driver")?.asObject()
        val swapchain = vulkan?.get("swapchain")?.asObject()
        val cache = vulkan?.get("shader_cache")?.asObject()
        val build = report["build"]?.asObject()
        val game = report["game"]?.asObject()?.get("frontend")?.asObject()
        val nativeSummary = report["native_summary"]?.asObject()
        val frames = nativeSummary?.get("frames")?.asObject()
            ?.get("guest_present_interval")?.asObject()
        val counters = nativeSummary?.get("counters")?.asObject()
        val timers = nativeSummary?.get("timers")?.asObject()
        val fps = report["frame_rate"]?.asObject()
        val android = report["android_summary"]?.asObject()
        val cpu = android?.get("process_cpu_pct_of_one_core")?.asObject()
        val samplerCost = android?.get("sampler_cost_ms")?.asObject()
        val gpuBusy = android?.get("gpu_busy_pct_kgsl")?.asObject()
        val gpuClock = android?.get("gpu_clock_mhz_kgsl")?.asObject()
        val gpuMaxClock = android?.get("gpu_max_clock_mhz_kgsl")?.asObject()
        val duration = session.num("duration_ms")?.let { fmt(it / 1000.0) + " s" } ?: "?"
        val deviceName = identity?.let { "${it.str("manufacturer")} ${it.str("model")}" }
            ?: "identity removed"

        return buildString {
            appendLine("Eden diagnostic report (schema ${DiagnosticsSchema.SCHEMA_VERSION})")
            appendLine("Generated: ${report.str("generated_at_utc")}")
            appendLine()
            appendLine("== Session ==")
            appendLine("State: ${session.str("state")}  End reason: ${session.str("end_reason")}")
            appendLine("Started: ${session.str("started_at_utc")}  Duration: $duration")
            appendLine(
                "Level: ${session.str("level")}  Samples: $sampleCount  Markers: $markerCount"
            )
            val stage = session.str("stage_note")
            if (stage.isNotBlank() && stage != "?") {
                appendLine("In-game stage: ${redactor.redactText(stage)}")
            }
            appendLine()
            val ids = report["identity"]?.asObject()
            val end = report["session_end"]?.asObject()
            val logCoverage = report["log_coverage"]?.asObject()
            val scope = report["events"]?.asObject()?.get("native_scope")?.asObject()
            appendLine("== Integrity ==")
            appendLine(
                "Capture: ${ids.str("capture_id")}  native seq ${ids.str("native_capture_seq")}" +
                    "  game run ${ids.str("game_run_id")}"
            )
            appendLine("Process: ${ids.str("process_instance_id")}")
            appendLine("End: ${end.str("classification")} (${end.str("evidence")})")
            appendLine(
                "Native events: ${(report["events"]?.asObject()?.get("native") as? JsonArray)
                    ?.size ?: 0} in capture, " +
                    "${(report["events"]?.asObject()?.get("native_context") as? JsonArray)
                        ?.size ?: 0} context, " +
                    "dropped in capture ${scope.str("dropped_this_capture")}"
            )
            appendLine(
                if ((logCoverage?.get("available") as? JsonPrimitive)?.content == "true") {
                    "Log: ${logCoverage.str("source")}, complete=${logCoverage.str("complete")}, " +
                        "start found=${logCoverage.str("capture_start_found")}, " +
                        "stop found=${logCoverage.str("capture_stop_found")}, " +
                        "omitted ${logCoverage.str("omitted_bytes")} B"
                } else {
                    "Log: not included (${logCoverage.str("reason")})"
                }
            )
            val changes = report["identity_changes"] as? JsonArray
            if (!changes.isNullOrEmpty()) {
                appendLine("Changed during capture (report keeps start values):")
                changes.forEach { c ->
                    appendLine("    ${c.asObject().str("field")}")
                }
            }
            appendLine()
            appendLine("== Build / device ==")
            appendLine("Eden: ${build.str("version")} (${build.str("description")})")
            appendLine("Branch: ${build.str("branch")}")
            appendLine("Device: $deviceName  SoC: ${device.str("soc_model")}")
            appendLine(
                "Android: ${device.str("android_release")} (API ${device.str("android_api")})"
            )
            appendLine("Kernel: ${device.str("kernel")}")
            appendLine("CPU backend: ${report.str("cpu_backend")}")
            appendLine()
            appendLine("== Game ==")
            appendLine("${game.str("title")}  [${game.str("program_id")}]")
            appendLine()
            appendLine("== Vulkan ==")
            appendLine("GPU: ${vkDevice.str("device_name")}  API ${vkDevice.str("api_version")}")
            appendLine("Driver: ${driver.str("id")} ${driver.str("name")} ${driver.str("info")}")
            val loaded = report["graphics_driver"]?.asObject()?.get("loaded")?.asObject()
            (loaded?.get("libraries") as? JsonArray)?.forEach { l ->
                val lib = l.asObject()
                appendLine(
                    "Loaded: ${lib.str("path").substringAfterLast('/')} " +
                        "build-id ${lib.str("elf_build_id")} sha256 " +
                        lib.str("sha256").take(16)
                )
            }
            appendLine(
                "Pipeline cache UUID: ${vkDevice.str("pipeline_cache_uuid")}  settings sha256: " +
                    report["configuration_hashes"]?.asObject().str("effective_settings_sha256")
                        .take(16)
            )
            appendLine(
                "Swapchain: ${swapchain.str("width")}x${swapchain.str("height")} " +
                    "${swapchain.str("format")} ${swapchain.str("present_mode")} " +
                    "images=${swapchain.str("image_count")}"
            )
            appendLine(
                "Shader cache: in file=${cache.str("pipelines_in_file")} " +
                    "restored=${cache.str("pipelines_restored")} " +
                    "driver cache=${cache.str("vulkan_driver_cache_bytes")} B"
            )
            appendLine()
            appendLine("== Frames (CPU wall clock) ==")
            appendLine(
                "Guest present FPS avg: ${fps.num("guest_present_fps_avg")?.let(::fmt) ?: "n/a"}"
            )
            appendLine("Game FPS avg: ${fps.num("game_fps_avg")?.let(::fmt) ?: "n/a"}")
            appendLine(
                "Guest frame interval p50/p90/p99/max ms: ${frames.str("p50_ms")} / " +
                    "${frames.str("p90_ms")} / ${frames.str("p99_ms")} / ${frames.str("max_ms")}"
            )
            pacingLine(frames?.get("pacing")?.asObject())?.let { appendLine(it) }
            appendLine()
            val gpu = report["gpu_timing"]?.asObject()
            appendLine("== GPU time (Vulkan timestamps, GPU clock) ==")
            if ((gpu?.get("available") as? JsonPrimitive)?.content == "true") {
                val session = gpu["session"]?.asObject()
                val frame = session?.get("frame_gpu")?.asObject()
                appendLine(
                    "Frame GPU time p50/p90/p99/max ms: ${frame.num1("p50_ms")} / " +
                        "${frame.num1("p90_ms")} / ${frame.num1("p99_ms")} / " +
                        frame.num1("max_ms")
                )
                appendLine(
                    "Frames complete/partial: ${session.str("frames_complete")} / " +
                        "${session.str("frames_partial")}  coverage of submissions: " +
                        "${session.num1("coverage_pct")}%"
                )
                appendLine(
                    "Dropped: ring full ${session.str("dropped_ring_full")}, unavailable " +
                        "${session.str("dropped_unavailable")}, " +
                        "lost ${session.str("dropped_lost")}  " +
                        "instrumentation CPU ${session.num1("instrumentation_cpu_ms")} ms"
                )
                appendLine(
                    "Union of submissions, % of wall time avg/max: " +
                        "${gpu["submit_busy_union_pct_of_window"]?.asObject().num1("avg")} / " +
                        gpu["submit_busy_union_pct_of_window"]?.asObject().num1("max")
                )
            } else {
                appendLine("Not available: ${gpu.str("reason")}")
            }
            appendLine()
            val waits = report["cpu_waits"]?.asObject()
            appendLine("== CPU waits on the GPU (wall clock, per thread, overlapping) ==")
            (waits?.get("by_reason") as? JsonObject)?.entries
                ?.sortedByDescending { it.value.asObject().num("total_us") ?: 0.0 }
                ?.forEach { (reason, value) ->
                    val v = value.asObject()
                    appendLine(
                        "  $reason: count=${v.str("count")} total=${v.num0("total_us")} µs " +
                            "max=${v.str("max_us")} µs"
                    )
                } ?: appendLine("  not recorded by this build")
            (waits?.get("by_thread") as? JsonObject)?.let { threads ->
                appendLine(
                    "  threads: " + threads.entries
                        .sortedByDescending { it.value.asObject().num("total_us") ?: 0.0 }
                        .joinToString(", ") { (name, value) ->
                            "$name ${value.asObject().num0("total_us")} µs"
                        }
                )
            }
            val presentation = waits?.get("presentation")?.asObject()
            if (presentation != null) {
                appendLine(
                    "  acquire/present/throttle max µs: " +
                        "${presentation["swapchain_acquire_cpu"]?.asObject().str("max_us")} / " +
                        "${presentation["swapchain_present_cpu"]?.asObject().str("max_us")} / " +
                        presentation["frame_pacing_throttle"]?.asObject().str("max_us")
                )
            }
            val work = report["workload"]?.asObject()?.get("per_host_frame")?.asObject()
            if (work != null) {
                val uploadKib = work.num("staging_upload_bytes")
                    ?.let { String.format(Locale.ROOT, "%.1f", it / 1024.0) } ?: "n/a"
                appendLine(
                    "Per host frame: submits ${work.num1("scheduler_submits")}, " +
                        "draws ${work.num1("draw_calls")}, " +
                        "dispatches ${work.num1("compute_dispatches")}, " +
                        "render passes ${work.num1("render_passes")}, upload KiB $uploadKib"
                )
            }
            appendLine()
            appendLine("== Shaders / pipelines ==")
            appendLine(
                "Runtime misses: graphics=${counters.str("graphics_pipeline_cache_miss")} " +
                    "compute=${counters.str("compute_pipeline_cache_miss")}"
            )
            appendLine(
                "Draws skipped while pipelines build: " +
                    counters.str("draw_skipped_pipeline_pending")
            )
            appendLine(
                "Failures: create=${counters.str("pipeline_create_failed")} " +
                    "translate=${counters.str("shader_translate_failed")}"
            )
            SUMMARY_TIMERS.forEach { name ->
                val t = timers?.get(name)?.asObject()
                appendLine(
                    "  $name: count=${t.str("count")} total=${t.str("total_us")} µs " +
                        "max=${t.str("max_us")} µs"
                )
            }
            appendLine()
            val hitchList = report["hitches"]?.asObject()
            appendLine(
                "== Longest frame intervals (over $HITCH_THRESHOLD_MS ms, " +
                    "${hitchList.str("windows_over_threshold")} sample windows) =="
            )
            (hitchList?.get("windows") as? JsonArray)?.forEach { h ->
                val hitch = h.asObject() ?: return@forEach
                appendLine(
                    "t=${hitch.num1("t_s")} s  frame max " +
                        "${hitch.num1("guest_frame_interval_max_ms")} ms"
                )
                appendLine(
                    "    max ms: build wait ${hitch.num1("pipeline_build_wait_max_ms")}, " +
                        "fence wait ${hitch.num1("fence_wait_max_ms")}, " +
                        "graphics create ${hitch.num1("graphics_pipeline_create_max_ms")}, " +
                        "compute create ${hitch.num1("compute_pipeline_create_max_ms")}, " +
                        "GPU frame ${hitch.num1("frame_gpu_max_ms")}"
                )
                appendLine(
                    "    misses graphics/compute ${hitch.num0("graphics_pipeline_cache_miss")}/" +
                        "${hitch.num0("compute_pipeline_cache_miss")}, " +
                        "shaders building ${hitch.num0("shaders_building")}"
                )
                (hitch["native_events"] as? JsonArray)?.forEach { e ->
                    val event = e.asObject() ?: return@forEach
                    appendLine("    event ${event.str("name")} ${event["fields"] ?: ""}")
                }
            }
            appendLine()
            appendLine("== Process / system ==")
            appendLine(
                "Process CPU (% of one core) avg/max: ${cpu.num1("avg")} / ${cpu.num1("max")}"
            )
            appendLine(
                "RSS peak (VmHWM, process lifetime, not this capture): " +
                    "${android.mib("rss_peak_kb_max")} MiB"
            )
            appendLine(
                "Min available system memory: ${android.mib("system_mem_available_kb_min")} MiB"
            )
            appendLine("Max thermal status: ${android.num0("thermal_status_max")}")
            appendLine("Max battery temperature: ${android.num1("battery_temp_c_max")} °C")
            appendLine(
                "GPU busy % (kgsl, all processes) avg/max: ${gpuBusy.num1("avg")} / " +
                    gpuBusy.num1("max")
            )
            appendLine(
                "GPU clock MHz avg/min/max: ${gpuClock.num0("avg")} / ${gpuClock.num0("min")} / " +
                    gpuClock.num0("max")
            )
            appendLine(
                "GPU clock upper limit MHz min/max: ${gpuMaxClock.num0("min")} / " +
                    gpuMaxClock.num0("max")
            )
            appendLine(
                "Sampler cost avg/max ms: ${samplerCost.num1("avg")} / ${samplerCost.num1("max")}"
            )
            appendLine()
            appendLine("== Markers ==")
            (report["markers"] as? JsonArray)?.forEach { m ->
                val marker = m.asObject() ?: return@forEach
                val context = marker["context"]?.asObject()
                appendLine(
                    "t=${marker.str("t_ms")} ms  ${marker.str("category")}: ${marker.str("note")}"
                )
                appendLine(
                    "    nearby: max guest frame " +
                        "${context.str("guest_frame_interval_max_ms")} ms, " +
                        "frames over 50 ms ${context.str("guest_frames_over_50ms")}"
                )
                appendLine("    counters: ${context?.get("counters") ?: "{}"}")
            }
            appendLine()
            appendLine("Times in this report are CPU-side measurements unless stated otherwise;")
            appendLine("only the \"GPU time\" section comes from GPU timestamps.")
        }
    }

    /** Share of guest frames per interval bucket, e.g. to spot alternating 16/33 ms pacing. */
    private fun pacingLine(pacing: JsonObject?): String? {
        if (pacing == null) {
            return null
        }
        val counts = pacing.mapValues { it.value.num() ?: 0.0 }
        val total = counts.values.sum()
        if (total <= 0) {
            return null
        }
        return "Guest frame pacing: " + counts.entries.joinToString(", ") { (name, count) ->
            val label = name.replace("lt_", "<").replace("ge_", ">=").replace('_', '-')
            "$label ${String.format(Locale.ROOT, "%.1f", count * 100.0 / total)}%"
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
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(
                redactor.redactPaths(element.content)
            )
        } else {
            element
        }
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
        const val HITCH_THRESHOLD_MS = 100L
        const val MAX_HITCHES = 10

        /** Console identifiers left out of exports; the emulator keeps using its values. */
        val EXCLUDED_SETTING_NAMES = setOf("serial_battery", "serial_unit")

        /** Wall-clock values that differ between runs without changing the configuration. */
        val VOLATILE_SETTING_NAMES = setOf("custom_rtc", "custom_rtc_offset")

        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        private val SUMMARY_TIMERS = listOf(
            "pipeline_build_wait",
            "fence_wait",
            "queue_submit",
            "graphics_shader_translate",
            "graphics_pipeline_create"
        )

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
            return "[truncated: first ${length - maxBytes} bytes omitted]\n" + text.substringAfter(
                '\n',
                text
            )
        }

        fun readJson(file: File): JsonElement? =
            if (file.isFile) {
                runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull()
            } else {
                null
            }

        /** Reads JSON lines, skipping a truncated last line left by an interrupted capture. */
        fun readJsonLines(file: File): List<JsonObject> {
            if (!file.isFile) {
                return emptyList()
            }
            return file.useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                    }
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

internal fun JsonObject?.num1(key: String): String =
    num(key)?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "n/a"

internal fun JsonObject?.num0(key: String): String =
    num(key)?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "n/a"

internal fun JsonObject?.mib(key: String): String =
    num(key)?.let { String.format(Locale.ROOT, "%.0f", it / 1024.0) } ?: "n/a"

internal fun JsonObject?.str(key: String): String {
    val value = this?.get(key) ?: return "?"
    return when (value) {
        is JsonPrimitive -> if (value is JsonNull) "?" else value.content
        else -> value.toString()
    }
}
