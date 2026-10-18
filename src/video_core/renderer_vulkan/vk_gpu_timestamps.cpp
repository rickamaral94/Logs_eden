// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <array>
#include <chrono>
#include <string>

#include <fmt/format.h>

#include "common/session_diagnostics.h"
#include "video_core/renderer_vulkan/vk_gpu_timestamps.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace Diag = Common::Diagnostics;

namespace {

// Results of one slot: begin and end timestamps, each followed by its availability word.
struct SlotResults {
    u64 begin;
    u64 begin_available;
    u64 end;
    u64 end_available;
};

// Polls after the tick completed before a result is given up on as unavailable.
constexpr u32 MaxPolls = 64;

class CostTimer {
public:
    CostTimer() : start{std::chrono::steady_clock::now()} {}
    ~CostTimer() {
        Diag::GpuTiming::Global().AddCost(static_cast<u64>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() -
                                                                 start)
                .count()));
    }

private:
    std::chrono::steady_clock::time_point start;
};

} // Anonymous namespace

GpuTimestamps::GpuTimestamps(const Device& device_) : device{device_}, slot_capture(SlotCount) {
    const auto families = device.GetPhysical().GetQueueFamilyProperties();
    const u32 family = device.GetGraphicsFamily();
    valid_bits = family < families.size() ? families[family].timestampValidBits : 0;
    period_ns = static_cast<double>(device.GetTimestampPeriod());
    std::string reason;
    if (valid_bits == 0) {
        reason = fmt::format("timestampValidBits is 0 on graphics queue family {}", family);
    } else if (!(period_ns > 0.0)) {
        reason = "timestampPeriod is not positive";
    }
    supported = reason.empty();
    Diag::GpuTiming::Global().Configure(supported, reason, period_ns, valid_bits);
}

GpuTimestamps::~GpuTimestamps() = default;

bool GpuTimestamps::EnsurePool() {
    if (pool) {
        return true;
    }
    if (pool_failed) {
        return false;
    }
    try {
        pool = device.GetLogical().CreateQueryPool({
            .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queryType = VK_QUERY_TYPE_TIMESTAMP,
            .queryCount = SlotCount * 2,
            .pipelineStatistics = 0,
        });
    } catch (const vk::Exception& exception) {
        pool_failed = true;
        Diag::GpuTiming::Global().Configure(
            false, fmt::format("query pool creation failed: {}", exception.what()), period_ns,
            valid_bits);
        return false;
    }
    return true;
}

void GpuTimestamps::BeginSubmission(vk::CommandBuffer upload_cmdbuf) {
    current_slot.reset();
    if (!supported || !Diag::IsCapturing() || !Diag::GpuTimestampsEnabled() ||
        Diag::DeviceLost()) {
        return;
    }
    CostTimer cost;
    if (!EnsurePool()) {
        return;
    }
    const auto slot = ring.Acquire();
    if (!slot) {
        Diag::GpuTiming::Global().NoteRingFull();
        return;
    }
    // host_query_reset is not required: the reset is recorded before the writes, outside any
    // render pass, in a command buffer of the same submission.
    upload_cmdbuf.ResetQueryPool(*pool, *slot * 2, 2);
    upload_cmdbuf.WriteTimestamp(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, *pool, *slot * 2);
    current_slot = slot;
}

void GpuTimestamps::EndSubmission(vk::CommandBuffer cmdbuf, u64 tick) {
    const bool capturing = Diag::IsCapturing();
    const u64 frame = Diag::HostFrameId();
    if (!current_slot) {
        if (capturing) {
            Diag::GpuTiming::Global().NoteSubmit(frame, false);
        }
        return;
    }
    CostTimer cost;
    // A begin timestamp was recorded, so the end one is too, even if the capture just stopped:
    // the slot then only returns to the ring once this tick completes.
    cmdbuf.WriteTimestamp(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, *pool, *current_slot * 2 + 1);
    ring.Submit(*current_slot, tick, frame, next_submit_id++);
    slot_capture[*current_slot] = capturing ? Diag::CaptureSequence() : 0;
    if (capturing) {
        Diag::GpuTiming::Global().NoteSubmit(frame, true);
    }
    current_slot.reset();
}

void GpuTimestamps::Poll(u64 gpu_tick) {
    if (ring.PendingCount() == 0) {
        return;
    }
    CostTimer cost;
    auto& aggregator = Diag::GpuTiming::Global();
    if (Diag::DeviceLost()) {
        aggregator.NoteLost(ring.DropAllPending());
        aggregator.SetPending(0);
        return;
    }
    const u64 capture = Diag::IsCapturing() ? Diag::CaptureSequence() : 0;
    for (const auto& entry : ring.Completed(gpu_tick)) {
        SlotResults results{};
        const VkResult result = device.GetLogical().GetQueryResults(
            *pool, entry.slot * 2, 2, sizeof(results), &results, sizeof(u64) * 2,
            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (result == VK_ERROR_DEVICE_LOST) {
            aggregator.NoteLost(ring.DropAllPending());
            break;
        }
        const bool available = results.begin_available != 0 && results.end_available != 0;
        if (!available) {
            if (ring.Retry(entry.slot) >= MaxPolls) {
                ring.Release(entry.slot);
                aggregator.NoteUnavailable();
            }
            // Later slots cannot be ready either; keep the order.
            break;
        }
        // Results of an earlier capture are discarded instead of being attributed to this one.
        if (capture != 0 && slot_capture[entry.slot] == capture) {
            aggregator.AddResult(entry.frame_id, results.begin, results.end);
        }
        ring.Release(entry.slot);
    }
    aggregator.SetPending(ring.PendingCount());
}

} // namespace Vulkan
