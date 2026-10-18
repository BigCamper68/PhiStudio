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

public final class ChartEvaluatorTest extends ChartEvaluatorTestSupport {
    @Test public void hudRotationUsesOwnObjectButPositionInheritsParent() {
        ChartDocument chart = chart();
        chart.useAttachUiFix = true;
        JudgeLine parent = new JudgeLine();
        setConstant(parent, EventType.ROTATE, 45);
        JudgeLine child = new JudgeLine();
        child.father = 0;
        child.rotateWithFather = true;
        child.attachUi = AttachedUiElement.SCORE;
        setConstant(child, EventType.ROTATE, 15);
        setConstant(child, EventType.MOVE_X, 100);
        chart.judgeLines.add(parent); chart.judgeLines.add(child);
        RenderScene.HudState hud = ChartEvaluator.evaluate(chart, 0, false).hud;
        assertTrue(hud.useAttachUiFix);
        assertEquals(-15, hud.transform(AttachedUiElement.SCORE).rotationDegrees, 0);
        assertEquals(100 / Math.sqrt(2), hud.transform(AttachedUiElement.SCORE).x, .001);
        assertTrue(Math.abs(hud.transform(AttachedUiElement.SCORE).y) > 0);
    }

    @Test
    public void delayedSpatialEventsExtrapolateEveryLayerLikePrpr() throws Exception {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        for (EventType type : new EventType[]{EventType.MOVE_X, EventType.MOVE_Y, EventType.ROTATE}) {
            line.layer(0).events(type).clear();
            setEvent(line.layer(0), type, 500.0, 800.0, 2, 4);
            setEvent(line.layer(1), type, 20.0, 30.0, 3, 4);
        }
        chart.judgeLines.add(line);
        RenderScene.RenderLine initial = ChartEvaluator.evaluate(chart, 0.0, false).lines.get(0);
        assertEquals(190.0, initial.x, EPSILON);
        assertEquals(190.0, initial.y, EPSILON);
        assertEquals(-190.0, initial.rotationDegrees, EPSILON);
        ChartDocument reopened = ChartDocument.fromJson(chart.toJsonString());
        assertEquals(initial.x, ChartEvaluator.evaluate(reopened, 0.0, false).lines.get(0).x, EPSILON);
        assertEquals(670.0, ChartEvaluator.evaluate(chart, 3.0, false).lines.get(0).x, EPSILON);
    }

    @Test
    public void undefinedCircularPreRollDoesNotTurnIntoOriginGeometry() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.layer(0).events(EventType.MOVE_Y).clear();
        setEvent(line.layer(0), EventType.MOVE_Y, 450.0, -625.0, 2, 4);
        line.layer(0).events(EventType.MOVE_Y).get(0).easingType = 18;
        chart.judgeLines.add(line);
        assertTrue(ChartEvaluator.evaluate(chart, 1.0, false).lines.isEmpty());
        assertEquals(450.0, ChartEvaluator.evaluate(chart, 2.0, false).lines.get(0).y, EPSILON);
    }

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
    public void resolvesParentPositionAndOptionalRotation() throws Exception {
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
        RenderScene.RenderLine reopened = ChartEvaluator.evaluate(
                ChartDocument.fromJson(chart.toJsonString()), 0.0, false).lines.get(1);
        assertEquals(evaluated.x, reopened.x, EPSILON);
        assertEquals(evaluated.y, reopened.y, EPSILON);
        assertEquals(evaluated.rotationDegrees, reopened.rotationDegrees, EPSILON);
        child.rotateWithFather = false;
        RenderScene.RenderLine independent = ChartEvaluator.evaluate(
                ChartDocument.fromJson(chart.toJsonString()), 0.0, false).lines.get(1);
        assertEquals(evaluated.x, independent.x, EPSILON);
        assertEquals(evaluated.y, independent.y, EPSILON);
        assertEquals(-30.0, independent.rotationDegrees, EPSILON);
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
}
