// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <memory>

#include "common/common_types.h"
#include "core/frontend/graphics_context.h"

struct ANativeWindow;

namespace Core::Frontend {
class EmuWindow;
}

// Desktop OpenGL 4.6 on Android through Mesa's Zink (OpenGL over the Vulkan driver the emulator
// loaded). Android has no window system Mesa can present to from an app, so contexts use the
// surfaceless platform: the presenting context renders into a pbuffer the size of the window
// and copies each frame to the ANativeWindow, one frame late through pixel pack buffers.
namespace AndroidGL {

/// Loads Mesa and initializes the display once per process. Returns false when Mesa or a
/// Vulkan driver it can use is missing; the reason is logged.
bool Initialize(const std::shared_ptr<Common::DynamicLibrary>& vulkan_library);

class Context final : public Core::Frontend::GraphicsContext {
public:
    /// presenter: the context that composites frames to the window. Other contexts share its
    /// objects and run without a surface.
    Context(const Core::Frontend::EmuWindow& window, void* share_context, bool presenter,
            std::shared_ptr<Common::DynamicLibrary> vulkan_library);
    ~Context() override;

    void SwapBuffers() override;
    void MakeCurrent() override;
    void DoneCurrent() override;

    std::shared_ptr<Common::DynamicLibrary> GetDriverLibrary() override {
        return vulkan_library;
    }

    [[nodiscard]] void* Handle() const {
        return context;
    }

private:
    void ResizeSurface(u32 width, u32 height);
    void ReleaseReadback();
    void BindWindow();
    void PresentPending();

    const Core::Frontend::EmuWindow& window;
    std::shared_ptr<Common::DynamicLibrary> vulkan_library;
    void* context{};
    void* surface{};
    bool presenter{};
    bool is_current{};

    u32 surface_width{};
    u32 surface_height{};
    ANativeWindow* bound_window{};

    std::array<u32, 2> pack_buffers{};
    std::array<void*, 2> pack_fences{};
    u32 pack_width{};
    u32 pack_height{};
    size_t pack_index{};
};

} // namespace AndroidGL
