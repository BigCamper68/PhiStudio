# PhiStudio for iPhone and iPad

This directory contains the native SwiftUI port of PhiStudio. The iOS app shares
the RPE data model and editing semantics of the Android version, while using an
adaptive interface designed for touch, Apple Pencil, pointer, and external
keyboard workflows.

## Requirements

- macOS with Xcode 16 or newer
- iOS or iPadOS 17.0 or newer
- An Apple Developer team configured in Xcode for installation on a physical device
- Internet access on the first build so Xcode can resolve the pinned Swift packages

## Build and run

1. Open `PhiStudioIOS/PhiStudioIOS.xcodeproj`.
2. Wait for Swift Package Manager to resolve the three pinned dependencies.
3. Select the **PhiStudioIOS** target, open **Signing & Capabilities**, and choose
   your development team.
4. If Xcode reports that the bundle identifier is already used, replace
   `com.bigcamper68.PhiStudio` with an identifier owned by your team.
5. Select an iPhone or iPad simulator/device and press **Run**.
6. Run the test suite with **Product → Test**.

The shared `PhiStudioIOS` scheme builds both the application and the
`PhiStudioIOSTests` XCTest target.

## Supported content

| Content | Import | Edit | Export |
|---|---:|---:|---:|
| RPE JSON | Yes | Yes | RPE JSON inside PEZ |
| PEZ / ZIP chart package | Yes | Yes | Yes |
| Official Phigros v1 / v3 JSON | Convert to RPE | Yes | PEZ |
| Legacy PEC | Convert to RPE | Yes | PEZ |
| Ogg Vorbis music | Yes | Playback | Preserved |
| MP3, WAV, M4A, AAC music | Yes | Playback | Preserved |
| PNG, JPEG, WebP, BMP, GIF illustration | Yes | Preview | Preserved |

Package import rejects absolute paths, path traversal, symbolic links, duplicate
normalized paths, conflicting file/directory paths, oversized entries, and
oversized archives. Files that PhiStudio does not understand are retained in the
private project workspace and included again on export.

## Editor coverage

- Exact RPE beat fractions, BPM changes, metadata, judge lines, parent lines,
  event layers, notes, line events, storyboard events, note-control parsing, and
  attached HUD elements.
- Tap, Hold, Flick, and Drag note editing with side, alpha, size, speed, visible
  time, Y offset, tint, hit-effect tint, fake-note, and judge-area properties.
- Move X, Move Y, Rotate, Alpha, and Speed events with all 29 RPE easing types,
  easing windows, link groups, and cubic Bézier points.
- Storyboard Scale, Color, Paint, Text, Incline, and GIF tracks.
- Undo/redo, copy/cut/paste, mirrored paste, line duplication, split and
  continuity tools, deterministic batch edits, Curve Notes, and Complex Move
  expression generation.
- Chart diagnostics for time ranges, invalid holds, parent cycles, overlapping
  events, reserved layers, alpha/range problems, and invalid BPM changes.
- Audio-synchronized preview with RPE 1.7 speed behavior, inherited judge-line
  transforms, note-control evaluation, multi-hit highlighting, hit sounds,
  hit effects, storyboard transforms, and HUD preview.
- Autosave recovery, explicit save, project duplication/deletion, resource
  replacement, Files app import, and PEZ export.

On iPad the editor mirrors the Android workspace: a 70/30 note-and-event canvas,
the seven-button transport bar, Create/Edit/Arrange control pages, and the
two-column project/chart menu. The timeline and preview use the original Android
note and hit-effect texture atlas. On iPhone the same controls remain available
in a compact scrolling dock and tools move into sheets. Drag the timeline to
seek, pinch to change its beat range, and tap to select or place an item.

The realtime evaluator keeps immutable BPM, event, speed, note-timing, combo,
and hit-sound indexes for the current chart revision. Illustration and texture
assets are decoded once, and hit sounds use prepared player pools rather than
allocating an audio player during every playback frame.

## Architecture

- `PhiStudioIOS/Core` — loss-preserving RPE models, converters, renderer,
  diagnostics, math expressions, and editing operations.
- `PhiStudioIOS/Services` — project storage, secure ZIP package handling, audio,
  settings, autosave, history, and application state.
- `PhiStudioIOS/Views` — adaptive SwiftUI project browser, timeline, preview,
  inspector, generators, batch tools, settings, and diagnostics.
- `PhiStudioIOSTests` — model, converter, evaluator, security, diagnostics, and
  editor-operation tests.

The app stores projects under its private Application Support container. Use **Export PEZ**
before deleting the app or moving work between devices.

## Dependencies

The Xcode project pins:

- ZIPFoundation 0.9.19 for ZIP/PEZ read and write
- ogg binary XCFramework 0.1.3
- vorbis binary XCFramework 0.1.2 for native Ogg Vorbis decoding

To add or remove Swift files, run:

```sh
cd ios/PhiStudioIOS
node scripts/generate_xcode_project.js
```

The generator is deterministic and uses only the Node.js standard library.
