// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>

#include <android/native_window.h>
#include <dlfcn.h>
#include <fmt/format.h>
#include <glad/glad.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "common/dynamic_library.h"
#include "common/logging.h"
#include "core/frontend/emu_window.h"
#include "jni/emu_window/gl_context.h"
#include "video_core/renderer_opengl/gl_es_compat.h"

namespace AndroidGL {

namespace {

// Mesa's EGL, kept apart from the system libEGL.so already loaded in the process.
struct Egl {
    PFNEGLGETPROCADDRESSPROC GetProcAddress{};
    PFNEGLGETPLATFORMDISPLAYEXTPROC GetPlatformDisplayEXT{};
    PFNEGLINITIALIZEPROC Initialize{};
    PFNEGLBINDAPIPROC BindAPI{};
    PFNEGLCHOOSECONFIGPROC ChooseConfig{};
    PFNEGLCREATECONTEXTPROC CreateContext{};
    PFNEGLDESTROYCONTEXTPROC DestroyContext{};
    PFNEGLCREATEPBUFFERSURFACEPROC CreatePbufferSurface{};
    PFNEGLDESTROYSURFACEPROC DestroySurface{};
    PFNEGLMAKECURRENTPROC MakeCurrent{};
    PFNEGLGETERRORPROC GetError{};
    PFNEGLQUERYSTRINGPROC QueryString{};

    EGLDisplay display{EGL_NO_DISPLAY};
    EGLConfig config{};
    bool ready{};
};

Egl egl;
std::once_flag init_flag;

template <typename T>
bool Load(void* lib, const char* name, T& out) {
    out = reinterpret_cast<T>(dlsym(lib, name));
    if (!out) {
        LOG_ERROR(Render_OpenGL, "Mesa EGL lacks {}", name);
    }
    return out != nullptr;
}

void* GetGLProcAddress(const char* name) {
    return reinterpret_cast<void*>(egl.GetProcAddress(name));
}

bool InitializeOnce(const std::shared_ptr<Common::DynamicLibrary>& vulkan_library) {
    // Zink takes the Vulkan entry points of the driver the emulator loaded (system or custom
    // through adrenotools) instead of opening libvulkan.so itself. Apps cannot open a DRM
    // device on Android, so the display is surfaceless (see .ci/android/mesa/eden-zink.patch).
    if (vulkan_library && vulkan_library->IsOpen()) {
        void* gipa = vulkan_library->GetSymbolAddress("vkGetInstanceProcAddr");
        void* gdpa = vulkan_library->GetSymbolAddress("vkGetDeviceProcAddr");
        if (gipa && gdpa) {
            const auto hex = [](void* ptr) {
                return fmt::format("{:x}", reinterpret_cast<uintptr_t>(ptr));
            };
            setenv("EDEN_ZINK_VK_GIPA", hex(gipa).c_str(), 1);
            setenv("EDEN_ZINK_VK_GDPA", hex(gdpa).c_str(), 1);
        }
    }
    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);

    void* lib = dlopen("libEGL_mesa.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        LOG_ERROR(Render_OpenGL, "Failed to load Mesa: {}", dlerror());
        return false;
    }
    bool ok = Load(lib, "eglGetProcAddress", egl.GetProcAddress);
    ok &= Load(lib, "eglInitialize", egl.Initialize);
    ok &= Load(lib, "eglBindAPI", egl.BindAPI);
    ok &= Load(lib, "eglChooseConfig", egl.ChooseConfig);
    ok &= Load(lib, "eglCreateContext", egl.CreateContext);
    ok &= Load(lib, "eglDestroyContext", egl.DestroyContext);
    ok &= Load(lib, "eglCreatePbufferSurface", egl.CreatePbufferSurface);
    ok &= Load(lib, "eglDestroySurface", egl.DestroySurface);
    ok &= Load(lib, "eglMakeCurrent", egl.MakeCurrent);
    ok &= Load(lib, "eglGetError", egl.GetError);
    ok &= Load(lib, "eglQueryString", egl.QueryString);
    if (!ok) {
        return false;
    }
    egl.GetPlatformDisplayEXT = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
        egl.GetProcAddress("eglGetPlatformDisplayEXT"));
    if (!egl.GetPlatformDisplayEXT) {
        LOG_ERROR(Render_OpenGL, "Mesa EGL lacks eglGetPlatformDisplayEXT");
        return false;
    }
    egl.display =
        egl.GetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    EGLint major{};
    EGLint minor{};
    if (egl.display == EGL_NO_DISPLAY || !egl.Initialize(egl.display, &major, &minor)) {
        LOG_ERROR(Render_OpenGL, "Mesa surfaceless display failed: 0x{:x}", egl.GetError());
        return false;
    }
    if (!egl.BindAPI(EGL_OPENGL_API)) {
        LOG_ERROR(Render_OpenGL, "Mesa EGL has no desktop OpenGL");
        return false;
    }
    static constexpr EGLint config_attribs[]{
        EGL_SURFACE_TYPE,
        EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE,
        EGL_OPENGL_BIT,
        EGL_RED_SIZE,
        8,
        EGL_GREEN_SIZE,
        8,
        EGL_BLUE_SIZE,
        8,
        EGL_ALPHA_SIZE,
        8,
        EGL_NONE,
    };
    EGLint count{};
    if (!egl.ChooseConfig(egl.display, config_attribs, &egl.config, 1, &count) || count == 0) {
        LOG_ERROR(Render_OpenGL, "Mesa EGL has no RGBA8 pbuffer config");
        return false;
    }
    LOG_INFO(Render_OpenGL, "Mesa EGL {}.{}: {}", major, minor,
             egl.QueryString(egl.display, EGL_VERSION));
    return true;
}

} // Anonymous namespace

bool Initialize(const std::shared_ptr<Common::DynamicLibrary>& vulkan_library) {
    std::call_once(init_flag, [&] { egl.ready = InitializeOnce(vulkan_library); });
    return egl.ready;
}

Context::Context(const Core::Frontend::EmuWindow& window_, void* share_context, bool presenter_,
                 std::shared_ptr<Common::DynamicLibrary> vulkan_library_)
    : window{window_}, vulkan_library{std::move(vulkan_library_)}, presenter{presenter_} {
    if (!Initialize(vulkan_library)) {
        return;
    }
    static constexpr EGLint context_attribs[]{
        EGL_CONTEXT_MAJOR_VERSION,
        4,
        EGL_CONTEXT_MINOR_VERSION,
        6,
        EGL_CONTEXT_OPENGL_PROFILE_MASK,
        EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_NONE,
    };
    context = egl.CreateContext(egl.display, egl.config,
                                share_context ? share_context : EGL_NO_CONTEXT, context_attribs);
    if (context == EGL_NO_CONTEXT) {
        LOG_ERROR(Render_OpenGL, "Failed to create an OpenGL 4.6 context: 0x{:x}", egl.GetError());
        context = nullptr;
        return;
    }
    if (presenter) {
        const auto& layout = window.GetFramebufferLayout();
        ResizeSurface(layout.width, layout.height);
    }
}

Context::~Context() {
    if (!context) {
        return;
    }
    // The pack buffers and fences go with the share group.
    DoneCurrent();
    if (surface) {
        egl.DestroySurface(egl.display, surface);
    }
    egl.DestroyContext(egl.display, context);
    if (bound_window) {
        ANativeWindow_release(bound_window);
    }
}

void Context::MakeCurrent() {
    if (!context || is_current) {
        return;
    }
    const EGLSurface draw = surface ? surface : EGL_NO_SURFACE;
    is_current = egl.MakeCurrent(egl.display, draw, draw, context) == EGL_TRUE;
    if (!is_current) {
        LOG_ERROR(Render_OpenGL, "eglMakeCurrent failed: 0x{:x}", egl.GetError());
        return;
    }
    static std::once_flag glad_flag;
    std::call_once(glad_flag, [] {
        if (!OpenGL::GLES::Load(GetGLProcAddress)) {
            LOG_ERROR(Render_OpenGL, "Failed to load OpenGL entry points from Mesa");
        }
    });
}

void Context::DoneCurrent() {
    if (!is_current) {
        return;
    }
    egl.MakeCurrent(egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    is_current = false;
}

void Context::ResizeSurface(u32 width, u32 height) {
    width = std::max(width, 1u);
    height = std::max(height, 1u);
    if (surface && width == surface_width && height == surface_height) {
        return;
    }
    const EGLint attribs[]{EGL_WIDTH, static_cast<EGLint>(width), EGL_HEIGHT,
                           static_cast<EGLint>(height), EGL_NONE};
    EGLSurface next = egl.CreatePbufferSurface(egl.display, egl.config, attribs);
    if (next == EGL_NO_SURFACE) {
        LOG_ERROR(Render_OpenGL, "Failed to create a {}x{} pbuffer: 0x{:x}", width, height,
                  egl.GetError());
        return;
    }
    if (is_current && egl.MakeCurrent(egl.display, next, next, context) != EGL_TRUE) {
        egl.DestroySurface(egl.display, next);
        return;
    }
    if (surface) {
        egl.DestroySurface(egl.display, surface);
    }
    surface = next;
    surface_width = width;
    surface_height = height;
}

void Context::ReleaseReadback() {
    for (size_t i = 0; i < pack_buffers.size(); ++i) {
        if (pack_fences[i]) {
            glDeleteSync(static_cast<GLsync>(pack_fences[i]));
            pack_fences[i] = nullptr;
        }
    }
    if (pack_buffers[0] != 0) {
        glDeleteBuffers(static_cast<GLsizei>(pack_buffers.size()), pack_buffers.data());
        pack_buffers = {};
    }
    pack_width = 0;
    pack_height = 0;
}

void Context::BindWindow() {
    auto* const target = static_cast<ANativeWindow*>(window.GetWindowInfo().render_surface);
    if (target == bound_window) {
        return;
    }
    if (bound_window) {
        ANativeWindow_release(bound_window);
        bound_window = nullptr;
    }
    if (target) {
        ANativeWindow_acquire(target);
        ANativeWindow_setBuffersGeometry(target, 0, 0, WINDOW_FORMAT_RGBA_8888);
        bound_window = target;
    }
}

void Context::PresentPending() {
    const size_t index = pack_index ^ 1;
    if (!pack_fences[index]) {
        return;
    }
    auto fence = static_cast<GLsync>(pack_fences[index]);
    glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1'000'000'000);
    glDeleteSync(fence);
    pack_fences[index] = nullptr;
    if (!bound_window) {
        return;
    }
    const size_t row_bytes = static_cast<size_t>(pack_width) * 4;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pack_buffers[index]);
    const auto* pixels = static_cast<const u8*>(
        glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, static_cast<GLsizeiptr>(row_bytes * pack_height),
                         GL_MAP_READ_BIT));
    if (!pixels) {
        return;
    }
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(bound_window, &buffer, nullptr) == 0) {
        const u32 width = std::min<u32>(pack_width, static_cast<u32>(buffer.width));
        const u32 height = std::min<u32>(pack_height, static_cast<u32>(buffer.height));
        auto* dst = static_cast<u8*>(buffer.bits);
        const size_t dst_stride = static_cast<size_t>(buffer.stride) * 4;
        // OpenGL rows run bottom to top.
        for (u32 y = 0; y < height; ++y) {
            std::memcpy(dst + y * dst_stride, pixels + (pack_height - 1 - y) * row_bytes,
                        static_cast<size_t>(width) * 4);
        }
        ANativeWindow_unlockAndPost(bound_window);
    }
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
}

void Context::SwapBuffers() {
    if (!presenter || !is_current || !surface) {
        return;
    }
    GLint read_framebuffer{};
    GLint pack_buffer{};
    GLint pack_alignment{};
    GLint pack_row_length{};
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_framebuffer);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack_buffer);
    glGetIntegerv(GL_PACK_ALIGNMENT, &pack_alignment);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &pack_row_length);

    BindWindow();
    if (pack_width != surface_width || pack_height != surface_height) {
        ReleaseReadback();
        glCreateBuffers(static_cast<GLsizei>(pack_buffers.size()), pack_buffers.data());
        const auto size = static_cast<GLsizeiptr>(surface_width) * surface_height * 4;
        for (const GLuint buffer : pack_buffers) {
            glNamedBufferStorage(buffer, size, nullptr, GL_MAP_READ_BIT | GL_CLIENT_STORAGE_BIT);
        }
        pack_width = surface_width;
        pack_height = surface_height;
    }

    // Read this frame into one buffer and show the previous one, so the copy does not wait
    // for the frame just submitted.
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pack_buffers[pack_index]);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glReadPixels(0, 0, static_cast<GLsizei>(pack_width), static_cast<GLsizei>(pack_height), GL_RGBA,
                 GL_UNSIGNED_BYTE, nullptr);
    pack_fences[pack_index] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    PresentPending();
    pack_index ^= 1;

    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(read_framebuffer));
    glBindBuffer(GL_PIXEL_PACK_BUFFER, static_cast<GLuint>(pack_buffer));
    glPixelStorei(GL_PACK_ALIGNMENT, pack_alignment);
    glPixelStorei(GL_PACK_ROW_LENGTH, pack_row_length);

    const auto& layout = window.GetFramebufferLayout();
    ResizeSurface(layout.width, layout.height);
}

namespace {

EGLDisplay es_display{EGL_NO_DISPLAY};
EGLConfig es_config{};
std::once_flag es_init_flag;
bool es_ready = false;

void* GetEsProcAddress(const char* name) {
    return reinterpret_cast<void*>(eglGetProcAddress(name));
}

bool InitializeEs() {
    es_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major{};
    EGLint minor{};
    if (es_display == EGL_NO_DISPLAY || !eglInitialize(es_display, &major, &minor)) {
        LOG_ERROR(Render_OpenGL, "System EGL failed to initialize: 0x{:x}", eglGetError());
        return false;
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        return false;
    }
    static constexpr EGLint config_attribs[]{
        EGL_SURFACE_TYPE,
        EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,
        8,
        EGL_GREEN_SIZE,
        8,
        EGL_BLUE_SIZE,
        8,
        EGL_ALPHA_SIZE,
        8,
        EGL_NONE,
    };
    EGLint count{};
    if (!eglChooseConfig(es_display, config_attribs, &es_config, 1, &count) || count == 0) {
        LOG_ERROR(Render_OpenGL, "System EGL has no RGBA8 OpenGL ES 3 window config");
        return false;
    }
    LOG_INFO(Render_OpenGL, "System EGL {}.{}: {}", major, minor,
             eglQueryString(es_display, EGL_VENDOR));
    return true;
}

} // Anonymous namespace

EsContext::EsContext(const Core::Frontend::EmuWindow& window_, void* share_context,
                     bool presenter_)
    : window{window_}, presenter{presenter_} {
    std::call_once(es_init_flag, [] { es_ready = InitializeEs(); });
    if (!es_ready) {
        return;
    }
    static constexpr EGLint context_attribs[]{
        EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE,
    };
    context = eglCreateContext(es_display, es_config,
                               share_context ? share_context : EGL_NO_CONTEXT, context_attribs);
    if (context == EGL_NO_CONTEXT) {
        LOG_ERROR(Render_OpenGL, "Failed to create an OpenGL ES 3.2 context: 0x{:x}",
                  eglGetError());
        context = nullptr;
        return;
    }
    if (!presenter) {
        // Workers need a surface to be current on drivers without surfaceless contexts.
        static constexpr EGLint pbuffer_attribs[]{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(es_display, es_config, pbuffer_attribs);
    }
}

EsContext::~EsContext() {
    if (!context) {
        return;
    }
    DoneCurrent();
    if (surface) {
        eglDestroySurface(es_display, surface);
    }
    eglDestroyContext(es_display, context);
    if (surface_window) {
        ANativeWindow_release(surface_window);
    }
}

bool EsContext::BindWindowSurface() {
    auto* const target = static_cast<ANativeWindow*>(window.GetWindowInfo().render_surface);
    if (target == surface_window && surface) {
        return true;
    }
    if (surface) {
        eglMakeCurrent(es_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(es_display, surface);
        surface = nullptr;
        is_current = false;
    }
    if (surface_window) {
        ANativeWindow_release(surface_window);
        surface_window = nullptr;
    }
    if (!target) {
        return false;
    }
    ANativeWindow_acquire(target);
    surface_window = target;
    surface = eglCreateWindowSurface(es_display, es_config, target, nullptr);
    if (surface == EGL_NO_SURFACE) {
        LOG_ERROR(Render_OpenGL, "Failed to create the window surface: 0x{:x}", eglGetError());
        surface = nullptr;
        return false;
    }
    return true;
}

void EsContext::MakeCurrent() {
    if (!context || is_current) {
        return;
    }
    if (presenter && !BindWindowSurface()) {
        return;
    }
    const EGLSurface draw = surface ? surface : EGL_NO_SURFACE;
    is_current = eglMakeCurrent(es_display, draw, draw, context) == EGL_TRUE;
    if (!is_current) {
        LOG_ERROR(Render_OpenGL, "eglMakeCurrent failed: 0x{:x}", eglGetError());
        return;
    }
    static std::once_flag load_flag;
    std::call_once(load_flag, [] {
        if (!OpenGL::GLES::Load(GetEsProcAddress)) {
            LOG_ERROR(Render_OpenGL, "Failed to load OpenGL ES entry points");
        }
    });
}

void EsContext::DoneCurrent() {
    if (!is_current) {
        return;
    }
    eglMakeCurrent(es_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    is_current = false;
}

void EsContext::SwapBuffers() {
    if (!presenter || !is_current) {
        return;
    }
    if (!eglSwapBuffers(es_display, surface)) {
        // The window went away or was replaced (rotation, app switch): attach to the new one.
        is_current = false;
        MakeCurrent();
    }
}

} // namespace AndroidGL
