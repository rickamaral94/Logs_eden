// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverLogAggregatorTest {
    private val start = 1_760_000_000_000L

    private fun line(offsetMs: Long, tag: String, message: String) =
        String.format(
            java.util.Locale.ROOT,
            "%d.%03d  4321  4400 W %-8s: %s",
            (start + offsetMs) / 1000,
            (start + offsetMs) % 1000,
            tag,
            message
        )

    @Test
    fun parsesEpochLines() {
        val parsed = DriverLogAggregator.parse(line(1500, "TU", "perf: sysmem fallback, 3 bins"))!!
        assertEquals(start + 1500, parsed.epochMs)
        assertEquals(4400, parsed.tid)
        assertEquals('W', parsed.priority)
        assertEquals("TU", parsed.tag)
        assertEquals("perf: sysmem fallback, 3 bins", parsed.message)
        assertNull(DriverLogAggregator.parse("--------- beginning of main"))
    }

    @Test
    fun groupsMessagesThatDifferOnlyInNumbers() {
        val aggregator = DriverLogAggregator(start)
        listOf(
            line(1000, "TU", "perf: LRZ disabled for depth write at 0x7a00 draw 12"),
            line(2000, "TU", "perf: LRZ disabled for depth write at 0x7b10 draw 40"),
            line(3000, "MESA", "shader cache hit")
        ).forEach { aggregator.add(DriverLogAggregator.parse(it)!!) }
        val digest = aggregator.digest()
        val messages = digest["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, messages.size)
        assertEquals("2", messages[0]["count"]!!.jsonPrimitive.content)
        assertEquals(1.0, messages[0]["first_t_s"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(2.0, messages[0]["last_t_s"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("2", digest["by_tag"]!!.jsonObject["TU"]!!.jsonPrimitive.content)
        assertTrue(aggregator.rawText().startsWith("    1.000"))
    }

    @Test
    fun rawTextAndKeysAreBounded() {
        val aggregator = DriverLogAggregator(start, maxRawBytes = 200, maxKeys = 2)
        repeat(20) { aggregator.add(DriverLogAggregator.parse(line(it.toLong(), "T$it", "m"))!!) }
        assertEquals(20L, aggregator.lines)
        assertTrue(aggregator.rawText().length <= 200)
        assertTrue(aggregator.rawDropped > 0)
        assertEquals(18L, aggregator.keysDropped)
    }
}
