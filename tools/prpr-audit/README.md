# prpr comparison harness

Pinned references:
- PhiStudio `329404cbe9144b3cc15ddaa6264a38e16be53435` (version 1.0.4, code 71).
- TeamFlos/phira `1559e58bdfc5b0e57f3e864d307d33e666d1e8f6`.

`generate_oracle.py /path/to/phira` extracts scalar tween functions and
`speed_segment_tween` from that checkout. Only unrelated graphical Tweenable
implementations and dependency imports are replaced/removed. It compiles using
rustc, without Cargo, an audio device or OpenGL. The resulting `oracle.rs` is
upstream GPL-3.0 code; it is a validation artifact, not linked into the Android app.
The script and CSV are included; generated Rust and executables are excluded
from the source archive. Reproduction requires the pinned upstream checkout.

`app/src/test/resources/prpr/scalar-oracle.csv` contains 1,218 values:
29 named easing types x 2 windows x 7 positions x 3 calculations (easing,
pre-170 speed on the new speed path, 170+ speed on the new speed path).
`PrprScalarOracleTest` compares independent Java playback calculations to these
Rust outputs, and also evaluates the unchanged 1.0.4 scalar model as a baseline.
Tolerance: 0.0002 + abs(reference) * 0.0000002, in normalized scalar units.
Large legacy values are real results of prpr's finite-difference endpoint
algorithm, so the test uses an explicit relative component as well as an
absolute one. This corpus does not measure entire-chart or pixel equivalence.

`PrprParityRegressionTest` covers BPM transitions, speed gaps/overlap/fallback,
Bezier windows, controls, extended events, alpha, visibility, signed size and
save/reopen. `PrprImportParityTest` checks official/PEC conversion.
`PrprCanvasGeometryTest` records real renderer draw commands under Robolectric
LEGACY: hold caps, mirror axes, uniform scaling and paint radius. It is not a
GPU screenshot test.

`benchmark.py` compiles the original evaluator from the pinned 1.0.4 commit into an isolated
validation directory, then runs the same JVM benchmark against original and
current code. It requires the two supplied chart ZIPs. Source archive excludes
those copyrighted chart packages and generated baseline classes.

`generate_line_oracle.py /path/to/phira /path/to/Disk-Failure.zip /output`
extracts the same upstream tween functions plus `Anim<f32>` and the RPE easing
map. It reconstructs rotation layers and extended Scale X from the supplied
130 BPM chart. The chart ZIP is not included. Generated Rust contains upstream
GPL-3.0 code and is not linked into the Android app.

`rotation-oracle.csv` covers f32 radians and trigonometry at ordinary and very
large angles; `disk-line-oracle.csv` covers all six lines at 13 positions, including
backward extrapolation before the first event. `PrprRotationTest` checks the
native basis and reverse layer addition order. `PrprExtremeLineRenderingTest`
records both Preview and export-layer Canvas commands for the initial huge
Scale X. `PrprLineClipperTest` checks the clipped quad. A separate surfaceless
GLES2/Mesa pixel comparison is supplied in checkpoint8's `checks/`, together
with the real-chart Java/Rust comparison and reproduction scripts. That pixel
comparison uses native line triangles and the candidate clipped triangles;
it does not run Android Canvas or verify a physical phone's GPU.

Checkpoint9 corrects the side using the user's mobile Phira observation: the
initial visible half is on the right. The checkpoint8 Mesa comparison produced
the left half; that desktop result is not a portable reference for the phone.
The clipper now processes the left X plane before the right X plane; ordinary
scales and rotation calculations are unchanged. The regression tests assert
both right-side boundaries in Preview and export-layer draw commands. Their
reference is the reported Phira appearance, not a new phone screenshot capture.
