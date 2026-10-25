// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string>

#include <glad/glad.h>

// OpenGL ES 3.2 support for the desktop OpenGL renderer. The renderer is written against
// desktop OpenGL 4.6 with direct state access; on an ES context the entry points ES lacks are
// pointed at the ES extension that provides them (OES/EXT suffix) or at an emulation built on
// ES calls, so the renderer runs unchanged. Operations ES cannot express are skipped and
// counted; UnsupportedJson() lists them for the diagnostics capture.
namespace OpenGL::GLES {

/// Loads every entry point through load and installs the emulation when the current context
/// is OpenGL ES. Returns false when no context is current or it is older than ES 3.2.
bool Load(GLADloadproc load);

/// True after Load() found an OpenGL ES context.
[[nodiscard]] bool IsActive();

/// ES extension names the context exposes that the renderer can use, as a JSON object of
/// booleans, and the skipped operations with their counts.
[[nodiscard]] std::string CapabilitiesJson();
[[nodiscard]] std::string UnsupportedJson();

} // namespace OpenGL::GLES
