# PhiStudio

PhiStudio is a native, touch-first Android chart editor for RPE and compatible
rhythm-game chart packages. It runs directly on Android without Winlator or
bundled Windows executables.

## Features

- RPE chart editing for notes, judge lines, BPM changes and events.
- Persistent right-hand workbench with Events / Storyboard / Shaders / Menu tabs.
- Shader editing
- Synchronized, horizontally scrollable storyboard lanes with direct touch editing.
- Embedded tool/property forms and a compact menu with centered page titles.
- Narrower workbench with scrollable fields/actions and coordinated nested list scrolling.
- Create / Edit / Arrange controls below the note grid, on the left.
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

## Installation

Download the APK from the GitHub Releases page and allow installation from your
browser or file manager. Back up important projects before editing them with a
new release.
