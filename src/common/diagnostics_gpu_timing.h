// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <deque>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "common/common_types.h"

// GPU execution time from Vulkan timestamp queries, independent of Vulkan so the bookkeeping can
// be tested on the host. The renderer writes one timestamp at the start and one at the end of
// each scheduler submission; this code owns the slot ring and turns completed pairs into
// per-submit, per-window and per-frame figures.
namespace Common::Diagnostics::GpuTiming {

/// Fixed ring of query slots (two queries each). A slot is only reused after its submission
/// completed and its results were read or given up on, so queries in flight are never reset.
class QueryRing {
public:
    struct Pending {
        u32 slot;
        u64 tick;
        u64 frame_id;
        u64 submit_id;
        u32 polls;
    };

    explicit QueryRing(u32 slot_count);

    [[nodiscard]] u32 SlotCount() const noexcept {
        return static_cast<u32>(states.size());
    }

    /// Returns a free slot, or nothing when every slot is recorded or in flight.
    [[nodiscard]] std::optional<u32> Acquire();
    /// The slot's timestamps were recorded into a command buffer submitted with this tick.
    void Submit(u32 slot, u64 tick, u64 frame_id, u64 submit_id);
    /// The slot was acquired but its command buffer will not be submitted with timestamps.
    void Abandon(u32 slot);
    /// Pending slots whose tick the GPU has reached, oldest first. They stay pending until
    /// Release() or Retry().
    [[nodiscard]] std::vector<Pending> Completed(u64 gpu_tick) const;
    void Release(u32 slot);
    /// Results were not available yet although the tick completed; returns the poll count.
    u32 Retry(u32 slot);
    /// Drops every pending slot (device loss, shutdown); returns how many were pending.
    u32 DropAllPending();

    [[nodiscard]] u32 PendingCount() const noexcept {
        return static_cast<u32>(pending.size());
    }

private:
    enum class SlotState : u8 { Free, Recorded, Pending };
    std::vector<SlotState> states;
    std::vector<Pending> pending; // in submission order
    u32 next_hint{};
};

/// Converts raw timestamps (timestampValidBits wide, timestampPeriod ns per tick) and
/// aggregates them. Thread safe: results come from the Vulkan worker thread, windows are taken
/// by the diagnostics sampler.
class Aggregator {
public:
    static constexpr size_t MaxWindowSubmits = 8192;
    static constexpr size_t MaxWindowFrames = 2048;
    static constexpr u32 HistogramBucketUs = 250;
    static constexpr size_t HistogramBuckets = 400; // 0 - 100 ms, plus overflow

    void Configure(bool supported, std::string unavailable_reason, double period_ns,
                   u32 valid_bits);
    void Reset();

    /// Every scheduler submission during a capture, measured or not (coverage denominator).
    void NoteSubmit(u64 frame_id, bool measured);
    void NoteRingFull();
    void NoteUnavailable(); ///< results never became available
    void NoteLost(u32 count); ///< pending results dropped (device loss, shutdown)
    void AddResult(u64 frame_id, u64 begin_raw, u64 end_raw);
    void SetPending(u32 count);
    void AddCost(u64 ns); ///< CPU time spent recording and reading queries

    /// Masked difference of two raw timestamps in nanoseconds (handles wraparound).
    [[nodiscard]] static u64 DeltaNs(u64 begin, u64 end, u32 valid_bits, double period_ns);

    [[nodiscard]] std::string TakeWindowJson();
    [[nodiscard]] std::string SessionJson() const;

private:
    struct Interval {
        u64 begin_ns;
        u64 end_ns;
    };
    struct FrameAccum {
        u64 frame_id{};
        u32 results{};
        std::vector<Interval> intervals;
        bool active{};
    };
    struct Counts {
        u64 submits{};
        u64 measured{};
        u64 ring_full{};
        u64 unavailable{};
        u64 lost{};
        u64 results{};
        u64 busy_ns{};
        u64 sum_ns{};
        u64 max_ns{};
        u64 frames_complete{};
        u64 frames_partial{};
        u64 cost_ns{};
    };

    [[nodiscard]] u64 Unwrap(u64 raw);
    void FinishFrame();
    static u64 UnionNs(std::vector<Interval> intervals);

    mutable std::mutex mutex;
    bool supported{};
    std::string reason;
    double period_ns{1.0};
    u32 valid_bits{64};
    u64 last_raw{};
    u64 epoch{};
    bool have_raw{};

    // Submissions are noted at submit time; their results arrive later in the same order.
    FrameAccum frame_results;
    std::deque<std::pair<u64, u32>> submit_counts; // frame id -> submissions, oldest first

    Counts window;
    Counts session;
    std::vector<u32> window_submit_us;
    std::vector<u32> window_frame_us;
    std::vector<Interval> window_intervals;
    std::array<u64, HistogramBuckets + 1> frame_histogram{};
    std::array<u64, HistogramBuckets + 1> submit_histogram{};
    u32 frame_max_us{};
    u32 pending{};
};

/// The process-wide aggregator read by the diagnostics samples.
[[nodiscard]] Aggregator& Global();

} // namespace Common::Diagnostics::GpuTiming
