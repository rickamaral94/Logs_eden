// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <chrono>
#include <set>
#include <string>
#include <thread>

#include <catch2/catch_test_macros.hpp>
#include <nlohmann/json.hpp>

#include "common/diagnostics_frame_log.h"
#include "common/diagnostics_gpu_timing.h"
#include "common/diagnostics_render_passes.h"
#include "common/session_diagnostics.h"

namespace Common::Diagnostics::FrameLog {
namespace {

using json = nlohmann::json;
using namespace std::chrono_literals;

size_t Column(const json& drained, std::string_view name) {
    const auto& columns = drained["columns"];
    for (size_t i = 0; i < columns.size(); ++i) {
        if (columns[i] == name) {
            return i;
        }
    }
    FAIL("missing column " << name);
    return 0;
}

Values Totals(u64 draws, u64 copies_closing) {
    Values values{};
    values[Index::Draws] = draws;
    values[Index::EndedBy + static_cast<size_t>(RenderPasses::EndReason::Copy)] = copies_closing;
    return values;
}

} // Anonymous namespace

TEST_CASE("FrameLog: columns are unique and match the values", "[common]") {
    const auto names = Log::ColumnNames();
    REQUIRE(names.size() == 4 + ValueCount + 2);
    REQUIRE(std::set<std::string_view>(names.begin(), names.end()).size() == names.size());
    REQUIRE(names[4 + Index::EndedBy] == "end_next_target");
    REQUIRE(names[4 + Index::EndedBy + RenderPasses::EndReasonCount - 1] == "end_unknown");
    REQUIRE(names[4 + Index::WaitByReasonUs + static_cast<size_t>(WaitReason::TextureDownload)] ==
            "wait_texture_download_us");
}

TEST_CASE("FrameLog: records are differences between frame ends", "[common]") {
    Log log;
    log.OnFrame(10, 1'000'000, false, Totals(100, 4));
    log.OnFrame(11, 1'025'000, false, Totals(160, 10));
    log.OnFrame(12, 1'050'000, true, Totals(161, 10));
    const json drained = json::parse(log.Drain(100, false));
    const auto& rows = drained["rows"];
    REQUIRE(rows.size() == 3);
    // The first frame of a capture covers everything since the start: flagged, not a frame.
    REQUIRE(rows[0][Column(drained, "across_pause")] == 1);
    REQUIRE(rows[0][Column(drained, "interval_us")] == 0);
    REQUIRE(rows[1][Column(drained, "frame")] == 11);
    REQUIRE(rows[1][Column(drained, "interval_us")] == 25'000);
    REQUIRE(rows[1][Column(drained, "end_mono_us")] == 1'025'000);
    REQUIRE(rows[1][Column(drained, "draws")] == 60);
    REQUIRE(rows[1][Column(drained, "end_copy")] == 6);
    REQUIRE(rows[1][Column(drained, "across_pause")] == 0);
    REQUIRE(rows[2][Column(drained, "across_pause")] == 1);
    REQUIRE(rows[2][Column(drained, "draws")] == 1);
}

TEST_CASE("FrameLog: GPU time is joined by frame id and late frames are not held", "[common]") {
    Log log;
    log.SetGpu(1, 7000, true); // before its frame end: kept until the record exists
    log.OnFrame(1, 0, false, {});
    log.OnFrame(2, 10, false, {});
    log.OnFrame(3, 20, false, {});
    log.SetGpu(2, 0, false);
    // Frame 3 has no result yet and is younger than the wait limit: it stays queued.
    json drained = json::parse(log.Drain(3 + 1, false));
    REQUIRE(drained["rows"].size() == 2);
    REQUIRE(drained["rows"][0][Column(drained, "gpu_us")] == 7000);
    REQUIRE(drained["rows"][0][Column(drained, "gpu_state")] == 1);
    REQUIRE(drained["rows"][1][Column(drained, "gpu_us")] == -1);
    REQUIRE(drained["rows"][1][Column(drained, "gpu_state")] == 2);
    drained = json::parse(log.Drain(3 + Log::GpuWaitFrames, false));
    REQUIRE(drained["rows"].size() == 1);
    REQUIRE(drained["rows"][0][Column(drained, "gpu_state")] == 3); // missing
    log.SetGpu(3, 5000, true); // after export
    const json session = json::parse(log.SessionJson());
    REQUIRE(session["gpu_complete"] == 1);
    REQUIRE(session["gpu_partial"] == 1);
    REQUIRE(session["gpu_missing"] == 1);
    REQUIRE(session["gpu_results_after_export"] == 1);
}

TEST_CASE("FrameLog: the queue is bounded and drops are counted", "[common]") {
    Log log;
    for (u64 i = 0; i < Log::Capacity + 5; ++i) {
        log.OnFrame(i, static_cast<s64>(i) * 16'000, false, {});
    }
    json session = json::parse(log.SessionJson());
    REQUIRE(session["frames_queued"] == Log::Capacity);
    REQUIRE(session["frames_dropped_queue_full"] == 5);
    const json drained = json::parse(log.Drain(0, true));
    REQUIRE(drained["rows"].size() == Log::Capacity);
    REQUIRE(drained["rows"][0][0] == 0); // oldest kept, rows contiguous
    session = json::parse(log.SessionJson());
    REQUIRE(session["frames_queued"] == 0);
    log.Reset();
    REQUIRE(json::parse(log.SessionJson())["frames_recorded"] == 0);
}

TEST_CASE("FrameLog: host frames of a capture carry existing counters", "[common]") {
    StopCapture();
    REQUIRE(StartCapture(""));
    std::this_thread::sleep_for(20ms);
    (void)TakeFramesJson(true);
    const u64 first = HostFrameId();
    AdvanceHostFrame(); // closes the frame that was open at capture start
    Increment(Counter::DrawCalls, 7);
    Increment(Counter::GraphicsPipelineCacheMiss, 2);
    AddTiming(Timer::FenceWait, 3ms);
    {
        ScopedWaitReason reason{WaitReason::TextureDownload};
        RecordFenceWait(2ms);
    }
    auto& passes = RenderPasses::Global();
    passes.Begin(RenderPasses::PassInfo{.render_pass = 1, .target = 1, .width = 8, .height = 8,
                                        .samples = 1, .color_attachments = 1,
                                        .depth_stencil = false, .color_clear_mask = 0,
                                        .depth_stencil_clear = false, .color_discard_mask = 0,
                                        .depth_stencil_discard = false, .resolve = false},
                 HostFrameId());
    passes.NoteDraw();
    passes.End(RenderPasses::EndReason::Readback);
    std::this_thread::sleep_for(5ms);
    AdvanceHostFrame();

    const json drained = json::parse(TakeFramesJson(true));
    REQUIRE(drained["rows"].size() == 2);
    const auto& row = drained["rows"][1];
    REQUIRE(row[Column(drained, "frame")] == first + 1);
    REQUIRE(row[Column(drained, "across_pause")] == 0);
    REQUIRE(row[Column(drained, "interval_us")] >= 5000);
    REQUIRE(row[Column(drained, "draws")] == 7);
    REQUIRE(row[Column(drained, "pipelines_created")] == 2);
    REQUIRE(row[Column(drained, "passes")] == 1);
    REQUIRE(row[Column(drained, "end_readback")] == 1);
    REQUIRE(row[Column(drained, "fence_wait_us")] == 5000);
    REQUIRE(row[Column(drained, "wait_texture_download_us")] == 2000);
    // The end time is on the same monotonic clock as events and markers.
    const s64 end_ms = row[Column(drained, "end_mono_us")].get<s64>() / 1000;
    REQUIRE(std::abs(end_ms - MonotonicMs()) < 1000);
    const json summary = json::parse(SessionSummaryJson());
    REQUIRE(summary["frame_log"]["frames_recorded"] == 2);
    StopCapture();
}

TEST_CASE("FrameLog: scene markers align with frame records", "[common]") {
    StopCapture();
    REQUIRE(StartCapture(""));
    (void)TakeFramesJson(true);
    AdvanceHostFrame();
    const json start = json::parse(MarkScene("scene_start", "bridge", 1));
    REQUIRE(start["capturing"] == true);
    REQUIRE(start["host_frame_id"] == HostFrameId());
    for (int i = 0; i < 3; ++i) {
        std::this_thread::sleep_for(2ms);
        AdvanceHostFrame();
    }
    const json end = json::parse(MarkScene("scene_end", "bridge", 1));
    REQUIRE(end["host_frame_id"].get<u64>() == start["host_frame_id"].get<u64>() + 3);
    const json drained = json::parse(TakeFramesJson(true));
    // Whole frames of the scene: ids strictly between the two markers. Each ends after the
    // start marker and no later than the end marker on the same clock.
    const u64 first = start["host_frame_id"].get<u64>();
    const u64 last = end["host_frame_id"].get<u64>();
    size_t inside = 0;
    for (const auto& row : drained["rows"]) {
        const u64 id = row[Column(drained, "frame")].get<u64>();
        const s64 end_us = row[Column(drained, "end_mono_us")].get<s64>();
        if (id > first && id < last) {
            ++inside;
            REQUIRE(end_us > start["mono_us"].get<s64>());
            REQUIRE(end_us <= end["mono_us"].get<s64>());
        }
    }
    REQUIRE(inside == 2);
    // The marker is also an event of this capture.
    const json events = json::parse(EventsJson());
    bool found = false;
    for (const auto& event : events) {
        found |= event["category"] == "scene" && event["name"] == "scene_end";
    }
    REQUIRE(found);
    StopCapture();
}

} // namespace Common::Diagnostics::FrameLog
