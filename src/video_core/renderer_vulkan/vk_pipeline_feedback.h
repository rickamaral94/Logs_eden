// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string_view>

#include "common/session_diagnostics.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

/// Optional VkPipelineCreationFeedback for diagnostics. Chained only while a capture runs and
/// only on Vulkan 1.3 devices; the feedback is informational and does not change how the
/// pipeline is built.
class PipelineCreationFeedback {
public:
    /// Returns the pNext to use: the feedback struct in front of next, or next unchanged.
    [[nodiscard]] const void* Chain(const Device& device, const void* next) {
        active = Common::Diagnostics::IsCapturing() && device.SupportsPipelineCreationFeedback();
        if (!active) {
            return next;
        }
        info = VkPipelineCreationFeedbackCreateInfo{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_CREATION_FEEDBACK_CREATE_INFO,
            .pNext = next,
            .pPipelineCreationFeedback = &whole,
            .pipelineStageCreationFeedbackCount = 0,
            .pPipelineStageCreationFeedbacks = nullptr,
        };
        return &info;
    }

    /// Counts the result; returns "hit", "miss" or "unknown" (feedback missing or invalid).
    std::string_view Record() const {
        namespace Diag = Common::Diagnostics;
        if (!active || (whole.flags & VK_PIPELINE_CREATION_FEEDBACK_VALID_BIT) == 0) {
            return "unknown";
        }
        Diag::Increment(Diag::Counter::PipelineFeedbackValid);
        if ((whole.flags & VK_PIPELINE_CREATION_FEEDBACK_APPLICATION_PIPELINE_CACHE_HIT_BIT) !=
            0) {
            Diag::Increment(Diag::Counter::DriverPipelineCacheHits);
            return "hit";
        }
        return "miss";
    }

private:
    bool active{};
    VkPipelineCreationFeedback whole{};
    VkPipelineCreationFeedbackCreateInfo info{};
};

} // namespace Vulkan
