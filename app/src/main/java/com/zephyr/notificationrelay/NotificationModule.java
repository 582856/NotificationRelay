package com.zephyr.notificationrelay;

import android.app.Application;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class NotificationModule extends XposedModule {
    private static final String TARGET = "com.milink.service";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String XHS = "com.xingin.xhs";
    private static final String KEY = "check_box_pref_key_app_maple_xhs";
    private static final String TAG = "MapleNotifyModule";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> NOTIFICATION_ENTRY = new ThreadLocal<>();
    private static Bitmap xhsIcon;
    private volatile Map<String, RelayAppStore.App> apps = new LinkedHashMap<>();
    private final Map<Object, WeakReference<Object>> customFragments = new WeakHashMap<>();
    private final Map<Object, WeakReference<Object>> pendingDeletes = new WeakHashMap<>();
    private final Map<String, String> statusBarSourceApps = Collections.synchronizedMap(new WeakHashMap<>());
    private String process;

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        process = param.getProcessName();
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"yingtian".equals(Build.DEVICE)) return;
        boolean milink = TARGET.equals(param.getPackageName()) && ("com.milink.crossdeviceservice".equals(process)
                || (TARGET + ":ui").equals(process) || TARGET.equals(process));
        boolean systemUi = SYSTEM_UI.equals(param.getPackageName()) && SYSTEM_UI.equals(process);
        if (!milink && !systemUi) return;
        try {
            hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (INSTALLED.compareAndSet(false, true)) install((Context) chain.getArg(0));
                return result;
            });
        } catch (Throwable e) { failure("attach", e); }
    }

    private void install(Context context) {
        ClassLoader loader = context.getClassLoader();
        if (SYSTEM_UI.equals(process)) {
            installStatusBarIconHook(context, loader);
            installStatusBarAppGrouping(loader);
            installGroupSummaryIconHook(loader);
            return;
        }
        if (!(TARGET + ":ui").equals(process)) watchAppCatalog(context);
        if (TARGET.equals(process)) {
            installDetailIconHook(loader);
            return;
        }
        if ((TARGET + ":ui").equals(process)) {
            installSettingsEntry(loader);
            return;
        }
        installSubscriptionDiagnostics(loader);
        installNotificationClickHook(loader);
        installScreenOffFilter(context, loader);
        try {
            Class<?> fragment = loader.loadClass("com.xiaomi.dist.notification.setting.SubscriptionAppFragment");
            Class<?> preference = loader.loadClass("androidx.preference.Preference");
            Class<?> checkbox = loader.loadClass("com.xiaomi.dist.notification.setting.preference.SubscriptionCheckBoxPreference");
            Class<?> group = loader.loadClass("androidx.preference.PreferenceGroup");
            Method add = method(group, "l", preference);
            Method setup = method(fragment, "setupCheckBoxPreference", checkbox, String.class);
            hook(method(fragment, "handleSubscribe", checkbox, String.class)).intercept(chain -> {
                Object target = chain.getThisObject();
                Object device = field(target, "mDeviceInfo");
                if (device == null || !Integer.valueOf(1).equals(method(device.getClass(), "getDeviceType").invoke(device)))
                    return chain.proceed();
                Field online = fragment.getDeclaredField("isOnline");
                online.setAccessible(true);
                boolean previous = online.getBoolean(target);
                // The launch-time discovery cache can be offline while stock messaging is reachable.
                // Attempt the real subscription; its permission, transport and cloud results still control the switch.
                online.setBoolean(target, true);
                try { return chain.proceed(); }
                finally { online.setBoolean(target, previous); }
            });
            hook(method(fragment, "initPreference")).intercept(chain -> {
                Object result = chain.proceed();
                try { addPreferences(chain.getThisObject(), preference, checkbox, add, setup); }
                catch (Throwable e) { failure("preference", e); }
                return result;
            });
            hook(method(fragment, "onResume")).intercept(chain -> {
                Object result = chain.proceed();
                if (fragment.isInstance(chain.getThisObject())) {
                    try {
                        if (addPreferences(chain.getThisObject(), preference, checkbox, add, setup)) {
                            Object target = chain.getThisObject();
                            java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) field(target, "mExecutor");
                            executor.execute(() -> {
                                try { method(fragment, "querySubscribeInfo").invoke(target); }
                                catch (Throwable e) { failure("refresh subscriptions", e); }
                            });
                        }
                    } catch (Throwable e) { failure("resume preferences", e); }
                }
                return result;
            });
            installAddButton(loader);
            installDeleteButton(loader, fragment, preference, checkbox, group);
            Log.i(TAG, "subscription UI hook installed");
        } catch (Throwable e) { failure("UI hook", e); }

        try {
            Class<?> processor = loader.loadClass("com.xiaomi.dist.notification.trans.client.receive.NotificationProcessor");
            Class<?> message = loader.loadClass("com.xiaomi.dist.notification.common.data.NotificationMessage");
            Method packageName = method(message, "getPackageName");
            Method sender = method(message, "getSenderDeviceId");
            Method mirror = method(processor, "getMirrorDeviceInfo", String.class);
            hook(method(processor, "getNotificationShowType", message)).intercept(chain -> {
                Object result = chain.proceed();
                if (Integer.valueOf(-1).equals(result) && apps.containsKey(packageName.invoke(chain.getArg(0)))) {
                    Log.i(TAG, "custom app notification accepted");
                    return mirror.invoke(chain.getThisObject(), sender.invoke(chain.getArg(0))) != null ? 2 : 1;
                }
                return result;
            });
            Class<?> controller = loader.loadClass("com.xiaomi.dist.notification.handler.NotificationController");
            hook(method(controller, "getTitle", Context.class, String.class, String.class)).intercept(chain -> {
                RelayAppStore.App app = apps.get(chain.getArg(1));
                return app != null ? app.label + " | " + chain.getArg(2) : chain.proceed();
            });
            hook(method(controller, "getSubtitle", Context.class, String.class)).intercept(chain ->
                    apps.containsKey(chain.getArg(1)) ? "在手机上查看或回复" : chain.proceed());
            installIconHooks(loader, controller);
            Log.i(TAG, "XHS receive hook installed");
        } catch (Throwable e) { failure("receive hook", e); }
    }

    private void installScreenOffFilter(Context context, ClassLoader loader) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power == null) throw new IllegalStateException("PowerManager unavailable");
            Class<?> processor = loader.loadClass("com.xiaomi.dist.notification.trans.client.receive.NotificationProcessor");
            Class<?> message = loader.loadClass("com.xiaomi.dist.notification.common.data.NotificationMessage");
            Method encrypted = method(processor, "processorNotification", String.class);
            Method direct = method(processor, "processorNotification", message);
            // Check the current screen state at both transport entries, before parsing message data.
            for (Method entry : new Method[] { encrypted, direct }) {
                hook(entry).intercept(chain -> {
                    if (!power.isInteractive()) return null;
                    return chain.proceed();
                });
            }
            Log.i(TAG, "screen-off notification filter installed");
        } catch (Throwable e) { failure("screen-off notification filter", e); }
    }

    /**
     * Adapted from HyperConnectToolkit's MiLinkNotificationFlowHooks (Apache-2.0).
     * Copyright 2026 silverpoetry. Modified for NotificationRelay's modern entry and private logs.
     * Stock still sends a passive command after launching; consume only its paired request.
     */
    private void installNotificationClickHook(ClassLoader loader) {
        try {
            Class<?> sdk = loader.loadClass("com.xiaomi.mirror.synergy.MiuiSynergySdk");
            Class<?> relay = loader.loadClass("com.xiaomi.mirror.synergy.MiuiSynergySdk$RelayAppCallback");
            Class<?> client = loader.loadClass("com.xiaomi.dist.notification.trans.client.NotifTransClient");
            Class<?> message = loader.loadClass("com.xiaomi.dist.notification.common.data.NotificationMessage");
            Class<?> callback = loader.loadClass("com.xiaomi.dist.notification.trans.client.callback.INotificationTransCallback");
            Method launch = method(sdk, "launchAppFromPendingIntentSink", Context.class, String.class, String.class, relay);
            Method success = method(relay, "onSuccess");
            Method failed = method(relay, "onFailure", int.class);
            Method publish = method(client, "handleNotification", String.class, message, callback);
            Method packageName = method(message, "getPackageName");
            Method result = method(callback, "onResult", int.class, Object.class);
            NotificationClickSession session = new NotificationClickSession();
            hook(publish).intercept(chain -> {
                String app;
                try { app = (String) packageName.invoke(chain.getArg(1)); }
                catch (Throwable e) {
                    session.clear();
                    failure("notification click correlation", e);
                    return chain.proceed();
                }
                if (!session.consumeMatching((String) chain.getArg(0), app, SystemClock.elapsedRealtime()))
                    return chain.proceed();
                if (chain.getArg(2) != null) {
                    try { result.invoke(chain.getArg(2), 1, null); }
                    catch (Throwable e) { failure("notification click acknowledgement", e); }
                }
                Log.i(TAG, "duplicate notification stream request consumed");
                return null;
            });
            hook(launch).intercept(chain -> {
                String device = (String) chain.getArg(1);
                String app = (String) chain.getArg(2);
                if (!(chain.getArg(0) instanceof Context context)
                        || device == null || device.isEmpty() || app == null || app.isEmpty())
                    return chain.proceed();
                boolean dispatched = false;
                try {
                    Bundle extras = new Bundle();
                    extras.putString("remoteDeviceId", device);
                    extras.putString("package", app);
                    dispatched = context.getContentResolver().call("com.xiaomi.mirror.callprovider",
                            "performPinIconClick", null, extras) != null;
                    if (!dispatched) Log.w(TAG, "notification PIN_APP provider returned no result");
                } catch (Throwable e) { failure("notification PIN_APP dispatch", e); }
                session.begin(device, app, SystemClock.elapsedRealtime());
                if (chain.getArg(3) != null) {
                    try {
                        if (dispatched) success.invoke(chain.getArg(3));
                        else failed.invoke(chain.getArg(3), -1);
                    } catch (Throwable e) { failure("notification click callback", e); }
                }
                if (dispatched) Log.i(TAG, "notification click dispatched through stock PIN_APP");
                return null;
            });
            Log.i(TAG, "notification click hooks installed");
        } catch (Throwable e) { failure("notification click hooks", e); }
    }

    private void watchAppCatalog(Context context) {
        reloadAppCatalog(context);
        try {
            context.getContentResolver().registerContentObserver(RelayAppStore.URI, true,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange) { reloadAppCatalog(context); }
                    });
        } catch (Throwable e) { failure("catalog observer", e); }
    }

    private void reloadAppCatalog(Context context) {
        try { apps = RelayAppStore.loadForHook(context); }
        catch (Throwable e) {
            failure("catalog load", e);
            if (apps.isEmpty()) {
                try {
                    RelayAppStore.App app = new RelayAppStore.App(XHS, "小红书", "");
                    app.icon = getXhsIcon(context);
                    Map<String, RelayAppStore.App> fallback = new LinkedHashMap<>();
                    fallback.put(XHS, app);
                    apps = fallback;
                } catch (Throwable iconError) { failure("catalog fallback", iconError); }
            }
        }
    }

    private void installAddButton(ClassLoader loader) {
        try {
            Class<?> activity = loader.loadClass("com.xiaomi.dist.notification.setting.SubscriptionAppActivity");
            Method enableMenu = method(activity, "setEndActionMenuEnabled", boolean.class);
            hook(method(activity, "onCreate", Bundle.class)).intercept(chain -> {
                Activity target = (Activity) chain.getThisObject();
                boolean shortcut = target.getIntent().getBooleanExtra("zephyr_open_app_list", false);
                boolean ready = false;
                if (shortcut) {
                    try { ready = prepareModuleAppList(target, loader); }
                    catch (Throwable e) { failure("module app list entry", e); }
                }
                Object result = chain.proceed();
                if (shortcut && !ready) {
                    try {
                        target.startActivity(new Intent().setClassName(TARGET,
                                "com.miui.circulate.world.ui.connectivitysettings.Notification.FeatureNotificationActivity"));
                        target.finish();
                    } catch (Throwable e) { failure("module settings fallback", e); }
                    return result;
                }
                try {
                    Object device = target.getIntent().getParcelableExtra("device_info");
                    if (device != null && Integer.valueOf(1).equals(method(device.getClass(), "getDeviceType").invoke(device))) {
                        // Miuix skips creating the right-hand menu unless this page enables it.
                        enableMenu.invoke(target, true);
                        target.invalidateOptionsMenu();
                    }
                } catch (Throwable e) { failure("enable add app menu", e); }
                return result;
            });
            hook(method(activity, "onCreateOptionsMenu", Menu.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (!activity.isInstance(chain.getThisObject())) return result;
                Activity target = (Activity) chain.getThisObject();
                try {
                    Object device = target.getIntent().getParcelableExtra("device_info");
                    if (device == null || !Integer.valueOf(1).equals(method(device.getClass(), "getDeviceType").invoke(device)))
                        return result;
                    Menu menu = (Menu) chain.getArg(0);
                    int id = 0x4d415050;
                    if (menu.findItem(id) == null) {
                        int endGroup = target.getResources().getIdentifier("miuix_action_end_menu_group", "id", TARGET);
                        if (endGroup == 0) throw new IllegalStateException("Miuix end menu group unavailable");
                        // Miuix routes only this group into the title bar's right-hand menu.
                        MenuItem item = menu.add(endGroup, id, 0, "搜索并添加应用");
                        int addId = target.getResources().getIdentifier("icon_car_air_con_plus", "drawable", TARGET);
                        if (addId != 0) {
                            Drawable icon = target.getDrawable(addId).mutate();
                            android.util.TypedValue color = new android.util.TypedValue();
                            if (target.getTheme().resolveAttribute(android.R.attr.textColorPrimary, color, true))
                                icon.setTintList(color.resourceId != 0 ? target.getColorStateList(color.resourceId)
                                        : android.content.res.ColorStateList.valueOf(color.data));
                            item.setIcon(icon);
                        }
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                        item.setOnMenuItemClickListener(clicked -> {
                            Intent intent = new Intent().setClassName("com.zephyr.notificationrelay",
                                    "com.zephyr.notificationrelay.AppPickerActivity");
                            target.startActivity(intent);
                            return true;
                        });
                    }
                    return true;
                } catch (Throwable e) { failure("add app menu", e); return result; }
            });
            Log.i(TAG, "add app menu hook installed");
        } catch (Throwable e) { failure("add app menu hook", e); }
    }

    private boolean prepareModuleAppList(Activity target, ClassLoader loader) throws Exception {
        Class<?> manager = loader.loadClass("com.xiaomi.dist.notification.discover.NotificationTransDeviceManager");
        Object instance = method(manager, "getInstance", Context.class).invoke(null, target);
        Object selected = null;
        for (Object device : (List<?>) method(manager, "getClientNotifiDevices").invoke(instance)) {
            if (!Integer.valueOf(1).equals(method(device.getClass(), "getDeviceType").invoke(device))) continue;
            // Multiple paired phones need the stock device chooser rather than an arbitrary selection.
            if (selected != null) return false;
            selected = device;
        }
        if (!(selected instanceof Parcelable device)) return false;
        Class<?> sdk = loader.loadClass("com.xiaomi.mirror.synergy.MiuiSynergySdk");
        Object synergy = method(sdk, "getInstance").invoke(null);
        Object remote = method(sdk, "queryRemoteDevice", Context.class, String.class).invoke(synergy, target,
                method(selected.getClass(), "getDeviceId").invoke(selected));
        target.getIntent().putExtra("device_info", device).putExtra("device_online", remote != null);
        return true;
    }

    private void installDeleteButton(ClassLoader loader, Class<?> fragment, Class<?> preference,
                                     Class<?> checkbox, Class<?> group) {
        try {
            Class<?> holder = loader.loadClass("androidx.preference.g");
            Class<?> builder = loader.loadClass("miuix.appcompat.app.u$a");
            Method remove = method(group, "u", preference);
            hook(method(checkbox, "onBindViewHolder", holder)).intercept(chain -> {
                Object result = chain.proceed();
                Object row = chain.getThisObject();
                try {
                    View view = (View) holder.getField("itemView").get(chain.getArg(0));
                    WeakReference<Object> owner = customFragments.get(row);
                    int tag = 0x4d44454c;
                    if (owner != null) {
                        view.setTag(tag, true);
                        view.setOnLongClickListener(clicked -> {
                            Object target = owner.get();
                            if (target == null) return false;
                            try {
                                if (!Boolean.TRUE.equals(method(checkbox, "isScanning").invoke(row))
                                        && !pendingDeletes.containsKey(row))
                                    confirmDelete(target, row, fragment, preference, checkbox, builder, remove);
                            } catch (Throwable e) { failure("confirm app deletion", e); }
                            return true;
                        });
                    } else if (Boolean.TRUE.equals(view.getTag(tag))) {
                        view.setOnLongClickListener(null);
                        view.setTag(tag, null);
                    }
                } catch (Throwable e) { failure("bind app deletion", e); }
                return result;
            });
            Class<?> callback = loader.loadClass("com.xiaomi.dist.notification.setting.SubscriptionAppFragment$UnSubscribeCallback");
            hook(method(callback, "lambda$subscribeResult$0", int.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object row = ((WeakReference<?>) field(chain.getThisObject(), "targetPreference")).get();
                    WeakReference<Object> owner = pendingDeletes.remove(row);
                    if (owner != null && Integer.valueOf(1).equals(chain.getArg(0)) && owner.get() != null) {
                        deleteApp(owner.get(), row, fragment, preference, remove);
                    }
                } catch (Throwable e) { failure("unsubscribe deletion callback", e); }
                return result;
            });
            Log.i(TAG, "app deletion hooks installed");
        } catch (Throwable e) { failure("app deletion hooks", e); }
    }

    private void confirmDelete(Object target, Object row, Class<?> fragment, Class<?> preference,
                               Class<?> checkbox, Class<?> builder, Method remove) throws Exception {
        Context context = (Context) method(fragment, "getContext").invoke(target);
        if (context == null) return;
        String key = (String) method(preference, "getKey").invoke(row);
        String packageName = (String) ((Map<?, ?>) field(target, "mSubscriptionCheckBoxMap")).get(key);
        RelayAppStore.App app = apps.get(packageName);
        if (app == null || !key.startsWith("check_box_pref_key_app_maple_")) return;
        Dialog previous = (Dialog) field(target, "mDialog");
        if (previous != null && previous.isShowing()) previous.dismiss();
        Object dialogBuilder = builder.getConstructor(Context.class).newInstance(context);
        method(builder, "B", CharSequence.class).invoke(dialogBuilder, "删除应用");
        method(builder, "o", CharSequence.class).invoke(dialogBuilder,
                "确定删除“" + app.label + "”？删除后将取消该应用的通知流转。");
        method(builder, "r", CharSequence.class, DialogInterface.OnClickListener.class).invoke(dialogBuilder,
                "取消", (DialogInterface.OnClickListener) (dialog, which) -> dialog.dismiss());
        method(builder, "x", CharSequence.class, DialogInterface.OnClickListener.class).invoke(dialogBuilder,
                "删除", (DialogInterface.OnClickListener) (dialog, which) -> {
                    try {
                        if (Boolean.TRUE.equals(method(checkbox, "isScanning").invoke(row))) return;
                        if (Boolean.TRUE.equals(method(checkbox, "isChecked").invoke(row))) {
                            pendingDeletes.put(row, new WeakReference<>(target));
                            method(fragment, "onPreferenceChange", preference, Object.class).invoke(target, row, false);
                            if (!Boolean.TRUE.equals(method(checkbox, "isScanning").invoke(row))) pendingDeletes.remove(row);
                        } else deleteApp(target, row, fragment, preference, remove);
                    } catch (Throwable e) {
                        pendingDeletes.remove(row);
                        failure("delete app", e);
                        try { method(fragment, "showShortToast", String.class).invoke(target, "删除失败，请重试"); }
                        catch (Throwable ignored) { }
                    }
                });
        Dialog dialog = (Dialog) method(builder, "a").invoke(dialogBuilder);
        Field dialogField = fragment.getDeclaredField("mDialog");
        dialogField.setAccessible(true);
        dialogField.set(target, dialog);
        dialog.show();
        method(fragment, "adjustDialogWindowForEmbeddedDisplay").invoke(target);
    }

    private void deleteApp(Object target, Object row, Class<?> fragment, Class<?> preference,
                           Method remove) throws Exception {
        Context context = (Context) method(preference, "getContext").invoke(row);
        String key = (String) method(preference, "getKey").invoke(row);
        Map<?, ?> mappings = (Map<?, ?>) field(target, "mSubscriptionCheckBoxMap");
        String packageName = (String) mappings.get(key);
        if (!RelayAppStore.validPackage(packageName)) return;
        context.getContentResolver().delete(RelayAppStore.URI.buildUpon().appendPath(packageName).build(), null, null);
        Field localField = fragment.getDeclaredField("mLocalPackNameList");
        localField.setAccessible(true);
        List<?> local = (List<?>) localField.get(target);
        if (local != null) {
            List<?> remaining = new ArrayList<>(local);
            remaining.remove(packageName);
            localField.set(target, remaining);
        }
        Object parent = method(preference, "getParent").invoke(row);
        if (parent != null) remove.invoke(parent, row);
        mappings.remove(key);
        customFragments.remove(row);
        reloadAppCatalog(context);
        Log.i(TAG, "custom app deleted");
    }

    private void installStatusBarIconHook(Context context, ClassLoader loader) {
        try {
            Class<?> manager = loader.loadClass("com.android.systemui.statusbar.notification.icon.IconManager");
            Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
            hook(method(manager, "getIconDescriptor", entry, boolean.class)).intercept(chain -> {
                Object descriptor = chain.proceed();
                try {
                    StatusBarNotification sbn = (StatusBarNotification) field(chain.getArg(0), "mSbn");
                    Notification notification = sbn.getNotification();
                    if (descriptor != null && TARGET.equals(sbn.getOpPkg())
                            && "hyperOs_msg".equals(notification.extras.getString("hyperOs.category"))) {
                        String sourceApp = notification.extras.getString("hyperOs.packageName");
                        String key = sbn.getKey();
                        if (sourceApp != null && !sourceApp.isBlank()) statusBarSourceApps.put(key, sourceApp);
                        else statusBarSourceApps.remove(key);
                        Icon icon = notification.getLargeIcon();
                        if (icon == null) {
                            Bitmap bitmap = notification.extras.getParcelable("hyperOs.msgProfile", Bitmap.class);
                            if (bitmap != null && !bitmap.isRecycled()) icon = Icon.createWithBitmap(bitmap);
                        }
                        if (icon != null) {
                            // Change SystemUI's icon descriptor, preserving the notification card's own small icon.
                            Field iconField = descriptor.getClass().getDeclaredField("icon");
                            iconField.setAccessible(true);
                            iconField.set(descriptor, roundedStatusBarIcon(context, icon));
                            Log.i(TAG, "relay status bar icon applied");
                        }
                    } else statusBarSourceApps.remove(sbn.getKey());
                } catch (Throwable e) { failure("status bar icon", e); }
                return descriptor;
            });
            Log.i(TAG, "relay status bar icon hook installed");
        } catch (Throwable e) { failure("status bar icon hook", e); }
    }

    private void installStatusBarAppGrouping(ClassLoader loader) {
        try {
            Class<?> model = loader.loadClass("com.android.systemui.statusbar.notification.icon.domain.interactor.ActiveNotificationIconModel");
            RelayStatusBarIcons grouping = new RelayStatusBarIcons(model);
            Class<?> transform = loader.loadClass("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel$icons$1");
            hook(method(transform, "invoke", Object.class, Object.class, Object.class)).intercept(chain -> {
                Set<?> original = (Set<?>) chain.getArg(0);
                Set<?> icons;
                try { icons = grouping.withSourceApps(original, statusBarSourceApps); }
                catch (Throwable e) {
                    failure("status bar app grouping", e);
                    return chain.proceed();
                }
                return icons == original ? chain.proceed()
                        : chain.proceed(new Object[]{icons, chain.getArg(1), chain.getArg(2)});
            });
            Log.i(TAG, "relay status bar app grouping hook installed");
        } catch (Throwable e) { failure("status bar app grouping hook", e); }
    }

    private void installGroupSummaryIconHook(ClassLoader loader) {
        try {
            Class<?> images = loader.loadClass("com.android.systemui.statusbar.notification.utils.NotifImageUtil");
            Class<?> expanded = loader.loadClass("com.android.systemui.statusbar.notification.ExpandedNotification");
            Method badge = method(loader.loadClass("miui.securityspace.XSpaceUserHandle"), "getXSpaceIcon",
                    Context.class, Drawable.class, android.os.UserHandle.class);
            hook(method(images, "applyAppIconAllowCustom", Context.class, expanded, ImageView.class, boolean.class))
                    .intercept(chain -> {
                Object result = chain.proceed();
                try {
                    StatusBarNotification sbn = (StatusBarNotification) chain.getArg(1);
                    ImageView view = (ImageView) chain.getArg(2);
                    if (sbn == null || view == null || !TARGET.equals(sbn.getOpPkg())) return result;
                    Notification notification = sbn.getNotification();
                    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) == 0
                            || !"hyperOs_msg".equals(notification.extras.getString("hyperOs.category"))) return result;
                    // The collapsed group header reloads its app icon separately from status-bar descriptors.
                    Bitmap bitmap = notification.extras.getParcelable("hyperOs.msgProfile", Bitmap.class);
                    Icon icon = bitmap != null && !bitmap.isRecycled()
                            ? Icon.createWithBitmap(bitmap) : notification.getLargeIcon();
                    if (icon != null) {
                        Context context = (Context) chain.getArg(0);
                        Drawable drawable = roundedStatusBarIcon(context, icon).loadDrawable(context);
                        if (drawable != null) {
                            if (Boolean.TRUE.equals(chain.getArg(3))) {
                                drawable = (Drawable) badge.invoke(null, context, drawable, sbn.getUser());
                            }
                            view.setImageDrawable(drawable);
                            Log.i(TAG, "relay group summary icon applied");
                        }
                    }
                } catch (Throwable e) { failure("group summary icon", e); }
                return result;
            });
            Log.i(TAG, "relay group summary icon hook installed");
        } catch (Throwable e) { failure("group summary icon hook", e); }
    }

    private static Icon roundedStatusBarIcon(Context context, Icon icon) {
        Drawable drawable = icon.loadDrawable(context);
        if (drawable == null) return icon;
        int size = 128;
        Bitmap source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        float scale = width > 0 && height > 0 ? (float) size / Math.max(width, height) : 1f;
        int drawnWidth = width > 0 ? Math.max(1, Math.round(width * scale)) : size;
        int drawnHeight = height > 0 ? Math.max(1, Math.round(height * scale)) : size;
        int left = (size - drawnWidth) / 2;
        int top = (size - drawnHeight) / 2;
        drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight);
        drawable.draw(new Canvas(source));
        Bitmap rounded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        // Stock app icons are shaped by the system; relay large bitmaps bypass that step.
        float radius = size * 0.22f;
        new Canvas(rounded).drawRoundRect(0, 0, size, size, radius, radius, paint);
        return Icon.createWithBitmap(rounded);
    }

    private void installIconHooks(ClassLoader loader, Class<?> controller) {
        try {
            hook(method(controller, "createNotificationExtras", Bitmap.class, boolean.class, String.class)).intercept(chain -> {
                Bundle extras = (Bundle) chain.proceed();
                RelayAppStore.App app = apps.get(chain.getArg(2));
                if (app != null) {
                    extras.putParcelable("hyperOs.msgProfile", app.icon);
                }
                return extras;
            });
            hook(method(controller, "createNotificationBuilder", boolean.class, String.class,
                    String.class, String.class, String.class, String.class, PendingIntent.class)).intercept(chain -> {
                Object builder = chain.proceed();
                RelayAppStore.App app = apps.get(chain.getArg(1));
                if (app != null) {
                    method(builder.getClass(), "r", Bitmap.class).invoke(builder, app.icon);
                    Log.i(TAG, "custom app notification icon applied");
                }
                return builder;
            });
            Log.i(TAG, "XHS icon hooks installed");
        } catch (Throwable e) { failure("icon hook", e); }
    }

    private void installDetailIconHook(ClassLoader loader) {
        try {
            Class<?> detail = loader.loadClass("com.xiaomi.dist.notification.handler.NotificationDetailDialog");
            hook(method(detail, "initDialogView", View.class)).intercept(chain -> {
                Object result = chain.proceed();
                Activity activity = (Activity) chain.getThisObject();
                RelayAppStore.App app = apps.get(activity.getIntent().getStringExtra("content_appId"));
                if (app != null) {
                    int id = activity.getResources().getIdentifier("imageView", "id", TARGET);
                    ImageView view = ((View) chain.getArg(0)).findViewById(id);
                    if (view != null) view.setImageBitmap(app.icon);
                    Log.i(TAG, "custom app detail icon applied");
                }
                return result;
            });
            Log.i(TAG, "XHS detail icon hook installed");
        } catch (Throwable e) { failure("detail icon hook", e); }
    }

    private static Bitmap getXhsIcon(Context context) throws Exception {
        if (xhsIcon == null) {
            Context module = context.createPackageContext("com.zephyr.notificationrelay", 0);
            int id = module.getResources().getIdentifier("xhs_icon", "drawable", "com.zephyr.notificationrelay");
            xhsIcon = BitmapFactory.decodeResource(module.getResources(), id);
            if (xhsIcon == null) throw new IllegalStateException("XHS icon unavailable");
        }
        return xhsIcon;
    }

    private void installSubscriptionDiagnostics(ClassLoader loader) {
        try {
            Class<?> controller = loader.loadClass("com.xiaomi.dist.notification.trans.client.sub.SubscribeController");
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals("lambda$subscribe$1")) {
                    hook(m).intercept(chain -> {
                        Log.i(TAG, "remote subscribe code=" + chain.getArg(3));
                        return chain.proceed();
                    });
                }
            }
            Class<?> callback = loader.loadClass("com.xiaomi.dist.notification.setting.SubscriptionAppFragment$SubscribeCallback");
            Class<?> fragment = loader.loadClass("com.xiaomi.dist.notification.setting.SubscriptionAppFragment");
            hook(method(fragment, "showOfflineConfirmDialog")).intercept(chain -> {
                Log.i(TAG, "subscribe blocked by page offline flag=" + field(chain.getThisObject(), "isOnline"));
                return chain.proceed();
            });
            hook(method(callback, "subscribeResult", int.class, java.util.List.class)).intercept(chain -> {
                Log.i(TAG, "UI subscribe code=" + chain.getArg(0));
                return chain.proceed();
            });
            Class<?> cloudCallback = loader.loadClass("com.xiaomi.dist.notification.trans.client.sub.SubscribeController$2");
            hook(method(cloudCallback, "onFailure", int.class, String.class)).intercept(chain -> {
                Log.i(TAG, "cloud subscribe failure code=" + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, "subscription diagnostics installed");
        } catch (Throwable e) { failure("subscription diagnostics", e); }
    }

    private void installSettingsEntry(ClassLoader loader) {
        try {
            Class<?> entry = loader.loadClass("com.miui.circulate.world.ui.connectivitysettings.i$b0");
            Class<?> helper = loader.loadClass("com.miui.circulate.world.ui.connectivitysettings.ConnectivityHelper");
            hook(method(entry, "a")).intercept(chain -> {
                Boolean previous = NOTIFICATION_ENTRY.get();
                NOTIFICATION_ENTRY.set(true);
                try { return chain.proceed(); }
                finally {
                    if (previous == null) NOTIFICATION_ENTRY.remove();
                    else NOTIFICATION_ENTRY.set(previous);
                }
            });
            // Change only this click's routing; other tablet checks retain stock results.
            hook(method(helper, "z", Context.class)).intercept(chain ->
                    Boolean.TRUE.equals(NOTIFICATION_ENTRY.get()) ? false : chain.proceed());
            Log.i(TAG, "system notification settings entry redirected");
        } catch (Throwable e) { failure("settings entry", e); }
    }

    private boolean addPreferences(Object fragment, Class<?> preference, Class<?> checkbox,
                                      Method add, Method setup) throws Exception {
        Method find = method(fragment.getClass(), "findPreference", CharSequence.class);
        Object device = field(fragment, "mDeviceInfo");
        // Add only to receiving a Xiaomi phone; watch subscriptions use a different direction.
        if (device == null || !Integer.valueOf(1).equals(method(device.getClass(), "getDeviceType").invoke(device))) return false;
        Context context = (Context) method(fragment.getClass(), "getContext").invoke(fragment);
        reloadAppCatalog(context);
        Object anchor = find.invoke(fragment, "check_box_pref_key_app_wework");
        Object parent = method(preference, "getParent").invoke(anchor);
        int order = (Integer) method(preference, "getOrder").invoke(anchor);
        Drawable reference = (Drawable) method(preference, "getIcon").invoke(anchor);
        Map<?, ?> mappings = (Map<?, ?>) field(fragment, "mSubscriptionCheckBoxMap");
        for (Object key : new ArrayList<>(mappings.keySet())) {
            if (key.toString().startsWith("check_box_pref_key_app_maple_") && !apps.containsKey(mappings.get(key))) {
                Object stale = find.invoke(fragment, key);
                if (stale != null) {
                    method(parent.getClass(), "u", preference).invoke(parent, stale);
                    customFragments.remove(stale);
                }
                mappings.remove(key);
            }
        }
        boolean changed = false;
        for (RelayAppStore.App app : apps.values()) {
            Object row = null;
            for (Map.Entry<?, ?> mapping : mappings.entrySet()) {
                if (app.packageName.equals(mapping.getValue())) {
                    row = find.invoke(fragment, mapping.getKey());
                    break;
                }
            }
            boolean added = row == null;
            if (added) {
                row = checkbox.getConstructor(Context.class).newInstance(context);
                String key = XHS.equals(app.packageName) ? KEY : "check_box_pref_key_app_maple_" + app.packageName;
                method(preference, "setKey", String.class).invoke(row, key);
                method(preference, "setTitle", CharSequence.class).invoke(row, app.label);
            }
            String rowKey = (String) method(preference, "getKey").invoke(row);
            if (rowKey != null && rowKey.startsWith("check_box_pref_key_app_maple_")) {
                customFragments.put(row, new WeakReference<>(fragment));
                if (order != Integer.MAX_VALUE) order++;
                method(preference, "setOrder", int.class).invoke(row, order);
            }
            Drawable drawable = new BitmapDrawable(context.getResources(), app.icon);
            int width = reference == null ? 96 : Math.max(1, reference.getIntrinsicWidth());
            int height = reference == null ? 96 : Math.max(1, reference.getIntrinsicHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(bitmap));
            method(preference, "setIcon", Drawable.class).invoke(row, new BitmapDrawable(context.getResources(), bitmap));
            if (added) {
                if (!Boolean.TRUE.equals(add.invoke(parent, row))) throw new IllegalStateException("Add preference failed");
                // Reuse the stock callback so check state reflects the remote subscription result.
                setup.invoke(fragment, row, app.packageName);
                List<?> subscribed = (List<?>) field(fragment, "mLocalPackNameList");
                method(checkbox, "setChecked", boolean.class).invoke(row, subscribed != null && subscribed.contains(app.packageName));
                changed = true;
            }
        }
        Object comment = find.invoke(fragment, "pref_key_list_comment");
        if (comment != null && order != Integer.MAX_VALUE) {
            // The stock footer must follow every custom app, including apps added on resume.
            method(preference, "setOrder", int.class).invoke(comment, order + 1);
            // The empty phone footer otherwise takes the group's last-row corner state.
            method(preference, "setVisible", boolean.class).invoke(comment, false);
        }
        return changed;
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void failure(String stage, Throwable error) {
        // Keep message bodies and device identifiers out of module diagnostics.
        Throwable cause = error.getCause() == null ? error : error.getCause();
        Log.e(TAG, stage + " failed: " + cause.getClass().getSimpleName());
    }
}
