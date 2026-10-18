// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsSchema
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsSession

/** Lists local captures so they can be reviewed and exported after the game was closed. */
class DiagnosticsSessionsDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val sessions = DiagnosticsSession.listSessions()
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_sessions)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.diagnostics_snapshot) { _, _ ->
                DiagnosticsExportDialogFragment.newInstance(null)
                    .show(parentFragmentManager, DiagnosticsExportDialogFragment.TAG)
            }
        if (sessions.isEmpty()) {
            builder.setMessage(R.string.diagnostics_sessions_empty)
        } else {
            val labels = sessions.map { info ->
                val duration = info.durationMs
                    ?.let { " " + DateUtils.formatElapsedTime(it / 1000) }
                    .orEmpty()
                getString(
                    R.string.diagnostics_session_item,
                    info.startedAtUtc?.replace('T', ' ')?.substringBefore('.') ?: info.id,
                    info.gameTitle ?: "-",
                    stateLabel(info.state) + duration,
                    info.markers
                )
            }.toTypedArray()
            builder.setItems(labels) { _, which ->
                DiagnosticsExportDialogFragment.newInstance(sessions[which].id)
                    .show(parentFragmentManager, DiagnosticsExportDialogFragment.TAG)
            }
        }
        return builder.create()
    }

    private fun stateLabel(state: String): String = getString(
        when (state) {
            DiagnosticsSchema.STATE_COMPLETED -> R.string.diagnostics_state_completed
            DiagnosticsSchema.STATE_ACTIVE -> R.string.diagnostics_state_active
            else -> R.string.diagnostics_state_interrupted
        }
    )

    companion object {
        const val TAG = "DiagnosticsSessionsDialogFragment"
    }
}
