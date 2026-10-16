package com.zephyr.relayquiet;

import android.app.Application;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuietModule extends XposedModule {
    private static final String TAG = "RelayQuietModule";
    private static final String MILINK = "com.milink.service";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String EVENT = "com.zephyr.relayquiet.EVENT";
    private static final String QUERY = "com.zephyr.relayquiet.QUERY";
    private static final long WAIT_MS = 200;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private final TransferState transfers = new TransferState();
    private final ThreadLocal<Object> alertEntry = new ThreadLocal<>();
    private final ThreadLocal<Boolean> replay = new ThreadLocal<>();
    private final Map<String, PendingWake> waiting = new ConcurrentHashMap<>();
    private SharedPreferences.OnSharedPreferenceChangeListener appListener;
    private static final class Peer {
        final String digest;
        String session;
        long sequence = -1;
        long queried = -1000;
        long received = -1;
        boolean awake;
        Peer(String digest) { this.digest = digest; }
    }
    private record PendingWake(PowerManager.WakeLock lock, String app) {
        void release() { if (lock.isHeld()) lock.release(); }
    }
    private String process;

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        process = param.getProcessName();
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"popsicle".equals(Build.DEVICE)) return;
        boolean sender = MILINK.equals(param.getPackageName()) && "com.milink.crossdeviceservice".equals(process);
        boolean systemUi = SYSTEM_UI.equals(param.getPackageName()) && SYSTEM_UI.equals(process);
        if (!sender && !systemUi) return;
        try {
            hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (INSTALLED.compareAndSet(false, true)) {
                    try {
                        Context context = (Context) chain.getArg(0);
                        if (sender) installSender(context); else installWakeGate(context);
                    } catch (Throwable e) { failure("install", e); }
                }
                return result;
            });
        } catch (Throwable e) { failure("attach", e); }
    }

    private void installSender(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> post = loader.loadClass("com.xiaomi.dist.notification.listener.trans.NotificationPostRequest");
        Class<?> device = loader.loadClass("com.xiaomi.continuity.networking.TrustedDeviceInfo");
        Class<?> message = loader.loadClass("com.xiaomi.dist.notification.common.data.NotificationMessage");
        Class<?> callback = loader.loadClass("com.xiaomi.continuity.messagecenter.PublisherManager$IPublishResult");
        Class<?> server = loader.loadClass("com.xiaomi.dist.notification.trans.NotifTransSvcMessage");
        Class<?> entity = loader.loadClass("com.xiaomi.dist.notification.trans.server.MessageEntity");
        Method deviceType = method(device, "getDeviceType");
        Method deviceId = method(device, "getDeviceId");
        Method key = method(message, "getNotificationKey");
        Method time = method(message, "getTimestamp");
        Method className = method(entity, "getClassName");
        Method methodName = method(entity, "getMethodName");
        Method get = method(entity, "get", String.class);
        Method put = method(entity, "put", String.class, Object.class);
        Method publish = method(server, "publish", String.class, entity);
        Class<?> resultClass = loader.loadClass("com.xiaomi.dist.notification.listener.trans.NotifTransManager$1");
        Class<?> messageData = loader.loadClass("com.xiaomi.continuity.messagecenter.MessageData");
        Class<?> task = loader.loadClass("com.xiaomi.dist.notification.listener.trans.NotifTransManager$TransTask");
        Method taskMessage = method(task, "notificationMessage");
        int systemUiUid = context.getPackageManager().getPackageUid(SYSTEM_UI, 0);
        HandlerThread worker = new HandlerThread("RelayQuietState");
        worker.start();
        Handler handler = new Handler(worker.getLooper());
        Map<String, Peer> peers = new LinkedHashMap<>();
        installAppList(context, handler);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                if (getSentFromUid() != systemUiUid || !QUERY.equals(intent.getAction())) return;
                if ("apps".equals(intent.getStringExtra("kind"))) {
                    sendApps(context);
                    return;
                }
                String token = intent.getStringExtra("token");
                if (!TransferState.valid(token)) return;
                TransferState.Sent sent = transfers.get(token, SystemClock.elapsedRealtime());
                if (sent == null) return;
                sendEvent(context, new Intent(EVENT).putExtra("kind", "sent").putExtra("token", token)
                        .putExtra("target", sent.target()).putExtra("time", sent.time()));
                for (Peer peer : peers.values()) if (peer.digest.equals(sent.target()) && peer.received >= 0)
                    sendScreen(context, peer);
            }
        }, new IntentFilter(QUERY), null, handler, Context.RECEIVER_EXPORTED);

        hook(method(post, "transNotification", device, message, callback)).intercept(chain -> {
            try {
                Object target = chain.getArg(0);
                if (((Number) deviceType.invoke(target)).intValue() == 2) {
                    Object incoming = chain.getArg(1);
                    String notificationKey = (String) key.invoke(incoming);
                    String targetId = (String) deviceId.invoke(target);
                    if (notificationKey != null && targetId != null) {
                        Object transport = field(chain.getThisObject(), "mSvcMessageServer");
                        handler.post(() -> {
                            try {
                                Peer peer = peers.get(targetId);
                                if (peer == null) {
                                    peer = new Peer(TransferState.token(targetId, 0));
                                    peers.put(targetId, peer);
                                    while (peers.size() > 16) peers.remove(peers.keySet().iterator().next());
                                }
                                long now = SystemClock.elapsedRealtime();
                                if (peer.received >= 0 && now - peer.received < TransferState.TTL_MS) return;
                                if (now - peer.queried < WAIT_MS) return;
                                peer.session = UUID.randomUUID().toString();
                                peer.sequence = -1;
                                peer.queried = now;
                                Object watch = entity.getConstructor(String.class, String.class)
                                        .newInstance("zephyr_screen_state", "watch_v1");
                                put.invoke(watch, "session", peer.session);
                                publish.invoke(transport, targetId, watch);
                            } catch (Throwable e) { failure("query tablet state", e); }
                        });
                    }
                }
            } catch (Throwable e) { failure("track transfer", e); }
            return chain.proceed();
        });
        hook(method(resultClass, "onSendSuccess", messageData, int.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object target = field(chain.getThisObject(), "val$deviceInfo");
                if (((Number) deviceType.invoke(target)).intValue() != 2) return result;
                Object incoming = taskMessage.invoke(field(chain.getThisObject(), "val$task"));
                String notificationKey = (String) key.invoke(incoming);
                String targetId = (String) deviceId.invoke(target);
                if (notificationKey == null || targetId == null) return result;
                String token = TransferState.token(notificationKey, ((Number) time.invoke(incoming)).longValue());
                String targetDigest = TransferState.token(targetId, 0);
                handler.post(() -> {
                    long now = SystemClock.elapsedRealtime();
                    transfers.sent(token, targetDigest, now);
                    sendEvent(context, new Intent(EVENT).putExtra("kind", "sent").putExtra("token", token)
                            .putExtra("target", targetDigest).putExtra("time", now));
                    Peer peer = peers.get(targetId);
                    if (peer != null && peer.received >= 0) sendScreen(context, peer);
                    Log.i(TAG, "tablet notification send succeeded");
                });
            } catch (Throwable e) { failure("send result", e); }
            return result;
        });
        hook(method(server, "handleOnClientSubscribe", String.class, String.class, entity)).intercept(chain -> {
            Object response = chain.getArg(2);
            if (!"zephyr_screen_state".equals(className.invoke(response))) return chain.proceed();
            try {
                if (!"notifi_trans".equals(chain.getArg(1)) || !"state_v1".equals(methodName.invoke(response))) return null;
                Object session = get.invoke(response, "session");
                Object seq = get.invoke(response, "sequence");
                Object awake = get.invoke(response, "awake");
                if (!(session instanceof String) || !(seq instanceof Number) || !(awake instanceof Boolean)) return null;
                String sender = (String) chain.getArg(0);
                handler.post(() -> {
                    Peer peer = peers.get(sender);
                    long sequence = ((Number) seq).longValue();
                    if (peer == null || !session.equals(peer.session) || sequence <= peer.sequence) return;
                    peer.sequence = sequence;
                    peer.awake = (Boolean) awake;
                    peer.received = SystemClock.elapsedRealtime();
                    transfers.screen(peer.digest, peer.awake, peer.received);
                    sendScreen(context, peer);
                    Log.i(TAG, "tablet screen state received");
                });
            } catch (Throwable e) { failure("tablet state", e); }
            return null;
        });
        Log.i(TAG, "sender state and success hooks installed");
    }

    private void installAppList(Context context, Handler handler) {
        SharedPreferences preferences = context.getSharedPreferences("app_list_prefs", Context.MODE_PRIVATE);
        Runnable refresh = () -> {
            Set<String> apps = new HashSet<>();
            try {
                // Only subscription settings are parsed; device identities are neither read nor shared.
                if (preferences.getInt("subscription_protocol_version", 0) >= 2) {
                    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                        if (!entry.getKey().startsWith("subscription_app_list_") || !(entry.getValue() instanceof String value)) continue;
                        JSONObject device = new JSONObject(value);
                        if (device.optInt("deviceType") != 2 || device.optInt("deviceSwitch") != 1) continue;
                        JSONArray list = device.optJSONArray("subscriptionAppInfoList");
                        if (list == null) continue;
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject app = list.optJSONObject(i);
                            String name = app == null ? "" : app.optString("packageName", "");
                            if (!name.isEmpty()) apps.add(TransferState.token(name, 0));
                        }
                    }
                }
                if (apps.size() > 4096) apps.clear();
            } catch (Throwable e) { apps.clear(); failure("subscription list", e); }
            transfers.apps(apps);
            sendApps(context);
            Log.i(TAG, "subscription app list synchronized");
        };
        appListener = (prefs, key) -> {
            if (key == null || key.startsWith("subscription_app_list_") || "subscription_protocol_version".equals(key)) handler.post(refresh);
        };
        preferences.registerOnSharedPreferenceChangeListener(appListener);
        handler.post(refresh);
    }

    private void sendApps(Context context) {
        sendEvent(context, new Intent(EVENT).putExtra("kind", "apps")
                .putStringArrayListExtra("apps", new ArrayList<>(transfers.apps())));
    }

    private void installWakeGate(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Handler handler = new Handler(Looper.getMainLooper());
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null || keyguard == null) throw new IllegalStateException("system services unavailable");
        int senderUid = context.getPackageManager().getPackageUid(MILINK, 0);
        Class<?> manager = loader.loadClass("com.android.systemui.statusbar.notification.policy.MiuiAlertManager");
        Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
        Class<?> bound = loader.loadClass("com.android.systemui.statusbar.notification.collection.coordinator.AlertCoordinator$$ExternalSyntheticLambda0");
        Class<?> proximity = loader.loadClass("com.android.keyguard.injector.KeyguardSensorInjector$mProximitySensorListener$1");
        Method wake = method(manager, "wakeUpForNotificationInternal");
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                if (getSentFromUid() != senderUid || !EVENT.equals(intent.getAction())) return;
                if ("apps".equals(intent.getStringExtra("kind"))) {
                    ArrayList<String> apps = intent.getStringArrayListExtra("apps");
                    if (apps == null || apps.size() > 4096) return;
                    for (String app : apps) if (!TransferState.valid(app)) return;
                    transfers.apps(apps);
                    Log.i(TAG, "subscription app list received");
                    return;
                }
                String target = intent.getStringExtra("target");
                long now = SystemClock.elapsedRealtime();
                long time = intent.getLongExtra("time", -1);
                if (!TransferState.valid(target) || time < 0 || time > now || now - time >= TransferState.TTL_MS) return;
                if ("screen".equals(intent.getStringExtra("kind"))) {
                    transfers.screen(target, intent.getBooleanExtra("awake", false), time);
                } else if ("sent".equals(intent.getStringExtra("kind"))) {
                    String token = intent.getStringExtra("token");
                    if (!TransferState.valid(token)) return;
                    transfers.sent(token, target, time);
                } else return;
                for (Map.Entry<String, PendingWake> entry : waiting.entrySet()) {
                    if (!transfers.enabled(entry.getValue().app()) || !transfers.canSuppress(entry.getKey(), now)
                            || !waiting.remove(entry.getKey(), entry.getValue())) continue;
                    entry.getValue().release();
                    Log.i(TAG, "notification wake suppressed for awake tablet");
                }
            }
        }, new IntentFilter(EVENT), null, handler, Context.RECEIVER_EXPORTED);
        hook(method(bound, "onViewBound$1", entry)).intercept(chain -> {
            Object previous = alertEntry.get();
            alertEntry.set(chain.getArg(0));
            try { return chain.proceed(); }
            finally { restoreEntry(previous); }
        });
        hook(method(proximity, "onSensorChanged", boolean.class)).intercept(chain -> {
            Object previous = alertEntry.get();
            Object current = null;
            try {
                Object sensor = field(chain.getThisObject(), "this$0");
                Object pending = field(sensor, "mProximitySensorChangeCallback");
                if (pending != null) current = field(pending, "f$1");
            } catch (Throwable e) { failure("proximity entry", e); }
            if (current == null) alertEntry.remove(); else alertEntry.set(current);
            try { return chain.proceed(); }
            finally { restoreEntry(previous); }
        });
        hook(wake).intercept(chain -> {
            if (Boolean.TRUE.equals(replay.get()) || power.isInteractive() || !keyguard.isKeyguardLocked()
                    || alertEntry.get() == null) return chain.proceed();
            Object current = alertEntry.get();
            String token;
            String app;
            try {
                StatusBarNotification sbn = (StatusBarNotification) field(current, "mSbn");
                app = TransferState.token(sbn.getPackageName(), 0);
                if (!sbn.getUser().equals(android.os.Process.myUserHandle()) || !transfers.enabled(app)) return chain.proceed();
                Notification notification = sbn.getNotification();
                if (Notification.CATEGORY_CALL.equals(notification.category) || Notification.CATEGORY_ALARM.equals(notification.category)
                        || notification.fullScreenIntent != null) return chain.proceed();
                token = TransferState.token(sbn.getKey(), sbn.getPostTime());
            } catch (Throwable e) { failure("wake identity", e); return chain.proceed(); }
            if (transfers.canSuppress(token, SystemClock.elapsedRealtime())) {
                Log.i(TAG, "notification wake suppressed for awake tablet");
                return null;
            }
            if (waiting.size() >= 128) return chain.proceed();
            Object instance = chain.getThisObject();
            PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RelayQuiet:state");
            lock.acquire(WAIT_MS + 400);
            PendingWake pending = new PendingWake(lock, app);
            PendingWake old = waiting.put(token, pending);
            if (old != null) old.release();
            // Never keep a libxposed Chain beyond this invocation; replay the method under a guard instead.
            boolean scheduled = handler.postDelayed(() -> {
                if (!waiting.remove(token, pending)) return;
                try {
                    if ((transfers.enabled(app) && transfers.canSuppress(token, SystemClock.elapsedRealtime()))
                            || power.isInteractive() || !keyguard.isKeyguardLocked()) return;
                    StatusBarNotification latest = (StatusBarNotification) field(current, "mSbn");
                    if (!token.equals(TransferState.token(latest.getKey(), latest.getPostTime()))
                            || ((Number) field(current, "mCancellationReason")).intValue() != -1
                            || !"NOT_DISMISSED".equals(String.valueOf(field(current, "mDismissState")))) return;
                    replay.set(true);
                    wake.invoke(instance);
                    Log.i(TAG, "notification wake continued by fallback");
                } catch (Throwable e) { failure("wake replay", e); }
                finally { replay.remove(); pending.release(); }
            }, WAIT_MS);
            if (!scheduled) {
                waiting.remove(token, pending);
                pending.release();
                return chain.proceed();
            }
            try {
                context.sendBroadcast(new Intent(QUERY).setPackage(MILINK).putExtra("token", token), null,
                        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            } catch (Throwable e) { failure("query send state", e); }
            return null;
        });
        context.sendBroadcast(new Intent(QUERY).setPackage(MILINK).putExtra("kind", "apps"), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        Log.i(TAG, "notification wake gate installed");
    }

    private void restoreEntry(Object previous) {
        if (previous == null) alertEntry.remove(); else alertEntry.set(previous);
    }

    private static void sendEvent(Context context, Intent intent) {
        context.sendBroadcast(intent.setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private static void sendScreen(Context context, Peer peer) {
        sendEvent(context, new Intent(EVENT).putExtra("kind", "screen").putExtra("target", peer.digest)
                .putExtra("awake", peer.awake).putExtra("time", peer.received));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static void failure(String action, Throwable error) {
        Log.w(TAG, action + " failed: " + error.getClass().getSimpleName());
    }
}
