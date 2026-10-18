package com.xpe.mobile.editor;

import com.xpe.mobile.model.ChartDocument;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EditorPlaybackTimingTest {
    private long requested;
    private double audioSeconds;
    private boolean audioPlaying;
    private EditorView editor(int offset) throws Exception {
        EditorView view = new EditorView(RuntimeEnvironment.getApplication());
        ChartDocument chart = ChartDocument.fromJson("{}"); chart.offsetMs = offset;
        view.setChart(chart); view.playbackSeekBounds.set(100, 0, 300, 40);
        view.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
                EditorView.Callback.class.getClassLoader(), new Class<?>[]{EditorView.Callback.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isAudioReady": return true;
                        case "isAudioPlaying": return audioPlaying;
                        case "audioDurationMs": return 123456L;
                        case "audioPositionMs": return Math.round(audioSeconds * 1000);
                        case "audioPositionSeconds": return audioSeconds;
                        case "startAudio": requested = (long) args[0]; audioPlaying = true; return null;
                        case "seekAudio": requested = (long) args[0]; return null;
                        case "pauseAudio": audioPlaying = false; return null;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == double.class) return 0.0;
                            return null;
                    }
                }));
        return view;
    }
    @Test public void seekBarReachesExactChartZeroAndTheOtherEndpoint() throws Exception {
        EditorView view = editor(-21);
        view.editorPlayback.beginPlaybackSeek(100, 10); view.editorPlayback.finishPlaybackScrub();
        assertEquals(-21, requested); assertEquals(0, view.currentBeat, 0);
        assertEquals(0, view.editorPlayback.currentSeconds(), 0);
        assertEquals("-0:00.021", EditorDrawingStyle.formatMillis(-21));
        view.editorPlayback.beginPlaybackSeek(300, 10); view.editorPlayback.finishPlaybackScrub();
        assertEquals(123456, requested); assertEquals(123.477, view.editorPlayback.currentSeconds(), 1e-12);
    }
    @Test public void positiveOffsetCanPauseAndResumeBeforeTheFirstChartBeat() throws Exception {
        EditorView view = editor(300); audioSeconds = .100; audioPlaying = true; view.playing = true;
        view.editorPlayback.advancePlayback(); assertEquals(0, view.currentBeat, 0);
        view.editorPlayback.togglePlay(); view.editorPlayback.togglePlay();
        assertEquals(100, requested);
    }
    @Test public void completionSetsTheExactFinalAudioPosition() throws Exception {
        EditorView view = editor(-21); audioSeconds = 123.456;
        view.audioCompleted();
        assertEquals(123456, view.editorPlayback.audioPositionMillis());
        assertEquals(123.477, view.editorPlayback.currentSeconds(), 1e-12);
    }
}
