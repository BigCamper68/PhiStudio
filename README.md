# PhiStudio

Language packs and contribution guide: [Internationalization](docs/i18n.md).
Java packages and source navigation: [Project structure](docs/structure.md).

PhiStudio is a native Android chart editor for RPE and compatible rhythm-game
chart packages. It runs directly on Android without Winlator.

## Features

- Edit notes, judge lines, BPM changes, events, storyboard, shaders and noise fields.
- Link parent lines and inherit rotation in Line List → Properties.
- Switch instantly between English and Simplified Chinese in Settings.
- Subdivide events using the beat grid, batch-edit values and clone events across
  lines with configurable timing, offsets and a preview. Undo/Redo is supported.
- Choose from 29 named easing curves with live graphs.
- Use touch selection, swipe scrolling and the Create / Edit / Arrange toolbar.
- Import RPE, official Phigros and legacy PEC charts; save projects and export packages.
- Preview charts with synchronized audio, custom hit sounds and shader effects.
- Render native Phigros chapter 9 noise fields in Preview, Playtest and video export,
  including animated subtraction areas, activation and touch effects.
- Show shaders and noise fields beneath the note grid, with independent switches
  in Settings (both enabled by default).
- Scale preview notes from 0.800 to 1.200 using Phira's note and hit-effect sizing.
- Play charts in full-screen Playtest with Phira-style judgement, multitouch,
  scoring, pause/retry, configurable start positions and countdowns.
- Export MP4 videos with resolution, FPS, bitrate, time range, volume and HUD controls.

## Noise field editor

The compact 34 dp bottom bar has two equally wide buttons: **mode selector** and **Menu**.
Choose **Event**, **Storyboard**, **Shaders** or **Noise Field** from the selector.

Noise Field uses the same workspace as Shaders: preview left, vertical beat grid
right and compact properties below. The toolbar shares the 38 dp buttons and the
full audio seek track. Preview hold/pan, body dragging, S/E resizing, header
scrolling and timeline pinch use the editor's normal speeds and behavior.

Each field has its own vertical grid: **Area**, **Active**, **Move**, **Scale** and
**Rotate**. The selector and arrows in the lower bar switch fields without moving
the playhead. **Locate** seeks to the selected field's appearance. **All fields**
opens an overview with a separate lane for each field, so simultaneous fields
never cover one another. Drag the headers horizontally; tap a field to open its
grid. All fields remain visible together in the preview.

Area edits lifetime times, bounds, subtraction and RGB color. Move/Scale/Rotate
show outgoing key intervals with native easing curves; the last key has one
handle. Exact values, anchors and the shared easing picker are below. Expand,
Compact and collapse work as in Shaders. Values use beats by default, while the
chart stores native seconds. Negative/offscreen bounds, negative times and
zero/negative scales are supported.

Valid edits save immediately through the shared chart history; there are no
Apply or Reset draft buttons. Undo/Redo treats continuous typing in one input
or changes in one open color palette as one operation. Invalid text stays
highlighted and keeps the last valid chart value. Undo restores complete
snapshots, including unknown JSON properties. Canceled handle gestures leave
the chart unchanged.

**Fields** offers search, type filters and an at-playhead snapshot. **Edges ⇄**
switches between bounds and center/size. Scale/Rotate include 3×3 screen anchor
presets. **Delete field/key** removes the selection with Undo support.
**More** contains copy/paste, duplicate, shifting, full-screen bounds, snapping,
guides and help. Preview guides do not enter Playtest or video export.

Color applies in every preview, Playtest and video export. It is stored as the
PhiStudio extension `phiStudioColor: [R, G, B]` (integer bytes 0–255) on the field;
the editable project JSON keeps this extension. **Native red** removes it and
restores the original material. Native charts without custom colors use the
unchanged Phigros shader path. Color never changes subtraction or touch blocking.

**Export package** automatically includes the Phira-Pro color representation when
fields have custom colors. It opens the normal save picker directly and preserves
the package's existing aspect-ratio settings. Exported packages reopen with the
original editable fields in Noise Field; imported workspaces immediately store
that native editor representation. Checkpoint 9 packages are recovered too,
including projects that checkpoint 9 saved with an empty field timeline.
Imported external Line2Area tracks without PhiStudio source data remain ordinary
RPE line/storyboard events. Native animation keys are stably sorted on save/export.
See [checkpoint 10](docs/checkpoints/1.0.9-checkpoint10.md) for the Android codec
regression, legacy recovery and verification.

Compatible color exports now align baked transforms with the native float clock,
preserve easing-table knots and use a finer curve approximation with a separate
float rounding floor. This reduces measured MULTIPHEX corner error without
changing native playback or losing checkpoint 9 project recovery. Noise Field
tabs and key actions have wider buttons; its footer starts with a compact field
list followed by wider previous/next buttons. See
[checkpoint 11](docs/checkpoints/1.0.9-checkpoint11.md) for measurements and limits.

## Playtest

In the full-screen **Preview**, tap a rendered note to select its source judge
line without seeking. The bottom strip shows the line number/name, source note
number and authored beat. Tap that strip or **Edit note** in the toolbar to pause
and open the exact note in the editor grid. A long press or mouse/stylus hover
shows the same source information without changing the active line. Holds can
be picked by their visible body or caps; dragging still scrubs the timeline.
See [checkpoint 12](docs/checkpoints/1.0.9-checkpoint12.md).

Open **Menu → Playtest**. Start from the beginning, the playhead, or a custom time
or beat. Countdown options are off, 2, 3 or 5 seconds. The last selections are
remembered. Playtest uses the current chart, including unsaved changes.
Pause offers Resume, Retry and Back to editor; exiting restores the playhead.
The system volume panel leaves playback running; backgrounding the activity pauses it.
Touching an active noise field also applies the music low-pass filter; hit sounds
keep their normal sound. Settings → Preview note scale affects all preview modes,
Playtest and exported video. It defaults to 1.000.

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
test data from the corresponding upstream sources. [Noise field validation](tools/noise-field/README.md)
documents the extracted shader reference, asset generation and host GPU comparison.
[Project loading measurements](tools/loading-audit/README.md) describe the library
discovery and chart-opening benchmark.

## Releases

Download the APK and source archive from [GitHub Releases](https://github.com/BigCamper68/PhiStudio/releases).
The release workflow validates, signs and publishes new versions pushed to `main`.
See the [1.0.9 changelog](.github/release-notes/v1.0.9.md).

Bundled third-party notices are in `app/src/main/assets/licenses`,
`app/src/main/assets/shaders` and `app/src/main/assets/noise-field/NOTICE.txt`.
