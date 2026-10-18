package com.xpe.mobile.video;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.view.Surface;
import com.xpe.mobile.audio.AudioPcmDecoder;
import com.xpe.mobile.audio.ExportAudioMixer;
import com.xpe.mobile.audio.PcmAudioAsset;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.preview.ShaderTimeline;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Offline H.264/AAC export. Fixed timestamps, bounded memory, cancellable between frames. */
public final class Mp4Exporter {
    public enum Stage { PREPARING, VIDEO, AUDIO, FINALIZING, SAVING }
    public interface Progress { void update(Stage stage, long done, long total); }
    private Mp4Exporter() { }

    public static MediaFormat videoFormat(VideoExportOptions options) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, options.width, options.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, options.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return format;
    }

    public static String videoEncoder(VideoExportOptions options) throws IOException {
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat(options));
        if (name == null) throw new IOException("This device cannot encode " + options.width + "×" + options.height
                + " at " + options.fps + " FPS with this bitrate. Try 720p / 30 FPS or lower bitrate.");
        return name;
    }

    /** Caller owns the resulting file; staging files are always removed. */
    public static File export(Context context, ChartDocument chart, ChartPackage pack, Uri audioSource,
                              long trackDurationMs, long packageOffsetMs, EditorSettings settings,
                              ShaderTimeline shaders, VideoExportOptions options, Progress progress) throws IOException {
        checkCancelled();
        String encoder = videoEncoder(options);
        File storage = VideoExportStorage.directory(context);
        VideoExportStorage.requireSpace(storage, options);
        File directory = VideoExportStorage.createWorkspace(context);
        File video = new File(directory, "video.mp4"), audio = new File(directory, "audio.m4a");
        File pcm = new File(directory, "music.pcm");
        File result = null;
        boolean success = false;
        try {
            progress.update(Stage.PREPARING, 0, 1);
            PcmAudioAsset decoded = null;
            if (options.music && audioSource != null) {
                decoded = AudioPcmDecoder.decode(context, audioSource, pcm);
                trackDurationMs = decoded.durationMillis();
            }
            if (options.music && audioSource == null) throw new IOException("The project has no music. Disable Music for a silent export.");
            VideoExportStorage.requireSpace(storage, options);
            checkCancelled();
            encodeVideo(context, chart, pack, trackDurationMs, packageOffsetMs, settings,
                    options.shaders ? shaders : ShaderTimeline.EMPTY, options, encoder, video, progress);
            if (options.music || options.hits) {
                try (ExportAudioMixer mixer = new ExportAudioMixer(context, decoded, chart, pack, packageOffsetMs, options, settings)) {
                    encodeAudio(mixer, audio, options.durationUs(), progress);
                }
            }
            checkCancelled();
            progress.update(Stage.FINALIZING, 0, 1);
            result = File.createTempFile("phistudio-video-", ".mp4", storage);
            if (options.music || options.hits) remux(video, audio, result, options.durationUs(), progress);
            else Files.copy(video.toPath(), result.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (result.length() == 0) throw new IOException("Encoder produced an empty MP4");
            checkCancelled(); success = true;
            return result;
        } finally {
            VideoExportStorage.removeWorkspace(directory);
            if (!success && result != null) result.delete();
        }
    }

    private static void encodeVideo(Context context, ChartDocument chart, ChartPackage pack,
                                     long trackDurationMs, long packageOffsetMs, EditorSettings settings,
                                     ShaderTimeline shaders, VideoExportOptions options, String encoderName,
                                     File output, Progress progress) throws IOException {
        MediaCodec codec = MediaCodec.createByCodecName(encoderName);
        Surface input = null;
        boolean started = false;
        try {
            codec.configure(videoFormat(options), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start(); started = true;
            try (CodecTrackWriter writer = new CodecTrackWriter(output, options.durationUs());
                 ExportFrameRenderer renderer = new ExportFrameRenderer(context, input, chart, pack,
                         options, shaders, trackDurationMs, settings, packageOffsetMs)) {
                for (long frame = 0; frame < options.frameCount(); frame++) {
                    checkCancelled();
                    writer.drain(codec, false);
                    renderer.render(frame);
                    writer.drain(codec, false);
                    progress.update(Stage.VIDEO, frame + 1, options.frameCount());
                }
                codec.signalEndOfInputStream();
                writer.finish(codec, options.durationUs());
            }
        } finally {
            try { if (input != null) input.release(); }
            finally { try { if (started) codec.stop(); } finally { codec.release(); } }
        }
    }

    private static void encodeAudio(ExportAudioMixer mixer, File output, long durationUs, Progress progress) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, mixer.sampleRate, mixer.channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        boolean started = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); started = true;
            byte[] samples = new byte[16384];
            try (CodecTrackWriter writer = new CodecTrackWriter(output, durationUs)) {
                boolean inputEnded = false;
                long lastInput = System.nanoTime();
                while (!inputEnded) {
                    checkCancelled();
                    writer.drain(codec, false);
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index < 0) {
                        if (System.nanoTime() - lastInput > 30_000_000_000L) throw new IOException("Audio encoder stopped responding");
                        continue;
                    }
                    lastInput = System.nanoTime();
                    ByteBuffer input = codec.getInputBuffer(index);
                    if (input == null) throw new IOException("AAC encoder input unavailable");
                    input.clear();
                    long timestampUs = mixer.emittedFrames() * 1_000_000L / mixer.sampleRate;
                    int bytes = mixer.read(samples, input.remaining());
                    input.put(samples, 0, bytes);
                    inputEnded = bytes == 0;
                    codec.queueInputBuffer(index, 0, bytes, timestampUs,
                            inputEnded ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    writer.drain(codec, false);
                    progress.update(Stage.AUDIO, mixer.emittedFrames(), mixer.totalFrames);
                }
                writer.finish(codec, durationUs);
            }
        } finally { try { if (started) codec.stop(); } finally { codec.release(); } }
    }

    private static void remux(File video, File audio, File output, long durationUs, Progress progress) throws IOException {
        MediaExtractor[] extractors = {new MediaExtractor(), new MediaExtractor()};
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractors[0].setDataSource(video.getAbsolutePath());
            extractors[1].setDataSource(audio.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] tracks = new int[2];
            int capacity = 4 * 1024 * 1024;
            for (int i = 0; i < 2; i++) {
                MediaFormat format = extractors[i].getTrackFormat(0);
                tracks[i] = muxer.addTrack(format);
                extractors[i].selectTrack(0);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    capacity = Math.max(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (capacity > 32 * 1024 * 1024) throw new IOException("Encoded sample exceeds 32 MiB");
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            muxer.start(); started = true;
            while (true) {
                checkCancelled();
                long v = extractors[0].getSampleTime(), a = extractors[1].getSampleTime();
                if (v < 0 && a < 0) break;
                int i = v < 0 ? 1 : a < 0 || v <= a ? 0 : 1;
                MediaExtractor extractor = extractors[i];
                buffer.clear();
                int bytes = extractor.readSampleData(buffer, 0);
                if (bytes < 0) throw new IOException("Encoded sample unavailable");
                info.set(0, bytes, extractor.getSampleTime(),
                        (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(tracks[i], buffer, info);
                progress.update(Stage.FINALIZING, Math.min(durationUs, info.presentationTimeUs), durationUs);
                extractor.advance();
            }
            info.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            for (int track : tracks) muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), info);
            muxer.stop(); started = false;
        } finally {
            for (MediaExtractor extractor : extractors) extractor.release();
            if (muxer != null) { try { if (started) muxer.stop(); } finally { muxer.release(); } }
        }
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
    }
}
