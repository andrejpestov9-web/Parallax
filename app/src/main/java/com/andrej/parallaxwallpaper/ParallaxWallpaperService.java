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
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gyroscope-controlled live wallpaper engine.
 *
 * The service never rewrites or recompresses the user's selected file. It only
 * decodes the source into memory for rendering. Multilayer assets can be added
 * later without changing the sensor and lifecycle code.
 */
public final class ParallaxWallpaperService extends WallpaperService {
    public static final String PREFS = "parallax_settings";
    public static final String KEY_IMAGE_URI = "image_uri";
    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_STRENGTH = "strength";
    public static final String KEY_INVERT_X = "invert_x";
    public static final String KEY_INVERT_Y = "invert_y";
    public static final String KEY_CALIBRATION_NONCE = "calibration_nonce";

    @Override
    public Engine onCreateEngine() {
        return new ParallaxEngine();
    }

    private final class ParallaxEngine extends Engine
            implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private static final long FRAME_DELAY_MS = 16L;
        private static final float MAX_ANGLE_RAD = (float) Math.toRadians(8.0);
        private static final float DEAD_ZONE_RAD = (float) Math.toRadians(0.25);
        private static final float FILTER_ALPHA = 0.12f;

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix imageMatrix = new Matrix();
        private final float[] rotationMatrix = new float[9];
        private final float[] remappedMatrix = new float[9];
        private final float[] orientation = new float[3];

        private final SharedPreferences preferences;
        private final SensorManager sensorManager;
        private final Sensor rotationSensor;

        private Bitmap sourceBitmap;
        private boolean visible;
        private boolean destroyed;
        private boolean calibrated;
        private float basePitch;
        private float baseRoll;
        private float filteredPitch;
        private float filteredRoll;
        private float launcherOffset;
        private long calibrationNonce;

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
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            Sensor gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            rotationSensor = gameRotation != null
                    ? gameRotation
                    : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            calibrationNonce = preferences.getLong(KEY_CALIBRATION_NONCE, 0L);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setOffsetNotificationsEnabled(true);
            preferences.registerOnSharedPreferenceChangeListener(this);
            reloadSourceBitmap();
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            visible = false;
            mainHandler.removeCallbacks(frameRunnable);
            sensorManager.unregisterListener(this);
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            imageLoader.shutdownNow();
            recycleBitmap();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            mainHandler.removeCallbacks(frameRunnable);
            if (visible) {
                calibrated = false;
                if (rotationSensor != null) {
                    sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
                }
                mainHandler.post(frameRunnable);
            } else {
                sensorManager.unregisterListener(this);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            drawFrame();
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                                     float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            launcherOffset = xOffset - 0.5f;
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
            // No action needed. Rotation-vector fusion supplies stable values.
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (KEY_IMAGE_URI.equals(key)) {
                reloadSourceBitmap();
            } else if (KEY_CALIBRATION_NONCE.equals(key)) {
                long nonce = sharedPreferences.getLong(KEY_CALIBRATION_NONCE, 0L);
                if (nonce != calibrationNonce) {
                    calibrationNonce = nonce;
                    calibrated = false;
                }
            }
        }

        private void reloadSourceBitmap() {
            String value = preferences.getString(KEY_IMAGE_URI, "");
            if (value == null || value.isEmpty()) {
                recycleBitmap();
                drawFrame();
                return;
            }
            final Uri uri = Uri.parse(value);
            imageLoader.submit(() -> {
                Bitmap decoded = null;
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeStream(stream, null, options);
                } catch (Exception ignored) {
                    // The placeholder remains visible if access was revoked or decoding failed.
                }
                final Bitmap loaded = decoded;
                mainHandler.post(() -> {
                    if (destroyed) {
                        if (loaded != null && !loaded.isRecycled()) loaded.recycle();
                        return;
                    }
                    recycleBitmap();
                    sourceBitmap = loaded;
                    drawFrame();
                });
            });
        }

        private void recycleBitmap() {
            Bitmap old = sourceBitmap;
            sourceBitmap = null;
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        }

        private void drawFrame() {
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
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            float sensitivity = preferences.getFloat(KEY_SENSITIVITY, 1.0f);
            float strength = preferences.getFloat(KEY_STRENGTH, 0.024f);
            float xSign = preferences.getBoolean(KEY_INVERT_X, false) ? -1f : 1f;
            float ySign = preferences.getBoolean(KEY_INVERT_Y, false) ? -1f : 1f;

            float normalizedX = clamp(filteredRoll * sensitivity / MAX_ANGLE_RAD, -1f, 1f);
            float normalizedY = clamp(filteredPitch * sensitivity / MAX_ANGLE_RAD, -1f, 1f);
            float moveX = -xSign * normalizedX * width * strength - launcherOffset * width * 0.012f;
            float moveY = ySign * normalizedY * height * strength * 0.55f;

            float coverScale = Math.max(
                    (float) width / sourceBitmap.getWidth(),
                    (float) height / sourceBitmap.getHeight()
            );
            // The fixed reserve also covers launcher page scrolling at the
            // lowest user-selectable parallax strength.
            float overscanScale = coverScale * (1.014f + strength * 2.4f);
            float scaledWidth = sourceBitmap.getWidth() * overscanScale;
            float scaledHeight = sourceBitmap.getHeight() * overscanScale;

            imageMatrix.reset();
            imageMatrix.postScale(overscanScale, overscanScale);
            imageMatrix.postTranslate(
                    (width - scaledWidth) * 0.5f + moveX,
                    (height - scaledHeight) * 0.5f + moveY
            );
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(sourceBitmap, imageMatrix, imagePaint);
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
            canvas.drawText("Dragon Parallax", width * 0.5f, height * 0.47f, textPaint);
            textPaint.setColor(Color.rgb(181, 222, 219));
            textPaint.setTextSize(Math.max(22f, width * 0.030f));
            canvas.drawText("Выберите изображение в настройках", width * 0.5f, height * 0.52f, textPaint);
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
