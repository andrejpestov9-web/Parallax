package com.andrej.parallaxwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gyroscope-controlled live wallpaper engine with four switchable scenes. */
public final class ParallaxWallpaperService extends WallpaperService {
    public static final String PREFS = "parallax_settings";
    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_STRENGTH = "strength";
    public static final String KEY_INVERT_X = "invert_x";
    public static final String KEY_INVERT_Y = "invert_y";
    public static final String KEY_CALIBRATION_NONCE = "calibration_nonce";
    public static final String KEY_RANDOM_NONCE = "random_nonce";
    public static final String KEY_SELECTION_MODE = "selection_mode";

    private static final String KEY_IMAGE_URI_LEGACY = "image_uri";
    private static final String KEY_IMAGE_URI_PREFIX = "image_uri_";
    private static final String KEY_DEPTH_URI_PREFIX = "depth_uri_";
    private static final String BUILTIN_DRAGON_SOURCE = "dragon/source.png";
    private static final String BUILTIN_DRAGON_DEPTH = "dragon/depth.png";
    private static final String[] SCENE_NAMES = {
            "Дракон", "Тигр", "Черепаха и Змея", "Птица"
    };

    public static String imageKey(int index) {
        return KEY_IMAGE_URI_PREFIX + clampSceneIndex(index);
    }

    public static String depthKey(int index) {
        return KEY_DEPTH_URI_PREFIX + clampSceneIndex(index);
    }

    public static String sceneName(int index) {
        return SCENE_NAMES[clampSceneIndex(index)];
    }

    /** Keeps an image selected in version 0.1 as the dragon scene after upgrade. */
    public static void migrateLegacyImagePreference(SharedPreferences preferences) {
        if (preferences.contains(imageKey(0)) || !preferences.contains(KEY_IMAGE_URI_LEGACY)) return;
        String legacy = preferences.getString(KEY_IMAGE_URI_LEGACY, "");
        if (legacy != null && !legacy.isEmpty()) {
            preferences.edit().putString(imageKey(0), legacy).apply();
        }
    }

    private static int clampSceneIndex(int index) {
        return Math.max(0, Math.min(SceneSelectionPolicy.SCENE_COUNT - 1, index));
    }

    @Override
    public Engine onCreateEngine() {
        return new ParallaxEngine();
    }

    private final class ParallaxEngine extends Engine
            implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private static final long FRAME_DELAY_MS = 16L;
        private static final long CROSSFADE_MS = 280L;
        private static final float MAX_ANGLE_RAD = (float) Math.toRadians(8.0);
        private static final float DEAD_ZONE_RAD = (float) Math.toRadians(0.25);
        private static final float FILTER_ALPHA = 0.12f;
        private static final int MESH_WIDTH = 32;
        private static final int MESH_HEIGHT = 58;
        private static final int MESH_VERTEX_COUNT = (MESH_WIDTH + 1) * (MESH_HEIGHT + 1);

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix imageMatrix = new Matrix();
        private final float[] meshVertices = new float[MESH_VERTEX_COUNT * 2];
        private final float[] rotationMatrix = new float[9];
        private final float[] remappedMatrix = new float[9];
        private final float[] orientation = new float[3];
        private final Random random = new Random();

        private final SharedPreferences preferences;
        private final SensorManager sensorManager;
        private final Sensor rotationSensor;

        private Bitmap sourceBitmap;
        private Bitmap transitionBitmap;
        private float[] sourceDepthGrid;
        private float[] transitionDepthGrid;
        private long transitionStartedAt;
        private boolean visible;
        private boolean destroyed;
        private boolean surfaceReady;
        private boolean calibrated;
        private boolean pageOffsetsAvailable;
        private float previousLauncherOffset = Float.NaN;
        private float launcherOffset;
        private float basePitch;
        private float baseRoll;
        private float filteredPitch;
        private float filteredRoll;
        private int currentPageIndex;
        private int activeSceneIndex = -1;
        private int loadGeneration;
        private long calibrationNonce;
        private long randomNonce;

        private final Runnable frameRunnable = new Runnable() {
            @Override
            public void run() {
                drawFrame();
                if (visible) {
                    mainHandler.postDelayed(this, FRAME_DELAY_MS);
                }
            }
        };

        ParallaxEngine() {
            Context context = ParallaxWallpaperService.this;
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            migrateLegacyImagePreference(preferences);
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            Sensor gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            rotationSensor = gameRotation != null
                    ? gameRotation
                    : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            calibrationNonce = preferences.getLong(KEY_CALIBRATION_NONCE, 0L);
            randomNonce = preferences.getLong(KEY_RANDOM_NONCE, 0L);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setOffsetNotificationsEnabled(true);
            preferences.registerOnSharedPreferenceChangeListener(this);
            selectSceneForCurrentState(true);
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            visible = false;
            loadGeneration++;
            mainHandler.removeCallbacks(frameRunnable);
            sensorManager.unregisterListener(this);
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            imageLoader.shutdownNow();
            recycleAllBitmaps();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            mainHandler.removeCallbacks(frameRunnable);
            if (visible) {
                calibrated = false;
                selectSceneForCurrentState(true);
                if (rotationSensor != null) {
                    sensorManager.registerListener(
                            this,
                            rotationSensor,
                            SensorManager.SENSOR_DELAY_GAME
                    );
                }
                mainHandler.post(frameRunnable);
            } else {
                sensorManager.unregisterListener(this);
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = true;
            drawFrame();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                                     float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            boolean detected = SceneSelectionPolicy.indicatesMultiplePages(
                    previousLauncherOffset,
                    xOffset,
                    xOffsetStep
            );
            previousLauncherOffset = xOffset;
            launcherOffset = xOffset - 0.5f;
            currentPageIndex = SceneSelectionPolicy.pageFromOffsets(xOffset, xOffsetStep);

            if (detected) pageOffsetsAvailable = true;
            String mode = currentMode();
            if (SceneSelectionPolicy.MODE_PAGES.equals(mode)
                    || (SceneSelectionPolicy.MODE_AUTO.equals(mode) && pageOffsetsAvailable)) {
                switchScene(currentPageIndex, false);
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix
            );
            SensorManager.getOrientation(remappedMatrix, orientation);

            float pitch = orientation[1];
            float roll = orientation[2];
            if (!calibrated) {
                basePitch = pitch;
                baseRoll = roll;
                filteredPitch = 0f;
                filteredRoll = 0f;
                calibrated = true;
                return;
            }

            float deltaPitch = wrapRadians(pitch - basePitch);
            float deltaRoll = wrapRadians(roll - baseRoll);
            if (Math.abs(deltaPitch) < DEAD_ZONE_RAD) deltaPitch = 0f;
            if (Math.abs(deltaRoll) < DEAD_ZONE_RAD) deltaRoll = 0f;

            filteredPitch += FILTER_ALPHA * (deltaPitch - filteredPitch);
            filteredRoll += FILTER_ALPHA * (deltaRoll - filteredRoll);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Rotation-vector fusion supplies stable values without extra handling.
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key != null && (key.startsWith(KEY_IMAGE_URI_PREFIX)
                    || key.startsWith(KEY_DEPTH_URI_PREFIX))) {
                if (key.equals(imageKey(activeSceneIndex))) {
                    loadActiveScene();
                } else if (key.equals(depthKey(activeSceneIndex))) {
                    loadActiveScene();
                } else if (!isSceneConfigured(activeSceneIndex)) {
                    selectSceneForCurrentState(true);
                }
            } else if (KEY_SELECTION_MODE.equals(key)) {
                selectSceneForCurrentState(true);
            } else if (KEY_RANDOM_NONCE.equals(key)) {
                long nonce = sharedPreferences.getLong(KEY_RANDOM_NONCE, 0L);
                if (nonce != randomNonce) {
                    randomNonce = nonce;
                    switchScene(chooseRandomScene(), false);
                }
            } else if (KEY_CALIBRATION_NONCE.equals(key)) {
                long nonce = sharedPreferences.getLong(KEY_CALIBRATION_NONCE, 0L);
                if (nonce != calibrationNonce) {
                    calibrationNonce = nonce;
                    calibrated = false;
                }
            }
        }

        private String currentMode() {
            return SceneSelectionPolicy.normalizeMode(preferences.getString(
                    KEY_SELECTION_MODE,
                    SceneSelectionPolicy.MODE_AUTO
            ));
        }

        private void selectSceneForCurrentState(boolean randomizeFallback) {
            String mode = currentMode();
            if (SceneSelectionPolicy.MODE_PAGES.equals(mode)) {
                switchScene(currentPageIndex, false);
                return;
            }
            if (SceneSelectionPolicy.MODE_AUTO.equals(mode) && pageOffsetsAvailable) {
                switchScene(currentPageIndex, false);
                return;
            }
            if (randomizeFallback || activeSceneIndex < 0 || !isSceneConfigured(activeSceneIndex)) {
                switchScene(chooseRandomScene(), false);
            }
        }

        private int chooseRandomScene() {
            boolean[] configured = new boolean[SceneSelectionPolicy.SCENE_COUNT];
            for (int index = 0; index < configured.length; index++) {
                configured[index] = isSceneConfigured(index);
            }
            return SceneSelectionPolicy.chooseRandomConfigured(
                    configured,
                    activeSceneIndex,
                    random
            );
        }

        private boolean isSceneConfigured(int index) {
            if (index < 0 || index >= SceneSelectionPolicy.SCENE_COUNT) return false;
            if (index == 0) return true;
            String value = preferences.getString(imageKey(index), "");
            return value != null && !value.isEmpty();
        }

        private void switchScene(int index, boolean forceReload) {
            int clamped = clampSceneIndex(index);
            if (!forceReload && clamped == activeSceneIndex) return;
            activeSceneIndex = clamped;
            loadActiveScene();
        }

        private void loadActiveScene() {
            final int requestedScene = activeSceneIndex;
            final int generation = ++loadGeneration;
            String value = preferences.getString(imageKey(requestedScene), "");
            final boolean useBuiltinDragon = requestedScene == 0
                    && (value == null || value.isEmpty());
            if (!useBuiltinDragon && (value == null || value.isEmpty())) {
                replaceBitmap(null, null);
                return;
            }

            final Uri uri = useBuiltinDragon ? null : Uri.parse(value);
            String depthValue = preferences.getString(depthKey(requestedScene), "");
            final Uri depthUri = useBuiltinDragon || depthValue == null || depthValue.isEmpty()
                    ? null
                    : Uri.parse(depthValue);
            imageLoader.submit(() -> {
                Bitmap decoded = null;
                float[] loadedDepth = null;
                try (InputStream depthStream = useBuiltinDragon
                        ? getAssets().open(BUILTIN_DRAGON_DEPTH)
                        : depthUri == null ? null
                        : getContentResolver().openInputStream(depthUri)) {
                    if (depthStream != null) loadedDepth = decodeDepthGrid(depthStream);
                } catch (Exception ignored) {
                    // A missing depth map safely falls back to flat image motion.
                }
                try (InputStream stream = useBuiltinDragon
                        ? getAssets().open(BUILTIN_DRAGON_SOURCE)
                        : getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeStream(stream, null, options);
                } catch (Exception ignored) {
                    // The placeholder remains visible if access was revoked or decoding failed.
                }
                final Bitmap loaded = decoded;
                final float[] finalDepth = loadedDepth;
                mainHandler.post(() -> {
                    if (destroyed || generation != loadGeneration
                            || requestedScene != activeSceneIndex) {
                        recycle(loaded);
                        return;
                    }
                    replaceBitmap(loaded, finalDepth);
                });
            });
        }

        private float[] decodeDepthGrid(InputStream stream) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 4;
            Bitmap depthBitmap = BitmapFactory.decodeStream(stream, null, options);
            if (depthBitmap == null) return null;
            try {
                float[] result = new float[MESH_VERTEX_COUNT];
                int index = 0;
                for (int row = 0; row <= MESH_HEIGHT; row++) {
                    int y = Math.min(
                            depthBitmap.getHeight() - 1,
                            Math.round((float) row / MESH_HEIGHT
                                    * (depthBitmap.getHeight() - 1))
                    );
                    for (int column = 0; column <= MESH_WIDTH; column++) {
                        int x = Math.min(
                                depthBitmap.getWidth() - 1,
                                Math.round((float) column / MESH_WIDTH
                                        * (depthBitmap.getWidth() - 1))
                        );
                        result[index++] = Color.red(depthBitmap.getPixel(x, y)) / 255f;
                    }
                }
                return result;
            } finally {
                recycle(depthBitmap);
            }
        }

        private void replaceBitmap(Bitmap loaded, float[] loadedDepth) {
            recycle(transitionBitmap);
            transitionBitmap = null;
            transitionDepthGrid = null;
            if (loaded == null) {
                recycle(sourceBitmap);
                sourceBitmap = null;
                sourceDepthGrid = null;
            } else {
                transitionBitmap = sourceBitmap;
                transitionDepthGrid = sourceDepthGrid;
                sourceBitmap = loaded;
                sourceDepthGrid = loadedDepth;
                transitionStartedAt = SystemClock.uptimeMillis();
            }
            drawFrame();
        }

        private void recycleAllBitmaps() {
            recycle(sourceBitmap);
            recycle(transitionBitmap);
            sourceBitmap = null;
            transitionBitmap = null;
            sourceDepthGrid = null;
            transitionDepthGrid = null;
        }

        private void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }

        private void drawFrame() {
            if (!surfaceReady || destroyed) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                try {
                    canvas = holder.lockHardwareCanvas();
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    canvas = holder.lockCanvas();
                }
                if (canvas == null) return;
                if (sourceBitmap == null || sourceBitmap.isRecycled()) {
                    drawPlaceholder(canvas);
                } else {
                    drawSourceImage(canvas);
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawSourceImage(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            long elapsed = SystemClock.uptimeMillis() - transitionStartedAt;
            float progress = Math.max(0f, Math.min(1f, (float) elapsed / CROSSFADE_MS));

            if (transitionBitmap != null && !transitionBitmap.isRecycled() && progress < 1f) {
                drawBitmapWithMotion(canvas, transitionBitmap, transitionDepthGrid, 255);
                drawBitmapWithMotion(
                        canvas,
                        sourceBitmap,
                        sourceDepthGrid,
                        Math.round(progress * 255f)
                );
            } else {
                if (transitionBitmap != null) {
                    recycle(transitionBitmap);
                    transitionBitmap = null;
                    transitionDepthGrid = null;
                }
                drawBitmapWithMotion(canvas, sourceBitmap, sourceDepthGrid, 255);
            }
        }

        private void drawBitmapWithMotion(Canvas canvas, Bitmap bitmap,
                                          float[] depthGrid, int alpha) {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            float sensitivity = preferences.getFloat(KEY_SENSITIVITY, 1.0f);
            float strength = preferences.getFloat(KEY_STRENGTH, 0.024f);
            float xSign = preferences.getBoolean(KEY_INVERT_X, false) ? -1f : 1f;
            float ySign = preferences.getBoolean(KEY_INVERT_Y, false) ? -1f : 1f;

            float normalizedX = clamp(filteredRoll * sensitivity / MAX_ANGLE_RAD, -1f, 1f);
            float normalizedY = clamp(filteredPitch * sensitivity / MAX_ANGLE_RAD, -1f, 1f);
            float coverScale = Math.max(
                    (float) width / bitmap.getWidth(),
                    (float) height / bitmap.getHeight()
            );
            float overscanScale = coverScale * (1.014f + strength * 2.4f);
            float scaledWidth = bitmap.getWidth() * overscanScale;
            float scaledHeight = bitmap.getHeight() * overscanScale;

            imagePaint.setAlpha(alpha);
            if (depthGrid == null || depthGrid.length != MESH_VERTEX_COUNT) {
                float moveX = -xSign * normalizedX * width * strength
                        - launcherOffset * width * 0.012f;
                float moveY = ySign * normalizedY * height * strength * 0.55f;
                imageMatrix.reset();
                imageMatrix.postScale(overscanScale, overscanScale);
                imageMatrix.postTranslate(
                        (width - scaledWidth) * 0.5f + moveX,
                        (height - scaledHeight) * 0.5f + moveY
                );
                canvas.drawBitmap(bitmap, imageMatrix, imagePaint);
            } else {
                float originX = (width - scaledWidth) * 0.5f;
                float originY = (height - scaledHeight) * 0.5f;
                int vertex = 0;
                for (int row = 0; row <= MESH_HEIGHT; row++) {
                    float v = (float) row / MESH_HEIGHT;
                    for (int column = 0; column <= MESH_WIDTH; column++) {
                        float u = (float) column / MESH_WIDTH;
                        float depthWeight = 0.18f + 0.82f * depthGrid[vertex];
                        float moveX = -xSign * normalizedX * width * strength * depthWeight
                                - launcherOffset * width * 0.012f;
                        float moveY = ySign * normalizedY * height * strength
                                * 0.55f * depthWeight;
                        meshVertices[vertex * 2] = originX + u * scaledWidth + moveX;
                        meshVertices[vertex * 2 + 1] = originY + v * scaledHeight + moveY;
                        vertex++;
                    }
                }
                canvas.drawBitmapMesh(
                        bitmap,
                        MESH_WIDTH,
                        MESH_HEIGHT,
                        meshVertices,
                        0,
                        null,
                        0,
                        imagePaint
                );
            }
            imagePaint.setAlpha(255);
        }

        private void drawPlaceholder(Canvas canvas) {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
            background.setShader(new LinearGradient(
                    0, 0, width, height,
                    Color.rgb(4, 15, 22),
                    Color.rgb(11, 79, 82),
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(0, 0, width, height, background);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(Math.max(34f, width * 0.048f));
            canvas.drawText("Parallax — " + sceneName(activeSceneIndex),
                    width * 0.5f, height * 0.47f, textPaint);
            textPaint.setColor(Color.rgb(181, 222, 219));
            textPaint.setTextSize(Math.max(22f, width * 0.030f));
            canvas.drawText("Выберите изображение в настройках",
                    width * 0.5f, height * 0.52f, textPaint);
        }

        private float wrapRadians(float value) {
            while (value > Math.PI) value -= (float) (Math.PI * 2.0);
            while (value < -Math.PI) value += (float) (Math.PI * 2.0);
            return value;
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
