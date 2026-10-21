# Native noise field reference and checks

The bundled resources and Java renderer implement official Phigros 4.0.1
`blockAreaList`. Source provenance and scope are described in
[checkpoint 2](../../docs/checkpoints/1.0.9-checkpoint2.md) and
[NOTICE.txt](../../app/src/main/assets/noise-field/NOTICE.txt).

## Regenerate assets

Check out these pinned references:

- CHCAT1320/CHCAT-Docs: `054fcfdddc4ddf66b1bbecc45df0d5264e3d3852`.
- Phira-Pro/Phira-Pro: `b67e5e845b58e3cf89d674e782a9b3bd0f5e8af8`.

```sh
python tools/noise-field/generate_assets.py /path/to/CHCAT-Docs \
  --phira-pro /path/to/Phira-Pro
```

The script extracts twelve vertex/fragment programs and material values. It
adapts GLSL interface syntax and texture calls for GLES2 and writes source hashes
to `sources.json`. No original chart, music or background is committed.

## Java and import tests

Use JDK 17 and Android SDK 35. The full suite also checks Playtest input routing
and the unrelated rendering regressions.

```sh
PHISTUDIO_NOISE_FIELD_CHART=/path/to/Desultory_Signals_AT.pez.zip \
  ./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Actual GL comparison

The host check needs JDK 17, compiled debug Java classes, a C compiler, and Mesa
EGL/GLES development libraries with surfaceless software rendering. It uses the
Gradle-cached `org.json:json:20240303` dependency. `ANDROID_HOME` and `JAVA_HOME`
can override the SDK/JDK locations; `NOISE_CC` selects the compiler.

```sh
python tools/noise-field/verify_gpu.py \
  --reference /path/to/CHCAT-Docs \
  --chart /path/to/Desultory_Signals_AT.pez.zip \
  --output /tmp/noise-field-gpu

python tools/noise-field/verify_geometry.py \
  --chart /path/to/Desultory_Signals_AT.pez.zip \
  --host /tmp/noise-field-gpu/host \
  --output /tmp/noise-field-geometry
```

`host/` is a desktop-only Android API adapter and a small JNI bridge to actual
Mesa GL. It is outside Android source sets and never enters the APK. The same
production Java compositor renders the original GLES3 programs and the adapted
GLES2 programs into PNGs over a diagnostic background. This checks shader math
and GL state; it does not exercise Android HWUI, SurfaceTexture or MediaCodec.
The geometry check independently evaluates the published C# equations using
Python float32 rounding; it is not an execution of the original game binary.

## Under-grid compositor check

Checkpoint 3 also tests production `ShaderGlRenderer`, including the actual
`samplerExternalOES` path. The host creates EGLImages as its layer producers;
Android HWUI and SurfaceTexture delivery remain outside this check.

After building `host/`, run `com.xpe.mobile.preview.EditorUnderlayHost` with the
generated classpath and `-Dnoise.gl.lib=/path/to/host/libnoisegles.so`. Its arguments
are the app assets directory, the Desultory Signals archive, and the JSON report
path. It checks full-opacity output and the editor's 128/255 composition against
known colors, then renders real fields through all compositor passes.

Music filtering and Phira note-scale references are documented in
[checkpoint 3](../../docs/checkpoints/1.0.9-checkpoint3.md).

## Colored fields

Checkpoint 8 adds a PhiStudio RGB extension, documented with its compatibility
scope in [checkpoint 8](../../docs/checkpoints/1.0.9-checkpoint8.md). Native shader
assets remain unchanged. After building the host adapter, run
`com.xpe.mobile.preview.NoiseFieldColorsHost` with the generated classpath and
`-Dnoise.gl.lib=/path/to/host/libnoisegles.so`. Arguments are the app assets
directory and an output directory for PNGs and `colors.json`.

This checks color displacement against an independent native Compose mask over
resize/time/rewind cases; active/inactive/ready white fields with canceled red
holes and touch effects; achromatic white; switching back to the native
material; and colored under-grid opacity through the actual external-texture
compositor. It uses Mesa EGL/GLES, not a physical-device or HWUI capture.
