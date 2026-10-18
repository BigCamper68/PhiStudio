package com.xpe.mobile.audio;

import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs the real streaming worker against a bounded, manually clocked audio device. */
public class HitSoundPlayerTimingTest {
    private static final int BLOCK = HitSoundStreamMixer.BLOCK_FRAMES;

    @Test public void separateHitsFiveMillisecondsApartStayCloseAfterTheOutputBufferFills() throws Exception {
        for (int capacity : new int[]{4, 8, 16}) {
            Device device = new Device(capacity * BLOCK);
            try (HitSoundPlayer player = player(device)) {
                player.prepare();
                // On checkpoint12 prepare is a no-op: the first hit creates/fills the track.
                // On the corrected implementation this time primes it with silence.
                device.allowPreparation();
                player.play(cue(NoteType.TAP));
                device.awaitFull();
                device.advance(BLOCK);
                device.awaitFull();
                player.play(cue(NoteType.FLICK));
                for (int i = 0; i < capacity + 4; i++) {
                    device.advance(BLOCK);
                    device.awaitFull();
                }
                int first = device.firstNonzero(0), second = device.firstNonzero(1);
                assertTrue("first hit is audible", first >= 0);
                assertTrue("second hit is audible", second >= 0);
                assertEquals("5 ms between calls must not become the device buffer duration (capacity="
                        + capacity + ")", BLOCK, second - first);
                System.out.println("Device buffer " + (capacity * 5) + " ms: input gap 5 ms, PCM onset gap "
                        + ((second - first) / 48.0) + " ms");
            }
        }
    }

    @Test public void hitsArrivingWhileTheDeviceIsFullShareItsNextWritableBlock() throws Exception {
        Device device = new Device(4 * BLOCK);
        try (HitSoundPlayer player = player(device)) {
            player.prepare(); device.awaitFull();
            player.play(cue(NoteType.TAP));
            // Let the worker try to write before the second hit reaches it.
            Thread.sleep(15);
            player.play(cue(NoteType.FLICK));
            device.advance(BLOCK); device.awaitFull();
            assertEquals("No stale block is mixed while waiting for output space",
                    4 * BLOCK, device.firstNonzero(0));
            assertEquals(device.firstNonzero(0), device.firstNonzero(1));
        }
    }

    @Test public void pauseDiscardsPendingHitsBeforeRepriming() throws Exception {
        Device device = new Device(4 * BLOCK);
        try (HitSoundPlayer player = player(device)) {
            player.prepare(); device.awaitFull();
            player.play(cue(NoteType.TAP)); player.stop(); player.prepare(); device.awaitFull();
            for (int i = 0; i < 4; i++) { device.advance(BLOCK); device.awaitFull(); }
            assertEquals("Pending clicks must not leak into Resume or Retry", -1, device.firstNonzero(0));
        }
    }

    @Test public void mutingAndReplacingSamplesKeepThePreparedOutputAlive() throws Exception {
        Device device = new Device(4 * BLOCK);
        try (HitSoundPlayer player = player(device)) {
            player.prepare(); device.awaitFull();
            player.setVolume(0); device.awaitFull();
            player.play(cue(NoteType.TAP));
            device.advance(BLOCK); device.awaitFull(); assertEquals(-1, device.firstNonzero(0));
            player.setCustomSounds(java.util.Collections.emptyMap()); device.awaitFull();
            player.setVolume(.5f); player.play(cue(NoteType.TAP));
            device.advance(BLOCK); device.awaitFull();
            assertEquals(500, (short) device.pcm.get(device.firstNonzero(0) * 2));
        }
    }

    @Test public void preparationAndSilentGapsKeepFeedingTheDeviceUntilPause() throws Exception {
        Device device = new Device(4 * BLOCK);
        try (HitSoundPlayer player = player(device)) {
            player.prepare(); device.awaitFull();
            assertEquals(-1, device.firstNonzero(0));
            for (int i = 0; i < 25; i++) { device.advance(BLOCK); device.awaitFull(); }
            assertTrue(device.written() >= 29 * BLOCK);
            player.stop();
            int written = device.written();
            assertFalse(device.isPlaying()); assertEquals(0, device.queued());
            device.advance(4 * BLOCK);
            Thread.sleep(20);
            assertEquals("Paused output must stay asleep", written, device.written());
            player.prepare(); device.awaitFull(); assertTrue(device.isPlaying());
            assertEquals("Resume reuses the native track", 1, device.creations);
        }
        device.awaitReleased();
    }

    private static HitSoundPlayer player(Device device) {
        // Long stereo samples isolate the first/second note in left/right channels.
        short[] left = new short[4800 * 2], right = left.clone();
        left[0] = 1000; right[1] = 2000;
        PcmHitSound a = new PcmHitSound(48000, 2, left), b = new PcmHitSound(48000, 2, right);
        return new HitSoundPlayer(new HitSoundStreamMixer(a, b, a), Runnable::run,
                cue -> fail("Unexpected fallback"), () -> { device.creations++; return device; });
    }

    private static HitSoundTimeline.Cue cue(NoteType type) {
        Note note = new Note(); note.type = type; return HitSoundTimeline.forNote(note);
    }

    private static final class Device implements HitSoundPlayer.Output {
        final int capacity;
        final List<Short> pcm = new ArrayList<>();
        int consumed, creations;
        boolean playing, released;
        Device(int capacity) { this.capacity = capacity; }
        public synchronized boolean isPlaying() { return playing; }
        public synchronized int writableFrames() { return capacity - queued(); }
        public synchronized void play() { playing = true; notifyAll(); }
        public synchronized int write(byte[] data, int offset, int length) {
            int frames = Math.min(length / 4, capacity - queued());
            for (int i = offset; i < offset + frames * 4; i += 2)
                pcm.add((short) ((data[i] & 255) | data[i + 1] << 8));
            notifyAll(); return frames * 4;
        }
        public synchronized void pause() { playing = false; }
        public synchronized void flush() { consumed = written(); notifyAll(); }
        public synchronized void release() { released = true; notifyAll(); }
        synchronized void advance(int frames) { consumed = Math.min(written(), consumed + frames); notifyAll(); }
        synchronized int queued() { return written() - consumed; }
        synchronized int written() { return pcm.size() / 2; }
        synchronized int firstNonzero(int channel) {
            for (int i = channel; i < pcm.size(); i += 2) if (pcm.get(i) != 0) return i / 2;
            return -1;
        }
        synchronized void allowPreparation() throws Exception {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80);
            while (queued() < capacity && System.nanoTime() < until) wait(2);
        }
        synchronized void awaitFull() throws Exception { await(() -> queued() == capacity); }
        synchronized void awaitReleased() throws Exception { await(() -> released); }
        private void await(BooleanSupplier condition) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!condition.getAsBoolean() && System.nanoTime() < until) wait(2);
            assertTrue("Audio worker did not reach expected device state", condition.getAsBoolean());
        }
    }
}
