// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2014 Citra Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <string>
#include <string_view>
#include <vector>
#include <glad/glad.h>

#include "common/logging.h"
#include "common/settings.h"
#include "video_core/renderer_opengl/gl_es_compat.h"
#include "video_core/renderer_opengl/gl_shader_util.h"

namespace OpenGL {

namespace {

constexpr std::string_view ES_PRECISION{
    "precision highp float;precision highp int;precision highp sampler2D;precision highp "
    "sampler3D;precision highp samplerCube;precision highp sampler2DArray;precision highp "
    "sampler2DShadow;precision highp sampler2DArrayShadow;precision highp samplerCubeShadow;"
    "precision highp isampler2D;precision highp usampler2D;precision highp usampler2DArray;"
    "precision highp usampler3D;precision highp image2D;precision highp image2DArray;"
    "precision highp image3D;precision highp uimage2D;precision highp uimage2DArray;"
    "precision highp uimage3D;precision highp iimage2D;\n"};

bool IsIdentifierChar(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

/// Rewrites a desktop GLSL source for GLSL ES 3.20: the version line, desktop-only extensions,
/// default precisions and function-local `const` (ES needs constant initializers there).
/// Sources already written for ES (the shader recompiler's) are returned as they are.
std::string TranslateToEs(std::string_view code) {
    if (code.starts_with("#version 320 es")) {
        return std::string{code};
    }
    std::string out;
    out.reserve(code.size() + ES_PRECISION.size() + 64);
    out += "#version 320 es\n";
    std::string body;
    body.reserve(code.size());
    int depth = 0;
    size_t line_start = 0;
    while (line_start < code.size()) {
        size_t line_end = code.find('\n', line_start);
        if (line_end == std::string_view::npos) {
            line_end = code.size();
        }
        std::string_view line = code.substr(line_start, line_end - line_start);
        line_start = line_end + 1;
        const size_t first = line.find_first_not_of(" \t");
        const std::string_view trimmed =
            first == std::string_view::npos ? std::string_view{} : line.substr(first);
        if (trimmed.starts_with("#version")) {
            continue;
        }
        if (trimmed.starts_with("#extension")) {
            if (trimmed.find("GL_ARB_") == std::string_view::npos &&
                trimmed.find("GL_NV_") == std::string_view::npos &&
                trimmed.find("GL_AMD_") == std::string_view::npos) {
                out += line;
                out += '\n';
            }
            continue;
        }
        for (size_t i = 0; i < line.size(); ++i) {
            const char c = line[i];
            if (c == '{') {
                ++depth;
            } else if (c == '}') {
                --depth;
            } else if (depth > 0 && line.substr(i).starts_with("const ") &&
                       (i == 0 || !IsIdentifierChar(line[i - 1]))) {
                i += 5;
                continue;
            }
            body += c;
        }
        body += '\n';
    }
    out += ES_PRECISION;
    out += body;
    return out;
}

} // Anonymous namespace

static OGLProgram LinkSeparableProgram(GLuint shader) {
    OGLProgram program;
    program.handle = glCreateProgram();
    glProgramParameteri(program.handle, GL_PROGRAM_SEPARABLE, GL_TRUE);
    glAttachShader(program.handle, shader);
    glLinkProgram(program.handle);
    glDetachShader(program.handle, shader);
    if (!Settings::values.renderer_debug) {
        return program;
    }
    GLint link_status{};
    glGetProgramiv(program.handle, GL_LINK_STATUS, &link_status);

    GLint log_length{};
    glGetProgramiv(program.handle, GL_INFO_LOG_LENGTH, &log_length);
    if (log_length == 0) {
        return program;
    }
    std::string log(log_length, 0);
    glGetProgramInfoLog(program.handle, log_length, nullptr, log.data());
    if (link_status == GL_FALSE) {
        LOG_ERROR(Render_OpenGL, "{}", log);
    } else {
        LOG_WARNING(Render_OpenGL, "{}", log);
    }
    return program;
}

static void LogShader(GLuint shader, std::string_view code = {}) {
    GLint shader_status{};
    glGetShaderiv(shader, GL_COMPILE_STATUS, &shader_status);
    if (shader_status == GL_FALSE) {
        LOG_ERROR(Render_OpenGL, "Failed to build shader");
    }
    GLint log_length{};
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &log_length);
    if (log_length == 0) {
        return;
    }
    std::string log(log_length, 0);
    glGetShaderInfoLog(shader, log_length, nullptr, log.data());
    if (shader_status == GL_FALSE) {
        LOG_ERROR(Render_OpenGL, "{}", log);
        if (!code.empty()) {
            LOG_INFO(Render_OpenGL, "\n{}", code);
        }
    } else {
        LOG_WARNING(Render_OpenGL, "{}", log);
    }
}

OGLProgram CreateProgram(std::string_view source, GLenum stage) {
    OGLShader shader;
    shader.handle = glCreateShader(stage);

    std::string translated;
    std::string_view code = source;
    if (GLES::IsActive()) {
        translated = TranslateToEs(source);
        code = translated;
    }
    const GLint length = static_cast<GLint>(code.size());
    const GLchar* const code_ptr = code.data();
    glShaderSource(shader.handle, 1, &code_ptr, &length);
    glCompileShader(shader.handle);
    if (Settings::values.renderer_debug) {
        LogShader(shader.handle, code);
    }
    return LinkSeparableProgram(shader.handle);
}

OGLProgram CreateProgram(std::span<const u32> code, GLenum stage) {
    OGLShader shader;
    shader.handle = glCreateShader(stage);

    glShaderBinary(1, &shader.handle, GL_SHADER_BINARY_FORMAT_SPIR_V_ARB, code.data(),
                   static_cast<GLsizei>(code.size_bytes()));
    glSpecializeShader(shader.handle, "main", 0, nullptr, nullptr);
    if (Settings::values.renderer_debug) {
        LogShader(shader.handle);
    }
    return LinkSeparableProgram(shader.handle);
}

OGLAssemblyProgram CompileProgram(std::string_view code, GLenum target) {
    OGLAssemblyProgram program;
    glGenProgramsARB(1, &program.handle);
    glNamedProgramStringEXT(program.handle, target, GL_PROGRAM_FORMAT_ASCII_ARB,
                            static_cast<GLsizei>(code.size()), code.data());
    if (Settings::values.renderer_debug) {
        const auto err = reinterpret_cast<const char*>(glGetString(GL_PROGRAM_ERROR_STRING_NV));
        if (err && *err) {
            if (std::strstr(err, "error")) {
                LOG_CRITICAL(Render_OpenGL, "\n{}", err);
                LOG_INFO(Render_OpenGL, "\n{}", code);
            } else {
                LOG_WARNING(Render_OpenGL, "\n{}", err);
            }
        }
    }
    return program;
}

} // namespace OpenGL
