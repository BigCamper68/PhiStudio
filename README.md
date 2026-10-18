# PhiStudio

Language packs and contribution guide: [Internationalization](docs/i18n.md).

PhiStudio is a native Android chart editor for RPE and compatible rhythm-game
chart packages. It runs directly on Android without Winlator.

## Features

- Edit notes, judge lines, BPM changes, events, storyboard and shaders.
- Subdivide events using the beat grid, batch-edit values and clone events across
  lines with configurable timing, offsets and a preview. Undo/Redo is supported.
- Choose from 29 named easing curves with live graphs.
- Use touch selection, swipe scrolling and the Create / Edit / Arrange toolbar.
- Import RPE, official Phigros and legacy PEC charts; save projects and export packages.
- Preview charts with synchronized audio, custom hit sounds and shader effects.
- Play charts in full-screen Playtest with Phira-style judgement, multitouch,
  scoring, pause/retry, configurable start positions and countdowns.
- Export MP4 videos with resolution, FPS, bitrate, time range, volume and HUD controls.

## Playtest

Open **Menu → Playtest**. Start from the beginning, the playhead, or a custom time
or beat. Countdown options are off, 2, 3 or 5 seconds. The last selections are
remembered. Playtest uses the current chart, including unsaved changes.
Pause offers Resume, Retry and Back to editor; exiting restores the playhead.

## Build

Requires JDK 17 and Android SDK / Build Tools 35. The app supports Android 8.0+
(API 26) in landscape orientation.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Add `-PcheckpointBuild=true` for a separate `com.xpe.mobile.checkpoint` app.

For a signed release, set `PHISTUDIO_KEYSTORE_PATH` (PKCS12),
`PHISTUDIO_KEYSTORE_PASSWORD`, `PHISTUDIO_KEY_ALIAS` and `PHISTUDIO_KEY_PASSWORD`:

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`.
Without signing variables, the release APK is unsigned. Keep keys and passwords
outside the repository.

The optional `tools/termux-tests.gradle` profile excludes desktop native-graphics
tests that cannot run on an ARM64 Termux host. GitHub Actions runs the full suite.
`tools/phira-fonts` and `tools/prpr-audit` regenerate font metrics and reference
test data from the corresponding upstream sources.

## Releases

Download the APK and source archive from [GitHub Releases](https://github.com/BigCamper68/PhiStudio/releases).
The release workflow validates, signs and publishes new versions pushed to `main`.
See the [1.0.7 changelog](.github/release-notes/v1.0.7.md).

Bundled third-party notices are in `app/src/main/assets/licenses` and
`app/src/main/assets/shaders`.
