package com.xpe.mobile.audio;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NoiseFieldAudioPlaybackTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void actualPlayerFiltersMusicBeforeMixingScheduledHitsAndResetsOnSeek() throws Exception {
        int rate = 48000, frames = rate / 5;
        byte[] source = NoiseFieldLowPassFilterTest.tone(rate, 1, 10000, frames);
        File file = folder.newFile(); Files.write(file.toPath(), source);
        PcmAudioAsset asset = new PcmAudioAsset(file, rate, 1, frames);
        Queue<Output> outputs = new ConcurrentLinkedQueue<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        PcmAudioPlayer player = new PcmAudioPlayer(asset, Runnable::run, new PcmAudioPlayer.Listener() {
            public void onCompletion() { }
            public void onError(String message) { errors.add(message); }
        }, (a,speed) -> { Output output = new Output(); outputs.add(output); return output; });
        try {
            var cue = ScheduledHitSoundMixerTest.timeline(100);
            var sounds = ScheduledHitSoundMixerTest.sounds(rate, 1, new short[]{12000});
            player.configureHitSounds(cue, 0, sounds);
            player.setNoiseFieldTouching(true); player.start(0,1);
            Output wet = take(outputs); assertTrue(wet.done.await(3,TimeUnit.SECONDS));
            byte[] filtered = source.clone();
            new NoiseFieldLowPassFilter(rate,1).process(filtered,filtered.length,true);
            new ScheduledHitSoundMixer(asset,cue,0,sounds,0).mix(filtered,filtered.length,0,1,1);
            assertArrayEquals(filtered,wet.data.toByteArray());
            assertTrue("The hit itself stays bright",NoiseFieldLowPassFilterTest.sample(filtered,rate/10)>11000);
            player.seek(0); player.configureHitSounds(HitSoundTimeline.empty(),0,null); player.start(0,1);
            Output dry = take(outputs); assertTrue(dry.done.await(3,TimeUnit.SECONDS));
            assertArrayEquals(source,dry.data.toByteArray());
            player.setNoiseFieldTouching(true); player.pause(); player.start(0,1);
            Output resumed = take(outputs); assertTrue(resumed.done.await(3,TimeUnit.SECONDS));
            assertArrayEquals(source,resumed.data.toByteArray());
            assertTrue(errors.toString(),errors.isEmpty());
        } finally { player.release(); }
    }
    private static Output take(Queue<Output> values) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(values.isEmpty()&&System.nanoTime()<end)Thread.sleep(2);
        Output output=values.poll();assertNotNull(output);return output;
    }
    private static class Output implements PcmAudioPlayer.Output {
        final ByteArrayOutputStream data=new ByteArrayOutputStream();
        final CountDownLatch done=new CountDownLatch(1);
        long head;
        public int write(byte[] pcm,int offset,int length){data.write(pcm,offset,length);head+=length/2;return length;}
        public long headFrames(){return head;}
        public void play(){} public void pause(){} public void flush(){} public void release(){done.countDown();}
    }
}
