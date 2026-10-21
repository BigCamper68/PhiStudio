# Project loading measurements

`ProjectLoadingProbe.java` executes production `PackageWorkspaceLoader`,
`ProjectLibrary.discoverWorkspaces` and `ProjectLibrary.openProject` on a host JVM.
It does one warm-up and reports all three measured repetitions and their median.
It checks that note and noise-field counts survive each load. This measures
package/index work with a warmed filesystem, not Android UI latency, cold startup,
thumbnail delivery, GPU initialization or audio decoding.

Compile production classes in each checkout with
`./gradlew compileDebugUnitTestJavaWithJavac`. Compile this standalone probe once
against those classes and the `org.json:json:20240303` test dependency. Use Java 17
or newer. For example, with the dependency JAR path in `ORG_JSON_JAR`:

```sh
PRODUCTION=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -cp "$PRODUCTION:$ORG_JSON_JAR" -d /tmp/loading-probe tools/loading-audit/ProjectLoadingProbe.java
java -Xmx2g -cp "/tmp/loading-probe:$PRODUCTION:$ORG_JSON_JAR" ProjectLoadingProbe /tmp/loading-before /tmp/before.json /path/Desultory_Signals_AT.pez.zip /path/Micrology.zip
```

The two optional ZIP arguments initialize an isolated library that must not exist
yet. Setup imports both charts and adds 600 binary 4 KiB resources to Desultory
Signals to exercise resource-heavy packages; import/setup is untimed. Micrology
retains its original package resources. Do not point the probe at a user's library:
`openProject` updates its last-opened index entries.

Copy that initialized library to `/tmp/loading-after`, then run the same probe
with the newer checkout's production classes and **without** ZIP arguments:

```sh
cp -a /tmp/loading-before /tmp/loading-after
java -Xmx2g -cp "/tmp/loading-probe:$PRODUCTION:$ORG_JSON_JAR" ProjectLoadingProbe /tmp/loading-after /tmp/after.json
```

Keep chart/resource bytes identical and run each version sequentially without
other builds/tests competing for CPU. Full opening still inventories all entries,
rejects symbolic links and enforces package size/count limits. The library list
checks only each indexed chart's path/header; unindexed recovery uses full
inspection. There is no persistent cache of parsed, mutable chart models.
