package com.xpe.mobile;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xpe.mobile.audio.HitSoundTimeline;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.PreviewViewportCalculator;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.playtest.PlaytestClock;
import com.xpe.mobile.preview.ChartEvaluator;
import com.xpe.mobile.preview.PlaytestJudge;
import com.xpe.mobile.preview.PreviewPlaybackState;
import com.xpe.mobile.preview.PreviewRenderer;
import com.xpe.mobile.preview.PreviewShaderView;
import com.xpe.mobile.preview.PreviewTextureDecoder;
import com.xpe.mobile.preview.RenderScene;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full-screen player. All chart input belongs to this window, never to the editor. */
@SuppressLint("ViewConstructor")
final class PlaytestView extends FrameLayout implements AutoCloseable {
    private final MainActivity activity;
    private final ChartDocument chart;
    private final EditorSettings settings;
    private final boolean rpe170;
    private final double startSeconds, offset, visualEnd;
    private final int countdown;
    private final Runnable exit;
    private PreviewRenderer renderer;
    private PreviewPlaybackState playback;
    private final PreviewShaderView shaders;
    private final View game, input;
    private final RectF area = new RectF(), viewport = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, float[]> waitingFingers = new LinkedHashMap<>();
    private final List<Note> hitNotes = new ArrayList<>();
    private Map<String, PreviewTextureDecoder.Texture> textures;
    private ScrollView panel;
    private PlaytestClock clock;
    private PlaytestJudge judge;
    private boolean audioStarted, closed;

    PlaytestView(MainActivity activity, ChartDocument chart, double startSeconds, int countdown, Runnable exit) {
        super(activity);
        this.activity = activity; this.chart = chart; this.startSeconds = startSeconds;
        this.countdown = countdown; this.exit = exit;
        settings = activity.editorView.getSettings();
        settings.highlightCurrentLine = false; settings.markLineId = false; settings.showFakeNoteOutline = false;
        rpe170 = activity.editorView.usesRpe170Speed();
        offset = com.xpe.mobile.audio.PlaybackTimeline.offsetMillis(chart.offsetMs,
                activity.currentPackageOffsetMs()) / 1000.0;
        visualEnd = activity.mainPlaytest.maximumStartSeconds(chart);
        setBackgroundColor(Color.BLACK);
        game = new View(activity) {
            @Override protected void onDraw(Canvas canvas) { drawGame(canvas); }
        };
        addView(game, new LayoutParams(-1, -1));
        shaders = new PreviewShaderView(activity);
        shaders.setFrameListener(game::invalidate);
        shaders.setErrorListener(message -> activity.showMessage(activity.getString(R.string.preview_shader_error, message)));
        addView(shaders, new LayoutParams(-1, -1));
        input = new View(activity) {
            @Override protected void onDraw(Canvas canvas) { drawCountdown(canvas); }
            @Override public boolean onTouchEvent(MotionEvent event) {
                int action = event.getActionMasked(), index = event.getActionIndex();
                if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                        && pauseTarget(event.getX(index), event.getY(index))) {
                    performClick(); return true;
                }
                return acceptTouch(event);
            }
            @Override public boolean performClick() { super.performClick(); togglePause(); return true; }
        };
        input.setClickable(true); input.setFocusable(true);
        UiText.description(input, R.string.playtest_accessibility);
        addView(input, new LayoutParams(-1, -1));
        restart();
    }

    private long now() { return SystemClock.uptimeMillis(); }
    private void restart() {
        activity.pauseAudio(); audioStarted = false;
        hitNotes.clear();
        if (renderer != null) renderer.release();
        renderer = activity.editorView.createPlaytestRenderer();
        textures = activity.editorView.playtestLineTextures();
        playback = new PreviewPlaybackState();
        judge = new PlaytestJudge(chart, rpe170, settings.playerWidth / (double) settings.playerHeight, startSeconds);
        judge.setListener(hit -> {
            if (hit.playsSound()) hitNotes.add(hit.note);
        });
        clock = new PlaytestClock(startSeconds, countdown, now());
        if (activity.audioController != null) activity.audioController.prepareManualHitSounds();
        waitingFingers.clear();
        if (panel != null) { removeView(panel); panel = null; }
        shaders.resetFailure(); game.invalidate(); input.invalidate();
    }

    PlaytestClock.State state() { return clock.state(); }
    PlaytestJudge judge() { return judge; }
    double seconds() { return clock.seconds(); }
    void relocalizeUi() { UiText.relocalize(this); game.invalidate(); input.invalidate(); }

    private void advanceClock() {
        boolean music = audioStarted && activity.audioController != null && activity.audioController.isPlaying();
        clock.tick(now(), music ? activity.audioController.positionSeconds() - offset : Double.NaN, music);
        if (clock.takeAudioStart()) {
            double audioSeconds = clock.seconds() + offset;
            audioStarted = activity.audioController != null && activity.audioController.isReady()
                    && audioSeconds < activity.audioController.durationMillis() / 1000.0;
            if (audioStarted) activity.audioController.start(Math.round(audioSeconds * 1000), 1);
            for (Map.Entry<Integer, float[]> finger : waitingFingers.entrySet()) {
                float[] p = finger.getValue();
                queue(finger.getKey(), PlaytestJudge.Phase.DOWN, p[0], p[1], clock.seconds());
            }
            waitingFingers.clear();
        }
    }

    void audioCompleted() {
        if (closed || !audioStarted) return;
        if (clock.state() == PlaytestClock.State.PLAYING && activity.audioController != null)
            clock.tick(now(), activity.audioController.positionSeconds() - offset, true);
        audioStarted = false; game.invalidate();
    }

    private void drawGame(Canvas canvas) {
        if (closed || viewport.isEmpty()) return;
        advanceClock();
        if (clock.state() == PlaytestClock.State.PLAYING) updateJudgements();
        Map<String, PreviewTextureDecoder.Texture> currentTextures = activity.editorView.playtestLineTextures();
        if (textures != currentTextures) { textures = currentTextures; renderer.setLineTextures(textures); }
        RenderScene scene = ChartEvaluator.evaluatePlaytest(chart, clock.seconds(),
                settings.highlightSimultaneousNotes, activity.audioDurationMs(), rpe170,
                viewport.width() / (double) viewport.height(), playback, judge);
        boolean submitted = shaders.submit(renderer, scene, viewport, area,
                activity.editorView.playtestBackground(), settings, -1, chart.getBackgroundDim(),
                activity.editorView.playtestShaders(), clock.seconds());
        if (!submitted) renderer.draw(canvas, scene, viewport, area,
                activity.editorView.playtestBackground(), settings, -1, chart.getBackgroundDim());
        double musicEnd = activity.isAudioReady() ? activity.audioDurationMs() / 1000.0 - offset : 0;
        double end = Math.max(judge.lastNoteSeconds() + PlaytestJudge.BAD,
                Math.max(visualEnd, musicEnd)) + .5;
        if (clock.state() == PlaytestClock.State.PLAYING && clock.seconds() >= end) {
            clock.finish(); activity.pauseAudio(); judge.clearTouches();
            post(() -> { if (!closed && clock.state() == PlaytestClock.State.FINISHED) showPanel(true); });
        }
        input.invalidate();
        if (clock.state() == PlaytestClock.State.COUNTDOWN || clock.state() == PlaytestClock.State.PLAYING)
            game.postInvalidateOnAnimation();
    }

    private void updateJudgements() {
        judge.update(clock.seconds());
        if (hitNotes.isEmpty()) return;
        // Per-note calls let the audio worker render between chord voices. Submit
        // the complete judgement pass under one mixer lock, with a shared onset.
        HitSoundTimeline.Cue cue = HitSoundTimeline.forNotes(hitNotes);
        hitNotes.clear();
        if (activity.audioController != null) activity.audioController.playHitSound(cue);
    }

    private void drawCountdown(Canvas canvas) {
        if (closed || clock.state() != PlaytestClock.State.COUNTDOWN) return;
        paint.setColor(0x60000000); canvas.drawRect(area, paint);
        paint.setTextAlign(Paint.Align.CENTER); paint.setColor(Color.WHITE);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.min(viewport.width() * .14f, viewport.height() * .28f));
        String label = Integer.toString(Math.max(1, clock.remaining(now())));
        float baseline = viewport.centerY() - (paint.ascent() + paint.descent()) * .5f;
        canvas.drawText(label, viewport.centerX(), baseline, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(activity.dp(14));
        canvas.drawText(activity.getString(R.string.menu_playtest), viewport.centerX(),
                viewport.centerY() - viewport.height() * .23f, paint);
    }

    private boolean pauseTarget(float x, float y) {
        if (viewport.isEmpty()) return false;
        // Phira's pause input target is fixed even when its HUD art is attached to a line.
        double aspect = viewport.width() / viewport.height();
        double px = (x - viewport.centerX()) * 2 / viewport.width();
        double py = (y - viewport.centerY()) * 2 / viewport.width();
        return Math.hypot(px + .94, py - (-1 / aspect + .07 / aspect + .024)) < .05;
    }

    private boolean acceptTouch(MotionEvent event) {
        if (closed) return true;
        int action = event.getActionMasked(), selected = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) { judge.clearTouches(); waitingFingers.clear(); return true; }
        if (clock.state() == PlaytestClock.State.PAUSED || clock.state() == PlaytestClock.State.FINISHED) return true;
        if (clock.state() == PlaytestClock.State.COUNTDOWN) {
            for (int i = 0; i < event.getPointerCount(); i++) waitingFingers.put(event.getPointerId(i),
                    new float[]{event.getX(i), event.getY(i)});
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                waitingFingers.remove(event.getPointerId(selected));
            return true;
        }
        // Remove UI delivery latency with MotionEvent's monotonic timestamps, retaining history.
        advanceClock();
        long received = now(); double current = clock.seconds();
        if (action == MotionEvent.ACTION_MOVE) {
            for (int h = 0; h < event.getHistorySize(); h++) for (int i = 0; i < event.getPointerCount(); i++)
                queue(event.getPointerId(i), PlaytestJudge.Phase.MOVE, event.getHistoricalX(i, h),
                        event.getHistoricalY(i, h), current - Math.max(0, received - event.getHistoricalEventTime(h)) / 1000.0);
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            PlaytestJudge.Phase phase = PlaytestJudge.Phase.MOVE;
            if (i == selected && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN))
                phase = PlaytestJudge.Phase.DOWN;
            else if (i == selected && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP))
                phase = PlaytestJudge.Phase.UP;
            queue(event.getPointerId(i), phase, event.getX(i), event.getY(i),
                    current - Math.max(0, received - event.getEventTime()) / 1000.0);
        }
        game.invalidate(); return true;
    }

    private void queue(int id, PlaytestJudge.Phase phase, float x, float y, double at) {
        if (viewport.isEmpty()) return;
        judge.queue(id, phase, (x - viewport.centerX()) * 2 / viewport.width(),
                (viewport.centerY() - y) * 2 / viewport.width(),
                x * 2 / getWidth() - 1, y * 2 / getHeight() - 1, at);
    }

    void togglePause() {
        if (closed || clock.state() == PlaytestClock.State.FINISHED) return;
        if (clock.state() == PlaytestClock.State.PAUSED) resume(); else pause();
    }
    void pause() {
        if (closed || clock.state() == PlaytestClock.State.PAUSED || clock.state() == PlaytestClock.State.FINISHED) return;
        advanceClock();
        if (clock.state() == PlaytestClock.State.PLAYING) updateJudgements();
        clock.pause(); activity.pauseAudio(); audioStarted = false;
        judge.clearTouches(); waitingFingers.clear();
        showPanel(false); game.invalidate(); input.invalidate();
    }
    private void resume() {
        if (closed) return;
        if (panel != null) { removeView(panel); panel = null; }
        clock.resume(now());
        if (activity.audioController != null) activity.audioController.prepareManualHitSounds();
        game.invalidate(); input.invalidate(); input.requestFocus();
    }

    private void showPanel(boolean result) {
        if (closed) return;
        if (panel != null) removeView(panel);
        panel = new ScrollView(activity); panel.setFillViewport(true); panel.setBackgroundColor(0xE611151A);
        panel.setClickable(true);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER); int pad = activity.dp(18); body.setPadding(pad, pad, pad, pad);
        panel.addView(body, new ScrollView.LayoutParams(-1, -1));
        TextView title = new TextView(activity); UiText.text(title, result ? R.string.playtest_results : R.string.playtest_paused);
        title.setTextSize(24); title.setTextColor(Color.WHITE); title.setGravity(Gravity.CENTER); body.addView(title);
        TextView stats = new TextView(activity); stats.setTextColor(Color.WHITE); stats.setTextSize(16);
        stats.setGravity(Gravity.CENTER); stats.setPadding(0, pad / 2, 0, pad / 2);
        UiText.text(stats, () -> activity.getString(R.string.playtest_stats, judge.score(), judge.accuracy() * 100,
                judge.maxCombo(), judge.total(), judge.count(PlaytestJudge.Grade.PERFECT),
                judge.count(PlaytestJudge.Grade.GOOD), judge.count(PlaytestJudge.Grade.BAD), judge.count(PlaytestJudge.Grade.MISS)));
        body.addView(stats);
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER); body.addView(actions);
        if (!result) action(actions, R.string.playtest_resume, this::resume);
        action(actions, R.string.playtest_retry, this::restart);
        action(actions, R.string.playtest_exit, exit);
        addView(panel, new LayoutParams(-1, -1)); panel.bringToFront(); panel.requestFocus();
    }

    private void action(LinearLayout row, int title, Runnable run) {
        Button button = WorkbenchView.button(activity, "");
        UiText.text(button, title);
        button.setTextSize(14); button.setMinHeight(activity.dp(48)); button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4)); row.addView(button, params);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        area.set(0, 0, w, h);
        PreviewViewportCalculator.Result fit = PreviewViewportCalculator.fit(0, 0, w, h, settings.playerWidth, settings.playerHeight);
        viewport.set((float) fit.left, (float) fit.top, (float) fit.right, (float) fit.bottom);
        judge.clearTouches(); waitingFingers.clear();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && clock != null) pause();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; judge.clearTouches(); waitingFingers.clear(); hitNotes.clear(); activity.pauseAudio();
        shaders.hidePreview(); renderer.release();
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
}
