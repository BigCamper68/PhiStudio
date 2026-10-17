# PhiStudio

PhiStudio is a native, touch-first Android chart editor for RPE and compatible
rhythm-game chart packages. It runs directly on Android without Winlator or
bundled Windows executables.

## Features

- RPE chart editing for notes, judge lines, BPM changes and events.
- Subdivide selected normal events using the current beat grid, with XY binding and atomic Undo/Redo.
- Batch editing and Event Clone for single or multiple objects; paired endpoint editing for stepped effects.
- Event Clone with named line selection, ordered destinations, separate timing/offset controls and a live result preview.
- Named easing lists with curve thumbnails and live graphs across every easing editor.
- Persistent right-hand workbench with Events / Storyboard / Shaders / Menu tabs.
- Shader editing
- Synchronized, horizontally scrollable storyboard lanes with direct touch editing.
- Embedded tool/property forms and a compact menu with centered page titles.
- Narrower workbench with scrollable fields/actions and coordinated nested list scrolling.
- Create / Edit / Arrange tabs with a horizontally swipeable action row below the note grid.
- Tap-on-release selection and swipe scrolling across note, event, storyboard and shader grids.
- Compact two-row footer without selection hints, four-row BPM/line lists and equal-height actions.
- Live selection properties without duplicate pages; Event Back returns to Events.
- Identical easing-curve geometry and numeric typography in ordinary/storyboard timelines.
- Matching note/event/storyboard beat-grid strokes and event-column widths.
- Continuous equal-thickness beat cursor across note, event and storyboard grids.
- Reliable nested list gestures, 29-type Complex Move easing dropdowns and pinch-only zoom.
- Storyboard Box+ selection and ordinary event-style touch creation and playback seeking.
- Import and conversion of RPE, official Phigros and legacy PEC charts.
- Local project library with explicit Save and package export.
- Native audio-synchronized preview.
- Offline MP4 export through Menu → Export chart to MP4: PhiStudio rendering and shaders,
  H.264/AAC, resolution/FPS/bitrate, music/hit volumes, time range and HUD controls.
- Hardware Canvas to external GL textures for shader playback, without full-frame CPU bitmap uploads.
- Shaders workbench: Preview on the left, vertical effects/keyframe timeline on the right, parameters below.
- prpr GLSL presets/imports, typed uniforms/color picker, 29 named easings, independent FX BPM, Undo/Redo and project/package persistence.
- Events / Storyboard / Shaders / Menu navigation preserves drafts and playback position.
- Live uniform edits reuse compiled GPU programs.
- Phira-matched preview HUD fonts, glyph metrics, anchors and attachUI transforms.
- Phira-matched multiline storyboard text with shared fonts and bounded layout caching.
- Large-chart memory and playback optimizations.
- Preservation of unsupported JSON fields and safe unknown package entries.

## Requirements

- Android 8.0 (API 26) or newer.
- Landscape orientation.

## Build

The project requires JDK 17 and Android SDK 35.

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For a checkpoint APK that installs alongside the release, add `-PcheckpointBuild=true`.
It uses package `com.xpe.mobile.checkpoint` and the label **PhiStudio Checkpoint**.
See [checkpoint 1.0.7-checkpoint3](docs/checkpoint-1.0.7-3.md) for Event Clone and easing controls,
and [checkpoint2](docs/checkpoint-1.0.7-2.md) for subdivision, toolbar and gestures.

Release builds use the existing PhiStudio signing certificate. Set
`PHISTUDIO_KEYSTORE_PATH` (PKCS12), `PHISTUDIO_KEYSTORE_PASSWORD`,
`PHISTUDIO_KEY_ALIAS` and `PHISTUDIO_KEY_PASSWORD`, then run
`./gradlew clean testDebugUnitTest lintRelease assembleRelease`.
Without these variables Gradle produces an unsigned release APK.
Keep keystores and passwords outside the repository.

The release workflow publishes **PhiStudio 1.0.6** as tag `v1.0.6` after a
push to `main`, using the existing Actions signing secrets. It attaches the
signed APK, a source archive and SHA-256 checksums. Release notes are stored in
`.github/release-notes/v1.0.6.md`.

See [architecture](ARCHITECTURE.md), [rendering compatibility](docs/rendering-parity.md)
and [release validation](docs/validation.md) for implementation and test details.

## Video export

Open a chart and choose **Menu → Export chart to MP4**. The export
uses the current chart and shader drafts. Set resolution (including custom sizes
and the player preset), 30/60 FPS, bitrate, start/end in seconds, audio volumes,
shaders and HUD. Rendering pauses editor playback and runs offline; performance
does not change the video's timing. Keep PhiStudio open until it finishes.

After rendering, select a destination in Android's file picker. Cancelling the
picker keeps the completed MP4 available from the export button for the rest of
the session. Music and hit sounds use the preview's chart/package offsets and
hit-sound settings, including custom hit sounds. A selected time range retains
hit-sound tails that cross its start; audio after the song ends is silent.

Export uses the device's Android H.264 encoder. Unsupported size/FPS/bitrate
combinations produce an error suggesting a smaller preset. Temporary storage is
needed for decoded PCM, intermediate tracks and the final MP4. A failing chart
shader or missing custom hit sound produces an export error; their respective
switches allow an export without that feature.

## Installation

Download the APK from the GitHub Releases page and allow installation from your
browser or file manager. Back up important projects before editing them with a
new release.
