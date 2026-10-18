// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverIdentityTest {
    @Test
    fun mapsListDriverLibrariesOnce() {
        val maps = """
            7a00000000-7a00100000 r-xp 00000000 fd:01 123  /vendor/lib64/hw/vulkan.adreno.so
            7a00100000-7a00200000 r--p 00100000 fd:01 123  /vendor/lib64/hw/vulkan.adreno.so
            7b00000000-7b00100000 r-xp 00000000 fd:05 456  /data/user/0/dev.eden/files/gpu_drivers/libvulkan_freedreno.so
            7c00000000-7c00100000 r-xp 00000000 fd:01 789  /system/lib64/libvulkan.so
            7d00000000-7d00100000 r-xp 00000000 fd:01 790  /system/lib64/libc.so
            7e00000000-7e00100000 rw-p 00000000 00:00 0    [anon:scudo]
        """.trimIndent()
        assertEquals(
            listOf(
                "/vendor/lib64/hw/vulkan.adreno.so",
                "/data/user/0/dev.eden/files/gpu_drivers/libvulkan_freedreno.so"
            ),
            DriverIdentity.mappedDriverLibraries(maps)
        )
    }

    private fun note(name: String, type: Int, desc: ByteArray): ByteArray {
        val nameBytes = (name + "\u0000").toByteArray()
        fun pad(n: Int) = (n + 3) and 3.inv()
        val buffer = ByteBuffer.allocate(12 + pad(nameBytes.size) + pad(desc.size))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(nameBytes.size).putInt(desc.size).putInt(type)
        buffer.put(nameBytes)
        buffer.position(12 + pad(nameBytes.size))
        buffer.put(desc)
        return buffer.array()
    }

    @Test
    fun buildIdIsReadFromTheGnuNote() {
        val id = byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())
        val notes = note("Android", 1, ByteArray(4)) + note("GNU", 3, id)
        assertEquals("1234abcd", DriverIdentity.findBuildIdNote(notes))
        assertNull(DriverIdentity.findBuildIdNote(note("GNU", 1, id)))
    }

    @Test
    fun buildIdFromAMinimalElf() {
        val notes = note("GNU", 3, byteArrayOf(1, 2, 3, 4, 5))
        val phOffset = 64
        val notesOffset = phOffset + 56
        val elf = ByteBuffer.allocate(notesOffset + notes.size).order(ByteOrder.LITTLE_ENDIAN)
        elf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
        elf.putLong(0x20, phOffset.toLong())
        elf.putShort(0x36, 56)
        elf.putShort(0x38, 1)
        elf.putInt(phOffset, 4) // PT_NOTE
        elf.putLong(phOffset + 0x08, notesOffset.toLong())
        elf.putLong(phOffset + 0x20, notes.size.toLong())
        elf.position(notesOffset)
        elf.put(notes)
        val file = Files.createTempFile("elf", ".so").toFile()
        try {
            file.writeBytes(elf.array())
            assertEquals("0102030405", DriverIdentity.elfBuildId(file))
            assertNull(DriverIdentity.elfBuildId(File(file.parent, "missing.so")))
        } finally {
            file.delete()
        }
    }
}
