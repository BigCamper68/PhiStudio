package com.xpe.mobile.audio;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaybackAudioCacheTest {
    private File music(Context context) throws Exception {
        File source=new File(context.getFilesDir(),"music.ogg"); Files.write(source.toPath(),new byte[]{1,2,3}); return source;
    }
    private PcmAudioAsset pcm(Context context) throws Exception {
        File output=PlaybackPcmFiles.create(context.getNoBackupFilesDir());
        Files.write(output.toPath(),new byte[48000*4]); return new PcmAudioAsset(output,48000,2,48000);
    }
    @Test public void cachedPcmSurvivesControllerLifetimeAndTemporaryFileCleanup() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); Uri uri=Uri.fromFile(music(context));
        String key=PlaybackAudioCache.key(context,uri);
        PlaybackAudioCache.Entry first=PlaybackAudioCache.remember(context,key,pcm(context));
        assertTrue(first.persistent); PlaybackAudioCache.release(first);
        PlaybackPcmFiles.create(context.getNoBackupFilesDir());
        PlaybackAudioCache.Entry second=PlaybackAudioCache.find(context,key);
        try { assertNotNull(second); assertEquals(first.asset.file,second.asset.file);
            assertEquals(48000,second.asset.totalFrames); assertEquals(1000,second.asset.durationMillis());
        } finally { PlaybackAudioCache.release(second); }
    }
    @Test public void changedMusicAndTruncatedPcmCannotReuseTheCache() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); File source=music(context); Uri uri=Uri.fromFile(source);
        String key=PlaybackAudioCache.key(context,uri);
        PlaybackAudioCache.Entry entry=PlaybackAudioCache.remember(context,key,pcm(context)); PlaybackAudioCache.release(entry);
        Files.write(source.toPath(),new byte[]{4,5,6,7});
        assertNull(PlaybackAudioCache.find(context,PlaybackAudioCache.key(context,uri)));
        Files.write(entry.asset.file.toPath(),new byte[]{1,2}); assertNull(PlaybackAudioCache.find(context,key));
        PlaybackAudioCache.discardUnused(context); assertFalse(entry.asset.file.exists());
    }
    @Test public void cleanupNeverDeletesPcmStillLeasedByAnotherController() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String key=PlaybackAudioCache.key(context,Uri.fromFile(music(context)));
        PlaybackAudioCache.Entry first=PlaybackAudioCache.remember(context,key,pcm(context));
        PlaybackAudioCache.Entry second=PlaybackAudioCache.find(context,key); assertNotNull(second);
        PlaybackAudioCache.release(first); PlaybackAudioCache.discardUnused(context); assertTrue(second.asset.file.isFile());
        PlaybackAudioCache.release(second); PlaybackAudioCache.discardUnused(context); assertFalse(second.asset.file.exists());
    }
    @Test public void aNewAudioControllerIsImmediatelyReadyWithoutDecodingTheSongAgain() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).get(); Uri uri=Uri.fromFile(music(activity));
        PlaybackAudioCache.Entry seeded=PlaybackAudioCache.remember(activity,PlaybackAudioCache.key(activity,uri),pcm(activity));
        PlaybackAudioCache.release(seeded);
        List<String> messages=new ArrayList<>();
        EditorAudioController.Listener listener=new EditorAudioController.Listener() {
            public void onAudioStateChanged() { } public void onAudioCompleted() { }
            public void showMessage(String message) { messages.add(message); } public boolean isHostUnavailable() { return false; }
        };
        EditorAudioController first=new EditorAudioController(activity,new Handler(Looper.getMainLooper()),listener);
        first.load(uri); assertTrue(first.isReady()); first.close(); assertTrue(seeded.asset.file.exists());
        EditorAudioController second=new EditorAudioController(activity,new Handler(Looper.getMainLooper()),listener);
        try { second.load(uri); assertTrue(second.isReady()); second.seek(543); assertEquals(543,second.positionMillis());
            assertTrue("A cached song must not display a fresh decoding message",messages.isEmpty());
        } finally { second.close(); }
    }

    @Test public void unavailableCacheStorageKeepsTheDecodedSongPlayable() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); PcmAudioAsset decoded=pcm(context);
        File occupied=new File(context.getFilesDir(),"occupied"); Files.write(occupied.toPath(),new byte[]{1});
        Context unavailable=new android.content.ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return occupied; }
        };
        PlaybackAudioCache.Entry entry=PlaybackAudioCache.remember(unavailable,"key",decoded);
        assertFalse(entry.persistent); assertEquals(decoded.file,entry.asset.file);
        assertEquals(192000,entry.asset.file.length()); PlaybackAudioCache.release(entry);
    }
}
