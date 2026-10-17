package com.xpe.mobile.video;

/** Validated, immutable settings. All timestamps are on the music/preview timeline. */
public final class VideoExportOptions {
    public final int width, height, fps, bitrate;
    public final long startMs, endMs;
    public final float musicVolume, hitVolume;
    public final boolean music, hits, shaders, hud;

    public VideoExportOptions(int width, int height, int fps, int bitrate,
                              long startMs, long endMs, boolean music, boolean hits,
                              float musicVolume, float hitVolume, boolean shaders, boolean hud) {
        if (width < 256 || height < 256 || width > 3840 || height > 2160
                || (width & 1) != 0 || (height & 1) != 0)
            throw new IllegalArgumentException("Resolution must be even, between 256×256 and 3840×2160");
        if (fps != 30 && fps != 60) throw new IllegalArgumentException("Choose 30 or 60 FPS");
        if (bitrate < 1_000_000 || bitrate > 80_000_000)
            throw new IllegalArgumentException("Video bitrate must be between 1 and 80 Mbps");
        if (startMs < -24 * 60 * 60 * 1000L || endMs <= startMs || endMs > 24 * 60 * 60 * 1000L)
            throw new IllegalArgumentException("End must follow start; maximum end time is 24 hours");
        if (!Float.isFinite(musicVolume) || !Float.isFinite(hitVolume)
                || musicVolume < 0 || musicVolume > 1 || hitVolume < 0 || hitVolume > 1)
            throw new IllegalArgumentException("Volume must be between 0 and 100%");
        this.width = width; this.height = height; this.fps = fps; this.bitrate = bitrate;
        this.startMs = startMs; this.endMs = endMs;
        this.music = music; this.hits = hits; this.musicVolume = musicVolume;
        this.hitVolume = hitVolume; this.shaders = shaders; this.hud = hud;
    }

    public long frameCount() { return ((endMs - startMs) * fps + 999) / 1000; }
    public long frameTimeUs(long frame) { return frame * 1_000_000L / fps; }
    /** Same unquantized timestamp for the scene and shader layers, excluding both offsets. */
    public double chartSeconds(long frame, int chartOffsetMs, long packageOffsetMs) {
        return Math.max(0.0, (startMs - (double) chartOffsetMs - packageOffsetMs) / 1000.0
                + frame / (double) fps);
    }
    public long durationUs() { return frameTimeUs(frameCount()); }
    public long estimatedBytes() {
        return Math.round(durationUs() / 1_000_000.0 * (bitrate + (music || hits ? 192_000 : 0)) / 8);
    }
}
