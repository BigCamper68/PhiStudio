package com.xpe.mobile.preview;

import android.app.Activity;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ShaderPlaybackRoutingTest {
    @Test public void gridTogglesAreIndependentAndDoNotDisableFullscreenEffects() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PreviewShaderView view = new PreviewShaderView(activity);
        var settings = new com.xpe.mobile.config.EditorSettings();
        var chart = com.xpe.mobile.model.ChartDocument.fromJson("{}");
        chart.noiseFields.addAll(NoiseFieldTest.fields(NoiseFieldTest.area(false, 0, 0, 3, 4)));
        RenderScene withFields = ChartEvaluator.evaluateAtSeconds(chart, 2, false);
        RenderScene empty = ChartEvaluator.evaluateAtSeconds(com.xpe.mobile.model.ChartDocument.fromJson("{}"), 2, false);
        ShaderTimeline effect = ShaderTimeline.parse("{\"bpm\":60,\"effects\":[{\"shader\":\"probe\",\"start\":[0,0,1],\"end\":[4,0,1]}]}",
                name -> "precision mediump float;varying vec2 uv;void main(){gl_FragColor=vec4(1.0);}");
        for (boolean shaders : new boolean[]{false, true}) for (boolean fields : new boolean[]{false, true}) {
            settings.gridPreviewShaders = shaders; settings.gridPreviewNoiseFields = fields;
            view.submitEditorUnderlay(null, withFields, null, null, null, settings, -1, ShaderTimeline.EMPTY, 2, .5f);
            assertEquals(fields ? View.VISIBLE : View.GONE, view.getVisibility());
            view.submitEditorUnderlay(null, empty, null, null, null, settings, -1, effect, 2, .5f);
            assertEquals(shaders ? View.VISIBLE : View.GONE, view.getVisibility());
            view.submit(null, withFields, null, null, null, settings, -1, .6, effect, 2);
            assertEquals(View.VISIBLE, view.getVisibility());
        }
    }
    @Test public void pausedSeekReenteringAnEffectRefreshesAfterTheHiddenFrameDrains() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PreviewShaderView view = new PreviewShaderView(activity);
        view.setSurfaceTextureListener(null); // Exercise worker scheduling without creating an EGL window.
        activity.setContentView(view);
        android.graphics.SurfaceTexture surface = new android.graphics.SurfaceTexture(0);
        Class<?> workerType = Class.forName(PreviewShaderView.class.getName() + "$Worker");
        var constructor = workerType.getDeclaredConstructor(PreviewShaderView.class,
                android.graphics.SurfaceTexture.class, int.class, int.class);
        constructor.setAccessible(true);
        Object worker = constructor.newInstance(view, surface, 128, 72);
        var main = new android.os.Handler(android.os.Looper.getMainLooper());
        try (ShaderCanvasLayer game = new ShaderCanvasLayer(128, 72, main, null);
             ShaderCanvasLayer overlay = new ShaderCanvasLayer(128, 72, main, null)) {
            org.robolectric.util.ReflectionHelpers.setField(view, "worker", worker);
            for (String name : new String[]{"ready", "inFlight", "gameplayAvailable", "overlayAvailable"})
                org.robolectric.util.ReflectionHelpers.setField(worker, name, true);
            org.robolectric.util.ReflectionHelpers.setField(worker, "width", 128);
            org.robolectric.util.ReflectionHelpers.setField(worker, "height", 72);
            org.robolectric.util.ReflectionHelpers.setField(worker, "gameplay", game);
            org.robolectric.util.ReflectionHelpers.setField(worker, "overlay", overlay);
            Class<?> frameType = Class.forName(PreviewShaderView.class.getName() + "$Frame");
            var frameConstructor = frameType.getDeclaredConstructors()[0];
            frameConstructor.setAccessible(true);
            var rect = new android.graphics.RectF(0, 0, 128, 72);
            Object frame = frameConstructor.newInstance(ShaderTimeline.EMPTY, 2d, rect, rect,
                    128, 72, NoiseFieldFrame.EMPTY, 1f);
            org.robolectric.util.ReflectionHelpers.setField(worker, "pending", frame);
            int[] refreshes = {0};
            view.setFrameListener(() -> refreshes[0]++);
            view.hidePreview();
            var acquire = workerType.getDeclaredMethod("acquire", int.class, int.class, boolean.class);
            acquire.setAccessible(true);
            assertEquals(0, acquire.invoke(worker, 128, 72, false));
            var drain = workerType.getDeclaredMethod("drain"); drain.setAccessible(true);
            drain.invoke(worker);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, refreshes[0]);
            assertFalse(org.robolectric.util.ReflectionHelpers.getField(worker, "inFlight"));
        } finally { surface.release(); }
    }

    @Test public void nativeNoiseFieldsRequestGpuWithoutAnExtraJsonShaderTimeline() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PreviewShaderView view = new PreviewShaderView(activity);
        var chart = com.xpe.mobile.model.ChartDocument.fromJson("{}");
        chart.noiseFields.addAll(NoiseFieldTest.fields(NoiseFieldTest.area(false, 1, 2, 3, 4)));
        for (double time : new double[]{0, 1.25, 1.75, 2, 3.5, 4, 2, 0}) {
            RenderScene scene = ChartEvaluator.evaluateAtSeconds(chart, time, false);
            assertFalse(view.submit(null, scene, null, null, null, null, -1, .6, ShaderTimeline.EMPTY, time));
            assertEquals(time >= 1 && time < 4 ? View.VISIBLE : View.GONE, view.getVisibility());
        }
    }
    @Test public void intervalsWithoutEffectsUseCanvasAndHidePreviouslyPresentedShaderFrame() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PreviewShaderView view = new PreviewShaderView(activity);
        ShaderTimeline timeline = ShaderTimeline.parse("{\"bpm\":60,\"effects\":["
                + "{\"shader\":\"probe\",\"start\":[2,0,1],\"end\":[3,0,1],\"vars\":{}}]}",
                name -> "precision mediump float; varying vec2 uv; uniform sampler2D screenTexture;"
                        + "void main(){gl_FragColor=texture2D(screenTexture,uv);}");
        for (double time : new double[]{0, 1.99, 3, 4, 0}) {
            view.setVisibility(View.VISIBLE);
            assertFalse(view.submit(null, null, null, null, null, null, 0, 0, timeline, time));
            assertEquals(View.GONE, view.getVisibility());
        }
        assertTrue(timeline.hasActive(2)); assertTrue(timeline.hasActive(2.99));
        // Inside an effect, an unavailable GL worker still requests its surface instead of bypassing it.
        assertFalse(view.submit(null, null, null, null, null, null, 0, 0, timeline, 2.5));
        assertEquals(View.VISIBLE, view.getVisibility());
    }
}
