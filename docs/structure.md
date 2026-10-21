# Java source layout

Production sources live under `app/src/main/java/com/xpe/mobile/`. Tests use the
same feature packages under `app/src/test/java/com/xpe/mobile/`; Activity-level
integration tests live in `app`.

| Package | Responsibility |
| --- | --- |
| `app` | Main Activity, lifecycle and menu coordination, file/project actions, application dialogs and export/Playtest sessions |
| `editor` | Note/event/storyboard grids, editing commands, history, property panels and shared editor controls |
| `noiseeditor` | Noise Field document, forms, browser, timeline and preview interaction |
| `shadereditor` | Shader document, forms, timeline and preview interaction |
| `playtest` | Playback clock, options and playable chart view |
| `preview` | Chart evaluation, geometry, textures, rendering, shaders and noise-field effects |
| `audio` | Audio decoding, transport, mixing, hit sounds and filters |
| `video` | Video export configuration, rendering and encoding |
| `project` | Local project library, browser, thumbnails and recovery |
| `packageio` | Chart package import, conversion, workspace loading and export |
| `model` | Chart data, timing, events, easing and serialization |
| `io` | Standalone chart file I/O |
| `config` | Editor settings and shortcuts |
| `i18n` | Language switching, localized UI and error descriptions |

There are no handwritten Java files directly in `com.xpe.mobile`. The Android
namespace remains `com.xpe.mobile`, so generated `R` classes still belong there.

The Activity implementation is `com.xpe.mobile.app.MainActivity`. The manifest
retains `com.xpe.mobile.MainActivity` as its launcher alias, preserving installed
shortcuts. Activity preferences retain their original filename for both regular
and checkpoint installations. Application IDs, project paths and chart formats
are unchanged by this reorganization.

Feature views expose lifecycle and editing operations needed by their hosts;
document state and form internals remain package-private. Cross-package test
fixtures live in the test source set rather than widening production fields.
