// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcParsersTest {
    // Layout of /proc/<pid>/task/<tid>/stat (fields 1..52), with a name containing spaces and ')'.
    private val stat = "4242 (Weird ) name) S 1000 1000 0 0 -1 4194368 100 0 0 0 " +
        "250 75 0 0 20 0 30 0 12345 100000 2000 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 " +
        "17 6 0 0 0 0 0 0 0 0 0 0 0 0 0"

    @Test
    fun parsesThreadTicksAndLastCpu() {
        val parsed = ProcParsers.parseThreadStat(stat, "CPUCore_0\n")!!
        assertEquals(4242, parsed.tid)
        assertEquals("CPUCore_0", parsed.name)
        assertEquals(325L, parsed.ticks)
        assertEquals(6, parsed.lastCpu)
    }

    @Test
    fun rejectsMalformedStat() {
        assertNull(ProcParsers.parseThreadStat("garbage", "x"))
        assertNull(ProcParsers.parseThreadStat("12 (a) S 1", "x"))
    }

    @Test
    fun parsesStatusKilobytes() {
        val status = "Name:\tdev.eden\nVmHWM:\t 2048000 kB\nVmRSS:\t 1900000 kB\nThreads:\t80\n"
        val parsed = ProcParsers.parseStatusKb(status)
        assertEquals(2048000L, parsed["VmHWM"])
        assertEquals(1900000L, parsed["VmRSS"])
        assertNull(parsed["Threads"])
    }

    @Test
    fun parsesKgslBusy() {
        assertEquals(25.0, ProcParsers.parseKgslBusyPercent("250 1000\n")!!, 0.001)
        assertNull(ProcParsers.parseKgslBusyPercent("0 0"))
        assertNull(ProcParsers.parseKgslBusyPercent(""))
    }

    @Test
    fun categorizesEdenThreads() {
        assertEquals("emu_cpu", ProcParsers.threadCategory("CPUCore_3", false))
        assertEquals("emu_gpu", ProcParsers.threadCategory("GPU", false))
        assertEquals("vulkan", ProcParsers.threadCategory("VulkanPresent", false))
        assertEquals("shader_pipeline", ProcParsers.threadCategory("VkPipelineBuild", false))
        assertEquals("audio", ProcParsers.threadCategory("DSP_AudioRende", false))
        assertEquals("ui", ProcParsers.threadCategory("anything", true))
        assertEquals("other", ProcParsers.threadCategory("Binder:1234_2", false))
        assertEquals("emu_gpu", ProcParsers.threadCategory("GPUFencingThrea", false))
        assertEquals("emu_main", ProcParsers.threadCategory("NativeEmulation", false))
        assertEquals("emu_hle_services", ProcParsers.threadCategory("nvservices", false))
        assertEquals("emu_hle_services", ProcParsers.threadCategory("FS:2", false))
        assertEquals("ui", ProcParsers.threadCategory("VsyncReceiver", false))
    }
}
