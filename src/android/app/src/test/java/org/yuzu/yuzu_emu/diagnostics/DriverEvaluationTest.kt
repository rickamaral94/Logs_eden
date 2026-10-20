// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverEvaluationTest {
    private val report = Json.parseToJsonElement(
        """
        {"vulkan":{"device":{"driver":{"id":"VK_DRIVER_ID_MESA_TURNIP","info":"Mesa 26.3"},
           "device_name":"Turnip Adreno (TM) 740","driver_version_raw":109064192,
           "extensions_removed_by_eden":[{"extension":"VK_KHR_timeline_semaphore",
             "decision":"removed"}],
           "effective_paths":{"timeline_semaphore":false}}},
         "settings":[{"name":"renderer_force_max_clock","value":"true"},
                     {"name":"language_index","value":"1"}],
         "driver_environment":{"TU_DEBUG":"perf"},
         "native_summary":{
           "counters":{"pipeline_feedback_valid":10,"driver_pipeline_cache_hits":4},
           "timers":{"graphics_pipeline_create":{"count":3,"max_us":400000}},
           "render_passes":{"passes":100,"empty_passes":20,"clear_only_passes":15,
             "same_target_reopen":30,"same_target_reopen_after":{"outside_operation":30},
             "attachment_loads":150,"attachment_clears":50,"attachment_stores":200,
             "passes_per_frame_avg":100.0,"draws_per_pass_avg":4.5,
             "shapes":{"top":[{"passes":40,"width":1920,"height":1080,
               "description":"c0=A8B8G8R8_UNORM(load,store) x1","draws_per_pass_avg":9.0,
               "empty_passes":0}]}}},
         "android_summary":{"gpu_clock_mhz_kgsl":{"avg":401.0},
                            "gpu_max_clock_mhz_kgsl":{"max":680.0}},
         "gpu_timing":{"available":true,"session":{"frame_gpu":{"p50_ms":12.5}}},
         "events":{"native":[
           {"name":"slow_graphics_pipeline_create","severity":"warning",
            "fields":{"pipeline_hash":"abc","ms":"400","driver_cache":"miss"}},
           {"name":"vulkan_device_lost","severity":"critical","category":"vulkan"}]},
         "driver_log":{"available":true,"messages":[
           {"tag":"TU","count":50,"example":"Disabling LRZ because of blend"},
           {"tag":"TU","count":7,"example":"Using sysmem: unaligned render area"},
           {"tag":"libc","count":3,"example":"something else"}]},
         "session_end":{"classification":"vk_error_device_lost"}}
        """
    ).jsonObject

    @Test
    fun categoriesFollowTurnipPerfMessages() {
        assertEquals("lrz", DriverEvaluation.categorize("Disabling LRZ because of blend"))
        assertEquals("sysmem", DriverEvaluation.categorize("Using sysmem: unaligned area"))
        assertEquals("other", DriverEvaluation.categorize("hello"))
        assertTrue(DriverEvaluation.isDriverTag("TU"))
        assertFalse(DriverEvaluation.isDriverTag("libc"))
    }

    @Test
    fun evaluationSeparatesObservationsAndHypotheses() {
        val eval = DriverEvaluation.build(report)
        val derived = eval["render_passes"]!!.jsonObject["derived"]!!.jsonObject
        assertEquals(20.0, derived["empty_pass_pct"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(
            1.5,
            derived["attachment_loads_per_pass"]!!.jsonPrimitive.content.toDouble(),
            1e-9
        )

        val observations = (eval["observations"] as JsonArray).map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertTrue(
            observations.containsAll(
                listOf(
                    "empty_render_passes",
                    "same_target_reopened",
                    "gpu_clock_below_limit",
                    "slow_pipeline_creates",
                    "driver_messages",
                    "device_lost"
                )
            )
        )
        val hypotheses = (eval["hypotheses"] as JsonArray).map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertTrue(hypotheses.contains("governor_limits_gpu"))
        assertTrue(hypotheses.contains("sysmem_fallbacks"))

        val messages = eval["driver_messages"]!!.jsonObject
        assertEquals("true", messages["perf_messages_enabled"]!!.jsonPrimitive.content)
        val lrz = messages["by_category"]!!.jsonObject["lrz"]!!.jsonObject
        assertEquals(50.0, lrz["count"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(
            "3.0",
            messages["other_tags"]!!.jsonObject["libc"]!!.jsonPrimitive.content
        )

        val settings = eval["driver"]!!.jsonObject["eden_settings"]!!.jsonObject
        assertTrue(settings.containsKey("renderer_force_max_clock"))
        assertFalse(settings.containsKey("language_index"))
        assertEquals(
            40.0,
            eval["pipelines"]!!.jsonObject["driver_cache_hit_pct"]!!.jsonPrimitive.content
                .toDouble(),
            1e-9
        )

        val lines = DriverEvaluation.summaryLines(eval)
        assertTrue(lines.first().contains("VK_DRIVER_ID_MESA_TURNIP"))
        assertTrue(lines.any { it.contains("Removed by Eden: VK_KHR_timeline_semaphore") })
        assertTrue(lines.any { it.startsWith("Hypothesis: ") })
    }

    @Test
    fun missingSectionsAreReportedNotGuessed() {
        val eval = DriverEvaluation.build(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(
            "false",
            eval["render_passes"]!!.jsonObject["available"]!!.jsonPrimitive.content
        )
        assertTrue((eval["observations"] as JsonArray).isEmpty())
        DriverEvaluation.summaryLines(eval) // does not throw
    }
}
