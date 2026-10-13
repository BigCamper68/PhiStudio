# Проверка релиза PhiStudio 1.0.5

Дата: 13 октября 2026. Пакет `com.xpe.mobile`, версия **1.0.5**, versionCode **76**.
Среда: Arch ARM64 внутри Android, JDK 17, Android SDK / Build Tools 35,
Gradle 8.9 и AGP 8.7.3.

- Unit-тесты: **758 всего, 743 успешных, 15 пропущенных, 0 ошибок/падений**.
- Проверка `Parallel Universe Shifter.zip`: 560 кадров playback/seek,
  сравнение нот и эффектов со stateless-расчётом, реальные интервалы шейдеров.
- Проверки кэша: смена BPM, перемотка, live edits, замена BeatTime и перекрытия
  событий. Проверены переходы shader/Canvas и ноты/paint скрытых линий.
- `lintDebug` и `lintRelease`: **0 ошибок**, по 24 прежних UnusedResources warning.
- `assembleDebug` и `assembleRelease` успешны.
- 2 252 расчётные сцены до/после оптимизации совпали; SHA-256:
  `167d77728c23df543babe3aa3cdeb9121e84e6e86fbc57c3ee7a929cfdd2c44c`.
  Сравнение включает тяжёлый чарт, demo, два aspect ratio, оба speed-пути,
  HUD, ноты, hit effects и stateful paint. Это сравнение данных, не GPU-снимков.
- APK подписывается прежним release-сертификатом; SHA-256 сертификата:
  `0711ce5197d30d09c71630447f8898fa9f195da36387b3f9fe385a084798a9d2`.

`tools/termux-tests.gradle` исключает 21 desktop native-graphics тест,
не поддерживаемый ARM64 Robolectric. Они не входят в число 758 и не считаются
пройденными. Остальные 15 пропусков относятся к необязательным fixtures и
нативной JVM audio-среде. В обычном desktop CI этот профиль не используется.

Пример проверки на ARM64 с подходящим системным aapt2:

```sh
PHISTUDIO_PLAYBACK_CHART='/path/to/Parallel Universe Shifter.zip' \
ANDROID_HOME=/path/to/android-sdk ./gradlew --no-daemon --offline \
  -Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2 \
  -I tools/termux-tests.gradle \
  testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Для CPU-сравнения есть `tools/playback-performance/benchmark.py` с параметрами
`--chart`, `--baseline` (classes.jar предыдущей сборки) и `--output`.
Чарт пользователя и скачанные reference-исходники не входят в релизный архив.
Замеры JVM чувствительны к JIT и нагрузке CPU и не измеряют FPS дисплея.
В финальной среде сборки нет подключённой через ADB Activity: аппаратный
GPU playback и MP4-кодирование на устройстве здесь не проверены.

Старые логи, XML-отчёты и скриншоты проверок хранятся вне исходников.
Тесты, ресурсы и лицензии при очистке сохранены побайтно. В трёх Java-файлах
удалены только пробелы в конце пустых строк; поведение приложения не менялось.
