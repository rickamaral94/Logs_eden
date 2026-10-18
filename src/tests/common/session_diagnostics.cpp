// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>
#include <nlohmann/json.hpp>

#include "common/session_diagnostics.h"

namespace Common::Diagnostics {
namespace {

using json = nlohmann::json;
using namespace std::chrono_literals;

void FreshCapture() {
    StopCapture();
    REQUIRE(StartCapture(""));
    // Drain anything recorded by previous test cases.
    (void)TakeSampleJson();
}

} // Anonymous namespace

TEST_CASE("SessionDiagnostics: nothing is recorded without a capture", "[common]") {
    StopCapture();
    AddTiming(Timer::FenceWait, 5ms);
    Increment(Counter::GraphicsPipelineCacheMiss);
    RecordFrameInterval(FrameSeries::GuestPresent, 16ms);

    REQUIRE(StartCapture(""));
    const json sample = json::parse(TakeSampleJson());
    REQUIRE(sample["timers"]["fence_wait"]["count"] == 0);
    REQUIRE(sample["counters"]["graphics_pipeline_cache_miss"] == 0);
    REQUIRE(sample["frames"]["guest_present_interval"]["count"] == 0);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: double start is rejected", "[common]") {
    FreshCapture();
    REQUIRE_FALSE(StartCapture(""));
    StopCapture();
    REQUIRE_FALSE(IsCapturing());
}

TEST_CASE("SessionDiagnostics: timers and counters reset per window", "[common]") {
    FreshCapture();
    AddTiming(Timer::QueueSubmit, 2ms);
    AddTiming(Timer::QueueSubmit, 6ms);
    Increment(Counter::SwapchainCreated, 3);

    json sample = json::parse(TakeSampleJson());
    REQUIRE(sample["timers"]["queue_submit"]["count"] == 2);
    REQUIRE(sample["timers"]["queue_submit"]["total_us"] == 8000);
    REQUIRE(sample["timers"]["queue_submit"]["max_us"] == 6000);
    REQUIRE(sample["counters"]["swapchain_created"] == 3);

    sample = json::parse(TakeSampleJson());
    REQUIRE(sample["timers"]["queue_submit"]["count"] == 0);
    REQUIRE(sample["counters"]["swapchain_created"] == 0);

    const json summary = json::parse(SessionSummaryJson());
    REQUIRE(summary["timers"]["queue_submit"]["count"] == 2);
    REQUIRE(summary["counters"]["swapchain_created"] == 3);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: frame interval percentiles", "[common]") {
    FreshCapture();
    // 98 frames at 16 ms, one at 40 ms and one at 120 ms.
    for (int i = 0; i < 98; ++i) {
        RecordFrameInterval(FrameSeries::GuestPresent, 16ms);
    }
    RecordFrameInterval(FrameSeries::GuestPresent, 40ms);
    RecordFrameInterval(FrameSeries::GuestPresent, 120ms);

    const json frames = json::parse(TakeSampleJson())["frames"]["guest_present_interval"];
    REQUIRE(frames["count"] == 100);
    REQUIRE(frames["p50_ms"] == 16.0);
    REQUIRE(frames["p99_ms"] == 40.0);
    REQUIRE(frames["max_ms"] == 120.0);
    REQUIRE(frames["over_33ms"] == 2);
    REQUIRE(frames["over_50ms"] == 1);
    REQUIRE(frames["over_100ms"] == 1);
    REQUIRE(frames["over_2x_median"] == 2);

    const json session = json::parse(SessionSummaryJson())["frames"]["guest_present_interval"];
    REQUIRE(session["count"] == 100);
    // Histogram percentiles report the upper bound of a 0.25 ms bucket.
    REQUIRE(session["p50_ms"] == 16.25);
    REQUIRE(session["max_ms"] == 120.0);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: identical consecutive events are merged", "[common]") {
    FreshCapture();
    RecordEvent(Severity::Error, "vulkan", "check_failed", {{"result", "VK_TIMEOUT"}});
    RecordEvent(Severity::Error, "vulkan", "check_failed", {{"result", "VK_TIMEOUT"}});
    RecordEvent(Severity::Error, "vulkan", "check_failed", {{"result", "VK_ERROR_DEVICE_LOST"}});

    const json events = json::parse(TakeSampleJson())["events"];
    REQUIRE(events.size() == 2);
    REQUIRE(events[0]["repeat"] == 2);
    REQUIRE(events[0]["during_capture"] == true);
    REQUIRE(events[1]["fields"]["result"] == "VK_ERROR_DEVICE_LOST");

    // Events already sampled are not repeated in the next window.
    REQUIRE(json::parse(TakeSampleJson())["events"].empty());
    StopCapture();
}

TEST_CASE("SessionDiagnostics: events are kept outside a capture", "[common]") {
    StopCapture();
    RecordEvent(Severity::Critical, "vulkan", "init_failed_test");
    const json events = json::parse(EventsJson());
    REQUIRE_FALSE(events.empty());
    REQUIRE(events.back()["name"] == "init_failed_test");
    REQUIRE(events.back()["during_capture"] == false);
}

TEST_CASE("SessionDiagnostics: sections and samplers", "[common]") {
    FreshCapture();
    SetSection("test_section", R"({"value":1})");
    SetSection("broken_section", "not json");
    RegisterSampler("test_sampler", [] { return std::string{R"({"heap":42})"}; });

    const json sections = json::parse(SectionsJson());
    REQUIRE(sections["test_section"]["value"] == 1);
    REQUIRE(sections["broken_section"]["unparsed"] == "not json");

    json sample = json::parse(TakeSampleJson());
    REQUIRE(sample["samplers"]["test_sampler"]["heap"] == 42);

    UnregisterSampler("test_sampler");
    sample = json::parse(TakeSampleJson());
    REQUIRE_FALSE(sample["samplers"].contains("test_sampler"));
    StopCapture();
}

TEST_CASE("SessionDiagnostics: concurrent recording and sampling", "[common]") {
    FreshCapture();
    constexpr int Threads = 4;
    constexpr int Iterations = 2000;
    std::vector<std::thread> workers;
    for (int t = 0; t < Threads; ++t) {
        workers.emplace_back([] {
            for (int i = 0; i < Iterations; ++i) {
                AddTiming(Timer::FenceWait, 1us);
                Increment(Counter::DrawSkippedPipelinePending);
                RecordFrameInterval(FrameSeries::HostPresent, 16ms);
            }
        });
    }
    u64 sampled_count = 0;
    for (int i = 0; i < 20; ++i) {
        sampled_count += json::parse(TakeSampleJson())["timers"]["fence_wait"]["count"].get<u64>();
    }
    for (auto& worker : workers) {
        worker.join();
    }
    sampled_count += json::parse(TakeSampleJson())["timers"]["fence_wait"]["count"].get<u64>();
    REQUIRE(sampled_count == Threads * Iterations);

    const json summary = json::parse(SessionSummaryJson());
    REQUIRE(summary["counters"]["draw_skipped_pipeline_pending"] == Threads * Iterations);
    REQUIRE(summary["frames"]["host_present_interval"]["count"] == Threads * Iterations);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: critical marker is written to the session directory", "[common]") {
    const auto dir = std::filesystem::temp_directory_path() / "eden_session_diag_test";
    std::filesystem::remove_all(dir);
    std::filesystem::create_directories(dir);

    StopCapture();
    REQUIRE(StartCapture(dir.string()));
    RecordEvent(Severity::Critical, "vulkan", "device_lost");
    WriteCriticalMarker("device_lost");
    StopCapture();

    size_t markers = 0;
    for (const auto& entry : std::filesystem::directory_iterator(dir)) {
        ++markers;
        std::ifstream file{entry.path()};
        const json marker = json::parse(file);
        REQUIRE(marker["reason"] == "device_lost");
        REQUIRE(marker["events"].back()["name"] == "device_lost");
    }
    REQUIRE(markers == 1);
    std::filesystem::remove_all(dir);
}

} // namespace Common::Diagnostics
