package com.xpe.mobile.audio;

import java.io.IOException;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public class PcmOutputSetupTest {
    static class Track {
        final int bytes; final boolean fast;
        boolean prepared, released;
        float speed = 1;
        Track(int bytes, boolean fast) { this.bytes = bytes; this.fast = fast; }
    }
    static class Backend implements PcmOutputSetup.Backend<Track> {
        List<Track> tracks = new ArrayList<>();
        boolean rejectFast, rejectFirstSpeed, rejectAllSpeeds;
        int speedCalls;
        public Track create(int bytes, boolean fast) throws IOException {
            if (rejectFast && fast) throw new IOException("FAST unavailable");
            Track track = new Track(bytes, fast); tracks.add(track); return track;
        }
        public void setSpeed(Track track, float speed) {
            speedCalls++;
            if (track.fast || rejectAllSpeeds || rejectFirstSpeed && speedCalls == 1) {
                throw new IllegalArgumentException("arguments out of range");
            }
            track.speed = speed;
        }
        public void prepare(Track track) { track.prepared = true; }
        public void release(Track track) { track.released = true; }
    }

    @Test public void normalSpeedNeverCallsRejectedFastPlaybackParams() throws Exception {
        Backend backend = new Backend();
        Track old = backend.create(3840, true);
        assertThrows(IllegalArgumentException.class, () -> backend.setSpeed(old, 1f));
        backend.release(old);
        for (int rate : new int[]{44100, 48000}) {
            Backend fixed = new Backend();
            Track track = PcmOutputSetup.open(rate, 4, 4096, 1, fixed);
            assertEquals(0, fixed.speedCalls); assertTrue(track.prepared);
            assertFalse(track.released); assertEquals(1, track.speed, 0);
        }
    }

    @Test public void alteredSpeedUsesNormalOutputAndRetriesWithLargerFreshTrack() throws Exception {
        for (float speed : new float[]{.25f, .5f, .75f, 2f}) {
            Backend backend = new Backend(); backend.rejectFirstSpeed = true;
            Track track = PcmOutputSetup.open(48000, 4, 4096, speed, backend);
            assertEquals(2, backend.tracks.size());
            assertTrue(backend.tracks.get(0).released);
            assertFalse(backend.tracks.get(0).fast); assertFalse(track.fast);
            assertTrue(track.bytes > backend.tracks.get(0).bytes);
            assertEquals(speed, track.speed, 0); assertTrue(track.prepared);
        }
    }

    @Test public void unsupportedLowLatencyFallsBackAndAlignsFrames() throws Exception {
        Backend backend = new Backend(); backend.rejectFast = true;
        Track track = PcmOutputSetup.open(11025, 4, 1025, 1, backend);
        assertFalse(track.fast); assertEquals(0, track.bytes % 4);
        assertEquals(0, backend.speedCalls); assertTrue(track.prepared);
    }

    @Test public void unrecoverableFailureReleasesBothTracksAndDoesNotChangeSpeedSilently() {
        Backend backend = new Backend(); backend.rejectAllSpeeds = true;
        IOException error = assertThrows(IOException.class,
                () -> PcmOutputSetup.open(48000, 4, 4096, .5f, backend));
        assertTrue(error.getMessage().contains("48000 Hz, 0.5x"));
        assertEquals(2, backend.tracks.size());
        for (Track track : backend.tracks) assertTrue(track.released);
    }

    @Test public void suppliedArchiveAudioFormatsUseCorrectOutputSetup() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR"); Assume.assumeNotNull(directory);
        for (String name : new String[]{"Sphalerite.zip", "oblivionphim.pez", "Justice Rectifier.zip"}) {
            Assume.assumeTrue("Fixture not supplied: " + name, new java.io.File(directory, name).isFile());
            try (ZipFile zip = new ZipFile(directory + "/" + name)) {
                var audio = zip.stream().filter(e -> e.getName().endsWith(".ogg")
                        || e.getName().endsWith(".mp3")).findFirst().orElseThrow();
                byte[] bytes;
                try (var input = zip.getInputStream(audio)) { bytes = input.readAllBytes(); }
                int[] format = audio.getName().endsWith(".ogg") ? vorbis(bytes) : mp3(bytes);
                assertEquals(48000, format[0]); assertEquals(2, format[1]);
                for (float speed : new float[]{1, .75f, .5f}) {
                    Backend backend = new Backend();
                    Track track = PcmOutputSetup.open(format[0], format[1] * 2, 4096, speed, backend);
                    assertTrue(track.prepared); assertEquals(speed, track.speed, 0);
                }
                System.out.println(name + ": " + format[0] + " Hz, " + format[1]
                        + " channels; output setup passes 1x/0.75x/0.5x with FAST-rejecting test backend");
            }
        }
    }

    private static int[] vorbis(byte[] b) {
        byte[] magic = {1, 'v', 'o', 'r', 'b', 'i', 's'};
        for (int i = 0; i + 16 < b.length; i++) {
            boolean match = true;
            for (int j = 0; j < magic.length; j++) if (b[i + j] != magic[j]) match = false;
            if (match) return new int[]{(b[i + 12] & 255) | (b[i + 13] & 255) << 8
                    | (b[i + 14] & 255) << 16 | (b[i + 15] & 255) << 24, b[i + 11] & 255};
        }
        throw new AssertionError("Missing Vorbis identification header");
    }

    private static int[] mp3(byte[] b) {
        int[] rates = {44100, 48000, 32000};
        int[] kbps = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        for (int i = 0; i + 4 < b.length; i++) {
            if ((b[i] & 255) != 255 || (b[i + 1] & 254) != 250) continue; // MPEG1 Layer III
            int r = (b[i + 2] >>> 2) & 3, bitrate = (b[i + 2] >>> 4) & 15;
            if (r == 3 || bitrate == 0 || bitrate == 15) continue;
            int next = i + 144000 * kbps[bitrate] / rates[r] + ((b[i + 2] >>> 1) & 1);
            if (next + 3 >= b.length || (b[next] & 255) != 255
                    || (b[next + 1] & 254) != 250 || ((b[next + 2] >>> 2) & 3) != r) continue;
            return new int[]{rates[r], (b[i + 3] & 192) == 192 ? 1 : 2};
        }
        throw new AssertionError("Missing consecutive MPEG1 Layer III frames");
    }
}
