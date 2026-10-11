package com.xpe.mobile.audio;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageHitSoundCatalog;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** Main-thread owned cache with generation-checked, cancellable background decoding. */
final class CustomHitSoundRepository implements AutoCloseable {
    interface Decoder { PcmHitSound decode(File file) throws IOException; }
    interface Listener { void changed(List<String> failures); }
    static final int MAX_FILES = 32;
    static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private final ExecutorService executor;
    private final Consumer<Runnable> post;
    private final Decoder decoder;
    private final Listener listener;
    private final Set<String> attempted = new HashSet<>();
    private final List<Future<?>> tasks = new ArrayList<>();
    private ChartPackage pack;
    private Map<String,PcmHitSound> samples = Collections.emptyMap();
    private AtomicLong usedBytes = new AtomicLong();
    private int generation, pending;
    private boolean closed;

    CustomHitSoundRepository(ExecutorService executor, Consumer<Runnable> post,
                             Decoder decoder, Listener listener) {
        this.executor = executor; this.post = post; this.decoder = decoder; this.listener = listener;
    }

    void setPackage(ChartPackage pack) {
        generation++;
        for (Future<?> task : tasks) task.cancel(true);
        tasks.clear();
        this.pack = pack;
        pending = 0;
        usedBytes = new AtomicLong();
        attempted.clear();
        samples = Collections.emptyMap();
    }

    boolean isLoading() { return pending > 0; }
    Map<String,PcmHitSound> samples() { return samples; }

    void ensure(Set<String> paths) {
        if (closed || paths.isEmpty()) return;
        List<String> requested = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String path : new TreeSet<>(paths)) {
            if (!attempted.add(path)) continue;
            if (attempted.size() <= MAX_FILES) requested.add(path);
            else rejected.add(path + " (32-file limit)");
        }
        if (!rejected.isEmpty()) listener.changed(rejected);
        if (requested.isEmpty()) return;
        int expected = generation;
        ChartPackage sourcePackage = pack;
        AtomicLong budget = usedBytes;
        pending++;
        try {
            tasks.add(executor.submit(() -> {
                Map<String,PcmHitSound> decoded = new LinkedHashMap<>();
                List<String> failures = new ArrayList<>();
                for (String path : requested) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try {
                        PcmHitSound sound = decoder.decode(PackageHitSoundCatalog.resolve(sourcePackage, path));
                        if (sound == null || sound.frameCount() == 0) throw new IOException("Empty hit sound");
                        long bytes = (long)sound.frameCount() * sound.channelCount * 2;
                        long used = budget.get();
                        if (bytes > MAX_CACHE_BYTES - used) throw new IOException("32 MiB cache limit");
                        budget.addAndGet(bytes); // decoding uses one serialized executor
                        decoded.put(path, sound);
                    } catch (IOException | RuntimeException | OutOfMemoryError exception) {
                        failures.add(path + " (" + exception.getMessage() + ")");
                    }
                }
                post.accept(() -> {
                    if (closed || generation != expected) return;
                    pending--;
                    Map<String,PcmHitSound> updated = new LinkedHashMap<>(samples);
                    for (var entry : decoded.entrySet()) {
                        updated.put(entry.getKey(), entry.getValue());
                    }
                    samples = Collections.unmodifiableMap(updated);
                    listener.changed(failures);
                });
            }));
        } catch (RuntimeException exception) {
            pending--;
            listener.changed(Collections.singletonList("Unable to prepare custom hit sounds"));
        }
    }

    @Override public void close() {
        closed = true;
        setPackage(null);
    }
}
