package com.xpe.mobile.preview;

/** prpr's raw f32 easing: includes extrapolation and its non-snapped Expo/Elastic endpoints. */
public final class ShaderTween {
    private static final int[] MAP = {2, 2, 4, 3, 7, 6, 5, 8, 10, 9, 13, 12, 11, 14,
            16, 15, 19, 18, 22, 21, 25, 24, 23, 26, 28, 27, 31, 30, 32, 29};
    private ShaderTween() { }
    public static float apply(int type, float x, float left, float right) {
        int tween = type >= 1 && type < MAP.length ? MAP[type] : 2;
        if (Math.abs(left) < 1e-5f && Math.abs(right - 1) < 1e-5f) return raw(tween, x);
        float a = raw(tween, left), b = raw(tween, right);
        return (raw(tween, left + (right - left) * x) - a) / (b - a);
    }
    private static float raw(int tween, float x) {
        if (tween == 2) return x;
        int family = (tween - 3) / 3, mode = (tween - 3) % 3;
        if (mode == 0) return in(family, x);
        if (mode == 1) return 1 - in(family, 1 - x);
        float doubled = x * 2;
        return doubled < 1 ? in(family, doubled) / 2 : 1 - in(family, 2 - doubled) / 2;
    }
    private static float in(int family, float x) {
        switch (family) {
            case 0: return 1 - (float) Math.cos(x * (float) Math.PI / 2);
            case 1: return x * x;
            case 2: return x * x * x;
            case 3: return x * x * x * x;
            case 4: return x * x * x * x * x;
            case 5: return (float) Math.pow(2, 10 * (x - 1));
            case 6: return 1 - (float) Math.sqrt(1 - x * x);
            case 7: return ((1.70158f + 1) * x - 1.70158f) * x * x;
            case 8: return -(float) Math.pow(2, 10 * x - 10)
                    * (float) Math.sin((x * 10 - 10.75f) * (2 * (float) Math.PI / 3));
            default: {
                float y = 1 - x, d = 2.75f, n = 7.5625f;
                if (y < 1 / d) return 1 - n * y * y;
                if (y < 2 / d) { y -= 1.5f / d; return 1 - (n * y * y + .75f); }
                if (y < 2.5f / d) { y -= 2.25f / d; return 1 - (n * y * y + .9375f); }
                y -= 2.625f / d; return 1 - (n * y * y + .984375f);
            }
        }
    }
}
