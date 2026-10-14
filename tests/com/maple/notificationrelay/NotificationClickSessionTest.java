package com.maple.notificationrelay;

public final class NotificationClickSessionTest {
    public static void main(String[] args) throws Exception {
        NotificationClickSession session = new NotificationClickSession();
        expect(!session.consumeMatching("device-a", "app.a", 100), "no pending click");
        session.begin("device-a", "app.a", 100);
        expect(session.consumeMatching("device-a", "app.a", 100), "immediate match");
        expect(!session.consumeMatching("device-a", "app.a", 101), "one-shot consumption");
        session.begin("device-a", "app.a", 100);
        expect(session.consumeMatching("device-a", "app.a", 5100), "inclusive timeout boundary");
        session.begin("device-a", "app.a", 100);
        expect(!session.consumeMatching("device-a", "app.a", 5101), "expired click");
        session.begin("device-a", "app.a", 100);
        expect(!session.consumeMatching("device-a", "app.a", 99), "negative elapsed time");
        session.begin("device-a", "app.a", 100);
        expect(!session.consumeMatching("device-b", "app.a", 101), "different device");
        expect(!session.consumeMatching("device-a", "app.a", 102), "mismatch clears pending click");
        session.begin("device-a", "app.a", 100);
        expect(!session.consumeMatching("device-a", "app.b", 101), "different app");
        session.begin("device-a", "app.a", 100);
        session.begin(null, "app.a", 100);
        expect(!session.consumeMatching("device-a", "app.a", 101), "invalid click clears state");
        session.begin("device-a", "app.a", 100);
        session.clear();
        expect(!session.consumeMatching("device-a", "app.a", 101), "explicit clearing");
        session.begin("device-a", "app.a", 100);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                expect(!session.consumeMatching("device-a", "app.a", 101), "another thread cannot consume click");
                session.begin("device-b", "app.b", 101);
                expect(session.consumeMatching("device-b", "app.b", 102), "worker click isolated");
            } catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        worker.join();
        if (error.get() != null) throw new AssertionError(error.get());
        expect(session.consumeMatching("device-a", "app.a", 103), "original thread click retained");
        System.out.println("NotificationClickSession tests passed");
    }

    private static void expect(boolean result, String description) {
        if (!result) throw new AssertionError(description);
    }
}
