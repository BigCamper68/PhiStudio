package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class Mp3GaplessInfoTest {
    @Test
    public void parsesLavcDelayAndPaddingFromInfoFrame() {
        byte[] frame = infoFrame(0, 576, 1085);

        Mp3GaplessInfo info = Mp3GaplessInfo.parse(frame, frame.length);

        assertEquals(576, info.encoderDelayFrames);
        assertEquals(1085, info.encoderPaddingFrames);
    }

    @Test
    public void skipsId3TagBeforeFindingFirstMp3Frame() {
        byte[] data = infoFrame(30, 576, 1668);
        data[0] = 'I';
        data[1] = 'D';
        data[2] = '3';
        data[3] = 4;
        data[4] = 0;
        data[5] = 0;
        data[6] = 0;
        data[7] = 0;
        data[8] = 0;
        data[9] = 20;

        Mp3GaplessInfo info = Mp3GaplessInfo.parse(data, data.length);

        assertEquals(576, info.encoderDelayFrames);
        assertEquals(1668, info.encoderPaddingFrames);
    }

    @Test
    public void malformedInputHasNoGaplessTrim() {
        Mp3GaplessInfo info = Mp3GaplessInfo.parse(new byte[]{1, 2, 3, 4}, 4);

        assertEquals(0, info.encoderDelayFrames);
        assertEquals(0, info.encoderPaddingFrames);
    }

    private static byte[] infoFrame(int frameOffset, int delay, int padding) {
        byte[] data = new byte[frameOffset + 220];
        data[frameOffset] = (byte) 0xff;
        data[frameOffset + 1] = (byte) 0xfb;
        data[frameOffset + 2] = (byte) 0x90;
        data[frameOffset + 3] = 0;
        int xing = frameOffset + 4 + 32;
        putAscii(data, xing, "Info");
        data[xing + 7] = 0x0f;
        int tag = xing + 4 + 4 + 4 + 4 + 100 + 4;
        putAscii(data, tag, "Lavc58.54");
        data[tag + 21] = (byte) (delay >>> 4);
        data[tag + 22] = (byte) (((delay & 0x0f) << 4) | (padding >>> 8));
        data[tag + 23] = (byte) padding;
        return data;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            target[offset + index] = (byte) value.charAt(index);
        }
    }
}
