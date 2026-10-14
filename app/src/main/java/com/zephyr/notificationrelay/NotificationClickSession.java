package com.zephyr.notificationrelay;

/**
 * Correlates MiLink's two synchronous halves of one notification click.
 *
 * <p>The state is thread-confined because Xiaomi runs both calls sequentially on the same
 * single-thread executor. Consumption is one-shot even on a mismatch, and the short expiry keeps
 * a vendor control-flow change from affecting a later, unrelated publish on the pooled thread.</p>
 *
 * Adapted from HyperConnectToolkit (Apache-2.0), copyright 2026 silverpoetry.
 * Modified package and inlined input validation for NotificationRelay.
 */
final class NotificationClickSession {
    static final long MAX_AGE_MILLIS = 5_000L;

    private final ThreadLocal<PendingClick> pending = new ThreadLocal<>();

    void begin(String deviceId, String packageName, long nowMillis) {
        if (deviceId == null || deviceId.isEmpty() || packageName == null || packageName.isEmpty()) {
            pending.remove();
            return;
        }
        pending.set(new PendingClick(deviceId, packageName, nowMillis));
    }

    boolean consumeMatching(String deviceId, String packageName, long nowMillis) {
        PendingClick click = pending.get();
        pending.remove();
        if (click == null) {
            return false;
        }
        long ageMillis = nowMillis - click.createdAtMillis;
        return ageMillis >= 0
                && ageMillis <= MAX_AGE_MILLIS
                && click.deviceId.equals(deviceId)
                && click.packageName.equals(packageName);
    }

    void clear() {
        pending.remove();
    }

    private static final class PendingClick {
        private final String deviceId;
        private final String packageName;
        private final long createdAtMillis;

        PendingClick(String deviceId, String packageName, long createdAtMillis) {
            this.deviceId = deviceId;
            this.packageName = packageName;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
