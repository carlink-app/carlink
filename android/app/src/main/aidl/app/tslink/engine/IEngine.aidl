package app.tslink.engine;

import android.app.PendingIntent;
import app.tslink.engine.IEngineClient;

// Control interface of the engine process (runs with shell rights via Shizuku).
interface IEngine {
    // Shizuku convention: transaction code 16777114 destroys the user service.
    void destroy() = 16777114;

    // devAllowAny: accept the car page from any network (emulator testing), not just the car hotspot.
    // libPath is ignored (kept for the transaction layout): the engine finds its own library.
    String start(String code, int port, String libPath, String devAllow) = 1;
    String status() = 2;
    void stop() = 3;
    String carHotspot(boolean on) = 4;

    // Link from the app process: notifications and app icons go to the car,
    // "open" requests come back through the client. Only the tslink app can
    // reach this binder (Shizuku hands it to the binding app only).
    void setClient(IEngineClient client) = 5;
    oneway void fromApp(String json) = 6;

    String hotspotOff() = 7;

    // User's explicit choice in the app: grant/revoke tslink's notification access.
    String notificationAccess(boolean on) = 8;

    // Open a notification's content intent on a display. Sent from here (shell
    // may start activities from the background; the app's listener may not).
    oneway void openNotification(in PendingIntent intent, String pkg, int displayId) = 9;

    // Recent engine log lines (logcat, tslink tags), for the app's log screen.
    String logs() = 10;

    // Pair a car from the one-time secret in its QR code (see EngineService).
    String startPairing(String secret) = 11;
    // "", "waiting", "paired <car address>" or "expired".
    String pairingResult() = 12;

    // Device check without a car (JSON, see SelfTest). Takes ~15 s; not while a car is connected.
    String selfTest() = 13;
}
