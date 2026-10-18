// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogDiagnosticsBinding
import org.yuzu.yuzu_emu.databinding.DialogDiagnosticsMarkerBinding
import org.yuzu.yuzu_emu.diagnostics.CaptureLevel
import org.yuzu.yuzu_emu.diagnostics.CaptureOptions
import org.yuzu.yuzu_emu.diagnostics.CaptureState
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsSchema
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsSession
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.SerializableHelper.parcelable

/** In-game controls for the diagnostic capture: start, stop and mark an issue. */
class DiagnosticsDialogFragment : DialogFragment() {
    private var _binding: DialogDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private val game: Game? get() = arguments?.parcelable(GAME)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDiagnosticsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics)
            .setView(binding.root)
            .setPositiveButton(R.string.diagnostics_start, null)
            .setNeutralButton(R.string.diagnostics_mark_issue, null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { toggleCapture() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showMarkerDialog() }
            render(DiagnosticsSession.state.value)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DiagnosticsSession.state.collect { render(it) } }
                // Refresh the elapsed time while the dialog is visible.
                while (true) {
                    render(DiagnosticsSession.state.value)
                    delay(1000)
                }
            }
        }
        return dialog
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    private fun render(state: CaptureState) {
        val currentBinding = _binding ?: return
        val dialog = dialog as? AlertDialog
        when (state) {
            is CaptureState.Idle -> {
                currentBinding.textStatus.text = getString(R.string.diagnostics_status_idle)
                currentBinding.groupStartOptions.visibility = View.VISIBLE
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(R.string.diagnostics_start)
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
            }

            is CaptureState.Capturing -> {
                val elapsed = (SystemClock.uptimeMillis() - state.startedUptimeMs) / 1000
                currentBinding.textStatus.text = getString(
                    R.string.diagnostics_status_capturing,
                    DateUtils.formatElapsedTime(elapsed),
                    state.markers
                )
                currentBinding.groupStartOptions.visibility = View.GONE
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(R.string.diagnostics_stop)
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleCapture() {
        val context = requireContext().applicationContext
        val state = DiagnosticsSession.state.value
        lifecycleScope.launch {
            if (state is CaptureState.Capturing) {
                val sessionId = state.sessionId
                withContext(Dispatchers.IO) { DiagnosticsSession.stop("user") }
                dismissAllowingStateLoss()
                DiagnosticsExportDialogFragment.newInstance(sessionId, game)
                    .show(parentFragmentManager, DiagnosticsExportDialogFragment.TAG)
                return@launch
            }
            val options = CaptureOptions(
                level = if (binding.radioLevelDetailed.isChecked) {
                    CaptureLevel.Detailed
                } else {
                    CaptureLevel.Standard
                },
                stageNote = binding.editStage.text?.toString().orEmpty()
            )
            val error = withContext(Dispatchers.IO) {
                DiagnosticsSession.start(context, options, game)
            }
            if (error != null) {
                Toast.makeText(
                    context,
                    getString(R.string.diagnostics_start_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showMarkerDialog() {
        val markerBinding = DialogDiagnosticsMarkerBinding.inflate(layoutInflater)
        val labels = resources.getStringArray(R.array.diagnostics_issue_categories)
        var selected = 0
        markerBinding.dropdownCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        )
        markerBinding.dropdownCategory.setText(labels[selected], false)
        markerBinding.dropdownCategory.setOnItemClickListener { _, _, position, _ ->
            selected = position
        }
        // Mark the moment now; the category and note only describe it.
        val markedAt = SystemClock.uptimeMillis()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_mark_issue)
            .setView(markerBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val category = DiagnosticsSchema.ISSUE_CATEGORIES
                    .getOrElse(selected) { "other" }
                val note = markerBinding.editNote.text?.toString().orEmpty()
                val delayMs = SystemClock.uptimeMillis() - markedAt
                if (DiagnosticsSession.mark(category, note, delayMs) != null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.diagnostics_marker_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val TAG = "DiagnosticsDialogFragment"
        private const val GAME = "game"

        fun newInstance(game: Game?) = DiagnosticsDialogFragment().apply {
            arguments = bundleOf(GAME to game)
        }
    }
}
