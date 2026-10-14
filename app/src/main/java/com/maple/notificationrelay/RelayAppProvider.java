package com.maple.notificationrelay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

public final class RelayAppProvider extends ContentProvider {
    @Override public boolean onCreate() {
        try { RelayAppStore.seed(getContext()); }
        catch (Exception e) { android.util.Log.e("MapleNotifyModule", "catalog initialization failed"); }
        return true;
    }

    private void checkReader() {
        if (Binder.getCallingUid() == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages)
            if ("com.milink.service".equals(name) || "com.android.systemui".equals(name)) return;
        throw new SecurityException("Catalog is available to relay processes only");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        checkReader();
        if (!RelayAppStore.URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        MatrixCursor cursor = new MatrixCursor(new String[]{"packageName", "label"});
        try {
            for (RelayAppStore.App app : RelayAppStore.read(getContext()).values())
                cursor.addRow(new Object[]{app.packageName, app.label});
        } catch (Exception e) { throw new IllegalStateException("Cannot read app catalog", e); }
        cursor.setNotificationUri(getContext().getContentResolver(), RelayAppStore.URI);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkReader();
        List<String> parts = uri.getPathSegments();
        if (!"r".equals(mode) || !RelayAppStore.AUTHORITY.equals(uri.getAuthority())
                || parts.size() != 2 || !"icons".equals(parts.get(0))
                || !RelayAppStore.validPackage(parts.get(1))) throw new FileNotFoundException("Unknown icon");
        File file = RelayAppStore.iconFile(getContext(), parts.get(1));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        checkReader();
        return uri.getPath().startsWith("/icons/") ? "image/png" : "vnd.android.cursor.dir/vnd.maple.relayapp";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) {
        if (Binder.getCallingUid() != Process.myUid()) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            boolean allowed = false;
            if (packages != null) for (String name : packages)
                if ("com.milink.service".equals(name)) allowed = true;
            if (!allowed) throw new SecurityException("Only relay settings can delete apps");
        }
        List<String> parts = uri.getPathSegments();
        if (!RelayAppStore.AUTHORITY.equals(uri.getAuthority()) || parts.size() != 2
                || !"apps".equals(parts.get(0)) || !RelayAppStore.validPackage(parts.get(1))
                || where != null || args != null) throw new IllegalArgumentException("Unknown app");
        try { return RelayAppStore.remove(getContext(), parts.get(1)) ? 1 : 0; }
        catch (Exception e) { throw new IllegalStateException("Cannot delete app", e); }
    }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
