package com.xpe.mobile.audio;

public final class Mp3GaplessInfo {
    public static final Mp3GaplessInfo NONE = new Mp3GaplessInfo(0, 0);

    public final int encoderDelayFrames;
    public final int encoderPaddingFrames;

    private Mp3GaplessInfo(int encoderDelayFrames, int encoderPaddingFrames) {
        this.encoderDelayFrames = encoderDelayFrames;
        this.encoderPaddingFrames = encoderPaddingFrames;
    }

    public static Mp3GaplessInfo parse(byte[] data, int length) {
        if (data == null || length <= 0) return NONE;
        int safeLength = Math.min(length, data.length);
        int start = id3PayloadEnd(data, safeLength);
        for (int frame = start; frame + 4 < safeLength; frame++) {
            if (!isLayerThreeHeader(data, frame, safeLength)) continue;
            int version = (data[frame + 1] >>> 3) & 0x03;
            int channelMode = (data[frame + 3] >>> 6) & 0x03;
            boolean mono = channelMode == 0x03;
            int sideInfoBytes = version == 0x03
                    ? (mono ? 17 : 32)
                    : (mono ? 9 : 17);
            boolean hasCrc = (data[frame + 1] & 0x01) == 0;
            int xing = frame + 4 + (hasCrc ? 2 : 0) + sideInfoBytes;
            if (!matches(data, safeLength, xing, "Xing")
                    && !matches(data, safeLength, xing, "Info")) {
                continue;
            }
            int cursor = xing + 4;
            if (cursor + 4 > safeLength) continue;
            int flags = readInt(data, cursor);
            cursor += 4;
            if ((flags & 0x01) != 0) cursor += 4;
            if ((flags & 0x02) != 0) cursor += 4;
            if ((flags & 0x04) != 0) cursor += 100;
            if ((flags & 0x08) != 0) cursor += 4;
            if (cursor + 24 > safeLength) continue;
            int first = data[cursor + 21] & 0xff;
            int second = data[cursor + 22] & 0xff;
            int third = data[cursor + 23] & 0xff;
            int delay = (first << 4) | (second >>> 4);
            int padding = ((second & 0x0f) << 8) | third;
            if (delay == 0 && padding == 0) return NONE;
            return new Mp3GaplessInfo(delay, padding);
        }
        return NONE;
    }

    private static int id3PayloadEnd(byte[] data, int length) {
        if (length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        for (int index = 6; index <= 9; index++) {
            if ((data[index] & 0x80) != 0) return 0;
        }
        int size = ((data[6] & 0x7f) << 21)
                | ((data[7] & 0x7f) << 14)
                | ((data[8] & 0x7f) << 7)
                | (data[9] & 0x7f);
        long end = 10L + size;
        if ((data[5] & 0x10) != 0) end += 10L;
        return end >= length ? 0 : (int) end;
    }

    private static boolean isLayerThreeHeader(byte[] data, int offset, int length) {
        if (offset + 4 > length) return false;
        int first = data[offset] & 0xff;
        int second = data[offset + 1] & 0xff;
        int third = data[offset + 2] & 0xff;
        if (first != 0xff || (second & 0xe0) != 0xe0) return false;
        int version = (second >>> 3) & 0x03;
        int layer = (second >>> 1) & 0x03;
        int bitrateIndex = (third >>> 4) & 0x0f;
        int sampleRateIndex = (third >>> 2) & 0x03;
        return version != 0x01 && layer == 0x01
                && bitrateIndex != 0 && bitrateIndex != 0x0f
                && sampleRateIndex != 0x03;
    }

    private static boolean matches(byte[] data, int length, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > length) return false;
        for (int index = 0; index < expected.length(); index++) {
            if (data[offset + index] != (byte) expected.charAt(index)) return false;
        }
        return true;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
