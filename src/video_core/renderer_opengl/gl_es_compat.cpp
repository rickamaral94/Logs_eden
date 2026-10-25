// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string_view>
#include <unordered_map>
#include <vector>

#include <nlohmann/json.hpp>

#include "common/logging.h"
#include "video_core/renderer_opengl/gl_es_compat.h"

namespace OpenGL::GLES {

namespace {

// Desktop enums that OpenGL ES rejects; glad defines all of them.
constexpr GLenum DESKTOP_ONLY_CAPS[]{
    GL_TEXTURE_CUBE_MAP_SEAMLESS, GL_COLOR_LOGIC_OP,       GL_PROGRAM_POINT_SIZE,
    GL_LINE_SMOOTH,               GL_POLYGON_SMOOTH,       GL_MULTISAMPLE,
    GL_SAMPLE_ALPHA_TO_ONE,       GL_ALPHA_TEST,           GL_POLYGON_OFFSET_LINE,
    GL_POLYGON_OFFSET_POINT,      GL_VERTEX_PROGRAM_NV,    GL_FRAGMENT_PROGRAM_NV,
    GL_GEOMETRY_PROGRAM_NV,       GL_TESS_CONTROL_PROGRAM_NV, GL_TESS_EVALUATION_PROGRAM_NV,
};

// Operations ES cannot express, counted for the diagnostics capture.
enum class Skip : size_t {
    PolygonMode,
    LogicOp,
    ClampColor,
    PointSize,
    AlphaFunc,
    PrimitiveRestartIndex,
    TextureBarrier,
    ClipControl,
    ViewportIndexed,
    DepthRangeIndexed,
    BaseInstance,
    DrawTransformFeedback,
    TextureView,
    TextureReadback,
    CompressedTextureReadback,
    ClearTexImage,
    DrawBufferOrder,
    LodBias,
    CapDisabled,
    Count,
};

constexpr std::array<std::string_view, static_cast<size_t>(Skip::Count)> SKIP_NAMES{
    "polygon_mode",
    "logic_op",
    "clamp_color",
    "point_size",
    "alpha_func",
    "primitive_restart_index",
    "texture_barrier",
    "clip_control",
    "viewport_indexed",
    "depth_range_indexed",
    "base_instance",
    "draw_transform_feedback",
    "texture_view",
    "texture_readback",
    "compressed_texture_readback",
    "clear_tex_image",
    "draw_buffer_order",
    "lod_bias",
    "cap_disabled",
};

std::array<std::atomic<u64>, static_cast<size_t>(Skip::Count)> skipped{};
std::array<std::atomic<bool>, static_cast<size_t>(Skip::Count)> warned{};

void Count(Skip what) {
    const auto index = static_cast<size_t>(what);
    skipped[index].fetch_add(1, std::memory_order_relaxed);
    if (!warned[index].exchange(true, std::memory_order_relaxed)) {
        LOG_WARNING(Render_OpenGL, "OpenGL ES: {} is not supported, skipped", SKIP_NAMES[index]);
    }
}

bool active = false;
std::unordered_map<std::string, bool> extensions;

bool Has(const char* name) {
    const auto it = extensions.find(name);
    return it != extensions.end() && it->second;
}

// ES extension entry points; glad only knows the desktop ones.
using PFNVIEWPORTINDEXEDFOES = void(APIENTRYP)(GLuint, GLfloat, GLfloat, GLfloat, GLfloat);
using PFNSCISSORINDEXEDOES = void(APIENTRYP)(GLuint, GLint, GLint, GLsizei, GLsizei);
using PFNDEPTHRANGEINDEXEDFOES = void(APIENTRYP)(GLuint, GLfloat, GLfloat);
using PFNCLIPCONTROLEXT = void(APIENTRYP)(GLenum, GLenum);
using PFNTEXTUREVIEWEXT = PFNGLTEXTUREVIEWPROC;
using PFNPOLYGONMODENV = void(APIENTRYP)(GLenum, GLenum);
using PFNBUFFERSTORAGEEXT = void(APIENTRYP)(GLenum, GLsizeiptr, const void*, GLbitfield);
using PFNCLEARTEXIMAGEEXT = PFNGLCLEARTEXIMAGEPROC;

struct Extensions {
    PFNVIEWPORTINDEXEDFOES ViewportIndexedf{};
    PFNSCISSORINDEXEDOES ScissorIndexed{};
    PFNDEPTHRANGEINDEXEDFOES DepthRangeIndexedf{};
    PFNCLIPCONTROLEXT ClipControl{};
    PFNTEXTUREVIEWEXT TextureView{};
    PFNPOLYGONMODENV PolygonMode{};
    PFNBUFFERSTORAGEEXT BufferStorage{};
    PFNCLEARTEXIMAGEEXT ClearTexImage{};
    PFNGLDRAWARRAYSINSTANCEDBASEINSTANCEPROC DrawArraysInstancedBaseInstance{};
    PFNGLDRAWELEMENTSINSTANCEDBASEINSTANCEPROC DrawElementsInstancedBaseInstance{};
    PFNGLDRAWELEMENTSINSTANCEDBASEVERTEXBASEINSTANCEPROC
        DrawElementsInstancedBaseVertexBaseInstance{};
    PFNGLMULTIDRAWARRAYSINDIRECTPROC MultiDrawArraysIndirect{};
    PFNGLMULTIDRAWELEMENTSINDIRECTPROC MultiDrawElementsIndirect{};
    PFNGLPOLYGONOFFSETCLAMPPROC PolygonOffsetClamp{};
    PFNGLGETQUERYOBJECTI64VPROC GetQueryObjecti64v{};
};
Extensions ext;

// Entry points of the ES driver that the emulation wraps.
struct Native {
    PFNGLBINDBUFFERPROC BindBuffer{};
    PFNGLBINDFRAMEBUFFERPROC BindFramebuffer{};
    PFNGLENABLEPROC Enable{};
    PFNGLDISABLEPROC Disable{};
    PFNGLENABLEIPROC Enablei{};
    PFNGLDISABLEIPROC Disablei{};
    PFNGLDELETETEXTURESPROC DeleteTextures{};
    PFNGLSAMPLERPARAMETERFPROC SamplerParameterf{};
};
Native native;

// Per context state the emulation shadows so it can restore bindings without queries. Each
// thread has at most one context current, and only the renderer thread edits objects.
struct Shadow {
    GLuint read_framebuffer{};
    GLuint draw_framebuffer{};
    GLuint parameter_buffer{};
    GLenum active_unit{GL_TEXTURE0};
    std::unordered_map<GLuint, GLenum> unit_targets;
};
thread_local Shadow shadow;

struct TextureInfo {
    GLenum desktop_target{};
    GLenum target{};
    GLenum format{};
};
std::mutex textures_mutex;
std::unordered_map<GLuint, TextureInfo> textures;

GLint scratch_unit{};

GLenum EsTarget(GLenum target) {
    switch (target) {
    case GL_TEXTURE_1D:
    case GL_TEXTURE_RECTANGLE:
        return GL_TEXTURE_2D;
    case GL_TEXTURE_1D_ARRAY:
        return GL_TEXTURE_2D_ARRAY;
    default:
        return target;
    }
}

TextureInfo Info(GLuint texture) {
    std::scoped_lock lock{textures_mutex};
    const auto it = textures.find(texture);
    return it != textures.end() ? it->second : TextureInfo{GL_TEXTURE_2D, GL_TEXTURE_2D, GL_RGBA8};
}

void Record(GLuint texture, GLenum desktop_target) {
    std::scoped_lock lock{textures_mutex};
    auto& info = textures[texture];
    info.desktop_target = desktop_target;
    info.target = EsTarget(desktop_target);
}

void RecordFormat(GLuint texture, GLenum format) {
    std::scoped_lock lock{textures_mutex};
    textures[texture].format = format;
}

void SetActiveUnit(GLenum unit) {
    if (shadow.active_unit != unit) {
        glActiveTexture(unit);
        shadow.active_unit = unit;
    }
}

// Binds texture on the scratch unit for an edit. The renderer never binds textures with
// glActiveTexture/glBindTexture itself, so only the emulation's own shadow changes.
GLenum BindForEdit(GLuint texture) {
    const GLenum target = Info(texture).target;
    SetActiveUnit(static_cast<GLenum>(GL_TEXTURE0 + scratch_unit));
    glBindTexture(target, texture);
    return target;
}

void BindBufferForEdit(GLenum target, GLuint buffer) {
    native.BindBuffer(target, buffer);
}

void APIENTRY ShimBindBuffer(GLenum target, GLuint buffer) {
    if (target == GL_PARAMETER_BUFFER) {
        shadow.parameter_buffer = buffer;
        return;
    }
    native.BindBuffer(target, buffer);
}

void APIENTRY ShimBindFramebuffer(GLenum target, GLuint framebuffer) {
    if (target == GL_FRAMEBUFFER || target == GL_READ_FRAMEBUFFER) {
        shadow.read_framebuffer = framebuffer;
    }
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) {
        shadow.draw_framebuffer = framebuffer;
    }
    native.BindFramebuffer(target, framebuffer);
}

void RestoreFramebuffers() {
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, shadow.read_framebuffer);
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, shadow.draw_framebuffer);
}

bool DesktopOnlyCap(GLenum cap) {
    if (cap == GL_DEPTH_CLAMP) {
        return !Has("GL_EXT_depth_clamp");
    }
    if (cap == GL_FRAMEBUFFER_SRGB) {
        return !Has("GL_EXT_sRGB_write_control");
    }
    if (cap >= GL_CLIP_DISTANCE0 && cap <= GL_CLIP_DISTANCE7) {
        return !Has("GL_EXT_clip_cull_distance");
    }
    return std::find(std::begin(DESKTOP_ONLY_CAPS), std::end(DESKTOP_ONLY_CAPS), cap) !=
           std::end(DESKTOP_ONLY_CAPS);
}

void APIENTRY ShimEnable(GLenum cap) {
    if (cap == GL_PRIMITIVE_RESTART) {
        native.Enable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
    } else if (!DesktopOnlyCap(cap)) {
        native.Enable(cap);
    } else {
        Count(Skip::CapDisabled);
    }
}

void APIENTRY ShimDisable(GLenum cap) {
    if (cap == GL_PRIMITIVE_RESTART) {
        native.Disable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
    } else if (!DesktopOnlyCap(cap)) {
        native.Disable(cap);
    }
}

void APIENTRY ShimEnablei(GLenum cap, GLuint index) {
    if (cap == GL_SCISSOR_TEST && !ext.ScissorIndexed) {
        if (index == 0) {
            native.Enable(cap);
        }
        return;
    }
    native.Enablei(cap, index);
}

void APIENTRY ShimDisablei(GLenum cap, GLuint index) {
    if (cap == GL_SCISSOR_TEST && !ext.ScissorIndexed) {
        if (index == 0) {
            native.Disable(cap);
        }
        return;
    }
    native.Disablei(cap, index);
}

void APIENTRY ShimDeleteTextures(GLsizei n, const GLuint* names) {
    {
        std::scoped_lock lock{textures_mutex};
        for (GLsizei i = 0; i < n; ++i) {
            textures.erase(names[i]);
        }
    }
    native.DeleteTextures(n, names);
}

void APIENTRY ShimSamplerParameterf(GLuint sampler, GLenum pname, GLfloat param) {
    if (pname == GL_TEXTURE_LOD_BIAS) {
        if (param != 0.0f) {
            Count(Skip::LodBias);
        }
        return;
    }
    native.SamplerParameterf(sampler, pname, param);
}

// Object creation: ES creates the object on its first bind.

void APIENTRY ShimCreateBuffers(GLsizei n, GLuint* buffers) {
    glGenBuffers(n, buffers);
    for (GLsizei i = 0; i < n; ++i) {
        BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffers[i]);
    }
}

void APIENTRY ShimCreateTextures(GLenum target, GLsizei n, GLuint* names) {
    glGenTextures(n, names);
    for (GLsizei i = 0; i < n; ++i) {
        Record(names[i], target);
        BindForEdit(names[i]);
    }
}

void APIENTRY ShimCreateSamplers(GLsizei n, GLuint* samplers) {
    glGenSamplers(n, samplers);
}

void APIENTRY ShimCreateQueries(GLenum, GLsizei n, GLuint* ids) {
    glGenQueries(n, ids);
}

void APIENTRY ShimCreateRenderbuffers(GLsizei n, GLuint* renderbuffers) {
    glGenRenderbuffers(n, renderbuffers);
    for (GLsizei i = 0; i < n; ++i) {
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffers[i]);
    }
}

void APIENTRY ShimCreateTransformFeedbacks(GLsizei n, GLuint* ids) {
    glGenTransformFeedbacks(n, ids);
}

void APIENTRY ShimCreateProgramPipelines(GLsizei n, GLuint* pipelines) {
    glGenProgramPipelines(n, pipelines);
}

// Buffers, edited through GL_COPY_WRITE_BUFFER, which the renderer does not use.

void APIENTRY ShimNamedBufferData(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    glBufferData(GL_COPY_WRITE_BUFFER, size, data, usage);
}

void APIENTRY ShimNamedBufferStorage(GLuint buffer, GLsizeiptr size, const void* data,
                                     GLbitfield flags) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    if (ext.BufferStorage) {
        ext.BufferStorage(GL_COPY_WRITE_BUFFER, size, data, flags);
    } else {
        glBufferData(GL_COPY_WRITE_BUFFER, size, data, GL_DYNAMIC_DRAW);
    }
}

void APIENTRY ShimNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size,
                                     const void* data) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    glBufferSubData(GL_COPY_WRITE_BUFFER, offset, size, data);
}

void* APIENTRY ShimMapNamedBufferRange(GLuint buffer, GLintptr offset, GLsizeiptr length,
                                       GLbitfield access) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    if (!ext.BufferStorage) {
        access &= ~static_cast<GLbitfield>(GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
    }
    return glMapBufferRange(GL_COPY_WRITE_BUFFER, offset, length, access);
}

void APIENTRY ShimFlushMappedNamedBufferRange(GLuint buffer, GLintptr offset, GLsizeiptr length) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    glFlushMappedBufferRange(GL_COPY_WRITE_BUFFER, offset, length);
}

GLboolean APIENTRY ShimUnmapNamedBuffer(GLuint buffer) {
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, buffer);
    return glUnmapBuffer(GL_COPY_WRITE_BUFFER);
}

void APIENTRY ShimCopyNamedBufferSubData(GLuint read, GLuint write, GLintptr read_offset,
                                         GLintptr write_offset, GLsizeiptr size) {
    BindBufferForEdit(GL_COPY_READ_BUFFER, read);
    BindBufferForEdit(GL_COPY_WRITE_BUFFER, write);
    glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, read_offset, write_offset, size);
}

void APIENTRY ShimGetNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size,
                                        void* data) {
    BindBufferForEdit(GL_COPY_READ_BUFFER, buffer);
    if (const void* src = glMapBufferRange(GL_COPY_READ_BUFFER, offset, size, GL_MAP_READ_BIT)) {
        std::memcpy(data, src, static_cast<size_t>(size));
        glUnmapBuffer(GL_COPY_READ_BUFFER);
    }
}

size_t ComponentSize(GLenum type) {
    switch (type) {
    case GL_UNSIGNED_BYTE:
    case GL_BYTE:
        return 1;
    case GL_UNSIGNED_SHORT:
    case GL_SHORT:
    case GL_HALF_FLOAT:
        return 2;
    default:
        return 4;
    }
}

size_t ComponentCount(GLenum format) {
    switch (format) {
    case GL_RG:
    case GL_RG_INTEGER:
        return 2;
    case GL_RGB:
    case GL_RGB_INTEGER:
        return 3;
    case GL_RGBA:
    case GL_RGBA_INTEGER:
        return 4;
    default:
        return 1;
    }
}

void APIENTRY ShimClearNamedBufferSubData(GLuint buffer, GLenum, GLintptr offset, GLsizeiptr size,
                                          GLenum format, GLenum type, const void* data) {
    const size_t element = ComponentSize(type) * ComponentCount(format);
    std::vector<u8> fill(static_cast<size_t>(size));
    for (size_t i = 0; data && i + element <= fill.size(); i += element) {
        std::memcpy(fill.data() + i, data, element);
    }
    ShimNamedBufferSubData(buffer, offset, size, fill.data());
}

void APIENTRY ShimBindBuffersRange(GLenum target, GLuint first, GLsizei count,
                                   const GLuint* buffers, const GLintptr* offsets,
                                   const GLsizeiptr* sizes) {
    for (GLsizei i = 0; i < count; ++i) {
        const GLuint index = first + static_cast<GLuint>(i);
        if (buffers && buffers[i] != 0) {
            glBindBufferRange(target, index, buffers[i], offsets[i], sizes[i]);
        } else {
            glBindBufferBase(target, index, 0);
        }
    }
}

void APIENTRY ShimBindVertexBuffers(GLuint first, GLsizei count, const GLuint* buffers,
                                    const GLintptr* offsets, const GLsizei* strides) {
    for (GLsizei i = 0; i < count; ++i) {
        const GLuint index = first + static_cast<GLuint>(i);
        if (buffers) {
            glBindVertexBuffer(index, buffers[i], offsets[i], strides[i]);
        } else {
            glBindVertexBuffer(index, 0, 0, 16);
        }
    }
}

void APIENTRY ShimTextureBufferRange(GLuint texture, GLenum internalformat, GLuint buffer,
                                     GLintptr offset, GLsizeiptr size) {
    RecordFormat(texture, internalformat);
    const GLenum target = BindForEdit(texture);
    glTexBufferRange(target, internalformat, buffer, offset, size);
}

// Textures, edited on the scratch unit.

void APIENTRY ShimTextureStorage2D(GLuint texture, GLsizei levels, GLenum internalformat,
                                   GLsizei width, GLsizei height) {
    RecordFormat(texture, internalformat);
    const auto info = Info(texture);
    BindForEdit(texture);
    if (info.desktop_target == GL_TEXTURE_1D_ARRAY) {
        glTexStorage3D(info.target, levels, internalformat, width, 1, height);
    } else {
        glTexStorage2D(info.target, levels, internalformat, width, height);
    }
}

void APIENTRY ShimTextureStorage3D(GLuint texture, GLsizei levels, GLenum internalformat,
                                   GLsizei width, GLsizei height, GLsizei depth) {
    RecordFormat(texture, internalformat);
    const GLenum target = BindForEdit(texture);
    glTexStorage3D(target, levels, internalformat, width, height, depth);
}

void APIENTRY ShimTextureStorage3DMultisample(GLuint texture, GLsizei samples,
                                              GLenum internalformat, GLsizei width, GLsizei height,
                                              GLsizei depth, GLboolean fixed) {
    RecordFormat(texture, internalformat);
    const GLenum target = BindForEdit(texture);
    glTexStorage3DMultisample(target, samples, internalformat, width, height, depth, fixed);
}

void APIENTRY ShimTextureSubImage2D(GLuint texture, GLint level, GLint x, GLint y, GLsizei width,
                                    GLsizei height, GLenum format, GLenum type,
                                    const void* pixels) {
    const auto info = Info(texture);
    BindForEdit(texture);
    if (info.desktop_target == GL_TEXTURE_1D_ARRAY) {
        glTexSubImage3D(info.target, level, x, 0, y, width, 1, height, format, type, pixels);
    } else {
        glTexSubImage2D(info.target, level, x, y, width, height, format, type, pixels);
    }
}

void APIENTRY ShimTextureSubImage3D(GLuint texture, GLint level, GLint x, GLint y, GLint z,
                                    GLsizei width, GLsizei height, GLsizei depth, GLenum format,
                                    GLenum type, const void* pixels) {
    const GLenum target = BindForEdit(texture);
    if (target != GL_TEXTURE_CUBE_MAP) {
        glTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, pixels);
        return;
    }
    // Direct state access addresses cube faces as layers.
    GLint row_length{};
    GLint image_height{};
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &row_length);
    glGetIntegerv(GL_UNPACK_IMAGE_HEIGHT, &image_height);
    const GLint rows = image_height != 0 ? image_height : height;
    const GLint row = row_length != 0 ? row_length : width;
    const auto layer_bytes = static_cast<uintptr_t>(row) * static_cast<uintptr_t>(rows) *
                             ComponentSize(type) * ComponentCount(format);
    for (GLsizei face = 0; face < depth; ++face) {
        const auto* data = static_cast<const u8*>(pixels) + layer_bytes * static_cast<size_t>(face);
        glTexSubImage2D(static_cast<GLenum>(GL_TEXTURE_CUBE_MAP_POSITIVE_X + z + face), level, x, y,
                        width, height, format, type, data);
    }
}

void APIENTRY ShimCompressedTextureSubImage2D(GLuint texture, GLint level, GLint x, GLint y,
                                              GLsizei width, GLsizei height, GLenum format,
                                              GLsizei size, const void* data) {
    const GLenum target = BindForEdit(texture);
    glCompressedTexSubImage2D(target, level, x, y, width, height, format, size, data);
}

void APIENTRY ShimCompressedTextureSubImage3D(GLuint texture, GLint level, GLint x, GLint y,
                                              GLint z, GLsizei width, GLsizei height,
                                              GLsizei depth, GLenum format, GLsizei size,
                                              const void* data) {
    const GLenum target = BindForEdit(texture);
    if (target != GL_TEXTURE_CUBE_MAP) {
        glCompressedTexSubImage3D(target, level, x, y, z, width, height, depth, format, size,
                                  data);
        return;
    }
    const GLsizei face_size = depth != 0 ? size / depth : size;
    for (GLsizei face = 0; face < depth; ++face) {
        const auto* face_data = static_cast<const u8*>(data) + face_size * face;
        glCompressedTexSubImage2D(static_cast<GLenum>(GL_TEXTURE_CUBE_MAP_POSITIVE_X + z + face),
                                  level, x, y, width, height, format, face_size, face_data);
    }
}

void APIENTRY ShimTextureParameteri(GLuint texture, GLenum pname, GLint param) {
    if (pname == GL_TEXTURE_LOD_BIAS) {
        return;
    }
    const GLenum target = BindForEdit(texture);
    glTexParameteri(target, pname, param);
}

void APIENTRY ShimTextureParameteriv(GLuint texture, GLenum pname, const GLint* params) {
    const GLenum target = BindForEdit(texture);
    if (pname == GL_TEXTURE_SWIZZLE_RGBA) {
        glTexParameteri(target, GL_TEXTURE_SWIZZLE_R, params[0]);
        glTexParameteri(target, GL_TEXTURE_SWIZZLE_G, params[1]);
        glTexParameteri(target, GL_TEXTURE_SWIZZLE_B, params[2]);
        glTexParameteri(target, GL_TEXTURE_SWIZZLE_A, params[3]);
        return;
    }
    glTexParameteriv(target, pname, params);
}

void APIENTRY ShimTextureView(GLuint texture, GLenum target, GLuint orig, GLenum internalformat,
                              GLuint min_level, GLuint num_levels, GLuint min_layer,
                              GLuint num_layers) {
    Record(texture, target);
    RecordFormat(texture, internalformat);
    if (!ext.TextureView) {
        Count(Skip::TextureView);
        return;
    }
    ext.TextureView(texture, EsTarget(target), orig, internalformat, min_level, num_levels,
                    min_layer, num_layers);
}

void APIENTRY ShimBindTextureUnit(GLuint unit, GLuint texture) {
    SetActiveUnit(GL_TEXTURE0 + unit);
    if (texture != 0) {
        const GLenum target = Info(texture).target;
        glBindTexture(target, texture);
        shadow.unit_targets[unit] = target;
    } else if (const auto it = shadow.unit_targets.find(unit); it != shadow.unit_targets.end()) {
        glBindTexture(it->second, 0);
        shadow.unit_targets.erase(it);
    }
}

void APIENTRY ShimBindTextures(GLuint first, GLsizei count, const GLuint* names) {
    for (GLsizei i = 0; i < count; ++i) {
        ShimBindTextureUnit(first + static_cast<GLuint>(i), names ? names[i] : 0);
    }
}

void APIENTRY ShimBindSamplers(GLuint first, GLsizei count, const GLuint* samplers) {
    for (GLsizei i = 0; i < count; ++i) {
        glBindSampler(first + static_cast<GLuint>(i), samplers ? samplers[i] : 0);
    }
}

void APIENTRY ShimBindImageTextures(GLuint first, GLsizei count, const GLuint* names) {
    for (GLsizei i = 0; i < count; ++i) {
        const GLuint unit = first + static_cast<GLuint>(i);
        const GLuint texture = names ? names[i] : 0;
        if (texture == 0) {
            glBindImageTexture(unit, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32UI);
            continue;
        }
        glBindImageTexture(unit, texture, 0, GL_TRUE, 0, GL_READ_WRITE, Info(texture).format);
    }
}

GLuint ScratchFramebuffer() {
    thread_local GLuint framebuffer = 0;
    if (framebuffer == 0) {
        glGenFramebuffers(1, &framebuffer);
    }
    return framebuffer;
}

void APIENTRY ShimGetTextureSubImage(GLuint texture, GLint level, GLint x, GLint y, GLint z,
                                     GLsizei width, GLsizei height, GLsizei depth, GLenum format,
                                     GLenum type, GLsizei, void* pixels) {
    const auto info = Info(texture);
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, ScratchFramebuffer());
    const bool depth_format = format == GL_DEPTH_COMPONENT || format == GL_DEPTH_STENCIL;
    const GLenum attachment = depth_format ? GL_DEPTH_ATTACHMENT : GL_COLOR_ATTACHMENT0;
    const bool layered = info.target == GL_TEXTURE_2D_ARRAY || info.target == GL_TEXTURE_3D ||
                         info.target == GL_TEXTURE_CUBE_MAP_ARRAY ||
                         info.target == GL_TEXTURE_CUBE_MAP;
    const auto layer_bytes = static_cast<size_t>(width) * static_cast<size_t>(height) *
                             ComponentSize(type) * ComponentCount(format);
    for (GLsizei layer = 0; layer < std::max(depth, 1); ++layer) {
        if (layered) {
            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, attachment, texture, level, z + layer);
        } else {
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, info.target, texture, level);
        }
        if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            Count(Skip::TextureReadback);
            break;
        }
        auto* dst = static_cast<u8*>(pixels) + layer_bytes * static_cast<size_t>(layer);
        glReadPixels(x, y, width, height, format, type, dst);
    }
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, GL_TEXTURE_2D, 0, 0);
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, shadow.read_framebuffer);
}

void APIENTRY ShimGetCompressedTextureSubImage(GLuint, GLint, GLint, GLint, GLint, GLsizei, GLsizei,
                                               GLsizei, GLsizei, void*) {
    Count(Skip::CompressedTextureReadback);
}

void APIENTRY ShimClearTexImage(GLuint texture, GLint level, GLenum format, GLenum type,
                                const void* data) {
    if (ext.ClearTexImage) {
        ext.ClearTexImage(texture, level, format, type, data);
    } else {
        Count(Skip::ClearTexImage);
    }
}

// Framebuffers: attachments are edited on the read binding, draw buffers on the draw binding;
// both are restored from the shadow.

void APIENTRY ShimNamedFramebufferTexture(GLuint framebuffer, GLenum attachment, GLuint texture,
                                          GLint level) {
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
    glFramebufferTexture(GL_READ_FRAMEBUFFER, attachment, texture, level);
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, shadow.read_framebuffer);
}

void APIENTRY ShimNamedFramebufferTextureLayer(GLuint framebuffer, GLenum attachment,
                                               GLuint texture, GLint level, GLint layer) {
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
    glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, attachment, texture, level, layer);
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, shadow.read_framebuffer);
}

void APIENTRY ShimNamedFramebufferDrawBuffers(GLuint framebuffer, GLsizei n, const GLenum* bufs) {
    // ES only accepts COLOR_ATTACHMENTi (or NONE) in position i.
    std::array<GLenum, 8> ordered{};
    const auto count = std::min<GLsizei>(n, static_cast<GLsizei>(ordered.size()));
    for (GLsizei i = 0; i < count; ++i) {
        const GLenum expected = static_cast<GLenum>(GL_COLOR_ATTACHMENT0 + i);
        if (bufs[i] == expected || bufs[i] == GL_NONE) {
            ordered[i] = bufs[i];
        } else {
            ordered[i] = GL_NONE;
            Count(Skip::DrawBufferOrder);
        }
    }
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
    glDrawBuffers(count, ordered.data());
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, shadow.draw_framebuffer);
}

void APIENTRY ShimNamedFramebufferDrawBuffer(GLuint framebuffer, GLenum buf) {
    std::array<GLenum, 8> bufs{};
    GLsizei count = 1;
    if (buf >= GL_COLOR_ATTACHMENT0 && buf < GL_COLOR_ATTACHMENT0 + bufs.size()) {
        count = static_cast<GLsizei>(buf - GL_COLOR_ATTACHMENT0 + 1);
        bufs[static_cast<size_t>(count - 1)] = buf;
    } else {
        bufs[0] = buf == GL_BACK ? GL_BACK : GL_NONE;
    }
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
    glDrawBuffers(count, bufs.data());
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, shadow.draw_framebuffer);
}

void APIENTRY ShimNamedFramebufferParameteri(GLuint framebuffer, GLenum pname, GLint param) {
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
    glFramebufferParameteri(GL_DRAW_FRAMEBUFFER, pname, param);
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, shadow.draw_framebuffer);
}

void APIENTRY ShimBlitNamedFramebuffer(GLuint read, GLuint draw, GLint sx0, GLint sy0, GLint sx1,
                                       GLint sy1, GLint dx0, GLint dy0, GLint dx1, GLint dy1,
                                       GLbitfield mask, GLenum filter) {
    native.BindFramebuffer(GL_READ_FRAMEBUFFER, read);
    native.BindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
    glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    RestoreFramebuffers();
}

// Fixed function state.

void APIENTRY ShimViewportIndexedf(GLuint index, GLfloat x, GLfloat y, GLfloat w, GLfloat h) {
    if (ext.ViewportIndexedf) {
        ext.ViewportIndexedf(index, x, y, w, h);
    } else if (index == 0) {
        glViewport(static_cast<GLint>(x), static_cast<GLint>(y), static_cast<GLsizei>(w),
                   static_cast<GLsizei>(h));
    } else {
        Count(Skip::ViewportIndexed);
    }
}

void APIENTRY ShimScissorIndexed(GLuint index, GLint x, GLint y, GLsizei w, GLsizei h) {
    if (ext.ScissorIndexed) {
        ext.ScissorIndexed(index, x, y, w, h);
    } else if (index == 0) {
        glScissor(x, y, w, h);
    } else {
        Count(Skip::ViewportIndexed);
    }
}

void APIENTRY ShimDepthRangeIndexed(GLuint index, GLdouble n, GLdouble f) {
    if (ext.DepthRangeIndexedf) {
        ext.DepthRangeIndexedf(index, static_cast<GLfloat>(n), static_cast<GLfloat>(f));
    } else if (index == 0) {
        glDepthRangef(static_cast<GLfloat>(n), static_cast<GLfloat>(f));
    } else {
        Count(Skip::DepthRangeIndexed);
    }
}

void APIENTRY ShimClipControl(GLenum origin, GLenum depth) {
    if (ext.ClipControl) {
        ext.ClipControl(origin, depth);
    } else if (origin != GL_LOWER_LEFT || depth != GL_NEGATIVE_ONE_TO_ONE) {
        Count(Skip::ClipControl);
    }
}

void APIENTRY ShimPolygonMode(GLenum face, GLenum mode) {
    if (ext.PolygonMode) {
        ext.PolygonMode(face, mode);
    } else if (mode != GL_FILL) {
        Count(Skip::PolygonMode);
    }
}

void APIENTRY ShimPolygonOffsetClamp(GLfloat factor, GLfloat units, GLfloat clamp) {
    if (ext.PolygonOffsetClamp) {
        ext.PolygonOffsetClamp(factor, units, clamp);
    } else {
        glPolygonOffset(factor, units);
    }
}

void APIENTRY ShimLogicOp(GLenum) {
    Count(Skip::LogicOp);
}

void APIENTRY ShimClampColor(GLenum, GLenum) {
    Count(Skip::ClampColor);
}

void APIENTRY ShimPointSize(GLfloat size) {
    if (size != 1.0f) {
        Count(Skip::PointSize);
    }
}

void APIENTRY ShimAlphaFunc(GLenum func, GLfloat) {
    if (func != GL_ALWAYS) {
        Count(Skip::AlphaFunc);
    }
}

void APIENTRY ShimMaterialfv(GLenum, GLenum, const GLfloat*) {}

void APIENTRY ShimEnableClientState(GLenum) {}

void APIENTRY ShimPrimitiveRestartIndex(GLuint index) {
    // ES restarts at the largest index of the index type only, which is what guests use.
    if (index != 0xFFFFFFFF && index != 0xFFFF && index != 0xFF) {
        Count(Skip::PrimitiveRestartIndex);
    }
}

void APIENTRY ShimTextureBarrier() {
    Count(Skip::TextureBarrier);
}

void APIENTRY ShimGetQueryObjecti64v(GLuint id, GLenum pname, GLint64* params) {
    if (ext.GetQueryObjecti64v) {
        ext.GetQueryObjecti64v(id, pname, params);
        return;
    }
    GLuint value{};
    glGetQueryObjectuiv(id, pname, &value);
    *params = value;
}

// Draws.

void APIENTRY ShimDrawArraysInstancedBaseInstance(GLenum mode, GLint first, GLsizei count,
                                                  GLsizei instances, GLuint base_instance) {
    if (ext.DrawArraysInstancedBaseInstance) {
        ext.DrawArraysInstancedBaseInstance(mode, first, count, instances, base_instance);
        return;
    }
    if (base_instance != 0) {
        Count(Skip::BaseInstance);
    }
    glDrawArraysInstanced(mode, first, count, instances);
}

void APIENTRY ShimDrawElementsInstancedBaseInstance(GLenum mode, GLsizei count, GLenum type,
                                                    const void* indices, GLsizei instances,
                                                    GLuint base_instance) {
    if (ext.DrawElementsInstancedBaseInstance) {
        ext.DrawElementsInstancedBaseInstance(mode, count, type, indices, instances,
                                              base_instance);
        return;
    }
    if (base_instance != 0) {
        Count(Skip::BaseInstance);
    }
    glDrawElementsInstanced(mode, count, type, indices, instances);
}

void APIENTRY ShimDrawElementsInstancedBaseVertexBaseInstance(GLenum mode, GLsizei count,
                                                              GLenum type, const void* indices,
                                                              GLsizei instances, GLint base_vertex,
                                                              GLuint base_instance) {
    if (ext.DrawElementsInstancedBaseVertexBaseInstance) {
        ext.DrawElementsInstancedBaseVertexBaseInstance(mode, count, type, indices, instances,
                                                        base_vertex, base_instance);
        return;
    }
    if (base_instance != 0) {
        Count(Skip::BaseInstance);
    }
    glDrawElementsInstancedBaseVertex(mode, count, type, indices, instances, base_vertex);
}

void APIENTRY ShimMultiDrawArraysIndirect(GLenum mode, const void* indirect, GLsizei count,
                                          GLsizei stride) {
    if (ext.MultiDrawArraysIndirect) {
        ext.MultiDrawArraysIndirect(mode, indirect, count, stride);
        return;
    }
    const auto step = static_cast<uintptr_t>(stride != 0 ? stride : 16);
    for (GLsizei i = 0; i < count; ++i) {
        glDrawArraysIndirect(mode, reinterpret_cast<const void*>(
                                       reinterpret_cast<uintptr_t>(indirect) + step * i));
    }
}

void APIENTRY ShimMultiDrawElementsIndirect(GLenum mode, GLenum type, const void* indirect,
                                            GLsizei count, GLsizei stride) {
    if (ext.MultiDrawElementsIndirect) {
        ext.MultiDrawElementsIndirect(mode, type, indirect, count, stride);
        return;
    }
    const auto step = static_cast<uintptr_t>(stride != 0 ? stride : 20);
    for (GLsizei i = 0; i < count; ++i) {
        glDrawElementsIndirect(mode, type,
                               reinterpret_cast<const void*>(
                                   reinterpret_cast<uintptr_t>(indirect) + step * i));
    }
}

// The draw count lives in GPU memory: read it back (a stall), then draw that many.
GLsizei ReadDrawCount(GLintptr offset, GLsizei max_count) {
    GLuint count{};
    BindBufferForEdit(GL_COPY_READ_BUFFER, shadow.parameter_buffer);
    if (const void* src =
            glMapBufferRange(GL_COPY_READ_BUFFER, offset, sizeof(count), GL_MAP_READ_BIT)) {
        std::memcpy(&count, src, sizeof(count));
        glUnmapBuffer(GL_COPY_READ_BUFFER);
    }
    return std::min(static_cast<GLsizei>(count), max_count);
}

void APIENTRY ShimMultiDrawArraysIndirectCount(GLenum mode, const void* indirect,
                                               GLintptr draw_count, GLsizei max_count,
                                               GLsizei stride) {
    ShimMultiDrawArraysIndirect(mode, indirect, ReadDrawCount(draw_count, max_count), stride);
}

void APIENTRY ShimMultiDrawElementsIndirectCount(GLenum mode, GLenum type, const void* indirect,
                                                 GLintptr draw_count, GLsizei max_count,
                                                 GLsizei stride) {
    ShimMultiDrawElementsIndirect(mode, type, indirect, ReadDrawCount(draw_count, max_count),
                                  stride);
}

void APIENTRY ShimDrawTransformFeedback(GLenum, GLuint) {
    Count(Skip::DrawTransformFeedback);
}

void LoadExtensions(GLADloadproc load) {
    extensions.clear();
    GLint count{};
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; ++i) {
        if (const auto* name = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i))) {
            extensions.emplace(name, true);
        }
    }
    const auto get = [load](const char* name) { return load(name); };
    const auto pick = [&](auto& out, const char* extension, const char* name) {
        if (Has(extension)) {
            out = reinterpret_cast<std::remove_reference_t<decltype(out)>>(get(name));
        }
    };
    ext = {};
    pick(ext.ViewportIndexedf, "GL_OES_viewport_array", "glViewportIndexedfOES");
    pick(ext.ScissorIndexed, "GL_OES_viewport_array", "glScissorIndexedOES");
    pick(ext.DepthRangeIndexedf, "GL_OES_viewport_array", "glDepthRangeIndexedfOES");
    pick(ext.ClipControl, "GL_EXT_clip_control", "glClipControlEXT");
    pick(ext.TextureView, "GL_OES_texture_view", "glTextureViewOES");
    if (!ext.TextureView) {
        pick(ext.TextureView, "GL_EXT_texture_view", "glTextureViewEXT");
    }
    pick(ext.PolygonMode, "GL_NV_polygon_mode", "glPolygonModeNV");
    pick(ext.BufferStorage, "GL_EXT_buffer_storage", "glBufferStorageEXT");
    pick(ext.ClearTexImage, "GL_EXT_clear_texture", "glClearTexImageEXT");
    pick(ext.DrawArraysInstancedBaseInstance, "GL_EXT_base_instance",
         "glDrawArraysInstancedBaseInstanceEXT");
    pick(ext.DrawElementsInstancedBaseInstance, "GL_EXT_base_instance",
         "glDrawElementsInstancedBaseInstanceEXT");
    pick(ext.DrawElementsInstancedBaseVertexBaseInstance, "GL_EXT_base_instance",
         "glDrawElementsInstancedBaseVertexBaseInstanceEXT");
    pick(ext.MultiDrawArraysIndirect, "GL_EXT_multi_draw_indirect",
         "glMultiDrawArraysIndirectEXT");
    pick(ext.MultiDrawElementsIndirect, "GL_EXT_multi_draw_indirect",
         "glMultiDrawElementsIndirectEXT");
    pick(ext.PolygonOffsetClamp, "GL_EXT_polygon_offset_clamp", "glPolygonOffsetClampEXT");
    pick(ext.GetQueryObjecti64v, "GL_EXT_disjoint_timer_query", "glGetQueryObjecti64vEXT");
}

void Install() {
    native.BindBuffer = glad_glBindBuffer;
    native.BindFramebuffer = glad_glBindFramebuffer;
    native.Enable = glad_glEnable;
    native.Disable = glad_glDisable;
    native.Enablei = glad_glEnablei;
    native.Disablei = glad_glDisablei;
    native.DeleteTextures = glad_glDeleteTextures;
    native.SamplerParameterf = glad_glSamplerParameterf;

    glad_glBindBuffer = ShimBindBuffer;
    glad_glBindFramebuffer = ShimBindFramebuffer;
    glad_glEnable = ShimEnable;
    glad_glDisable = ShimDisable;
    glad_glEnablei = ShimEnablei;
    glad_glDisablei = ShimDisablei;
    glad_glDeleteTextures = ShimDeleteTextures;
    glad_glSamplerParameterf = ShimSamplerParameterf;

    glad_glCreateBuffers = ShimCreateBuffers;
    glad_glCreateTextures = ShimCreateTextures;
    glad_glCreateSamplers = ShimCreateSamplers;
    glad_glCreateQueries = ShimCreateQueries;
    glad_glCreateRenderbuffers = ShimCreateRenderbuffers;
    glad_glCreateTransformFeedbacks = ShimCreateTransformFeedbacks;
    glad_glCreateProgramPipelines = ShimCreateProgramPipelines;

    glad_glNamedBufferData = ShimNamedBufferData;
    glad_glNamedBufferStorage = ShimNamedBufferStorage;
    glad_glNamedBufferSubData = ShimNamedBufferSubData;
    glad_glMapNamedBufferRange = ShimMapNamedBufferRange;
    glad_glFlushMappedNamedBufferRange = ShimFlushMappedNamedBufferRange;
    glad_glUnmapNamedBuffer = ShimUnmapNamedBuffer;
    glad_glCopyNamedBufferSubData = ShimCopyNamedBufferSubData;
    glad_glGetNamedBufferSubData = ShimGetNamedBufferSubData;
    glad_glClearNamedBufferSubData = ShimClearNamedBufferSubData;
    glad_glBindBuffersRange = ShimBindBuffersRange;
    glad_glBindVertexBuffers = ShimBindVertexBuffers;
    glad_glTextureBufferRange = ShimTextureBufferRange;

    glad_glTextureStorage2D = ShimTextureStorage2D;
    glad_glTextureStorage3D = ShimTextureStorage3D;
    glad_glTextureStorage3DMultisample = ShimTextureStorage3DMultisample;
    glad_glTextureSubImage2D = ShimTextureSubImage2D;
    glad_glTextureSubImage3D = ShimTextureSubImage3D;
    glad_glCompressedTextureSubImage2D = ShimCompressedTextureSubImage2D;
    glad_glCompressedTextureSubImage3D = ShimCompressedTextureSubImage3D;
    glad_glTextureParameteri = ShimTextureParameteri;
    glad_glTextureParameteriv = ShimTextureParameteriv;
    glad_glTextureView = ShimTextureView;
    glad_glBindTextureUnit = ShimBindTextureUnit;
    glad_glBindTextures = ShimBindTextures;
    glad_glBindSamplers = ShimBindSamplers;
    glad_glBindImageTextures = ShimBindImageTextures;
    glad_glGetTextureSubImage = ShimGetTextureSubImage;
    glad_glGetCompressedTextureSubImage = ShimGetCompressedTextureSubImage;
    glad_glClearTexImage = ShimClearTexImage;

    glad_glNamedFramebufferTexture = ShimNamedFramebufferTexture;
    glad_glNamedFramebufferTextureLayer = ShimNamedFramebufferTextureLayer;
    glad_glNamedFramebufferDrawBuffer = ShimNamedFramebufferDrawBuffer;
    glad_glNamedFramebufferDrawBuffers = ShimNamedFramebufferDrawBuffers;
    glad_glNamedFramebufferParameteri = ShimNamedFramebufferParameteri;
    glad_glBlitNamedFramebuffer = ShimBlitNamedFramebuffer;

    glad_glViewportIndexedf = ShimViewportIndexedf;
    glad_glScissorIndexed = ShimScissorIndexed;
    glad_glDepthRangeIndexed = ShimDepthRangeIndexed;
    glad_glClipControl = ShimClipControl;
    glad_glPolygonMode = ShimPolygonMode;
    glad_glPolygonOffsetClamp = ShimPolygonOffsetClamp;
    glad_glLogicOp = ShimLogicOp;
    glad_glClampColor = ShimClampColor;
    glad_glPointSize = ShimPointSize;
    glad_glAlphaFunc = ShimAlphaFunc;
    glad_glMaterialfv = ShimMaterialfv;
    glad_glEnableClientState = ShimEnableClientState;
    glad_glPrimitiveRestartIndex = ShimPrimitiveRestartIndex;
    glad_glTextureBarrier = ShimTextureBarrier;
    glad_glGetQueryObjecti64v = ShimGetQueryObjecti64v;

    glad_glDrawArraysInstancedBaseInstance = ShimDrawArraysInstancedBaseInstance;
    glad_glDrawElementsInstancedBaseInstance = ShimDrawElementsInstancedBaseInstance;
    glad_glDrawElementsInstancedBaseVertexBaseInstance =
        ShimDrawElementsInstancedBaseVertexBaseInstance;
    glad_glMultiDrawArraysIndirect = ShimMultiDrawArraysIndirect;
    glad_glMultiDrawElementsIndirect = ShimMultiDrawElementsIndirect;
    glad_glMultiDrawArraysIndirectCount = ShimMultiDrawArraysIndirectCount;
    glad_glMultiDrawElementsIndirectCount = ShimMultiDrawElementsIndirectCount;
    glad_glDrawTransformFeedback = ShimDrawTransformFeedback;
}

} // Anonymous namespace

bool Load(GLADloadproc load) {
    if (!gladLoadGLLoader(load)) {
        return false;
    }
    const auto* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    active = version && std::string_view{version}.starts_with("OpenGL ES");
    if (!active) {
        return true;
    }
    if (GLVersion.major < 3 || (GLVersion.major == 3 && GLVersion.minor < 2)) {
        LOG_ERROR(Render_OpenGL, "OpenGL ES 3.2 is required, the driver offers {}", version);
        return false;
    }
    // Every ES 3.2 entry point shares its name with a desktop one.
    gladLoadGLAllCoreLoader(load);
    LoadExtensions(load);
    GLint units{};
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &units);
    scratch_unit = std::max(units - 1, 0);
    Install();
    LOG_INFO(Render_OpenGL, "OpenGL ES {}.{} with {} extensions, desktop calls emulated",
             GLVersion.major, GLVersion.minor, extensions.size());
    return true;
}

bool IsActive() {
    return active;
}

std::string CapabilitiesJson() {
    static constexpr const char* NAMES[]{
        "GL_EXT_buffer_storage",
        "GL_EXT_clip_control",
        "GL_EXT_clip_cull_distance",
        "GL_EXT_depth_clamp",
        "GL_EXT_base_instance",
        "GL_EXT_multi_draw_indirect",
        "GL_EXT_texture_view",
        "GL_OES_texture_view",
        "GL_OES_viewport_array",
        "GL_EXT_shader_framebuffer_fetch",
        "GL_EXT_texture_compression_s3tc",
        "GL_EXT_texture_compression_rgtc",
        "GL_EXT_texture_compression_bptc",
        "GL_KHR_texture_compression_astc_ldr",
        "GL_EXT_texture_norm16",
        "GL_EXT_color_buffer_float",
        "GL_EXT_color_buffer_half_float",
        "GL_EXT_shader_io_blocks",
        "GL_EXT_gpu_shader5",
        "GL_EXT_shader_non_constant_global_initializers",
        "GL_KHR_shader_subgroup",
        "GL_OES_shader_image_atomic",
        "GL_EXT_shader_image_load_formatted",
        "GL_NV_polygon_mode",
        "GL_EXT_polygon_offset_clamp",
        "GL_EXT_clear_texture",
        "GL_EXT_disjoint_timer_query",
        "GL_EXT_sRGB_write_control",
        "GL_OES_texture_stencil8",
        "GL_KHR_debug",
    };
    nlohmann::json out = nlohmann::json::object();
    for (const char* name : NAMES) {
        out[name] = Has(name);
    }
    return out.dump();
}

std::string UnsupportedJson() {
    nlohmann::json out = nlohmann::json::object();
    for (size_t i = 0; i < skipped.size(); ++i) {
        if (const u64 count = skipped[i].load(std::memory_order_relaxed); count != 0) {
            out[std::string{SKIP_NAMES[i]}] = count;
        }
    }
    return out.dump();
}

} // namespace OpenGL::GLES
