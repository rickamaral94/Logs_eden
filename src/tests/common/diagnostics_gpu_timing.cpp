// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <catch2/catch_test_macros.hpp>
#include <nlohmann/json.hpp>

#include "common/diagnostics_gpu_timing.h"

namespace Common::Diagnostics::GpuTiming {
namespace {

using json = nlohmann::json;

// Adreno 740 on Turnip reports a 52.083 ns period and 48 valid bits on the graphics queue.
constexpr double Period = 52.08333206176758;
constexpr u32 ValidBits = 48;

u64 Ticks(double ms) {
    return static_cast<u64>(ms * 1'000'000.0 / Period);
}

} // Anonymous namespace

TEST_CASE("GpuTiming: ring never hands out a slot that is in flight", "[common]") {
    QueryRing ring{2};
    const auto a = ring.Acquire();
    const auto b = ring.Acquire();
    REQUIRE(a.has_value());
    REQUIRE(b.has_value());
    REQUIRE(*a != *b);
    REQUIRE_FALSE(ring.Acquire().has_value()); // saturated

    ring.Submit(*a, 10, 1, 1);
    ring.Submit(*b, 11, 1, 2);
    REQUIRE(ring.Completed(9).empty());
    const auto done = ring.Completed(10);
    REQUIRE(done.size() == 1);
    REQUIRE(done[0].slot == *a);
    REQUIRE_FALSE(ring.Acquire().has_value()); // completed but not released yet

    ring.Release(*a);
    REQUIRE(ring.Acquire() == a);
    REQUIRE(ring.PendingCount() == 1);
}

TEST_CASE("GpuTiming: abandoned and dropped slots return to the ring", "[common]") {
    QueryRing ring{2};
    const auto a = ring.Acquire();
    ring.Abandon(*a);
    const auto b = ring.Acquire();
    const auto c = ring.Acquire();
    REQUIRE(b.has_value());
    REQUIRE(c.has_value());
    ring.Submit(*b, 1, 0, 0);
    ring.Submit(*c, 2, 0, 1);
    REQUIRE(ring.Retry(*b) == 1);
    REQUIRE(ring.Retry(*b) == 2);
    REQUIRE(ring.DropAllPending() == 2);
    REQUIRE(ring.PendingCount() == 0);
    REQUIRE(ring.Acquire().has_value());
    REQUIRE(ring.Acquire().has_value());
}

TEST_CASE("GpuTiming: deltas handle 48-bit wraparound", "[common]") {
    const u64 max = (u64{1} << ValidBits) - 1;
    const u64 begin = max - Ticks(1.0);
    const u64 end = Ticks(2.0); // wrapped past zero
    const u64 ns = Aggregator::DeltaNs(begin, end, ValidBits, Period);
    REQUIRE(ns > 2'999'000);
    REQUIRE(ns < 3'001'000);
    // Bits above timestampValidBits are undefined and must be ignored.
    const u64 garbage = u64{0xABCD} << ValidBits;
    REQUIRE(Aggregator::DeltaNs(begin | garbage, end, ValidBits, Period) == ns);
}

TEST_CASE("GpuTiming: frames are complete only when every submission was measured", "[common]") {
    Aggregator aggregator;
    aggregator.Configure(true, {}, Period, ValidBits);
    aggregator.Reset();

    // Frame 1: two submissions, both measured, overlapping by 1 ms -> 5 ms busy union.
    aggregator.NoteSubmit(1, true);
    aggregator.NoteSubmit(1, true);
    // Frame 2: one measured, one dropped because the ring was full.
    aggregator.NoteSubmit(2, true);
    aggregator.NoteSubmit(2, false);
    aggregator.NoteRingFull();
    // Frame 3 closes frame 2.
    aggregator.NoteSubmit(3, true);

    aggregator.AddResult(1, Ticks(100.0), Ticks(103.0));
    aggregator.AddResult(1, Ticks(102.0), Ticks(105.0));
    aggregator.AddResult(2, Ticks(110.0), Ticks(112.0));
    aggregator.AddResult(3, Ticks(120.0), Ticks(121.0));

    const json window = json::parse(aggregator.TakeWindowJson());
    REQUIRE(window["available"] == true);
    REQUIRE(window["submits"] == 5);
    REQUIRE(window["results"] == 4);
    REQUIRE(window["dropped_ring_full"] == 1);
    REQUIRE(window["frames_complete"] == 1);
    REQUIRE(window["frames_partial"] == 1);
    const double frame_ms = window["frame_gpu"]["max_ms"].get<double>();
    REQUIRE(frame_ms > 4.99);
    REQUIRE(frame_ms < 5.01);
    // Sum of submissions counts the overlap twice; the union does not.
    REQUIRE(window["submit_sum_ms"].get<double>() > window["submit_busy_union_ms"].get<double>());
    REQUIRE(window["coverage_pct"].get<double>() == 80.0);

    const json next = json::parse(aggregator.TakeWindowJson());
    REQUIRE(next["submits"] == 0);
    REQUIRE(next["frame_gpu"]["count"] == 0);

    const json session = json::parse(aggregator.SessionJson());
    REQUIRE(session["frames_complete"] == 1);
    REQUIRE(session["frame_gpu"]["count"] == 1);
    REQUIRE(session["timestamp_valid_bits"] == ValidBits);
}

TEST_CASE("GpuTiming: frames without any result are partial", "[common]") {
    Aggregator aggregator;
    aggregator.Configure(true, {}, Period, ValidBits);
    aggregator.Reset();
    aggregator.NoteSubmit(1, false);
    aggregator.NoteSubmit(2, true);
    aggregator.NoteSubmit(3, true);
    aggregator.AddResult(2, Ticks(1.0), Ticks(2.0));
    aggregator.AddResult(3, Ticks(3.0), Ticks(4.0));
    const json window = json::parse(aggregator.TakeWindowJson());
    REQUIRE(window["frames_complete"] == 1);
    REQUIRE(window["frames_partial"] == 1);
}

TEST_CASE("GpuTiming: unwrapping keeps intervals ordered across a wrap", "[common]") {
    Aggregator aggregator;
    aggregator.Configure(true, {}, Period, ValidBits);
    aggregator.Reset();
    const u64 max = (u64{1} << ValidBits) - 1;
    aggregator.NoteSubmit(1, true);
    aggregator.NoteSubmit(1, true);
    aggregator.NoteSubmit(2, true);
    aggregator.AddResult(1, max - Ticks(2.0), max - Ticks(1.0));
    aggregator.AddResult(1, Ticks(1.0), Ticks(2.0)); // after the wrap
    aggregator.AddResult(2, Ticks(5.0), Ticks(6.0));
    const json window = json::parse(aggregator.TakeWindowJson());
    REQUIRE(window["frames_complete"] == 1);
    const double frame_ms = window["frame_gpu"]["max_ms"].get<double>();
    REQUIRE(frame_ms > 1.99);
    REQUIRE(frame_ms < 2.01); // two disjoint 1 ms intervals, not a 2^48 tick span
}

TEST_CASE("GpuTiming: unsupported devices report a reason instead of zeros", "[common]") {
    Aggregator aggregator;
    aggregator.Configure(false, "timestampValidBits is 0 on the graphics queue", 0.0, 0);
    aggregator.NoteSubmit(1, false);
    const json window = json::parse(aggregator.TakeWindowJson());
    REQUIRE(window["available"] == false);
    REQUIRE(window["reason"] == "timestampValidBits is 0 on the graphics queue");
    REQUIRE_FALSE(window.contains("frame_gpu"));
}

TEST_CASE("GpuTiming: lost results are counted", "[common]") {
    Aggregator aggregator;
    aggregator.Configure(true, {}, Period, ValidBits);
    aggregator.Reset();
    aggregator.NoteSubmit(1, true);
    aggregator.NoteLost(1);
    aggregator.NoteUnavailable();
    aggregator.SetPending(3);
    const json window = json::parse(aggregator.TakeWindowJson());
    REQUIRE(window["dropped_lost"] == 1);
    REQUIRE(window["dropped_unavailable"] == 1);
    REQUIRE(window["pending"] == 3);
}

} // namespace Common::Diagnostics::GpuTiming
