// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAnalysisTest {
    private val columns = listOf(
        "frame", "end_mono_us", "interval_us", "across_pause", "game_frames", "draws",
        "passes", "reopens", "end_copy", "end_readback", "end_unknown",
        "reopen_after_readback", "pipelines_created", "fence_wait_us", "wait_texture_download_us",
        "gpu_us", "gpu_state"
    )

    /** Frames 1..50, one every 25 ms; every 5th frame is slow and has readback splits. */
    private fun frameLine(
        ids: IntRange,
        pauseAt: Int = -1,
        missing: Set<Int> = emptySet()
    ): String {
        val rows = ids.filterNot { it in missing }.joinToString(",") { id ->
            val slow = id % 5 == 0
            val interval = if (slow) 50_000 else 25_000
            val readbacks = if (slow) 6 else 1
            val values = listOf(
                id, 1_000_000L + id * 25_000L, interval, if (id == pauseAt) 1 else 0, 1, 400,
                120, 40, 30, readbacks, 2, readbacks, if (slow) 1 else 0, 3000, 1000 * readbacks,
                if (id == 7) -1 else 12_000, if (id == 7) 3 else 1
            )
            values.joinToString(",", "[", "]")
        }
        val names = columns.joinToString(",") { "\"$it\"" }
        return """{"capture_id":"c","mono_ms":0,"frames":{"columns":[$names],""" +
            """"row_count":1,"rows":[$rows]}}"""
    }

    private fun marker(kind: String, frame: Long, monoMs: Long, id: Int = 1) =
        Json.parseToJsonElement(
            """{"type":"scene_marker","kind":"$kind","scene_id":$id,"name":"bridge",
               "mono_ms":$monoMs,"host_frame_id":$frame,"capture_seq":3,"capturing":true}"""
        ).jsonObject

    private fun analyze(
        events: List<JsonObject>,
        lines: List<String>,
        samples: List<JsonObject> = emptyList()
    ) = SceneAnalysis.analyze(events, SceneAnalysis.readFrames(lines.asSequence()), samples)
        .map { it.jsonObject }

    @Test
    fun wholeFramesBetweenMarkersAreSelected() {
        // Frame 10 is in progress at the start marker, frame 31 at the end marker.
        val scene = analyze(
            listOf(marker("scene_start", 10, 1240), marker("scene_end", 31, 1770)),
            listOf(frameLine(1..25), frameLine(20..50)) // frames 20-25 drained twice
        ).single()
        assertEquals(20L, scene["frames_expected"]!!.jsonPrimitive.long)
        assertEquals(20L, scene["frames_recorded"]!!.jsonPrimitive.long)
        assertEquals(20L, scene["frames_used"]!!.jsonPrimitive.long)
        assertEquals(530.0, scene["marker_duration_ms"]!!.jsonPrimitive.double, 1e-9)
        // 16 frames of 25 ms and 4 of 50 ms.
        assertEquals(600.0, scene["frames_duration_ms"]!!.jsonPrimitive.double, 1e-9)
        assertTrue((scene["coverage_notes"] as JsonArray).isEmpty())
    }

    @Test
    fun pausedAndMissingFramesAreReportedNotUsed() {
        val scene = analyze(
            listOf(marker("scene_start", 10, 1240), marker("scene_end", 31, 1770)),
            listOf(frameLine(1..50, pauseAt = 15, missing = setOf(20, 21)))
        ).single()
        assertEquals(18L, scene["frames_recorded"]!!.jsonPrimitive.long)
        assertEquals(17L, scene["frames_used"]!!.jsonPrimitive.long)
        val notes = (scene["coverage_notes"] as JsonArray).map { it.jsonPrimitive.content }
        assertTrue(notes.any { it.startsWith("2 frames of the scene are not in frames.jsonl") })
        assertTrue(notes.any { it.startsWith("1 frames span a pause") })
    }

    @Test
    fun passEndsAreAggregatedByReasonWithUnknownKept() {
        val scene = analyze(
            listOf(marker("scene_start", 0, 1000), marker("scene_end", 51, 2300)),
            listOf(frameLine(1..50))
        ).single()
        val ends = scene["render_pass_ends"]!!.jsonObject
        assertEquals(setOf("copy", "readback", "unknown"), ends.keys)
        val readback = ends["readback"]!!.jsonObject
        assertEquals(40L * 1 + 10L * 6, readback["total"]!!.jsonPrimitive.long)
        assertEquals(100L, readback["reopened_same_target"]!!.jsonPrimitive.long)
        assertEquals(100L, ends["unknown"]!!.jsonObject["total"]!!.jsonPrimitive.long)
        // GPU time only from frames with complete results: frame 7 is missing.
        assertEquals(98.0, scene["gpu_coverage_pct"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun slowFramesAreComparedAsCoOccurrence() {
        val scene = analyze(
            listOf(marker("scene_start", 0, 1000), marker("scene_end", 51, 2300)),
            listOf(frameLine(1..50))
        ).single()
        val slow = scene["slow_frames"]!!.jsonObject
        assertEquals(37.5, slow["threshold_ms"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(10L, slow["slow"]!!.jsonPrimitive.long)
        val readback = slow["mean_per_frame"]!!.jsonObject["end_readback"]!!.jsonObject
        assertEquals(6.0, readback["ratio"]!!.jsonPrimitive.double, 1e-9)
        // Equal in both groups: kept with ratio 1, not hidden.
        val copy = slow["mean_per_frame"]!!.jsonObject["end_copy"]!!.jsonObject
        assertEquals(1.0, copy["ratio"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(10, slow["slowest"]!!.jsonArray.size)
        assertTrue(slow["note"]!!.jsonPrimitive.content.contains("AB/BA"))
        val lines = SceneAnalysis.summaryLines(JsonArray(listOf(scene)))
        assertTrue(lines.any { it.contains("end_readback x6.0") && it.contains("hypothesis") })
    }

    @Test
    fun sceneWithoutEndRunsToTheLastFrameAndSaysSo() {
        val scene = analyze(
            listOf(marker("scene_start", 40, 2000)),
            listOf(frameLine(1..50))
        ).single()
        assertEquals(10L, scene["frames_used"]!!.jsonPrimitive.long)
        assertTrue(
            (scene["coverage_notes"] as JsonArray).any {
                it.jsonPrimitive.content.startsWith("no end marker")
            }
        )
    }

    @Test
    fun systemMetricsComeFromSamplesInsideTheScene() {
        val samples = listOf(900L, 1500L, 2500L).map { mono ->
            Json.parseToJsonElement(
                """{"mono_ms":$mono,"android":{"system":{"gpu_clock_mhz_kgsl":${mono / 10},
                   "thermal_status":${if (mono == 1500L) 2 else 0}}}}"""
            ).jsonObject
        }
        val scene = analyze(
            listOf(marker("scene_start", 0, 1000), marker("scene_end", 51, 2300)),
            listOf(frameLine(1..50)),
            samples
        ).single()
        val system = scene["system"]!!.jsonObject
        assertEquals(1L, system["samples"]!!.jsonPrimitive.long)
        assertEquals(
            2.0,
            system["thermal_status"]!!.jsonObject["max"]!!.jsonPrimitive.double,
            1e-9
        )
    }

    @Test
    fun noScenesGiveAnEmptyListAndAHint() {
        assertTrue(analyze(emptyList(), listOf(frameLine(1..5))).isEmpty())
        assertTrue(SceneAnalysis.summaryLines(JsonArray(emptyList())).single().contains("Mark"))
    }

    @Test
    fun frameFileStopsAtItsLimitAndCountsTheRest() {
        val dir = Files.createTempDirectory("eden-frames").toFile()
        try {
            val file = File(dir, DiagnosticsSchema.FRAMES_FILE)
            val writer = FrameFileWriter(file, "c", maxBytes = 600)
            val drain = """{"columns":["frame"],"gpu_states":[],"row_count":3,""" +
                """"rows":[[1],[2],[3]]}"""
            writer.append(drain, 1)
            writer.append("""{"columns":["frame"],"row_count":0,"rows":[]}""", 2)
            writer.append(drain.replace("[1],[2],[3]", "[4],[5],[6]"), 3)
            writer.append(
                drain.replace("[1],[2],[3]", "[7],[8],[9]".repeat(1)) + " ".repeat(500),
                4
            )
            writer.append(drain.replace("[1],[2],[3]", "[10],[11],[12]"), 5)
            writer.append("""{"error":"native exception"}""", 6)
            assertTrue(file.length() <= 600)
            assertEquals(6L, writer.rowsWritten)
            assertEquals(6L, writer.rowsNotWritten) // nothing written after the first refusal
            assertEquals(1L, writer.drainErrors)
            val table = SceneAnalysis.readFrames(file.readLines().asSequence())
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), table.rows.map { it[0] })
            val status = writer.status()
            assertEquals(6L, status["rows_not_written_size_limit"]!!.jsonPrimitive.long)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedLinesAreSkipped() {
        val table = SceneAnalysis.readFrames(
            sequenceOf(
                "{\"frames\":{\"columns\":[\"frame\"],\"rows\":[[1],[2,3],\"x\"]}}",
                "{bad",
                ""
            )
        )
        assertEquals(1, table.rows.size)
        assertNull(table.column("gpu_us"))
        assertFalse(table.columns.isEmpty())
    }
}
