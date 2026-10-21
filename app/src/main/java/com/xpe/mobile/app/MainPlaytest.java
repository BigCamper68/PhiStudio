package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import com.xpe.mobile.R;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.playtest.PlaytestClock;
import com.xpe.mobile.playtest.PlaytestOptions;
import com.xpe.mobile.playtest.PlaytestView;

/** Owns the playtest window and returns the shared audio transport to the editor on exit. */
final class MainPlaytest implements AutoCloseable {
    private final MainActivity activity;
    private Dialog window;
    private AlertDialog optionsDialog;
    private PlaytestView player;
    private double editorSeconds;

    MainPlaytest(MainActivity activity) { this.activity = activity; }
    boolean isOpen() { return player != null; }
    void relocalizeUi() { if (player != null) player.relocalizeUi(); }

    void showOptions() {
        ChartDocument chart = activity.editorView.getChart();
        if (chart == null || isOpen()) return;
        if (activity.audioController != null && activity.audioController.isLoading()) {
            activity.showMessage(activity.getString(R.string.playtest_audio_loading)); return;
        }
        activity.editorView.stopPlayback();
        double seconds = activity.editorView.shaderEditorSeconds();
        optionsDialog = PlaytestOptionsDialog.show(activity, chart, seconds,
                activity.editorView.getCurrentBeat(), start -> start(chart, start));
    }

    double maximumStartSeconds(ChartDocument chart) {
        double end = activity.isAudioReady() ? activity.audioDurationMs() / 1000.0
                - com.xpe.mobile.audio.PlaybackTimeline.offsetMillis(chart.offsetMs, activity.currentPackageOffsetMs()) / 1000.0 : 0;
        for (JudgeLine line : chart.judgeLines) for (Note note : line.notes) {
            if (note == null || note.startTime == null) continue;
            end = Math.max(end, chart.beatToSeconds(note.type == NoteType.HOLD && note.endTime != null
                    ? note.endTime.toDouble() : note.startTime.toDouble()));
        }
        return Math.max(0, end);
    }

    private void start(ChartDocument chart, double start) {
        if (isOpen() || activity.editorView.getChart() != chart) return;
        editorSeconds = activity.editorView.shaderEditorSeconds();
        activity.editorView.stopPlayback();
        if (activity.audioController != null) activity.audioController.setManualJudgements(true);
        Dialog dialog = new Dialog(activity, R.style.AppTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); dialog.setCancelable(false);
        player = new PlaytestView(activity, chart, start,
                PlaytestOptions.load(activity.getPreferences(MainActivity.MODE_PRIVATE)).countdown, dialog::dismiss);
        dialog.setContentView(player);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_SPACE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && player != null) {
                    if (key != KeyEvent.KEYCODE_SPACE && (player.state() == PlaytestClock.State.PAUSED
                            || player.state() == PlaytestClock.State.FINISHED)) dialog.dismiss();
                    else player.togglePause();
                }
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> releasePlayer());
        window = dialog;
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    void pause() { if (player != null) player.pause(); }
    boolean audioCompleted() {
        if (player == null) return false;
        player.audioCompleted(); return true;
    }
    private void releasePlayer() {
        if (player == null) return;
        player.close(); player = null; window = null;
        if (activity.audioController != null) activity.audioController.setManualJudgements(false);
        if (!activity.isDestroyed()) activity.editorView.shaderEditorSeek(editorSeconds);
        activity.hideSystemUi();
    }
    @Override public void close() {
        if (optionsDialog != null) { optionsDialog.dismiss(); optionsDialog = null; }
        if (window != null) window.dismiss();
        releasePlayer();
    }
}
