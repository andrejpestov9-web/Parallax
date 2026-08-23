package com.andrej.parallaxwallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** On-device configuration screen for the dragon transformation and future beast scenes. */
public final class SettingsActivity extends Activity {
    private static final int REQUEST_IMAGE_BASE = 1100;
    private static final int REQUEST_DEPTH_BASE = 1200;

    private SharedPreferences preferences;
    private final TextView[] sceneLabels = new TextView[SceneSelectionPolicy.SCENE_COUNT];
    private TextView wallpaperInstallerStatus;

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

        TextView title = text("Parallax — превращение", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView privacy = text(
                "Дракон уже встроен как три состояния одного изображения. " +
                        "Приложение не изменяет и не перезаписывает исходники.",
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
                        ? "Датчик наклона не найден — будет показан центральный кадр"
                        : "Датчик наклона найден: " + rotationSensor.getName(),
                13,
                rotationSensor == null ? Color.rgb(255, 180, 120) : Color.rgb(120, 215, 185)
        );
        sensorStatus.setPadding(0, 0, 0, dp(14));
        root.addView(sensorStatus, matchWrap());

        TextView transformationHint = text(
                "Наклон влево — женщина на троне. Центр — женщина стоит в тумане. " +
                        "Наклон вправо — драконица. Переход идёт напрямую от гироскопа.",
                15,
                Color.rgb(255, 210, 130)
        );
        transformationHint.setPadding(0, 0, 0, dp(14));
        root.addView(transformationHint, matchWrap());

        addSectionTitle(root, "Проверка и установка");

        Button internalPreview = button("1. ПРОВЕРИТЬ ЭФФЕКТ ВНУТРИ PARALLAX");
        internalPreview.setOnClickListener(v -> startActivity(
                new Intent(this, HologramPreviewActivity.class)
        ));
        root.addView(internalPreview, matchWrapWithMargin(6));

        Button directInstall = button("2. УСТАНОВИТЬ КАК ЖИВЫЕ ОБОИ");
        directInstall.setOnClickListener(v -> openDirectWallpaperPreview());
        root.addView(directInstall, matchWrapWithMargin(6));

        Button googleWallpapers = button("3. ОТКРЫТЬ GOOGLE WALLPAPERS");
        googleWallpapers.setOnClickListener(v -> openGoogleWallpapers());
        root.addView(googleWallpapers, matchWrapWithMargin(6));

        Button diagnostics = button("4. ДИАГНОСТИКА JOYUI");
        diagnostics.setOnClickListener(v -> startActivity(
                new Intent(this, WallpaperDiagnosticsActivity.class)
        ));
        root.addView(diagnostics, matchWrapWithMargin(6));

        TextView installHint = text(
                "Кнопка 1 всегда показывает эффект внутри приложения. Кнопка 2 сначала " +
                        "передаёт Android компонент Parallax системе, затем доверенному " +
                        "Google Wallpapers. Wallcraft намеренно не используется. Кнопка 4 " +
                        "показывает точный отчёт по компонентам именно этого телефона.",
                13,
                Color.rgb(255, 210, 130)
        );
        installHint.setPadding(0, dp(8), 0, dp(8));
        root.addView(installHint, matchWrap());

        wallpaperInstallerStatus = text(wallpaperInstallerStatusText(), 12,
                Color.rgb(140, 195, 190));
        wallpaperInstallerStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(wallpaperInstallerStatus, matchWrap());

        addSectionTitle(root, "Сцены зверей");
        for (int index = 0; index < SceneSelectionPolicy.SCENE_COUNT; index++) {
            final int sceneIndex = index;
            sceneLabels[index] = text(sceneImageText(index), 14, Color.rgb(201, 229, 226));
            root.addView(sceneLabels[index], matchWrapWithMargin(index == 0 ? 4 : 12));

            if (index == 0) {
                continue;
            }

            Button choose = button("Выбрать: " + ParallaxWallpaperService.sceneName(index));
            choose.setOnClickListener(v -> chooseImage(sceneIndex));
            root.addView(choose, matchWrapWithMargin(5));

            Button chooseDepth = button(
                    "Выбрать карту глубины: " + ParallaxWallpaperService.sceneName(index)
            );
            chooseDepth.setOnClickListener(v -> chooseDepthMap(sceneIndex));
            root.addView(chooseDepth, matchWrapWithMargin(5));

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

        addSeekBar(root, "Чувствительность превращения", 20, 180,
                Math.round(preferences.getFloat(
                        ParallaxWallpaperService.KEY_SENSITIVITY, 1.0f) * 100f),
                value -> preferences.edit().putFloat(
                        ParallaxWallpaperService.KEY_SENSITIVITY,
                        value / 100f
                ).apply());

        addSeekBar(root, "Сила движения для остальных сцен", 6, 40,
                Math.round(preferences.getFloat(
                        ParallaxWallpaperService.KEY_STRENGTH, 0.024f) * 1000f),
                value -> preferences.edit().putFloat(
                        ParallaxWallpaperService.KEY_STRENGTH,
                        value / 1000f
                ).apply());

        CheckBox invertX = checkBox("Поменять левую и правую стороны",
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
        Intent intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        if (!launchSystemHandler(intent, false)) {
            showInstallerFailure("Системный список живых обоев не найден");
        }
    }

    private void openDirectWallpaperPreview() {
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(intent);
            updateInstallerStatus("Android получил прямой запрос на установку Parallax");
            return;
        } catch (Exception ignored) {
            // JOYUI may hide the handler from queries; explicit system fallbacks are tried next.
        }
        if (!launchTrustedHandler(intent, true)) {
            Intent chooser = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
            if (!launchTrustedHandler(chooser, false)) {
                showInstallerFailure(
                        "JOYUI и Google Wallpapers не предоставили экран установки"
                );
            }
        }
    }

    private void openGoogleWallpapers() {
        String googlePackage = "com.google.android.apps.wallpaper";
        PackageManager packageManager = getPackageManager();
        Intent launch = packageManager.getLaunchIntentForPackage(googlePackage);
        if (launch != null) {
            try {
                startActivity(launch);
                updateInstallerStatus(
                        "В Google Wallpapers ищите отдельный раздел «Живые обои»"
                );
                return;
            } catch (Exception ignored) {
                // Fall through to an explicit launcher lookup.
            }
        }

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        main.setPackage(googlePackage);
        List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                main,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo == null) continue;
            Intent explicit = new Intent(main);
            explicit.setComponent(new ComponentName(
                    handler.activityInfo.packageName,
                    handler.activityInfo.name
            ));
            try {
                startActivity(explicit);
                updateInstallerStatus(
                        "В Google Wallpapers ищите отдельный раздел «Живые обои»"
                );
                return;
            } catch (Exception ignored) {
                // Try the next exported launcher activity.
            }
        }
        showInstallerFailure("Google Wallpapers не найден или не запускается");
    }

    private void openJoyuiWallpaperSettings() {
        Intent settings = new Intent("android.settings.WALLPAPER_SETTINGS");
        if (launchSystemHandler(settings, false)) return;
        Intent fallback = new Intent(Intent.ACTION_SET_WALLPAPER);
        if (!launchSystemHandler(fallback, false)) {
            showInstallerFailure("Системные настройки обоев JOYUI не найдены");
        }
    }

    /**
     * Launches only an OS/firmware activity. This prevents third-party wallpaper apps such as
     * Wallcraft from becoming the default handler for our installation buttons.
     */
    private boolean launchSystemHandler(Intent baseIntent, boolean directPreview) {
        List<ResolveInfo> handlers = querySystemHandlers(baseIntent);
        for (ResolveInfo handler : handlers) {
            Intent explicit = new Intent(baseIntent);
            explicit.setComponent(new ComponentName(
                    handler.activityInfo.packageName,
                    handler.activityInfo.name
            ));
            try {
                startActivity(explicit);
                updateInstallerStatus("Открыт системный компонент: "
                        + handler.activityInfo.packageName + "/" + handler.activityInfo.name);
                return true;
            } catch (Exception ignored) {
                // Try the next system implementation exposed by the firmware.
            }
        }

        // Some JOYUI builds hide the picker from intent queries but keep the AOSP component.
        String[][] knownComponents = directPreview
                ? new String[][] {
                    {"com.android.wallpaper.livepicker",
                            "com.android.wallpaper.livepicker.LiveWallpaperChange"}
                }
                : new String[][] {
                    {"com.android.wallpaper.livepicker",
                            "com.android.wallpaper.livepicker.LiveWallpaperActivity"}
                };
        for (String[] candidate : knownComponents) {
            Intent explicit = new Intent(baseIntent);
            explicit.setComponent(new ComponentName(candidate[0], candidate[1]));
            try {
                startActivity(explicit);
                updateInstallerStatus("Открыт встроенный Android-компонент: " + candidate[0]);
                return true;
            } catch (Exception ignored) {
                // The firmware genuinely does not expose this component.
            }
        }
        updateInstallerStatus("Системный обработчик не найден. " + wallpaperInstallerStatusText());
        return false;
    }

    /**
     * In addition to firmware components, permits the official Google Wallpapers package even
     * when it was installed from Play Store and therefore has no FLAG_SYSTEM. Other third-party
     * wallpaper applications remain excluded so Wallcraft cannot capture the request.
     */
    private boolean launchTrustedHandler(Intent baseIntent, boolean directPreview) {
        if (launchSystemHandler(baseIntent, directPreview)) return true;

        Intent googleOnly = new Intent(baseIntent);
        googleOnly.setPackage("com.google.android.apps.wallpaper");
        List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(
                googleOnly,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo == null) continue;
            Intent explicit = new Intent(baseIntent);
            explicit.setComponent(new ComponentName(
                    handler.activityInfo.packageName,
                    handler.activityInfo.name
            ));
            try {
                startActivity(explicit);
                updateInstallerStatus("Открыт доверенный обработчик Google Wallpapers: "
                        + handler.activityInfo.name);
                return true;
            } catch (Exception ignored) {
                // Google Wallpapers is present but this activity is blocked by the firmware.
            }
        }
        return false;
    }

    private List<ResolveInfo> querySystemHandlers(Intent intent) {
        PackageManager packageManager = getPackageManager();
        List<ResolveInfo> all = packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        List<ResolveInfo> system = new ArrayList<>();
        for (ResolveInfo info : all) {
            if (info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
            ApplicationInfo app = info.activityInfo.applicationInfo;
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if ((app.flags & systemFlags) != 0) system.add(info);
        }
        system.sort(Comparator.comparingInt(this::systemHandlerPriority));
        return system;
    }

    private int systemHandlerPriority(ResolveInfo info) {
        String packageName = info.activityInfo.packageName;
        if ("com.android.wallpaper.livepicker".equals(packageName)) return 0;
        if ("com.android.thememanager".equals(packageName)) return 1;
        if ("com.google.android.apps.wallpaper".equals(packageName)) return 2;
        return 10;
    }

    private String wallpaperInstallerStatusText() {
        List<ResolveInfo> direct = querySystemHandlers(
                new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        );
        List<ResolveInfo> chooser = querySystemHandlers(
                new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        );
        return "Диагностика: системных экранов подтверждения — " + direct.size()
                + ", списков живых обоев — " + chooser.size() + ".";
    }

    private void updateInstallerStatus(String value) {
        if (wallpaperInstallerStatus != null) wallpaperInstallerStatus.setText(value);
    }

    private void showInstallerFailure(String message) {
        String details = wallpaperInstallerStatusText();
        updateInstallerStatus(message + ". " + details);
        Toast.makeText(this, message + ". Смотрите строку диагностики под кнопками.",
                Toast.LENGTH_LONG).show();
    }

    private String sceneImageText(int index) {
        if (index == 0) {
            return "Дракон: встроены 3 кадра · трон → туман → драконица";
        }
        String uri = preferences.getString(ParallaxWallpaperService.imageKey(index), "");
        String depthUri = preferences.getString(ParallaxWallpaperService.depthKey(index), "");
        String imageState = uri == null || uri.isEmpty()
                ? "изображение не выбрано"
                : "изображение выбрано";
        String depthState = depthUri == null || depthUri.isEmpty()
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
