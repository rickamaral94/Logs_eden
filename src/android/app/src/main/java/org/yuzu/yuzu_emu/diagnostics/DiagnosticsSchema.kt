// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

/**
 * Report format constants. Field names are part of the format: do not rename them. Additive
 * changes keep the version; renames, removals or changed meanings bump [SCHEMA_VERSION] and
 * must be documented in docs/Diagnostics.md.
 */
object DiagnosticsSchema {
    const val SCHEMA_VERSION = 2
    const val SESSION_FORMAT_VERSION = 2

    /** Report schema versions this build can read (offline tools accept the same). */
    val READABLE_SCHEMA_VERSIONS = listOf(1, 2)

    const val SESSION_FILE = "session.json"
    const val SAMPLES_FILE = "samples.jsonl"
    const val EVENTS_FILE = "events.jsonl"
    const val STATIC_START_FILE = "static_start.json"
    const val STATIC_END_FILE = "static_end.json"
    const val CRITICAL_PREFIX = "native_critical_"

    const val REPORT_FILE = "report.json"
    const val SUMMARY_FILE = "summary.txt"
    const val README_FILE = "README.txt"
    const val MANIFEST_FILE = "manifest.json"
    const val DRIVER_LOG_FILE = "driver_logcat.txt"
    const val DRIVER_LOG_DIGEST_FILE = "driver_logcat_digest.json"

    const val STATE_ACTIVE = "active"
    const val STATE_COMPLETED = "completed"
    const val STATE_INTERRUPTED = "interrupted"

    const val AVAILABLE = "available"
    const val UNAVAILABLE = "unavailable"

    /** Issue categories offered when the user marks a problem. Values are stable identifiers. */
    val ISSUE_CATEGORIES = listOf(
        "missing_textures",
        "wrong_colors",
        "artifacts",
        "flicker",
        "distorted_geometry",
        "black_screen",
        "shader_stutter",
        "fps_drop",
        "stutter",
        "freeze",
        "audio",
        "other"
    )
}
