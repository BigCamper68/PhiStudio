package com.xpe.mobile.playtest;

/** Countdown freezes chart time. Music is the master clock, with a monotonic silent tail. */
public final class PlaytestClock {
    public enum State { COUNTDOWN, PLAYING, PAUSED, FINISHED }
    private State state;
    private final int countdown;
    private double seconds, baseSeconds;
    private long deadline, baseWall;
    private boolean startAudio;

    public PlaytestClock(double start, int countdown, long now) {
        this.countdown = countdown; seconds = baseSeconds = start;
        beginCountdown(now);
    }
    public State state() { return state; }
    public double seconds() { return seconds; }
    public int remaining(long now) { return (int) Math.max(0, (deadline - now + 999) / 1000); }

    private void beginCountdown(long now) {
        deadline = now + countdown * 1000L;
        state = State.COUNTDOWN; baseWall = now;
    }

    public void tick(long now, double audioChartSeconds, boolean audioPlaying) {
        if (state == State.COUNTDOWN) {
            if (now < deadline) return;
            state = State.PLAYING; baseWall = now; baseSeconds = seconds; startAudio = true;
            return;
        }
        if (state != State.PLAYING) return;
        if (audioPlaying && Double.isFinite(audioChartSeconds)) {
            seconds = Math.max(seconds, audioChartSeconds);
            baseWall = now; baseSeconds = seconds;
        } else seconds = Math.max(seconds, baseSeconds + Math.max(0, now - baseWall) / 1000.0);
    }

    public boolean takeAudioStart() { boolean next = startAudio; startAudio = false; return next; }
    public void pause() {
        if (state == State.FINISHED || state == State.PAUSED) return;
        state = State.PAUSED; startAudio = false;
    }
    public void resume(long now) { if (state == State.PAUSED) beginCountdown(now); }
    public void finish() { state = State.FINISHED; startAudio = false; }
}
