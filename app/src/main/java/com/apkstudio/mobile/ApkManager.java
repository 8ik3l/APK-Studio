package com.apkstudio.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ApkManager {
    private final Context context;
    private final File workspace;

    public ApkManager(Context c) {
        context = c;
        workspace = new File(c.getExternalFilesDir(null), "workspace");
        if (!workspace.exists() && !workspace.mkdirs()) {
            throw new IllegalStateException("تعذر إنشاء مجلد العمل");
        }
    }

    public File copyToWorkspace(Uri uri) throws IOException {
        File out = new File(workspace, "input.apk");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (in == null) throw new IOException("تعذر فتح ملف APK");
            copy(in, os);
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

    public File extract(File apk) throws IOException {
        File out = new File(workspace, baseName(apk) + "_project");
        deleteRecursive(out);
        if (!out.mkdirs()) throw new IOException("تعذر إنشاء مجلد الاستخراج");

        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(apk)))) {
            ZipEntry entry;
            byte[] buffer = new byte[1024 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                File target = safeChild(out, entry.getName());
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) throw new IOException("تعذر إنشاء مجلد");
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("تعذر إنشاء مجلد");
                    }
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zin.read(buffer)) != -1) os.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
        return out;
    }

    public File findExtractedProject(File apk) {
        File out = new File(workspace, baseName(apk) + "_project");
        return out.isDirectory() ? out : null;
    }

    public File build(File project) throws IOException {
        File out = new File(project.getParentFile(), baseName(project) + "-rebuilt-unsigned.apk");
        if (out.exists() && !out.delete()) throw new IOException("تعذر استبدال APK الناتج");

        File[] children = project.listFiles();
        if (children == null) throw new IOException("مشروع الاستخراج فارغ");

        try (ZipOutputStream zout = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            byte[] buffer = new byte[1024 * 1024];
            for (File child : children) {
                addToZip(project, child, child.getName(), zout, buffer);
            }
        }
        if (!out.isFile() || out.length() == 0) {
            throw new IOException("فشل إنشاء APK");
        }
        return out;
    }

    private void addToZip(File root, File file, String name, ZipOutputStream zout, byte[] buffer) throws IOException {
        if (file.isDirectory()) {
            String dirName = name.endsWith("/") ? name : name + "/";
            zout.putNextEntry(new ZipEntry(dirName));
            zout.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    addToZip(root, child, dirName + child.getName(), zout, buffer);
                }
            }
            return;
        }

        zout.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int n;
            while ((n = in.read(buffer)) != -1) zout.write(buffer, 0, n);
        }
        zout.closeEntry();
    }

    private File safeChild(File root, String name) throws IOException {
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) {
            throw new IOException("مسار غير آمن داخل APK: " + name);
        }
        return target;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    public String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
