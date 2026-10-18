// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cmath>

#include <nlohmann/json.hpp>

#include "common/diagnostics_gpu_timing.h"

namespace Common::Diagnostics::GpuTiming {

namespace {

using json = nlohmann::json;

u32 ToUs(u64 ns) {
    const u64 us = ns / 1000;
    return us > UINT32_MAX ? UINT32_MAX : static_cast<u32>(us);
}

double Ms(u64 ns) {
    return static_cast<double>(ns) / 1'000'000.0;
}

u32 Percentile(const std::vector<u32>& sorted, double p) {
    if (sorted.empty()) {
        return 0;
    }
    const auto rank = static_cast<size_t>(std::ceil(p * static_cast<double>(sorted.size())));
    return sorted[std::clamp<size_t>(rank, 1, sorted.size()) - 1];
}

json DistributionJson(std::vector<u32> values_us) {
    json out{{"count", values_us.size()}};
    if (values_us.empty()) {
        return out;
    }
    std::sort(values_us.begin(), values_us.end());
    u64 total = 0;
    for (const u32 value : values_us) {
        total += value;
    }
    const auto ms = [](u32 us) { return static_cast<double>(us) / 1000.0; };
    out["mean_ms"] = static_cast<double>(total) / 1000.0 / static_cast<double>(values_us.size());
    out["p50_ms"] = ms(Percentile(values_us, 0.50));
    out["p90_ms"] = ms(Percentile(values_us, 0.90));
    out["p99_ms"] = ms(Percentile(values_us, 0.99));
    out["max_ms"] = ms(values_us.back());
    return out;
}

template <size_t N>
json HistogramJson(const std::array<u64, N>& histogram, u32 bucket_us, u32 max_us) {
    u64 count = 0;
    for (const u64 value : histogram) {
        count += value;
    }
    json out{{"count", count}};
    if (count == 0) {
        return out;
    }
    const auto percentile = [&](double p) {
        const auto target = static_cast<u64>(std::ceil(p * static_cast<double>(count)));
        u64 seen = 0;
        for (size_t bucket = 0; bucket < N; ++bucket) {
            seen += histogram[bucket];
            if (seen >= target) {
                if (bucket == N - 1) {
                    return static_cast<double>(max_us) / 1000.0;
                }
                return static_cast<double>((bucket + 1) * bucket_us) / 1000.0;
            }
        }
        return static_cast<double>(max_us) / 1000.0;
    };
    out["p50_ms"] = percentile(0.50);
    out["p90_ms"] = percentile(0.90);
    out["p99_ms"] = percentile(0.99);
    out["max_ms"] = static_cast<double>(max_us) / 1000.0;
    out["percentile_resolution_ms"] = static_cast<double>(bucket_us) / 1000.0;
    out["percentile_method"] = "upper bound of the histogram bucket";
    return out;
}

} // Anonymous namespace

QueryRing::QueryRing(u32 slot_count) : states(slot_count, SlotState::Free) {
    pending.reserve(slot_count);
}

std::optional<u32> QueryRing::Acquire() {
    const u32 count = SlotCount();
    for (u32 i = 0; i < count; ++i) {
        const u32 slot = (next_hint + i) % count;
        if (states[slot] == SlotState::Free) {
            states[slot] = SlotState::Recorded;
            next_hint = (slot + 1) % count;
            return slot;
        }
    }
    return std::nullopt;
}

void QueryRing::Submit(u32 slot, u64 tick, u64 frame_id, u64 submit_id) {
    states[slot] = SlotState::Pending;
    pending.push_back(Pending{slot, tick, frame_id, submit_id, 0});
}

void QueryRing::Abandon(u32 slot) {
    if (states[slot] == SlotState::Recorded) {
        states[slot] = SlotState::Free;
    }
}

std::vector<QueryRing::Pending> QueryRing::Completed(u64 gpu_tick) const {
    std::vector<Pending> out;
    for (const auto& entry : pending) {
        // Ticks complete in submission order; stop at the first one still running.
        if (entry.tick > gpu_tick) {
            break;
        }
        out.push_back(entry);
    }
    return out;
}

void QueryRing::Release(u32 slot) {
    const auto it = std::ranges::find(pending, slot, &Pending::slot);
    if (it != pending.end()) {
        pending.erase(it);
    }
    states[slot] = SlotState::Free;
}

u32 QueryRing::Retry(u32 slot) {
    const auto it = std::ranges::find(pending, slot, &Pending::slot);
    return it != pending.end() ? ++it->polls : 0;
}

u32 QueryRing::DropAllPending() {
    const auto dropped = static_cast<u32>(pending.size());
    for (const auto& entry : pending) {
        states[entry.slot] = SlotState::Free;
    }
    pending.clear();
    return dropped;
}

void Aggregator::Configure(bool supported_, std::string unavailable_reason, double period_ns_,
                           u32 valid_bits_) {
    std::scoped_lock lock{mutex};
    supported = supported_;
    reason = std::move(unavailable_reason);
    period_ns = period_ns_ > 0.0 ? period_ns_ : 1.0;
    valid_bits = std::clamp<u32>(valid_bits_, 1, 64);
}

void Aggregator::Reset() {
    std::scoped_lock lock{mutex};
    have_raw = false;
    epoch = 0;
    last_raw = 0;
    frame_results = FrameAccum{};
    submit_counts.clear();
    window = Counts{};
    session = Counts{};
    window_submit_us.clear();
    window_frame_us.clear();
    window_intervals.clear();
    frame_histogram.fill(0);
    submit_histogram.fill(0);
    frame_max_us = 0;
    pending = 0;
}

u64 Aggregator::DeltaNs(u64 begin, u64 end, u32 bits, double period) {
    const u64 mask = bits >= 64 ? ~u64{0} : (u64{1} << bits) - 1;
    const u64 ticks = ((end & mask) - (begin & mask)) & mask;
    return static_cast<u64>(static_cast<double>(ticks) * period);
}

u64 Aggregator::Unwrap(u64 raw) {
    // Results arrive in submission order, so a large backwards step is a counter wrap.
    if (valid_bits >= 64) {
        return raw;
    }
    const u64 range = u64{1} << valid_bits;
    raw &= range - 1;
    if (have_raw && raw < last_raw && last_raw - raw > range / 2) {
        epoch += range;
    }
    have_raw = true;
    last_raw = raw;
    return epoch + raw;
}

void Aggregator::NoteSubmit(u64 frame_id, bool measured) {
    std::scoped_lock lock{mutex};
    ++window.submits;
    ++session.submits;
    if (measured) {
        ++window.measured;
        ++session.measured;
    }
    if (!submit_counts.empty() && submit_counts.back().first == frame_id) {
        ++submit_counts.back().second;
    } else {
        submit_counts.emplace_back(frame_id, 1);
        // Bounded: frames whose results never arrive are dropped from the front.
        while (submit_counts.size() > MaxWindowFrames) {
            submit_counts.pop_front();
            ++window.frames_partial;
            ++session.frames_partial;
        }
    }
}

void Aggregator::NoteRingFull() {
    std::scoped_lock lock{mutex};
    ++window.ring_full;
    ++session.ring_full;
}

void Aggregator::NoteUnavailable() {
    std::scoped_lock lock{mutex};
    ++window.unavailable;
    ++session.unavailable;
}

void Aggregator::NoteLost(u32 count) {
    std::scoped_lock lock{mutex};
    window.lost += count;
    session.lost += count;
}

void Aggregator::SetPending(u32 count) {
    std::scoped_lock lock{mutex};
    pending = count;
}

void Aggregator::AddCost(u64 ns) {
    std::scoped_lock lock{mutex};
    window.cost_ns += ns;
    session.cost_ns += ns;
}

void Aggregator::AddResult(u64 frame_id, u64 begin_raw, u64 end_raw) {
    std::scoped_lock lock{mutex};
    const u64 duration = DeltaNs(begin_raw, end_raw, valid_bits, period_ns);
    const u64 begin_ns = static_cast<u64>(static_cast<double>(Unwrap(begin_raw)) * period_ns);
    const Interval interval{begin_ns, begin_ns + duration};

    for (Counts* counts : {&window, &session}) {
        ++counts->results;
        counts->sum_ns += duration;
        counts->max_ns = (std::max)(counts->max_ns, duration);
    }
    if (window_submit_us.size() < MaxWindowSubmits) {
        window_submit_us.push_back(ToUs(duration));
        window_intervals.push_back(interval);
    }
    submit_histogram[(std::min)(static_cast<size_t>(ToUs(duration) / HistogramBucketUs),
                                HistogramBuckets)]++;

    if (frame_results.active && frame_results.frame_id != frame_id) {
        FinishFrame();
    }
    if (!frame_results.active) {
        frame_results = FrameAccum{};
        frame_results.frame_id = frame_id;
        frame_results.active = true;
    }
    ++frame_results.results;
    frame_results.intervals.push_back(interval);
}

void Aggregator::FinishFrame() {
    const u64 id = frame_results.frame_id;
    u32 submits = 0;
    while (!submit_counts.empty() && submit_counts.front().first <= id) {
        const auto [frame, count] = submit_counts.front();
        submit_counts.pop_front();
        if (frame == id) {
            submits = count;
        } else {
            // Submitted frames without any result (every submission unmeasured or lost).
            ++window.frames_partial;
            ++session.frames_partial;
        }
    }
    const bool complete = submits != 0 && frame_results.results == submits;
    if (complete) {
        const u64 busy = UnionNs(std::move(frame_results.intervals));
        const u32 us = ToUs(busy);
        ++window.frames_complete;
        ++session.frames_complete;
        if (window_frame_us.size() < MaxWindowFrames) {
            window_frame_us.push_back(us);
        }
        frame_histogram[(std::min)(static_cast<size_t>(us / HistogramBucketUs),
                                   HistogramBuckets)]++;
        frame_max_us = (std::max)(frame_max_us, us);
    } else {
        ++window.frames_partial;
        ++session.frames_partial;
    }
    frame_results = FrameAccum{};
}

u64 Aggregator::UnionNs(std::vector<Interval> intervals) {
    if (intervals.empty()) {
        return 0;
    }
    std::sort(intervals.begin(), intervals.end(),
              [](const Interval& a, const Interval& b) { return a.begin_ns < b.begin_ns; });
    u64 total = 0;
    u64 start = intervals.front().begin_ns;
    u64 end = intervals.front().end_ns;
    for (const auto& interval : intervals) {
        if (interval.begin_ns > end) {
            total += end - start;
            start = interval.begin_ns;
            end = interval.end_ns;
        } else {
            end = (std::max)(end, interval.end_ns);
        }
    }
    return total + (end - start);
}

std::string Aggregator::TakeWindowJson() {
    std::scoped_lock lock{mutex};
    json out{
        {"available", supported},
        {"source", "vkCmdWriteTimestamp: TOP_OF_PIPE at the start and BOTTOM_OF_PIPE at the end "
                   "of each scheduler submission (upload + main command buffer)"},
    };
    if (!supported) {
        out["reason"] = reason;
        return out.dump();
    }
    window.busy_ns = UnionNs(std::move(window_intervals));
    out["submits"] = window.submits;
    out["submits_measured"] = window.measured;
    out["results"] = window.results;
    out["coverage_pct"] = window.submits != 0 ? 100.0 * static_cast<double>(window.results) /
                                                    static_cast<double>(window.submits)
                                              : 0.0;
    out["pending"] = pending;
    out["dropped_ring_full"] = window.ring_full;
    out["dropped_unavailable"] = window.unavailable;
    out["dropped_lost"] = window.lost;
    out["submit_sum_ms"] = Ms(window.sum_ns);
    out["submit_busy_union_ms"] = Ms(window.busy_ns);
    out["submit"] = DistributionJson(std::move(window_submit_us));
    out["frames_complete"] = window.frames_complete;
    out["frames_partial"] = window.frames_partial;
    out["frame_gpu"] = DistributionJson(std::move(window_frame_us));
    out["instrumentation_cpu_ms"] = Ms(window.cost_ns);
    window = Counts{};
    window_submit_us = {};
    window_frame_us = {};
    window_intervals = {};
    return out.dump();
}

std::string Aggregator::SessionJson() const {
    std::scoped_lock lock{mutex};
    json out{{"available", supported}};
    if (!supported) {
        out["reason"] = reason;
        return out.dump();
    }
    out["timestamp_period_ns"] = period_ns;
    out["timestamp_valid_bits"] = valid_bits;
    out["submits"] = session.submits;
    out["submits_measured"] = session.measured;
    out["results"] = session.results;
    out["coverage_pct"] = session.submits != 0 ? 100.0 * static_cast<double>(session.results) /
                                                     static_cast<double>(session.submits)
                                               : 0.0;
    out["dropped_ring_full"] = session.ring_full;
    out["dropped_unavailable"] = session.unavailable;
    out["dropped_lost"] = session.lost;
    out["submit_sum_ms"] = Ms(session.sum_ns);
    out["submit_max_ms"] = Ms(session.max_ns);
    out["submit"] = HistogramJson(submit_histogram, HistogramBucketUs, ToUs(session.max_ns));
    out["frames_complete"] = session.frames_complete;
    out["frames_partial"] = session.frames_partial;
    out["frame_gpu"] = HistogramJson(frame_histogram, HistogramBucketUs, frame_max_us);
    out["instrumentation_cpu_ms"] = Ms(session.cost_ns);
    return out.dump();
}

Aggregator& Global() {
    static Aggregator aggregator;
    return aggregator;
}

} // namespace Common::Diagnostics::GpuTiming
