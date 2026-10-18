// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <string>
#include <string_view>

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
    out["game"] = GameJson();
    out["settings"] = SettingsJson();
    out["sections"] = sections.is_discarded() ? json::object() : std::move(sections);
    out["events"] = events.is_discarded() ? json::array() : std::move(events);
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

jboolean Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_isCapturing(JNIEnv* env,
                                                                           jobject obj) {
    return static_cast<jboolean>(Diag::IsCapturing());
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_takeSample(JNIEnv* env, jobject obj) {
    return SafeJson(env, [] { return Diag::TakeSampleJson(); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_sessionSummary(JNIEnv* env,
                                                                             jobject obj) {
    return SafeJson(env, [] { return Diag::SessionSummaryJson(); });
}

jstring Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_staticInfo(JNIEnv* env, jobject obj) {
    return SafeJson(env, [] { return StaticInfoJson().dump(); });
}

void Java_org_yuzu_yuzu_1emu_diagnostics_NativeDiagnostics_recordMarker(JNIEnv* env, jobject obj,
                                                                        jlong j_marker_id,
                                                                        jstring j_category) {
    Diag::RecordEvent(Diag::Severity::Info, "user", "marker",
                      {{"marker_id", std::to_string(j_marker_id)},
                       {"category", Common::Android::GetJString(env, j_category)}});
}

} // extern "C"
