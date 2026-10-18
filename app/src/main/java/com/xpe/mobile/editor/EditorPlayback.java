package com.xpe.mobile.editor;

import android.os.SystemClock;
import com.xpe.mobile.R;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.audio.PlaybackTimeline;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;

/** EditorPlayback responsibilities extracted from EditorChromeView. */
final class EditorPlayback {
    private final EditorChromeView delegateOwner;
    private final PlaybackTimeCursor timeCursor = new PlaybackTimeCursor();

    EditorPlayback(EditorChromeView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    double currentSeconds() {
        return timeCursor.seconds(delegateOwner.chart, delegateOwner.currentBeat);
    }

    void setCurrentSeconds(double seconds) {
        if (delegateOwner.chart != null) {
            delegateOwner.currentBeat = timeCursor.beatAtSeconds(delegateOwner.chart, seconds);
        }
    }

    long audioPositionMillis() {
        return Math.round(timeCursor.audioSeconds(delegateOwner.chart, delegateOwner.currentBeat,
                delegateOwner.packageOffsetMs) * 1000);
    }

    void setAudioPositionSeconds(double seconds) {
        if (delegateOwner.chart != null) delegateOwner.currentBeat = timeCursor.beatAtAudioSeconds(
                delegateOwner.chart, seconds, delegateOwner.packageOffsetMs);
    }

    long audioStartMillis() {
        return PlaybackTimeline.startMillis(delegateOwner.chart.offsetMs, delegateOwner.packageOffsetMs);
    }

    void togglePlay() {
        if (delegateOwner.playing) {
            delegateOwner.playing = false;
            delegateOwner.playbackScrubbing = false;
            delegateOwner.editorPlayback.clearHitSoundPlayback();
            if (delegateOwner.callback != null) delegateOwner.callback.pauseAudio();
        } else {
            delegateOwner.playing = true;
            delegateOwner.playbackScrubbing = false;
            delegateOwner.editorPlayback.prepareHitSoundPlayback(true);
            delegateOwner.lastFrameMs = SystemClock.elapsedRealtime();
            if (delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
                delegateOwner.callback.startAudio(audioPositionMillis(), delegateOwner.playbackSpeed);
            }
        }
        delegateOwner.invalidate();
    }

    void cyclePlaybackSpeed() {
        if (Math.abs(delegateOwner.playbackSpeed - 1.0f) < 0.01f) delegateOwner.playbackSpeed = 0.75f;
        else if (Math.abs(delegateOwner.playbackSpeed - 0.75f) < 0.01f) delegateOwner.playbackSpeed = 0.5f;
        else delegateOwner.playbackSpeed = 1.0f;
        if (delegateOwner.playing && delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
            delegateOwner.editorPlayback.prepareHitSoundPlayback(false);
            delegateOwner.callback.startAudio(audioPositionMillis(), delegateOwner.playbackSpeed);
        }
        delegateOwner.invalidate();
    }

    void advancePlayback() {
        long now = SystemClock.elapsedRealtime();
        if (delegateOwner.playbackScrubbing) {
            delegateOwner.lastFrameMs = now;
            if (delegateOwner.playing) delegateOwner.postInvalidateOnAnimation();
            return;
        }
        if (delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
            setAudioPositionSeconds(delegateOwner.callback.audioPositionSeconds());
            if (!delegateOwner.callback.isAudioPlaying()) {
                delegateOwner.playing = false;
                delegateOwner.editorPlayback.clearHitSoundPlayback();
            } else {
                delegateOwner.editorPlayback.dispatchHitSounds();
            }
        } else {
            long delta = Math.max(0L, now - delegateOwner.lastFrameMs);
            setCurrentSeconds(currentSeconds() + delta * delegateOwner.playbackSpeed / 1000.0);
            delegateOwner.editorPlayback.dispatchHitSounds();
        }
        delegateOwner.lastFrameMs = now;
        if (delegateOwner.playing) delegateOwner.postInvalidateOnAnimation();
    }

    void seekPlaybackToCurrentBeat() {
        delegateOwner.previewPlayback.reset();
        if (delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
            if (delegateOwner.playing) delegateOwner.editorPlayback.prepareHitSoundPlayback(false);
            delegateOwner.callback.startAudio(audioPositionMillis(), delegateOwner.playbackSpeed);
        }
    }

    final void beginPanGesture() {
        delegateOwner.gestureMode = GestureMode.PAN;
        delegateOwner.playbackScrubbing = delegateOwner.playing;
        if (delegateOwner.playbackScrubbing && delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
            delegateOwner.callback.pauseAudio();
        }
    }

    final boolean beginPlaybackSeek(float x, float y) {
        if (delegateOwner.callback == null || !delegateOwner.callback.isAudioReady() || delegateOwner.callback.audioDurationMs() <= 0L) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                    delegateOwner.getResources().getString(R.string.seek_audio_unavailable));
            return true;
        }
        delegateOwner.gestureStartX = x;
        delegateOwner.gestureStartY = y;
        delegateOwner.gestureLastY = y;
        delegateOwner.gestureMode = GestureMode.SEEK_BAR;
        delegateOwner.playbackScrubbing = true;
        if (delegateOwner.playing) delegateOwner.callback.pauseAudio();
        delegateOwner.updatePlaybackSeek(x);
        delegateOwner.invalidate();
        return true;
    }

    final void updatePlaybackSeek(float x) {
        delegateOwner.previewPlayback.reset();
        if (delegateOwner.callback == null || !delegateOwner.callback.isAudioReady()) return;
        long duration = delegateOwner.callback.audioDurationMs();
        double fraction = PlaybackScrubMapper.fractionForX(
                x, delegateOwner.playbackSeekBounds.left, delegateOwner.playbackSeekBounds.right);
        long position = PlaybackScrubMapper.positionForFraction(fraction, audioStartMillis(), duration);
        setAudioPositionSeconds(position / 1000.0);
    }

    final void finishPlaybackScrub() {
        if (!delegateOwner.playbackScrubbing) return;
        delegateOwner.playbackScrubbing = false;
        delegateOwner.lastFrameMs = SystemClock.elapsedRealtime();
        if (delegateOwner.callback != null && delegateOwner.callback.isAudioReady()) {
            long position = audioPositionMillis();
            if (delegateOwner.playing) {
                delegateOwner.editorPlayback.prepareHitSoundPlayback(false);
                delegateOwner.callback.startAudio(position, delegateOwner.playbackSpeed);
            }
            else delegateOwner.callback.seekAudio(position);
        }
    }

    void prepareHitSoundPlayback(boolean includeZeroBoundary) {
        delegateOwner.hitSoundTimeline = HitSoundTimeline.build(delegateOwner.chart,
                delegateOwner.settings.tapFlickHitsoundOffsetMs, delegateOwner.settings.dragHitsoundOffsetMs);
        if (delegateOwner.callback != null) delegateOwner.callback.configureHitSounds(delegateOwner.hitSoundTimeline, delegateOwner.chart.offsetMs, delegateOwner.packageOffsetMs);
        long currentTimeMs = delegateOwner.chart == null ? 0L : delegateOwner.chart.beatToMillis(delegateOwner.currentBeat);
        delegateOwner.lastHitSoundChartTimeMs = includeZeroBoundary && currentTimeMs == 0L
                ? -1L : currentTimeMs;
    }

    void clearHitSoundPlayback() {
        delegateOwner.hitSoundTimeline = HitSoundTimeline.empty();
        delegateOwner.lastHitSoundChartTimeMs = 0L;
    }

    void dispatchHitSounds() {
        if (!delegateOwner.playing || delegateOwner.chart == null) return;
        if (delegateOwner.callback != null && delegateOwner.callback.schedulesHitSounds()) return;
        long currentTimeMs = delegateOwner.chart.beatToMillis(delegateOwner.currentBeat);
        if (currentTimeMs < delegateOwner.lastHitSoundChartTimeMs) {
            delegateOwner.lastHitSoundChartTimeMs = currentTimeMs;
            return;
        }
        if (delegateOwner.callback != null) {
            for (HitSoundTimeline.Cue cue : delegateOwner.hitSoundTimeline.cuesBetween(
                    delegateOwner.lastHitSoundChartTimeMs, currentTimeMs)) {
                delegateOwner.callback.playHitSound(cue);
            }
        }
        delegateOwner.lastHitSoundChartTimeMs = currentTimeMs;
    }
}
