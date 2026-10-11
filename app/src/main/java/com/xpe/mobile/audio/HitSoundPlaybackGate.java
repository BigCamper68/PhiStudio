package com.xpe.mobile.audio;

/** Freeze the source clock while required custom samples load; keep only the latest start. */
final class HitSoundPlaybackGate {
    static final class Request {
        final long positionMs;
        final float speed;
        Request(long positionMs, float speed) { this.positionMs = positionMs; this.speed = speed; }
    }
    private Request pending;
    boolean request(long positionMs, float speed, boolean loading) {
        pending = loading ? new Request(positionMs, speed) : null;
        return !loading;
    }
    boolean isWaiting() { return pending != null; }
    long positionMillis() { return pending == null ? 0 : pending.positionMs; }
    Request takeIfReady(boolean ready) {
        if (!ready) return null;
        Request result = pending;
        pending = null;
        return result;
    }
    void cancel() { pending = null; }
}
