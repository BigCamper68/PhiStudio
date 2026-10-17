package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.xpe.mobile.preview.PlaytestJudge.Grade.*;
import static com.xpe.mobile.preview.PlaytestJudge.Phase.*;

public class PlaytestJudgeTest {
    private ChartDocument chart() throws Exception {
        ChartDocument c = ChartDocument.fromJson("{}");
        c.judgeLines.clear(); c.judgeLines.add(new JudgeLine()); return c;
    }
    private Note note(ChartDocument c, NoteType type, double time, double end, double x) {
        Note n = new Note(); n.type = type; n.startTime = BeatTime.fromDouble(time * 2, 100000);
        n.endTime = BeatTime.fromDouble(end * 2, 100000); n.positionX = x;
        c.judgeLines.get(0).notes.add(n); return n;
    }
    private PlaytestJudge judge(ChartDocument c) { return new PlaytestJudge(c, false, 16.0 / 9, 0); }
    private void touch(PlaytestJudge j, int id, PlaytestJudge.Phase phase, double at, double x, double y) {
        j.queue(id, phase, x, y, x, y, at); j.update(at);
    }
    private PlaytestJudge tap(double at) throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, at, 0, 0); return j;
    }

    @Test public void tapWindowsIncludePhirasAsymmetricLateOffset() throws Exception {
        assertEquals(1, tap(.95).count(PERFECT));
        assertEquals(1, tap(.88).count(GOOD));
        assertEquals(1, tap(.81).count(BAD));
        assertEquals(1, tap(1.149).count(PERFECT));
        assertEquals(1, tap(1.21).count(GOOD));
        assertEquals(0, tap(.779).judged());
    }
    @Test public void missOccursOnlyAfterTheBadWindowAndCannotBeRevived() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        j.update(1.219); assertEquals(0, j.judged());
        j.update(1.221); assertEquals(1, j.count(MISS));
        touch(j, 0, DOWN, 1.23, 0, 0); assertEquals(1, j.judged());
    }
    @Test public void judgementUsesLineXAndIgnoresDistanceAboveTheLine() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, 1, .23, 100); assertEquals(1, j.count(PERFECT));
        c = chart(); note(c, NoteType.TAP, 1, 1, 0); j = judge(c);
        touch(j, 0, DOWN, 1, .237, 0); assertEquals(0, j.judged());
    }
    @Test public void judgeAreaChangesInputWidthIndependentlyOfNoteSizeAndVisibility() throws Exception {
        ChartDocument c = chart(); Note n = note(c, NoteType.TAP, 1, 1, 0);
        n.judgeArea = 2; n.alpha = 0; n.size = .001; n.visibleTime = 0;
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, 1, .4, 100); assertEquals(1, j.count(PERFECT));
    }
    @Test public void oneDownSelectsOneTapAndASecondFingerHitsTheChord() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); note(c, NoteType.TAP, 1, 1, 0);
        PlaytestJudge j = judge(c); touch(j, 3, DOWN, 1, 0, 0); assertEquals(1, j.judged());
        j.update(1.01); assertEquals(1, j.judged());
        touch(j, 9, DOWN, 1.02, 0, 0); assertEquals(2, j.count(PERFECT)); assertEquals(1000000, j.score());
    }
    @Test public void aQuickDownAndUpBetweenFramesStillHitsOnlyOnce() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        j.queue(0, DOWN, 0, 0, 0, 0, 1); j.queue(0, UP, 0, 0, 0, 0, 1.005);
        j.update(1.01); j.update(1.02); assertEquals(1, j.count(PERFECT));
    }
    @Test public void badTapCannotBeScoredOrSoundedTwice() throws Exception {
        ChartDocument c = chart(); Note n = note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        List<PlaytestJudge.Hit> hits = new ArrayList<>(); j.setListener(hits::add);
        touch(j, 0, DOWN, .8, 0, 0); touch(j, 1, DOWN, .82, 0, 0);
        j.update(1.3); assertEquals(1, j.count(BAD)); assertEquals(1, j.judged());
        assertEquals(1, hits.size()); assertFalse(hits.get(0).playsSound()); assertTrue(j.hidden(n));
    }
    @Test public void tapPriorityAndSustainedDragCanShareOneFinger() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.DRAG, 1, 1, 0); note(c, NoteType.TAP, 1, 1, 0);
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, 1, 0, 0); assertEquals(2, j.count(PERFECT));
    }
    @Test public void overlappingHoldHeadUsesTheSameStableTieOrderAsPhirasLineCache() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); note(c, NoteType.HOLD, 1, 2, 0);
        PlaytestJudge j = judge(c); List<PlaytestJudge.Hit> hits = new ArrayList<>(); j.setListener(hits::add);
        touch(j, 0, DOWN, 1, 0, 0); assertEquals(0, j.judged()); assertTrue(hits.get(0).holdHead);
        touch(j, 1, DOWN, 1.01, 0, 0); assertEquals(1, j.count(PERFECT)); j.update(2);
        assertEquals(2, j.count(PERFECT));
    }
    @Test public void adjacentTapPriorityUsesJudgementWidthIndependentlyOfSpriteWidth() throws Exception {
        ChartDocument c = chart(); Note first = note(c, NoteType.TAP, 1, 1, 0);
        Note adjacent = note(c, NoteType.TAP, 1.008, 1.008, 87.75);
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, 1, .13, 0);
        assertTrue("The earlier note has no distance penalty inside 0.13175016", j.hidden(first));
        assertFalse(j.hidden(adjacent));
    }
    @Test public void earlyDragLatchesUntilItsTimeEvenAfterRelease() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.DRAG, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, .8, 0, .5); assertEquals(0, j.judged());
        touch(j, 0, UP, .85, 0, .5); j.update(1); assertEquals(1, j.count(PERFECT));
    }
    @Test public void dragCanBeCaughtByAFingerAlreadyDownButTapCannot() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.DRAG, 1, 1, 0); note(c, NoteType.TAP, 1, 1, 0);
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, .5, 0, 0); j.update(1); j.update(1.23);
        assertEquals(1, j.count(PERFECT)); assertEquals(1, j.count(MISS));
    }
    @Test public void aTapOrOneMovementDoesNotCountAsFlick() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.FLICK, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, .9, 0, 0); touch(j, 0, MOVE, .99, .2, 0); j.update(1.23);
        assertEquals(0, j.count(PERFECT)); assertEquals(1, j.count(MISS));
    }
    @Test public void fastFlickCanPrejudgeAndCommitAfterRelease() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.FLICK, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, .8, 0, 0); touch(j, 0, MOVE, .85, .01, 0);
        touch(j, 0, MOVE, .9, .2, 0); assertEquals(0, j.judged());
        touch(j, 0, UP, .95, .2, 0); j.update(1); assertEquals(1, j.count(PERFECT));
    }
    @Test public void verticalFlickWorksAndSlowMotionFails() throws Exception {
        for (double speed : new double[]{.2, .001}) {
            ChartDocument c = chart(); note(c, NoteType.FLICK, 1, 1, 0); PlaytestJudge j = judge(c);
            touch(j, 0, DOWN, .8, 0, 0); touch(j, 0, MOVE, .85, 0, .001);
            touch(j, 0, MOVE, .9, 0, speed); j.update(1.23);
            assertEquals(speed > .1 ? 1 : 0, j.count(PERFECT));
        }
    }
    @Test public void holdHeadPlaysOnceAndCommitsOnlyAtItsEnd() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.HOLD, 1, 2, 0); PlaytestJudge j = judge(c);
        List<PlaytestJudge.Hit> hits = new ArrayList<>(); j.setListener(hits::add);
        touch(j, 0, DOWN, .9, 0, 0); assertEquals(0, j.judged());
        assertEquals(1, hits.size()); assertTrue(hits.get(0).holdHead); assertTrue(hits.get(0).playsSound());
        j.update(1.5); j.update(2); assertEquals(1, j.count(GOOD));
        assertEquals(2, hits.size()); assertFalse(hits.get(1).playsSound());
    }
    @Test public void holdReleaseToleranceAndFingerReplacement() throws Exception {
        ChartDocument c = chart(); Note n = note(c, NoteType.HOLD, 1, 2, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, 1, 0, 0); touch(j, 0, UP, 1.1, 0, 0);
        j.update(1.12); j.update(1.169); assertEquals(0, j.judged());
        touch(j, 2, DOWN, 1.17, 0, .9); j.update(1.5); j.update(2); assertEquals(1, j.count(PERFECT));
        c = chart(); n = note(c, NoteType.HOLD, 1, 2, 0); j = judge(c);
        touch(j, 0, DOWN, 1, 0, 0); touch(j, 0, UP, 1.1, 0, 0);
        j.update(1.12); j.update(1.171); assertEquals(1, j.count(MISS)); assertTrue(j.missedHold(n));
    }
    @Test public void overdueMissesCommitBeforeHoldEndsLikeTheUpstreamPassOrder() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.HOLD, 1, 2, 0);
        JudgeLine second = new JudgeLine(); Note tap = new Note(); tap.startTime = tap.endTime = new BeatTime(3, 0, 1);
        second.notes.add(tap); c.judgeLines.add(second);
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, 1, 0, 0); j.update(2);
        assertEquals(1, j.count(PERFECT)); assertEquals(1, j.count(MISS)); assertEquals(1, j.combo());
    }
    @Test public void badVisualFreezesAtTheActualInputFrameAndExpiresWithoutHitSound() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, .8, 0, 0);
        RenderScene first = ChartEvaluator.evaluatePlaytest(c, .8, true, -1, false, 16.0 / 9,
                new PreviewPlaybackState(), j);
        assertEquals(1, first.badNotes.size());
        double y = first.badNotes.get(0).notes.get(0).startDistance;
        j.update(1);
        RenderScene later = ChartEvaluator.evaluatePlaytest(c, 1, true, -1, false, 16.0 / 9,
                new PreviewPlaybackState(), j);
        assertEquals(y, later.badNotes.get(0).notes.get(0).startDistance, 0);
        assertTrue(later.badNotes.get(0).notes.get(0).alpha < first.badNotes.get(0).notes.get(0).alpha);
        j.update(1.31); assertTrue(j.badNotes(1.31).isEmpty());
    }
    @Test public void last220MillisecondsOfAHoldAreSafeToRelease() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.HOLD, 1, 2, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, 1, 0, 0); touch(j, 0, UP, 1.79, 0, 0); j.update(1.99); j.update(2);
        assertEquals(1, j.count(PERFECT)); assertEquals(0, j.count(MISS));
    }
    @Test public void practiceStartSkipsEarlierNotesAndCrossingHolds() throws Exception {
        ChartDocument c = chart(); Note old = note(c, NoteType.HOLD, 1, 5, 0);
        note(c, NoteType.TAP, 2, 2, 0); Note fake = note(c, NoteType.TAP, 3, 3, 0); fake.fake = true;
        PlaytestJudge j = new PlaytestJudge(c, false, 16.0 / 9, 2);
        assertEquals(1, j.total()); assertTrue(j.hidden(old));
        touch(j, 0, DOWN, 2, 0, 0); j.update(6); assertEquals(1, j.judged()); assertEquals(1000000, j.score());
    }
    @Test public void rotatedLineUsesItsOwnAxisForTouchDistance() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, -270);
        LineEvent rotation = c.judgeLines.get(0).eventLayers.get(0).events(EventType.ROTATE).get(0);
        rotation.start = rotation.end = 90;
        PlaytestJudge j = judge(c); touch(j, 0, DOWN, 1, 10, .4); assertEquals(1, j.count(PERFECT));
    }
    @Test public void cancelledPointersCannotKeepHoldsOrDragsAlive() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.DRAG, 1, 1, 0); PlaytestJudge j = judge(c);
        touch(j, 0, DOWN, .5, 0, 0); j.clearTouches(); j.update(1.23); assertEquals(1, j.count(MISS));
    }
    @Test public void manualSceneNeverShowsAutoplayHitsOrScore() throws Exception {
        ChartDocument c = chart(); note(c, NoteType.TAP, 1, 1, 0); PlaytestJudge j = judge(c); j.update(1.1);
        RenderScene scene = ChartEvaluator.evaluatePlaytest(c, 1.1, true, -1, false, 16.0 / 9,
                new PreviewPlaybackState(), j);
        assertEquals(0, scene.hud.score); assertEquals(0, scene.hud.combo);
        assertEquals(0, scene.lines.get(0).hitEffects.size()); assertEquals(1, scene.lines.get(0).notes.size());
        RenderScene auto = ChartEvaluator.evaluateAtSeconds(c, 1.1, true);
        assertEquals(1000000, auto.hud.score); assertFalse(auto.lines.get(0).hitEffects.isEmpty());
    }
    @Test public void anEmptyPracticeSegmentHasFiniteResults() throws Exception {
        PlaytestJudge j = judge(chart()); assertEquals(0, j.score()); assertEquals(1, j.accuracy(), 0);
    }
}
