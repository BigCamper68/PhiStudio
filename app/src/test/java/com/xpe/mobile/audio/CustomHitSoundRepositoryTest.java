package com.xpe.mobile.audio;

import com.xpe.mobile.model.*;
import com.xpe.mobile.packageio.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class CustomHitSoundRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private final List<String> failures = new ArrayList<>();
    private CustomHitSoundRepository repository;
    private int workspaceCount;

    @After public void close() throws Exception {
        if (repository != null) repository.close();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }

    private ChartPackage pack(String... paths) throws Exception {
        ChartDocument chart = CustomHitSoundMixerTest.chart();
        chart.song = "music.ogg";
        for (String path : paths) CustomHitSoundMixerTest.note(chart, NoteType.TAP, path, 100);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("chart.json"));
            zip.write(chart.toJsonString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("music.ogg")); zip.write(1); zip.closeEntry();
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry(path)); zip.write(2); zip.closeEntry();
            }
        }
        return new PackageImporter().importPackage(new ByteArrayInputStream(bytes.toByteArray()),
                new File(temporary.getRoot(), "pack-" + ++workspaceCount), "chart.zip");
    }

    private void create(CustomHitSoundRepository.Decoder decoder) {
        repository = new CustomHitSoundRepository(executor, callbacks::add, decoder, failures::addAll);
    }
    private void finish() throws Exception {
        executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        Runnable task; while ((task = callbacks.poll()) != null) task.run();
    }

    @Test public void decodeIsCachedOncePerPathAndFailuresWarnOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        create(file -> {
            calls.incrementAndGet();
            if (file.getName().equals("broken.ogg")) throw new IOException("broken");
            return new PcmHitSound(48000, 1, new short[]{123});
        });
        repository.setPackage(pack("sounds/custom.ogg", "broken.ogg"));
        Set<String> paths = Set.of("sounds/custom.ogg", "broken.ogg", "missing.ogg");
        repository.ensure(paths); repository.ensure(paths);
        assertTrue(repository.isLoading()); finish(); assertFalse(repository.isLoading());
        assertEquals(2, calls.get()); assertEquals(2, failures.size());
        assertEquals(Set.of("sounds/custom.ogg"), repository.samples().keySet());
        repository.ensure(paths); finish();
        assertEquals(2, calls.get()); assertEquals(2, failures.size());
    }

    @Test public void switchedPackageCannotPublishOldAudioEvenWhenDecoderIgnoresInterrupt() throws Exception {
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1);
        create(file -> {
            if (file.getName().equals("old.ogg")) {
                decoding.countDown();
                boolean done = false;
                while (!done) try { done = release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
            }
            return new PcmHitSound(48000, 1, new short[]{123});
        });
        repository.setPackage(pack("old.ogg"));
        repository.ensure(Set.of("old.ogg"));
        assertTrue(decoding.await(3, TimeUnit.SECONDS));
        try {
            repository.setPackage(pack("new.ogg"));
            repository.ensure(Set.of("new.ogg"));
        } finally { release.countDown(); }
        finish();
        assertEquals(Set.of("new.ogg"), repository.samples().keySet());
        assertFalse(repository.isLoading());
    }

    @Test public void enforcesFileAndResidentCacheLimitsBeforePublishing() throws Exception {
        String[] paths = new String[33];
        for (int i = 0; i < paths.length; i++) paths[i] = String.format(Locale.US, "s%02d.ogg", i);
        AtomicInteger calls = new AtomicInteger();
        create(file -> {
            calls.incrementAndGet();
            int size = file.getName().equals("s00.ogg")
                    ? (int)CustomHitSoundRepository.MAX_CACHE_BYTES / 2 : 1;
            return new PcmHitSound(48000, 1, new short[size]);
        });
        repository.setPackage(pack(paths));
        repository.ensure(new HashSet<>(Arrays.asList(paths)));
        finish();
        assertEquals(32, calls.get());
        assertEquals(Set.of("s00.ogg"), repository.samples().keySet());
        assertEquals(32, failures.size()); // 31 cache failures + 1 file-count failure
    }

    @Test public void closeBeforeQueuedPublicationDiscardsDecodedSamples() throws Exception {
        create(file -> new PcmHitSound(48000, 1, new short[]{1}));
        repository.setPackage(pack("sound.ogg")); repository.ensure(Set.of("sound.ogg"));
        executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertFalse(callbacks.isEmpty()); repository.close();
        Runnable task; while ((task = callbacks.poll()) != null) task.run();
        assertTrue(repository.samples().isEmpty()); assertFalse(repository.isLoading());
    }
}
