# Структура PhiStudio

Публичные классы приложения остаются точками входа. Их внутренние части
разделены по задачам; компоненты внутри пакета не добавляют публичный API.

| Область | Точка входа | Компоненты |
|---|---|---|
| Экран приложения | `MainActivity` | `MainScreenSetup`, `MainActivityResults`, `MainFileActions`, `MainProjectActions`, `MainProjectSession`, `NewProjectDialog`, `MainShaderSession`, `MainPreviewResources`, `MainMenuUi`, `MainShortcuts` |
| Диалоги | `EditorDialogController` | metadata, BPM, settings, diagnostics, judge line, note и event property dialogs; отдельные batch/generated chart dialogs |
| Редактор | `EditorView` и его базовые классы | `EditorGestureRouter`, `NoteGestureController`, `EventGestureController`, `EditorSelectionEdits`, `EditorClipboardController`; property, storyboard, generated и line edits |
| Отрисовка редактора | `EditorSurfaceView`, `EditorChromeView` | timeline/note/event painters, toolbar/bottom bar/preview UI, playback/session UI, drawing style и field copies |
| Shader UI | `ShaderEditorView` | `ShaderEditorTimeline`, `ShaderPreviewPane`, `ShaderEffectForms`, `ShaderAnimationForms` |
| Вычисление Preview | `ChartEvaluator` | `PreviewChartIndex`, `PreviewLineEvaluator`, `PreviewHitEvaluator`, `PreviewHudEvaluator`, `PreviewPaintEvaluator`, `PreviewGifEvaluator` |
| Отрисовка Preview | `PreviewRenderer` | `PreviewHudRenderer`, `PreviewLineRenderer`, `PreviewNoteRenderer`, `PreviewPaintRenderer`, `PreviewHitEffects` |
| Экспорт MP4 | `MainVideoExport`, `VideoExportDialog`, `Mp4Exporter` | `VideoExportOptions`, `ExportFrameRenderer`, `CodecTrackWriter`, `ExportAudioMixer`; собственные экземпляры PreviewRenderer/ShaderGlRenderer и общий ScheduledHitSoundMixer |
| Импорт пакетов | `PackageImporter` | `PackageArchiveReader`, `PackageChartSelection`, `PackageResourceSelection` |
| Библиотека проектов | `ProjectLibrary` | `ProjectIndexStore`, `ProjectWorkspaceDiscovery`, `ProjectWorkspaceFiles` |
| Storyboard-модель | `ExtendedLineEvents` | `StoryboardTextTween`, `StoryboardColorTween`; публичные вложенные типы событий сохранены |
| Audio DSP | `Sonic` | `SonicTiming`, `SonicImpl`, `SonicFloatImpl`, `SonicShortImpl`; исходные лицензии и алгоритмы сохранены |
| Playtest | `MainPlaytest`, `PlaytestView` | `PlaytestOptionsDialog`, `PlaytestOptions`, `PlaytestClock`, `PlaytestJudge`; ручные оценки, звуки и результаты отделены от autoplay Preview |

`ChartEvaluator` создаёт `RenderScene`. Рендер использует готовую сцену;
подготовленные данные и слабый кэш чарта принадлежат `PreviewChartIndex`.
Bitmap-история paint находится в `PreviewPaintRenderer`, состояние частиц —
в `PreviewHitEffects`. Владельцем UI-состояния остаётся соответствующая View
или Activity, а внутренние компоненты получают этого владельца при создании.
Программные shader View требуют владельца и создаются из `ShaderEditorView`.

Операции редактора по-прежнему проходят через команды и историю undo/redo.
Lifecycle-вызовы Android остаются в Activity/View; внутренние обработчики
получают управление в том же порядке. Контекст и тема диалогов принадлежат
исходной View/Activity/ContextThemeWrapper.

Крупные тестовые серии разделены по темам. Общие данные, `@Before`/`@After`
и вспомогательные методы расположены в абстрактных `*TestSupport`.
Каждый исходный `@Test` перенесён один раз, его тело сохранено.

Для проверки эквивалентности предыдущей сборке предусмотрены:

- `tools/refactor/compare_runtime.py --baseline <classes.jar>`: сериализация
  чарта/метаданные пакета, полные сцены, paint и PCM Sonic. Нужны два chart ZIP
  из `--charts` и скомпилированные классы предыдущей версии.
- `tools/refactor/compare_android.py --baseline <classes.jar>`: восемь
  offscreen-снимков редактора, Preview и shader UI через Android ART/Canvas.
  Это проверка для Termux на Android, без установки APK.

Результаты проверки релиза — [docs/validation.md](docs/validation.md).
Особенности совместимости предпросмотра с prpr —
[docs/rendering-parity.md](docs/rendering-parity.md).
Игровой режим и проверка поведения ввода Phira — [docs/playtest.md](docs/playtest.md).
