package com.zephyr.relayquiet;

public final class TransferStateTest {
    public static void main(String[] args) throws Exception {
        TransferState state = new TransferState();
        String app = TransferState.token("synthetic.app", 0);
        String other = TransferState.token("synthetic.other", 0);
        check(!state.enabled(app), "unknown subscription list must keep stock wake");
        java.util.HashSet<String> enabled = new java.util.HashSet<>();
        enabled.add(app);
        state.apps(enabled);
        enabled.clear();
        check(state.enabled(app) && !state.enabled(other), "only enabled apps enter wake gate; snapshot is immutable");
        state.apps(java.util.Set.of(other));
        check(!state.enabled(app) && state.enabled(other), "subscription changes replace previous list");
        state.apps(java.util.Set.of());
        check(!state.enabled(other), "switch off must restore stock wake");
        String first = TransferState.token("synthetic-key", 100);
        String update = TransferState.token("synthetic-key", 101);
        check(TransferState.valid(first) && !first.equals(update), "updates need distinct identities");
        state.screen("tablet-a", true, 0);
        check(!state.canSuppress(first, 1), "awake state alone must not suppress");
        state.sent(first, "tablet-b", 2);
        check(!state.canSuppress(first, 3), "another tablet state must not match");
        state.sent(first, "tablet-a", 4);
        check(state.canSuppress(first, 5), "successful send and awake tablet must suppress");
        state.screen("tablet-a", false, 6);
        check(!state.canSuppress(first, 7), "screen off must restore normal wake");
        state.screen("tablet-a", true, 5);
        check(!state.canSuppress(first, 8), "older awake state must not overwrite newer off state");
        state.screen("tablet-a", true, 9);
        check(state.canSuppress(first, 10), "fresh awake state must match a recent send");
        check(!state.canSuppress(update, 10), "old success must not suppress a notification update");
        check(!state.canSuppress(first, 4 + TransferState.TTL_MS), "old send must expire");
        state.sent(update, "tablet-a", 10_000);
        check(!state.canSuppress(update, 10_001), "stale screen state must restore normal wake");
        for (int i = 0; i < 300; i++) state.sent(TransferState.token("capacity", i), "tablet-a", 20_000);
        check(state.get(TransferState.token("capacity", 0), 20_001) == null, "cache must be bounded");
        check(state.get(TransferState.token("capacity", 299), 20_001) != null, "latest send must remain");
        System.out.println("TransferState tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
