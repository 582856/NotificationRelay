package com.zephyr.notificationrelay;

import android.content.Context;
import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.function.Consumer;

final class RelayDiagnostics {
    static final String TAG = "RelayEntryDebug";
    private static final int LIMIT = 128 * 1024;
    private static final ArrayDeque<String> pending = new ArrayDeque<>();
    private static Context context;
    private static Handler writer;
    private static Consumer<String> framework;

    static synchronized void framework(Consumer<String> logger) {
        if (BuildConfig.RELAY_DIAGNOSTICS) framework = logger;
    }

    static synchronized void note(String event) {
        if (!BuildConfig.RELAY_DIAGNOSTICS) return;
        String line = System.currentTimeMillis() + " pid=" + Process.myPid() + " " + event.replace('\n', ' ').replace('\r', ' ');
        Log.i(TAG, line);
        if (framework != null) {
            try { framework.accept(line); }
            catch (Throwable ignored) { Log.w(TAG, "framework diagnostic storage unavailable"); }
        }
        if (pending.size() >= 64) pending.removeFirst();
        pending.addLast(line);
        flush();
    }

    static synchronized void attach(Context attached) {
        if (!BuildConfig.RELAY_DIAGNOSTICS) return;
        context = attached.getApplicationContext();
        if (context == null) context = attached;
        flush();
    }

    static void watchPages(Application application) {
        if (!BuildConfig.RELAY_DIAGNOSTICS) return;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                note("activity_created class=" + activity.getClass().getName());
            }
            @Override public void onActivityResumed(Activity activity) {
                note("activity_resumed class=" + activity.getClass().getName());
            }
            @Override public void onActivityDestroyed(Activity activity) {
                note("activity_destroyed class=" + activity.getClass().getName());
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        });
        note("milink_page_lifecycle_probe_installed");
    }

    private static void flush() {
        if (context == null || pending.isEmpty()) return;
        if (writer == null) {
            HandlerThread thread = new HandlerThread("RelayEntryDebug");
            thread.start();
            writer = new Handler(thread.getLooper());
        }
        Context target = context;
        while (!pending.isEmpty()) {
            String line = pending.removeFirst();
            writer.post(() -> {
                try {
                    Bundle extras = new Bundle();
                    extras.putString("line", line);
                    target.getContentResolver().call(Uri.parse("content://com.zephyr.notificationrelay.apps"),
                            "appendDiagnostic", null, extras);
                } catch (Exception error) {
                    Log.w(TAG, "diagnostic storage unavailable: " + error.getClass().getSimpleName());
                }
            });
        }
    }

    static void error(String stage, Throwable error) {
        if (!BuildConfig.RELAY_DIAGNOSTICS) return;
        StringBuilder detail = new StringBuilder("error stage=").append(stage);
        // Exception messages can contain notification or account data; export only types and code locations.
        for (int i = 0; error != null && i < 3; i++, error = error.getCause()) {
            detail.append(" cause=").append(error.getClass().getName());
            StackTraceElement[] trace = error.getStackTrace();
            for (int j = 0; j < Math.min(4, trace.length); j++) {
                detail.append(" at=").append(trace[j].getClassName()).append('.').append(trace[j].getMethodName())
                        .append(':').append(trace[j].getLineNumber());
            }
        }
        note(detail.toString());
    }

    private static File file(Context owner) { return new File(owner.getFilesDir(), "entry-diagnostics.log"); }

    static synchronized void append(Context owner, String line) throws Exception {
        if (!BuildConfig.RELAY_DIAGNOSTICS || line == null || line.length() > 4096) throw new IllegalArgumentException("Invalid diagnostic");
        File file = file(owner);
        if (file.length() > LIMIT) Files.writeString(file.toPath(), "[Older diagnostic events cleared]\n", StandardCharsets.UTF_8);
        Files.writeString(file.toPath(), line.replace('\n', ' ').replace('\r', ' ') + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static synchronized String read(Context owner) throws Exception {
        File file = file(owner);
        return file.exists() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
    }
}
