package com.xpe.mobile.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public final class Mp3PcmDecoder {
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final int GAPLESS_SCAN_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final String ENCODER_DELAY_KEY = "encoder-delay";
    private static final String ENCODER_PADDING_KEY = "encoder-padding";

    private Mp3PcmDecoder() {
    }

    public static PcmAudioAsset decode(Context context, Uri source, File output)
            throws IOException {
        if (context == null || source == null || output == null) {
            throw new IllegalArgumentException("Context, source and output are required");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create PCM cache directory");
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean codecStarted = false;
        boolean completed = false;
        try {
            extractor.setDataSource(context, source, null);
            int trackIndex = findAudioTrack(extractor);
            if (trackIndex < 0) throw new IOException("MP3 contains no audio track");
            MediaFormat sourceFormat = extractor.getTrackFormat(trackIndex);
            String mime = sourceFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) {
                throw new IOException("Unsupported MP3 track format");
            }
            Mp3GaplessInfo parsedGapless = readGaplessInfo(context, source);
            int encoderDelayFrames = optionalInteger(sourceFormat, ENCODER_DELAY_KEY);
            int encoderPaddingFrames = optionalInteger(sourceFormat, ENCODER_PADDING_KEY);
            if (encoderDelayFrames == 0) {
                encoderDelayFrames = parsedGapless.encoderDelayFrames;
            }
            if (encoderPaddingFrames == 0) {
                encoderPaddingFrames = parsedGapless.encoderPaddingFrames;
            }

            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            // Some Android codecs consume encoder-delay / encoder-padding themselves while
            // others expose the untrimmed PCM. PhiStudio performs deterministic trimming below,
            // so disable codec-side gapless trimming to avoid deleting the delay twice and
            // shifting the chart clock by tens of milliseconds on affected devices.
            sourceFormat.setInteger(ENCODER_DELAY_KEY, 0);
            sourceFormat.setInteger(ENCODER_PADDING_KEY, 0);
            codec.configure(sourceFormat, null, null, 0);
            codec.start();
            codecStarted = true;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            OutputSpec outputSpec = null;
            long framesToSkip = encoderDelayFrames;
            long writtenFrames = 0L;

            try (FileOutputStream pcm = new FileOutputStream(output, false)) {
                while (!outputEnded) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("MP3 decoding cancelled");
                    }

                    if (!inputEnded) {
                        int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (inputIndex >= 0) {
                            ByteBuffer input = codec.getInputBuffer(inputIndex);
                            if (input == null) throw new IOException("MP3 decoder input unavailable");
                            input.clear();
                            int sampleSize = extractor.readSampleData(input, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                long presentationTimeUs = Math.max(0L, extractor.getSampleTime());
                                codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                        presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputSpec = outputSpec(codec.getOutputFormat(), outputSpec);
                    } else if (outputIndex >= 0) {
                        try {
                            if (info.size > 0
                                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (outputSpec == null) {
                                    outputSpec = outputSpec(
                                            codec.getOutputFormat(outputIndex), null);
                                }
                                ByteBuffer decoded = codec.getOutputBuffer(outputIndex);
                                if (decoded == null) {
                                    throw new IOException("MP3 decoder output unavailable");
                                }
                                int frameSize = outputSpec.bytesPerFrame();
                                if (info.size % frameSize != 0) {
                                    throw new IOException("MP3 decoder returned partial PCM frame");
                                }
                                long bufferFrames = info.size / frameSize;
                                long skippedFrames = Math.min(framesToSkip, bufferFrames);
                                framesToSkip -= skippedFrames;
                                int skippedBytes = Math.toIntExact(skippedFrames * frameSize);
                                int bytesToWrite = info.size - skippedBytes;
                                if (bytesToWrite > 0) {
                                    ByteBuffer slice = decoded.duplicate();
                                    slice.position(info.offset + skippedBytes);
                                    slice.limit(info.offset + info.size);
                                    writeBuffer(pcm, slice);
                                    writtenFrames += bytesToWrite / frameSize;
                                }
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false);
                        }
                    }
                }
            }

            if (outputSpec == null || writtenFrames <= 0L) {
                throw new IOException("MP3 decoder produced no PCM audio");
            }
            long padding = Math.min(writtenFrames, Math.max(0, encoderPaddingFrames));
            if (padding > 0L) {
                writtenFrames -= padding;
                try (RandomAccessFile pcm = new RandomAccessFile(output, "rw")) {
                    pcm.setLength(writtenFrames * outputSpec.bytesPerFrame());
                }
            }
            completed = true;
            return new PcmAudioAsset(output, outputSpec.sampleRate,
                    outputSpec.channelCount, writtenFrames);
        } catch (RuntimeException exception) {
            throw new IOException("MP3 decoder failed", exception);
        } finally {
            if (codec != null) {
                if (codecStarted) {
                    try {
                        codec.stop();
                    } catch (RuntimeException ignored) {
                        // Release below even when a vendor codec rejects stop after failure.
                    }
                }
                codec.release();
            }
            extractor.release();
            if (!completed) output.delete();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        return -1;
    }

    private static int optionalInteger(MediaFormat format, String key) {
        if (!format.containsKey(key)) return 0;
        try {
            return Math.max(0, format.getInteger(key));
        } catch (ClassCastException | NullPointerException ignored) {
            return 0;
        }
    }

    private static Mp3GaplessInfo readGaplessInfo(Context context, Uri source) {
        byte[] prefix = new byte[GAPLESS_SCAN_LIMIT_BYTES];
        int total = 0;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) return Mp3GaplessInfo.NONE;
            while (total < prefix.length) {
                int count = input.read(prefix, total, prefix.length - total);
                if (count < 0) break;
                if (count == 0) continue;
                total += count;
            }
            return Mp3GaplessInfo.parse(prefix, total);
        } catch (IOException | RuntimeException ignored) {
            return Mp3GaplessInfo.NONE;
        }
    }

    private static OutputSpec outputSpec(MediaFormat format, OutputSpec previous)
            throws IOException {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                : AudioFormat.ENCODING_PCM_16BIT;
        if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
            throw new IOException("MP3 decoder did not provide 16-bit PCM");
        }
        OutputSpec current = new OutputSpec(sampleRate, channelCount);
        if (previous != null && (previous.sampleRate != current.sampleRate
                || previous.channelCount != current.channelCount)) {
            throw new IOException("MP3 PCM format changed during decoding");
        }
        return current;
    }

    private static void writeBuffer(FileOutputStream output, ByteBuffer buffer)
            throws IOException {
        byte[] copy = new byte[Math.min(32 * 1024, Math.max(1, buffer.remaining()))];
        while (buffer.hasRemaining()) {
            int count = Math.min(copy.length, buffer.remaining());
            buffer.get(copy, 0, count);
            output.write(copy, 0, count);
        }
    }

    private static final class OutputSpec {
        final int sampleRate;
        final int channelCount;

        OutputSpec(int sampleRate, int channelCount) throws IOException {
            if (sampleRate <= 0 || channelCount < 1 || channelCount > 2) {
                throw new IOException("Unsupported decoded MP3 PCM format");
            }
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }

        int bytesPerFrame() {
            return channelCount * 2;
        }
    }
}
