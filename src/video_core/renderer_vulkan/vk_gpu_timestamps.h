// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <optional>
#include <vector>

#include "common/common_types.h"
#include "common/diagnostics_gpu_timing.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;

/// Diagnostic GPU execution time of scheduler submissions. Only active during a capture with
/// timestamps enabled. It adds two timestamp writes and one query reset to command buffers the
/// scheduler records anyway, and reads results only for ticks the GPU has already reached: it
/// never waits, submits or adds synchronization of its own. All methods run on the scheduler
/// worker thread.
class GpuTimestamps {
public:
    static constexpr u32 SlotCount = 256;

    explicit GpuTimestamps(const Device& device);
    ~GpuTimestamps();

    GpuTimestamps(const GpuTimestamps&) = delete;
    GpuTimestamps& operator=(const GpuTimestamps&) = delete;

    /// Right after the upload command buffer began.
    void BeginSubmission(vk::CommandBuffer upload_cmdbuf);
    /// Before the main command buffer ends; tick is the value the submission signals.
    void EndSubmission(vk::CommandBuffer cmdbuf, u64 tick);
    /// After a submission: reads the results of slots whose tick the GPU has reached.
    void Poll(u64 gpu_tick);

private:
    bool EnsurePool();

    const Device& device;
    bool supported{};
    u32 valid_bits{};
    double period_ns{};
    bool pool_failed{};
    vk::QueryPool pool;
    Common::Diagnostics::GpuTiming::QueryRing ring{SlotCount};
    std::vector<u64> slot_capture; ///< capture sequence each pending slot belongs to
    std::optional<u32> current_slot;
    u64 next_submit_id{};
};

} // namespace Vulkan
