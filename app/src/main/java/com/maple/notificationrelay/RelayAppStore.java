package com.maple.notificationrelay;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class RelayAppStore {
    static final String AUTHORITY = "com.maple.notificationrelay.apps";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/apps");

    static final class App {
        final String packageName;
        final String label;
        final String iconUrl;
        Bitmap icon;

        App(String packageName, String label, String iconUrl) {
            this.packageName = packageName;
            this.label = label;
            this.iconUrl = iconUrl;
        }
    }

    static boolean validPackage(String name) {
        return name != null && name.length() <= 200
                && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    static File iconFile(Context context, String packageName) {
        if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid package");
        return new File(context.getFilesDir(), "apps/icons/" + packageName + ".png");
    }

    static synchronized Map<String, App> read(Context context) throws Exception {
        Map<String, App> apps = new LinkedHashMap<>();
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "apps/apps.json"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return apps;
        JSONArray array = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String name = item.getString("packageName");
            if (validPackage(name)) apps.put(name, new App(name, item.getString("label"), ""));
        }
        return apps;
    }

    static synchronized void save(Context context, App app, Bitmap bitmap) throws Exception {
        if (!validPackage(app.packageName) || app.label.isBlank() || app.label.length() > 200
                || bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Invalid app");
        File iconPath = iconFile(context, app.packageName);
        if (!iconPath.getParentFile().isDirectory() && !iconPath.getParentFile().mkdirs())
            throw new IOException("Cannot create icon directory");
        AtomicFile icon = new AtomicFile(iconPath);
        FileOutputStream output = icon.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Icon encode failed");
            icon.finishWrite(output);
        } catch (Exception e) { icon.failWrite(output); throw e; }
        Map<String, App> apps = read(context);
        apps.put(app.packageName, app);
        write(context, apps);
        context.getContentResolver().notifyChange(URI, null);
    }

    private static void write(Context context, Map<String, App> apps) throws Exception {
        JSONArray array = new JSONArray();
        for (App item : apps.values()) array.put(new JSONObject()
                .put("packageName", item.packageName).put("label", item.label));
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "apps/apps.json"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) { file.failWrite(output); throw e; }
    }

    static synchronized boolean remove(Context context, String packageName) throws Exception {
        if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid package");
        Map<String, App> apps = read(context);
        if (apps.remove(packageName) == null) return false;
        write(context, apps);
        new AtomicFile(iconFile(context, packageName)).delete();
        context.getContentResolver().notifyChange(URI, null);
        return true;
    }

    static synchronized void seed(Context context) throws Exception {
        File catalog = new File(context.getFilesDir(), "apps/apps.json");
        // An existing catalog may intentionally omit the default app after deletion.
        if (catalog.exists() || new File(catalog + ".bak").exists()) return;
        Bitmap icon = BitmapFactory.decodeResource(context.getResources(), R.drawable.xhs_icon);
        save(context, new App("com.xingin.xhs", "小红书", ""), icon);
    }

    static Map<String, App> loadForHook(Context context) throws Exception {
        Map<String, App> apps = new LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            if (cursor == null) throw new IOException("App catalog unavailable");
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow("packageName"));
                if (!validPackage(name)) continue;
                App app = new App(name, cursor.getString(cursor.getColumnIndexOrThrow("label")), "");
                try (InputStream input = context.getContentResolver().openInputStream(iconUri(name))) {
                    app.icon = BitmapFactory.decodeStream(input);
                }
                if (app.icon != null) apps.put(name, app);
            }
        }
        return apps;
    }

    static Uri iconUri(String packageName) {
        if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid package");
        return Uri.parse("content://" + AUTHORITY + "/icons/" + packageName);
    }
}
