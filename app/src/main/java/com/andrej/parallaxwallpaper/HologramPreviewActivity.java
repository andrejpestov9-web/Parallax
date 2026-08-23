package com.andrej.parallaxwallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Guaranteed in-app preview of the three locked Dragon frames.
 *
 * This activity does not depend on the firmware's live-wallpaper picker. It uses the same
 * rotation-vector mapping and the same read-only assets as the wallpaper service.
 */
public final class HologramPreviewActivity extends Activity implements SensorEventListener {
    private static final String[] ASSET_PATHS = {
            "dragon_hologram/left.webp",
            "dragon_hologram/center.webp",
            "dragon_hologram/right.webp"
    };
    private static final float FULL_ANGLE_RAD = (float) Math.toRadians(12.0);
    private static final float DEAD_ZONE_RAD = (float) Math.toRadians(0.25);
    private static final float FILTER_ALPHA = 0.12f;

    private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
    private final Bitmap[] bitmaps = new Bitmap[3];
    private final float[] weights = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] orientation = new float[3];

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private SharedPreferences preferences;
    private HologramView hologramView;
    private boolean destroyed;
    private boolean calibrated;
    private float baseRoll;
    private float filteredRoll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);

        preferences = getSharedPreferences(ParallaxWallpaperService.PREFS, MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rotationSensor = gameRotation != null
                ? gameRotation
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        hologramView = new HologramView(this);
        root.addView(hologramView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView hint = new TextView(this);
        hint.setText("ТРОН  ←  НАКЛОНЯЙТЕ ТЕЛЕФОН  →  ДРАКОНИЦА");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.argb(150, 0, 0, 0));
        hint.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        hintParams.topMargin = dp(8);
        hintParams.leftMargin = dp(8);
        hintParams.rightMargin = dp(8);
        root.addView(hint, hintParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        controls.setBackgroundColor(Color.argb(170, 0, 0, 0));

        Button center = new Button(this);
        center.setText("ЦЕНТР");
        center.setAllCaps(false);
        center.setOnClickListener(v -> recenter());
        controls.addView(center, weightedButton());

        Button install = new Button(this);
        install.setText("УСТАНОВИТЬ ОБОИ");
        install.setAllCaps(false);
        install.setOnClickListener(v -> openDirectInstaller());
        controls.addView(install, weightedButton());

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        controlParams.leftMargin = dp(8);
        controlParams.rightMargin = dp(8);
        controlParams.bottomMargin = dp(8);
        root.addView(controls, controlParams);

        setContentView(root);
        loadLockedFrames();
    }

    @Override
    protected void onResume() {
        super.onResume();
        calibrated = false;
        if (rotationSensor != null) {
            sensorManager.registerListener(
                    this,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        } else {
            Toast.makeText(this,
                    "Датчик наклона не найден — показан центральный кадр",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        sensorManager.unregisterListener(this);
        imageLoader.shutdownNow();
        for (int index = 0; index < bitmaps.length; index++) {
            Bitmap bitmap = bitmaps[index];
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmaps[index] = null;
        }
        super.onDestroy();
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
        float roll = orientation[2];
        if (!calibrated) {
            baseRoll = roll;
            filteredRoll = 0f;
            calibrated = true;
            hologramView.invalidate();
            return;
        }

        float deltaRoll = wrapRadians(roll - baseRoll);
        if (Math.abs(deltaRoll) < DEAD_ZONE_RAD) deltaRoll = 0f;
        filteredRoll += FILTER_ALPHA * (deltaRoll - filteredRoll);
        hologramView.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Rotation-vector fusion supplies stable values without extra handling.
    }

    private void recenter() {
        calibrated = false;
        filteredRoll = 0f;
        hologramView.invalidate();
        Toast.makeText(this, "Текущее положение сохранено как центр",
                Toast.LENGTH_SHORT).show();
    }

    private void openDirectInstaller() {
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        direct.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(direct);
            return;
        } catch (Exception ignored) {
            // Some vendor firmware removes the direct confirmation activity.
        }

        Intent chooser = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        try {
            startActivity(chooser);
        } catch (Exception ignored) {
            Toast.makeText(this,
                    "Экран установки не найден. Открываю диагностику JOYUI.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, WallpaperDiagnosticsActivity.class));
        }
    }

    private void loadLockedFrames() {
        imageLoader.submit(() -> {
            Bitmap[] loaded = new Bitmap[ASSET_PATHS.length];
            boolean complete = true;
            for (int index = 0; index < loaded.length; index++) {
                try (InputStream stream = getAssets().open(ASSET_PATHS[index])) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    loaded[index] = BitmapFactory.decodeStream(stream, null, options);
                    if (loaded[index] == null) complete = false;
                } catch (Exception ignored) {
                    complete = false;
                }
            }

            final boolean success = complete;
            runOnUiThread(() -> {
                if (destroyed) {
                    recycleLoaded(loaded);
                    return;
                }
                if (!success) {
                    recycleLoaded(loaded);
                    Toast.makeText(this, "Не удалось открыть встроенные кадры",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                System.arraycopy(loaded, 0, bitmaps, 0, bitmaps.length);
                hologramView.invalidate();
            });
        });
    }

    private void recycleLoaded(Bitmap[] loaded) {
        for (Bitmap bitmap : loaded) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float wrapRadians(float value) {
        while (value > Math.PI) value -= (float) (Math.PI * 2.0);
        while (value < -Math.PI) value += (float) (Math.PI * 2.0);
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class HologramView extends View {
        private final Paint imagePaint =
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();

        HologramView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            if (!framesReady()) {
                textPaint.setColor(Color.WHITE);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setTextSize(dp(18));
                canvas.drawText("Загрузка трёх кадров…",
                        getWidth() * 0.5f, getHeight() * 0.5f, textPaint);
                return;
            }

            float sensitivity = preferences.getFloat(
                    ParallaxWallpaperService.KEY_SENSITIVITY,
                    1.0f
            );
            float direction = preferences.getBoolean(
                    ParallaxWallpaperService.KEY_INVERT_X,
                    false
            ) ? -1f : 1f;
            float tilt = clamp(direction * filteredRoll * sensitivity / FULL_ANGLE_RAD,
                    -1f, 1f);
            if (Math.abs(tilt) < 0.025f) tilt = 0f;
            HologramBlend.fillWeights(tilt, weights);

            if (tilt <= 0f) {
                drawCover(canvas, bitmaps[HologramBlend.LEFT], 255);
                int centerAlpha = Math.round(weights[HologramBlend.CENTER] * 255f);
                if (centerAlpha > 0) {
                    drawCover(canvas, bitmaps[HologramBlend.CENTER], centerAlpha);
                }
            } else {
                drawCover(canvas, bitmaps[HologramBlend.CENTER], 255);
                int rightAlpha = Math.round(weights[HologramBlend.RIGHT] * 255f);
                if (rightAlpha > 0) {
                    drawCover(canvas, bitmaps[HologramBlend.RIGHT], rightAlpha);
                }
            }
        }

        private boolean framesReady() {
            for (Bitmap bitmap : bitmaps) {
                if (bitmap == null || bitmap.isRecycled()) return false;
            }
            return true;
        }

        private void drawCover(Canvas canvas, Bitmap bitmap, int alpha) {
            float scale = Math.max(
                    (float) getWidth() / bitmap.getWidth(),
                    (float) getHeight() / bitmap.getHeight()
            );
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(
                    (getWidth() - scaledWidth) * 0.5f,
                    (getHeight() - scaledHeight) * 0.5f
            );
            imagePaint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, matrix, imagePaint);
            imagePaint.setAlpha(255);
        }
    }
}
