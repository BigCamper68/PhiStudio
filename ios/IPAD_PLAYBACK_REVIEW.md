# iPad UI and playback review — PhiStudio 1.0.4

Дата: 27 июля 2026 года

## Использованные референсы

- Android `EditorChromeView`, `EditorView`, `EditorSurfaceView`,
  `NoteTextureSet` и `PreviewRenderer`.
- Приложенные iPad-скриншоты редактора и двухколоночного меню.
- PhiChain — как дополнительный референс разделения update/render и кэшей.

## UI

- iPad больше не использует трёхколоночный `NavigationSplitView`.
- Верхняя панель повторяет Android: Play/Pause, скорость, Preview/Editor, Save,
  Undo, Redo и Menu; справа находятся chart/line, BPM/offset и seek bar.
- Рабочая область разделена на 70% note timeline и 30% пять event lanes:
  Move X, Move Y, Rotate, Alpha и Speed.
- Gameplay overlay и полноэкранный Preview используют вписанный и обрезанный
  viewport Android 1350×900; фон timeline и фон gameplay затемняются отдельно.
- Seek bar показывает реальную позицию аудио, при перетаскивании приостанавливает
  playback и выполняет один seek после отпускания.
- Нижняя панель повторяет Android-вкладки Create/Edit/Arrange и их действия.
- Menu повторяет приложенный двухколоночный диалог Projects / Chart editing.
- iPhone сохраняет адаптивную полноширинную timeline/event раскладку и получает
  прокручиваемый dock с теми же действиями.

## Текстуры

Все восемь `note_*.png` и `hit_fx.png` побайтно совпадают с Android-ресурсами.
Ранее iOS, несмотря на наличие этих файлов, рисовал ноты геометрическими
SwiftUI-фигурами. Теперь обе Canvas-сцены используют Android atlas:

- обычные и multi-hit Tap, Drag и Flick;
- Hold с отдельными tail/body/head срезами (50/50 px и 50/95 px);
- 5×6 hit-effect atlas и совпадающие с Java seed/траектории частиц.

Декодирование выполняется один раз в `NoteTextureAtlas`.

## Найденные причины лагов и исправления

| Приоритет | Причина | Исправление |
| --- | --- | --- |
| P0 | `store.scene` вычислялся дважды за один SwiftUI body | Scene memoization по revision/beat/settings |
| P0 | Каждый preview frame заново строил BPM/speed/event/note/combo индексы | `ChartEvaluationCache` и immutable prepared chart |
| P0 | Фон читался с диска через `UIImage(contentsOfFile:)` при каждом body | Асинхронная загрузка один раз в `EditorStore` |
| P0 | Каждый hitsound создавал и `prepareToPlay()` новый `AVAudioPlayer` | Подготовленные round-robin player pools |
| P1 | Каждый playback tick сканировал все ноты для hitsounds | Отсортированный индекс и binary cursor |
| P1 | Старые ноты проходили дорогую speed/control evaluation | Раннее временное отсечение с сохранением hit effects |
| P1 | Для каждой ноты на каждом кадре повторно интегрировались start/end distances | Предвычисление обеих дистанций в prepared chart |
| P1 | Каждая нота делала UUID-hash lookup тайминга на каждом кадре | Плотные массивы таймингов по line/note index |
| P1 | `EventLayer`, storyboard и prepared layers делали линейный latest lookup | Binary search |
| P1 | Beat↔milliseconds сортировал BPM при каждом вызове | Сортировка только при импорте/изменении и timing profile |
| P0 | Несколько hitsound запускались последовательными `play()` | Chord batch с единым `deviceCurrentTime` и увеличенными pools |
| P0 | `Canvas(rendersAsynchronously:)` накапливал устаревшие realtime-кадры | Синхронный Canvas, ограничение display link до 60 FPS и ранний culling |
| P1 | Custom line images/GIF декодировались бы в render path | Ограниченный асинхронный `PreviewLineTextureLoader` и готовый frame cache |
| P2 | Undo → новая ветка могла повторно использовать cache той же revision | Явная invalidation при mutate/undo/redo |
| P2 | Scrub постоянно выполнял дорогой audio seek | Pause во время drag и один seek при завершении |
| P2 | XY Bind был визуальным toggle без поведения | Парное создание/выбор/копирование/удаление/split Move X/Y |

## Регрессионная проверка

- Добавлен XCTest сравнения fresh и reused evaluator cache на нескольких beats.
- Тот же тест проверяет invalidation после изменения chart revision.
- Добавлены XCTest для Event Clone и точного времени/координат hit effects.
- Xcode project повторно сгенерирован штатным deterministic Node-скриптом.
- Swift-файлы проверены AST-парсером; дополнительно проверены подключение новых
  source/resource файлов, plist и SHA-256 соответствие Android-текстур.

Полная компиляция и UI-тест на реальном iPad требуют macOS с Xcode 16+.

## Исправления 1.0.4

- Внутренняя библиотека перенесена из Documents в Application Support; старые
  проекты мигрируют до чтения индекса, а reopen/export покрыты round-trip тестом.
- Устранено рекурсивное присваивание playback rate и перезапуск аудио при смене
  скорости.
- Реализованы project-relative custom line textures и storyboard GIF timing.
- Все 29 easing получили точные названия PhiStudio и интерактивные графики.
- Line list и BPM list получили явные Apply/Edit/Duplicate/Delete действия.
- Move запускается только с выбранной ноты и показывает единственную live-позицию;
  drag вне выделения прокручивает timeline.
- Preview получил вертикальный scrub; Editor overlay — hit effects без двойных нот.
- Note size в timeline масштабирует только X, а event label отделён от easing curve.
- Верхняя/нижняя панели увеличены, dock-кнопки имеют одинаковый размер и центрирование,
  playhead поднят над панелью.
- Нормальная judge line использует формулу PhiChain: texture `1920×3` и
  `line_world_scale = viewportWidth×3/1920`.
