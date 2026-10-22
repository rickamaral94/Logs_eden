// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <string>
#include <string_view>

#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#include <android/api-level.h>
#include <fmt/format.h>
#include <jni.h>
#include <nlohmann/json.hpp>

#include "common/android/android_common.h"
#include "common/logging.h"
#include "common/scm_rev.h"
#include "common/session_diagnostics.h"
#include "common/settings.h"
#include "core/core.h"
#include "native.h"

namespace {

namespace Diag = Common::Diagnostics;
using nlohmann::json;

// Categories that hold paths, accounts, network or input configuration. They are not needed to
// reproduce rendering or performance issues and may contain personal data.
constexpr std::array EXCLUDED_CATEGORIES{
    Settings::Category::Paths,       Settings::Category::DataStorage,
    Settings::Category::WebService,  Settings::Category::Multiplayer,
    Settings::Category::Network,     Settings::Category::Controls,
    Settings::Category::Shortcuts,   Settings::Category::Ui,
    Settings::Category::UiAudio,     Settings::Category::UiGeneral,
    Settings::Category::UiLayout,    Settings::Category::UiGameList,
    Settings::Category::Screenshots,
};

// Console identifiers that are not needed to reproduce an issue. The values used by the emulator
// are not changed; they are only left out of the export.
constexpr std::array<std::string_view, 2> EXCLUDED_SETTINGS{
    "serial_battery",
    "serial_unit",
};

// String settings are free text (names, paths, device ids); only these are known to be safe.
constexpr std::array<std::string_view, 3> ALLOWED_STRING_SETTINGS{
    "log_filter",
    "post_shader_chain",
    "post_shader_preset",
};

json SettingsJson() {
    json out = json::array();
    for (const auto& [category, settings] : Settings::values.linkage.by_category) {
        if (std::ranges::find(EXCLUDED_CATEGORIES, category) != EXCLUDED_CATEGORIES.end()) {
            continue;
        }
        for (const auto* setting : settings) {
            const std::string label = setting->GetLabel();
            if (std::ranges::find(EXCLUDED_SETTINGS, label) != EXCLUDED_SETTINGS.end()) {
                continue;
            }
            if (setting->TypeId() == "string" &&
                std::ranges::find(ALLOWED_STRING_SETTINGS, label) ==
                    ALLOWED_STRING_SETTINGS.end()) {
                continue;
            }
            out.push_back({
                {"category", Settings::TranslateCategory(category)},
                {"name", label},
                {"value", setting->Canonicalize()},
                {"is_default", setting->ToString() == setting->DefaultToString()},
                {"per_game", !setting->UsingGlobal()},
            });
        }
    }
    return out;
}

json GameJson() {
    auto& session = EmulationSession::GetInstance();
    if (!session.IsRunning()) {
        return json{{"running", false}};
    }
    const auto& system = session.System();
    const auto& build_id = system.GetApplicationProcessBuildID();
    std::string build_id_hex;
    for (const u8 byte : build_id) {
        build_id_hex += fmt::format("{:02X}", byte);
    }
    return json{
        {"running", true},
        {"program_id", fmt::format("{:016X}", system.GetApplicationProcessProgramID())},
        {"build_id", build_id_hex},
    };
}

// Mesa/Freedreno variables in effect for this process (set through the Freedreno settings
// before the driver loaded). Values are debug flags, not paths.
json DriverEnvironmentJson() {
    json out = json::object();
    for (const char* name : {"TU_DEBUG", "FD_MESA_DEBUG", "IR3_SHADER_DEBUG", "TU_BREADCRUMBS",
                             "FD_RD_DUMP", "FD_RD_DUMP_FRAMES", "FD_DEV_FEATURES",
                             "MESA_SHADER_CACHE_DISABLE", "MESA_VK_WSI_PRESENT_MODE"}) {
        if (const char* value = getenv(name)) {
            out[name] = value;
        }
    }
    return out;
}

json StaticInfoJson() {
    json build = json::object();
    build["version"] = std::string{Common::g_build_version};
    build["fullname"] = std::string{Common::g_build_fullname};
    build["branch"] = std::string{Common::g_scm_branch};
    build["description"] = std::string{Common::g_scm_desc};
    build["revision"] = std::string{Common::g_scm_rev};
    build["build_id"] = std::string{Common::g_build_id};
    build["build_date"] = std::string{Common::g_build_date};
    build["compiler"] = std::string{Common::g_compiler_id};
    build["is_dev_build"] = Common::g_is_dev_build;
    build["is_nightly_build"] = Common::g_is_nightly_build;

    json sections = json::parse(Diag::SectionsJson(), nullptr, false);
    json events = json::parse(Diag::EventsJson(), nullptr, false);

    json out = json::object();
    out["build"] = std::move(build);
    out["android_api_level_native"] = android_get_device_api_level();
    out["cpu_backend"] = Settings::IsNceEnabled() ? "NCE" : "JIT";
    out["stage"] = std::string{Diag::StageName(Diag::GetStage())};
    out["capture_seq"] = Diag::CaptureSequence();
    out["game_run_id"] = Diag::GameRunId();
    out["mono_ms"] = Diag::MonotonicMs();
    // Maps the "[ seconds]" prefix of eden_log.txt lines to the monotonic clock.
    out["log_elapsed_us"] = Common::Log::ElapsedTime().count();
    out["game"] = GameJson();
    out["settings"] = SettingsJson();
    out["driver_environment"] = DriverEnvironmentJson();
    out["sections"] = sections.is_discarded() ? json::object() : std::move(sections);
    out["events"] = events.is_discarded() ? json::array() : std::move(events);
    return out;
}

// One line per thread of this process: "tid starttime utime+stime processor\tname". Reading the
// stat files natively avoids a Java File/Reader per thread, which dominated the sampler cost.
std::string ThreadStats() {
    std::string out;
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) {
        return out;
    }
    std::array<char, 1024> buffer{};
    while (const dirent* entry = readdir(dir)) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') {
            continue;
        }
        const std::string path = fmt::format("/proc/self/task/{}/stat", entry->d_name);
        const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        const ssize_t size = read(fd, buffer.data(), buffer.size() - 1);
        close(fd);
        if (size <= 0) {
            continue;
        }
        const std::string_view stat{buffer.data(), static_cast<size_t>(size)};
        const size_t open_paren = stat.find('(');
        const size_t close_paren = stat.rfind(')');
        if (open_paren == std::string_view::npos || close_paren == std::string_view::npos ||
            close_paren < open_paren || close_paren + 2 >= stat.size()) {
            continue;
        }
        // Fields after the name start at field 3 (state).
        std::array<std::string_view, 40> fields{};
        size_t count = 0;
        std::string_view rest = stat.substr(close_paren + 2);
        while (!rest.empty() && count < fields.size()) {
            const size_t space = rest.find(' ');
            fields[count++] = rest.substr(0, space);
            rest = space == std::string_view::npos ? std::string_view{} : rest.substr(space + 1);
        }
        if (count < 37) {
            continue;
        }
        const auto number = [](std::string_view text) {
            u64 value = 0;
            for (const char c : text) {
                if (c < '0' || c > '9') {
                    break;
                }
                value = value * 10 + static_cast<u64>(c - '0');
            }
            return value;
        };
        // utime 14, stime 15, starttime 22, processor 39 (1-based field numbers).
        const u64 ticks = number(fields[11]) + number(fields[12]);
        std::string name{stat.substr(open_paren + 1, close_paren - open_paren - 1)};
        std::ranges::replace(name, '\t', ' ');
        std::ranges::replace(name, '\n', ' ');
        out += fmt::format("{} {} {} {}\t{}\n", entry->d_name, number(fields[19]), ticks,
                           number(fields[36]), name);
    }
    closedir(dir);
    return out;
}

jstring SafeJson(JNIEnv* env, auto&& producer) {
    try {
        return Common::Android::ToJString(env, producer());
    } catch (const std::exception& e) {
        return Common::Android::ToJString(env, json{{"error", e.what()}}.dump());
    }
}

} // Anonymous namespace

extern "C" {

jboolean Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_startCapture(JNIEnv* env,
                                                                            jobject obj,
                                                                            jstring j_session_dir) {
    return static_cast<jboolean>(
        Diag::StartCapture(Common::Android::GetJString(env, j_session_dir)));
}

void Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_stopCapture(JNIEnv* env, jobject obj) {
    Diag::StopCapture();
}

void Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_flushLog(JNIEnv* env, jobject obj) {
    Common::Log::Flush();
}

void Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_setGpuTimestampsEnabled(
    JNIEnv* env, jobject obj, jboolean enabled) {
    Diag::SetGpuTimestampsEnabled(enabled != JNI_FALSE);
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_readThreadStats(JNIEnv* env,
                                                                             jobject obj) {
    return Common::Android::ToJString(env, ThreadStats());
}

jlong Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_captureSequence(JNIEnv* env,
                                                                           jobject obj) {
    return static_cast<jlong>(Diag::CaptureSequence());
}

jboolean Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_isCapturing(JNIEnv* env,
                                                                           jobject obj) {
    return static_cast<jboolean>(Diag::IsCapturing());
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_takeSample(JNIEnv* env, jobject obj) {
    return SafeJson(env, [] { return Diag::TakeSampleJson(); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_takeFrames(JNIEnv* env, jobject obj,
                                                                         jboolean all) {
    return SafeJson(env, [all] { return Diag::TakeFramesJson(all != JNI_FALSE); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_sessionSummary(JNIEnv* env,
                                                                             jobject obj) {
    return SafeJson(env, [] { return Diag::SessionSummaryJson(); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_staticInfo(JNIEnv* env, jobject obj) {
    return SafeJson(env, [] { return StaticInfoJson().dump(); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_markScene(JNIEnv* env, jobject obj,
                                                                        jstring j_kind,
                                                                        jstring j_name,
                                                                        jlong j_scene_id) {
    const std::string kind = Common::Android::GetJString(env, j_kind);
    const std::string name = Common::Android::GetJString(env, j_name);
    return SafeJson(env, [&] {
        return Diag::MarkScene(kind, name, static_cast<u64>(j_scene_id));
    });
}

void Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_recordMarker(JNIEnv* env, jobject obj,
                                                                        jlong j_marker_id,
                                                                        jstring j_category) {
    Diag::RecordEvent(Diag::Severity::Info, "user", "marker",
                      {{"marker_id", std::to_string(j_marker_id)},
                       {"category", Common::Android::GetJString(env, j_category)}});
}

} // extern "C"
