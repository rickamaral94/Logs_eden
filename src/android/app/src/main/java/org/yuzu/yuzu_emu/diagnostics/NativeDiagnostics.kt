// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

/** JNI bridge to common/session_diagnostics. All JSON results are produced natively. */
object NativeDiagnostics {
    external fun startCapture(sessionDir: String): Boolean
    external fun stopCapture()
    external fun isCapturing(): Boolean

    /** Sequence of the current or last native capture in this process; events carry it. */
    external fun captureSequence(): Long

    /** Metrics accumulated since the previous call; resets the native window. */
    external fun takeSample(): String

    /** Totals and session-wide frame time percentiles for the current capture. */
    external fun sessionSummary(): String

    /** Build identity, settings, running game ids, Vulkan sections and the event ring. */
    external fun staticInfo(): String

    external fun recordMarker(markerId: Long, category: String)

    /** Writes buffered Eden log lines to eden_log.txt so an export includes the latest ones. */
    external fun flushLog()
}
