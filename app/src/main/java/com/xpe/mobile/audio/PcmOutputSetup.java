package com.xpe.mobile.audio;

import java.io.IOException;

/** Negotiates output without asking a FAST track to enable time stretching at normal speed. */
final class PcmOutputSetup {
    interface Backend<T> {
        T create(int bufferBytes, boolean lowLatency) throws IOException;
        void setSpeed(T track, float speed);
        void prepare(T track);
        void release(T track);
    }

    static <T> T open(int sampleRate, int frameBytes, int minimumBytes, float speed,
                      Backend<T> backend) throws IOException {
        if (sampleRate <= 0 || frameBytes <= 0 || minimumBytes <= 0
                || !Float.isFinite(speed) || speed < .25f || speed > 2f) {
            throw new IOException("Invalid PCM output configuration");
        }
        boolean normalSpeed = speed == 1f;
        Exception previous = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean lowLatency = normalSpeed && attempt == 0;
            // Time stretching needs a conventional track and enough source frames. A failed
            // route gets a fresh track, not setBufferSizeInFrames beyond its fixed capacity.
            int milliseconds = lowLatency ? 20 : attempt == 0 ? 250 : 500;
            long requested = (long) Math.ceil(sampleRate * (double) frameBytes
                    * milliseconds / 1000 * Math.max(1f, speed));
            long aligned = ((Math.max(minimumBytes, requested) + frameBytes - 1) / frameBytes) * frameBytes;
            if (aligned > Integer.MAX_VALUE) throw new IOException("PCM output buffer is too large");
            T track = null;
            try {
                track = backend.create((int) aligned, lowLatency);
                // Android already starts at speed=1/pitch=1. Calling setPlaybackParams even
                // with those values can reject FAST output with 'arguments out of range'.
                if (!normalSpeed) backend.setSpeed(track, speed);
                backend.prepare(track);
                return track;
            } catch (IOException | RuntimeException exception) {
                if (track != null) backend.release(track);
                if (previous != null) exception.addSuppressed(previous);
                previous = exception;
            }
        }
        throw new IOException("Cannot configure PCM output (" + sampleRate + " Hz, "
                + speed + "x): " + previous.getMessage(), previous);
    }
}
