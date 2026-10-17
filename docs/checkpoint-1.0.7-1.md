# PhiStudio 1.0.7-checkpoint1

This checkpoint closes the missing density-based event subdivision workflow used
to prepare dense movement, opacity and speed effects. `Split here` retains the
single cut at the cursor. `Subdivide` creates all cuts in one undoable operation.

## Editing workflow

1. Open **Menu → Settings** and set **Subdivisions per beat-grid interval**.
   The step in beats is `1 / (beat-grid divisions × density)`.
   Grid 4 with density 8 gives a 1/32 beat step; density 4 gives 1/16.
2. Select one normal event, or several with **Edit → Box+**.
3. Choose **Edit → Subdivide**. The lower action row has paging arrows when
   the controls do not fit. When the snapped cursor is inside an event, it
   anchors the cuts in both directions; otherwise the event start anchors them.
   Original boundaries are retained, including short first/last segments.
4. All resulting parts remain selected. Use **Batch edit** to calculate values,
   or **Event Clone** to copy the group to other lines.

For a 4-beat event with grid 4, density 4 produces 64 parts and density 8 produces
128 parts. These are time-grid operations; no source chart or preset is required.

**Batch edit → Start and end values → To** assigns the calculated number to
both endpoints of each event, making constant steps in one operation. For an
alternating opacity mask, use Alpha, bounds 1/1, periodic sequence `0 -1`,
disturbance 0. Standard profile sampling includes both endpoint values.
For continuous movement, edit endpoint values and then use **Stick events**.

Batch edit works with a single selected note or event. Event Clone also works
with a single selected event. The batch event-type filter starts with the type
of the selected event instead of always starting with MoveX.

## Data and history

- Applies to the normal MoveX/MoveY/Rotate/Alpha/Speed tracks on layers 0–3.
- Uses the persisted cut-density setting; decimal densities and rational beat
  boundaries are supported without accumulating floating-point time drift.
- Linked X/Y events are subdivided together, including when only one axis is
  selected. Selecting both axes does not duplicate the operation.
- Preserves standard easing windows, link groups and unknown JSON fields.
  Easing slices use elapsed chart time so BPM changes preserve the curve.
- The operation is fully planned before mutation. Invalid input, overlaps,
  missing X/Y pairs and exceeding 4096 resulting parts reject the whole operation.
- Undo restores the original events, their identities, source track order and
  original selection; Redo restores the generated selection.

Custom Bézier curves and imported nonlinear Speed curves with unequal endpoints
are currently rejected atomically: their source playback cannot safely be encoded
by the existing editor easing-window representation. A standard easing slice
whose equal endpoints would erase an interior excursion is also rejected; a finer
density can avoid that case. Constant events can be subdivided regardless of their
unused curve metadata. Storyboard subdivision is outside this checkpoint.

## Build and validation

Version code: **81**. The optional `-PcheckpointBuild=true` build property changes
only the debug application ID/label, so the APK installs alongside the release.
It uses debug signing and has its own project library. Import a chart package to
test it; use export to transfer chart work between applications.

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 ./gradlew \
  --no-daemon --offline \
  -Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2 \
  -PcheckpointBuild=true -I tools/termux-tests.gradle \
  testDebugUnitTest lintDebug assembleDebug
```

The checkpoint adds 17 tests: 11 for exact subdivision, curve preservation and
history, 4 for editor actions and selection restoration, and 2 for paired endpoint
batch edits. The existing ARM64 profile excludes 23 unsupported desktop native
graphics tests; those exclusions are not counted as passed tests.

Validation: the full ARM64 suite completed with **842 tests: 827 passed,
15 skipped, no failures or errors**. The skips require optional external fixtures.
The editor tests are also rerun after the final resource wording change.
`lintDebug` has no errors and only the 24 existing unused-resource warnings;
`assembleDebug` produces a signed checkpoint APK.

Private, external chart data and generated reports are kept outside the source
archive. A local probe on the supplied fragments of Astaroth and Alter Strife
subdivided 1532 source events into 15880 parts, compared 79400 sampled values
(maximum difference 1.71e-13), and checked restoration of source identities.
This numerical check does not establish identical GPU output on a phone.
