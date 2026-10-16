package com.zephyr.notificationrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.api.XposedInterface;

final class ScreenStatePublisher {
    private static final String TAG = "MapleNotifyModule";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, String> watchers = new LinkedHashMap<>();
    private long sequence;

    void install(XposedInterface module, Context context) throws ReflectiveOperationException {
        ClassLoader loader = context.getClassLoader();
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        Class<?> serverClass = loader.loadClass("com.xiaomi.dist.notification.trans.NotifTransSvcMessage");
        Class<?> entity = loader.loadClass("com.xiaomi.dist.notification.trans.server.MessageEntity");
        Method className = method(entity, "getClassName");
        Method methodName = method(entity, "getMethodName");
        Method get = method(entity, "get", String.class);
        Method put = method(entity, "put", String.class, Object.class);
        Method publish = method(serverClass, "publish", String.class, entity);
        Object[] server = new Object[1];
        module.hook(method(serverClass, "handleOnClientSubscribe", String.class, String.class, entity)).intercept(chain -> {
            Object message = chain.getArg(2);
            if (!"zephyr_screen_state".equals(className.invoke(message))) return chain.proceed();
            try {
                if (!"notifi_trans".equals(chain.getArg(1)) || !"watch_v1".equals(methodName.invoke(message))) return null;
                Object value = get.invoke(message, "session");
                if (!(value instanceof String session) || !session.matches("[a-f0-9-]{36}")) return null;
                String sender = (String) chain.getArg(0);
                Object source = chain.getThisObject();
                executor.execute(() -> {
                    server[0] = source;
                    watchers.put(sender, session);
                    while (watchers.size() > 16) watchers.remove(watchers.keySet().iterator().next());
                    publishState(power, entity, put, publish, source, sender, session);
                });
            } catch (Throwable e) { failure(e); }
            return null;
        });
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                // Query PowerManager at publication time so queued screen events cannot replay stale state.
                executor.execute(() -> {
                    if (server[0] == null) return;
                    for (Map.Entry<String, String> watcher : watchers.entrySet())
                        publishState(power, entity, put, publish, server[0], watcher.getKey(), watcher.getValue());
                });
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
        Log.i(TAG, "screen state publisher installed");
    }

    private void publishState(PowerManager power, Class<?> entity, Method put, Method publish,
                              Object server, String target, String session) {
        try {
            Object response = entity.getConstructor(String.class, String.class).newInstance("zephyr_screen_state", "state_v1");
            put.invoke(response, "session", session);
            put.invoke(response, "sequence", ++sequence);
            put.invoke(response, "awake", power.isInteractive());
            publish.invoke(server, target, response);
            Log.i(TAG, "tablet screen state published");
        } catch (Throwable e) { failure(e); }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) throws ReflectiveOperationException {
        Method result = owner.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    private static void failure(Throwable error) {
        Log.w(TAG, "screen state publish failed: " + error.getClass().getSimpleName());
    }
}
