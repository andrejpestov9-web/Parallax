package com.andrej.parallaxwallpaper;

import java.util.Random;

/** Pure selection logic shared by the wallpaper engine and host-side tests. */
public final class SceneSelectionPolicy {
    public static final int SCENE_COUNT = 4;
    public static final String MODE_AUTO = "auto";
    public static final String MODE_PAGES = "pages";
    public static final String MODE_RANDOM = "random";

    private static final float OFFSET_EPSILON = 0.015f;

    private SceneSelectionPolicy() { }

    public static String normalizeMode(String value) {
        if (MODE_PAGES.equals(value) || MODE_RANDOM.equals(value)) return value;
        return MODE_AUTO;
    }

    /** Maps the launcher's normalized horizontal offset to one of four scenes. */
    public static int pageFromOffsets(float xOffset, float xOffsetStep) {
        float clampedOffset = clamp(xOffset, 0f, 1f);
        if (xOffsetStep > 0f && xOffsetStep < 0.99f) {
            int reportedPage = Math.round(clampedOffset / xOffsetStep);
            int reportedLastPage = Math.max(1, Math.round(1f / xOffsetStep));
            float normalizedPage = (float) reportedPage / reportedLastPage;
            return clamp(Math.round(normalizedPage * (SCENE_COUNT - 1)), 0, SCENE_COUNT - 1);
        }
        return clamp(Math.round(clampedOffset * (SCENE_COUNT - 1)), 0, SCENE_COUNT - 1);
    }

    /** True when the launcher appears to expose more than one home-screen page. */
    public static boolean indicatesMultiplePages(float previousOffset,
                                                  float currentOffset,
                                                  float xOffsetStep) {
        boolean multiPageStep = xOffsetStep > 0f && xOffsetStep < 0.99f;
        boolean moved = !Float.isNaN(previousOffset)
                && Math.abs(currentOffset - previousOffset) >= OFFSET_EPSILON;
        return multiPageStep || moved;
    }

    /** Picks only configured scenes and avoids an immediate repeat when possible. */
    public static int chooseRandomConfigured(boolean[] configured,
                                             int previousIndex,
                                             Random random) {
        int configuredCount = 0;
        for (int i = 0; i < SCENE_COUNT; i++) {
            if (i < configured.length && configured[i]) configuredCount++;
        }
        if (configuredCount == 0) return 0;
        if (configuredCount == 1) {
            for (int i = 0; i < SCENE_COUNT; i++) {
                if (i < configured.length && configured[i]) return i;
            }
        }

        int candidate;
        do {
            candidate = random.nextInt(SCENE_COUNT);
        } while (candidate >= configured.length
                || !configured[candidate]
                || candidate == previousIndex);
        return candidate;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
