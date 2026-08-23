import com.andrej.parallaxwallpaper.SceneSelectionPolicy;

import java.util.Random;

/** Host-side checks for page mapping and random fallback without Android SDK. */
public final class SceneSelectionPolicyTest {
    public static void main(String[] args) {
        assertEquals(0, SceneSelectionPolicy.pageFromOffsets(0f, 1f / 3f));
        assertEquals(1, SceneSelectionPolicy.pageFromOffsets(1f / 3f, 1f / 3f));
        assertEquals(2, SceneSelectionPolicy.pageFromOffsets(2f / 3f, 1f / 3f));
        assertEquals(3, SceneSelectionPolicy.pageFromOffsets(1f, 1f / 3f));
        assertEquals(2, SceneSelectionPolicy.pageFromOffsets(0.60f, 0f));

        assertTrue(SceneSelectionPolicy.indicatesMultiplePages(Float.NaN, 0f, 1f / 3f));
        assertTrue(SceneSelectionPolicy.indicatesMultiplePages(0f, 0.34f, 0f));
        assertFalse(SceneSelectionPolicy.indicatesMultiplePages(Float.NaN, 0f, 1f));

        boolean[] configured = {true, false, true, false};
        int picked = SceneSelectionPolicy.chooseRandomConfigured(
                configured,
                0,
                new Random(42L)
        );
        assertEquals(2, picked);

        boolean[] single = {false, false, false, true};
        assertEquals(3, SceneSelectionPolicy.chooseRandomConfigured(
                single,
                3,
                new Random(1L)
        ));

        boolean[] empty = {false, false, false, false};
        assertEquals(0, SceneSelectionPolicy.chooseRandomConfigured(
                empty,
                -1,
                new Random(1L)
        ));

        System.out.println("PASS: four-scene page mapping and random fallback");
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("expected false");
    }
}
