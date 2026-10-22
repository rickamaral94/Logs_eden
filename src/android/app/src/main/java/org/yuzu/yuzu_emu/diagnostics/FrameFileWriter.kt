// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Appends the per host frame records drained from native code to frames.jsonl, one line per
 * drain, up to [maxBytes]. Past the limit rows are counted instead of written, so a long capture
 * keeps its samples and events and the report states how many frames are missing.
 */
class FrameFileWriter(
    private val file: File,
    private val captureId: String,
    private val maxBytes: Long = MAX_BYTES
) {
    var rowsWritten = 0L
        private set
    var rowsNotWritten = 0L
        private set
    var drainErrors = 0L
        private set

    /** [nativeJson] is the output of NativeDiagnostics.takeFrames. */
    fun append(nativeJson: String, monoMs: Long) {
        val rows = ROW_COUNT.find(nativeJson)?.groupValues?.get(1)?.toLongOrNull()
        if (rows == null) {
            drainErrors++
            return
        }
        if (rows == 0L) {
            return
        }
        val line = "{\"capture_id\":${JsonPrimitive(captureId)},\"mono_ms\":$monoMs," +
            "\"frames\":$nativeJson}\n"
        val bytes = line.toByteArray()
        if (rowsNotWritten > 0 || file.length() + bytes.size > maxBytes) {
            rowsNotWritten += rows
            return
        }
        FileOutputStream(file, true).use { it.write(bytes) }
        rowsWritten += rows
    }

    fun status(): JsonObject = buildJsonObject {
        put("file", DiagnosticsSchema.FRAMES_FILE)
        put("rows_written", rowsWritten)
        put("rows_not_written_size_limit", rowsNotWritten)
        put("drain_errors", drainErrors)
        put("max_bytes", maxBytes)
        put("bytes", file.length())
    }

    companion object {
        const val MAX_BYTES = 24L * 1024L * 1024L
        private val ROW_COUNT = Regex("\"row_count\":(\\d+)")
    }
}
