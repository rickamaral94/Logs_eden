// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Driver-centric digest of a report: what the Vulkan driver was given (workload and render
 * pass shape), how it performed (GPU time, clocks, pipeline creation), what it said (perf
 * messages) and what Eden changed around it. Observations are measured facts with their
 * evidence; hypotheses are explicitly separate and say how to test them. Pure, built from the
 * assembled (already redacted) report so it can be tested on the JVM.
 */
object DriverEvaluation {
    const val FORMAT_VERSION = 1

    /** Turnip perf_debug categories, first match wins. */
    private val CATEGORIES = listOf(
        "lrz" to Regex("(?i)\\blrz\\b"),
        "ubwc" to Regex("(?i)ubwc"),
        "sysmem" to Regex("(?i)sysmem"),
        "gmem" to Regex("(?i)gmem|tile"),
        "binning" to Regex("(?i)binning|\\bbins?\\b"),
        "resolve" to Regex("(?i)resolve"),
        "blit_copy_clear" to Regex("(?i)blit|copy|clear"),
        "shader" to Regex("(?i)spill|shader|compil|register|ir3"),
        "sync_flush" to Regex("(?i)flush|invalidat|stall|barrier|wait"),
        "memory" to Regex("(?i)alloc|memory|\\bbo\\b|oom")
    )
    private val DRIVER_TAG = Regex("(?i)turnip|^tu$|mesa|freedreno|vulkan|adreno|kgsl|ir3|^fd")
    private val DRIVER_SETTING = Regex(
        "(?i)vulkan|driver|clock|async|dyna|vertex_input|accuracy|astc|shader|resolution|" +
            "msaa|aniso|vsync|present|descriptor|turbo|sync|backend|frame_?skip|fsr|filter"
    )

    fun categorize(message: String): String =
        CATEGORIES.firstOrNull { it.second.containsMatchIn(message) }?.first ?: "other"

    fun isDriverTag(tag: String): Boolean = DRIVER_TAG.containsMatchIn(tag)

    fun build(report: JsonObject): JsonObject {
        val vkDevice = report["vulkan"]?.asObject()?.get("device")?.asObject()
        val summary = report["native_summary"]?.asObject()
        val passes = summary?.get("render_passes")?.asObject()
        val observations = mutableListOf<JsonObject>()
        val hypotheses = mutableListOf<JsonObject>()

        val renderPasses = renderPassSection(passes, observations, hypotheses)
        val gpu = gpuSection(report, observations, hypotheses)
        val pipelines = pipelineSection(report, observations)
        val messages = messageSection(report, observations, hypotheses)
        val workarounds = workaroundSection(vkDevice)
        val errors = errorSection(report, observations)

        return buildJsonObject {
            put("format_version", FORMAT_VERSION)
            put(
                "purpose",
                "data for evaluating and improving the Vulkan driver (Turnip first): workload " +
                    "shape, GPU time and clocks, pipeline creation, driver messages, Eden " +
                    "workarounds and errors"
            )
            put("driver", driverSection(report, vkDevice))
            put("workload", report["workload"]?.asObject()?.get("per_host_frame") ?: JsonNull)
            put("render_passes", renderPasses)
            put("gpu", gpu)
            put("pipelines", pipelines)
            put("driver_messages", messages)
            put("eden_workarounds", workarounds)
            put("errors", errors)
            put("observations", JsonArray(observations))
            put("hypotheses", JsonArray(hypotheses))
            put(
                "note",
                "observations are measured in this capture; hypotheses are not conclusions " +
                    "and name the A/B test that would confirm them (tools/diagnostics/" +
                    "eden_diag.py abba)"
            )
        }
    }

    private fun observation(id: String, text: String, evidence: JsonObject) = buildJsonObject {
        put("id", id)
        put("text", text)
        put("evidence", evidence)
    }

    private fun hypothesis(id: String, text: String, test: String) = buildJsonObject {
        put("id", id)
        put("text", text)
        put("test", test)
    }

    private fun pct(part: Double?, whole: Double?): Double? =
        if (part == null || whole == null || whole <= 0) null else part * 100.0 / whole

    private fun driverSection(report: JsonObject, vkDevice: JsonObject?): JsonObject {
        val driver = vkDevice?.get("driver")?.asObject()
        val graphicsDriver = report["graphics_driver"]?.asObject()
        val settings = (report["settings"] as? JsonArray).orEmpty()
            .mapNotNull { it.asObject() }
            .filter { DRIVER_SETTING.containsMatchIn(it.str("name")) }
        return buildJsonObject {
            put("vulkan_driver_id", driver?.get("id") ?: JsonNull)
            put("vulkan_driver_name", driver?.get("name") ?: JsonNull)
            put("vulkan_driver_info", driver?.get("info") ?: JsonNull)
            put("driver_version_raw", vkDevice?.get("driver_version_raw") ?: JsonNull)
            put("api_version", vkDevice?.get("api_version") ?: JsonNull)
            put("device_name", vkDevice?.get("device_name") ?: JsonNull)
            put("pipeline_cache_uuid", vkDevice?.get("pipeline_cache_uuid") ?: JsonNull)
            put("selected_package", graphicsDriver?.get("selected") ?: JsonNull)
            put(
                "loaded_libraries",
                buildJsonArray {
                    (graphicsDriver?.get("loaded")?.asObject()?.get("libraries") as? JsonArray)
                        ?.mapNotNull { it.asObject() }
                        ?.forEach { lib ->
                            add(
                                buildJsonObject {
                                    put("file", lib.str("path").substringAfterLast('/'))
                                    put("elf_build_id", lib["elf_build_id"] ?: JsonNull)
                                    put("sha256", lib["sha256"] ?: JsonNull)
                                }
                            )
                        }
                }
            )
            put("environment", report["driver_environment"] ?: JsonNull)
            put("soc", report["device"]?.asObject()?.get("soc_model") ?: JsonNull)
            put("kernel", report["device"]?.asObject()?.get("kernel") ?: JsonNull)
            put(
                "eden_settings",
                JsonObject(settings.associate { it.str("name") to (it["value"] ?: JsonNull) })
            )
        }
    }

    private fun renderPassSection(
        passes: JsonObject?,
        observations: MutableList<JsonObject>,
        hypotheses: MutableList<JsonObject>
    ): JsonElement {
        val count = passes?.num("passes")?.takeIf { it > 0 }
        if (passes == null || count == null) {
            return DiagnosticsReportBuilder.unavailable("no render pass profile in this build")
        }
        val empty = passes.num("empty_passes") ?: 0.0
        val reopen = passes.num("same_target_reopen") ?: 0.0
        val loads = passes.num("attachment_loads") ?: 0.0
        val clears = passes.num("attachment_clears") ?: 0.0
        val stores = passes.num("attachment_stores") ?: 0.0
        val reopenAfter = passes["same_target_reopen_after"]?.asObject()
        val derived = buildJsonObject {
            put("empty_pass_pct", pct(empty, count))
            put("same_target_reopen_pct", pct(reopen, count))
            put("attachment_loads_per_pass", loads / count)
            put("attachment_stores_per_pass", stores / count)
            put("load_share_of_load_or_clear_pct", pct(loads, loads + clears))
            put("passes_per_frame_avg", passes["passes_per_frame_avg"] ?: JsonNull)
            put("draws_per_pass_avg", passes["draws_per_pass_avg"] ?: JsonNull)
        }
        val emptyPct = pct(empty, count) ?: 0.0
        if (emptyPct >= 10.0) {
            observations += observation(
                "empty_render_passes",
                String.format(
                    Locale.ROOT,
                    "%.1f%% of render passes contain no draw (%.0f clear-only)",
                    emptyPct,
                    passes.num("clear_only_passes") ?: 0.0
                ),
                buildJsonObject {
                    put("passes", count)
                    put("empty_passes", empty)
                    put("clear_only_passes", passes["clear_only_passes"] ?: JsonNull)
                }
            )
        }
        val reopenPct = pct(reopen, count) ?: 0.0
        if (reopenPct >= 10.0) {
            observations += observation(
                "same_target_reopened",
                String.format(
                    Locale.ROOT,
                    "%.1f%% of render passes reopen the framebuffer the previous pass used",
                    reopenPct
                ),
                buildJsonObject {
                    put("same_target_reopen", reopen)
                    put("after", reopenAfter ?: JsonNull)
                }
            )
            hypotheses += hypothesis(
                "reopen_costs_gmem_round_trips",
                "On a tiled renderer every reopened pass reloads and stores its attachments; " +
                    "passes split by operations outside the render pass (see the reasons in " +
                    "same_target_reopen_after) may cost more than the work they enclose",
                "mark the same scene in AB/BA runs and compare frame time and GPU time " +
                    "against the per-frame reopen counts in scenes"
            )
        }
        val loadsPerPass = loads / count
        if (loadsPerPass >= 1.0) {
            observations += observation(
                "attachments_loaded",
                String.format(
                    Locale.ROOT,
                    "%.2f attachment loads and %.2f stores per render pass on average",
                    loadsPerPass,
                    stores / count
                ),
                buildJsonObject {
                    put("attachment_loads", loads)
                    put("attachment_clears", clears)
                    put("attachment_stores", stores)
                    put("attachment_discards", passes["attachment_discards"] ?: JsonNull)
                }
            )
        }
        return buildJsonObject {
            put("derived", derived)
            put("session", passes)
            put(
                "note",
                "load/store counts come from Eden's render pass keys (LOAD unless cleared, STORE " +
                    "unless discarded); the driver may still skip work it can prove unneeded"
            )
        }
    }

    private fun gpuSection(
        report: JsonObject,
        observations: MutableList<JsonObject>,
        hypotheses: MutableList<JsonObject>
    ): JsonObject {
        val gpu = report["gpu_timing"]?.asObject()
        val session = gpu?.get("session")?.asObject()
        val android = report["android_summary"]?.asObject()
        val clock = android?.get("gpu_clock_mhz_kgsl")?.asObject()
        val maxClock = android?.get("gpu_max_clock_mhz_kgsl")?.asObject()
        val busy = android?.get("gpu_busy_pct_kgsl")?.asObject()
        val clockAvg = clock.num("avg")
        val clockMax = maxClock.num("max")
        if (clockAvg != null && clockMax != null && clockMax > 0 && clockAvg < clockMax * 0.8) {
            observations += observation(
                "gpu_clock_below_limit",
                String.format(
                    Locale.ROOT,
                    "average GPU clock %.0f MHz is %.0f%% of the %.0f MHz limit",
                    clockAvg,
                    clockAvg * 100.0 / clockMax,
                    clockMax
                ),
                buildJsonObject {
                    put("gpu_clock_mhz_kgsl", clock ?: JsonNull)
                    put("gpu_max_clock_mhz_kgsl", maxClock ?: JsonNull)
                    put("gpu_busy_pct_kgsl", busy ?: JsonNull)
                }
            )
            hypotheses += hypothesis(
                "governor_limits_gpu",
                "the GPU frequency governor, not the driver's work, limits GPU time",
                "ABBA with renderer_force_max_clock on and off; compare frame_gpu p50"
            )
        }
        return buildJsonObject {
            put("timestamps_available", gpu?.get("available") ?: JsonNull)
            put("frame_gpu", session?.get("frame_gpu") ?: JsonNull)
            put("submit_gpu", session?.get("submit") ?: JsonNull)
            put("coverage_pct", session?.get("coverage_pct") ?: JsonNull)
            put(
                "submit_busy_union_pct_of_window",
                gpu?.get("submit_busy_union_pct_of_window") ?: JsonNull
            )
            put("kgsl_clock_mhz", clock ?: JsonNull)
            put("kgsl_max_clock_mhz", maxClock ?: JsonNull)
            put("kgsl_busy_pct", busy ?: JsonNull)
            put(
                "fence_wait_by_reason",
                report["cpu_waits"]?.asObject()?.get("by_reason") ?: JsonNull
            )
        }
    }

    private fun pipelineSection(
        report: JsonObject,
        observations: MutableList<JsonObject>
    ): JsonObject {
        val summary = report["native_summary"]?.asObject()
        val counters = summary?.get("counters")?.asObject()
        val timers = summary?.get("timers")?.asObject()
        val events = (report["events"]?.asObject()?.get("native") as? JsonArray).orEmpty()
            .mapNotNull { it.asObject() }
        val slow = events.filter { it.str("name").startsWith("slow_") }
        val failed = events.filter { it.str("name").endsWith("_create_failed") }
        val valid = counters.num("pipeline_feedback_valid")
        val hits = counters.num("driver_pipeline_cache_hits")
        val create = timers?.get("graphics_pipeline_create")?.asObject()
        if (slow.isNotEmpty()) {
            observations += observation(
                "slow_pipeline_creates",
                "${slow.size} driver pipeline creations took at least 250 ms",
                buildJsonObject {
                    put("graphics_pipeline_create", create ?: JsonNull)
                    put("events", slow.size)
                }
            )
        }
        return buildJsonObject {
            put("graphics_pipeline_create", create ?: JsonNull)
            put("compute_pipeline_create", timers?.get("compute_pipeline_create") ?: JsonNull)
            put("pipeline_build_wait", timers?.get("pipeline_build_wait") ?: JsonNull)
            put(
                "runtime_graphics_misses",
                counters?.get("graphics_pipeline_cache_miss") ?: JsonNull
            )
            put("runtime_compute_misses", counters?.get("compute_pipeline_cache_miss") ?: JsonNull)
            put("creation_feedback_valid", valid)
            put("driver_cache_hits", hits)
            put("driver_cache_hit_pct", pct(hits, valid))
            put("failures", counters?.get("pipeline_create_failed") ?: JsonNull)
            put(
                "slow_creates",
                buildJsonArray {
                    slow.take(MAX_LISTED).forEach { e ->
                        val fields = e["fields"]?.asObject()
                        add(
                            buildJsonObject {
                                put("kind", e.str("name"))
                                put("pipeline_hash", fields?.get("pipeline_hash") ?: JsonNull)
                                put("ms", fields?.get("ms") ?: JsonNull)
                                put("driver_cache", fields?.get("driver_cache") ?: JsonNull)
                                put("mono_ms", e["mono_ms"] ?: JsonNull)
                            }
                        )
                    }
                }
            )
            put(
                "failed_creates",
                buildJsonArray { failed.take(MAX_LISTED).forEach { add(it["fields"] ?: JsonNull) } }
            )
        }
    }

    private fun messageSection(
        report: JsonObject,
        observations: MutableList<JsonObject>,
        hypotheses: MutableList<JsonObject>
    ): JsonElement {
        val log = report["driver_log"]?.asObject()
        if ((log?.get("available") as? JsonPrimitive)?.content != "true") {
            return log ?: DiagnosticsReportBuilder.unavailable("not recorded")
        }
        val env = report["driver_environment"]?.asObject()
        val tuDebug = (env?.get("TU_DEBUG") as? JsonPrimitive)?.content.orEmpty()
        val messages = (log["messages"] as? JsonArray).orEmpty().mapNotNull { it.asObject() }
        val driver = messages.filter { isDriverTag(it.str("tag")) }
        val categories = driver.groupBy { categorize(it.str("example")) }
            .mapValues { (_, list) ->
                buildJsonObject {
                    put("count", list.sumOf { it.num("count") ?: 0.0 })
                    put("distinct", list.size)
                    put(
                        "examples",
                        buildJsonArray {
                            list.take(3).forEach { add(JsonPrimitive(it.str("example").take(200))) }
                        }
                    )
                }
            }
        val total = driver.sumOf { it.num("count") ?: 0.0 }
        if (total > 0) {
            val top = categories.entries.maxByOrNull { it.value.num("count") ?: 0.0 }
            observations += observation(
                "driver_messages",
                String.format(
                    Locale.ROOT,
                    "%.0f driver messages; most frequent category: %s",
                    total,
                    top?.key ?: "?"
                ),
                JsonObject(categories)
            )
            if ((categories["sysmem"]?.num("count") ?: 0.0) > 0) {
                hypotheses += hypothesis(
                    "sysmem_fallbacks",
                    "Turnip renders some passes in sysmem mode; the messages name the reason",
                    "group the sysmem examples by reason and compare their count with the " +
                        "render pass shapes that match (formats, MSAA, resolve)"
                )
            }
        }
        return buildJsonObject {
            put("tu_debug", tuDebug)
            put("perf_messages_enabled", tuDebug.split(',').any { it.trim() == "perf" })
            put("driver_message_count", total)
            put("by_category", JsonObject(categories))
            put(
                "other_tags",
                JsonObject(
                    messages.filterNot { isDriverTag(it.str("tag")) }
                        .groupBy { it.str("tag") }
                        .mapValues { (_, list) ->
                            JsonPrimitive(list.sumOf { it.num("count") ?: 0.0 })
                        }
                )
            )
            put(
                "note",
                "categorized from the most frequent distinct messages of driver_log; Turnip " +
                    "only reports performance fallbacks with TU_DEBUG=perf"
            )
        }
    }

    private fun workaroundSection(vkDevice: JsonObject?): JsonObject = buildJsonObject {
        put("extensions_removed_by_eden", vkDevice?.get("extensions_removed_by_eden") ?: JsonNull)
        put("effective_paths", vkDevice?.get("effective_paths") ?: JsonNull)
        put("eden_feature_flags", vkDevice?.get("eden_feature_flags") ?: JsonNull)
        put(
            "note",
            "Eden disables or avoids these paths for this driver; each is a candidate for a " +
                "driver fix or for re-enabling after one"
        )
    }

    private fun errorSection(
        report: JsonObject,
        observations: MutableList<JsonObject>
    ): JsonObject {
        val events = (report["events"]?.asObject()?.get("native") as? JsonArray).orEmpty()
            .mapNotNull { it.asObject() }
            .filter { it.str("severity") in setOf("error", "critical") }
        val end = report["session_end"]?.asObject()
        val classification = end.str("classification")
        if (classification.contains("device_lost")) {
            observations += observation(
                "device_lost",
                "the capture ended with VK_ERROR_DEVICE_LOST",
                end ?: JsonObject(emptyMap())
            )
        }
        return buildJsonObject {
            put("session_end", classification)
            put(
                "device_fault",
                report["vulkan"]?.asObject()?.get("device")?.asObject()?.get("device_fault")
                    ?: JsonNull
            )
            put(
                "error_events",
                buildJsonArray {
                    events.take(MAX_LISTED).forEach { e ->
                        add(
                            buildJsonObject {
                                put("category", e["category"] ?: JsonNull)
                                put("name", e["name"] ?: JsonNull)
                                put("fields", e["fields"] ?: JsonNull)
                                put("repeat", e["repeat"] ?: JsonNull)
                            }
                        )
                    }
                }
            )
        }
    }

    /** Lines for the top of summary.txt. */
    fun summaryLines(evaluation: JsonObject): List<String> = buildList {
        val driver = evaluation["driver"]?.asObject()
        add(
            "Driver: ${driver.str("vulkan_driver_id")} ${driver.str("vulkan_driver_info")} " +
                "(raw ${driver.str("driver_version_raw")})  GPU: ${driver.str("device_name")}"
        )
        (driver?.get("loaded_libraries") as? JsonArray)?.mapNotNull { it.asObject() }
            ?.forEach { add("  loaded ${it.str("file")} build-id ${it.str("elf_build_id")}") }
        val gpu = evaluation["gpu"]?.asObject()
        val frame = gpu?.get("frame_gpu")?.asObject()
        add(
            "GPU frame time p50/p90/p99 ms: ${frame.num1("p50_ms")} / ${frame.num1("p90_ms")} / " +
                "${frame.num1("p99_ms")}  clock avg ${gpu?.get("kgsl_clock_mhz")?.asObject()
                    .num0("avg")} MHz of ${gpu?.get("kgsl_max_clock_mhz")?.asObject()
                    .num0("max")}  busy avg ${gpu?.get("kgsl_busy_pct")?.asObject().num1("avg")}%"
        )
        val passes = evaluation["render_passes"]?.asObject()
        val derived = passes?.get("derived")?.asObject()
        if (derived != null) {
            add(
                "Render passes/frame ${derived.num1("passes_per_frame_avg")}, draws/pass " +
                    "${derived.num1("draws_per_pass_avg")}, empty ${derived.num1(
                        "empty_pass_pct"
                    )}%, same-target reopen ${derived.num1("same_target_reopen_pct")}%, " +
                    "loads/pass ${derived.num1("attachment_loads_per_pass")}"
            )
            val top = passes["session"]?.asObject()?.get("shapes")?.asObject()?.get("top")
                as? JsonArray
            top?.take(5)?.mapNotNull { it.asObject() }?.forEach { s ->
                add(
                    "  ${s.str("passes")}x ${s.str("width")}x${s.str("height")} " +
                        "${s.str("description")} draws/pass ${s.num1("draws_per_pass_avg")} " +
                        "empty ${s.str("empty_passes")}"
                )
            }
        } else {
            add("Render passes: ${passes.str("reason")}")
        }
        val pipelines = evaluation["pipelines"]?.asObject()
        add(
            "Pipelines: create max ${pipelines?.get("graphics_pipeline_create")?.asObject()
                .str("max_us")} µs, slow ${(pipelines?.get("slow_creates") as? JsonArray)?.size
                ?: 0}, driver cache hits ${pipelines.str("driver_cache_hits")}/" +
                pipelines.str("creation_feedback_valid")
        )
        val messages = evaluation["driver_messages"]?.asObject()
        add(
            "Driver messages: ${messages.num0("driver_message_count")} (TU_DEBUG=" +
                "${messages.str("tu_debug").ifEmpty { "unset" }}) " +
                (messages?.get("by_category") as? JsonObject)?.entries
                    ?.joinToString(" ") { "${it.key}=${it.value.asObject().num0("count")}" }
                    .orEmpty()
        )
        val removed = evaluation["eden_workarounds"]?.asObject()
            ?.get("extensions_removed_by_eden") as? JsonArray
        add(
            "Removed by Eden: " + (
                removed?.mapNotNull { it.asObject()?.str("extension") }?.joinToString(", ")
                    ?.ifEmpty { "none" } ?: "not recorded"
                )
        )
        add("End: ${evaluation["errors"]?.asObject().str("session_end")}")
        (evaluation["observations"] as? JsonArray)?.mapNotNull { it.asObject() }?.forEach {
            add("Observation: ${it.str("text")}")
        }
        (evaluation["hypotheses"] as? JsonArray)?.mapNotNull { it.asObject() }?.forEach {
            add("Hypothesis: ${it.str("text")} [test: ${it.str("test")}]")
        }
    }

    private const val MAX_LISTED = 20
}
