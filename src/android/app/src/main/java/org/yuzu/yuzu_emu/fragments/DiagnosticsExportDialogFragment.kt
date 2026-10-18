// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogDiagnosticsExportBinding
import org.yuzu.yuzu_emu.diagnostics.BundlePlan
import org.yuzu.yuzu_emu.diagnostics.BundleResult
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsExporter
import org.yuzu.yuzu_emu.diagnostics.DiagnosticsSession
import org.yuzu.yuzu_emu.diagnostics.ExportOptions
import org.yuzu.yuzu_emu.diagnostics.SessionInfo
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.SerializableHelper.parcelable

/**
 * Shows what a bundle will contain before anything is written or shared. Sharing is always an
 * explicit user action through the system share sheet.
 */
class DiagnosticsExportDialogFragment : DialogFragment() {
    private var _binding: DialogDiagnosticsExportBinding? = null
    private val binding get() = _binding!!

    private var session: SessionInfo? = null
    private var plan: BundlePlan? = null
    private var previewJob: Job? = null

    private val sessionId: String? get() = arguments?.getString(SESSION_ID)
    private val game: Game? get() = arguments?.parcelable(GAME)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDiagnosticsExportBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_review_export)
            .setView(binding.root)
            .setPositiveButton(R.string.diagnostics_export_share, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (sessionId != null) {
            builder.setNeutralButton(R.string.diagnostics_delete, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { export() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { delete() }
        }
        listOf(
            binding.checkEdenLog,
            binding.checkGpuLog,
            binding.checkIdentity,
            binding.checkAdvanced
        )
            .forEach { it.setOnCheckedChangeListener { _, _ -> refreshPreview() } }
        refreshPreview()
        return dialog
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    private fun options() = ExportOptions(
        includeEdenLog = binding.checkEdenLog.isChecked,
        includeGpuLog = binding.checkGpuLog.isChecked,
        includeDeviceIdentity = binding.checkIdentity.isChecked,
        includeAdvancedDumps = binding.checkAdvanced.isChecked
    )

    private fun refreshPreview() {
        val context = requireContext().applicationContext
        val options = options()
        binding.textPreview.setText(R.string.diagnostics_preview_loading)
        setExportEnabled(false)
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val info = sessionId?.let { id ->
                        DiagnosticsSession.listSessions().firstOrNull { it.id == id }
                    }
                    info to DiagnosticsExporter.plan(context, info, options, game)
                }
            }
            val currentBinding = _binding ?: return@launch
            result.onSuccess { (info, built) ->
                session = info
                plan = built
                currentBinding.textPreview.text = previewText(built)
                setExportEnabled(true)
            }.onFailure {
                plan = null
                currentBinding.textPreview.text =
                    getString(
                        R.string.diagnostics_export_failed,
                        it.message ?: it.javaClass.simpleName
                    )
            }
        }
    }

    private fun previewText(built: BundlePlan): String {
        val redacted = built.report["privacy"]?.jsonObject?.get("redacted_values")
            ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return buildString {
            appendLine(getString(R.string.diagnostics_preview_redaction, redacted))
            appendLine()
            appendLine(getString(R.string.diagnostics_preview_contents))
            built.entries.forEach {
                val kib = String.format(Locale.ROOT, "%.1f", it.content.size / 1024.0)
                appendLine("  ${it.name}  $kib KiB")
            }
            appendLine()
            append(built.summary)
        }
    }

    private fun setExportEnabled(enabled: Boolean) {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
    }

    private fun export() {
        val built = plan ?: return
        val context = requireContext()
        setExportEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { DiagnosticsExporter.write(built, session) }
            when (result) {
                is BundleResult.Success -> {
                    Toast.makeText(context, R.string.diagnostics_export_saved, Toast.LENGTH_SHORT)
                        .show()
                    val share = DiagnosticsExporter.shareIntent(context, result.file)
                    startActivity(
                        Intent.createChooser(share, getString(R.string.diagnostics_export_share))
                    )
                    dismissAllowingStateLoss()
                }

                is BundleResult.Failure -> {
                    Toast.makeText(
                        context,
                        getString(R.string.diagnostics_export_failed, result.reason),
                        Toast.LENGTH_LONG
                    ).show()
                    setExportEnabled(true)
                }
            }
        }
    }

    private fun delete() {
        val info = session ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DiagnosticsSession.deleteSession(info) }
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val TAG = "DiagnosticsExportDialogFragment"
        private const val SESSION_ID = "session_id"
        private const val GAME = "game"

        /** [sessionId] null builds a snapshot report without a capture. */
        fun newInstance(sessionId: String?, game: Game? = null) =
            DiagnosticsExportDialogFragment().apply {
                arguments = bundleOf(SESSION_ID to sessionId, GAME to game)
            }
    }
}
