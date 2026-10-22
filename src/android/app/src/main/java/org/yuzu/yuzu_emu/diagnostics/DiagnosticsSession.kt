// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.Log

enum class CaptureLevel(val id: String, val intervalMs: Long) {
    Standard("standard", 2_000L),
    Detailed("detailed", 1_000L)
}

data class CaptureOptions(
    val level: CaptureLevel = CaptureLevel.Standard,
    val stageNote: String = "",
    val maxDurationMs: Long = 30L * 60L * 1000L,
    val gpuTimestamps: Boolean = true
)

sealed class CaptureState {
    data object Idle : CaptureState()
    data class Capturing(
        val sessionId: String,
        val startedUptimeMs: Long,
        val level: CaptureLevel,
        val markers: Int,
        val openScene: String? = null,
        val scenes: Int = 0
    ) : CaptureState()
}

data class SessionInfo(
    val id: String,
    val directory: File,
    val state: String,
    val startedAtUtc: String?,
    val durationMs: Long?,
    val gameTitle: String?,
    val markers: Int,
    val endReason: String?
)

/**
 * Owns the single diagnostic capture. Everything is written into
 * <user>/diagnostics/sessions/<id>/ while the capture runs, so a capture that ends with a crash
 * can still be exported on the next launch. Nothing leaves the device unless the user exports
 * and shares a bundle.
 */
object DiagnosticsSession {
    private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
    private const val MAX_SAMPLES_BYTES = 16L * 1024L * 1024L
    private const val LOGCAT_TAG = "EdenDiag"

    private val json = Json { prettyPrint = false }
    private val pretty = Json { prettyPrint = true }
    private val lock = Any()
    private val sampleLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var samplingJob: Job? = null
    private var sampler: AndroidMetricsSampler? = null
    private var driverLog: DriverLogCollector? = null

    @Volatile
    private var frameWriter: FrameFileWriter? = null
    private var sceneCount = 0L
    private var openScene: Pair<Long, String>? = null

    @Volatile
    private var sessionDir: File? = null
    private var sessionMeta: JsonObject? = null
    private var startedUptimeMs = 0L
    private var markerCount = 0
    private var sampleCount = 0L
    private var appContext: Context? = null
    private var trimCallbacks: ComponentCallbacks2? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    val rootDirectory: File
        get() = File(DirectoryInitialization.userDirectory!!, "diagnostics")
    val sessionsDirectory: File get() = File(rootDirectory, "sessions")
    val exportsDirectory: File get() = File(rootDirectory, "exports")

    fun isCapturing(): Boolean = _state.value is CaptureState.Capturing

    /** Starts a capture. Returns null on success or a short, user-presentable failure reason. */
    fun start(
        context: Context,
        options: CaptureOptions,
        game: Game?
    ): String? = synchronized(lock) {
        if (isCapturing()) {
            return "already_capturing"
        }
        val sessions = sessionsDirectory
        if (!sessions.isDirectory && !sessions.mkdirs()) {
            return "cannot_create_directory"
        }
        if (sessions.usableSpace < MIN_FREE_BYTES) {
            return "insufficient_space"
        }
        val id = newSessionId()
        val dir = File(sessions, id)
        if (!dir.mkdirs()) {
            return "cannot_create_directory"
        }

        val app = context.applicationContext
        // Flushed first so the offset below is where this capture's log lines begin.
        runCatching { NativeDiagnostics.flushLog() }
        val logOffset = edenLogFile()?.takeIf { it.isFile }?.length()
        val nativeStatic = parseNative(
            runCatching { NativeDiagnostics.staticInfo() }.getOrDefault("{}")
        )
        val nativeObject = nativeStatic as? JsonObject
        val logOriginMono = nativeObject?.let { n ->
            val mono = n.num("mono_ms") ?: return@let null
            val elapsedUs = n.num("log_elapsed_us") ?: return@let null
            (mono - elapsedUs / 1000.0).toLong()
        }
        val process = ProcessIdentity.current(app)
        val meta = buildJsonObject {
            put("session_format_version", DiagnosticsSchema.SESSION_FORMAT_VERSION)
            put("session_id", id)
            // One session holds exactly one capture; the ids are kept apart for the format.
            put("capture_id", id)
            put("state", DiagnosticsSchema.STATE_ACTIVE)
            put("started_at_utc", utcNow())
            put("started_wall_ms", System.currentTimeMillis())
            put("started_uptime_ms", SystemClock.uptimeMillis())
            put("process", process)
            put("game_run_id", nativeObject?.get("game_run_id") ?: JsonPrimitive(null as Long?))
            put("log_start_offset_bytes", logOffset)
            put("log_clock_origin_mono_ms", logOriginMono)
            put("level", options.level.id)
            put("interval_ms", options.level.intervalMs)
            put("max_duration_ms", options.maxDurationMs)
            put("stage_note", options.stageNote)
            put("gpu_timestamps_enabled", options.gpuTimestamps)
            put("game_title", game?.title)
        }
        try {
            File(dir, DiagnosticsSchema.SESSION_FILE).writeText(
                pretty.encodeToString(JsonElement.serializer(), meta)
            )
            val static = buildJsonObject {
                put("collected_at_utc", utcNow())
                put("app", DeviceInfoCollector.app())
                put(
                    "device",
                    runCatching { DeviceInfoCollector.device(app) }.getOrElse { errorJson(it) }
                )
                put(
                    "graphics_driver",
                    runCatching { DeviceInfoCollector.graphicsDriver() }.getOrElse { errorJson(it) }
                )
                put(
                    "game",
                    runCatching { DeviceInfoCollector.game(game) }.getOrElse { errorJson(it) }
                )
                put(
                    "driver_libraries",
                    runCatching { DriverIdentity.collect() }.getOrElse { errorJson(it) }
                )
                put("native", nativeStatic)
                put("metric_definitions", AndroidMetricsSampler.METRIC_DEFINITIONS)
            }
            File(dir, DiagnosticsSchema.STATIC_START_FILE).writeText(
                pretty.encodeToString(JsonElement.serializer(), static)
            )
        } catch (e: IOException) {
            dir.deleteRecursively()
            return "write_failed"
        }

        runCatching { NativeDiagnostics.setGpuTimestampsEnabled(options.gpuTimestamps) }
        if (!NativeDiagnostics.startCapture(dir.absolutePath)) {
            dir.deleteRecursively()
            return "native_start_failed"
        }
        val startedMeta = JsonObject(
            meta + (
                "native_capture_seq" to JsonPrimitive(
                    runCatching { NativeDiagnostics.captureSequence() }.getOrNull()
                )
                ) + ("started_mono_ms" to JsonPrimitive(SystemClock.uptimeMillis()))
        )
        runCatching {
            File(dir, DiagnosticsSchema.SESSION_FILE).writeText(
                pretty.encodeToString(JsonElement.serializer(), startedMeta)
            )
        }

        sessionDir = dir
        sessionMeta = startedMeta
        frameWriter = FrameFileWriter(File(dir, DiagnosticsSchema.FRAMES_FILE), id)
        appContext = app
        startedUptimeMs = SystemClock.uptimeMillis()
        markerCount = 0
        sampleCount = 0
        sceneCount = 0
        openScene = null
        registerSystemCallbacks(app)
        _state.value = CaptureState.Capturing(id, startedUptimeMs, options.level, 0)
        appendEvent(
            buildJsonObject {
                put("type", "capture_started")
                put("level", options.level.id)
            }
        )

        driverLog = DriverLogCollector(
            android.os.Process.myPid(),
            startedMeta["started_wall_ms"]?.let { (it as JsonPrimitive).content.toLong() }
                ?: System.currentTimeMillis()
        ).also { it.start() }
        val sampler = AndroidMetricsSampler(app, options.level == CaptureLevel.Detailed)
        this.sampler = sampler
        samplingJob = scope.launch {
            while (isActive) {
                delay(options.level.intervalMs)
                if (!isActive) {
                    break
                }
                val reason = writeSample(sampler)
                val elapsed = SystemClock.uptimeMillis() - startedUptimeMs
                if (reason == null && elapsed >= options.maxDurationMs) {
                    stop("duration_limit")
                    break
                }
                if (reason != null) {
                    stop(reason)
                    break
                }
            }
        }
        Log.info("[Diagnostics] Capture $id started (${options.level.id})")
        null
    }

    /**
     * Marks the moment the user noticed a problem, [noticedMsAgo] before now (the time spent
     * filling in the marker dialog). Returns the marker id, or null when idle.
     */
    fun mark(category: String, note: String, noticedMsAgo: Long = 0): Long? = synchronized(lock) {
        val current = _state.value as? CaptureState.Capturing ?: return null
        val markerId = SystemClock.uptimeMillis() - noticedMsAgo.coerceAtLeast(0)
        runCatching { NativeDiagnostics.recordMarker(markerId, category) }
        markerCount++
        appendEvent(
            buildJsonObject {
                put("type", "user_marker")
                put("marker_id", markerId)
                put("noticed_t_ms", markerId - startedUptimeMs)
                put("category", category)
                put("note", note)
            }
        )
        _state.value = current.copy(markers = markerCount)
        markerId
    }

    /**
     * Starts a named test scene, or ends the open one. Scene markers carry the native monotonic
     * time and host frame id, so the report can select the whole frames between them. Returns
     * the marker event, or null when idle or when there is no scene to end.
     */
    fun markScene(start: Boolean, name: String = ""): JsonObject? = synchronized(lock) {
        val current = _state.value as? CaptureState.Capturing ?: return null
        if (start && openScene != null) {
            sceneMarker(false, "next_scene_started")
        }
        val event = if (start) {
            sceneCount++
            openScene = sceneCount to name.trim().ifEmpty { "scene $sceneCount" }.take(80)
            sceneMarker(true, "user")
        } else {
            sceneMarker(false, "user") ?: return null
        }
        _state.value = current.copy(
            openScene = openScene?.second,
            scenes = sceneCount.toInt()
        )
        event
    }

    // Called with lock held.
    private fun sceneMarker(start: Boolean, reason: String): JsonObject? {
        val (id, name) = openScene ?: return null
        val kind = if (start) "scene_start" else "scene_end"
        val position = parseNative(
            runCatching { NativeDiagnostics.markScene(kind, name, id) }.getOrDefault("{}")
        ) as? JsonObject ?: JsonObject(emptyMap())
        val event = JsonObject(
            buildJsonObject {
                put("type", "scene_marker")
                put("kind", kind)
                put("scene_id", id)
                put("name", name)
                put("reason", reason)
                put("wall_ms", System.currentTimeMillis())
            } + position
        )
        appendEvent(event)
        // In the Eden log and in this process' logcat, next to the driver's own messages.
        Log.info("[Diagnostics] Scene $id $kind ($name) frame ${position["host_frame_id"]}")
        android.util.Log.i(
            LOGCAT_TAG,
            "$kind id=$id frame=${position["host_frame_id"]} mono_ms=${position["mono_ms"]}"
        )
        if (!start) {
            openScene = null
        }
        return event
    }

    fun stop(reason: String = "user") {
        synchronized(lock) {
            val dir = sessionDir ?: return
            sceneMarker(false, "capture_stopped")
            samplingJob?.cancel()
            samplingJob = null
            appContext?.let { unregisterSystemCallbacks(it) }

            // Drain the partial window since the last sample, then stop the native capture so
            // the summary below is final (capturing=false, fixed duration).
            sampler?.let { writeSample(it) }
            sampler = null
            driverLog?.let { log ->
                runCatching { log.stop(dir) }
                    .onFailure { Log.error("[Diagnostics] Driver log: ${it.message}") }
            }
            driverLog = null
            runCatching { NativeDiagnostics.stopCapture() }
            // Frames still waiting for GPU results are written without them.
            synchronized(sampleLock) {
                runCatching {
                    frameWriter?.append(
                        NativeDiagnostics.takeFrames(true),
                        SystemClock.uptimeMillis()
                    )
                }
            }
            val frames = frameWriter?.status()
            frameWriter = null
            appendEvent(
                buildJsonObject {
                    put("type", "capture_stopped")
                    put("reason", reason)
                }
            )
            val summary = parseNative(
                runCatching { NativeDiagnostics.sessionSummary() }.getOrDefault("{}")
            )
            val nativeStatic = parseNative(
                runCatching { NativeDiagnostics.staticInfo() }.getOrDefault("{}")
            )

            val endUptime = SystemClock.uptimeMillis()
            runCatching {
                File(dir, DiagnosticsSchema.STATIC_END_FILE).writeText(
                    pretty.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("collected_at_utc", utcNow())
                            put("native_summary", summary)
                            put("native", nativeStatic)
                        }
                    )
                )
            }.onFailure { Log.error("[Diagnostics] Failed to write end state: ${it.message}") }

            Log.info("[Diagnostics] Capture ${dir.name} stopped ($reason)")
            runCatching { NativeDiagnostics.flushLog() }
            val meta = sessionMeta
            if (meta != null) {
                val updated = JsonObject(
                    meta + mapOf(
                        "state" to JsonPrimitive(DiagnosticsSchema.STATE_COMPLETED),
                        "ended_at_utc" to JsonPrimitive(utcNow()),
                        "ended_mono_ms" to JsonPrimitive(endUptime),
                        "log_stop_offset_bytes" to JsonPrimitive(edenLogFile()?.length()),
                        "duration_ms" to JsonPrimitive(endUptime - startedUptimeMs),
                        "end_reason" to JsonPrimitive(reason),
                        "samples" to JsonPrimitive(sampleCount),
                        "frames" to (frames ?: JsonNull),
                        "markers" to JsonPrimitive(markerCount),
                        "scenes" to JsonPrimitive(sceneCount)
                    )
                )
                runCatching {
                    File(dir, DiagnosticsSchema.SESSION_FILE).writeText(
                        pretty.encodeToString(JsonElement.serializer(), updated)
                    )
                }
            }
            sessionDir = null
            sessionMeta = null
            appContext = null
            _state.value = CaptureState.Idle
        }
    }

    fun onEmulationStopped() {
        if (isCapturing()) {
            stop("emulation_stopped")
        }
    }

    /** Lists sessions, newest first, marking abandoned active sessions as interrupted. */
    fun listSessions(): List<SessionInfo> {
        val current = synchronized(lock) { sessionDir }
        val dirs = sessionsDirectory.listFiles { file -> file.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, DiagnosticsSchema.SESSION_FILE)
            val meta = runCatching { Json.parseToJsonElement(metaFile.readText()).jsonObject }
                .getOrNull() ?: return@mapNotNull null
            var state = meta["state"]?.jsonPrimitive?.content ?: DiagnosticsSchema.STATE_INTERRUPTED
            var endReason = meta["end_reason"]?.jsonPrimitive?.content
            if (state == DiagnosticsSchema.STATE_ACTIVE && dir != current) {
                state = DiagnosticsSchema.STATE_INTERRUPTED
                endReason = "process_ended_during_capture"
                val updated = JsonObject(
                    meta + mapOf(
                        "state" to JsonPrimitive(state),
                        "end_reason" to JsonPrimitive(endReason),
                        "duration_ms" to JsonPrimitive(lastSampleTime(dir))
                    )
                )
                runCatching {
                    metaFile.writeText(
                        pretty.encodeToString(JsonElement.serializer(), updated)
                    )
                }
            }
            SessionInfo(
                id = dir.name,
                directory = dir,
                state = state,
                startedAtUtc = meta["started_at_utc"]?.jsonPrimitive?.content,
                durationMs = meta["duration_ms"]?.jsonPrimitive
                    ?.let { runCatching { it.long }.getOrNull() }
                    ?: lastSampleTime(dir),
                gameTitle = meta["game_title"]?.jsonPrimitive?.content?.takeIf { it != "null" },
                markers = meta["markers"]?.jsonPrimitive?.let { runCatching { it.int }.getOrNull() }
                    ?: countMarkers(dir),
                endReason = endReason
            )
        }.sortedByDescending { it.id }
    }

    fun deleteSession(info: SessionInfo): Boolean {
        if (synchronized(lock) { info.directory == sessionDir }) {
            return false
        }
        return info.directory.deleteRecursively()
    }

    // Takes sampleLock only, never lock: stop() holds lock while it drains the last window.
    private fun writeSample(sampler: AndroidMetricsSampler): String? = synchronized(sampleLock) {
        val dir = sessionDir ?: return null
        val native = parseNative(runCatching { NativeDiagnostics.takeSample() }.getOrDefault("{}"))
        runCatching {
            frameWriter?.append(NativeDiagnostics.takeFrames(false), SystemClock.uptimeMillis())
        }.onFailure { Log.error("[Diagnostics] Failed to write frames: ${it.message}") }
        val android = runCatching { sampler.sample() }.getOrElse { errorJson(it) }
        val line = buildJsonObject {
            put("capture_id", dir.name)
            put("t_ms", SystemClock.uptimeMillis() - startedUptimeMs)
            put("mono_ms", SystemClock.uptimeMillis())
            put("native", native)
            put("android", android)
        }
        val file = File(dir, DiagnosticsSchema.SAMPLES_FILE)
        return try {
            FileOutputStream(file, true).use { out ->
                out.write(
                    (json.encodeToString(JsonElement.serializer(), line) + "\n").toByteArray()
                )
                out.fd.sync()
            }
            sampleCount++
            when {
                file.length() >= MAX_SAMPLES_BYTES -> "size_limit"
                dir.usableSpace < MIN_FREE_BYTES -> "insufficient_space"
                else -> null
            }
        } catch (e: IOException) {
            Log.error("[Diagnostics] Failed to write sample: ${e.message}")
            "write_failed"
        }
    }

    private fun appendEvent(event: JsonObject) {
        val dir = sessionDir ?: return
        val line = JsonObject(
            mapOf(
                "t_ms" to JsonPrimitive(SystemClock.uptimeMillis() - startedUptimeMs),
                "mono_ms" to JsonPrimitive(SystemClock.uptimeMillis()),
                "utc" to JsonPrimitive(utcNow())
            ) + event
        )
        runCatching {
            FileOutputStream(File(dir, DiagnosticsSchema.EVENTS_FILE), true).use {
                it.write((json.encodeToString(JsonElement.serializer(), line) + "\n").toByteArray())
            }
        }.onFailure { Log.error("[Diagnostics] Failed to write event: ${it.message}") }
    }

    private fun registerSystemCallbacks(context: Context) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                synchronized(lock) {
                    appendEvent(
                        buildJsonObject {
                            put("type", "trim_memory")
                            put("level", level)
                            put("level_name", trimMemoryLevelName(level))
                        }
                    )
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                synchronized(lock) { appendEvent(buildJsonObject { put("type", "low_memory") }) }
            }
        }
        context.registerComponentCallbacks(callbacks)
        trimCallbacks = callbacks

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                synchronized(lock) {
                    appendEvent(
                        buildJsonObject {
                            put("type", "thermal_status_changed")
                            put("status", status)
                        }
                    )
                }
            }
            val executor = Executor { command -> scope.launch { command.run() } }
            runCatching { powerManager.addThermalStatusListener(executor, listener) }
                .onSuccess { thermalListener = listener }
        }
    }

    @Suppress("DEPRECATION")
    private fun trimMemoryLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running_moderate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running_low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running_critical"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "ui_hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> "unknown"
    }

    private fun unregisterSystemCallbacks(context: Context) {
        trimCallbacks?.let { context.unregisterComponentCallbacks(it) }
        trimCallbacks = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                runCatching {
                    context.getSystemService(PowerManager::class.java)
                        .removeThermalStatusListener(listener)
                }
            }
        }
        thermalListener = null
    }

    private fun lastSampleTime(dir: File): Long? = runCatching {
        File(dir, DiagnosticsSchema.SAMPLES_FILE).useLines { lines ->
            lines.lastOrNull { it.isNotBlank() }
        }?.let { Json.parseToJsonElement(it).jsonObject["t_ms"]?.jsonPrimitive?.long }
    }.getOrNull()

    private fun countMarkers(dir: File): Int = runCatching {
        File(dir, DiagnosticsSchema.EVENTS_FILE).useLines { lines ->
            lines.count { it.contains("\"user_marker\"") }
        }
    }.getOrDefault(0)

    fun edenLogFile(): File? = DirectoryInitialization.userDirectory?.let {
        File(File(it, "log"), "eden_log.txt")
    }

    internal fun parseNative(raw: String): JsonElement =
        runCatching { Json.parseToJsonElement(raw) }.getOrElse {
            buildJsonObject { put("unparsed", raw.take(4096)) }
        }

    private fun errorJson(throwable: Throwable): JsonObject = buildJsonObject {
        put("error", throwable.javaClass.simpleName)
        put("message", throwable.message)
    }

    private fun newSessionId(): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val suffix = (SystemClock.uptimeMillis() % 10000).toString().padStart(4, '0')
        return "${format.format(Date())}-$suffix"
    }

    internal fun utcNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
