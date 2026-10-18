// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Identifies the Vulkan driver the process actually mapped, as opposed to the driver package
 * selected in the settings: AdrenoTools and the system loader both end up as a shared object in
 * /proc/self/maps.
 */
object DriverIdentity {
    private const val MAX_HASH_BYTES = 256L * 1024L * 1024L

    private val DRIVER_NAME = Regex(
        "(?i)(vulkan[^/]*\\.so|libvulkan_[^/]*\\.so|freedreno[^/]*\\.so|turnip[^/]*\\.so|" +
            "adreno[^/]*\\.so|libmali[^/]*\\.so|libGLES_mali\\.so|libadrenotools[^/]*\\.so|" +
            "libmain_hook\\.so|libhook_impl\\.so|libfile_redirect_hook\\.so)$"
    )

    /** Unique mapped files that look like Vulkan driver or driver-loading libraries. */
    fun mappedDriverLibraries(maps: String): List<String> = maps.lineSequence()
        .mapNotNull { line ->
            val path = line.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { "/$it" }
            path?.trim()?.takeIf { DRIVER_NAME.containsMatchIn(it.substringAfterLast('/')) }
        }
        .filterNot { it.endsWith("/libvulkan.so") } // the loader, not a driver
        .distinct()
        .toList()

    /** GNU build-id from the PT_NOTE segments of a 64-bit little-endian ELF file. */
    fun elfBuildId(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(64)
            raf.readFully(header)
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                header[4] != 2.toByte() || header[5] != 1.toByte()
            ) {
                return null
            }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val phOffset = h.getLong(0x20)
            val phEntrySize = h.getShort(0x36).toInt() and 0xffff
            val phCount = h.getShort(0x38).toInt() and 0xffff
            for (i in 0 until phCount) {
                val entry = ByteArray(phEntrySize)
                raf.seek(phOffset + i.toLong() * phEntrySize)
                raf.readFully(entry)
                val p = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                if (p.getInt(0) != PT_NOTE) continue
                val offset = p.getLong(0x08)
                val size = p.getLong(0x20).coerceAtMost(64 * 1024)
                val notes = ByteArray(size.toInt())
                raf.seek(offset)
                raf.readFully(notes)
                findBuildIdNote(notes)?.let { return it }
            }
            null
        }
    }.getOrNull()

    internal fun findBuildIdNote(notes: ByteArray): String? {
        val b = ByteBuffer.wrap(notes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 12 <= notes.size) {
            val nameSize = b.getInt(pos)
            val descSize = b.getInt(pos + 4)
            val type = b.getInt(pos + 8)
            val nameStart = pos + 12
            val descStart = nameStart + align4(nameSize)
            val next = descStart + align4(descSize)
            if (nameSize < 0 || descSize < 0 || next > notes.size || next <= pos) return null
            val name = String(notes, nameStart, (nameSize - 1).coerceAtLeast(0), Charsets.US_ASCII)
            if (type == NT_GNU_BUILD_ID && name == "GNU") {
                return (descStart until descStart + descSize).joinToString("") {
                    String.format(Locale.ROOT, "%02x", notes[it])
                }
            }
            pos = next
        }
        return null
    }

    private fun align4(value: Int) = (value + 3) and 3.inv()

    private fun sha256(file: File): String? {
        if (!file.isFile || file.length() > MAX_HASH_BYTES) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }.getOrNull()
    }

    /** Mapped driver libraries with size, SHA-256 and build-id where the file is readable. */
    fun collect(): JsonObject {
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull()
            ?: return buildJsonObject {
                put("available", false)
                put("reason", "/proc/self/maps not readable")
            }
        val libraries = mappedDriverLibraries(maps)
        return buildJsonObject {
            put("available", true)
            put("source", "/proc/self/maps at capture start (libraries mapped by this process)")
            put(
                "libraries",
                buildJsonArray {
                    libraries.forEach { path ->
                        val file = File(path)
                        add(
                            buildJsonObject {
                                put("path", path)
                                put("readable", file.canRead())
                                put("bytes", file.takeIf { it.isFile }?.length())
                                put("sha256", sha256(file))
                                put("elf_build_id", elfBuildId(file))
                            }
                        )
                    }
                }
            )
            put(
                "note",
                "sha256 or build-id are null when the file is not readable by the app; the " +
                    "Vulkan-reported identity is in vulkan.device.driver"
            )
        }
    }

    private const val PT_NOTE = 4
    private const val NT_GNU_BUILD_ID = 3
}
