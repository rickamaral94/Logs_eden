// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <deque>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>

#include "common/common_types.h"

// Per host frame records for correlating render pass splits with slow frames. A record holds the
// difference of capture totals that already exist (counters, timers, waits, render pass profile)
// between two consecutive host frame ends, so it adds no measurement of its own: nothing is
// waited on, submitted or queried for it. GPU time is joined later from the timestamp
// aggregator. Records wait in a bounded queue until the diagnostics sampler drains them.
namespace Common::Diagnostics::FrameLog {

/// Positions of the cumulative values read at every frame end; see ColumnNames().
namespace Index {
inline constexpr size_t GameFrames = 0;
inline constexpr size_t Draws = 1;
inline constexpr size_t IndexedDraws = 2;
inline constexpr size_t IndirectDraws = 3;
inline constexpr size_t Passes = 4;
inline constexpr size_t EmptyPasses = 5;
inline constexpr size_t Reopens = 6;
inline constexpr size_t EndReasons = 15; ///< RenderPasses::EndReasonCount
inline constexpr size_t EndedBy = 7;
inline constexpr size_t ReopenAfter = EndedBy + EndReasons;
inline constexpr size_t Submits = ReopenAfter + EndReasons;
inline constexpr size_t Dispatches = Submits + 1;
inline constexpr size_t UploadBytes = Submits + 2;
inline constexpr size_t DownloadBytes = Submits + 3;
inline constexpr size_t PipelinesCreated = Submits + 4;
inline constexpr size_t PipelineBuildWaitUs = Submits + 5;
inline constexpr size_t DrawsSkippedPipelinePending = Submits + 6;
inline constexpr size_t FenceWaitUs = Submits + 7;
inline constexpr size_t WaitReasons = 10; ///< Diagnostics::WaitReason::Count
inline constexpr size_t WaitByReasonUs = FenceWaitUs + 1;
inline constexpr size_t QueueSubmitUs = WaitByReasonUs + WaitReasons;
inline constexpr size_t FramePacingThrottleUs = QueueSubmitUs + 1;
inline constexpr size_t WaitForIdle = FramePacingThrottleUs + 1;
inline constexpr size_t WaitForIdleElided = WaitForIdle + 1;
inline constexpr size_t Count = WaitForIdleElided + 1;
} // namespace Index

inline constexpr size_t ValueCount = Index::Count;

/// Cumulative values of the capture at a frame end, in ColumnNames() order after the fixed
/// leading columns.
using Values = std::array<u64, ValueCount>;

enum class GpuState : u8 {
    Pending,  ///< Results may still arrive.
    Complete, ///< Every submission of the frame was measured.
    Partial,  ///< Some submissions were not measured; no GPU time.
    Missing,  ///< No result arrived in time or GPU time is off.
};

struct Record {
    u64 frame_id{};
    s64 end_mono_us{};
    u32 interval_us{};  ///< Since the previous frame end; 0 for the first frame of a capture.
    bool across_pause{}; ///< The interval spans a capture start, pause or resume.
    std::array<u32, ValueCount> deltas{};
    u32 gpu_us{};
    GpuState gpu_state{GpuState::Pending};
};

class Log {
public:
    static constexpr size_t Capacity = 2048;
    /// Frames to wait for GPU results before a record is drained without them.
    static constexpr u64 GpuWaitFrames = 16;
    static constexpr size_t MaxEarlyGpu = 64;

    void Reset();

    /// Records the end of frame_id. across_pause marks an interval that spans a discontinuity.
    void OnFrame(u64 frame_id, s64 end_mono_us, bool across_pause, const Values& totals);
    /// GPU time of a frame from the timestamp aggregator (complete=false: partially measured).
    void SetGpu(u64 frame_id, u32 us, bool complete);
    void AddCost(u64 ns);

    /// Records ready for export as {"columns":[...],"rows":[[...],...],...}. Records still
    /// waiting for GPU results stay queued unless all is set.
    [[nodiscard]] std::string Drain(u64 current_frame, bool all);
    [[nodiscard]] std::string SessionJson() const;

    [[nodiscard]] static std::span<const std::string_view> ColumnNames();

private:
    [[nodiscard]] bool Ready(const Record& record, u64 current_frame) const;

    mutable std::mutex mutex;
    std::deque<Record> queue;
    Values previous{};
    s64 previous_end_us{};
    u64 last_frame{};
    bool have_previous{};
    std::unordered_map<u64, std::pair<u32, bool>> early_gpu;

    u64 recorded{};
    u64 dropped{};
    u64 drained{};
    u64 gpu_complete{};
    u64 gpu_partial{};
    u64 gpu_missing{};
    u64 gpu_late{};
    std::atomic<u64> cost_ns{};
};

[[nodiscard]] Log& Global();

} // namespace Common::Diagnostics::FrameLog
