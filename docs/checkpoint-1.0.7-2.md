# PhiStudio 1.0.7-checkpoint2

Subdivide now uses the current beat grid directly. The lower action row scrolls
with a horizontal finger swipe. Grid taps select on release, and swipes pan the
timeline while preserving the previous selection.

## Subdivision

1. In **Arrange**, choose the beat-grid division. Division 32 gives a 1/32 beat
   step; a four-beat event produces 128 parts. Division 16 produces 64 parts.
2. Select one or more normal events and use **Edit → Subdivide**.
3. The generated parts remain selected for **Batch edit** or **Event Clone**.
   One Undo restores the original events and selection; Redo restores the parts.

The separate cut-density setting has been removed from the settings dialog,
model and calculation. Its obsolete stored value is ignored and removed on the
next settings save. The beat-grid setting is the only subdivision control.
`Split here` still makes one cut at the playhead.

Cuts remain anchored at the snapped cursor when it is inside the event, and at
the event start otherwise. Short boundary parts, exact rational beats, standard
easing windows, BPM changes, X/Y pairs and unknown JSON fields are preserved.
The existing limit of 4096 resulting parts and atomic validation remain in place.
Custom nonconstant Bézier and imported nonlinear Speed curves remain unsupported.
Subdivision applies to ordinary events on layers 0–3; Storyboard has its own
single-point Split action.

## Lower toolbar

The tabs **Create / Edit / Arrange** remain fixed above the action row. Swipe the
row left or right to reveal more actions. Scrolling has inertia and a position
indicator; touching a moving row stops it without running an action. A tap runs
a button only on release. Swipes, cancellation, a second pointer and releasing
outside the button do not run it. Hit targets are clipped to the visible row.
Changing tabs or selecting a new object returns to the start of the row.

- **Create**: Tap, Hold, Drag, Flick, Event, Select.
- **Edit**: Properties for a single object, Copy/Paste, Batch edit and Subdivide
  for events, then selection/move/clone/split tools, Cut/Delete and specialized
  endpoint, note and linking actions. Clear is last. Storyboard puts Properties,
  Copy/Paste and Box+ before Split/Glue and Cut/Delete.
- **Arrange**: beat grid, line, layer, X grid, X/Y binding.

## Grid gestures

Normal note/event, Storyboard, shader-effect and shader-parameter timelines
recognize a tap only after release. Moving beyond Android's touch tolerance
turns the gesture into scrolling and preserves the previous selection. A swipe
starting on an unselected endpoint also scrolls instead of selecting or resizing
that object. Returning the finger to its starting position does not turn a swipe
back into a tap. Cancel and pinch discard pending selections.

Tap/Drag/Flick placement also waits for release, so a swipe does not add a note.
Explicit **Box+**, Hold/Event creation, moving already selected notes in **Move**
mode, and dragging the handles of already selected objects keep their editing
behavior. To resize an unselected event, tap to select it before dragging its
start/end handle. Vertical swipes on selected event bodies scroll normally.

## Build and validation

Version code **82**, application ID **com.xpe.mobile.checkpoint**, debug signing.
The APK updates checkpoint1 and keeps its project library. It remains separate
from the release application and its library.

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 ./gradlew \
  --no-daemon --offline \
  -Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2 \
  -PcheckpointBuild=true -I tools/termux-tests.gradle \
  testDebugUnitTest lintDebug assembleDebug
```

The ARM64 profile excludes 23 desktop native-graphics tests; these are not counted
as passed. Tests requiring optional external fixtures can be skipped. The APK
and reports in the checkpoint package record the completed validation results.
Robolectric checks gesture dispatch and state changes; these checks do not replace
a physical-device evaluation of touch feel or GPU rendering.

Validation completed: **863 tests, 848 passed, 15 skipped, no failures or errors**.
After the final cleanup, **72 UI tests** passed again. Lint reports
**0 errors and 21 existing unused-resource warnings**; `assembleDebug` and APK
signature verification passed. The signing certificate matches checkpoint1.
The external chart-fragment probe checked 1532 source events, 15880 parts and
79400 sampled values, with maximum error 1.71e-13 and source identity restored
by Undo. Private chart files are not part of the source archive.
