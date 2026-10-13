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
