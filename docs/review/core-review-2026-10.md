# Eden core review (October 2026)

A pass over the code that emulates the Switch, judged against Eden's own code: bugs, repeated
work and places where the same thing is done two ways. Six areas were read. Each finding below
was checked against the code and its callers before being listed. Paths are under `src/`.

Fixed in this change. All of these are small and local; none changes a default other than the
two MSAA discard switches.

| Area | Finding | Fix |
| --- | --- | --- |
| Buffer cache | After every draw or dispatch that uploaded a buffer, `PostCopyBarrier` recorded a second barrier and ended the render pass, although mapped uploads already carry their own barriers (or run in the upload command buffer, which ends with one) | `any_buffer_uploaded` is only set for immediate (non-mapped) uploads, i.e. OpenGL; the dead Vulkan blocks are removed |
| Buffer cache | `UsageTracker` marked too few 64-byte blocks (rounded down, ignored the offset in the block; sizes under 64 shifted by 64, which is UB), so an upload could be reordered before a draw still reading the old data; `IsUsed` re-checked the whole first page when the range fit in one page, which blocked reordering needlessly | Exact block mask; early return in `IsUsed`; unit test `tests/video_core/usage_tracker.cpp` |
| Buffer cache | `BindGraphicsUniformBuffer` dereferenced an empty `optional` for unmapped addresses | `value_or(0)` (address 0 is the null buffer) |
| Buffer cache | `DMAClear` checked a word count as bytes | Size in bytes |
| Buffer cache | Inline index buffer erased without unregistering or delayed destruction; a garbage-collected one left a stale id | `DeleteBuffer`; the id is reset when that buffer is deleted |
| Device memory | 4-entry translation cache shared by the GPU and service threads without a lock | Per-thread cache invalidated by a generation counter on map/unmap |
| Core memory | `HandleRasterizerDownload` indexed 4-entry arrays with the host thread id of service threads (4 and up) | Extra slot for non-CPU threads, under its own lock |
| Core memory | `GetSpan` compared the page after the last byte | Last byte |
| NCE | Host SIGBUS jumped to a null handler (the previous action was never saved); SIGSEGV forwarding ignored `SA_SIGINFO`/`SIG_DFL` | Save the previous action; forward by its type, default action re-raises |
| Core timing | An event whose type was destroyed while queued made `Advance` spin forever holding both locks | Pop it |
| Host memory | Fallback path closed fd -1 and leaked the real one | Close, then clear |
| GPU front end | `synced` shared a bitfield byte with fields the GPU thread writes unlocked, while the fence thread wrote it under a mutex; with non-delayed fences it was never reset, so every later list signalled a fence (only with `sync_memory_operations`) | Plain bools; reset on every sync and on list end |
| DMA | Constant fill with 1- or 2-byte components wrote `v,0,v,0`, and without cache invalidation | Fill per component; write through |
| Texture cache | Feedback-loop result cached by serials that do not change when a draw binds other textures: needless barriers (render pass splits) or missed ones | Evaluated per draw; dead serials removed |
| Texture cache | Rebinding a preemptively downloaded render target in one fence window queued the same download again | Queued once |
| Texture cache | Subresource lookup accepted `layer == layers` | `>=` |
| Vulkan | MSAA attachments stored with `DONT_CARE` after a deferred clear, then reloaded when the pass is split | `ENABLE_MSAA_*_DISCARD = false` |
| Vulkan | `DrawTexture`'s blit did not invalidate scheduler state (next draw could keep the blit pipeline, descriptor set, viewport) and left the source in `SHADER_READ_ONLY_OPTIMAL` | `InvalidateState()`; barrier in `GENERAL` |
| Vulkan | Deferred clears of layer 0 cleared every layer of a layered framebuffer | Deferred only when the clear covers all layers |
| Vulkan | A deferred clear started query segments outside the render pass that ended them inside it | Segments only for non-deferred clears |
| Vulkan | `CurrentGraphicsPipeline()` (full key refresh and compare) ran 2-4 times per draw | Looked up once and passed down |
| Vulkan | Fence-path waiters were woken with `notify_one` though several threads can wait (Turnip uses this path) | `notify_all` |
| Vulkan | Descriptor update ring value-initialised (committed) about 96 MiB + 24 MiB at startup | Raw storage, entries constructed when written |
| Audio | A buffer was queued before its samples, so the sink could read short and drift | Samples first |
| Audio | Renderer list appended outside the lock the manager thread iterates under | Inside the lock |
| nvdrv | `IocCtrlEventWait` wrote `events[event_id]` with a guest id on every early return | Bounds check |
| FS | Two log lines on every save and save open | `LOG_DEBUG` |
| Diagnostics | Counter and timer calls were out-of-line on the draw path | Capture check inlined |

Not changed yet, with the reason:

| Area | Finding | Why not now |
| --- | --- | --- |
| HLE | 600 µs sleep after every `IStorage` read while a process has a video decoder open (`service.cpp`), i.e. every RomFS read for such games | Added for a specific game; needs a narrower trigger tested with it |
| VFS | One filesystem-wide lock held across each file read/write (`vfs_real.cpp`); several FS queries walk whole directory trees | Most likely contributor to the post-save hitches; needs a lifetime-safe redesign |
| Texture cache | Resolve shadow images keyed by MSAA image only: framebuffers with another render area can keep a destroyed view | Needs a keyed shadow cache; MSAA-specific testing |
| Texture cache | BCn CPU decode breaks mips smaller than 4 px of array/cube textures | Decoder rewrite with tests |
| Texture cache | `FindDMAImage` stops at the first match, so its "newest image" selection never runs (same in yuzu) | Changes which image DMA copies use |
| Texture cache | GC and `DownloadMemory` wait for the GPU once per image; framebuffer key includes an OpenGL-only field | Batching and key change need testing |
| Vulkan | Unconditional compute barrier before every dispatch | Can reuse the wait-for-idle hazard flag; under-barriering corrupts compute input |
| Vulkan | Rescaling push constants pushed on every draw | Small per-draw cost; cache per pipeline later |
| GPU front end | Every register write re-dirties state (deliberate since #3712); one queue submit per guest command list | Known regressions behind both; need per-game testing |
| GPU front end | Multi-word inline uploads split at GPFIFO boundaries; macro cache keeps rebased entries after a re-upload | Correct fix spans engines |
| GPU front end | GPU command queue reserves about 25 MB (4096 slots with 6 KB inline buffers) | Smaller inline buffers move allocations to the submit path |
| NCE | Spin locks without `wfe`/`yield`; instruction cache never invalidated for code loads; flags kept in two places after an SVC; permission faults on mapped pages retried forever | Assembly and fault-path changes need device testing |
| Core timing | NCE guest counter reads ignore `fast_cpu_time` / speed scaling that `svcGetSystemTick` applies | Behavioural change for every game |
| Audio | Manager and DSP threads ping-pong without pacing when no renderer is active | Needs an event to wait on |
| HID | Npad polled at 1 kHz | Setting with a lower Android default |
