// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <vector>

#include <nlohmann/json.hpp>

#include "common/diagnostics_frame_log.h"
#include "common/diagnostics_render_passes.h"
#include "common/session_diagnostics.h"

namespace Common::Diagnostics::FrameLog {

namespace {

using json = nlohmann::json;

static_assert(Index::EndReasons == RenderPasses::EndReasonCount);
static_assert(Index::WaitReasons == static_cast<size_t>(WaitReason::Count));

constexpr std::array<std::string_view, 4> LEADING{"frame", "end_mono_us", "interval_us",
                                                  "across_pause"};
constexpr std::array<std::string_view, 2> TRAILING{"gpu_us", "gpu_state"};
constexpr std::array<std::string_view, 4> GPU_STATES{"pending", "complete", "partial", "missing"};

struct Columns {
    std::vector<std::string> storage;
    std::vector<std::string_view> views;

    Columns() {
        storage.reserve(LEADING.size() + ValueCount + TRAILING.size());
        for (const auto name : LEADING) {
            storage.emplace_back(name);
        }
        std::array<std::string, ValueCount> values;
        values[Index::GameFrames] = "game_frames";
        values[Index::Draws] = "draws";
        values[Index::IndexedDraws] = "indexed_draws";
        values[Index::IndirectDraws] = "indirect_draws";
        values[Index::Passes] = "passes";
        values[Index::EmptyPasses] = "empty_passes";
        values[Index::Reopens] = "reopens";
        for (size_t i = 0; i < Index::EndReasons; ++i) {
            const auto name = RenderPasses::EndReasonName(static_cast<RenderPasses::EndReason>(i));
            values[Index::EndedBy + i] = "end_" + std::string{name};
            values[Index::ReopenAfter + i] = "reopen_after_" + std::string{name};
        }
        values[Index::Submits] = "submits";
        values[Index::Dispatches] = "dispatches";
        values[Index::UploadBytes] = "upload_bytes";
        values[Index::DownloadBytes] = "download_bytes";
        values[Index::PipelinesCreated] = "pipelines_created";
        values[Index::PipelineBuildWaitUs] = "pipeline_build_wait_us";
        values[Index::DrawsSkippedPipelinePending] = "draws_skipped_pipeline_pending";
        values[Index::FenceWaitUs] = "fence_wait_us";
        for (size_t i = 0; i < Index::WaitReasons; ++i) {
            values[Index::WaitByReasonUs + i] =
                "wait_" + std::string{WaitReasonName(static_cast<WaitReason>(i))} + "_us";
        }
        values[Index::QueueSubmitUs] = "queue_submit_us";
        values[Index::FramePacingThrottleUs] = "frame_pacing_throttle_us";
        for (auto& value : values) {
            storage.push_back(std::move(value));
        }
        for (const auto name : TRAILING) {
            storage.emplace_back(name);
        }
        views.assign(storage.begin(), storage.end());
    }
};

const Columns& GetColumns() {
    static const Columns columns;
    return columns;
}

u32 Saturate(u64 value) {
    return value > UINT32_MAX ? UINT32_MAX : static_cast<u32>(value);
}

} // namespace

std::span<const std::string_view> Log::ColumnNames() {
    return GetColumns().views;
}

void Log::Reset() {
    std::scoped_lock lock{mutex};
    queue.clear();
    previous = {};
    previous_end_us = 0;
    last_frame = 0;
    have_previous = false;
    early_gpu.clear();
    recorded = 0;
    dropped = 0;
    drained = 0;
    gpu_complete = 0;
    gpu_partial = 0;
    gpu_missing = 0;
    gpu_late = 0;
    cost_ns = 0;
}

void Log::OnFrame(u64 frame_id, s64 end_mono_us, bool across_pause, const Values& totals) {
    Record record{};
    record.frame_id = frame_id;
    record.end_mono_us = end_mono_us;
    std::scoped_lock lock{mutex};
    if (have_previous && end_mono_us >= previous_end_us) {
        record.interval_us = Saturate(static_cast<u64>(end_mono_us - previous_end_us));
    }
    // The first frame of a capture holds everything since the capture start, not one frame.
    record.across_pause = across_pause || !have_previous;
    for (size_t i = 0; i < ValueCount; ++i) {
        record.deltas[i] = Saturate(totals[i] >= previous[i] ? totals[i] - previous[i] : 0);
    }
    previous = totals;
    previous_end_us = end_mono_us;
    last_frame = frame_id;
    have_previous = true;
    ++recorded;

    if (const auto it = early_gpu.find(frame_id); it != early_gpu.end()) {
        record.gpu_us = it->second.first;
        record.gpu_state = it->second.second ? GpuState::Complete : GpuState::Partial;
        early_gpu.erase(it);
    }
    if (queue.size() >= Capacity) {
        // The sampler stalled: keep the oldest records so drained rows stay contiguous.
        ++dropped;
        return;
    }
    queue.push_back(record);
}

void Log::SetGpu(u64 frame_id, u32 us, bool complete) {
    std::scoped_lock lock{mutex};
    const auto it = std::find_if(queue.rbegin(), queue.rend(), [frame_id](const Record& r) {
        return r.frame_id == frame_id;
    });
    if (it != queue.rend()) {
        it->gpu_us = complete ? us : 0;
        it->gpu_state = complete ? GpuState::Complete : GpuState::Partial;
        return;
    }
    // Results normally arrive after the frame end; keep the few that arrive before it.
    const bool future = !have_previous || frame_id > last_frame;
    if (future && early_gpu.size() < MaxEarlyGpu) {
        early_gpu.emplace(frame_id, std::make_pair(complete ? us : 0, complete));
    } else {
        ++gpu_late;
    }
}

void Log::AddCost(u64 ns) {
    std::scoped_lock lock{mutex};
    cost_ns += ns;
}

bool Log::Ready(const Record& record, u64 current_frame) const {
    return record.gpu_state != GpuState::Pending || record.frame_id + GpuWaitFrames <= current_frame;
}

std::string Log::Drain(u64 current_frame, bool all) {
    json rows = json::array();
    {
        std::scoped_lock lock{mutex};
        while (!queue.empty() && (all || Ready(queue.front(), current_frame))) {
            Record& record = queue.front();
            if (record.gpu_state == GpuState::Pending) {
                record.gpu_state = GpuState::Missing;
            }
            switch (record.gpu_state) {
            case GpuState::Complete:
                ++gpu_complete;
                break;
            case GpuState::Partial:
                ++gpu_partial;
                break;
            default:
                ++gpu_missing;
                break;
            }
            json row = json::array();
            row.push_back(record.frame_id);
            row.push_back(record.end_mono_us);
            row.push_back(record.interval_us);
            row.push_back(record.across_pause ? 1 : 0);
            for (const u32 value : record.deltas) {
                row.push_back(value);
            }
            if (record.gpu_state == GpuState::Complete) {
                row.push_back(record.gpu_us);
            } else {
                row.push_back(-1);
            }
            row.push_back(static_cast<u32>(record.gpu_state));
            rows.push_back(std::move(row));
            queue.pop_front();
            ++drained;
        }
    }
    json columns = json::array();
    for (const auto name : ColumnNames()) {
        columns.push_back(name);
    }
    const size_t row_count = rows.size();
    return json{
        {"columns", std::move(columns)},
        {"gpu_states", GPU_STATES},
        {"row_count", row_count},
        {"rows", std::move(rows)},
    }
        .dump();
}

std::string Log::SessionJson() const {
    std::scoped_lock lock{mutex};
    const u64 judged = gpu_complete + gpu_partial + gpu_missing;
    return json{
        {"frames_recorded", recorded},
        {"frames_drained", drained},
        {"frames_queued", queue.size()},
        {"frames_dropped_queue_full", dropped},
        {"queue_capacity", Capacity},
        {"gpu_complete", gpu_complete},
        {"gpu_partial", gpu_partial},
        {"gpu_missing", gpu_missing},
        {"gpu_results_after_export", gpu_late},
        {"gpu_coverage_pct",
         judged != 0 ? 100.0 * static_cast<double>(gpu_complete) / static_cast<double>(judged)
                     : 0.0},
        {"instrumentation_cpu_us", cost_ns / 1000},
        {"instrumentation_cpu_us_per_frame",
         recorded != 0 ? static_cast<double>(cost_ns) / 1000.0 / static_cast<double>(recorded)
                       : 0.0},
    }
        .dump();
}

Log& Global() {
    static Log log;
    return log;
}

} // namespace Common::Diagnostics::FrameLog
