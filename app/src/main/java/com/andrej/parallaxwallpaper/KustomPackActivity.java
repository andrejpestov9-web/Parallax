package com.andrej.parallaxwallpaper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One-tap bridge from the installed pack to the exact preset in KLWP.
 * The source artwork remains inside the APK and is served read-only by
 * org.kustom.api.Provider.
 */
public final class KustomPackActivity extends Activity {
    private static final String KLWP_PACKAGE = "org.kustom.wallpaper";
    private static final String KLWP_EDITOR = "org.kustom.lib.editor.WpAdvancedEditorActivity";
    private static final String PRESET_URI =
        "kfile://com.andrej.dragonhologrampack/wallpapers/Dragon_Hologram_Gyro.klwp.zip";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!getPreferences(MODE_PRIVATE).getBoolean("opened_once", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("opened_once", true).apply();
            getWindow().getDecorView().postDelayed(this::openPreset, 250L);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(5, 24, 31));

        TextView title = new TextView(this);
        title.setText("Dragon Hologram");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(0), dp(24)));

        TextView status = new TextView(this);
        status.setText("Пресет встроен. Нажмите кнопку, затем в KLWP нажмите «Сохранить». Всё остальное уже настроено.");
        status.setTextColor(Color.rgb(190, 225, 220));
        status.setTextSize(18f);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap(dp(0), dp(32)));

        Button open = new Button(this);
        open.setText("ОТКРЫТЬ ГОТОВЫЕ ОБОИ В KLWP");
        open.setTextSize(17f);
        open.setOnClickListener(v -> openPreset());
        root.addView(open, matchWrap(dp(0), dp(16)));

        Button appInfo = new Button(this);
        appInfo.setText("СБРОСИТЬ ПЕРВЫЙ ЗАПУСК");
        appInfo.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "Готово: при следующем запуске KLWP откроется автоматически", Toast.LENGTH_LONG).show();
        });
        root.addView(appInfo, matchWrap(dp(0), dp(8)));

        return root;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openPreset() {
        Intent editor = new Intent();
        editor.setComponent(new ComponentName(KLWP_PACKAGE, KLWP_EDITOR));
        editor.setData(Uri.parse(PRESET_URI));
        editor.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(editor);
        } catch (ActivityNotFoundException missingKlwp) {
            openKlwpStore();
        }
    }

    private void openKlwpStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + KLWP_PACKAGE)));
        } catch (ActivityNotFoundException noStore) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + KLWP_PACKAGE)));
        }
    }
}
