package com.xpe.mobile.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteControlEvents;
import com.xpe.mobile.model.NoteType;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ChartEvaluatorTest {
    private static final double EPSILON = 0.002;
    private static final double SPEED_DISTANCE_RATIO = (100.0 / 0.83175) / 120.0;

    @Test
    public void evaluatesLayeredTransformsAndEasing() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        setEvent(line.eventLayers.get(0), EventType.MOVE_X, 0.0, 100.0, 0, 2);
        EventLayer overlay = new EventLayer();
        setEvent(overlay, EventType.MOVE_X, 20.0, 20.0, 0, 2);
        line.eventLayers.add(overlay);
        chart.judgeLines.add(line);

        RenderScene scene = ChartEvaluator.evaluate(chart, 1.0, false);

        assertEquals(70.0, scene.lines.get(0).x, EPSILON);
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
        assertEquals(1440.0 * SPEED_DISTANCE_RATIO, compatibilityDistance, EPSILON);
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
    public void resolvesParentPositionAndOptionalRotation() {
        ChartDocument chart = chart();
        JudgeLine parent = new JudgeLine();
        setConstant(parent, EventType.MOVE_X, 100.0);
        setConstant(parent, EventType.ROTATE, 90.0);
        JudgeLine child = new JudgeLine();
        child.father = 0;
        child.rotateWithFather = true;
        setConstant(child, EventType.MOVE_X, 10.0);
        setConstant(child, EventType.ROTATE, 30.0);
        chart.judgeLines.add(parent);
        chart.judgeLines.add(child);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 0.0, false).lines.get(1);

        assertEquals(100.0, evaluated.x, EPSILON);
        assertEquals(-10.0, evaluated.y, EPSILON);
        assertEquals(-120.0, evaluated.rotationDegrees, EPSILON);
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
    public void coverHidesWholeFutureHoldButDoesNotClipAnActiveHold() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note hold = note(NoteType.HOLD, 1);
        hold.endTime = beat(3);
        hold.yOffset = -1000.0;
        line.notes.add(hold);
        chart.judgeLines.add(line);

        assertTrue(ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.isEmpty());

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
    public void malformedParentCycleStaysFinite() {
        ChartDocument chart = chart();
        JudgeLine first = new JudgeLine();
        JudgeLine second = new JudgeLine();
        first.father = 1;
        second.father = 0;
        chart.judgeLines.add(first);
        chart.judgeLines.add(second);

        RenderScene scene = ChartEvaluator.evaluate(chart, 0.0, false);

        assertTrue(Double.isFinite(scene.lines.get(0).x));
        assertTrue(Double.isFinite(scene.lines.get(1).y));
        assertFalse(scene.lines.isEmpty());
    }

    @Test
    public void evaluatesExtendedVisualsAndPerNoteTint() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("scaleXEvents", new JSONArray().put(extendedEvent(1.0, 3.0, 0, 2)))
                .put("scaleYEvents", new JSONArray().put(extendedEvent(1.0, 2.0, 0, 2)))
                .put("inclineEvents", new JSONArray().put(extendedEvent(0.0, 30.0, 0, 2)))
                .put("colorEvents", new JSONArray().put(extendedEvent(
                        new JSONArray().put(0).put(10).put(20),
                        new JSONArray().put(100).put(110).put(120), 0, 2)))
                .put("textEvents", new JSONArray().put(extendedEvent("", "AB", 0, 2))));
        Note note = note(NoteType.TAP, 2);
        note.hasTint = true;
        note.tintRgb = 0x123456;
        line.notes.add(note);
        chart.judgeLines.add(line);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0);

        assertEquals(2.0, evaluated.scaleX, EPSILON);
        assertEquals(1.5, evaluated.scaleY, EPSILON);
        assertEquals(15.0, evaluated.inclineDegrees, EPSILON);
        assertEquals(0x323C46, evaluated.colorRgb);
        assertEquals("A", evaluated.text);
        assertEquals(0x123456, evaluated.notes.get(0).colorRgb);
    }

    @Test
    public void appliesHitEffectTintAtTheJudgementTime() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        Note note = note(NoteType.TAP, 1);
        note.hasHitEffectTint = true;
        note.hitEffectTintRgb = 0xA0B0C0;
        line.notes.add(note);
        chart.judgeLines.add(line);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0);

        assertEquals(1, evaluated.hitEffects.size());
        assertEquals(0xA0B0C0, evaluated.hitEffects.get(0).colorRgb);
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
        assertEquals(0xFEFFA9, active.hitEffects.get(0).colorRgb);
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
    public void convertsExtendedScaleValuesForEachRpeLineKind() throws Exception {
        ChartDocument chart = chart();
        JudgeLine ordinary = new JudgeLine();
        ordinary.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("scaleXEvents", new JSONArray().put(
                        extendedEvent(2.0, 2.0, 0, 2)))
                .put("scaleYEvents", new JSONArray().put(
                        extendedEvent(3.0, 3.0, 0, 2))));
        JudgeLine text = new JudgeLine();
        text.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("textEvents", new JSONArray().put(extendedEvent("A", "A", 0, 2)))
                .put("scaleXEvents", new JSONArray().put(
                        extendedEvent(2.0, 2.0, 0, 2))));
        JudgeLine texture = new JudgeLine();
        texture.texture = "custom.png";
        double customTextureScale = 5.0 / 6.0;
        texture.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("scaleXEvents", new JSONArray().put(
                        extendedEvent(customTextureScale, customTextureScale, 0, 2)))
                .put("scaleYEvents", new JSONArray().put(
                        extendedEvent(customTextureScale, customTextureScale, 0, 2))));
        chart.judgeLines.add(ordinary);
        chart.judgeLines.add(text);
        chart.judgeLines.add(texture);

        RenderScene scene = ChartEvaluator.evaluate(chart, 1.0, false);

        assertEquals(1.0, scene.lines.get(0).scaleX, EPSILON);
        assertEquals(3.0, scene.lines.get(0).scaleY, EPSILON);
        assertEquals(2.0, scene.lines.get(1).scaleX, EPSILON);
        assertEquals(1.0, scene.lines.get(1).scaleY, EPSILON);
        assertEquals(customTextureScale, scene.lines.get(2).scaleX, EPSILON);
        assertEquals(customTextureScale, scene.lines.get(2).scaleY, EPSILON);
    }

    @Test
    public void reconstructsPaintHistoryDeterministicallyAndHonorsClear() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        setEvent(line.eventLayers.get(0), EventType.MOVE_X, 0.0, 100.0, 0, 2);
        line.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("paintEvents", new JSONArray().put(extendedEvent(6.0, 6.0, 0, 2)))
                .put("scaleXEvents", new JSONArray().put(
                        extendedEvent(2.0, 2.0, 0, 2)))
                .put("scaleYEvents", new JSONArray().put(
                        extendedEvent(3.0, 3.0, 0, 2))));
        setConstant(line, EventType.ALPHA, 100.0);
        chart.judgeLines.add(line);

        RenderScene.RenderLine painted = ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0);

        assertTrue(painted.paintMode);
        assertTrue(painted.paintStrokes.size() > 10);
        assertEquals(0.0, painted.paintStrokes.get(0).x, EPSILON);
        assertEquals(50.0, painted.paintStrokes
                .get(painted.paintStrokes.size() - 1).x, EPSILON);
        RenderScene.PaintStroke last = painted.paintStrokes
                .get(painted.paintStrokes.size() - 1);
        assertEquals(255, last.alpha);
        assertEquals(1.0, last.scaleX, EPSILON);
        assertEquals(3.0, last.scaleY, EPSILON);

        line.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("paintEvents", new JSONArray()
                        .put(extendedEvent(6.0, 6.0, 0, 1))
                        .put(extendedEvent(0.0, 0.0, 1, 2))));
        assertTrue(ChartEvaluator.evaluate(chart, 1.5, false)
                .lines.get(0).paintStrokes.isEmpty());
    }

    @Test
    public void customTextureTakesPrecedenceOverTextAndPaintKinds() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.texture = "textures/custom.png";
        line.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("textEvents", new JSONArray().put(extendedEvent("A", "B", 0, 2)))
                .put("paintEvents", new JSONArray().put(extendedEvent(5.0, 5.0, 0, 2))));
        chart.judgeLines.add(line);

        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 1.0, false)
                .lines.get(0);

        assertFalse(evaluated.paintMode);
        assertTrue(evaluated.paintStrokes.isEmpty());
        assertEquals(null, evaluated.text);
        assertEquals("textures/custom.png", evaluated.textureName);
    }

    @Test
    public void appliesRpeNoteControlsAtTheUnscaledHeight() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.noteControls = NoteControlEvents.fromJson(new JSONObject()
                .put("posControl", control("pos", 2.0))
                .put("sizeControl", control("size", 0.5))
                .put("alphaControl", control("alpha", 0.5))
                .put("yControl", control("y", 0.25)));
        Note note = note(NoteType.TAP, 1);
        note.positionX = 10.0;
        note.size = 2.0;
        note.alpha = 200;
        note.speed = 2.0;
        note.yOffset = 3.0;
        line.notes.add(note);
        chart.judgeLines.add(line);

        RenderScene.RenderNote evaluated = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0).notes.get(0);

        assertEquals(20.0, evaluated.x, EPSILON);
        assertEquals(300.0 * SPEED_DISTANCE_RATIO + 6.0,
                evaluated.startDistance, EPSILON);
        assertEquals(1.0, evaluated.size, EPSILON);
        assertEquals(100, evaluated.alpha);
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
    public void evaluatesControlledAndResumedGifTimeline() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.texture = "animated.gif";
        line.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("gifEvents", new JSONArray().put(
                        extendedEvent(0.25, 0.75, 1, 3))));
        chart.judgeLines.add(line);

        RenderScene.RenderLine before = ChartEvaluator.evaluate(chart, 0.0, false)
                .lines.get(0);
        RenderScene.RenderLine controlled = ChartEvaluator.evaluate(chart, 2.0, false)
                .lines.get(0);
        RenderScene.RenderLine resumed = ChartEvaluator.evaluate(chart, 4.0, false)
                .lines.get(0);

        assertTrue(before.gifEnabled);
        assertFalse(before.gifControlled);
        assertEquals(0L, before.gifAnchorTimeMs);
        assertTrue(controlled.gifControlled);
        assertEquals(0.5, controlled.gifProgress, EPSILON);
        assertFalse(resumed.gifControlled);
        assertEquals(0.75, resumed.gifProgress, EPSILON);
        assertEquals(1500L, resumed.gifAnchorTimeMs);
    }

    @Test
    public void attachUiLineControlsHudAndIsNotRenderedAsJudgeLine() throws Exception {
        ChartDocument chart = chart();
        chart.name = "HUD chart";
        chart.level = "IN 15";
        JudgeLine gameplay = new JudgeLine();
        gameplay.notes.add(note(NoteType.TAP, 1));
        gameplay.notes.add(note(NoteType.TAP, 2));
        JudgeLine hudLine = new JudgeLine();
        hudLine.attachUi = AttachedUiElement.SCORE;
        setEvent(hudLine.eventLayers.get(0), EventType.MOVE_X, 0.0, 100.0, 0, 2);
        setEvent(hudLine.eventLayers.get(0), EventType.MOVE_Y, 0.0, 50.0, 0, 2);
        setEvent(hudLine.eventLayers.get(0), EventType.ROTATE, 0.0, 30.0, 0, 2);
        setConstant(hudLine, EventType.ALPHA, 128.0);
        hudLine.extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("scaleXEvents", new JSONArray().put(extendedEvent(1.0, 3.0, 0, 2)))
                .put("scaleYEvents", new JSONArray().put(extendedEvent(1.0, 2.0, 0, 2)))
                .put("colorEvents", new JSONArray().put(extendedEvent(
                        new JSONArray().put(0).put(20).put(40),
                        new JSONArray().put(100).put(120).put(140), 0, 2))));
        chart.judgeLines.add(gameplay);
        chart.judgeLines.add(hudLine);

        RenderScene scene = ChartEvaluator.evaluate(chart, 1.0, false);
        RenderScene.HudTransform transform = scene.hud.transform(AttachedUiElement.SCORE);

        assertEquals(1, scene.lines.size());
        assertEquals(0, scene.lines.get(0).sourceIndex);
        assertEquals(1, transform.sourceIndex);
        assertEquals(50.0, transform.x, EPSILON);
        assertEquals(25.0, transform.y, EPSILON);
        assertEquals(-15.0, transform.rotationDegrees, EPSILON);
        assertEquals(128, transform.alpha);
        assertEquals(0x32465A, transform.colorRgb);
        assertEquals(2.0, transform.scaleX, EPSILON);
        assertEquals(1.5, transform.scaleY, EPSILON);
        assertEquals("HUD chart", scene.hud.name);
        assertEquals("IN 15", scene.hud.level);
        assertEquals(1, scene.hud.combo);
        assertEquals(500000, scene.hud.score);
        assertEquals(0.5, scene.hud.progress, EPSILON);

        RenderScene timed = ChartEvaluator.evaluate(chart, 1.0, false, 10_000L);
        assertEquals(0.05, timed.hud.progress, EPSILON);
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

    private static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange();
        bpm.bpm = 120.0;
        chart.bpmChanges.add(bpm);
        return chart;
    }

    private static ChartDocument chartWithEasedSpeed(int rpeVersion) throws Exception {
        JSONObject speed = new JSONObject()
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("start", 0.0)
                .put("end", 12.0)
                .put("easingType", 5);
        JSONObject note = new JSONObject()
                .put("type", 1)
                .put("above", 1)
                .put("startTime", new JSONArray().put(4).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("positionX", 0.0)
                .put("speed", 1.0)
                .put("size", 1.0)
                .put("alpha", 255)
                .put("visibleTime", 999999.0);
        JSONObject line = new JSONObject()
                .put("eventLayers", new JSONArray().put(new JSONObject()
                        .put("speedEvents", new JSONArray().put(speed))))
                .put("notes", new JSONArray().put(note));
        JSONObject root = new JSONObject()
                .put("META", new JSONObject().put("RPEVersion", rpeVersion))
                .put("BPMList", new JSONArray().put(new JSONObject()
                        .put("bpm", 120.0)
                        .put("startTime", new JSONArray().put(0).put(0).put(1))))
                .put("judgeLineList", new JSONArray().put(line));
        return ChartDocument.fromJson(root.toString());
    }

    private static Note note(NoteType type, int startBeat) {
        Note note = new Note();
        note.type = type;
        note.startTime = beat(startBeat);
        note.endTime = note.startTime;
        return note;
    }

    private static BeatTime beat(int whole) {
        return new BeatTime(whole, 0, 1);
    }

    private static void setConstant(JudgeLine line, EventType type, double value) {
        setEvent(line.eventLayers.get(0), type, value, value, 0, 1);
    }

    private static void setEvent(EventLayer layer, EventType type, double start, double end,
                                 int startBeat, int endBeat) {
        layer.events(type).clear();
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(startBeat);
        event.endTime = beat(endBeat);
        event.start = start;
        event.end = end;
        event.easingType = 1;
        layer.events(type).add(event);
    }

    private static JSONObject extendedEvent(Object start, Object end,
                                            int startBeat, int endBeat) throws Exception {
        return new JSONObject()
                .put("startTime", new JSONArray().put(startBeat).put(0).put(1))
                .put("endTime", new JSONArray().put(endBeat).put(0).put(1))
                .put("start", start)
                .put("end", end)
                .put("easingType", 1)
                .put("easingLeft", 0.0)
                .put("easingRight", 1.0)
                .put("bezier", 0)
                .put("bezierPoints", new JSONArray().put(0).put(0).put(0).put(0));
    }

    private static JSONArray control(String key, double value) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("x", 0.0).put("easing", 1).put(key, value));
    }
}
