package com.xpe.mobile.audio;

import android.content.Context;
import android.content.res.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Loads a bundled 48 kHz stereo little-endian PCM resource without a platform codec. */
final class HitSoundPcmLoader {
    private static final int MAX_SAMPLE_BYTES = 2 * 1024 * 1024;

    private HitSoundPcmLoader() {
    }

    static PcmHitSound load(Context context, int resourceId) throws IOException {
        if (context == null) throw new IllegalArgumentException("Context is required");
        try (InputStream input = context.getResources().openRawResource(resourceId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] transfer = new byte[16 * 1024];
            int read;
            while ((read = input.read(transfer)) != -1) {
                if (output.size() > MAX_SAMPLE_BYTES - read) {
                    throw new IOException("Hit-sound PCM resource is too large");
                }
                output.write(transfer, 0, read);
            }
            byte[] pcm = output.toByteArray();
            if (pcm.length == 0 || pcm.length % HitSoundPcmMixer.OUTPUT_BYTES_PER_FRAME != 0) {
                throw new IOException("Hit-sound PCM resource contains incomplete frames");
            }
            return PcmHitSound.fromLittleEndianPcm16(
                    HitSoundPcmMixer.OUTPUT_SAMPLE_RATE,
                    HitSoundPcmMixer.OUTPUT_CHANNEL_COUNT,
                    pcm);
        } catch (Resources.NotFoundException | IllegalArgumentException exception) {
            throw new IOException("Hit-sound PCM resource is unavailable", exception);
        }
    }
}
