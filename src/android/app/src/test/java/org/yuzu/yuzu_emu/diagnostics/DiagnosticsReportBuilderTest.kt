// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsReportBuilderTest {
    private lateinit var root: File
    private lateinit var session: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("eden-diag-test").toFile()
        session = File(root, "sessions/20260101-000000-0001").apply { mkdirs() }
        File(session, DiagnosticsSchema.SESSION_FILE).writeText(
            """{"session_format_version":2,"session_id":"20260101-000000-0001",
            "capture_id":"20260101-000000-0001","native_capture_seq":2,"state":"completed",
            "started_at_utc":"2026-01-01T00:00:00.000Z","level":"standard","duration_ms":6000,
            "interval_ms":2000,"process":{"process_instance_id":"boot1-pid10-start5","pid":10},
            "end_reason":"user","stage_note":"Boss fight in /storage/emulated/0/secret"}"""
        )
        File(session, DiagnosticsSchema.STATIC_START_FILE).writeText(
            """{"app":{"version_name":"test"},
            "device":{"identity":{"manufacturer":"Maker","model":"Phone 1"},"android_api":34},
            "graphics_driver":{"selection":"system"},
            "game":{"known":true,"title":"Test Title","program_id":"0100000000010000"},
            "native":{"build":{"version":"v0"},"cpu_backend":"NCE",
              "settings":[{"name":"serial_unit","value":"217324292"},
                {"name":"resolution_setup","value":"2"}],
              "sections":{"vulkan_device":{"device_name":"GPU X","api_version":"1.3.0"}},
              "events":[]},
            "metric_definitions":{}}"""
        )
        File(session, DiagnosticsSchema.STATIC_END_FILE).writeText(
            """{"native_summary":{"frames":{"guest_present_interval":{"count":10,"p99_ms":40.0},
                "host_present_interval":{"count":20}},
              "counters":{"graphics_pipeline_cache_miss":3,"scheduler_submits":30,"draw_calls":600},
              "timers":{"fence_wait":{"count":3,"total_us":9000,"max_us":5000}},"capture_seq":2,
              "fence_wait_by_reason":{"swapchain_image":{"count":2,"total_us":8000,"max_us":5000},
                "guest_sync":{"count":1,"total_us":1000,"max_us":1000}},
              "fence_wait_by_thread":{"VulkanPresent":{"count":3,"total_us":9000,"max_us":5000}},
              "gpu_timing":{"available":true,"coverage_pct":98.5,"frames_complete":170,
                "frames_partial":3,"frame_gpu":{"count":170,"p50_ms":12.5,"p99_ms":21.0}},
              "capture_start_mono_ms":1000,"capture_stop_mono_ms":7000,"capturing":false},
            "native":{"build":{"version":"v0"},"cpu_backend":"NCE","settings":[],
              "sections":{"vulkan_device":{"device_name":"GPU X","api_version":"1.3.0"},
                "shader_cache":{"pipelines_in_file":5,"pipelines_restored":5}},
              "events":[{"seq":1,"name":"long_frame","capture_seq":1,"mono_ms":500},
                {"seq":2,"name":"check_failed","capture_seq":2,"mono_ms":2000,
                 "fields":{"function":"vk::Device::Create"}}]}}"""
        )
        val samples = (1..3).joinToString("\n") { i ->
            """{"t_ms":${i * 2000},"mono_ms":${1000 + i * 2000},
            "native":{"window_ms":2000,"frames":{"guest_present_interval":{"count":60,"max_ms":${if (i == 2) 120.0 else 17.0},"over_50ms":${if (i == 2) 1 else 0}},
              "host_present_interval":{"count":60}},
              "counters":{"game_frames":30,"graphics_pipeline_cache_miss":${if (i == 2) 3 else 0}},
              "timers":{"pipeline_build_wait":{"count":1,"total_us":${i * 1000},"max_us":${i * 1000}}},
              "gpu_timing":{"available":true,"submit_busy_union_ms":${i * 500}},
              "samplers":{"vulkan_memory_budget":{"available":true,"heaps":[{"index":0,"usage_bytes":${i * 100},"budget_bytes":1000}]}}},
            "android":{"process":{"cpu_pct_of_one_core":${100 + i},"memory":{"rss_kb":${1000 * i},"rss_peak_kb":${1000 * i}},
              "thread_categories":{"emu_cpu":80.0},"gc":{"gc_count":1}},
              "system":{"mem_available_kb":${5000 - i},"thermal_status":$i,"low_memory":false},
              "availability":{"gpu_busy":"unavailable: kgsl gpubusy not readable"},"sampler_cost_ms":1.5}}"""
                .replace("\n", "")
        }
        // An interrupted capture can leave a truncated last line.
        File(session, DiagnosticsSchema.SAMPLES_FILE).writeText(samples + "\n{\"t_ms\":80")
        File(session, DiagnosticsSchema.EVENTS_FILE).writeText(
            """{"t_ms":0,"type":"capture_started"}
            {"t_ms":4100,"type":"user_marker","marker_id":42,"category":"flicker","note":"see 192.168.0.2 /sdcard/x"}
            """.trimIndent() + "\n"
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val sessionId = "20260101-000000-0001"

    private fun build(options: ExportOptions = ExportOptions()): BundlePlan {
        val log = File(root, "eden_log.txt").apply {
            writeText(
                "[   1.0] Core <Info> line /storage/emulated/0/Roms/a.nsp\n" +
                    "[   2.0] Frontend <Info> [Diagnostics] Capture $sessionId started\n" +
                    "[   3.0] HW.Memory <Error> Unmapped Device ReadBlock @ 0x0\n" +
                    "[   9.0] Frontend <Info> [Diagnostics] Capture $sessionId stopped\n" +
                    "[  10.0] Core <Info> next title\n"
            )
        }
        val inputs = ExportInputs(
            sessionDir = session,
            edenLog = log,
            gpuLog = null,
            gpuCrashDumps = emptyList(),
            exitReasons = JsonArray(emptyList()),
            generatedAtUtc = "2026-01-01T00:10:00.000Z"
        )
        return DiagnosticsReportBuilder(DiagnosticsRedactor(), options).build(inputs)
    }

    @Test
    fun reportHasStableTopLevelFields() {
        val report = build().report
        for (field in listOf(
            "schema_version", "generated_at_utc", "session", "app", "device", "graphics_driver",
            "game", "build", "settings", "vulkan", "native_summary", "android_summary",
            "frame_rate", "markers", "events", "exit_reasons", "availability", "contents", "privacy"
        )) {
            assertTrue("missing $field", report.containsKey(field))
        }
        assertEquals(
            DiagnosticsSchema.SCHEMA_VERSION,
            report["schema_version"]!!.jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun aggregatesFrameRatesAndMemoryBudget() {
        val report = build().report
        val fps = report["frame_rate"]!!.jsonObject
        assertEquals(30.0, fps["guest_present_fps_avg"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(15.0, fps["game_fps_avg"]!!.jsonPrimitive.content.toDouble(), 0.001)
        val budget = report["vulkan"]!!.jsonObject["memory_budget_peak"]!!.jsonObject
        val heap = budget["heaps"]!!.jsonArray[0].jsonObject
        assertEquals(300L, heap["peak_usage_bytes"]!!.jsonPrimitive.content.toLong())
        val android = report["android_summary"]!!.jsonObject
        assertEquals(3, android["samples"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "4997",
            android["system_mem_available_kb_min"]!!.jsonPrimitive.content.substringBefore('.')
        )
    }

    @Test
    fun markerContextUsesNearbySamplesOnly() {
        val marker = build().report["markers"]!!.jsonArray.single().jsonObject
        val context = marker["context"]!!.jsonObject
        assertEquals("flicker", marker["category"]!!.jsonPrimitive.content)
        assertEquals(3, context["samples"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            120.0,
            context["guest_frame_interval_max_ms"]!!.jsonPrimitive.content.toDouble(),
            0.001
        )
        assertEquals(
            3L,
            context["counters"]!!.jsonObject["graphics_pipeline_cache_miss"]!!
                .jsonPrimitive.content.toLong()
        )
        val note = marker["note"]!!.jsonPrimitive.content
        assertFalse(note.contains("192.168.0.2"))
        assertFalse(note.contains("/sdcard"))
    }

    @Test
    fun hitchesListWindowsOverThreshold() {
        val hitches = build().report["hitches"]!!.jsonObject
        assertEquals(1, hitches["windows_over_threshold"]!!.jsonPrimitive.content.toInt())
        val window = hitches["windows"]!!.jsonArray.single().jsonObject
        assertEquals(4.0, window["t_s"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(
            120.0,
            window["guest_frame_interval_max_ms"]!!.jsonPrimitive.content.toDouble(),
            0.001
        )
        assertEquals(
            2.0,
            window["pipeline_build_wait_max_ms"]!!.jsonPrimitive.content.toDouble(),
            0.001
        )
        assertTrue(build().summary.contains("Longest frame intervals"))
    }

    @Test
    fun personalDataIsRedactedEverywhere() {
        val plan = build()
        val all = plan.entries.joinToString("\n") { String(it.content) }
        assertFalse(all.contains("/storage/emulated/0"))
        assertFalse(all.contains("192.168.0.2"))
        assertTrue(
            plan.report["privacy"]!!.jsonObject["redacted_values"]!!
                .jsonPrimitive.content.toInt() > 0
        )
    }

    @Test
    fun deviceIdentityCanBeRemoved() {
        val withIdentity = build().report["device"]!!.jsonObject
        assertTrue(withIdentity["identity"] is JsonObject)
        val withoutOptions = ExportOptions(includeDeviceIdentity = false)
        val without = build(withoutOptions).report["device"]!!.jsonObject
        assertEquals("removed_by_user", without["identity"]!!.jsonPrimitive.content)
        val summary = build(ExportOptions(includeDeviceIdentity = false)).summary
        assertFalse(summary.contains("Phone 1"))
    }

    @Test
    fun optionalLogsFollowOptions() {
        assertTrue(build().entries.any { it.name == "logs/eden_log.txt" })
        assertFalse(
            build(ExportOptions(includeEdenLog = false)).entries.any { it.name.startsWith("logs/") }
        )
    }

    @Test
    fun unavailableSectionsAreMarked() {
        val vulkan = build().report["vulkan"]!!.jsonObject
        val swapchain = vulkan["swapchain"]!!.jsonObject
        assertEquals(JsonPrimitive(false), swapchain["available"])
        assertTrue(build().report["availability"]!!.jsonObject.containsKey("gpu_busy"))
    }

    @Test
    fun interruptedSessionWithoutEndStateStillBuilds() {
        File(session, DiagnosticsSchema.STATIC_END_FILE).delete()
        val report = build().report
        assertEquals(JsonPrimitive(false), report["native_summary"]!!.jsonObject["available"])
        assertEquals(
            3,
            report["android_summary"]!!.jsonObject["samples"]!!.jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun bundleWriterProducesZipWithAllEntries() {
        val plan = build()
        val destination = File(root, "exports/bundle.zip")
        val result = DiagnosticsBundleWriter.write(plan, destination)
        assertTrue(result is BundleResult.Success)
        ZipFile(destination).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertTrue(
                names.containsAll(
                    setOf(
                        "report.json",
                        "summary.txt",
                        "README.txt",
                        "samples.jsonl",
                        "events.jsonl"
                    )
                )
            )
            val report = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("report.json")).reader().readText()
            )
            assertEquals(
                DiagnosticsSchema.SCHEMA_VERSION,
                report.jsonObject["schema_version"]!!.jsonPrimitive.content.toInt()
            )
        }
        assertFalse(File(root, "exports/bundle.zip.partial").exists())
    }

    @Test
    fun nativeEventsOfOtherCapturesAreContext() {
        val events = build().report["events"]!!.jsonObject
        val capture = events["native"]!!.jsonArray
        val context = events["native_context"]!!.jsonArray
        assertEquals(1, capture.size)
        assertEquals("check_failed", capture[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, context.size)
        assertEquals("false", context[0].jsonObject["during_capture"]!!.jsonPrimitive.content)
    }

    @Test
    fun consoleIdentifiersAreNotExported() {
        val plan = build()
        val all = plan.entries.joinToString("\n") { String(it.content) }
        assertFalse(all.contains("217324292"))
        val names = plan.report["settings"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("resolution_setup"), names)
    }

    @Test
    fun manifestListsFilesWithHashesAndCoverage() {
        val plan = build()
        val manifest = Json.parseToJsonElement(
            String(plan.entries.single { it.name == DiagnosticsSchema.MANIFEST_FILE }.content)
        ).jsonObject
        val files = manifest["files"]!!.jsonArray.map { it.jsonObject }
        val report = files.single { it["name"]!!.jsonPrimitive.content == "report.json" }
        assertEquals(64, report["sha256"]!!.jsonPrimitive.content.length)
        assertTrue(files.any { it["name"]!!.jsonPrimitive.content == "logs/eden_log.txt" })
        assertEquals("3", manifest["samples"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        val log = plan.report["log_coverage"]!!.jsonObject
        assertEquals("true", log["complete"]!!.jsonPrimitive.content)
        val logText = String(plan.entries.single { it.name == "logs/eden_log.txt" }.content)
        assertFalse(logText.contains("next title"))
        assertTrue(logText.contains("Unmapped"))
        assertEquals(
            "user_stopped_capture",
            plan.report["session_end"]!!.jsonObject["classification"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun gpuTimingKeepsItsSemanticsAndAliases() {
        val plan = build()
        val gpu = plan.report["gpu_timing"]!!.jsonObject
        assertEquals("true", gpu["available"]!!.jsonPrimitive.content)
        assertTrue(gpu["semantics"]!!.jsonPrimitive.content.contains("Not GPU utilization"))
        val aliases = gpu["cemu_aliases"]!!.jsonObject
        assertEquals("12.5", aliases["gpuTimePerFrameMs"]!!.jsonPrimitive.content)
        assertEquals("98.5", aliases["gpuCoveragePct"]!!.jsonPrimitive.content)
        val busy = gpu["submit_busy_union_pct_of_window"]!!.jsonObject
        assertEquals(75.0, busy["max"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertTrue(plan.summary.contains("Frame GPU time p50/p90/p99/max ms: 12.5"))
    }

    @Test
    fun cpuWaitsAndWorkloadAreNormalizedPerFrame() {
        val plan = build()
        val waits = plan.report["cpu_waits"]!!.jsonObject
        assertTrue(waits["by_reason"]!!.jsonObject.containsKey("swapchain_image"))
        assertTrue(waits["note"]!!.jsonPrimitive.content.contains("not additive"))
        assertFalse(waits.containsKey("available"))
        val perFrame = plan.report["workload"]!!.jsonObject["per_host_frame"]!!.jsonObject
        assertEquals(30.0, perFrame["draw_calls"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(1.5, perFrame["scheduler_submits"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertTrue(plan.summary.contains("swapchain_image: count=2 total=8000"))
    }

    @Test
    fun configurationHashesIgnoreExcludedSettings() {
        val hashes = build().report["configuration_hashes"]!!.jsonObject
        assertEquals(64, hashes["effective_settings_sha256"]!!.jsonPrimitive.content.length)
        assertEquals("1", hashes["settings_count"]!!.jsonPrimitive.content)
        val driver = build().report["graphics_driver"]!!.jsonObject
        assertEquals(
            "system",
            driver["selected"]!!.jsonObject["selection"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun gpuTimingUnavailableIsExplicit() {
        File(session, DiagnosticsSchema.STATIC_END_FILE).delete()
        val gpu = build().report["gpu_timing"]!!.jsonObject
        assertEquals("false", gpu["available"]!!.jsonPrimitive.content)
    }

    @Test
    fun bundleWriterReportsUnusableDestination() {
        val blocker = File(root, "not_a_directory").apply { writeText("x") }
        val result = DiagnosticsBundleWriter.write(build(), File(blocker, "bundle.zip"))
        assertTrue(result is BundleResult.Failure)
        assertEquals("cannot_create_directory", (result as BundleResult.Failure).reason)
    }
}
