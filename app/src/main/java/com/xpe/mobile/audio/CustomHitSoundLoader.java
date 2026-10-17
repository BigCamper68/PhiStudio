package com.xpe.mobile.audio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Decode once in the background; no file/codec work in the playback mixer. */
final class CustomHitSoundLoader {
    static final long MAX_DURATION_MS = 30_000;
    static final long MAX_ENCODED_BYTES = 20L * 1024 * 1024;
    static final long MAX_DECODED_BYTES = 12L * 1024 * 1024;

    static PcmHitSound load(Context context, File source) throws IOException {
        if (source.length() > MAX_ENCODED_BYTES) throw new IOException("Hit-sound file exceeds 20 MiB");
        File temporary = File.createTempFile("phistudio-hit-", ".pcm", context.getCacheDir());
        try {
            PcmAudioAsset asset = AudioPcmDecoder.decode(context, Uri.fromFile(source), temporary,
                    MAX_DECODED_BYTES, MAX_DURATION_MS);
            return PcmHitSound.fromLittleEndianPcm16(asset.sampleRate, asset.channelCount,
                    Files.readAllBytes(asset.file.toPath())).convertTo(48_000, 2);
        } finally {
            temporary.delete();
        }
    }
}
