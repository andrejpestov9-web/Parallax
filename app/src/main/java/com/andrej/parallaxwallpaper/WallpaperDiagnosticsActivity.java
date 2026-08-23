package com.andrej.parallaxwallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Reports the wallpaper components that are actually exposed by the current JOYUI build. */
public final class WallpaperDiagnosticsActivity extends Activity {
    private static final String GOOGLE_WALLPAPERS = "com.google.android.apps.wallpaper";
    private static final String THEME_MANAGER = "com.android.thememanager";
    private static final String MI_WALLPAPER = "com.miui.miwallpaper";
    private static final String[] KNOWN_PACKAGES = {
            "com.android.wallpaper.livepicker",
            THEME_MANAGER,
            MI_WALLPAPER,
            "com.miui.android.fashiongallery",
            GOOGLE_WALLPAPERS
    };

    private TextView reportView;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Диагностика установки обоев");
        setContentView(createContent());
        refreshReport();
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(6, 20, 25));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = text("Диагностика JOYUI", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView explanation = text(
                "Этот экран ничего не устанавливает и не меняет изображения. Он проверяет, " +
                        "видит ли система службу Parallax и какой компонент способен открыть " +
                        "подтверждение живых обоев.",
                14,
                Color.rgb(183, 223, 220)
        );
        explanation.setPadding(0, dp(10), 0, dp(12));
        root.addView(explanation, matchWrap());

        Button refresh = button("ПРОВЕРИТЬ СНОВА");
        refresh.setOnClickListener(v -> refreshReport());
        root.addView(refresh, matchWrapWithMargin(4));

        Button google = button("ОТКРЫТЬ GOOGLE WALLPAPERS");
        google.setOnClickListener(v -> openGoogleWallpapers());
        root.addView(google, matchWrapWithMargin(6));

        Button googleDetails = button("СВЕДЕНИЯ / КЭШ GOOGLE WALLPAPERS");
        googleDetails.setOnClickListener(v -> openPackageDetails(GOOGLE_WALLPAPERS));
        root.addView(googleDetails, matchWrapWithMargin(6));

        Button themes = button("ОТКРЫТЬ ТЕМЫ JOYUI");
        themes.setOnClickListener(v -> openPackage(THEME_MANAGER, "Темы JOYUI"));
        root.addView(themes, matchWrapWithMargin(6));

        Button miWallpaper = button("ОТКРЫТЬ MI WALLPAPER");
        miWallpaper.setOnClickListener(v -> openPackage(MI_WALLPAPER, "Mi Wallpaper"));
        root.addView(miWallpaper, matchWrapWithMargin(6));

        Button settings = button("ОТКРЫТЬ СВЕДЕНИЯ О PARALLAX");
        settings.setOnClickListener(v -> openAppDetails());
        root.addView(settings, matchWrapWithMargin(6));

        Button copy = button("СКОПИРОВАТЬ ОТЧЁТ");
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy, matchWrapWithMargin(6));

        reportView = text("Проверка…", 12, Color.rgb(205, 229, 226));
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, dp(16), 0, dp(24));
        root.addView(reportView, matchWrap());

        scroll.addView(root, matchWrap());
        return scroll;
    }

    private void refreshReport() {
        PackageManager packageManager = getPackageManager();
        StringBuilder builder = new StringBuilder();
        builder.append("PARALLAX / JOYUI DIAGNOSTICS\n");
        builder.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        builder.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        builder.append("Build: ").append(Build.DISPLAY).append('\n');
        builder.append("Live-wallpaper feature: ")
                .append(packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER)
                        ? "YES" : "NO")
                .append("\n\n");

        String engineState = getSharedPreferences(
                ParallaxWallpaperService.PREFS,
                MODE_PRIVATE
        ).getString(ParallaxWallpaperService.KEY_ENGINE_DIAGNOSTICS, "NEVER_STARTED");
        builder.append("LAST ENGINE STATE: ").append(engineState).append("\n\n");

        appendOwnService(builder, packageManager);
        appendIntentHandlers(builder, packageManager,
                "CHANGE_LIVE_WALLPAPER",
                new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER));
        appendIntentHandlers(builder, packageManager,
                "LIVE_WALLPAPER_CHOOSER",
                new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        appendIntentHandlers(builder, packageManager,
                "WALLPAPER_SETTINGS",
                new Intent("android.settings.WALLPAPER_SETTINGS"));
        appendIntentHandlers(builder, packageManager,
                "SET_WALLPAPER",
                new Intent(Intent.ACTION_SET_WALLPAPER));

        builder.append("\nKNOWN PACKAGES\n");
        for (String packageName : KNOWN_PACKAGES) {
            builder.append("- ").append(packageName).append(": ")
                    .append(packageState(packageManager, packageName)).append('\n');
        }

        builder.append("\nWALLPAPER ACTIVITY CANDIDATES\n");
        builder.append("Copy this complete section after installing the APK.\n\n");
        appendPackageActivities(builder, packageManager, GOOGLE_WALLPAPERS);
        appendPackageActivities(builder, packageManager, THEME_MANAGER);
        appendPackageActivities(builder, packageManager, MI_WALLPAPER);

        report = builder.toString();
        reportView.setText(report);
    }

    private void appendOwnService(StringBuilder builder, PackageManager packageManager) {
        builder.append("PARALLAX WALLPAPER SERVICE\n");
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        try {
            ServiceInfo info = packageManager.getServiceInfo(
                    component,
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
            );
            builder.append("Declared: YES\n");
            builder.append("Enabled: ").append(info.enabled ? "YES" : "NO").append('\n');
            builder.append("Exported: ").append(info.exported ? "YES" : "NO").append('\n');
            builder.append("Permission: ").append(info.permission).append('\n');
        } catch (PackageManager.NameNotFoundException error) {
            builder.append("Declared: NO — ").append(error.getMessage()).append('\n');
        }

        Intent servicesIntent = new Intent(WallpaperService.SERVICE_INTERFACE);
        List<ResolveInfo> services = packageManager.queryIntentServices(
                servicesIntent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
        );
        boolean found = false;
        for (ResolveInfo resolved : services) {
            if (resolved.serviceInfo == null) continue;
            if (getPackageName().equals(resolved.serviceInfo.packageName)
                    && ParallaxWallpaperService.class.getName()
                    .equals(resolved.serviceInfo.name)) {
                found = true;
                break;
            }
        }
        builder.append("Discoverable by WallpaperService intent: ")
                .append(found ? "YES" : "NO")
                .append("\n\n");
    }

    private void appendIntentHandlers(StringBuilder builder, PackageManager packageManager,
                                      String title, Intent intent) {
        List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS
        );
        builder.append(title).append(": ").append(handlers.size()).append('\n');
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo == null) continue;
            ApplicationInfo application = handler.activityInfo.applicationInfo;
            int systemFlags = ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            boolean system = application != null && (application.flags & systemFlags) != 0;
            builder.append("  - ").append(handler.activityInfo.packageName)
                    .append('/').append(handler.activityInfo.name)
                    .append(system ? " [system]" : " [user]")
                    .append(handler.activityInfo.enabled ? " [enabled]" : " [disabled]")
                    .append('\n');
        }
        builder.append('\n');
    }

    private String packageState(PackageManager packageManager, String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.MATCH_DISABLED_COMPONENTS
            );
            ApplicationInfo application = info.applicationInfo;
            return application != null && application.enabled
                    ? "INSTALLED / ENABLED"
                    : "INSTALLED / DISABLED";
        } catch (PackageManager.NameNotFoundException error) {
            return "NOT FOUND";
        }
    }

    private void appendPackageActivities(StringBuilder builder,
                                         PackageManager packageManager,
                                         String packageName) {
        builder.append(packageName).append('\n');
        try {
            PackageInfo info = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
            );
            builder.append("Version: ").append(info.versionName == null
                    ? "unknown" : info.versionName).append(" (")
                    .append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? info.getLongVersionCode() : info.versionCode)
                    .append(")\n");

            Intent launch = packageManager.getLaunchIntentForPackage(packageName);
            builder.append("Launch activity: ")
                    .append(launch == null || launch.getComponent() == null
                            ? "NONE" : launch.getComponent().flattenToShortString())
                    .append('\n');

            ActivityInfo[] activities = info.activities;
            int total = activities == null ? 0 : activities.length;
            int candidates = 0;
            builder.append("Declared activities: ").append(total).append('\n');
            if (activities != null) {
                for (ActivityInfo activity : activities) {
                    if (!isWallpaperCandidate(activity.name)) continue;
                    candidates++;
                    builder.append("  - ").append(activity.name)
                            .append(activity.exported ? " [exported]" : " [private]")
                            .append(activity.enabled ? " [enabled]" : " [disabled]")
                            .append('\n');
                }
            }
            builder.append("Matching candidates: ").append(candidates).append("\n\n");
        } catch (PackageManager.NameNotFoundException error) {
            builder.append("NOT FOUND\n\n");
        } catch (RuntimeException error) {
            builder.append("READ FAILED: ")
                    .append(error.getClass().getSimpleName()).append("\n\n");
        }
    }

    private boolean isWallpaperCandidate(String activityName) {
        if (activityName == null) return false;
        String normalized = activityName.toLowerCase(Locale.ROOT);
        return normalized.contains("wallpaper")
                || normalized.contains("livepicker")
                || normalized.contains("livewallpaper")
                || normalized.contains("preview")
                || normalized.contains("picker")
                || normalized.contains("theme")
                || normalized.contains("customization");
    }

    private void openGoogleWallpapers() {
        openPackage(GOOGLE_WALLPAPERS, "Google Wallpapers");
    }

    private void openPackage(String packageName, String label) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this,
                    label + " не найден или не имеет запускаемого экрана",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(launch);
        } catch (Exception error) {
            Toast.makeText(this,
                    "JOYUI заблокировал запуск " + label + ": "
                            + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openAppDetails() {
        openPackageDetails(getPackageName());
    }

    private void openPackageDetails(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.parse("package:" + packageName));
        try {
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Системные сведения приложения не открылись",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Parallax JOYUI diagnostics", report));
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
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
}
