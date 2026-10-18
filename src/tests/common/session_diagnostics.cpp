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
    // Synthetic intervals end now; they must start after the capture began to be recorded.
    std::this_thread::sleep_for(400ms);
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

    const json sample = json::parse(TakeSampleJson());
    const json frames = sample["frames"]["guest_present_interval"];
    REQUIRE(frames["count"] == 100);
    REQUIRE(frames["p50_ms"] == 16.0);
    REQUIRE(frames["p99_ms"] == 40.0);
    REQUIRE(frames["max_ms"] == 120.0);
    REQUIRE(frames["over_33ms"] == 2);
    REQUIRE(frames["over_50ms"] == 1);
    REQUIRE(frames["over_100ms"] == 1);
    REQUIRE(frames["over_2x_median"] == 2);
    REQUIRE(frames["pacing"]["12_20ms"] == 98);
    REQUIRE(frames["pacing"]["38_55ms"] == 1);
    REQUIRE(frames["pacing"]["ge_55ms"] == 1);
    REQUIRE(frames["pacing"]["28_38ms"] == 0);

    // Only the 120 ms frame crosses the long-frame threshold.
    size_t long_frames = 0;
    for (const auto& event : sample["events"]) {
        if (event["name"] == "long_frame") {
            ++long_frames;
            REQUIRE(event["fields"]["interval_ms"] == "120");
        }
    }
    REQUIRE(long_frames == 1);

    const json session = json::parse(SessionSummaryJson())["frames"]["guest_present_interval"];
    REQUIRE(session["count"] == 100);
    // Histogram percentiles report the upper bound of a 0.25 ms bucket.
    REQUIRE(session["p50_ms"] == 16.25);
    REQUIRE(session["max_ms"] == 120.0);
    REQUIRE(session["pacing"]["12_20ms"] == 98);
    REQUIRE(session["pacing"]["38_55ms"] == 1);
    REQUIRE(session["pacing"]["ge_55ms"] == 1);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: long frame events are capped per capture", "[common]") {
    FreshCapture();
    for (int i = 0; i < 150; ++i) {
        RecordFrameInterval(FrameSeries::HostPresent, std::chrono::milliseconds(200 + i));
    }
    size_t long_frames = 0;
    const json sample = json::parse(TakeSampleJson());
    for (const auto& event : sample["events"]) {
        long_frames += event["name"] == "long_frame" ? 1 : 0;
    }
    REQUIRE(long_frames == 100);
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

TEST_CASE("SessionDiagnostics: sequential captures do not share events", "[common]") {
    FreshCapture();
    const u64 first = CaptureSequence();
    RecordEvent(Severity::Info, "test", "first_capture_event");
    StopCapture();
    RecordEvent(Severity::Info, "test", "between_captures");

    REQUIRE(StartCapture(""));
    const u64 second = CaptureSequence();
    REQUIRE(second == first + 1);
    RecordEvent(Severity::Info, "test", "second_capture_event");

    // The first sample of a capture only holds events recorded during it.
    const json sample = json::parse(TakeSampleJson());
    REQUIRE(sample["capture_seq"] == second);
    REQUIRE(sample["events"].size() == 1);
    REQUIRE(sample["events"][0]["name"] == "second_capture_event");
    REQUIRE(sample["events"][0]["capture_seq"] == second);

    // The ring keeps the older events as context, tagged with their own capture.
    for (const auto& event : json::parse(EventsJson())) {
        if (event["name"] == "first_capture_event") {
            REQUIRE(event["capture_seq"] == first);
        } else if (event["name"] == "between_captures") {
            REQUIRE(event["capture_seq"] == 0);
            REQUIRE(event["during_capture"] == false);
        }
    }
    StopCapture();
}

TEST_CASE("SessionDiagnostics: summary after stop is final", "[common]") {
    FreshCapture();
    std::this_thread::sleep_for(20ms);
    StopCapture();
    const json first = json::parse(SessionSummaryJson());
    REQUIRE(first["capturing"] == false);
    REQUIRE(first["capture_stop_mono_ms"].is_number());
    std::this_thread::sleep_for(20ms);
    const json second = json::parse(SessionSummaryJson());
    REQUIRE(second["duration_ms"] == first["duration_ms"]);
    REQUIRE(first["duration_ms"].get<s64>() >= 20);
}

TEST_CASE("SessionDiagnostics: events carry the game run", "[common]") {
    FreshCapture();
    const u64 run = BeginGameRun();
    REQUIRE(GameRunId() == run);
    RecordEvent(Severity::Info, "test", "in_game_run");
    const json events = json::parse(TakeSampleJson())["events"];
    REQUIRE(events.back()["game_run_id"] == run);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: fence waits are split by reason and thread", "[common]") {
    FreshCapture();
    RecordFenceWait(2ms);
    {
        ScopedWaitReason outer{WaitReason::SwapchainImage};
        RecordFenceWait(20ms);
        {
            ScopedWaitReason inner{WaitReason::GuestSync};
            REQUIRE(ScopedWaitReason::Current() == WaitReason::GuestSync);
            RecordFenceWait(5ms);
        }
        REQUIRE(ScopedWaitReason::Current() == WaitReason::SwapchainImage);
    }
    REQUIRE(ScopedWaitReason::Current() == WaitReason::Unspecified);
    std::thread worker{[] {
        ScopedWaitReason reason{WaitReason::TextureDownload};
        RecordFenceWait(40ms);
    }};
    worker.join();

    const json sample = json::parse(TakeSampleJson());
    REQUIRE(sample["timers"]["fence_wait"]["count"] == 4);
    const json reasons = sample["fence_wait_by_reason"];
    REQUIRE(reasons["unspecified"]["count"] == 1);
    REQUIRE(reasons["unspecified"]["distribution"]["1_4ms"] == 1);
    REQUIRE(reasons["swapchain_image"]["total_us"] == 20000);
    REQUIRE(reasons["swapchain_image"]["distribution"]["16_33ms"] == 1);
    REQUIRE(reasons["guest_sync"]["distribution"]["4_8ms"] == 1);
    REQUIRE(reasons["texture_download"]["distribution"]["ge_33ms"] == 1);
    REQUIRE_FALSE(reasons.contains("present_resources"));
    u64 thread_waits = 0;
    for (const auto& [name, entry] : sample["fence_wait_by_thread"].items()) {
        thread_waits += entry["count"].get<u64>();
    }
    REQUIRE(thread_waits == 4);
    REQUIRE(sample["fence_wait_by_thread"].size() >= 1);

    // Windows reset, the session keeps the totals.
    REQUIRE(json::parse(TakeSampleJson())["fence_wait_by_reason"].empty());
    const json summary = json::parse(SessionSummaryJson());
    REQUIRE(summary["fence_wait_by_reason"]["swapchain_image"]["count"] == 1);
    StopCapture();
}

TEST_CASE("SessionDiagnostics: frames spanning a pause are not recorded", "[common]") {
    FreshCapture();
    RecordFrameInterval(FrameSeries::GuestPresent, 16ms);
    NoteFrameDiscontinuity("paused");
    std::this_thread::sleep_for(20ms);
    NoteFrameDiscontinuity("resumed");
    // The first frame after resuming covers the pause.
    RecordFrameInterval(FrameSeries::GuestPresent, 13s);
    std::this_thread::sleep_for(20ms);
    RecordFrameInterval(FrameSeries::GuestPresent, 17ms);

    const json sample = json::parse(TakeSampleJson());
    const json frames = sample["frames"]["guest_present_interval"];
    REQUIRE(frames["count"] == 2);
    REQUIRE(frames["skipped_across_pause"] == 1);
    REQUIRE(frames["max_ms"] == 17.0);
    bool paused = false;
    for (const auto& event : sample["events"]) {
        paused |= event["category"] == "emulation" && event["name"] == "paused";
        REQUIRE(event["name"] != "long_frame");
    }
    REQUIRE(paused);
    const json session = json::parse(SessionSummaryJson());
    REQUIRE(session["frames"]["guest_present_interval"]["skipped_across_pause"] == 1);
    StopCapture();
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
