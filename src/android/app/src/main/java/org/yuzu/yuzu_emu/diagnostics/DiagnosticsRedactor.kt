// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

/**
 * Removes personal paths and identifiers from text that goes into a diagnostic bundle.
 *
 * [knownDirectories] are replaced first by their placeholder (e.g. the Eden user directory),
 * so relative structure inside them stays readable. Everything else that looks like a storage
 * path, URI, e-mail or network address is replaced entirely.
 */
class DiagnosticsRedactor(knownDirectories: Map<String, String> = emptyMap()) {
    private val directories = knownDirectories
        .filterKeys { it.length > 1 }
        .entries
        .sortedByDescending { it.key.length }

    var replacements = 0
        private set

    /** Redacts paths, URIs and e-mails. Safe for structured values such as driver strings. */
    fun redactPaths(text: String): String {
        var result = text
        for ((directory, placeholder) in directories) {
            if (result.contains(directory)) {
                replacements += countOccurrences(result, directory)
                result = result.replace(directory, placeholder)
            }
        }
        for ((pattern, placeholder) in PATH_PATTERNS) {
            result = pattern.replace(result) {
                replacements++
                placeholder
            }
        }
        return result
    }

    /** Full redaction for free text such as logs and user notes. */
    fun redactText(text: String): String {
        var result = redactPaths(text)
        for ((pattern, placeholder) in NETWORK_PATTERNS) {
            result = pattern.replace(result) {
                replacements++
                placeholder
            }
        }
        return result
    }

    private fun countOccurrences(text: String, value: String): Int {
        var count = 0
        var index = text.indexOf(value)
        while (index >= 0) {
            count++
            index = text.indexOf(value, index + value.length)
        }
        return count
    }

    companion object {
        private val PATH_PATTERNS = listOf(
            Regex("(?i)\\b(?:content|file)://[^\\s\"'<>]+") to "<uri>",
            Regex(
                "(?i)(?:/storage/emulated/\\d+|/storage/[0-9A-F]{4}-[0-9A-F]{4}|/sdcard|/mnt/[^\\s/]+|" +
                    "/data/user(?:_de)?/\\d+|/data/data|/data/media/\\d+|/data/app)(?:/[^\\s\"'<>:,;)]*)?"
            ) to "<path>",
            Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b") to "<email>"
        )

        private val NETWORK_PATTERNS = listOf(
            Regex("(?i)\\b(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\\b") to "<mac>",
            Regex(
                "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?::\\d{1,5})?(?![\\d.])"
            ) to "<ip>"
        )
    }
}
