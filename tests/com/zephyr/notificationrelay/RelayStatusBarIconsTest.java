package com.zephyr.notificationrelay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RelayStatusBarIconsTest {
    public static final class Model {
        public final String notifKey, groupKey, packageName;
        public final Object shelfIcon, statusBarIcon, aodIcon;
        public final boolean isAmbient, isFocusNotification;

        public Model(String key, String group, Object shelf, Object statusBar, Object aod,
                     boolean ambient, String app, boolean focus) {
            notifKey = key;
            groupKey = group;
            shelfIcon = shelf;
            statusBarIcon = statusBar;
            aodIcon = aod;
            isAmbient = ambient;
            packageName = app;
            isFocusNotification = focus;
        }
    }

    public static void main(String[] args) throws Exception {
        RelayStatusBarIcons grouping = new RelayStatusBarIcons(Model.class);
        Object image = new Object();
        Model first = new Model("first", "group-a", image, image, image, true, "com.milink.service", false);
        Model duplicate = new Model("duplicate", "group-b", image, image, image, false, "com.milink.service", false);
        Model second = new Model("second", "group-c", image, image, image, false, "com.milink.service", false);
        Model local = new Model("local", null, image, image, image, false, "app.a", false);
        Model focus = new Model("focus", "island", image, image, image, false, "com.milink.service", true);
        Set<Model> original = new LinkedHashSet<>(List.of(first, duplicate, second, local, focus));
        Map<String, String> apps = Map.of("first", "app.a", "duplicate", "app.a", "second", "app.b", "focus", "app.c");

        expect(distinctPackages(original).size() == 2, "stock merges all relay apps");
        Set<?> rewritten = grouping.withSourceApps(original, apps);
        List<?> icons = new ArrayList<>(rewritten);
        expect(distinctPackages(rewritten).size() == 4, "two relay apps plus local app and stock focus icon");
        expect(rewritten.size() == original.size(), "no notification models removed before stock filtering");
        Model copied = (Model) icons.get(0);
        expect(copied != first, "relay model is copied");
        expect(copied.packageName.equals(((Model) icons.get(1)).packageName), "same app deduplicates across groups/devices");
        expect(!copied.packageName.equals(((Model) icons.get(2)).packageName), "different relay apps remain distinct");
        expect(!copied.packageName.equals(local.packageName), "relay and local app identities stay separate");
        expect(copied.notifKey.equals(first.notifKey) && copied.groupKey.equals(first.groupKey), "notification and group keys preserved");
        expect(copied.shelfIcon == image && copied.statusBarIcon == image && copied.aodIcon == image && copied.isAmbient, "images and visibility state preserved");
        expect(icons.get(3) == local && icons.get(4) == focus, "local and focus models untouched");
        expect(first.packageName.equals("com.milink.service") && original.contains(first), "original model and set intact");
        expect(grouping.withSourceApps(original, Map.of()) == original, "no mappings uses stock set");
        expect(grouping.withSourceApps(original, Map.of("first", " ")) == original, "invalid source uses stock set");
        Set<Model> empty = new LinkedHashSet<>();
        expect(grouping.withSourceApps(empty, apps) == empty, "cleared notifications leave no icons");
        System.out.println("RelayStatusBarIcons tests passed");
    }

    private static Set<String> distinctPackages(Set<?> icons) {
        Set<String> packages = new LinkedHashSet<>();
        for (Object icon : icons) packages.add(((Model) icon).packageName);
        return packages;
    }

    private static void expect(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
    }
}
