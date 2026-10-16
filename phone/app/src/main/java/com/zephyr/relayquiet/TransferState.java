package com.zephyr.relayquiet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

final class TransferState {
    static final long TTL_MS = 5000;
    private record Screen(boolean awake, long time) { }
    record Sent(String target, long time) { }
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final Map<String, Sent> sent = new LinkedHashMap<>();
    private Set<String> apps = Set.of();

    synchronized void apps(Collection<String> values) { apps = Set.copyOf(values); }
    synchronized Set<String> apps() { return apps; }
    synchronized boolean enabled(String app) { return apps.contains(app); }

    synchronized void screen(String target, boolean awake, long time) {
        Screen old = screens.get(target);
        if (old == null || time >= old.time()) screens.put(target, new Screen(awake, time));
        trim(screens);
    }

    synchronized void sent(String token, String target, long time) {
        sent.put(token, new Sent(target, time));
        trim(sent);
    }

    synchronized Sent get(String token, long now) {
        Sent result = sent.get(token);
        return result != null && fresh(result.time(), now) ? result : null;
    }

    synchronized boolean canSuppress(String token, long now) {
        Sent success = get(token, now);
        Screen screen = success == null ? null : screens.get(success.target());
        return screen != null && screen.awake() && fresh(screen.time(), now);
    }

    private static boolean fresh(long time, long now) { return now >= time && now - time < TTL_MS; }

    private static void trim(Map<?, ?> map) {
        while (map.size() > 256) map.remove(map.keySet().iterator().next());
    }

    static boolean valid(String token) { return token != null && token.matches("[a-f0-9]{64}"); }

    static String token(String key, long time) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((key + "\n" + time).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
