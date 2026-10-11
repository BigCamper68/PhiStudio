package com.xpe.mobile.audio;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PcmAudioPlayerTransportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<PcmAudioPlayer> players = new ArrayList<>();
    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private int completions;
    private int errors;

    @After public void releasePlayers() { for (var player : players) player.release(); }

    private PcmAudioPlayer player(PcmAudioPlayer.OutputFactory factory, int frames) throws Exception {
        File file = temp.newFile();
        try (FileOutputStream stream = new FileOutputStream(file)) {
            for (int i = 0; i < frames; i++) { stream.write(i & 255); stream.write(i >>> 8); }
        }
        PcmAudioPlayer result = new PcmAudioPlayer(new PcmAudioAsset(file, 1000, 1, frames), callbacks::add,
                new PcmAudioPlayer.Listener() {
                    public void onCompletion() { completions++; }
                    public void onError(String message) { errors++; }
                }, factory);
        players.add(result);
        return result;
    }

    static class FakeOutput implements PcmAudioPlayer.Output {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final CountDownLatch played = new CountDownLatch(1), released = new CountDownLatch(1);
        volatile boolean playing;
        volatile long head;
        volatile int capacity = 20; // one 10ms block; emulate output backpressure
        volatile boolean consume;
        int playCount;
        public synchronized int write(byte[] pcm, int offset, int length) {
            int n = Math.min(length, Math.max(0, capacity - data.size()));
            data.write(pcm, offset, n);
            if (consume) head += n / 2;
            return n;
        }
        public void play() { playing = true; playCount++; played.countDown(); }
        public void pause() { playing = false; }
        public void flush() { head = 0; }
        public long headFrames() { return head; }
        public void release() { playing = false; released.countDown(); }
        synchronized int firstSample() {
            byte[] bytes = data.toByteArray();
            return (short) ((bytes[0] & 255) | bytes[1] << 8);
        }
    }

    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private void drainCallbacks() { Runnable r; while ((r = callbacks.poll()) != null) r.run(); }

    @Test public void rapidBackwardForwardSeekAndResumeReadExactSourceSamples() throws Exception {
        Queue<FakeOutput> outputs = new ConcurrentLinkedQueue<>();
        PcmAudioPlayer player = player((asset, speed) -> { FakeOutput o = new FakeOutput(); outputs.add(o); return o; }, 10000);
        for (int position : new int[]{4000, 1000, 7500, 0, 350}) {
            player.seek(position);
            assertEquals(position, player.positionMillis());
            player.start(position, 1);
            FakeOutput output = waitOutput(outputs);
            await(output.played);
            assertEquals(position, output.firstSample());
            output.head = 7;
            assertEquals(position + 7, player.positionMillis());
            player.pause();
            assertFalse(output.playing);
            assertEquals(position + 7, player.positionMillis());
            await(output.released);
        }
        drainCallbacks(); assertEquals(0, completions); assertEquals(0, errors);
    }

    @Test public void slowPlaybackKeepsNativeOutputAtOneXAndClockInSourceFrames() throws Exception {
        Queue<FakeOutput> outputs = new ConcurrentLinkedQueue<>();
        PcmAudioPlayer player = player((asset, nativeSpeed) -> {
            assertEquals(1f, nativeSpeed, 0);
            FakeOutput output = new FakeOutput(); outputs.add(output); return output;
        }, 10000);
        for (float speed : new float[]{.5f, .75f, 1f}) {
            player.start(1000, speed);
            FakeOutput output = waitOutput(outputs); await(output.played);
            output.head = 100;
            assertEquals(1000 + Math.round(100 * speed), player.positionMillis());
            player.pause();
            assertEquals(1000 + Math.round(100 * speed), player.positionMillis());
            await(output.released);
        }
        drainCallbacks(); assertEquals(0, errors);
    }

    @Test public void slowPlaybackActuallyWritesUnstretchedHitAndCompletesAtSourceEnd() throws Exception {
        for (float speed : new float[]{.5f, .75f}) {
            FakeOutput output = new FakeOutput(); output.capacity = 100000; output.consume = true;
            PcmAudioPlayer player = player((asset, nativeSpeed) -> {
                assertEquals(1f, nativeSpeed, 0); return output;
            }, 1000);
            short[] hit = new short[]{100, 200, 300, 400, 500, 600};
            player.configureHitSounds(ScheduledHitSoundMixerTest.timeline(600), 0,
                    ScheduledHitSoundMixerTest.sounds(1000, 1, hit));
            player.setVolume(0);
            player.start(500, speed);
            await(output.released); drainCallbacks();
            byte[] data = output.data.toByteArray();
            assertEquals(Math.round(500 / (double)speed) * 2, data.length);
            int first = Math.round(100 / speed);
            for (int frame = 0; frame < data.length / 2; frame++) {
                assertEquals(frame >= first && frame < first + hit.length
                        ? hit[frame-first] : 0, ScheduledHitSoundMixerTest.sample(data, frame, 1));
            }
            assertEquals(1000, player.positionMillis());
        }
        assertEquals(2, completions); assertEquals(0, errors);
    }

    @Test public void customHitActuallyReachesNativePcmAtFixedDurationOnSlowPlayback() throws Exception {
        FakeOutput output = new FakeOutput(); output.capacity = 100000; output.consume = true;
        PcmAudioPlayer player = player((asset, nativeSpeed) -> {
            assertEquals(1f, nativeSpeed, 0); return output;
        }, 1000);
        var chart = CustomHitSoundMixerTest.chart();
        CustomHitSoundMixerTest.note(chart, com.xpe.mobile.model.NoteType.DRAG, "custom.wav", 600);
        short[] hit = {1000, 2000, 3000, 4000};
        player.configureHitSounds(HitSoundTimeline.build(chart, 0, 0), 0,
                CustomHitSoundMixerTest.builtins(1000),
                Map.of("custom.wav", new PcmHitSound(1000, 1, hit)));
        player.setVolume(0);
        player.start(500, .5f); await(output.released); drainCallbacks();
        byte[] data = output.data.toByteArray();
        assertEquals(2000, data.length);
        for (int frame = 0; frame < 1000; frame++) {
            assertEquals(frame >= 200 && frame < 204 ? hit[frame-200] : 0,
                    ScheduledHitSoundMixerTest.sample(data, frame, 1));
        }
        assertEquals(1000, player.positionMillis()); assertEquals(1, completions); assertEquals(0, errors);
    }

    private static FakeOutput waitOutput(Queue<FakeOutput> outputs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (outputs.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        FakeOutput output = outputs.poll(); assertNotNull(output); return output;
    }

    @Test public void cancelledNativeCreationCannotStartOldAudioAfterNewSeek() throws Exception {
        CountDownLatch creating = new CountDownLatch(1), finish = new CountDownLatch(1);
        FakeOutput old = new FakeOutput(), fresh = new FakeOutput();
        AtomicInteger calls = new AtomicInteger();
        PcmAudioPlayer player = player((asset, speed) -> {
            if (calls.getAndIncrement() > 0) return fresh;
            creating.countDown();
            boolean done = false;
            while (!done) try { finish.await(); done = true; } catch (InterruptedException ignored) { }
            return old;
        }, 10000);
        try {
            player.start(5000, 1); await(creating);
            player.pause(); player.seek(1000); player.start(1000, 1);
            await(fresh.played); finish.countDown(); await(old.released);
            assertEquals(0, old.playCount);
            assertEquals(1000, fresh.firstSample());
            assertEquals(1000, player.positionMillis());
        } finally { finish.countDown(); }
    }

    @Test public void oldCompletionCallbackCannotStopNewPlayback() throws Exception {
        FakeOutput output = new FakeOutput(); output.capacity = 100000; output.consume = true;
        PcmAudioPlayer player = player((asset, speed) -> output, 10);
        player.start(0, 1); await(output.released);
        assertFalse(callbacks.isEmpty());
        player.seek(0); // cancels the completed generation before its queued callback runs
        drainCallbacks(); assertEquals(0, completions); assertEquals(0, errors);
    }

    @Test public void oldErrorCallbackCannotAffectNewSeek() throws Exception {
        PcmAudioPlayer player = player((asset, speed) -> { throw new IOException("test output failure"); }, 10);
        player.start(0, 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (callbacks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        assertFalse(callbacks.isEmpty()); player.seek(5); drainCallbacks();
        assertEquals(0, errors); assertEquals(5, player.positionMillis());
    }

    @Test public void seekNearEndDrainsEvenBelowNativeStartThreshold() throws Exception {
        FakeOutput output = new FakeOutput() {
            @Override public int startThresholdFrames() { return 20; }
            @Override public synchronized int write(byte[] pcm, int offset, int length) {
                int written = super.write(pcm, offset, length);
                if (data.size() >= 40) head = data.size() / 2;
                return written;
            }
        };
        output.capacity = 40;
        PcmAudioPlayer player = player((asset, speed) -> output, 100);
        player.start(97, 1); await(output.released); drainCallbacks();
        assertEquals(97, output.firstSample());
        assertEquals(100, player.positionMillis());
        assertEquals(1, completions); assertEquals(0, errors);
    }
}
