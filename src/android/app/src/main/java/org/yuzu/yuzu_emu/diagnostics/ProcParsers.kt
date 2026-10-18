// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

/** CPU time of one thread from /proc/self/task/<tid>/stat, in clock ticks. */
data class ThreadCpuStat(
    val tid: Int,
    val name: String,
    val ticks: Long,
    val lastCpu: Int?
)

object ProcParsers {
    /**
     * Parses a /proc/<pid>/task/<tid>/stat line. The thread name is taken from the separate
     * comm file because the name inside the parentheses may itself contain spaces or ')'.
     */
    fun parseThreadStat(raw: String, name: String): ThreadCpuStat? {
        val nameEnd = raw.lastIndexOf(')')
        if (nameEnd <= 0 || nameEnd + 2 >= raw.length) {
            return null
        }
        val tid = raw.substringBefore(' ').trim().toIntOrNull() ?: return null
        // Fields after the name start at field 3 (state); utime and stime are fields 14 and 15,
        // processor (last CPU) is field 39.
        val fields = raw.substring(nameEnd + 2).trim().split(WHITESPACE)
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        val lastCpu = fields.getOrNull(36)?.toIntOrNull()
        return ThreadCpuStat(tid, name.trim().ifEmpty { "unnamed" }, utime + stime, lastCpu)
    }

    /** Parses the kB values of /proc/self/status, e.g. VmRSS and VmHWM. */
    fun parseStatusKb(raw: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (line in raw.lineSequence()) {
            val separator = line.indexOf(':')
            if (separator <= 0 || !line.endsWith("kB")) {
                continue
            }
            val value = line.substring(separator + 1).removeSuffix("kB").trim().toLongOrNull()
            if (value != null) {
                result[line.substring(0, separator)] = value
            }
        }
        return result
    }

    /** Parses "busy total" from /sys/class/kgsl/kgsl-3d0/gpubusy. */
    fun parseKgslBusyPercent(raw: String): Double? {
        val values = raw.trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (values.size < 2 || values[0] < 0 || values[1] <= 0) {
            return null
        }
        return (values[0].toDouble() * 100.0 / values[1].toDouble()).coerceIn(0.0, 100.0)
    }

    /**
     * Groups Eden thread names (limited to 15 characters by the kernel) into stable categories.
     * [isMainThread] is true for the thread whose tid equals the process id.
     */
    fun threadCategory(name: String, isMainThread: Boolean): String {
        if (isMainThread) {
            return "ui"
        }
        return when {
            name.startsWith("CPUCore_") || name == "CPUThread" -> "emu_cpu"
            name == "GPU" -> "emu_gpu"
            name.startsWith("VulkanWorker") || name.startsWith("VulkanPresent") -> "vulkan"
            name.startsWith("VkPipeline") || name.startsWith("ShaderWorker") -> "shader_pipeline"
            name.startsWith("ImageTranscode") || name.startsWith("TextureDecoder") -> "texture"
            name.startsWith("Audio") || name.startsWith("DSP_") || name.startsWith("cubeb") ||
                name.startsWith("AAudio") || name.startsWith("Oboe") -> "audio"
            name == "RenderThread" || name.startsWith("hwui") -> "ui"
            name.startsWith("HostTiming") || name.startsWith("VSyncThread") ||
                name.startsWith("TimeWorker") -> "timing"
            name.startsWith("HeapTaskDaemon") || name.startsWith("FinalizerDaemon") ||
                name.startsWith("ReferenceQueueD") || name.startsWith("Jit thread pool") ||
                name.startsWith("Signal Catcher") -> "art_runtime"
            else -> "other"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
