package com.xpe.mobile.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

public final class ChartNoteEvaluationTest extends ChartEvaluatorTestSupport {
    @Test public void parentRotationUsesNormalizedAxesAndHitOriginFollowsIt() {
        ChartDocument chart = chart();
        JudgeLine parent = new JudgeLine();
        setConstant(parent, EventType.MOVE_X, 100);
        setConstant(parent, EventType.MOVE_Y, 200);
        setConstant(parent, EventType.ROTATE, -45);
        JudgeLine child = new JudgeLine();
        child.father = 0;
        setConstant(child, EventType.MOVE_X, 180);
        setConstant(child, EventType.MOVE_Y, 90);
        Note note = note(NoteType.TAP, 0);
        note.yOffset = 50;
        child.notes.add(note);
        chart.judgeLines.add(parent);
        chart.judgeLines.add(child);
        for (double aspect : new double[]{16.0/9, 1.5, 21.0/9, 9.0/16, 1.5}) {
            double width = 1920, height = width/aspect;
            RenderScene.RenderLine line = ChartEvaluator.evaluate(chart,0,false,-1,false,aspect).lines.get(1);
            double r = Math.toRadians(45);
            double expectedX = 100*width/1350
                    + Math.cos(r)*180*width/1350 - Math.sin(r)*90*height/900;
            double expectedY = 200*height/900
                    + Math.sin(r)*180*width/1350 + Math.cos(r)*90*height/900;
            // prpr uses an f32 basis; allow its subpixel rounding against this double reference.
            assertEquals(expectedX,line.x*width/1350,1e-4);
            assertEquals(expectedY,line.y*height/900,1e-4);
            assertEquals(line.x,line.hitEffects.get(0).lineX,1e-8);
            assertEquals(line.y,line.hitEffects.get(0).lineY,1e-8);
            assertEquals(50,line.hitEffects.get(0).localY,1e-8);
        }
    }

    @Test public void preparedNoteTimingRefreshesAfterEditAndUndo() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note note = note(NoteType.TAP, 2);
        line.notes.add(note);
        chart.judgeLines.add(line);
        double original = ChartEvaluator.evaluate(chart, 0, false).lines.get(0).notes.get(0).startDistance;
        note.startTime = beat(4);
        chart.markEdited();
        double moved = ChartEvaluator.evaluate(chart, 0, false).lines.get(0).notes.get(0).startDistance;
        assertEquals(original * 2, moved, EPSILON);
        note.startTime = beat(2);
        chart.markEdited();
        assertEquals(original, ChartEvaluator.evaluate(chart, 0, false).lines.get(0).notes.get(0).startDistance, EPSILON);
    }

    @Test public void expiredFakeWithoutCoverStillRendersAfterEffectWindow() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.cover = false;
        Note note = note(NoteType.TAP, 2);
        note.fake = true;
        line.notes.add(note);
        chart.judgeLines.add(line);
        assertEquals(1, ChartEvaluator.evaluate(chart, 4, false).lines.get(0).notes.size());
    }

    @Test
    public void integratesSpeedAcrossBpmSegments() {
        ChartDocument chart = chart();
        BpmChange slower = new BpmChange();
        slower.startTime = beat(1);
        slower.bpm = 60.0;
        chart.bpmChanges.add(slower);
        chart.sortBpm();
        JudgeLine line = new JudgeLine();
        line.notes.add(note(NoteType.TAP, 2));
        chart.judgeLines.add(line);

        RenderScene scene = ChartEvaluator.evaluate(chart, 0.0, false);

        assertEquals(1800.0 * SPEED_DISTANCE_RATIO,
                scene.lines.get(0).notes.get(0).startDistance, EPSILON);
    }

    @Test
    public void packageFlagSelectsRpe170SpeedEasingForNoteHeight() throws Exception {
        ChartDocument chart = chartWithEasedSpeed(170);

        double modernDistance = ChartEvaluator.evaluate(chart, 0.0, false, -1L, true)
                .lines.get(0).notes.get(0).startDistance;
        double compatibilityDistance = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.get(0).startDistance;

        assertEquals(960.0 * SPEED_DISTANCE_RATIO, modernDistance, EPSILON);
        // Classic prpr samples the end 0.1 ms early: 12 * (1 - 0.0001 / 2).
        assertEquals(1439.928 * SPEED_DISTANCE_RATIO, compatibilityDistance, EPSILON);
    }

    @Test
    public void lineWithoutSpeedEventsHasZeroHeightLikePhira() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.eventLayers.get(0).events(EventType.SPEED).clear();
        line.notes.add(note(NoteType.TAP, 2));
        chart.judgeLines.add(line);

        assertEquals(0.0, ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.get(0).startDistance, EPSILON);
    }

    @Test
    public void coverHidesFutureNoteBehindLine() {
        ChartDocument chart = chart();
        JudgeLine covered = new JudgeLine();
        setConstant(covered, EventType.SPEED, -10.0);
        covered.notes.add(note(NoteType.TAP, 1));
        chart.judgeLines.add(covered);

        assertTrue(ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.isEmpty());

        covered.cover = false;
        assertEquals(1, ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.size());
    }

    @Test
    public void holdShrinksToLineAfterItsStartAndEmitsHitEffect() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note hold = note(NoteType.HOLD, 1);
        hold.endTime = beat(3);
        line.notes.add(hold);
        chart.judgeLines.add(line);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0);

        assertEquals(0.0, evaluated.notes.get(0).startDistance, EPSILON);
        assertEquals(1200.0 * SPEED_DISTANCE_RATIO,
                evaluated.notes.get(0).endDistance, EPSILON);
        assertEquals(1, evaluated.hitEffects.size());
        assertEquals(0.0, evaluated.hitEffects.get(0).progress, EPSILON);
        assertFalse(evaluated.notes.get(0).holdHeadVisible);
    }

    @Test
    public void visibleTimeAndPastTimeCullNotes() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note future = note(NoteType.TAP, 4);
        future.visibleTime = 1.0;
        line.notes.add(future);
        chart.judgeLines.add(line);

        assertTrue(ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.isEmpty());
        assertEquals(1, ChartEvaluator.evaluate(chart, 3.0, false)
                .lines.get(0).notes.size());
        assertTrue(ChartEvaluator.evaluate(chart, 4.25, false)
                .lines.get(0).notes.isEmpty());
    }

    @Test
    public void sortsLinesAndMarksAnyNoteTypesAtTheSameBeatAcrossLines() {
        ChartDocument chart = chart();
        JudgeLine high = new JudgeLine();
        high.zOrder = 10;
        high.notes.add(note(NoteType.FLICK, 2));
        JudgeLine low = new JudgeLine();
        low.zOrder = -2;
        low.notes.add(note(NoteType.TAP, 2));
        chart.judgeLines.add(high);
        chart.judgeLines.add(low);

        RenderScene scene = ChartEvaluator.evaluate(chart, 0.0, true);

        assertEquals(1, scene.lines.get(0).sourceIndex);
        assertEquals(0, scene.lines.get(1).sourceIndex);
        assertTrue(scene.lines.get(0).notes.get(0).multiHit);
        assertTrue(scene.lines.get(1).notes.get(0).multiHit);
    }

    @Test
    public void uncoveredFakeNoteContinuesAcrossTheJudgeLine() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.cover = false;
        Note fake = note(NoteType.FLICK, 1);
        fake.fake = true;
        line.notes.add(fake);
        chart.judgeLines.add(line);

        RenderScene.RenderNote crossed = ChartEvaluator.evaluate(chart, 1.5, false)
                .lines.get(0).notes.get(0);

        assertTrue(crossed.fake);
        assertEquals(-300.0 * SPEED_DISTANCE_RATIO, crossed.startDistance, EPSILON);
        line.cover = true;
        assertTrue(ChartEvaluator.evaluate(chart, 1.5, false)
                .lines.get(0).notes.isEmpty());
    }

    @Test
    public void coveredFakeAndFinishedHoldDisappearAtExactBoundary() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note fake = note(NoteType.TAP, 1);
        fake.fake = true;
        Note hold = note(NoteType.HOLD, 0);
        hold.endTime = beat(1);
        line.notes.add(fake);
        line.notes.add(hold);
        chart.judgeLines.add(line);

        assertTrue(ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0).notes.isEmpty());
    }

    @Test
    public void coverIgnoresYOffsetAndDoesNotClipAnActiveHold() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note hold = note(NoteType.HOLD, 1);
        hold.endTime = beat(3);
        hold.yOffset = -1000.0;
        line.notes.add(hold);
        chart.judgeLines.add(line);

        // prpr core/note.rs tests cover_base before adding the object's yOffset.
        RenderScene.RenderNote future = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.get(0);
        assertEquals(-1000.0 + 600.0 * SPEED_DISTANCE_RATIO,
                future.startDistance, EPSILON);
        assertTrue(future.holdHeadVisible);

        RenderScene.RenderNote active = ChartEvaluator.evaluate(chart, 1.5, false)
                .lines.get(0).notes.get(0);
        assertEquals(-1000.0, active.startDistance, EPSILON);
        assertEquals(-1000.0 + 900.0 * SPEED_DISTANCE_RATIO,
                active.endDistance, EPSILON);
        assertFalse(active.holdHeadVisible);

        line.cover = false;
        RenderScene.RenderNote uncovered = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.get(0);
        assertEquals(-1000.0 + 600.0 * SPEED_DISTANCE_RATIO,
                uncovered.startDistance, EPSILON);
        assertEquals(-1000.0 + 1800.0 * SPEED_DISTANCE_RATIO,
                uncovered.endDistance, EPSILON);
        assertTrue(uncovered.holdHeadVisible);
    }

    @Test
    public void hitEffectKeepsHitTimeWorldPositionForItsFullLifetime() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        setEvent(line.eventLayers.get(0), EventType.MOVE_X, 0.0, 100.0, 0, 2);
        Note note = note(NoteType.TAP, 1);
        note.positionX = 10.0;
        line.notes.add(note);
        chart.judgeLines.add(line);

        RenderScene.RenderLine active = ChartEvaluator.evaluate(chart, 1.5, false)
                .lines.get(0);

        assertEquals(1, active.hitEffects.size());
        assertEquals(60.0, active.hitEffects.get(0).worldX, EPSILON);
        assertEquals(0.0, active.hitEffects.get(0).worldY, EPSILON);
        assertEquals(0.5, active.hitEffects.get(0).progress, EPSILON);
        assertEquals(0xFFEC9F, active.hitEffects.get(0).colorRgb);
        assertEquals(225, active.hitEffects.get(0).alpha);
        assertTrue(ChartEvaluator.evaluate(chart, 2.0, false)
                .lines.get(0).hitEffects.isEmpty());
    }

    @Test
    public void holdEmitsDeterministicOneHundredFiftyMillisecondPulses() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note hold = note(NoteType.HOLD, 1);
        hold.endTime = beat(3);
        line.notes.add(hold);
        chart.judgeLines.add(line);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 2.0, false)
                .lines.get(0);

        assertEquals(3, evaluated.hitEffects.size());
        assertEquals(0.1, evaluated.hitEffects.get(0).progress, EPSILON);
        assertEquals(0.4, evaluated.hitEffects.get(1).progress, EPSILON);
        assertEquals(0.7, evaluated.hitEffects.get(2).progress, EPSILON);
        assertFalse(evaluated.hitEffects.get(0).seed
                == evaluated.hitEffects.get(1).seed);
    }

    @Test
    public void zeroLineAlphaKeepsNotesButAnyNegativeAlphaHidesLineAndNotes() {
        ChartDocument chart = chart();
        JudgeLine transparent = new JudgeLine();
        setConstant(transparent, EventType.ALPHA, 0.0);
        transparent.notes.add(note(NoteType.TAP, 1));
        chart.judgeLines.add(transparent);

        RenderScene.RenderLine visibleNotes = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0);
        assertEquals(0, visibleNotes.alpha);
        assertEquals(1, visibleNotes.notes.size());

        for (double negativeAlpha : new double[]{-1.0, -255.0, -510.0, -28050.0}) {
            setConstant(transparent, EventType.ALPHA, negativeAlpha);
            assertTrue("negative ALPHA " + negativeAlpha,
                    ChartEvaluator.evaluate(chart, 0.0, false).lines.isEmpty());
        }
    }

    @Test
    public void negativeAlphaKeepsRealHitEffectsAndHoldPulsesButNotFakeEffects() {
        for (double alpha : new double[]{-1.0, -255.0, -510.0, -28050.0}) {
            for (NoteType type : NoteType.values()) {
                ChartDocument chart = chart();
                JudgeLine line = new JudgeLine();
                setConstant(line, EventType.ALPHA, alpha);
                Note real = note(type, 1);
                real.endTime = beat(3);
                line.notes.add(real);
                Note fake = real.copy();
                fake.fake = true;
                line.notes.add(fake);
                chart.judgeLines.add(line);

                RenderScene scene = ChartEvaluator.evaluate(chart, 1.0, false);
                assertEquals(1, scene.lines.size());
                assertEquals(0, scene.lines.get(0).alpha);
                assertTrue(scene.lines.get(0).notes.isEmpty());
                assertEquals(1, scene.lines.get(0).hitEffects.size());
                if (type == NoteType.HOLD) {
                    RenderScene later = ChartEvaluator.evaluate(chart, 2.0, false);
                    assertTrue(later.lines.get(0).notes.isEmpty());
                    assertFalse(later.lines.get(0).hitEffects.isEmpty());
                }
                assertTrue(ChartEvaluator.evaluate(chart, 5.0, false).lines.isEmpty());
            }
        }
    }

    @Test
    public void holdAddsComboOnlyAtItsEndBeat() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note hold = note(NoteType.HOLD, 1);
        hold.endTime = beat(3);
        line.notes.add(hold);
        line.notes.add(note(NoteType.TAP, 2));
        chart.judgeLines.add(line);

        assertEquals(0, ChartEvaluator.evaluate(chart, 1.0, false).hud.combo);
        assertEquals(1, ChartEvaluator.evaluate(chart, 2.0, false).hud.combo);
        assertEquals(2, ChartEvaluator.evaluate(chart, 3.0, false).hud.combo);
        assertEquals(1_000_000,
                ChartEvaluator.evaluate(chart, 3.0, false).hud.score);
    }
}
