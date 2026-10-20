// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A log file that may hold the capture, with the role it plays (current or previous process). */
class LogSource(val role: String, val file: File)

class LogSegment(val text: String?, val coverage: JsonObject, val digest: JsonObject)

/**
 * Cuts the part of eden_log.txt that belongs to one capture out of the log of a whole process
 * lifetime, which may hold several titles and captures.
 *
 * The capture is located by the "[Diagnostics] Capture <id> started/stopped" lines that
 * DiagnosticsSession writes, not by file offsets alone: the file is rewritten by every process
 * start (the previous one is kept as eden_log.txt.old.txt). Lines before the start line are
 * included as limited, labelled context. Byte offsets refer to the source file.
 */
object LogSegmentExtractor {
    const val DEFAULT_MAX_BYTES = 4L * 1024L * 1024L
    const val DEFAULT_CONTEXT_BYTES = 64L * 1024L

    // Lines after the stop line may belong to the next title or capture: none by default.
    const val DEFAULT_AFTER_BYTES = 0L
    private const val MAX_DIGEST_EXAMPLES = 3
    private const val MAX_EXAMPLE_CHARS = 240

    private val TIMESTAMP = Regex("^\\[\\s*(\\d+\\.\\d+)]")
    private const val REPEAT_PREFIX = "#diag: previous line repeated "
    private const val REPEAT_SUFFIX = " more times"
    private val LEVEL_CLASS = Regex("^\\[[^]]*]\\s+(\\S+)\\s+<(\\w+)>")

    /** Recognizable log patterns. Counting them is classification, not diagnosis. */
    private val PATTERNS = listOf(
        "assert" to Regex("\\bassert\\b|ASSERT", RegexOption.IGNORE_CASE),
        "guest_panic" to Regex("Userspace PANIC|Break: "),
        "unmapped_access" to Regex("Unmapped|unmapped (?:read|write)", RegexOption.IGNORE_CASE),
        "unimplemented" to Regex("Unimplemented|\\(STUBBED\\)|not implemented"),
        "vulkan_error" to Regex("Render\\.Vulkan <(?:Error|Critical)>"),
        "device_lost" to Regex("DEVICE_LOST|device lost", RegexOption.IGNORE_CASE),
        "shader_error" to Regex(
            "Shader <(?:Error|Critical)>|shader.*(?:failed|exception)",
            RegexOption.IGNORE_CASE
        )
    )

    fun startLine(captureId: String) = "[Diagnostics] Capture $captureId started"
    fun stopLine(captureId: String) = "[Diagnostics] Capture $captureId stopped"

    fun extract(
        sources: List<LogSource>,
        captureId: String?,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        contextBytes: Long = DEFAULT_CONTEXT_BYTES,
        afterBytes: Long = DEFAULT_AFTER_BYTES
    ): LogSegment {
        val existing = sources.filter { it.file.isFile }
        if (existing.isEmpty()) {
            return LogSegment(null, unavailableCoverage("no log file"), emptyDigest())
        }
        if (captureId == null) {
            // Snapshot without a capture: the end of the current log, all of it labelled context.
            val source = existing.first()
            return cut(source, null, null, maxBytes, contextBytes = maxBytes, afterBytes = 0)
        }
        for (source in existing) {
            val start = runCatching { findLine(source.file, startLine(captureId)) }.getOrNull()
                ?: continue
            val stop = runCatching { findLine(source.file, stopLine(captureId), start.first) }
                .getOrNull()
            return cut(source, start, stop, maxBytes, contextBytes, afterBytes)
        }
        val coverage = buildJsonObject {
            put("available", false)
            put("reason", "capture start line not found in the current or previous process log")
            put("searched", JsonArray(existing.map { JsonPrimitive(it.role) }))
        }
        return LogSegment(null, coverage, emptyDigest())
    }

    /** Byte range [start, end) of the first line containing [needle] at or after [from]. */
    internal fun findLine(file: File, needle: String, from: Long = 0): Pair<Long, Long>? {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray(64 * 1024)
            // ISO-8859-1 maps bytes to chars one to one, so string indices are byte offsets.
            val window = StringBuilder()
            var windowStart = from
            var eof = false
            while (!eof) {
                val read = raf.read(buffer)
                if (read <= 0) {
                    eof = true
                } else {
                    window.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                }
                val index = window.indexOf(needle)
                if (index >= 0) {
                    val end = window.indexOf('\n', index)
                    if (end < 0 && !eof) {
                        continue
                    }
                    val lineStart = window.lastIndexOf("\n", index) + 1
                    val lineEnd = if (end >= 0) end + 1 else window.length
                    return (windowStart + lineStart) to (windowStart + lineEnd)
                }
                // Keep only the last, possibly incomplete, line.
                val keep = window.lastIndexOf("\n") + 1
                window.delete(0, keep)
                windowStart += keep
            }
            return null
        }
    }

    private fun cut(
        source: LogSource,
        start: Pair<Long, Long>?,
        stop: Pair<Long, Long>?,
        maxBytes: Long,
        contextBytes: Long,
        afterBytes: Long
    ): LogSegment {
        val length = source.file.length()
        val captureStart = start?.first ?: length
        val contextFrom = (captureStart - contextBytes).coerceAtLeast(0)
        val captureEnd = stop?.second ?: length
        val to = (captureEnd + afterBytes).coerceAtMost(length)
        val lastByte = if (length > 0) readRange(source.file, length - 1, length) else ""
        val endsMidLine = length > 0 && lastByte != "\n"

        val context = alignToLineStart(
            readRange(source.file, contextFrom, captureStart),
            contextFrom > 0
        )
        val collapsed = collapseRepeats(readRange(source.file, captureStart, captureEnd))
        var capture = collapsed.first
        var omittedBytes = 0L
        val captureBudget = (maxBytes - context.length).coerceAtLeast(maxBytes / 2)
        if (capture.length > captureBudget) {
            // Keep the beginning (capture start, early errors) and the end (most recent lines).
            val head = (captureBudget / 4).toInt()
            val tail = (captureBudget - head).toInt()
            omittedBytes = (capture.length - head - tail).toLong()
            capture = capture.substring(0, head) +
                "\n#diag: ---- $omittedBytes bytes omitted (size limit) ----\n" +
                capture.substring(capture.length - tail)
        }
        val after = readRange(source.file, captureEnd, to)

        val captureText = start != null
        val text = buildString {
            append("#diag: source=${source.role} source_bytes=$length")
            append(" included=[${captureStart - context.length},$to)")
            append(" capture=[$captureStart,$captureEnd)\n")
            append("#diag: lines before the capture start marker are context, not part of it\n")
            append(context)
            if (captureText) {
                append("#diag: ---- capture start (byte $captureStart) ----\n")
                append(capture)
                if (stop != null) {
                    append("#diag: ---- capture stop (byte $captureEnd) ----\n")
                } else {
                    append("\n#diag: ---- capture stop line not found: log ends here ----\n")
                }
                append(after)
            }
            if (endsMidLine && to == length) {
                append("\n#diag: ---- source file ends mid-line ----\n")
            }
        }.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }

        val coverage = buildJsonObject {
            put("available", true)
            put("source", source.role)
            put("source_bytes", length)
            put("included_from_byte", captureStart - context.length)
            put("included_to_byte", to)
            put("context_bytes_before", context.length)
            put("capture_start_byte", start?.first)
            put("capture_stop_byte", stop?.second)
            put("bytes_after_stop", after.length)
            put("omitted_bytes", omittedBytes)
            put("collapsed_repeated_lines", collapsed.second)
            put("capture_start_found", start != null)
            put("capture_stop_found", stop != null)
            put("source_ends_mid_line", endsMidLine)
            put("first_line_log_s", firstTimestamp(context.ifEmpty { capture }))
            put("last_line_log_s", lastTimestamp(after.ifEmpty { capture }))
            put("capture_start_log_s", start?.let { firstTimestamp(capture) })
            put("capture_stop_log_s", stop?.let { lastTimestamp(capture) })
            put("complete", start != null && stop != null && omittedBytes == 0L)
        }
        val digest = buildJsonObject {
            put("note", "pattern counts from log text; classification only, not a diagnosis")
            put("capture", digest(if (captureText) capture + after else ""))
            put("context_before_capture", digest(context))
        }
        return LogSegment(text, coverage, digest)
    }

    /**
     * Replaces runs of lines that only differ in their timestamp with the first line and a
     * "#diag: previous line repeated N more times" marker. Returns the text and N in total.
     */
    internal fun collapseRepeats(text: String): Pair<String, Long> {
        val out = StringBuilder(text.length)
        var previousKey: String? = null
        var repeats = 0L
        var removed = 0L
        fun flush() {
            if (repeats > 0) {
                out.append(REPEAT_PREFIX).append(repeats).append(REPEAT_SUFFIX).append('\n')
                removed += repeats
                repeats = 0
            }
        }
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            val last = index == lines.lastIndex
            if (last && line.isEmpty()) return@forEachIndexed
            val key = if (line.startsWith("#diag:")) null else TIMESTAMP.replace(line, "")
            if (key != null && key == previousKey) {
                repeats++
                return@forEachIndexed
            }
            flush()
            previousKey = key
            out.append(line)
            if (!last) out.append('\n')
        }
        flush()
        return out.toString() to removed
    }

    /** Counts per level, per log class and per known pattern, with a few example lines. */
    internal fun digest(text: String): JsonObject {
        val levels = sortedMapOf<String, Int>()
        val classes = mutableMapOf<String, Int>()
        val patternCounts = mutableMapOf<String, Int>()
        val patternFirst = mutableMapOf<String, Double?>()
        val examples = mutableMapOf<String, MutableList<String>>()
        var lines = 0
        var previous: String? = null
        text.lineSequence().forEach { raw ->
            if (raw.isBlank()) return@forEach
            // A collapsed run counts as many times as it occurred.
            val weight = repeatCount(raw)
            val line = if (weight != null) previous ?: return@forEach else raw
            if (weight == null && line.startsWith("#diag:")) return@forEach
            previous = line
            val count = weight?.toInt() ?: 1
            lines += count
            LEVEL_CLASS.find(line)?.let {
                classes.merge(it.groupValues[1], count, Int::plus)
                levels.merge(it.groupValues[2], count, Int::plus)
            }
            for ((name, pattern) in PATTERNS) {
                if (!pattern.containsMatchIn(line)) continue
                if (patternCounts.merge(name, count, Int::plus) == count && weight == null) {
                    patternFirst[name] = TIMESTAMP.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                }
                val list = examples.getOrPut(name) { mutableListOf() }
                if (list.size < MAX_DIGEST_EXAMPLES && line !in list) {
                    list += line.take(MAX_EXAMPLE_CHARS)
                }
            }
        }
        return buildJsonObject {
            put("lines", lines)
            put("levels", JsonObject(levels.mapValues { JsonPrimitive(it.value) }))
            put(
                "top_classes",
                JsonObject(
                    classes.entries.sortedByDescending { it.value }.take(10)
                        .associate { it.key to JsonPrimitive(it.value) }
                )
            )
            put(
                "patterns",
                JsonObject(
                    patternCounts.mapValues { (name, count) ->
                        buildJsonObject {
                            put("count", count)
                            put("first_log_s", patternFirst[name])
                            put(
                                "examples",
                                buildJsonArray {
                                    examples[name].orEmpty().forEach { add(JsonPrimitive(it)) }
                                }
                            )
                        }
                    }
                )
            )
        }
    }

    private fun repeatCount(line: String): Long? =
        if (line.startsWith(REPEAT_PREFIX) && line.endsWith(REPEAT_SUFFIX)) {
            line.substring(REPEAT_PREFIX.length, line.length - REPEAT_SUFFIX.length).toLongOrNull()
        } else {
            null
        }

    private fun readRange(file: File, from: Long, to: Long): String {
        if (to <= from) return ""
        val bytes = ByteArray((to - from).toInt())
        RandomAccessFile(file, "r").use {
            it.seek(from)
            it.readFully(bytes)
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun alignToLineStart(text: String, cutInsideFile: Boolean): String =
        if (cutInsideFile) text.substringAfter('\n', "") else text

    private fun firstTimestamp(text: String): Double? = text.lineSequence()
        .firstNotNullOfOrNull { TIMESTAMP.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    private fun lastTimestamp(text: String): Double? = text.lines().asReversed()
        .firstNotNullOfOrNull { TIMESTAMP.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    private fun unavailableCoverage(reason: String) = buildJsonObject {
        put("available", false)
        put("reason", reason)
    }

    private fun emptyDigest() = buildJsonObject { put("available", false) }
}
