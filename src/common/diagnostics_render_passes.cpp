// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <bit>
#include <cmath>

#include <nlohmann/json.hpp>

#include "common/diagnostics_render_passes.h"

namespace Common::Diagnostics::RenderPasses {

namespace {

using json = nlohmann::json;

constexpr std::array<std::string_view, EndReasonCount> END_NAMES{
    "next_target", "submit",  "clear",   "copy",  "readback",
    "blit",        "dispatch", "eden_compute", "query", "barrier",
    "transform_feedback", "present", "sync", "other", "unknown",
};
constexpr std::array<std::string_view, Profiler::DrawBuckets> DRAW_BUCKET_NAMES{
    "0", "1", "2_4", "5_16", "17_64", "65_plus",
};

template <size_t N>
json ByReason(const std::array<u64, N>& values) {
    json out = json::object();
    for (size_t i = 0; i < N; ++i) {
        out[std::string{END_NAMES[i]}] = values[i];
    }
    return out;
}

u32 Percentile(const std::vector<u32>& sorted, double p) {
    if (sorted.empty()) {
        return 0;
    }
    const auto rank = static_cast<size_t>(std::ceil(p * static_cast<double>(sorted.size())));
    return sorted[std::clamp<size_t>(rank, 1, sorted.size()) - 1];
}

double Ratio(u64 numerator, u64 denominator) {
    return denominator == 0 ? 0.0
                            : static_cast<double>(numerator) / static_cast<double>(denominator);
}

} // namespace

std::string_view EndReasonName(EndReason reason) noexcept {
    const auto index = static_cast<size_t>(reason);
    return index < END_NAMES.size() ? END_NAMES[index] : "unknown";
}

size_t Profiler::DrawBucket(u64 draws) noexcept {
    if (draws <= 1) {
        return static_cast<size_t>(draws);
    }
    if (draws <= 4) {
        return 2;
    }
    if (draws <= 16) {
        return 3;
    }
    return draws <= 64 ? 4 : 5;
}

size_t Profiler::ShapeKeyHash::operator()(const ShapeKey& key) const noexcept {
    return std::hash<u64>{}(key.render_pass ^ (static_cast<u64>(key.width) << 32 | key.height) *
                                                  0x9E3779B97F4A7C15ULL);
}

void Profiler::Reset() {
    std::scoped_lock lock{mutex};
    window = {};
    session = {};
    frame = {};
    window_frame_passes.clear();
    session_frame_passes = {};
    window_frames = 0;
    session_frames = 0;
    window_frames_dropped = 0;
    shapes_overflow_passes = 0;
    shapes.clear();
}

bool Profiler::Begin(const PassInfo& info, u64 frame_id) {
    if (active) {
        End(EndReason::Unknown);
    }
    active = true;
    current = info;
    current_draws = 0;
    current_frame = frame_id;

    std::scoped_lock lock{mutex};
    const ShapeKey key{info.render_pass, info.width, info.height};
    if (shapes.contains(key)) {
        return false;
    }
    if (shapes.size() >= MaxShapes) {
        return false;
    }
    shapes[key].samples = info.samples;
    return true;
}

void Profiler::Describe(const PassInfo& info, std::string description) {
    std::scoped_lock lock{mutex};
    const auto it = shapes.find(ShapeKey{info.render_pass, info.width, info.height});
    if (it != shapes.end()) {
        it->second.description = std::move(description);
    }
}

void Profiler::Accumulate(Counts& counts, const PassInfo& info, u64 draws, EndReason reason,
                          bool reopened, EndReason previous_end) {
    const u64 pixels = static_cast<u64>(info.width) * info.height;
    const u32 attachments = info.color_attachments + (info.depth_stencil ? 1 : 0);
    const u32 clears = static_cast<u32>(std::popcount(info.color_clear_mask)) +
                       (info.depth_stencil && info.depth_stencil_clear ? 1 : 0);
    const u32 discards = static_cast<u32>(std::popcount(info.color_discard_mask)) +
                         (info.depth_stencil && info.depth_stencil_discard ? 1 : 0);
    ++counts.passes;
    counts.draws += draws;
    counts.max_draws = std::max(counts.max_draws, draws);
    if (draws == 0) {
        ++counts.empty;
        if (clears != 0) {
            ++counts.clear_only;
        }
    }
    ++counts.draw_buckets[DrawBucket(draws)];
    counts.pixels += pixels;
    counts.sample_pixels += pixels * std::max<u32>(info.samples, 1);
    if (info.samples > 1) {
        ++counts.msaa;
    }
    counts.attachment_clears += clears;
    counts.attachment_loads += attachments - std::min(clears, attachments);
    counts.attachment_discards += discards;
    counts.attachment_stores += attachments - std::min(discards, attachments);
    if (info.resolve) {
        ++counts.resolves;
    }
    ++counts.ended_by[static_cast<size_t>(reason)];
    if (reopened) {
        ++counts.same_target_reopen;
        ++counts.reopen_after[static_cast<size_t>(previous_end)];
    }
}

void Profiler::End(EndReason reason) {
    if (!active) {
        return;
    }
    active = false;
    const bool reopened = have_last && last_target == current.target;
    const EndReason previous_end = last_end;
    have_last = true;
    last_target = current.target;
    last_end = reason;

    std::scoped_lock lock{mutex};
    Accumulate(window, current, current_draws, reason, reopened, previous_end);
    Accumulate(session, current, current_draws, reason, reopened, previous_end);

    const ShapeKey key{current.render_pass, current.width, current.height};
    if (const auto it = shapes.find(key); it != shapes.end()) {
        Shape& shape = it->second;
        ++shape.passes;
        shape.draws += current_draws;
        shape.pixels += static_cast<u64>(current.width) * current.height;
        shape.empty += current_draws == 0 ? 1 : 0;
        shape.same_target_reopen += reopened ? 1 : 0;
        ++shape.ended_by[static_cast<size_t>(reason)];
    } else {
        ++shapes_overflow_passes;
    }

    if (frame.active && frame.id != current_frame) {
        FinishFrame();
    }
    if (!frame.active) {
        frame = {};
        frame.id = current_frame;
        frame.active = true;
    }
    ++frame.passes;
    frame.empty += current_draws == 0 ? 1 : 0;
    frame.pixels += static_cast<u64>(current.width) * current.height;
}

void Profiler::FinishFrame() {
    if (!frame.active) {
        return;
    }
    ++window_frames;
    ++session_frames;
    if (window_frame_passes.size() < MaxWindowFrames) {
        window_frame_passes.push_back(frame.passes);
    } else {
        ++window_frames_dropped;
    }
    ++session_frame_passes[std::min<size_t>(frame.passes, FrameHistogramBuckets)];
    frame = {};
}

std::string Profiler::CountsJson(const Counts& counts, u64 frames) {
    json buckets = json::object();
    for (size_t i = 0; i < DrawBuckets; ++i) {
        buckets[std::string{DRAW_BUCKET_NAMES[i]}] = counts.draw_buckets[i];
    }
    json out{
        {"passes", counts.passes},
        {"empty_passes", counts.empty},
        {"clear_only_passes", counts.clear_only},
        {"draws_in_passes", counts.draws},
        {"draws_per_pass_avg", Ratio(counts.draws, counts.passes)},
        {"draws_per_pass_max", counts.max_draws},
        {"draws_per_pass_histogram", std::move(buckets)},
        {"msaa_passes", counts.msaa},
        {"resolve_passes", counts.resolves},
        {"pixels", counts.pixels},
        {"sample_pixels", counts.sample_pixels},
        {"attachment_loads", counts.attachment_loads},
        {"attachment_clears", counts.attachment_clears},
        {"attachment_stores", counts.attachment_stores},
        {"attachment_discards", counts.attachment_discards},
        {"same_target_reopen", counts.same_target_reopen},
        {"same_target_reopen_after", ByReason(counts.reopen_after)},
        {"ended_by", ByReason(counts.ended_by)},
        {"frames", frames},
    };
    if (frames != 0) {
        out["passes_per_frame_avg"] = Ratio(counts.passes, frames);
        out["pixels_per_frame_avg"] = Ratio(counts.pixels, frames);
    }
    return out.dump();
}

std::string Profiler::TakeWindowJson() {
    std::scoped_lock lock{mutex};
    json out = json::parse(CountsJson(window, window_frames));
    std::vector<u32> passes = std::move(window_frame_passes);
    std::sort(passes.begin(), passes.end());
    if (!passes.empty()) {
        out["passes_per_frame"] = json{
            {"count", passes.size()},
            {"p50", Percentile(passes, 0.50)},
            {"p90", Percentile(passes, 0.90)},
            {"max", passes.back()},
        };
    }
    if (window_frames_dropped != 0) {
        out["frames_dropped"] = window_frames_dropped;
    }
    window = {};
    window_frame_passes = {};
    window_frames = 0;
    window_frames_dropped = 0;
    return out.dump();
}

std::string Profiler::SessionJson() const {
    std::scoped_lock lock{mutex};
    json out = json::parse(CountsJson(session, session_frames));
    if (session_frames != 0) {
        const auto at = [this](double p) {
            const auto rank = static_cast<u64>(std::ceil(p * static_cast<double>(session_frames)));
            u64 seen = 0;
            for (size_t i = 0; i < session_frame_passes.size(); ++i) {
                seen += session_frame_passes[i];
                if (seen >= std::max<u64>(rank, 1)) {
                    return i;
                }
            }
            return FrameHistogramBuckets;
        };
        size_t max = 0;
        for (size_t i = 0; i < session_frame_passes.size(); ++i) {
            if (session_frame_passes[i] != 0) {
                max = i;
            }
        }
        out["passes_per_frame"] = json{
            {"count", session_frames}, {"p50", at(0.50)}, {"p90", at(0.90)},
            {"p99", at(0.99)},         {"max", max},      {"max_is_lower_bound",
                                                           max == FrameHistogramBuckets},
        };
    }

    std::vector<std::pair<const ShapeKey*, const Shape*>> sorted;
    sorted.reserve(shapes.size());
    for (const auto& [key, shape] : shapes) {
        if (shape.passes != 0) {
            sorted.emplace_back(&key, &shape);
        }
    }
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) {
        return a.second->passes != b.second->passes ? a.second->passes > b.second->passes
                                                    : a.second->pixels > b.second->pixels;
    });
    json top = json::array();
    for (size_t i = 0; i < sorted.size() && i < TopShapes; ++i) {
        const auto& [key, shape] = sorted[i];
        top.push_back(json{
            {"width", key->width},
            {"height", key->height},
            {"samples", shape->samples},
            {"description", shape->description},
            {"passes", shape->passes},
            {"share_of_passes", Ratio(shape->passes, session.passes)},
            {"empty_passes", shape->empty},
            {"draws", shape->draws},
            {"draws_per_pass_avg", Ratio(shape->draws, shape->passes)},
            {"pixels", shape->pixels},
            {"same_target_reopen", shape->same_target_reopen},
            {"ended_by", ByReason(shape->ended_by)},
        });
    }
    out["shapes"] = json{
        {"distinct", shapes.size()},
        {"tracked_limit", MaxShapes},
        {"untracked_passes", shapes_overflow_passes},
        {"top", std::move(top)},
    };
    return out.dump();
}

Totals Profiler::SessionTotals() const {
    std::scoped_lock lock{mutex};
    return Totals{
        .passes = session.passes,
        .empty = session.empty,
        .draws = session.draws,
        .same_target_reopen = session.same_target_reopen,
        .ended_by = session.ended_by,
        .reopen_after = session.reopen_after,
    };
}

Profiler& Global() {
    static Profiler profiler;
    return profiler;
}

} // namespace Common::Diagnostics::RenderPasses
