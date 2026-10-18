// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.yuzu.yuzu_emu.BuildConfig
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.PatchType
import org.yuzu.yuzu_emu.utils.GpuDriverHelper

/** One-off descriptions collected when a capture starts. */
object DeviceInfoCollector {
    /** Fields under "identity" are dropped from the report when the user opts out. */
    fun device(context: Context): JsonObject {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors()
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return buildJsonObject {
            put(
                "identity",
                buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("device", Build.DEVICE)
                    put("product", Build.PRODUCT)
                    put("fingerprint", Build.FINGERPRINT)
                }
            )
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                put("soc_manufacturer", Build.SOC_MANUFACTURER)
                put("soc_model", Build.SOC_MODEL)
            }
            put("supported_abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })
            put("android_release", Build.VERSION.RELEASE)
            put("android_api", Build.VERSION.SDK_INT)
            put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("kernel", System.getProperty("os.version"))
            put("cpu_cores", cores)
            put(
                "cpu_max_freq_mhz",
                buildJsonArray {
                    for (core in 0 until cores) {
                        val khz = runCatching {
                            File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                                .readText().trim().toLong()
                        }.getOrNull()
                        add(khz?.div(1000L))
                    }
                }
            )
            put("cpu_summary", runCatching { NativeLibrary.getCpuSummary() }.getOrNull())
            put("total_memory_kb", memoryInfo.totalMem / 1024)
            put("display_refresh_rate_hz", display?.refreshRate)
            display?.mode?.let {
                put("display_width_px", it.physicalWidth)
                put("display_height_px", it.physicalHeight)
            }
        }
    }

    fun app(): JsonObject = buildJsonObject {
        put("application_id", BuildConfig.APPLICATION_ID)
        put("version_name", BuildConfig.VERSION_NAME)
        put("version_code", BuildConfig.VERSION_CODE)
        put("build_type", BuildConfig.BUILD_TYPE)
        put("flavor", BuildConfig.FLAVOR)
        put("build_version_native", runCatching { NativeLibrary.getBuildVersion() }.getOrNull())
    }

    /** Driver selection as configured in Eden. Vulkan-reported identity lives in the native sections. */
    fun graphicsDriver(): JsonObject {
        val customPath = runCatching { StringSetting.DRIVER_PATH.getString() }.getOrDefault("")
        val custom = if (customPath.isNotEmpty()) {
            runCatching { GpuDriverHelper.customDriverSettingData }.getOrNull()
        } else {
            null
        }
        return buildJsonObject {
            put("selection", if (custom != null) "custom" else "system")
            put(
                "custom_driver_loading_supported",
                runCatching { GpuDriverHelper.supportsCustomDriverLoading() }.getOrNull()
            )
            if (custom != null) {
                put(
                    "custom",
                    buildJsonObject {
                        put("name", custom.name)
                        put("version", custom.version)
                        put("vendor", custom.vendor)
                        put("author", custom.author)
                        put("min_api", custom.minApi)
                        put("library_name", custom.libraryName)
                    }
                )
            }
        }
    }

    /** Title and enabled add-ons. Game files themselves are never read or copied. */
    fun game(game: Game?): JsonObject {
        if (game == null) {
            return buildJsonObject { put("known", false) }
        }
        val patches = runCatching { NativeLibrary.getPatchesForFile(game.path, game.programId) }
            .getOrNull()
        return buildJsonObject {
            put("known", true)
            put("title", game.title)
            put("program_id", game.programIdHex)
            put("is_homebrew", game.isHomebrew)
            put("patches_available", patches != null)
            put(
                "patches",
                buildJsonArray {
                    patches?.forEach { patch ->
                        add(
                            buildJsonObject {
                                put("name", patch.name)
                                put("version", patch.version)
                                put("type", PatchType.from(patch.type).name.lowercase())
                                put("enabled", patch.enabled)
                            }
                        )
                    }
                }
            )
        }
    }
}
