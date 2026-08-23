package com.andrej.parallaxwallpaper;

/** Pure three-state blend math used by the gyroscope-driven dragon transformation. */
public final class HologramBlend {
    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    private HologramBlend() { }

    /**
     * Returns normalized left/center/right weights for an input in the -1..1 range.
     * The smoothstep curve keeps the center stable and removes harsh transition edges.
     */
    public static float[] weights(float normalizedTilt) {
        float[] result = new float[3];
        fillWeights(normalizedTilt, result);
        return result;
    }

    /** Allocation-free variant for the live wallpaper render loop. */
    public static void fillWeights(float normalizedTilt, float[] result) {
        if (result == null || result.length < 3) {
            throw new IllegalArgumentException("three output weights are required");
        }
        float tilt = clamp(normalizedTilt, -1f, 1f);
        result[LEFT] = 0f;
        result[CENTER] = 0f;
        result[RIGHT] = 0f;
        if (tilt <= 0f) {
            float mix = smoothStep(tilt + 1f);
            result[LEFT] = 1f - mix;
            result[CENTER] = mix;
        } else {
            float mix = smoothStep(tilt);
            result[CENTER] = 1f - mix;
            result[RIGHT] = mix;
        }
    }

    private static float smoothStep(float value) {
        float t = clamp(value, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
