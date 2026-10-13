package com.xpe.mobile.video;

import android.media.MediaCodec;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** One encoded track on disk; avoids buffering either stream while awaiting the other format. */
final class CodecTrackWriter implements AutoCloseable {
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final long durationUs;
    private boolean started, ended;
    private int track;
    private long lastProgress = System.nanoTime();

    CodecTrackWriter(File output, long durationUs) throws IOException {
        this.durationUs = durationUs;
        muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    boolean drain(MediaCodec codec, boolean wait) throws IOException {
        while (!ended) {
            Mp4Exporter.checkCancelled();
            int index = codec.dequeueOutputBuffer(info, wait ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (wait && System.nanoTime() - lastProgress > 30_000_000_000L)
                    throw new IOException("Encoder stopped responding");
                return ended;
            }
            lastProgress = System.nanoTime();
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (started) throw new IOException("Encoder changed format twice");
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start(); started = true;
            } else if (index >= 0) {
                try {
                    // AAC encoders may emit priming/padding outside the requested interval.
                    if (info.size > 0 && info.presentationTimeUs >= 0 && info.presentationTimeUs < durationUs
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!started) throw new IOException("Encoder format unavailable");
                        ByteBuffer data = codec.getOutputBuffer(index);
                        if (data == null) throw new IOException("Encoder output unavailable");
                        data.position(info.offset); data.limit(info.offset + info.size);
                        muxer.writeSampleData(track, data, info);
                    }
                    ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally { codec.releaseOutputBuffer(index, false); }
            }
        }
        return ended;
    }

    void finish(MediaCodec codec, long durationUs) throws IOException {
        lastProgress = System.nanoTime();
        while (!ended) drain(codec, true);
        if (!started) throw new IOException("Encoder produced no track");
        // Explicit end timestamp gives the final video frame its full frame duration.
        info.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), info);
        muxer.stop(); started = false;
    }

    @Override public void close() {
        try { if (started) muxer.stop(); }
        finally { started = false; muxer.release(); }
    }
}
