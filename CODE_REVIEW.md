# Ревью исходного кода PhiStudio

## Дополнение от 2 августа 2026 года — версия 1.0.3

Повторно проверены затронутые пути Storyboard, preview renderer, attached UI,
preview viewport, editor timeline и сохранение настроек. Аудиоподсистема v4 и
встроенные PCM-ресурсы оставлены побайтно без изменений.

Исправления этого обновления:

- Storyboard-окно расширено до 94% экрана, внутренний отступ строк действий
  уменьшен до 2 dp;
- judge line использует плоский `BUTT` cap, а нулевой Scale X или Scale Y не
  создаёт вырожденную геометрию;
- размер storyboard-текста приведён к формуле Phira: 4% ширины игрового
  viewport при `size(1.0)`;
- HUD использует исходные относительные метрики Phira, включая прямоугольный
  pause, размеры текста, поля и progress bar без незаполненного трека;
- стандартное соотношение preview изменено с 3:2 на 16:9; старое сохранённое
  значение 1350x900 мигрирует автоматически;
- базовая линия таймлайна перенесена с 72 dp на 28 dp от нижней границы. Та же
  координата продолжает использоваться отрисовкой, hit-testing и конвертацией
  beat/Y.

Добавлены регрессионные тесты точного масштаба текста, метрик HUD, нулевого
масштаба, viewport 16:9 и миграции настроек. В изолированной среде выполнены
11 целевых тестов и компиляция изменённого renderer-пути с `javac -Xlint:all`:
ошибок и предупреждений нет. Полный Android Gradle-прогон этого дополнения не
повторялся, поскольку локально отсутствуют Android SDK и дистрибутив Gradle;
результаты полного базового ревью v4 сохранены ниже.

Дата ревью: 23 июля 2026 года
Исходная ревизия: `fcabb54` (`main`)

## Итог

Проект имеет сильную предметную модель, хорошее покрытие чистой Java-логики и
аккуратную работу с недоверенными ZIP-пакетами. Главными проблемами были два
монолитных UI-класса, смешение тяжёлых задач в одном executor, несколько
конкурентных и memory-risk сценариев, а также ошибка потери изменённых
типизированных свойств заметки при экспорте.

В рамках ревью критические проблемы исправлены, а крупные классы разделены по
ответственности без изменения пользовательского сценария:

| Файл | Было | Стало |
| --- | ---: | ---: |
| `MainActivity.java` | 3814 строк | 1445 строк |
| `EditorView.java` | 3649 строк | 1419 строк |

Новые границы:

- `EditorAudioController` — музыка, MP3 decode, PCM playback и hit sounds;
- `EditorDialogController` — фасад диалогов;
- `EditorDialogSection` — общие UI-примитивы диалогов;
- `StoryboardDialogSection` — storyboard;
- `EditorOperationDialogSection` — batch edit, clone, complex move и curve notes;
- `EditorSurfaceView` — состояние и canvas-примитивы;
- `EditorDocumentView` — undoable-команды над chart;
- `EditorChromeView` — preview, toolbar, seek, playback и нижняя панель;
- `EditorView` — touch-жесты и непосредственные edit-операции.

## Исправленные находки

### P1 — блокировка project I/O декодированием MP3

MP3 decode выполнялся в том же single-thread executor, что импорт, открытие,
восстановление и дублирование проектов. Длинный трек мог полностью остановить
project workflow.

Исправление: аудио получило собственный именованный executor, а декодирование
изолировано в `EditorAudioController`.

### P1 — гонка между preview prewarm и редактированием

Фоновый `ChartEvaluator.prepare()` обходил изменяемые списки chart одновременно
с touch-редактированием. Модель не потокобезопасна, поэтому были возможны
неконсистентный preview и runtime-исключения.

Исправление: небезопасный фоновый prewarm удалён. Кэш строится лениво на
владеющем моделью UI-потоке; контракт `ChartEvaluator.prepare()` уточнён.

### P1 — тяжёлая стартовая загрузка в `onCreate`

Текущий проект и autosave читались и парсились на main thread. Большой chart мог
привести к заметной паузе или ANR ещё до появления project browser.

Исправление: загрузка библиотеки, текущего проекта, autosave и demo перенесена в
project executor. Публикация готовой модели в `EditorView` остаётся на main
thread.

### P1 — удержание всех JSON-кандидатов пакета в памяти

`PackageImporter` сохранял разобранный `JSONObject` каждого chart-кандидата до
завершения выбора. При нескольких больших JSON это умножало пиковое потребление
heap и могло завершиться OOM.

Исправление: импорт стал двухфазным. Первая фаза хранит только путь и формат;
после выбора повторно читается только один chart. При OOM незавершённый
workspace очищается.

### P1 — экспорт старых `tint` и `judgeArea`

`Note` читал `tint`, `tintHitEffects` и `judgeArea` в типизированные поля, но
`toJson()` продолжал экспортировать исходные raw-значения. Изменения этих полей
могли молча потеряться.

Исправление: сериализация теперь использует актуальные поля, сохраняет
дополнительные исходные tint-компоненты и удаляет отключённые tint-поля.
Добавлены регрессионные тесты.

### P2 — неполная очистка после resource failure

OOM во время создания или копирования проекта мог оставить частичный workspace.
Ошибка MP3 decode могла до следующей загрузки оставить частичный PCM-файл.

Исправление: OOM включён в transactional cleanup импортера, creator, copier и
duplicate workflow; неуспешный PCM output удаляется сразу.

### P2 — пересечение preview decode и project tasks

Декодирование illustration/line textures делило executor с операциями
библиотеки. Теперь asset decode выполняется в отдельном последовательном
executor и не задерживает import/open/remove.

### P3 — качество API и диагностики компилятора

- `EditorAudioController` хранит защитную копию settings;
- алиасы одного modifier (`CTRL+LEFTCTRL`, `ALT+RIGHTALT`) больше не проходят
  как корректный shortcut;
- добавлены `serialVersionUID` для сериализуемых exception/list классов;
- удалены неиспользуемые imports.

## Что уже сделано хорошо

- ZIP-slip, absolute paths, duplicate/conflicting paths и symlink workspaces
  блокируются.
- Есть лимиты количества entries, compressed/uncompressed entry size и общего
  размера распаковки.
- Chart и library index сохраняются через temporary file и atomic move, где это
  поддерживается.
- Неизвестные JSON-поля и package resources сохраняются при round trip.
- Preview caches привязаны к revision/structure chart.
- Большинство editor-операций представлены обратимыми командами и покрыты
  unit-тестами.
- У приложения нет сетевых permissions и runtime-зависимостей.

## Оставшиеся риски

### P1 — autosave и explicit save ещё выполняют I/O на main thread

Периодический autosave, `onPause`, project save и часть export workflow
сериализуют изменяемую модель синхронно. Для очень больших chart это всё ещё
может дать frame stall или ANR.

Корректное исправление требует immutable snapshot: chart нельзя просто передать
background thread, потому что UI продолжает менять его списки. Рекомендуется
добавить полноценный deep snapshot с revision token, сериализовать snapshot в
save executor и помечать chart сохранённым только если revision не изменился.

### P2 — нет явного лимита для standalone JSON и размера decoded PCM

Standalone chart читается целиком в память. Выбранный пользователем MP3 может
развернуться в PCM, во много раз превышающий исходный файл и свободное место
cache. Нужны продуктовые лимиты для максимального chart JSON, длительности или
размера PCM и проверка доступного места перед decode.

### P2 — отсутствуют Android UI/instrumentation тесты

Текущий набор хорошо проверяет model/editor/package/preview алгоритмы, но не
проверяет Activity lifecycle, SAF callbacks, dialogs, MediaPlayer/MediaCodec и
реальные touch-жесты. Минимальный следующий слой — instrumentation smoke tests
для startup, open/edit/save/reopen, rotation/config change и audio replacement.

### P3 — deprecated Android APIs

Остаются совместимые ветки с legacy system UI flags, deprecated soft-input mode,
`MediaTimestamp#getAnchorSytemNanoTime()` для API 26–28 и
`android.graphics.Movie` для GIF. Они не ломают текущую сборку, но требуют
поэтапной миграции.

### P3 — ресурсы и backup policy

Часть сообщений и canvas-текста всё ещё захардкожена на английском.
`allowBackup="true"` вместе с локальными chart/media и `largeHeap="true"` стоит
подтвердить как осознанную продуктовую политику.

## Проверка

Финальная проверка выполнена с нуля:

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

Дополнительно используется `javac -Xlint:all`; ожидаемыми остаются только
перечисленные Android deprecation warnings.

Результат:

- 257 unit-тестов: 0 failures, 0 errors, 3 skipped;
- Android Lint: `No issues found`;
- `assembleDebug`: успешно;
- debug APK: 1,4 МБ.

## Подготовка релиза 1.0.2

- `versionCode` увеличен с 38 до 39, `versionName` — с 1.0.1 до 1.0.2;
- release signing подключается только через переменные окружения, секреты не
  хранятся в Gradle-файлах;
- `.p12` и release-password файлы явно исключены из Git;
- `clean testDebugUnitTest lintRelease assembleRelease`: успешно;
- подписанный APK проходит `zipalign` и `apksigner verify`;
- package: `com.xpe.mobile`, min SDK 26, target SDK 35;
- SHA-256 финального APK фиксируется в неизменяемых заметках GitHub Release;
- SHA-256 сертификата:
  `0711ce5197d30d09c71630447f8898fa9f195da36387b3f9fe385a084798a9d2`.

Сертификат совпадает с опубликованным PhiStudio 1.0.1, поэтому APK 1.0.2
совместим с обновлением поверх предыдущего релиза.
