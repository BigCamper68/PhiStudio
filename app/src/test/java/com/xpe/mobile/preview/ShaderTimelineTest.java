package com.xpe.mobile.preview;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class ShaderTimelineTest {
    private static final String SOURCE = "uniform float power; // %0.5%\n"
            + "uniform vec2 offset; // %0.1, -0.2%\nuniform vec4 color; // %0, 0, 0, 1%\n";
    private static ShaderTimeline parse(String bpm, String effects) throws IOException {
        return ShaderTimeline.parse("{\"bpm\":" + bpm + ",\"effects\":" + effects + "}", name -> SOURCE);
    }
    private static String effect(String vars) {
        return "{\"start\":[0,0,1],\"end\":[10,0,1],\"shader\":\"test\",\"vars\":" + vars + "}";
    }
    @Test public void tempoIsIndependentAndEndIsExclusive() throws Exception {
        ShaderTimeline timeline = parse("[{\"time\":[0,0,1],\"bpm\":120},"
                + "{\"time\":[4,0,1],\"bpm\":60}]", "[" + effect("{}") + "]");
        ShaderTimeline.Effect effect = timeline.effects.get(0);
        assertEquals(8, effect.end, 1e-12);
        assertTrue(effect.active(0)); assertTrue(effect.active(7.999999));
        assertFalse(effect.active(8)); assertFalse(effect.active(-.001));
    }
    @Test public void constantsAndColorNormalizationMatchPrpr() throws Exception {
        ShaderTimeline.Effect effect = parse("120", "[" + effect(
                "{\"power\":2,\"offset\":[1,-2],\"color\":[255,128,0,64]}") + "]").effects.get(0);
        assertArrayEquals(new float[]{2}, effect.uniforms.get("power").at(42), 0);
        assertArrayEquals(new float[]{1,-2}, effect.uniforms.get("offset").at(42), 0);
        assertArrayEquals(new float[]{1,128f/255,0,64f/255}, effect.uniforms.get("color").at(42), 1e-7f);
        assertArrayEquals(new float[]{.5f}, effect.defaults.get("power"), 0);
        assertArrayEquals(new float[]{.1f,-.2f}, effect.defaults.get("offset"), 0);
    }
    @Test public void pairsExtrapolateHoldGapsAndStepAtDuplicatesAfterSeeking() throws Exception {
        String events = "{\"power\":["
                + "{\"startTime\":[2,0,1],\"endTime\":[4,0,1],\"start\":2,\"end\":4,\"easingType\":1},"
                + "{\"startTime\":[6,0,1],\"endTime\":[8,0,1],\"start\":10,\"end\":20,\"easingType\":1},"
                + "{\"startTime\":[8,0,1],\"endTime\":[10,0,1],\"start\":30,\"end\":40,\"easingType\":1}]}";
        ShaderTimeline.Animation animation = parse("60", "[" + effect(events) + "]").effects.get(0).uniforms.get("power");
        assertEquals(0, animation.at(0)[0], 1e-6);
        assertEquals(4, animation.at(5)[0], 0);
        assertEquals(10, animation.at(6)[0], 0);
        assertEquals(15, animation.at(7)[0], 0);
        assertEquals(30, animation.at(8)[0], 0);
        assertEquals(40, animation.at(99)[0], 0);
        assertEquals(3, animation.at(3)[0], 0);
    }
    @Test public void vectorAndColorAnimationsUseTheirOwnBpmSeconds() throws Exception {
        String events = "{\"offset\":[{\"startTime\":[0,0,1],\"endTime\":[4,0,1],"
                + "\"start\":[0,2],\"end\":[4,6],\"easingType\":5}],"
                + "\"color\":[{\"startTime\":[0,0,1],\"endTime\":[4,0,1],"
                + "\"start\":[0,0,0,255],\"end\":[255,128,64,0],\"easingType\":1}]}";
        ShaderTimeline.Effect effect = parse("120", "[" + effect(events) + "]").effects.get(0);
        assertArrayEquals(new float[]{1,3}, effect.uniforms.get("offset").at(1), 1e-6f);
        assertArrayEquals(new float[]{.5f,64f/255,32f/255,.5f}, effect.uniforms.get("color").at(1), 1e-6f);
    }
    @Test public void unorderedPairsRetainPrprCursorAcrossPlaybackSeekAndRewind() throws Exception {
        String events = "{\"power\":["
                + "{\"startTime\":[0,0,1],\"endTime\":[3,0,1],\"start\":0,\"end\":30,\"easingType\":1},"
                + "{\"startTime\":[1,0,1],\"endTime\":[2,0,1],\"start\":100,\"end\":200,\"easingType\":1}]}";
        ShaderTimeline.Animation animation = parse("60", "[" + effect(events) + "]")
                .effects.get(0).uniforms.get("power");
        // Values independently captured from upstream Rust Anim for this query order.
        double[] times = {0, 1, 2.5, 3, 4, 1, 1, .5, 2.5, 4};
        float[] values = {0, 10, 25, 200, 200, 100, 100, 5, 25, 200};
        for (int i = 0; i < times.length; i++) {
            assertEquals("query " + i, values[i], animation.at(times[i])[0], 1e-5f);
        }
    }
    @Test public void overlappingUniformContinuesUntilPreviousEndThenSeeksInSourceOrder() throws Exception {
        String events = "{\"power\":["
                + "{\"startTime\":[0,0,1],\"endTime\":[3,0,1],\"start\":0,\"end\":30,\"easingType\":1},"
                + "{\"startTime\":[1,0,1],\"endTime\":[5,0,1],\"start\":100,\"end\":140,\"easingType\":1}]}";
        ShaderTimeline.Animation animation = parse("60", "[" + effect(events) + "]")
                .effects.get(0).uniforms.get("power");
        double[] times = {0, 2, 3, 4, 6, 2, .5, 2, 3};
        float[] values = {0, 20, 120, 130, 140, 110, 5, 20, 120};
        for (int i = 0; i < times.length; i++) {
            assertEquals("query " + i, values[i], animation.at(times[i])[0], 1e-5f);
        }
    }
    @Test public void prprExpoElasticAndEasingWindowsRemainUnsnapped() {
        assertEquals(1f/1024, ShaderTween.apply(17, 0, 0, 1), 0);
        assertEquals(1-1f/1024, ShaderTween.apply(16, 1, 0, 1), 0);
        assertEquals((.5f*.5f-.25f*.25f)/(.75f*.75f-.25f*.25f),
                ShaderTween.apply(5, .5f, .25f, .75f), 1e-6f);
        assertEquals(-.5f, ShaderTween.apply(999, -.5f, 0, 1), 0);
        for (int type = 1; type <= 29; type++) assertTrue(Float.isFinite(ShaderTween.apply(type, .5f, 0, 1)));
    }
    @Test public void fileOrderAndGlobalFlagIgnoreLineAndOrder() throws Exception {
        String a = effect("{}").replace("\"vars\"", "\"line\":5,\"order\":99,\"global\":true,\"vars\"");
        ShaderTimeline timeline = parse("120", "[" + a + "," + effect("{\"power\":7}") + "]");
        assertTrue(timeline.effects.get(0).global); assertFalse(timeline.effects.get(1).global);
        assertEquals(7, timeline.effects.get(1).uniforms.get("power").at(0)[0], 0);
    }
    @Test public void failuresIdentifyEffectAndDoNotSwallowMissingResources() throws Exception {
        try {
            ShaderTimeline.parse("{\"bpm\":120,\"effects\":[" + effect("{}") + "]}",
                    name -> { throw new IOException("missing shader"); });
            fail();
        } catch (IOException error) { assertTrue(error.getMessage().contains("effect 0")); }
        try { parse("0", "[]"); fail(); } catch (IOException error) { assertTrue(error.getMessage().contains("BPM")); }
    }
}
