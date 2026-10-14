package com.zephyr.notificationrelay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppLibrary {
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);

    static List<RelayAppStore.App> search(String query) throws Exception {
        if (query.isBlank() || query.length() > 100) throw new IllegalArgumentException("Invalid query");
        String page = new String(fetch("https://sj.qq.com/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8), 4 * 1024 * 1024), StandardCharsets.UTF_8);
        Matcher matcher = NEXT_DATA.matcher(page);
        if (!matcher.find()) throw new IOException("App library response changed");
        JSONObject response = new JSONObject(matcher.group(1)).getJSONObject("props")
                .getJSONObject("pageProps").getJSONObject("dynamicCardResponse");
        JSONArray components = response.getJSONObject("data").getJSONArray("components");
        LinkedHashMap<String, RelayAppStore.App> apps = new LinkedHashMap<>();
        for (int i = 0; i < components.length(); i++) {
            JSONObject data = components.getJSONObject(i).getJSONObject("data");
            if (!"SearchList".equals(data.optString("name"))) continue;
            JSONArray items = data.getJSONArray("itemData");
            for (int j = 0; j < items.length() && apps.size() < 50; j++) {
                JSONObject item = items.getJSONObject(j);
                String pkg = item.optString("pkg_name");
                String label = item.optString("name");
                String icon = item.optString("icon").replaceFirst("^http://", "https://");
                if (!RelayAppStore.validPackage(pkg) || pkg.startsWith("com.tencent.pcgame.")
                        || pkg.endsWith(".syzs_cloud") || label.isBlank() || icon.isBlank()) continue;
                apps.putIfAbsent(pkg, new RelayAppStore.App(pkg, label, icon));
            }
        }
        return new ArrayList<>(apps.values());
    }

    static Bitmap icon(String url) throws Exception {
        byte[] data = fetch(url, 1024 * 1024);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096)
            throw new IOException("Invalid icon dimensions");
        BitmapFactory.Options options = new BitmapFactory.Options();
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize == 0 ? 1 : options.inSampleSize) > 512)
            options.inSampleSize = options.inSampleSize == 0 ? 2 : options.inSampleSize * 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Cannot decode icon");
        float scale = 256f / Math.max(bitmap.getWidth(), bitmap.getHeight());
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    private static byte[] fetch(String address, int maxSize) throws Exception {
        URL url = new URL(address);
        String host = url.getHost();
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || !(host.equals("sj.qq.com") || host.endsWith(".myapp.com")
                || host.endsWith(".qpic.cn") || host.endsWith(".qq.com")))
            throw new IOException("Unsupported app library URL");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("App library HTTP " + code);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled");
                    if (output.size() + count > maxSize) throw new IOException("Response too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
