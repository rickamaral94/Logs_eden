// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIntegrityTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun eventsAreScopedByCaptureSequence() {
        val events = listOf(
            obj("""{"name":"long_frame","capture_seq":1,"during_capture":true,"mono_ms":100}"""),
            obj("""{"name":"init","capture_seq":0,"during_capture":false,"mono_ms":150}"""),
            obj("""{"name":"long_frame","capture_seq":2,"during_capture":true,"mono_ms":300}""")
        )
        val scoped = SessionIntegrity.scopeNativeEvents(events, 2, 250, 400)
        assertEquals(1, scoped.capture.size)
        assertEquals(2, scoped.context.size)
        // An event of an earlier capture no longer claims to be part of this one.
        assertEquals("false", scoped.context[0]["during_capture"]!!.jsonPrimitive.content)
    }

    @Test
    fun schemaOneEventsFallBackToTheCaptureInterval() {
        // Schema 1 reports carry during_capture=true for events of an earlier capture.
        val events = listOf(
            obj("""{"name":"long_frame","during_capture":true,"mono_ms":80106449}"""),
            obj("""{"name":"long_frame","during_capture":true,"mono_ms":80541000}""")
        )
        val scoped = SessionIntegrity.scopeNativeEvents(events, null, 80526643, 80839030)
        assertEquals(1, scoped.capture.size)
        assertEquals("mono_interval", scoped.capture[0]["scope_method"]!!.jsonPrimitive.content)
        assertEquals("false", scoped.context[0]["during_capture"]!!.jsonPrimitive.content)
    }

    @Test
    fun exitReasonsAreLinkedOnlyWithEvidence() {
        val exits = Json.parseToJsonElement(
            """[{"pid":10,"timestamp_ms":5000,"reason":"crash_native","status":0},
                {"pid":10,"timestamp_ms":500,"reason":"low_memory","status":0},
                {"pid":11,"timestamp_ms":6000,"reason":"signaled","status":6}]"""
        )
        val linked = SessionIntegrity.linkExitReasons(exits, 10, 1000).jsonArray
            .map { it.jsonObject }
        assertEquals("capture_process", linked[0]["relation"]!!.jsonPrimitive.content)
        assertEquals("native_crash", linked[0]["category"]!!.jsonPrimitive.content)
        assertEquals("history", linked[1]["relation"]!!.jsonPrimitive.content)
        assertEquals("low_memory_kill", linked[1]["category"]!!.jsonPrimitive.content)
        assertEquals("abort", linked[2]["category"]!!.jsonPrimitive.content)
        assertEquals("history", linked[2]["relation"]!!.jsonPrimitive.content)

        val end = SessionIntegrity.endClassification(
            obj("""{"end_reason":"process_ended_during_capture"}"""),
            emptyList(),
            JsonArray(linked)
        )
        assertEquals("process_ended:native_crash", end["classification"]!!.jsonPrimitive.content)
    }

    @Test
    fun endClassificationSeparatesCauses() {
        fun classify(reason: String, critical: List<String> = emptyList()) =
            SessionIntegrity.endClassification(
                obj("""{"end_reason":"$reason"}"""),
                critical,
                JsonArray(emptyList())
            )["classification"]!!.jsonPrimitive.content
        assertEquals("user_stopped_capture", classify("user"))
        assertEquals("game_exit", classify("emulation_stopped"))
        assertEquals("capture_limit_reached", classify("duration_limit"))
        assertEquals("vk_error_device_lost", classify("user", listOf("device_lost")))
        assertEquals("process_ended:unknown", classify("process_ended_during_capture"))
    }

    @Test
    fun identityChangesListStartAndEnd() {
        val start = obj(
            """{"game":{"program_id":"A"},"game_run_id":1,
              "settings":[{"name":"resolution_setup","value":"1"}]}"""
        )
        val end = obj(
            """{"game":{"program_id":"B"},"game_run_id":2,
              "settings":[{"name":"resolution_setup","value":"2"}]}"""
        )
        val changes = SessionIntegrity.identityChanges(start, end).map {
            (it as JsonObject)["field"]!!.jsonPrimitive.content
        }
        assertTrue(
            changes.containsAll(
                listOf("game.program_id", "game_run_id", "settings.resolution_setup")
            )
        )
        assertTrue(SessionIntegrity.identityChanges(start, start).isEmpty())
    }
}
