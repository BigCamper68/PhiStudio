package com.xpe.mobile.app;

import android.os.Handler;
import android.os.Looper;
import com.xpe.mobile.R;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ProjectTaskFailureTest {
    private MainActivity activity() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme); activity.mainHandler=new Handler(Looper.getMainLooper());
        activity.projectExecutor=Executors.newSingleThreadExecutor(); return activity;
    }

    @Test public void rejectedTasksNotifyTheCallerAndDismissProgress() {
        MainActivity activity=activity(); activity.projectExecutor.shutdownNow();
        AtomicReference<Exception> failure=new AtomicReference<>();
        activity.mainProjectSession.runProjectTask(R.string.project_library_loading,()->null,result->fail(),failure::set);
        assertTrue(failure.get() instanceof RejectedExecutionException);
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    @Test public void memoryFailuresNotifyTheCallerSoDeferredResultsCannotStayBlocked() throws Exception {
        MainActivity activity=activity(); AtomicReference<Exception> failure=new AtomicReference<>();
        try {
            activity.mainProjectSession.runProjectTask(R.string.project_library_loading,()->{throw new OutOfMemoryError("injected");},result->fail(),failure::set);
            activity.projectExecutor.submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(failure.get()); assertTrue(failure.get().getCause() instanceof OutOfMemoryError);
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        } finally { activity.projectExecutor.shutdownNow(); }
    }
}
