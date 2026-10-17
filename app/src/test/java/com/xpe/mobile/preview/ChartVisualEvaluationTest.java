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

public final class ChartVisualEvaluationTest extends ChartEvaluatorTestSupport {
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
        assertEquals(255, evaluated.hitEffects.get(0).alpha);
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
}
