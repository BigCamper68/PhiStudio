package com.xpe.mobile.app;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import com.xpe.mobile.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class MainActivityCompatibilityTest {
    @Test public void launcherKeepsInstalledComponentAndTargetsReorganizedActivity() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        ComponentName legacy = new ComponentName(app.getPackageName(), "com.xpe.mobile.MainActivity");
        ActivityInfo alias = app.getPackageManager().getActivityInfo(legacy, 0);
        assertTrue(alias.exported);
        assertEquals(MainActivity.class.getName(), alias.targetActivity);
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        assertEquals(legacy, launch.getComponent());
    }

    @Test public void existingActivityPreferencesRemainReadableAndWritable() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        assertEquals("com.xpe.mobile", activity.getPackageName());
        checkPreferences(activity, "MainActivity");
    }

    @Test
    public void checkpointActivityPreferencesKeepTheirOriginalQualifiedName() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        ContextWrapper checkpoint = new ContextWrapper(activity.getBaseContext()) {
            @Override public String getPackageName() { return "com.xpe.mobile.checkpoint"; }
        };
        ReflectionHelpers.setField(activity, "mBase", checkpoint);
        assertEquals("com.xpe.mobile.checkpoint", activity.getPackageName());
        checkPreferences(activity, "com.xpe.mobile.MainActivity");
    }

    private void checkPreferences(MainActivity activity, String legacyName) {
        activity.setTheme(R.style.AppTheme);
        SharedPreferences legacy = activity.getSharedPreferences(legacyName, 0);
        legacy.edit().putString("playtest.start", "custom").putInt("playtest.countdown", 3).commit();
        assertEquals("custom", activity.getPreferences(0).getString("playtest.start", ""));
        assertEquals(3, activity.getPreferences(0).getInt("playtest.countdown", 0));
        activity.getPreferences(0).edit().putInt("playtest.countdown", 5).commit();
        assertEquals(5, legacy.getInt("playtest.countdown", 0));
    }
}
