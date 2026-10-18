// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    private val userDir = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files"

    @Test
    fun knownDirectoriesKeepRelativeStructure() {
        val redactor = DiagnosticsRedactor(mapOf(userDir to "<eden_user_dir>"))
        assertEquals(
            "Loading <eden_user_dir>/shader/0100000000010000/vulkan.bin",
            redactor.redactPaths("Loading $userDir/shader/0100000000010000/vulkan.bin")
        )
        assertEquals(1, redactor.replacements)
    }

    @Test
    fun storagePathsAndUrisAreRemoved() {
        val redactor = DiagnosticsRedactor()
        val text = redactor.redactPaths(
            "Game: /storage/emulated/0/Roms/Personal Folder/game.nsp " +
                "uri content://com.android.externalstorage.documents/tree/primary%3ARoms " +
                "sd /storage/1A2B-3C4D/Switch/game.xci owner someone@example.com"
        )
        assertFalse(text.contains("Personal"))
        assertFalse(text.contains("primary%3ARoms"))
        assertFalse(text.contains("1A2B-3C4D"))
        assertFalse(text.contains("someone@example.com"))
        assertTrue(text.contains("<path>"))
        assertTrue(text.contains("<uri>"))
        assertTrue(text.contains("<email>"))
    }

    @Test
    fun versionNumbersSurviveTextRedaction() {
        val redactor = DiagnosticsRedactor()
        val text = redactor.redactText("Vulkan 1.3.275 driver 512.744.6.0 conformance 1.3.8.3")
        // 1.3.8.3 is a valid IPv4 shape; only full redaction mode treats it as an address.
        assertTrue(text.contains("1.3.275"))
        assertTrue(text.contains("512.744.6.0"))
        val structured = DiagnosticsRedactor().redactPaths("conformance 1.3.8.3")
        assertEquals("conformance 1.3.8.3", structured)
    }

    @Test
    fun userChosenSettingValuesAreRemovedFromLogs() {
        val redactor = DiagnosticsRedactor()
        val text = redactor.redactText(
            "LogSettings: -- WebService.eden_username: Ricardo Silva\n" +
                "LogSettings: -- System.device_name: Meu Odin\n" +
                "LogSettings: -- Renderer.use_vsync: 1"
        )
        assertFalse(text.contains("Ricardo"))
        assertFalse(text.contains("Meu Odin"))
        assertTrue(text.contains("eden_username: <redacted>"))
        assertTrue(text.contains("use_vsync: 1"))
    }

    @Test
    fun networkAddressesAreRemovedFromText() {
        val redactor = DiagnosticsRedactor()
        val text = redactor.redactText("room 192.168.1.20:24872 mac 01:23:45:67:89:AB")
        assertEquals("room <ip> mac <mac>", text)
    }
}
