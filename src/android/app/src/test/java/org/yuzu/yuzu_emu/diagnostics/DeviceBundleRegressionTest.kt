// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads schema 1 bundles exported from a device. Bundles hold user data, so they are not part
 * of the repository: set EDEN_DIAG_FIXTURES to a directory with the .zip files to run this.
 * The bundles are only read, never modified.
 */
class DeviceBundleRegressionTest {
    private val fixtures = System.getenv("EDEN_DIAG_FIXTURES")?.let(::File)

    private fun bundles(): List<File> {
        assumeTrue(fixtures?.isDirectory == true)
        return fixtures!!.listFiles { f -> f.name.endsWith(".zip") }.orEmpty().sortedBy { it.name }
    }

    private fun report(zip: File): JsonObject = ZipFile(zip).use {
        Json.parseToJsonElement(
            it.getInputStream(it.getEntry("report.json")).reader().readText()
        ).jsonObject
    }

    @Test
    fun schemaOneEventsOfEarlierCapturesBecomeContext() {
        for (zip in bundles()) {
            val report = report(zip)
            assertEquals("1", report["schema_version"]!!.jsonPrimitive.content)
            val summary = report["native_summary"]!!.jsonObject
            val start = summary["capture_start_mono_ms"]!!.jsonPrimitive.content.toLong()
            val duration = report["session"]!!.jsonObject["duration_ms"]!!.jsonPrimitive
                .content.toLong()
            val events = report["events"]!!.jsonObject["native"]!!.jsonArray.map { it.jsonObject }
            val scoped = SessionIntegrity.scopeNativeEvents(events, null, start, start + duration)
            scoped.capture.forEach {
                val mono = it["mono_ms"]!!.jsonPrimitive.content.toLong()
                assertTrue(mono >= start && mono <= start + duration)
            }
            assertEquals(events.size, scoped.capture.size + scoped.context.size)
        }
    }

    @Test
    fun logOfEachBundleIsLocatedAndItsGapsReported() {
        for (zip in bundles()) {
            val id = report(zip)["session"]!!.jsonObject["session_id"]!!.jsonPrimitive.content
            val dir = Files.createTempDirectory("eden-bundle").toFile()
            try {
                val log = File(dir, "eden_log.txt")
                ZipFile(zip).use { z ->
                    z.getInputStream(z.getEntry("logs/eden_log.txt")).use { input ->
                        log.outputStream().use { input.copyTo(it) }
                    }
                }
                val segment = LogSegmentExtractor.extract(
                    listOf(LogSource("current_process_log", log)),
                    id
                )
                val coverage = segment.coverage
                assertEquals("true", coverage["capture_start_found"]!!.jsonPrimitive.content)
                // These logs were copied without a flush: the stop line is missing and the file
                // ends mid-line, which must be reported rather than hidden.
                assertEquals("false", coverage["capture_stop_found"]!!.jsonPrimitive.content)
                assertEquals("true", coverage["source_ends_mid_line"]!!.jsonPrimitive.content)
                assertFalse(coverage["complete"]!!.jsonPrimitive.content.toBoolean())
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
