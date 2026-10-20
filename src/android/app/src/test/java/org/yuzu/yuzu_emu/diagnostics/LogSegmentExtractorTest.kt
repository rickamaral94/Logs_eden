// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mirrors the layout of the device logs that motivated the extractor: one process lifetime
 * with several titles, two captures, and a file that ends mid-line while still being written.
 */
class LogSegmentExtractorTest {
    private lateinit var root: File
    private lateinit var current: File

    private fun line(t: Double, text: String) =
        String.format(java.util.Locale.ROOT, "[%10.6f] %s\n", t, text)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("eden-log-test").toFile()
        current = File(root, "eden_log.txt")
        current.writeText(
            line(0.1, "Frontend <Info> boot") +
                line(61.5, "Debug.Emulated <Critical> svc_exception.cpp:56: Userspace PANIC!") +
                line(101.9, "Core <Info> core.cpp:351:Load: Loading Title A") +
                line(111.0, "Frontend <Info> [Diagnostics] Capture A-1 started (standard)") +
                line(117.1, "Service.FS <Warning> fs_i_filesystem.cpp:138: (STUBBED) called") +
                line(150.0, "Render.Vulkan <Error> vulkan_wrapper.cpp:10:Check: VK_TIMEOUT") +
                line(482.5, "Frontend <Info> [Diagnostics] Capture A-1 stopped (user)") +
                line(492.4, "Debug <Critical> host_memory.cpp:770: assert virtual_offset") +
                line(537.6, "Frontend <Info> [Diagnostics] Capture B-2 started (standard)") +
                line(549.6, "HW.GPU <Info> nvdec.cpp:33:~Nvdec: Destroying nvdec 9") +
                "[ 549.625239] HW.GPU <Info> video_core/host1x/"
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.content == "true"

    @Test
    fun completedCaptureIsCutWithLabelledContext() {
        val segment = LogSegmentExtractor.extract(
            listOf(LogSource("current_process_log", current)),
            "A-1"
        )
        val text = segment.text!!
        val coverage = segment.coverage
        assertTrue(coverage.bool("capture_start_found"))
        assertTrue(coverage.bool("capture_stop_found"))
        assertTrue(coverage.bool("complete"))
        val captureStart = text.indexOf("---- capture start")
        val captureStop = text.indexOf("---- capture stop")
        assertTrue(captureStart in 0 until captureStop)
        // The older title's panic is context, before the capture start label.
        assertTrue(text.indexOf("Userspace PANIC") in 0 until captureStart)
        // Nothing from the next title or capture is included.
        assertFalse(text.contains("assert virtual_offset"))
        assertFalse(text.contains("B-2"))
        assertEquals(
            111.0,
            coverage["capture_start_log_s"]!!.jsonPrimitive.content.toDouble(),
            1e-6
        )
        assertEquals(482.5, coverage["capture_stop_log_s"]!!.jsonPrimitive.content.toDouble(), 1e-6)

        val digest = segment.digest
        val capture = digest["capture"]!!.jsonObject["patterns"]!!.jsonObject
        assertEquals("1", capture["unimplemented"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        assertEquals("1", capture["vulkan_error"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        assertFalse(capture.containsKey("guest_panic"))
        val context = digest["context_before_capture"]!!.jsonObject["patterns"]!!.jsonObject
        assertTrue(context.containsKey("guest_panic"))
    }

    @Test
    fun captureWithoutStopLineIsReportedIncomplete() {
        val segment = LogSegmentExtractor.extract(
            listOf(LogSource("current_process_log", current)),
            "B-2"
        )
        val coverage = segment.coverage
        assertTrue(coverage.bool("capture_start_found"))
        assertFalse(coverage.bool("capture_stop_found"))
        assertFalse(coverage.bool("complete"))
        assertTrue(coverage.bool("source_ends_mid_line"))
        assertTrue(segment.text!!.contains("capture stop line not found"))
        assertTrue(segment.text!!.contains("ends mid-line"))
    }

    @Test
    fun previousProcessLogIsUsedWhenTheCaptureIsThere() {
        val previous = File(root, "eden_log.txt.old.txt").apply { writeText(current.readText()) }
        current.writeText(line(0.2, "Frontend <Info> new process"))
        val segment = LogSegmentExtractor.extract(
            listOf(
                LogSource("current_process_log", current),
                LogSource("previous_process_log", previous)
            ),
            "A-1"
        )
        assertEquals("previous_process_log", segment.coverage["source"]!!.jsonPrimitive.content)
        assertFalse(segment.text!!.contains("new process"))
    }

    @Test
    fun missingCaptureIsUnavailableInsteadOfAWrongLog() {
        val segment = LogSegmentExtractor.extract(
            listOf(LogSource("current_process_log", current)),
            "C-3"
        )
        assertNull(segment.text)
        assertFalse(segment.coverage.bool("available"))
    }

    @Test
    fun oversizedCaptureKeepsHeadAndTail() {
        val big = StringBuilder()
        big.append(line(1.0, "Frontend <Info> [Diagnostics] Capture D-4 started (standard)"))
        repeat(2000) { big.append(line(2.0 + it, "Service.SET <Info> spam line number $it")) }
        big.append(line(9000.0, "Frontend <Info> [Diagnostics] Capture D-4 stopped (user)"))
        current.writeText(big.toString())
        val segment = LogSegmentExtractor.extract(
            listOf(LogSource("current_process_log", current)),
            "D-4",
            maxBytes = 16 * 1024
        )
        val omitted = segment.coverage["omitted_bytes"]!!.jsonPrimitive.content.toLong()
        assertTrue(omitted > 0)
        assertFalse(segment.coverage.bool("complete"))
        assertTrue(segment.text!!.contains("spam line number 0"))
        assertTrue(segment.text!!.contains("Capture D-4 stopped"))
    }

    @Test
    fun repeatedLinesAreCollapsedAndStillCounted() {
        val text = line(1.0, "Render.Vulkan <Error> vk_scheduler.cpp:1: same") +
            line(1.1, "Render.Vulkan <Error> vk_scheduler.cpp:1: same") +
            line(1.2, "Render.Vulkan <Error> vk_scheduler.cpp:1: same") +
            line(1.3, "Frontend <Info> other")
        val (collapsed, removed) = LogSegmentExtractor.collapseRepeats(text)
        assertEquals(2L, removed)
        assertEquals(
            line(1.0, "Render.Vulkan <Error> vk_scheduler.cpp:1: same") +
                "#diag: previous line repeated 2 more times\n" +
                line(1.3, "Frontend <Info> other"),
            collapsed
        )
        val digest = LogSegmentExtractor.digest(collapsed)
        assertEquals(4, digest["lines"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            3,
            digest["patterns"]!!.jsonObject["vulkan_error"]!!.jsonObject["count"]!!
                .jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun findLineReturnsByteOffsets() {
        val range = LogSegmentExtractor.findLine(current, "Capture A-1 stopped")!!
        val bytes = current.readBytes()
        val text = String(bytes, range.first.toInt(), (range.second - range.first).toInt())
        assertTrue(text.startsWith("["))
        assertTrue(text.endsWith("(user)\n"))
    }
}
