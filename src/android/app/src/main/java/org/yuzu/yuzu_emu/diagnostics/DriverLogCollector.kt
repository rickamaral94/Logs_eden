// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One logcat line in "-v epoch" format. */
data class LogcatLine(
    val epochMs: Long,
    val tid: Int,
    val priority: Char,
    val tag: String,
    val message: String
)

/**
 * Bounded aggregation of driver and system messages of this process: counts per normalized
 * message (numbers and addresses replaced), first/last time, and the first raw lines up to a
 * size limit. Pure, so it can be tested on the JVM.
 */
class DriverLogAggregator(
    private val captureStartWallMs: Long,
    private val maxRawBytes: Int = 1024 * 1024,
    private val maxKeys: Int = 500
) {
    private class Entry(
        val tag: String,
        val priority: Char,
        val example: String,
        val firstMs: Long
    ) {
        var count = 0L
        var lastMs = firstMs
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val raw = StringBuilder()
    var lines = 0L
        private set
    var rawDropped = 0L
        private set
    var keysDropped = 0L
        private set

    fun add(line: LogcatLine) {
        lines++
        val key = "${line.tag}|${normalize(line.message)}"
        val entry = entries[key] ?: if (entries.size < maxKeys) {
            Entry(line.tag, line.priority, line.message.take(MAX_EXAMPLE), line.epochMs)
                .also { entries[key] = it }
        } else {
            keysDropped++
            null
        }
        entry?.let {
            it.count++
            it.lastMs = line.epochMs
        }
        val text = String.format(
            Locale.ROOT,
            "%9.3f %5d %c %s: %s\n",
            (line.epochMs - captureStartWallMs) / 1000.0,
            line.tid,
            line.priority,
            line.tag,
            line.message
        )
        if (raw.length + text.length <= maxRawBytes) raw.append(text) else rawDropped++
    }

    fun rawText(): String = raw.toString()

    fun digest(): JsonObject = buildJsonObject {
        put("lines", lines)
        put("raw_lines_dropped", rawDropped)
        put("distinct_messages_dropped", keysDropped)
        put(
            "messages",
            buildJsonArray {
                entries.values.sortedByDescending { it.count }.take(MAX_DIGEST).forEach { e ->
                    add(
                        buildJsonObject {
                            put("tag", e.tag)
                            put("priority", e.priority.toString())
                            put("count", e.count)
                            put("first_t_s", (e.firstMs - captureStartWallMs) / 1000.0)
                            put("last_t_s", (e.lastMs - captureStartWallMs) / 1000.0)
                            put("example", e.example)
                        }
                    )
                }
            }
        )
        put(
            "by_tag",
            JsonObject(
                entries.values.groupBy { it.tag }
                    .mapValues { (_, list) -> JsonPrimitive(list.sumOf { it.count }) }
            )
        )
    }

    companion object {
        private const val MAX_EXAMPLE = 300
        private const val MAX_DIGEST = 50
        private val LINE = Regex(
            "^\\s*(\\d+)\\.(\\d{3})\\s+\\d+\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*: (.*)$"
        )
        private val NUMBER = Regex("0x[0-9a-fA-F]+|\\b\\d+(?:\\.\\d+)?\\b")

        fun parse(line: String): LogcatLine? {
            val m = LINE.find(line) ?: return null
            val (seconds, millis, tid, priority, tag, message) = m.destructured
            return LogcatLine(
                epochMs = seconds.toLong() * 1000 + millis.toLong(),
                tid = tid.toIntOrNull() ?: 0,
                priority = priority[0],
                tag = tag.trim(),
                message = message
            )
        }

        fun normalize(message: String): String = NUMBER.replace(message, "#")
    }
}

/**
 * Streams logcat of this process during a capture, excluding Eden's own log tag, so messages
 * from the Vulkan driver (for example Turnip with TU_DEBUG=perf), the loader and the system
 * are kept next to the capture. Apps can only read their own process' logs.
 */
class DriverLogCollector(
    private val pid: Int,
    private val captureStartWallMs: Long
) {
    private var process: Process? = null
    private var reader: Thread? = null
    private val aggregator = DriverLogAggregator(captureStartWallMs)

    @Volatile
    private var error: String? = null

    fun start() {
        val since = String.format(Locale.ROOT, "%.3f", captureStartWallMs / 1000.0)
        val command = listOf(
            "logcat",
            "-v",
            "epoch",
            "--pid=$pid",
            "-T",
            since,
            EDEN_TAG + ":S",
            "*:V"
        )
        process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
            .onFailure { error = it.javaClass.simpleName + ": " + it.message }
            .getOrNull()
        val stream = process?.inputStream ?: return
        reader = Thread({
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { text ->
                        DriverLogAggregator.parse(text)?.let {
                            synchronized(aggregator) {
                                aggregator.add(
                                    it
                                )
                            }
                        }
                    }
                }
            }
        }, "DiagLogcat").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the stream and writes the raw lines and the digest into the session directory. */
    fun stop(dir: File) {
        process?.destroy()
        reader?.join(500)
        val digest = synchronized(aggregator) {
            File(dir, DiagnosticsSchema.DRIVER_LOG_FILE).writeText(aggregator.rawText())
            aggregator.digest()
        }
        File(dir, DiagnosticsSchema.DRIVER_LOG_DIGEST_FILE).writeText(
            JsonObject(
                digest + mapOf(
                    "available" to JsonPrimitive(error == null),
                    "reason" to JsonPrimitive(error),
                    "source" to JsonPrimitive(
                        "logcat -v epoch --pid of this process, all tags except $EDEN_TAG"
                    )
                )
            ).toString()
        )
    }

    companion object {
        /** Tag of Eden's own LogcatBackend; those lines are already in eden_log.txt. */
        const val EDEN_TAG = "YuzuNative"
    }
}
