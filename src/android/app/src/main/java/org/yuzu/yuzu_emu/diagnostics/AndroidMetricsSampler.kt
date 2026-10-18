// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.yuzu.yuzu_emu.NativeLibrary

/**
 * Samples process and system metrics exposed to an unprivileged app. Every metric is reported
 * with its unit and source; values the device does not expose are null with a reason in
 * "availability". Process metrics (scope "process") are kept apart from system-wide ones
 * (scope "system").
 */
class AndroidMetricsSampler(
    private val context: Context,
    private val detailed: Boolean,
    private val maxThreads: Int = 12
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).coerceAtLeast(1L)
    private val pid = Process.myPid()

    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()
    private var previousThreadTicks = readThreads().associate { it.tid to it.ticks }
    private var previousGc = readGcStats()
    private var sampleCount = 0L

    fun sample(): JsonObject {
        val start = SystemClock.elapsedRealtimeNanos()
        val availability = mutableMapOf<String, String>()

        val nowCpuMs = Process.getElapsedCpuTime()
        val nowWallMs = SystemClock.elapsedRealtime()
        val wallDelta = nowWallMs - previousWallMs
        val processCpuPct = if (wallDelta > 0) {
            (nowCpuMs - previousCpuMs).toDouble() * 100.0 / wallDelta.toDouble()
        } else {
            null
        }
        previousCpuMs = nowCpuMs
        previousWallMs = nowWallMs

        val threads = sampleThreads(wallDelta, availability)
        val memory = sampleProcessMemory(availability)
        val gc = sampleGc(availability)
        val system = sampleSystem(availability)
        val perf = samplePerf(availability)

        sampleCount++
        val costMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        return buildJsonObject {
            put(
                "process",
                buildJsonObject {
                    put("cpu_pct_of_one_core", processCpuPct)
                    put("cpu_cores", Runtime.getRuntime().availableProcessors())
                    put("threads", threads.first)
                    put("thread_categories", threads.second)
                    put("memory", memory)
                    put("gc", gc)
                }
            )
            put("system", system)
            put("emulator", perf)
            put("availability", JsonObject(availability.mapValues { JsonPrimitive(it.value) }))
            put("sampler_cost_ms", costMs)
        }
    }

    private fun sampleThreads(
        wallDeltaMs: Long,
        availability: MutableMap<String, String>
    ): Pair<JsonArray, JsonObject> {
        val current = readThreads()
        if (current.isEmpty() || wallDeltaMs <= 0) {
            availability["threads"] = "${DiagnosticsSchema.UNAVAILABLE}: /proc/self/task unreadable"
            return JsonArray(emptyList()) to JsonObject(emptyMap())
        }
        val deltas = current.mapNotNull { stat ->
            val previous = previousThreadTicks[stat.tid] ?: return@mapNotNull null
            val delta = stat.ticks - previous
            if (delta < 0) null else stat to delta
        }
        previousThreadTicks = current.associate { it.tid to it.ticks }

        val tickMs = 1000.0 / clockTicksPerSecond
        fun pct(ticks: Long) = ticks * tickMs * 100.0 / wallDeltaMs

        val categories = deltas
            .groupBy { ProcParsers.threadCategory(it.first.name, it.first.tid == pid) }
            .mapValues { (_, list) -> JsonPrimitive(pct(list.sumOf { it.second })) }
        val top = deltas
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxThreads)
        availability["threads"] = DiagnosticsSchema.AVAILABLE
        return buildJsonArray {
            for ((stat, ticks) in top) {
                add(
                    buildJsonObject {
                        put("name", stat.name)
                        put("category", ProcParsers.threadCategory(stat.name, stat.tid == pid))
                        put("cpu_pct_of_one_core", pct(ticks))
                        put("last_cpu", stat.lastCpu)
                    }
                )
            }
        } to JsonObject(categories)
    }

    private fun sampleProcessMemory(availability: MutableMap<String, String>): JsonObject {
        val status = runCatching { ProcParsers.parseStatusKb(File("/proc/self/status").readText()) }
            .getOrNull()
        if (status == null) {
            availability["memory.rss"] = "${DiagnosticsSchema.UNAVAILABLE}: /proc/self/status"
        }
        val runtime = Runtime.getRuntime()
        // Debug.getPss walks smaps and is comparatively expensive for a large process, so it is
        // only sampled at a lower rate and only at the detailed level.
        val pssKb = if (detailed && sampleCount % PSS_SAMPLE_EVERY == 0L) {
            runCatching { Debug.getPss() }.getOrNull()
        } else {
            null
        }
        if (!detailed) {
            availability["memory.pss"] = "${DiagnosticsSchema.UNAVAILABLE}: detailed level only"
        }
        return buildJsonObject {
            put("rss_kb", status?.get("VmRSS"))
            put("rss_peak_kb", status?.get("VmHWM"))
            put("swap_kb", status?.get("VmSwap"))
            put("pss_kb", pssKb)
            put("java_heap_used_kb", (runtime.totalMemory() - runtime.freeMemory()) / 1024)
            put("java_heap_max_kb", runtime.maxMemory() / 1024)
            put("native_heap_allocated_kb", Debug.getNativeHeapAllocatedSize() / 1024)
        }
    }

    private fun readGcStats(): Map<String, Long> = GC_STATS.mapNotNull { key ->
        Debug.getRuntimeStat(key)?.toLongOrNull()?.let { key to it }
    }.toMap()

    private fun sampleGc(availability: MutableMap<String, String>): JsonObject {
        val current = readGcStats()
        if (current.isEmpty()) {
            availability["gc"] = "${DiagnosticsSchema.UNAVAILABLE}: Debug.getRuntimeStat"
        }
        val deltas = current.mapValues { (key, value) -> value - (previousGc[key] ?: value) }
        previousGc = current
        return buildJsonObject {
            put("gc_count", deltas["art.gc.gc-count"])
            put("gc_time_ms", deltas["art.gc.gc-time"])
            put("blocking_gc_count", deltas["art.gc.blocking-gc-count"])
            put("blocking_gc_time_ms", deltas["art.gc.blocking-gc-time"])
        }
    }

    private fun sampleSystem(availability: MutableMap<String, String>): JsonObject {
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            availability["thermal_status"] = "${DiagnosticsSchema.UNAVAILABLE}: API < 29"
            null
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(0).takeUnless { it.isNaN() }
        } else {
            null
        }
        if (headroom == null) {
            availability["thermal_headroom"] = "${DiagnosticsSchema.UNAVAILABLE}: not reported"
        }
        val cpuFreq = readCpuFrequenciesMhz()
        if (cpuFreq.all { it == null }) {
            availability["cpu_freq"] = "${DiagnosticsSchema.UNAVAILABLE}: cpufreq not readable"
        }
        val gpuBusy = runCatching { ProcParsers.parseKgslBusyPercent(File(KGSL_BUSY).readText()) }
            .getOrNull()
        val gpuClock = readLong(KGSL_CLOCK)?.div(1_000_000L)
        if (gpuBusy == null) {
            availability["gpu_busy"] = "${DiagnosticsSchema.UNAVAILABLE}: kgsl gpubusy not readable"
        }
        return buildJsonObject {
            put("mem_available_kb", memoryInfo.availMem / 1024)
            put("mem_total_kb", memoryInfo.totalMem / 1024)
            put("mem_low_threshold_kb", memoryInfo.threshold / 1024)
            put("low_memory", memoryInfo.lowMemory)
            put("thermal_status", thermalStatus)
            put("thermal_headroom", headroom)
            put("battery_temp_c", readBatteryTemperature())
            put("cpu_freq_mhz", JsonArray(cpuFreq.map { it?.let(::JsonPrimitive) ?: JsonNull }))
            put("gpu_busy_pct_kgsl", gpuBusy)
            put("gpu_clock_mhz_kgsl", gpuClock)
        }
    }

    // NativeLibrary.getPerfStats() resets the core statistics used by the performance overlay,
    // so frame rates are derived from the native frame interval data instead.
    private fun samplePerf(availability: MutableMap<String, String>): JsonObject {
        val shaders = runCatching { NativeLibrary.getShadersBuilding() }.getOrNull()
        if (shaders == null) {
            availability["emulator.shaders_building"] = DiagnosticsSchema.UNAVAILABLE
        }
        return buildJsonObject {
            put("shaders_building", shaders)
        }
    }

    private fun readThreads(): List<ThreadCpuStat> =
        File("/proc/self/task").listFiles()?.mapNotNull { dir ->
            runCatching {
                ProcParsers.parseThreadStat(
                    dir.resolve("stat").readText(),
                    dir.resolve("comm").readText()
                )
            }.getOrNull()
        }.orEmpty()

    private fun readCpuFrequenciesMhz(): List<Long?> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).map { readLong("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.div(1000L) }
    }

    private fun readBatteryTemperature(): Double? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    companion object {
        private const val PSS_SAMPLE_EVERY = 10L
        private const val KGSL_BUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy"
        private const val KGSL_CLOCK = "/sys/class/kgsl/kgsl-3d0/gpuclk"
        private val GC_STATS = listOf(
            "art.gc.gc-count",
            "art.gc.gc-time",
            "art.gc.blocking-gc-count",
            "art.gc.blocking-gc-time"
        )

        /** Describes every metric produced by [sample]: unit, scope and source. */
        val METRIC_DEFINITIONS: JsonObject = buildJsonObject {
            fun def(name: String, unit: String, scope: String, source: String, note: String? = null) {
                put(
                    name,
                    buildJsonObject {
                        put("unit", unit)
                        put("scope", scope)
                        put("source", source)
                        if (note != null) put("note", note)
                    }
                )
            }
            def("process.cpu_pct_of_one_core", "%", "process", "Process.getElapsedCpuTime", "100 = one core fully busy")
            def("process.threads[].cpu_pct_of_one_core", "%", "process", "/proc/self/task/*/stat utime+stime")
            def("process.thread_categories", "%", "process", "/proc/self/task/*/stat grouped by thread name")
            def("process.memory.rss_kb", "KiB", "process", "/proc/self/status VmRSS")
            def("process.memory.rss_peak_kb", "KiB", "process", "/proc/self/status VmHWM", "peak since process start")
            def("process.memory.pss_kb", "KiB", "process", "Debug.getPss", "detailed level, every 10 samples")
            def("process.memory.java_heap_used_kb", "KiB", "process", "Runtime")
            def("process.memory.native_heap_allocated_kb", "KiB", "process", "Debug.getNativeHeapAllocatedSize", "malloc heap only, excludes driver mappings")
            def("process.gc.*", "count/ms per window", "process", "Debug.getRuntimeStat art.gc.*")
            def("system.mem_available_kb", "KiB", "system", "ActivityManager.getMemoryInfo")
            def("system.thermal_status", "enum", "system", "PowerManager.getCurrentThermalStatus", "0 none .. 6 shutdown")
            def("system.thermal_headroom", "ratio", "system", "PowerManager.getThermalHeadroom(0)", "1.0 = severe throttling threshold")
            def("system.battery_temp_c", "°C", "system", "ACTION_BATTERY_CHANGED")
            def("system.cpu_freq_mhz", "MHz", "system", "cpufreq scaling_cur_freq")
            def("system.gpu_busy_pct_kgsl", "%", "system", "/sys/class/kgsl/kgsl-3d0/gpubusy", "Adreno kernel driver, all processes")
            def("emulator.shaders_building", "count", "process", "NativeLibrary.getShadersBuilding", "shaders queued or compiling at sample time")
            def("native.counters.game_frames", "frames per window", "process", "PerfStats::EndGameFrame", "game FPS = game_frames / window_ms * 1000")
            def("native.timers.*", "µs CPU wall clock", "process", "common/session_diagnostics", "CPU time inside or blocked on the operation, not GPU execution time")
            def("native.frames.guest_present_interval", "ms", "process", "PerfStats::EndSystemFrame", "interval between guest display flips")
            def("native.frames.host_present_interval", "ms", "process", "RendererVulkan::Composite", "interval between host composites, not display scanout")
            def("native.samplers.vulkan_memory_budget", "bytes", "process", "VK_EXT_memory_budget", "driver reported heap usage and budget")
            def("sampler_cost_ms", "ms", "process", "SystemClock", "time spent collecting the Android metrics of this sample")
        }
    }
}
