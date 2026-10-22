// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

/** JNI bridge to common/session_diagnostics. All JSON results are produced natively. */
object NativeDiagnostics {
    external fun startCapture(sessionDir: String): Boolean
    external fun stopCapture()
    external fun isCapturing(): Boolean

    /** "tid starttime ticks cpu\tname" lines for every thread; see ProcParsers. */
    external fun readThreadStats(): String

    /** Vulkan timestamp queries around scheduler submissions while capturing. */
    external fun setGpuTimestampsEnabled(enabled: Boolean)

    /** Sequence of the current or last native capture in this process; events carry it. */
    external fun captureSequence(): Long

    /** Metrics accumulated since the previous call; resets the native window. */
    external fun takeSample(): String

    /**
     * Per host frame records ready for export ({"columns","rows"}); [all] also returns records
     * still waiting for GPU time (used once the capture stopped).
     */
    external fun takeFrames(all: Boolean): String

    /** Totals and session-wide frame time percentiles for the current capture. */
    external fun sessionSummary(): String

    /** Build identity, settings, running game ids, Vulkan sections and the event ring. */
    external fun staticInfo(): String

    external fun recordMarker(markerId: Long, category: String)

    /**
     * Records a scene marker natively ([kind] scene_start or scene_end) and returns
     * {"mono_ms","mono_us","host_frame_id","capture_seq","capturing"}.
     */
    external fun markScene(kind: String, name: String, sceneId: Long): String

    /** Writes buffered Eden log lines to eden_log.txt so an export includes the latest ones. */
    external fun flushLog()
}
