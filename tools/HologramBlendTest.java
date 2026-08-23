import com.andrej.parallaxwallpaper.HologramBlend;

/** Host-side tests for the three-frame tilt mapping. */
public final class HologramBlendTest {
    public static void main(String[] args) {
        assertWeights(-1f, 1f, 0f, 0f);
        assertWeights(0f, 0f, 1f, 0f);
        assertWeights(1f, 0f, 0f, 1f);
        assertWeights(-0.5f, 0.5f, 0.5f, 0f);
        assertWeights(0.5f, 0f, 0.5f, 0.5f);
        assertWeights(-20f, 1f, 0f, 0f);
        assertWeights(20f, 0f, 0f, 1f);
        System.out.println("PASS: three-state hologram blend mapping");
    }

    private static void assertWeights(float tilt, float left, float center, float right) {
        float[] actual = HologramBlend.weights(tilt);
        assertNear(left, actual[HologramBlend.LEFT]);
        assertNear(center, actual[HologramBlend.CENTER]);
        assertNear(right, actual[HologramBlend.RIGHT]);
        assertNear(1f, actual[0] + actual[1] + actual[2]);
    }

    private static void assertNear(float expected, float actual) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
