package com.apkstudio.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;

public class MainActivity extends Activity {
    private static final int PICK_APK = 1001;
    private ApkManager manager;
    private TextView status;
    private TextView details;
    private LinearLayout fileList;
    private File currentApk;

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        manager = new ApkManager(this);
        buildUi();
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setBackgroundColor(Color.rgb(34,34,42));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,11,15));
        root.setPadding(dp(14), dp(18), dp(14), dp(12));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = text("APK Studio", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.RIGHT);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        root.addView(text("إدارة وتحليل ملفات APK", 14, Color.LTGRAY),
                new LinearLayout.LayoutParams(-1, dp(32)));

        Button open = button("اختيار APK");
        root.addView(open, new LinearLayout.LayoutParams(-1, dp(54)));
        open.setOnClickListener(v -> pickApk());

        details = text("لم يتم اختيار ملف", 14, Color.LTGRAY);
        details.setGravity(Gravity.RIGHT | Gravity.TOP);
        details.setBackgroundColor(Color.rgb(20,20,26));
        root.addView(details, new LinearLayout.LayoutParams(-1, dp(150)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button extract = button("استخراج");
        Button rebuild = button("إعادة بناء");
        actions.addView(extract, new LinearLayout.LayoutParams(0, dp(54), 1));
        actions.addView(rebuild, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(actions);

        extract.setOnClickListener(v -> runExtract());
        rebuild.setOnClickListener(v -> runRebuild());

        TextView filesTitle = text("محتويات APK", 18, Color.WHITE);
        filesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(filesTitle, new LinearLayout.LayoutParams(-1, dp(44)));

        ScrollView scroll = new ScrollView(this);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(0,0,0,dp(20));
        scroll.addView(fileList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));

        status = text("جاهز", 13, Color.GRAY);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(38)));
        setContentView(root);
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/vnd.android.package-archive");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_APK);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_APK || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            currentApk = manager.copyToWorkspace(data.getData());
            inspect();
        } catch (Exception e) { showError(e); }
    }

    private void inspect() {
        try {
            PackageInfo p = manager.getPackageInfo(currentApk);
            ApplicationInfo ai = p.applicationInfo;
            String label = ai != null ? getPackageManager().getApplicationLabel(ai).toString() : "غير معروف";
            StringBuilder s = new StringBuilder();
            s.append("الاسم: ").append(label).append("\n");
            s.append("Package: ").append(p.packageName).append("\n");
            s.append("Version: ").append(p.versionName).append(" (").append(p.getLongVersionCode()).append(")\n");
            if (ai != null) s.append("Target SDK: ").append(ai.targetSdkVersion).append("\n");
            s.append("الحجم: ").append(manager.humanSize(currentApk.length())).append("\n");
            details.setText(s.toString());
            loadFiles();
            status.setText("تم تحليل APK بنجاح");
        } catch (Exception e) { showError(e); }
    }

    private void loadFiles() {
        fileList.removeAllViews();
        try {
            for (String n : manager.listEntries(currentApk)) {
                TextView row = text(n, 13, Color.LTGRAY);
                row.setGravity(Gravity.RIGHT);
                row.setBackgroundColor(Color.rgb(18,18,23));
                fileList.addView(row, new LinearLayout.LayoutParams(-1, dp(42)));
            }
        } catch (Exception e) { showError(e); }
    }

    private void runExtract() {
        if (currentApk == null) { toast("اختر APK أولاً"); return; }
        status.setText("جاري الاستخراج...");
        new Thread(() -> {
            try {
                File out = manager.decode(currentApk);
                runOnUiThread(() -> {
                    status.setText("تم الاستخراج: " + out.getAbsolutePath());
                    toast("تم استخراج المشروع");
                });
            } catch (Exception e) { runOnUiThread(() -> showError(e)); }
        }).start();
    }

    private void runRebuild() {
        if (currentApk == null) { toast("اختر APK أولاً"); return; }
        status.setText("جاري إعادة البناء...");
        new Thread(() -> {
            try {
                File project = manager.findDecodedProject(currentApk);
                if (project == null) throw new Exception("استخرج APK أولاً، ثم أعد البناء.");
                File out = manager.build(project);
                runOnUiThread(() -> {
                    status.setText("تم البناء: " + out.getAbsolutePath());
                    toast("تم إنشاء APK غير موقع");
                });
            } catch (Exception e) { runOnUiThread(() -> showError(e)); }
        }).start();
    }

    private void showError(Exception e) {
        String m = e.getMessage() == null ? "حدث خطأ" : e.getMessage();
        status.setText("خطأ: " + m);
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
