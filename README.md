# PhiStudio

PhiStudio is a native, touch-first chart editor for Android, iPhone, and iPad.
It edits RPE and compatible rhythm-game chart packages directly on each
platform without Winlator or bundled Windows executables.

## Features

- RPE chart editing for notes, judge lines, BPM changes and events.
- Storyboard event editing.
- Import and conversion of RPE, official Phigros and legacy PEC charts.
- Local project library with explicit Save and package export.
- Native audio-synchronized preview.
- Large-chart memory and playback optimizations.
- Preservation of unsupported JSON fields and safe unknown package entries.

## Requirements

- Android 8.0 (API 26) or newer for the Android app.
- iOS or iPadOS 17.0 or newer for the Apple app.

## Build

The project requires JDK 17 and Android SDK 35.

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## iPhone and iPad

The native SwiftUI port, its Xcode project, build instructions, compatibility
matrix, and XCTest suite are in [`ios/`](ios/README.md).

## Installation

Download the APK from the GitHub Releases page and allow installation from your
browser or file manager. Back up important projects before editing them with a
new release.
