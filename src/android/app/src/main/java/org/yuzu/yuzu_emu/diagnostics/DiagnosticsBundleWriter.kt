// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class BundleResult {
    data class Success(val file: File) : BundleResult()
    data class Failure(val reason: String, val message: String?) : BundleResult()
}

object DiagnosticsBundleWriter {
    /** Extra space required beyond the uncompressed bundle size. */
    private const val SPACE_MARGIN_BYTES = 8L * 1024L * 1024L

    /**
     * Writes [plan] to [destination] through a temporary file, so a failed export never leaves
     * a truncated bundle behind.
     */
    fun write(plan: BundlePlan, destination: File): BundleResult {
        val parent = destination.parentFile ?: return BundleResult.Failure("invalid_destination", null)
        if (!parent.isDirectory && !parent.mkdirs()) {
            return BundleResult.Failure("cannot_create_directory", parent.name)
        }
        val required = plan.entries.sumOf { it.content.size.toLong() } + SPACE_MARGIN_BYTES
        if (parent.usableSpace < required) {
            return BundleResult.Failure("insufficient_space", "${required / 1024 / 1024} MiB required")
        }
        val temporary = File(parent, destination.name + ".partial")
        try {
            temporary.delete()
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                for (entry in plan.entries) {
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(entry.content)
                    zip.closeEntry()
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("cannot replace existing bundle")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("cannot finalize bundle")
            }
            return BundleResult.Success(destination)
        } catch (e: IOException) {
            temporary.delete()
            return BundleResult.Failure("write_failed", e.message)
        } catch (e: SecurityException) {
            temporary.delete()
            return BundleResult.Failure("permission_denied", e.message)
        }
    }
}
