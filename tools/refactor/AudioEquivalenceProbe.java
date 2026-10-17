package com.xpe.mobile.audio;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;

/** PCM output must remain bit-for-bit equal after splitting Sonic. */
public final class AudioEquivalenceProbe {
    public static void main(String[] args)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");int cases=0;long total=0;
        for(boolean floats:new boolean[]{false,true})for(int channels:new int[]{1,2})for(int rate:new int[]{44100,48000})
        for(float speed:new float[]{.5f,.75f,1f,1.25f,1.5f,2f})for(float pitch:new float[]{.8f,1f,1.2f}) {
            Sonic sonic=new Sonic(rate,channels,speed,pitch,48000,floats);
            int frameCount=rate/3;
            for(int start=0;start<frameCount;start+=379) {
                int n=Math.min(379,frameCount-start);
                ByteBuffer input=ByteBuffer.allocate(n*channels*(floats?4:2)).order(ByteOrder.nativeOrder());
                for(int f=0;f<n;f++)for(int ch=0;ch<channels;ch++) {
                    double signal=Math.sin((start+f)*(ch==0?.021:.033))*.65+Math.cos((start+f)*.003)*.15;
                    if(floats)input.putFloat((float)signal);else input.putShort((short)(signal*30000));
                }
                input.flip();sonic.queueInput(input);
                int available=sonic.getOutputSize();ByteBuffer out=ByteBuffer.allocate(available).order(ByteOrder.nativeOrder());sonic.getOutput(out);hash.update(out.array());total+=available;
            }
            sonic.queueEndOfStream();int available=sonic.getOutputSize();ByteBuffer out=ByteBuffer.allocate(available).order(ByteOrder.nativeOrder());sonic.getOutput(out);hash.update(out.array());total+=available;cases++;
            sonic.flush();
        }
        System.out.println("cases="+cases+" bytes="+total+" sha256="+HexFormat.of().formatHex(hash.digest()));
    }
}
