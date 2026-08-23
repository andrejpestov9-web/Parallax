package com.andrej.parallaxwallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Simple on-device configuration screen with no external dependencies. */
public final class SettingsActivity extends Activity {
    private static final int REQUEST_IMAGE = 1001;
    private SharedPreferences preferences;
    private TextView selectedImageLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(ParallaxWallpaperService.PREFS, MODE_PRIVATE);
        setContentView(createContent());
    }

    private View createContent() {
        int padding = dp(22);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(6, 20, 25));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(6, 20, 25));

        TextView title = text("Dragon Parallax", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView privacy = text(
                "Приложение только читает выбранный файл для показа. " +
                        "Оригинал не изменяется, не пережимается и не перезаписывается.",
                15,
                Color.rgb(183, 223, 220)
        );
        privacy.setPadding(0, dp(12), 0, dp(18));
        root.addView(privacy, matchWrap());

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        TextView sensorStatus = text(
                rotationSensor == null
                        ? "Датчик наклона не найден — останется только смещение рабочего стола"
                        : "Датчик наклона найден: " + rotationSensor.getName(),
                13,
                rotationSensor == null ? Color.rgb(255, 180, 120) : Color.rgb(120, 215, 185)
        );
        sensorStatus.setPadding(0, 0, 0, dp(14));
        root.addView(sensorStatus, matchWrap());

        selectedImageLabel = text(selectedImageText(), 15, Color.WHITE);
        root.addView(selectedImageLabel, matchWrap());

        Button choose = button("Выбрать изображение");
        choose.setOnClickListener(v -> chooseImage());
        root.addView(choose, matchWrapWithMargin(10));

        addSeekBar(root, "Чувствительность наклона", 20, 180,
                Math.round(preferences.getFloat(ParallaxWallpaperService.KEY_SENSITIVITY, 1.0f) * 100f),
                value -> preferences.edit().putFloat(
                        ParallaxWallpaperService.KEY_SENSITIVITY,
                        value / 100f
                ).apply());

        addSeekBar(root, "Сила смещения", 6, 40,
                Math.round(preferences.getFloat(ParallaxWallpaperService.KEY_STRENGTH, 0.024f) * 1000f),
                value -> preferences.edit().putFloat(
                        ParallaxWallpaperService.KEY_STRENGTH,
                        value / 1000f
                ).apply());

        CheckBox invertX = checkBox("Инвертировать движение по горизонтали",
                preferences.getBoolean(ParallaxWallpaperService.KEY_INVERT_X, false));
        invertX.setOnCheckedChangeListener((buttonView, checked) -> preferences.edit()
                .putBoolean(ParallaxWallpaperService.KEY_INVERT_X, checked).apply());
        root.addView(invertX, matchWrap());

        CheckBox invertY = checkBox("Инвертировать движение по вертикали",
                preferences.getBoolean(ParallaxWallpaperService.KEY_INVERT_Y, false));
        invertY.setOnCheckedChangeListener((buttonView, checked) -> preferences.edit()
                .putBoolean(ParallaxWallpaperService.KEY_INVERT_Y, checked).apply());
        root.addView(invertY, matchWrap());

        Button recenter = button("Запомнить текущее положение как центр");
        recenter.setOnClickListener(v -> {
            preferences.edit().putLong(
                    ParallaxWallpaperService.KEY_CALIBRATION_NONCE,
                    System.nanoTime()
            ).apply();
            Toast.makeText(this, "Центр обновлён", Toast.LENGTH_SHORT).show();
        });
        root.addView(recenter, matchWrapWithMargin(12));

        Button apply = button("Открыть предпросмотр живых обоев");
        apply.setOnClickListener(v -> openWallpaperPreview());
        root.addView(apply, matchWrapWithMargin(12));

        TextView hint = text(
                "Если JOYUI ограничивает датчики в фоне, разрешите приложению работу без " +
                        "ограничений батареи. Интернет приложению не требуется.",
                13,
                Color.rgb(136, 178, 177)
        );
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint, matchWrap());
        scrollView.addView(root, matchWrap());
        return scrollView;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The current grant remains usable for providers without persistent permissions.
        }
        preferences.edit().putString(
                ParallaxWallpaperService.KEY_IMAGE_URI,
                uri.toString()
        ).apply();
        selectedImageLabel.setText(selectedImageText());
    }

    private void openWallpaperPreview() {
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(intent);
        } catch (Exception exception) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private String selectedImageText() {
        String uri = preferences.getString(ParallaxWallpaperService.KEY_IMAGE_URI, "");
        return uri == null || uri.isEmpty()
                ? "Изображение пока не выбрано"
                : "Изображение выбрано — исходный файл остаётся неизменным";
    }

    private void addSeekBar(LinearLayout root, String label, int min, int max,
                            int current, IntConsumer consumer) {
        TextView caption = text(label, 16, Color.WHITE);
        caption.setPadding(0, dp(16), 0, 0);
        root.addView(caption, matchWrap());
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(min, Math.min(max, current)) - min);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) consumer.accept(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(seekBar, matchWrap());
    }

    private CheckBox checkBox(String title, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(title);
        box.setTextColor(Color.WHITE);
        box.setTextSize(15);
        box.setChecked(checked);
        return box;
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        return button;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithMargin(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface IntConsumer {
        void accept(int value);
    }
}
