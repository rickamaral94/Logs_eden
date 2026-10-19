# Diagnostic captures (Android)

A diagnostic capture records Vulkan, CPU, memory and frame time data while you play. Use it to
report a graphical glitch, a crash or a performance problem with enough detail for someone else to
reproduce it.

Captures stay on your device. Nothing is sent anywhere unless you export a bundle and share it.

## Installing next to an official build

Diagnostics builds use their own package id (`dev.eden.eden_emulator.diagnostics`) and appear as
**Eden Diagnostics**, so they install alongside the official or nightly Eden without replacing it.
Android keeps the data of each app separate, so on first launch of Eden Diagnostics you need to:

- Install your keys and firmware again, and add your games folder.
- Copy saves over if you need them (Eden's save export/import), since saves are not shared.
- Expect the shader cache to start empty; the first run of a game compiles shaders again.

Builds are produced by passing `-PdiagnosticsBuild=true` to Gradle (or setting
`ORG_GRADLE_PROJECT_diagnosticsBuild=true`). Without it the package id is unchanged.

## Recording

1. Start the game and get close to the point where the problem happens.
2. Open the in-game menu and choose **Diagnostics**.
3. Pick a level:
   - **Standard**: samples every 2 seconds, lowest overhead.
   - **Detailed**: samples every second and also records PSS memory.
4. Leave **Measure GPU time** on (it records how long the GPU spent on each frame; turn it off
   only to compare its overhead).
5. Optionally describe where you are in the game (for example "chapter 3, first boss").
6. Tap **Start capture** and keep playing.
7. When you see the problem, open **Diagnostics** again and tap **Mark issue**. Pick what you saw
   (missing textures, flicker, stutter, ...) and add a short note. The marker uses the moment you
   opened the dialog, not the moment you finished typing.
8. Tap **Stop capture**. Closing the game also stops it. A capture stops by itself after 30
   minutes.

## Exporting

After stopping, the review screen opens. You can also find all captures in
**Settings → Diagnostic captures**, including captures interrupted by a crash.

Before anything is written you can choose:

- Include the Eden log: only the lines of this capture, plus a little labelled context before it.
- Include the GPU log, if the GPU logging setting produced one.
- Include the device model and build fingerprint.
- Include advanced GPU logger dumps (off by default; they can be large).

The preview shows the summary, every file in the bundle and how many personal values were
redacted. **Export and share** saves the zip in `diagnostics/exports` inside the Eden folder and
opens the share sheet.

If the game fails to start, use **Snapshot report** in the capture list to export the device,
driver and Vulkan information plus recent errors without a capture.

## Turnip performance warnings

To see why the Turnip driver falls back to slower paths, open **Settings → Freedreno** (global or
for the game), apply the **Diagnostics - Turnip perf warnings** preset, then start the game and a
capture. The driver's messages end up in `logs/driver_logcat.txt` and are summarized in the
report. The preset makes the driver do extra logging, so remove it (preset **Performance -
Default**) for normal play and do not compare FPS between runs with and without it.

## Comparing two drivers or builds

To compare driver A with driver B on the same scene:

1. Use the same game, game version, settings, resolution and save point for every run, and let
   the shader cache warm up first (play the scene once without capturing).
2. Alternate the runs: A, B, B, A, A, B, B, A, A, B (five pairs), each about 2-3 minutes of the
   same scene, with a few minutes of rest if the device gets warm.
3. Export every capture and run
   `python3 tools/diagnostics/eden_diag.py abba --a A1.zip A2.zip ... --b B1.zip B2.zip ...`.
   It refuses comparisons where something other than the driver changed and prints the median
   and spread of each metric.

To measure the overhead of the diagnostics themselves, alternate runs with GPU time on and off,
and runs at Standard and Detailed level, on the same scene, and compare FPS, frame times and
`sampler_cost_ms` the same way.

## What is never included

ROMs, firmware, keys, saves, passwords or tokens, console serial numbers and memory dumps. Storage paths, file URIs,
e-mail addresses and network addresses are removed from the bundle.

## Tips for useful reports

- Record the same scene twice: once with your usual settings and once after changing a single
  setting or driver. Comparing the two reports is far more useful than one long capture.
- Mention whether the shader cache was already built (the report shows how many pipelines were
  restored from disk).
- Keep captures short and mark the moment of the problem.
