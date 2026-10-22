// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "common/common_types.h"

// Render pass profile for driver evaluation: how many passes the emulated workload opens, how
// many draws they hold, which attachments are loaded, cleared or discarded and why passes end.
// Tile-based drivers (Turnip in GMEM mode) pay a load and a store per attachment and pass, so
// these figures explain most of their per-frame overhead. Independent of Vulkan so the
// bookkeeping can be tested on the host.
namespace Common::Diagnostics::RenderPasses {

/// Why the scheduler closed a render pass. Operations that must run outside a render pass
/// pass their class to Scheduler::RequestOutsideRenderPassOperationContext; a call site that
/// does not is counted as Unknown.
enum class EndReason : u8 {
    NextTarget,        ///< Another framebuffer or render pass was requested.
    Submit,            ///< The command buffer was flushed or finished.
    Clear,             ///< A deferred clear was realized, or a buffer was cleared.
    Copy,              ///< Buffer or image copy, upload or reinterpretation on the GPU.
    Readback,          ///< Image download to host memory.
    Blit,              ///< Blit, scale or MSAA copy through a helper pass.
    Dispatch,          ///< Guest compute dispatch.
    EdenCompute,       ///< Eden helper compute (index conversion, ASTC decode, unswizzle).
    Query,             ///< Query reset, copy or resolve.
    Barrier,           ///< Explicit barrier, feedback loop or layout transition.
    TransformFeedback, ///< Transform feedback setup.
    Present,           ///< Composition, post-processing and presentation of a frame.
    Sync,              ///< Wait for idle.
    Other,             ///< Outside operation without a more specific class.
    Unknown,           ///< Closed without a declared reason.
    Count,
};

inline constexpr size_t EndReasonCount = static_cast<size_t>(EndReason::Count);

/// Cumulative counts of a capture, read at frame boundaries by the frame log.
struct Totals {
    u64 passes{};
    u64 empty{};
    u64 draws{};
    u64 same_target_reopen{};
    std::array<u64, EndReasonCount> ended_by{};
    std::array<u64, EndReasonCount> reopen_after{};
};

struct PassInfo {
    u64 render_pass;          ///< VkRenderPass handle (identifies formats and load/store ops).
    u64 target;               ///< VkFramebuffer handle.
    u32 width;
    u32 height;
    u32 samples;
    u32 color_attachments;
    bool depth_stencil;
    u32 color_clear_mask;
    bool depth_stencil_clear;
    u32 color_discard_mask;
    bool depth_stencil_discard;
    bool resolve;             ///< MSAA resolve attachments are written at the end of the pass.
};

/// Fed by the thread that records into the Vulkan scheduler (Begin, NoteDraw, End); windows
/// are taken by the diagnostics sampler.
class Profiler {
public:
    static constexpr size_t MaxShapes = 128;
    static constexpr size_t MaxWindowFrames = 2048;
    static constexpr size_t TopShapes = 24;
    static constexpr size_t DrawBuckets = 6; // 0, 1, 2-4, 5-16, 17-64, 65+
    static constexpr size_t FrameHistogramBuckets = 512; // passes per frame, one per count

    void Reset();

    /// Starts a pass during a capture. Returns true when the shape (render pass and extent) is
    /// new and should be described with Describe().
    bool Begin(const PassInfo& info, u64 frame_id);
    void Describe(const PassInfo& info, std::string description);
    void NoteDraw() noexcept {
        if (active) {
            ++current_draws;
        }
    }
    void End(EndReason reason);

    [[nodiscard]] std::string TakeWindowJson();
    [[nodiscard]] std::string SessionJson() const;
    [[nodiscard]] Totals SessionTotals() const;

    [[nodiscard]] static size_t DrawBucket(u64 draws) noexcept;

private:
    struct ShapeKey {
        u64 render_pass;
        u32 width;
        u32 height;
        bool operator==(const ShapeKey&) const noexcept = default;
    };
    struct ShapeKeyHash {
        size_t operator()(const ShapeKey& key) const noexcept;
    };
    struct Counts {
        u64 passes{};
        u64 empty{};
        u64 clear_only{};
        u64 draws{};
        u64 max_draws{};
        u64 pixels{};
        u64 sample_pixels{};
        u64 msaa{};
        u64 attachment_loads{};
        u64 attachment_clears{};
        u64 attachment_stores{};
        u64 attachment_discards{};
        u64 resolves{};
        u64 same_target_reopen{};
        std::array<u64, DrawBuckets> draw_buckets{};
        std::array<u64, EndReasonCount> ended_by{};
        std::array<u64, EndReasonCount> reopen_after{};
    };
    struct Shape {
        std::string description;
        u32 samples{};
        u64 passes{};
        u64 empty{};
        u64 draws{};
        u64 pixels{};
        u64 same_target_reopen{};
        std::array<u64, EndReasonCount> ended_by{};
    };
    struct Frame {
        u64 id{};
        u32 passes{};
        u32 empty{};
        u64 pixels{};
        bool active{};
    };

    static void Accumulate(Counts& counts, const PassInfo& info, u64 draws, EndReason reason,
                           bool reopened, EndReason previous_end);
    void FinishFrame();
    static std::string CountsJson(const Counts& counts, u64 frames);

    // Producer-only state (the recording thread).
    bool active{};
    PassInfo current{};
    u64 current_draws{};
    u64 current_frame{};
    u64 last_target{};
    EndReason last_end{EndReason::Unknown};
    bool have_last{};

    mutable std::mutex mutex;
    Counts window;
    Counts session;
    Frame frame;
    std::vector<u32> window_frame_passes;
    std::array<u64, FrameHistogramBuckets + 1> session_frame_passes{}; // last = overflow
    u64 window_frames{};
    u64 session_frames{};
    u64 window_frames_dropped{};
    u64 shapes_overflow_passes{};
    std::unordered_map<ShapeKey, Shape, ShapeKeyHash> shapes;
};

[[nodiscard]] std::string_view EndReasonName(EndReason reason) noexcept;

/// The process-wide profiler read by the diagnostics samples.
[[nodiscard]] Profiler& Global();

} // namespace Common::Diagnostics::RenderPasses
