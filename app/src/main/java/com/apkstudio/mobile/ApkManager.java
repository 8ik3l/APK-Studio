package com.apkstudio.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import brut.androlib.ApkBuilder;
import brut.androlib.ApkDecoder;
import brut.androlib.Config;

public class ApkManager {
    private final Context context;
    private final File workspace;

    public ApkManager(Context c) {
        context = c;
        workspace = new File(c.getExternalFilesDir(null), "workspace");
        if (!workspace.exists()) workspace.mkdirs();
    }

    public File copyToWorkspace(Uri uri) throws IOException {
        File out = new File(workspace, "input.apk");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("تعذر فتح الملف");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        return out;
    }

    public PackageInfo getPackageInfo(File apk) throws Exception {
        PackageInfo p = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(),
                android.content.pm.PackageManager.GET_ACTIVITIES |
                android.content.pm.PackageManager.GET_PERMISSIONS |
                android.content.pm.PackageManager.GET_SERVICES |
                android.content.pm.PackageManager.GET_PROVIDERS |
                android.content.pm.PackageManager.GET_RECEIVERS);
        if (p == null) throw new Exception("تعذر قراءة AndroidManifest.xml لهذا APK");
        if (p.applicationInfo != null) p.applicationInfo.sourceDir = apk.getAbsolutePath();
        return p;
    }

    public List<String> listEntries(File apk) throws IOException {
        ArrayList<String> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) out.add(e.nextElement().getName());
        }
        Collections.sort(out);
        return out;
    }

    public File decode(File apk) throws Exception {
        File out = new File(workspace, baseName(apk) + "_project");
        Config config = new Config("3.0.3");
        config.setForced(true);
        config.setJobs(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
        config.setDecodeSources(Config.DecodeSources.ONLY_MAIN_CLASSES);
        new ApkDecoder(apk, config).decode(out);
        return out;
    }

    public File findDecodedProject(File apk) {
        File out = new File(workspace, baseName(apk) + "_project");
        return out.isDirectory() ? out : null;
    }

    public File build(File project) throws Exception {
        Config config = new Config("3.0.3");
        config.setForced(true);
        File out = new File(project, "dist/rebuilt.apk");
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        new ApkBuilder(project, config).build(out);
        if (!out.isFile() || out.length() == 0) {
            throw new Exception("Apktool لم ينتج APK. راجع حالة المشروع والموارد.");
        }
        return out;
    }

    private String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0,i) : n;
    }

    public String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format(Locale.US, "%.1f KB", bytes/1024.0);
        return String.format(Locale.US, "%.1f MB", bytes/(1024.0*1024.0));
    }
}
