package com.xpe.mobile.preview;

/** Float-domain easing used by the playback engine, including its endpoint values. */
final class PrprEasing {
    // Encodes family and direction independently of the editor's easing IDs.
    private static final int[] FAMILY = {0,0,1,1,2,2,1,2,3,3,4,4,3,4,5,5,6,6,7,7,8,8,7,8,9,9,10,10,10,9};
    private static final int[] DIRECTION = {0,0,1,0,1,0,2,2,1,0,1,0,2,2,1,0,1,0,1,0,1,0,2,2,1,0,1,0,2,2};
    private static final float PI = (float) Math.PI;

    static float value(int type, float x) {
        if (type < 2 || type >= FAMILY.length) return x;
        int family = FAMILY[type];
        switch (DIRECTION[type]) {
            case 1: return 1f - in(family, 1f - x);
            case 2: {
                float doubled = 2f * x;
                return doubled < 1f ? in(family, doubled) / 2f : 1f - in(family, 2f - doubled) / 2f;
            }
            default: return in(family, x);
        }
    }

    static float window(int type, float x, float left, float right) {
        left = Math.max(0f, Math.min(1f, left));
        right = Math.max(0f, Math.min(1f, right));
        if (type <= 1 || left >= right || Math.abs(left) < 1e-5f && Math.abs(right - 1f) < 1e-5f) return value(type, x);
        float from = value(type, left), to = value(type, right);
        return (value(type, left + (right - left) * x) - from) / (to - from);
    }

    private static float in(int family, float x) {
        switch (family) {
            case 1: return 1f - (float) Math.cos(x * PI / 2f);
            case 2: return x * x;
            case 3: return x * x * x;
            case 4: return x * x * x * x;
            case 5: return x * x * x * x * x;
            case 6: return (float) Math.pow(2f, 10f * (x - 1f));
            case 7: return 1f - (float) Math.sqrt(1f - x * x);
            case 8: return ((1.70158f + 1f) * x - 1.70158f) * x * x;
            case 9: return -((float) Math.pow(2f, 10f * x - 10f)
                    * (float) Math.sin((x * 10f - 10.75f) * (2f * PI / 3f)));
            case 10: {
                float t = 1f - x, offset = 0f, base = 0f;
                if (t >= 2.5f / 2.75f) { offset = 2.625f / 2.75f; base = .984375f; }
                else if (t >= 2f / 2.75f) { offset = 2.25f / 2.75f; base = .9375f; }
                else if (t >= 1f / 2.75f) { offset = 1.5f / 2.75f; base = .75f; }
                float delta = t - offset;
                return 1f - (7.5625f * (delta * delta) + base);
            }
            default: return x;
        }
    }
}
