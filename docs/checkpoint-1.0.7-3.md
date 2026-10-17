# PhiStudio 1.0.7-checkpoint3

Event Clone now uses named destination lines, separate timing and value controls,
and a live result preview. All easing selectors use the same named list and graph;
curve thumbnails are also shown in each dropdown row.

## Event Clone

Select one or more ordinary events and open **Edit → Event Clone**.

1. Review the source line, layer, event count and beat range. **Copy — keep
   originals** is the default; **Move — remove originals** is explicit.
2. Use **Choose lines** to select destinations by name or line number. The picker
   supports search, pages of 12 lines, All other lines, and Clear. Cancelling the
   picker keeps the current draft. Selected lines retain their chosen order;
   use the up/down buttons to change it. Long destination lists can be expanded.
3. Choose **Same time on every line** or **Shift each following line**. The first
   destination uses the source time. Later destinations add the entered beat
   shift cumulatively. Positive shifts go later; negative shifts go earlier,
   provided the resulting event times stay valid. Decimal and rational beat
   input remain supported.
4. **Adjust values (optional)** exposes only the event types in the source
   selection. Each type can keep its values, add a constant offset, or progress
   from a first-line offset to a last-line offset using a named easing curve.
   Separate optional fields provide repeating multipliers and random variation.
   Speed values remain unchanged. There are no U.L.E.D. tuple fields.
5. Review the count, destination order, shifted beat ranges and value offsets.
   Preview plans the complete operation without editing the chart. It checks
   existing-event conflicts, generated-event conflicts and the 4096-event limit.
   Errors appear in the form and disable Apply. Random values use one stable seed
   for the draft, so the preview matches the applied copies.

Only the first 12 destination details are printed in the preview; validation
covers every destination. The existing limit of 256 destination lines remains.
Copies retain source durations, easing windows, Bézier curves and JSON metadata.
The selected offset easing controls variation *between destination lines*, not
an event's original animation curve. The complete operation uses one Undo/Redo.

Clone and Batch edit open in their originating workbench tab. Opening the forms
or destination picker no longer switches to Menu and clears selected events.
Clone checks that its source chart, line, layer and selection are still current
before applying, preventing a stale form from modifying a different selection.

## Easing everywhere

The common selector is used by ordinary event properties, Storyboard properties,
Batch edit, Event Clone offsets, both Complex Move axes, Curve Notes and shader
parameter animation. All 29 choices have names and a live graph; the dropdown
also displays a graph beside each name. Internal chart IDs remain unchanged.

Window and custom Bézier editing remain available where supported. Graphs reflect
window edits and preserve equal units on both axes. Back/Elastic overshoot is
fitted into the preview rather than clipped away. Shader previews evaluate the
shader playback easing with its current window.

In **Batch edit → Easing type**, choose a curve directly. Applying replaces
custom Bézier and resets the easing window to the selected full curve; times and
endpoint values are preserved. Undo restores the previous curve, window and
Bézier state. Numeric value progression, arithmetic edit modes and repeating
sequences remain available for the other batch fields. Speed easing stays fixed
to Linear.

## Build and validation

Version code **83**, application ID **com.xpe.mobile.checkpoint**, debug signing.
The APK updates checkpoint1/checkpoint2 and keeps the checkpoint project library.

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 ./gradlew \
  --no-daemon --offline \
  -Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2 \
  -PcheckpointBuild=true -I tools/termux-tests.gradle \
  testDebugUnitTest lintDebug assembleDebug
```

Regression coverage includes named line selection, search/pagination and order,
copy/move, positive/negative time shifts, value profiles, deterministic variation,
stale-selection protection, atomic history and form reachability at 640×240,
800×360 and 1200×700. Easing coverage checks every dropdown curve, overshoot,
window updates, tool integration and actual property/batch application.

The ARM64 profile excludes 23 unsupported desktop native-graphics tests. Optional
external-fixture skips and final build results are recorded in the packaged
reports. Robolectric validates form layout and behavior; physical-device touch
feel and GPU appearance are not measured by these tests. Private chart packages
and signing keys are not included in the source archive.

Validation completed: **882 tests, 867 passed, 15 skipped, no failures or errors**.
The full run includes **19 new UI regression tests**, all passing.
Lint reports **0 errors and 20 unused-resource warnings**.
`assembleDebug` completed successfully.
