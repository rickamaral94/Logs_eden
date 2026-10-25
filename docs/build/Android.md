# Android

## Dependencies

* [Android Studio](https://developer.android.com/studio)
* [NDK 27+ and CMake 3.22.1](https://developer.android.com/studio/projects/install-ndk#default-version)
* [Git](https://git-scm.com/download)

## WINDOWS ONLY - Additional Dependencies

* **[Visual Studio 2022 Community](https://visualstudio.microsoft.com/downloads/)** - **Make sure to select "Desktop development with C++" support in the installer. Make sure to update to the latest version if already installed.**
* **[Vulkan SDK](https://vulkan.lunarg.com/sdk/home#windows)** - **Make sure to select Latest SDK.**
  * A convenience script to install the latest SDK is provided in `.ci\windows\install-vulkan-sdk.ps1`.

## Cloning Eden with Git

```sh
git clone --recursive https://git.eden-emu.dev/eden-emu/eden.git
```

Eden by default will be cloned into:

* `C:\Users\<user-name>\eden` on Windows
* `~/eden` on Linux and macOS

## Building

1. Start Android Studio, on the startup dialog select `Open`.
2. Navigate to the `eden/src/android` directory and click on `OK`.
3. In `Build > Select Build Variant`, select `release` or `relWithDebInfo` as the "Active build variant".
4. Build the project with `Build > Make Project` or run it on an Android device with `Run > Run 'app'`.

## Building with Terminal

1. Download the SDK and NDK from Android Studio.
2. Navigate to SDK and NDK paths.
3. Then set ANDROID_SDK_ROOT and ANDROID_NDK_ROOT in terminal via
`export ANDROID_SDK_ROOT=path/to/sdk`
`export ANDROID_NDK_ROOT=path/to/ndk`.
4. Navigate to `eden/src/android`.
5. Then Build with `./gradlew assembleRelWithDebInfo`.
6. To build the optimised build use `./gradlew assembleGenshinSpoofRelWithDebInfo`.
7. You can pass extra variables to cmake via `-PYUZU_ANDROID_ARGS="-D..."`

Remember to have a Java SDK installed if not already, on Debian and similar this is done with `sudo apt install openjdk-17-jdk`.

### Script

A convenience script for building is provided in `.ci/android/build.sh`. On Windows, this must be run in Git Bash or MSYS2. This script provides the following options:

```txt
Usage: build.sh [-c|--chromeos] [-t|--target FLAVOR] [-b|--build-type BUILD_TYPE]
                [-h|--help] [-r|--release] [extra options]

Build script for Android.
Associated variables can be set outside the script,
and will apply both to this script and the packaging script.
bool values are "true" or "false"

Options:
    -c, --chromeos          Build for ChromeOS (x86_64) (variable: CHROMEOS, bool)
                            Default: false
    -r, --release           Enable update checker. If set, sets the DEVEL bool variable to false.
                            By default, DEVEL is true.
    -t, --target <FLAVOR>   Build flavor (variable: TARGET)
                            Valid values are: legacy, optimized, standard
                            Default: standard
    -b, --build-type <TYPE> Build type (variable: TYPE)
                            Valid values are: Release, RelWithDebInfo, Debug
                            Default: Debug

Extra arguments are passed to CMake (e.g. -DCMAKE_OPTION_NAME=VALUE)
Set the CCACHE variable to "true" to enable build caching.
The APK and AAB will be output into "artifacts".
```

Examples:

* Build legacy release with update checker:
  * `.ci/android/build.sh -r -t legacy`
* Build standard release with debug info without update checker for phones:
  * `.ci/android/build.sh -b RelWithDebInfo`
* Build optimized release with update checker for ChromeOS:
  * `.ci/android/build.sh -c -r -t optimized`

### OpenGL backend (Mesa Zink)

The Android build includes the desktop OpenGL 4.6 renderer. Android has no desktop OpenGL, so
the app loads Mesa's EGL with the Zink driver (OpenGL on top of Vulkan) from its own libraries.
Build them once before building the APK; the CI does this and caches the result:

```sh
sh .ci/android/build-mesa-zink.sh   # needs meson >= 1.4, ninja, flex, bison, patchelf, mako, pyyaml
```

The script cross-builds the Mesa tag in `MESA_VERSION` with the NDK, applies
`.ci/android/mesa/eden-zink.patch` and copies `libEGL_mesa.so`, `libgallium_dri.so` and their
helpers into `src/android/app/src/main/jniLibs/arm64-v8a/`. Without them the APK still builds;
choosing OpenGL then fails at boot with "Failed to load Mesa" in the log.

How it runs (`src/android/app/src/main/jni/emu_window/gl_context.cpp`):

- Zink uses the Vulkan driver Eden already loaded (system or custom through adrenotools); the
  patch takes its entry points from `EDEN_ZINK_VK_GIPA`/`EDEN_ZINK_VK_GDPA` instead of opening
  `libvulkan.so`. Mesa reads them once per process, so after changing the custom driver restart
  the app before using OpenGL again.
- Apps cannot open a DRM device, so the display is `EGL_PLATFORM_SURFACELESS_MESA` (the patch
  lets Zink start there without one). The context that composites renders into a pbuffer the size
  of the window; each frame is read into one of two pixel pack buffers and the previous one is
  copied to the `ANativeWindow` by the CPU. This costs one frame of latency and a copy of the
  window per frame: the backend is for compatibility checks and comparisons with Vulkan, not
  speed. Shader workers get their own contexts that share objects with it.
- The renderer needs OpenGL 4.6. Zink reaches it on Turnip; with a driver that lacks the needed
  Vulkan features the boot fails with "OpenGL 4.6 is not available".

The backend is chosen in Settings, Debug, API. Diagnostic captures record the OpenGL version,
vendor and renderer in the `opengl_device` section, and host frame intervals as with Vulkan;
figures that only the Vulkan renderer records (GPU timestamps, render passes, wait-for-idle,
pipeline timers) stay empty for OpenGL sessions.

### OpenGL ES driver (experimental)

Settings, Debug, "OpenGL driver" also offers the system's own OpenGL ES 3.2 driver, which needs
no Mesa and presents straight to the window (no CPU copy). It is aimed at GPUs whose Vulkan
driver is the weak point (Mali). The renderer is written for desktop OpenGL 4.6, so on ES
`video_core/renderer_opengl/gl_es_compat.cpp` points the desktop calls at ES extensions or
emulates them on ES calls (direct state access through a scratch texture unit and the copy
buffer binding, texture views, indexed viewports, multi draw with a count), the shader
recompiler emits `#version 320 es`, and the renderer's own GLSL is rewritten when it is
compiled (`TranslateToEs` in `gl_shader_util.cpp`).

What ES cannot do is skipped and counted. A capture lists the counts in the
`opengl_es_unsupported` sample and the driver's extensions in `opengl_device.extensions`, which
is what to read first when a game renders wrongly. Known gaps: no polygon mode, logic ops,
clip control, per-viewport state, transform feedback draws or base instance without the
matching extension; no 64-bit or warp intrinsics in shaders; MSAA and BC4 helper shaders and
the GPU ASTC decoder are not translated (ASTC is sampled natively where the driver has it).

### Additional Resources

<https://developer.android.com/studio/intro>
