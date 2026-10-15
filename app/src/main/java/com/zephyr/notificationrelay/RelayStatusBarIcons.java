package com.zephyr.notificationrelay;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class RelayStatusBarIcons {
    private final Constructor<?> copy;
    private final Field key, group, shelf, statusBar, aod, ambient, focused;

    RelayStatusBarIcons(Class<?> model) throws ReflectiveOperationException {
        key = model.getField("notifKey");
        group = model.getField("groupKey");
        shelf = model.getField("shelfIcon");
        statusBar = model.getField("statusBarIcon");
        aod = model.getField("aodIcon");
        ambient = model.getField("isAmbient");
        focused = model.getField("isFocusNotification");
        copy = model.getConstructor(String.class, String.class, shelf.getType(), statusBar.getType(),
                aod.getType(), boolean.class, String.class, boolean.class);
    }

    Set<?> withSourceApps(Set<?> original, Map<String, String> sourceApps) throws ReflectiveOperationException {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        boolean changed = false;
        for (Object icon : original) {
            String sourceApp = sourceApps.get(key.get(icon));
            // Focus notifications keep the stock package used to coordinate with the dynamic island.
            if (sourceApp == null || sourceApp.isBlank() || focused.getBoolean(icon)) {
                result.add(icon);
                continue;
            }
            // Copy only the status-bar input: shared notification models and their hash keys stay intact.
            result.add(copy.newInstance(key.get(icon), group.get(icon), shelf.get(icon), statusBar.get(icon),
                    aod.get(icon), ambient.getBoolean(icon), "com.milink.service:relay:" + sourceApp, false));
            changed = true;
        }
        return changed ? result : original;
    }
}
