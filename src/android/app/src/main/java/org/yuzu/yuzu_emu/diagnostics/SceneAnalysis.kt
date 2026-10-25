// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Per host frame records (frames.jsonl) of the scenes the user marked: which operations closed
 * and reopened render passes, and whether that coincides with slow frames. Frame records are
 * differences of the capture totals between two host frame ends (see diagnostics_frame_log).
 * Everything here is description; a co-occurrence with slow frames is a hypothesis to be tested
 * with AB/BA runs, never a cause.
 */
object SceneAnalysis {
    /** Slow frame: interval at least this many times the scene median. */
    const val SLOW_FACTOR = 1.5
    private const val SLOWEST_LISTED = 10

    class FrameTable(val columns: List<String>, val rows: List<LongArray>) {
        private val index = columns.withIndex().associate { it.value to it.index }
        fun column(name: String): Int? = index[name]
        operator fun get(row: LongArray, name: String): Long? = index[name]?.let { row[it] }
    }

    /** Merges the lines of frames.jsonl; a frame drained twice is kept once. */
    fun readFrames(lines: Sequence<String>): FrameTable {
        var columns: List<String> = emptyList()
        val rows = sortedMapOf<Long, LongArray>()
        lines.filter { it.isNotBlank() }.forEach { line ->
            val frames = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(line).asObject()
                    ?.get("frames")?.asObject()
            }.getOrNull() ?: return@forEach
            val lineColumns = (frames["columns"] as? JsonArray)
                ?.map { (it as JsonPrimitive).content } ?: return@forEach
            if (columns.isEmpty()) columns = lineColumns
            if (lineColumns != columns) return@forEach
            (frames["rows"] as? JsonArray)?.forEach { row ->
                val values = (row as? JsonArray)
                    ?.map { (it as? JsonPrimitive)?.longOrNull ?: 0L }
                    ?.toLongArray() ?: return@forEach
                if (values.size == columns.size) rows[values[0]] = values
            }
        }
        return FrameTable(columns, rows.values.toList())
    }

    /** Scene start/end pairs from events.jsonl lines of type scene_marker, by scene id. */
    fun pairMarkers(events: List<JsonObject>): List<Pair<JsonObject, JsonObject?>> {
        val markers = events.filter { it.str("type") == "scene_marker" }
        return markers.filter { it.str("kind") == "scene_start" }.map { start ->
            start to markers.firstOrNull {
                it.str("kind") == "scene_end" && it.str("scene_id") == start.str("scene_id")
            }
        }
    }

    fun analyze(
        events: List<JsonObject>,
        frames: FrameTable,
        samples: List<JsonObject>
    ): JsonArray = buildJsonArray {
        pairMarkers(events).forEach { (start, end) -> add(scene(start, end, frames, samples)) }
    }

    private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else average()

    private fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun distribution(values: List<Double>): JsonObject {
        val sorted = values.sorted()
        return buildJsonObject {
            put("count", sorted.size)
            put("mean", sorted.meanOrNull())
            put("p50", percentile(sorted, 0.50))
            put("p90", percentile(sorted, 0.90))
            put("p99", percentile(sorted, 0.99))
            put("max", sorted.lastOrNull())
        }
    }

    private fun scene(
        start: JsonObject,
        end: JsonObject?,
        table: FrameTable,
        samples: List<JsonObject>
    ): JsonObject {
        val firstId = start.num("host_frame_id")?.toLong()
        val lastFrameInTable = table.rows.lastOrNull()?.get(0)
        // Whole frames only: the frame in progress at each marker is excluded.
        val lastId = end?.num("host_frame_id")?.toLong() ?: lastFrameInTable?.plus(1)
        val startMono = start.num("mono_ms")
        val endMono = end?.num("mono_ms") ?: table.rows.lastOrNull()
            ?.let { (table[it, "end_mono_us"] ?: 0L) / 1000.0 }
        val inScene = if (firstId == null || lastId == null) {
            emptyList()
        } else {
            table.rows.filter { it[0] > firstId && it[0] < lastId }
        }
        val pause = table.column("across_pause")
        val used = inScene.filter { pause == null || it[pause] == 0L }
        val expected = if (firstId != null && lastId != null) {
            (lastId - firstId - 1).coerceAtLeast(0)
        } else {
            0
        }
        val notes = mutableListOf<String>()
        if (end == null) notes += "no end marker: the scene runs to the last recorded frame"
        if (inScene.size < expected) {
            notes += "${expected - inScene.size} frames of the scene are not in frames.jsonl " +
                "(queue full, size limit or capture interrupted)"
        }
        if (used.size < inScene.size) {
            notes += "${inScene.size - used.size} frames span a pause or the capture start " +
                "and are excluded"
        }

        fun values(name: String): List<Double> =
            table.column(name)?.let { c -> used.map { it[c].toDouble() } }.orEmpty()

        val intervalMs = values("interval_us").map { it / 1000.0 }
        val gpuState = table.column("gpu_state")
        val gpuMs = table.column("gpu_us")?.let { c ->
            used.filter { gpuState == null || it[gpuState] == 1L }.map { it[c] / 1000.0 }
        }.orEmpty()
        if (used.isNotEmpty() && gpuMs.size < used.size) {
            notes += "GPU time for ${gpuMs.size} of ${used.size} frames (partial or missing " +
                "timestamp results, or GPU time off)"
        }
        val durationS = intervalMs.sum() / 1000.0
        val reasons = table.columns.filter { it.startsWith("end_") && it != "end_mono_us" }
            .map { it.removePrefix("end_") }

        return buildJsonObject {
            put("scene_id", start["scene_id"] ?: JsonNull)
            put("name", start["name"] ?: JsonNull)
            put("start", markerPosition(start))
            put("end", end?.let { markerPosition(it) } ?: JsonNull)
            put("end_reason", end?.get("reason") ?: JsonNull)
            put(
                "frame_selection",
                "host frames with start.host_frame_id < frame < end.host_frame_id"
            )
            put("frames_expected", expected)
            put("frames_recorded", inScene.size)
            put("frames_used", used.size)
            put(
                "marker_duration_ms",
                if (startMono != null && endMono != null) endMono - startMono else null
            )
            put("frames_duration_ms", durationS * 1000.0)
            put("host_fps", if (durationS > 0) used.size / durationS else null)
            put(
                "game_fps",
                if (durationS > 0) values("game_frames").sum() / durationS else null
            )
            put("frame_interval_ms", distribution(intervalMs))
            put("gpu_ms", distribution(gpuMs))
            put(
                "gpu_coverage_pct",
                if (used.isEmpty()) null else gpuMs.size * 100.0 / used.size
            )
            put(
                "per_frame",
                buildJsonObject {
                    PER_FRAME.forEach { put(it, distribution(values(it))) }
                }
            )
            put(
                "render_pass_ends",
                buildJsonObject {
                    reasons.forEach { reason ->
                        val ended = values("end_$reason")
                        val reopened = values("reopen_after_$reason")
                        put(
                            reason,
                            buildJsonObject {
                                put("total", ended.sum().toLong())
                                put("per_frame", ended.meanOrNull())
                                put("reopened_same_target", reopened.sum().toLong())
                                put("reopened_per_frame", reopened.meanOrNull())
                            }
                        )
                    }
                }
            )
            put(
                "fence_wait_us_per_frame",
                buildJsonObject {
                    table.columns.filter { it.startsWith("wait_") }.forEach { name ->
                        put(
                            name.removePrefix("wait_").removeSuffix("_us"),
                            values(name).meanOrNull()
                        )
                    }
                }
            )
            put("slow_frames", slowFrames(table, used, intervalMs, reasons))
            put("system", system(samples, startMono, endMono))
            put("coverage_notes", JsonArray(notes.map { JsonPrimitive(it) }))
        }
    }

    private fun markerPosition(marker: JsonObject) = buildJsonObject {
        listOf("mono_ms", "wall_ms", "utc", "host_frame_id", "capture_seq", "capturing")
            .forEach { put(it, marker[it] ?: JsonNull) }
    }

    private val PER_FRAME = listOf(
        "draws",
        "passes",
        "empty_passes",
        "reopens",
        "submits",
        "dispatches",
        "pipelines_created",
        "pipeline_build_wait_us",
        "draws_skipped_pipeline_pending",
        "fence_wait_us",
        "wait_for_idle",
        "wait_for_idle_elided",
        "queue_submit_us",
        "upload_bytes",
        "download_bytes"
    )

    /**
     * Frames at least [SLOW_FACTOR] times the scene median interval, against the others: mean
     * per frame of each count in both groups. A higher mean in slow frames is a co-occurrence.
     */
    private fun slowFrames(
        table: FrameTable,
        used: List<LongArray>,
        intervalMs: List<Double>,
        reasons: List<String>
    ): JsonObject {
        val median = percentile(intervalMs.sorted(), 0.5)
        val interval = table.column("interval_us")
        if (median == null || interval == null || used.isEmpty()) {
            return DiagnosticsReportBuilder.unavailable("no frames in the scene")
        }
        val threshold = median * SLOW_FACTOR
        val (slow, normal) = used.partition { it[interval] / 1000.0 >= threshold }
        val compared = PER_FRAME + reasons.flatMap { listOf("end_$it", "reopen_after_$it") } +
            listOf("gpu_us")
        val gpuState = table.column("gpu_state")
        fun mean(rows: List<LongArray>, name: String): Double? {
            val c = table.column(name) ?: return null
            val selected = if (name == "gpu_us") {
                rows.filter { gpuState == null || it[gpuState] == 1L }
            } else {
                rows
            }
            return selected.map { it[c].toDouble() }.meanOrNull()
        }
        return buildJsonObject {
            put("threshold_ms", threshold)
            put("definition", "interval >= $SLOW_FACTOR x scene median interval")
            put("slow", slow.size)
            put("other", normal.size)
            put(
                "mean_per_frame",
                buildJsonObject {
                    compared.forEach { name ->
                        val s = mean(slow, name)
                        val o = mean(normal, name)
                        if ((s ?: 0.0) == 0.0 && (o ?: 0.0) == 0.0) return@forEach
                        put(
                            name,
                            buildJsonObject {
                                put("slow", s)
                                put("other", o)
                                put("ratio", if (s != null && o != null && o > 0) s / o else null)
                            }
                        )
                    }
                }
            )
            put(
                "slowest",
                buildJsonArray {
                    slow.sortedByDescending { it[interval] }.take(SLOWEST_LISTED).forEach { row ->
                        add(
                            buildJsonObject {
                                put("frame", row[0])
                                put("end_mono_ms", (table[row, "end_mono_us"] ?: 0L) / 1000.0)
                                put("interval_ms", row[interval] / 1000.0)
                                put(
                                    "gpu_ms",
                                    table[row, "gpu_us"]?.takeIf { it >= 0 }?.div(1000.0)
                                )
                                listOf(
                                    "draws",
                                    "passes",
                                    "reopens",
                                    "pipelines_created",
                                    "fence_wait_us"
                                ).forEach { put(it, table[row, it]) }
                                put(
                                    "ended_by",
                                    JsonObject(
                                        reasons.mapNotNull { r ->
                                            table[row, "end_$r"]?.takeIf { it > 0 }
                                                ?.let { r to JsonPrimitive(it) }
                                        }.toMap()
                                    )
                                )
                            }
                        )
                    }
                }
            )
            put(
                "note",
                "co-occurrence within this scene only; confirm with AB/BA runs of the same " +
                    "scene before attributing a cause"
            )
        }
    }

    /** Clocks and thermal state of the samples inside the scene (sample resolution only). */
    private fun system(
        samples: List<JsonObject>,
        startMono: Double?,
        endMono: Double?
    ): JsonObject {
        if (startMono == null || endMono == null) {
            return DiagnosticsReportBuilder.unavailable("scene has no time range")
        }
        // A sample covers the window that ends at its time.
        val inside = samples.filter { (it.num("mono_ms") ?: -1.0) in startMono..endMono }
            .mapNotNull { it["android"]?.asObject()?.get("system")?.asObject() }
        fun stats(name: String): JsonElement =
            DiagnosticsReportBuilder.stats(inside.mapNotNull { it.num(name) })
        return buildJsonObject {
            put("samples", inside.size)
            put("gpu_clock_mhz_kgsl", stats("gpu_clock_mhz_kgsl"))
            put("gpu_max_clock_mhz_kgsl", stats("gpu_max_clock_mhz_kgsl"))
            put("gpu_busy_pct_kgsl", stats("gpu_busy_pct_kgsl"))
            put("thermal_status", stats("thermal_status"))
            put("battery_temp_c", stats("battery_temp_c"))
            put(
                "note",
                "read once per sample interval, not per frame; a short scene can have no sample"
            )
        }
    }

    /** Lines for summary.txt. */
    fun summaryLines(scenes: JsonArray): List<String> = buildList {
        if (scenes.isEmpty()) {
            add("No scene marked. Mark the start and end of the same scene in each run.")
            return@buildList
        }
        scenes.mapNotNull { it.asObject() }.forEach { s ->
            val interval = s["frame_interval_ms"]?.asObject()
            val perFrame = s["per_frame"]?.asObject()
            add(
                "Scene ${s.str("scene_id")} \"${s.str("name")}\": " +
                    "${s.str("frames_used")} frames, " +
                    "host FPS ${s.num1("host_fps")}, interval p50/p90/p99 ms " +
                    "${interval.num1("p50")}/${interval.num1("p90")}/${interval.num1("p99")}, " +
                    "GPU p50 ${s["gpu_ms"]?.asObject().num1("p50")} ms " +
                    "(coverage ${s.num1("gpu_coverage_pct")}%)"
            )
            add(
                "  per frame: passes ${perFrame?.get("passes")?.asObject().num1("mean")}, " +
                    "reopens ${perFrame?.get("reopens")?.asObject().num1("mean")}, " +
                    "draws ${perFrame?.get("draws")?.asObject().num1("mean")}, " +
                    "pipelines ${perFrame?.get("pipelines_created")?.asObject().num1("mean")}"
            )
            val ends = s["render_pass_ends"]?.asObject()?.entries
                ?.sortedByDescending { it.value.asObject().num("total") ?: 0.0 }
                ?.filter { (it.value.asObject().num("total") ?: 0.0) > 0 }
                ?.take(6)
                ?.joinToString(", ") { (reason, v) ->
                    "$reason ${v.asObject().num1("per_frame")}" +
                        " (reopen ${v.asObject().num1("reopened_per_frame")})"
                }
            add("  pass ends per frame: ${ends ?: "n/a"}")
            val slow = s["slow_frames"]?.asObject()
            val ratios = slow?.get("mean_per_frame")?.asObject()?.entries
                ?.filter { (it.value.asObject().num("ratio") ?: 0.0) >= 1.25 }
                ?.sortedByDescending { it.value.asObject().num("ratio") }
                ?.take(5)
                ?.joinToString(", ") { "${it.key} x${it.value.asObject().num1("ratio")}" }
            add(
                "  slow frames (>= ${slow.num1("threshold_ms")} ms): ${slow.str("slow")}; " +
                    "higher in slow frames (hypothesis): ${ratios?.ifEmpty { "none" } ?: "n/a"}"
            )
            (s["coverage_notes"] as? JsonArray)?.forEach {
                add("  note: ${(it as JsonPrimitive).content}")
            }
        }
    }
}
