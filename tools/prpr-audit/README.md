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
