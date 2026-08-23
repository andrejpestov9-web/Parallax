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
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** On-device configuration screen with four independent wallpaper scenes. */
public final class SettingsActivity extends Activity {
    private static final int REQUEST_IMAGE_BASE = 1100;
    private static final int REQUEST_DEPTH_BASE = 1200;

    private SharedPreferences preferences;
    private final TextView[] sceneLabels = new TextView[SceneSelectionPolicy.SCENE_COUNT];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(ParallaxWallpaperService.PREFS, MODE_PRIVATE);
        ParallaxWallpaperService.migrateLegacyImagePreference(preferences);
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

        TextView title = text("Parallax", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView privacy = text(
                "Четыре независимые сцены. Приложение только читает выбранные файлы: " +
                        "оригиналы не изменяются, не пережимаются и не перезаписываются.",
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
                        ? "Датчик наклона не найден — останется смена сцен"
                        : "Датчик наклона найден: " + rotationSensor.getName(),
                13,
                rotationSensor == null ? Color.rgb(255, 180, 120) : Color.rgb(120, 215, 185)
        );
        sensorStatus.setPadding(0, 0, 0, dp(14));
        root.addView(sensorStatus, matchWrap());

        addSectionTitle(root, "Установка живых обоев");

        Button systemChooser = button("1. ОТКРЫТЬ СПИСОК ЖИВЫХ ОБОЕВ");
        systemChooser.setOnClickListener(v -> openSystemLiveWallpaperList());
        root.addView(systemChooser, matchWrapWithMargin(6));

        Button directPreview = button("2. ОТКРЫТЬ PARALLAX НАПРЯМУЮ");
        directPreview.setOnClickListener(v -> openDirectWallpaperPreview());
        root.addView(directPreview, matchWrapWithMargin(6));

        Button joyuiSettings = button("3. ОТКРЫТЬ НАСТРОЙКИ ОБОЕВ JOYUI");
        joyuiSettings.setOnClickListener(v -> openJoyuiWallpaperSettings());
        root.addView(joyuiSettings, matchWrapWithMargin(6));

        TextView installHint = text(
                "Сначала нажмите кнопку 1 и выберите Parallax. Если JOYUI не откроет " +
                        "список, используйте кнопку 2. Кнопка 3 — системный запасной путь.",
                13,
                Color.rgb(255, 210, 130)
        );
        installHint.setPadding(0, dp(8), 0, dp(8));
        root.addView(installHint, matchWrap());

        addSectionTitle(root, "Изображения зверей");
        for (int index = 0; index < SceneSelectionPolicy.SCENE_COUNT; index++) {
            final int sceneIndex = index;
            sceneLabels[index] = text(sceneImageText(index), 14, Color.rgb(201, 229, 226));
            root.addView(sceneLabels[index], matchWrapWithMargin(index == 0 ? 4 : 12));

            Button choose = button("Выбрать: " + ParallaxWallpaperService.sceneName(index));
            choose.setOnClickListener(v -> chooseImage(sceneIndex));
            root.addView(choose, matchWrapWithMargin(5));

            Button chooseDepth = button(
                    "Выбрать карту глубины: " + ParallaxWallpaperService.sceneName(index)
            );
            chooseDepth.setOnClickListener(v -> chooseDepthMap(sceneIndex));
            root.addView(chooseDepth, matchWrapWithMargin(5));

            if (index == 0) {
                Button restoreDragon = button("Вернуть встроенного 4K-дракона");
                restoreDragon.setOnClickListener(v -> {
                    preferences.edit()
                            .remove(ParallaxWallpaperService.imageKey(0))
                            .remove(ParallaxWallpaperService.depthKey(0))
                            .apply();
                    sceneLabels[0].setText(sceneImageText(0));
                });
                root.addView(restoreDragon, matchWrapWithMargin(5));
            }
        }

        addSectionTitle(root, "Как переключать зверей");
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        String currentMode = SceneSelectionPolicy.normalizeMode(preferences.getString(
                ParallaxWallpaperService.KEY_SELECTION_MODE,
                SceneSelectionPolicy.MODE_AUTO
        ));

        RadioButton automatic = radioButton(
                "Автоматически: страницы, иначе случайно",
                SceneSelectionPolicy.MODE_AUTO
        );
        RadioButton pages = radioButton(
                "По четырём экранам рабочего стола",
                SceneSelectionPolicy.MODE_PAGES
        );
        RadioButton random = radioButton(
                "Случайно при возвращении на главный экран",
                SceneSelectionPolicy.MODE_RANDOM
        );
        modes.addView(automatic, matchWrap());
        modes.addView(pages, matchWrap());
        modes.addView(random, matchWrap());
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton checked = group.findViewById(checkedId);
            if (checked == null || checked.getTag() == null) return;
            preferences.edit().putString(
                    ParallaxWallpaperService.KEY_SELECTION_MODE,
                    checked.getTag().toString()
            ).apply();
        });
        if (SceneSelectionPolicy.MODE_PAGES.equals(currentMode)) {
            pages.setChecked(true);
        } else if (SceneSelectionPolicy.MODE_RANDOM.equals(currentMode)) {
            random.setChecked(true);
        } else {
            automatic.setChecked(true);
        }
        root.addView(modes, matchWrapWithMargin(4));

        Button randomNow = button("Выбрать случайного зверя сейчас");
        randomNow.setOnClickListener(v -> {
            preferences.edit().putLong(
                    ParallaxWallpaperService.KEY_RANDOM_NONCE,
                    System.nanoTime()
            ).apply();
            Toast.makeText(this, "Сцена переключена", Toast.LENGTH_SHORT).show();
        });
        root.addView(randomNow, matchWrapWithMargin(8));

        addSeekBar(root, "Чувствительность наклона", 20, 180,
                Math.round(preferences.getFloat(
                        ParallaxWallpaperService.KEY_SENSITIVITY, 1.0f) * 100f),
                value -> preferences.edit().putFloat(
                        ParallaxWallpaperService.KEY_SENSITIVITY,
                        value / 100f
                ).apply());

        addSeekBar(root, "Сила смещения", 6, 40,
                Math.round(preferences.getFloat(
                        ParallaxWallpaperService.KEY_STRENGTH, 0.024f) * 1000f),
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

        TextView hint = text(
                "Для режима по экранам создайте четыре страницы рабочего стола. " +
                        "Если JOYUI не передаёт их смещение, режим «Автоматически» " +
                        "сам использует случайную смену. При блокировке датчиков разрешите " +
                        "приложению работу без ограничений батареи.",
                13,
                Color.rgb(136, 178, 177)
        );
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint, matchWrap());
        scrollView.addView(root, matchWrap());
        return scrollView;
    }

    private void addSectionTitle(LinearLayout root, String value) {
        TextView section = text(value, 19, Color.WHITE);
        section.setPadding(0, dp(16), 0, dp(5));
        root.addView(section, matchWrap());
    }

    private void chooseImage(int sceneIndex) {
        choosePng(REQUEST_IMAGE_BASE + sceneIndex);
    }

    private void chooseDepthMap(int sceneIndex) {
        choosePng(REQUEST_DEPTH_BASE + sceneIndex);
    }

    private void choosePng(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean depthRequest = requestCode >= REQUEST_DEPTH_BASE
                && requestCode < REQUEST_DEPTH_BASE + SceneSelectionPolicy.SCENE_COUNT;
        int sceneIndex = requestCode - (depthRequest ? REQUEST_DEPTH_BASE : REQUEST_IMAGE_BASE);
        if (sceneIndex < 0 || sceneIndex >= SceneSelectionPolicy.SCENE_COUNT
                || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The current grant remains usable for providers without persistence.
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (depthRequest) {
            editor.putString(ParallaxWallpaperService.depthKey(sceneIndex), uri.toString());
        } else {
            editor.putString(ParallaxWallpaperService.imageKey(sceneIndex), uri.toString());
            editor.remove(ParallaxWallpaperService.depthKey(sceneIndex));
        }
        editor.apply();
        sceneLabels[sceneIndex].setText(sceneImageText(sceneIndex));
    }

    private void openSystemLiveWallpaperList() {
        try {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        } catch (Exception exception) {
            Toast.makeText(
                    this,
                    "JOYUI не открыла список. Нажмите кнопку 2.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openDirectWallpaperPreview() {
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(
                    this,
                    "Прямой просмотр заблокирован JOYUI. Нажмите кнопку 3.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openJoyuiWallpaperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WALLPAPER_SETTINGS));
        } catch (Exception firstFailure) {
            try {
                startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
            } catch (Exception secondFailure) {
                Toast.makeText(
                        this,
                        "JOYUI не предоставляет системный экран установки живых обоев.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private String sceneImageText(int index) {
        String uri = preferences.getString(ParallaxWallpaperService.imageKey(index), "");
        String depthUri = preferences.getString(ParallaxWallpaperService.depthKey(index), "");
        boolean builtinDragon = index == 0 && (uri == null || uri.isEmpty());
        String imageState = builtinDragon
                ? "встроенный 4K-оригинал"
                : uri == null || uri.isEmpty() ? "изображение не выбрано" : "изображение выбрано";
        String depthState = builtinDragon
                ? "встроенная карта глубины"
                : depthUri == null || depthUri.isEmpty()
                ? "обычный плоский параллакс"
                : "карта глубины выбрана";
        return ParallaxWallpaperService.sceneName(index) + ": "
                + imageState + " · " + depthState;
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

    private RadioButton radioButton(String title, String mode) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTag(mode);
        return button;
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
