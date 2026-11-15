// CarHotspot: restart the phone's Wi-Fi hotspot on a fixed, public-looking
// subnet (phone 9.9.0.1, car 9.9.0.2) so the Tesla browser, which refuses
// private addresses, can reach the phone directly.
//
// Runs with shell (adb) privileges via app_process; no app, no root:
//   adb push carhotspot.dex /data/local/tmp/
//   adb shell CLASSPATH=/data/local/tmp/carhotspot.dex app_process / CarHotspot start
//   adb shell CLASSPATH=/data/local/tmp/carhotspot.dex app_process / CarHotspot normal
//   adb shell CLASSPATH=/data/local/tmp/carhotspot.dex app_process / CarHotspot stop
//
// Uses the system TetheringManager API (TetheringRequest.setStaticIpv4Addresses,
// Android 11+) through reflection, since it is not in the public SDK.

import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class CarHotspot {
    static final int TETHERING_WIFI = 0;

    /** Minimal context that identifies the caller as the shell package. */
    static class ShellContext extends ContextWrapper {
        ShellContext() { super(null); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public String getAttributionTag() { return null; }
        @Override public Context getApplicationContext() { return this; }
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "start";
        String local = args.length > 1 ? args[1] : "9.9.0.1/24";
        String client = args.length > 2 ? args[2] : "9.9.0.2/24";

        Object tm = tetheringManager();
        Method stop = tm.getClass().getMethod("stopTethering", int.class);

        switch (cmd) {
            case "stop":
                stop.invoke(tm, TETHERING_WIFI);
                System.out.println("hotspot stopped");
                break;
            case "normal":
                stop.invoke(tm, TETHERING_WIFI);
                Thread.sleep(1500);
                start(tm, null, null);
                break;
            case "start":
                stop.invoke(tm, TETHERING_WIFI);
                Thread.sleep(1500);
                start(tm, local, client);
                break;
            default:
                System.err.println("usage: CarHotspot start [local/prefix client/prefix] | normal | stop");
                System.exit(2);
        }
        System.exit(0);
    }

    static Object tetheringManager() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "tethering");
        if (binder == null) throw new IllegalStateException("tethering service not found");
        Class<?> tmClass = Class.forName("android.net.TetheringManager");
        Constructor<?> c = tmClass.getDeclaredConstructor(Context.class, Supplier.class);
        c.setAccessible(true);
        Supplier<IBinder> supplier = () -> binder;
        return c.newInstance(new ShellContext(), supplier);
    }

    static void start(Object tm, String local, String client) throws Exception {
        Class<?> builderClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Object b = builderClass.getConstructor(int.class).newInstance(TETHERING_WIFI);
        if (local != null) {
            Class<?> la = Class.forName("android.net.LinkAddress");
            Constructor<?> lac = la.getDeclaredConstructor(String.class);
            lac.setAccessible(true);
            builderClass.getMethod("setStaticIpv4Addresses", la, la)
                    .invoke(b, lac.newInstance(local), lac.newInstance(client));
        }
        builderClass.getMethod("setExemptFromEntitlementCheck", boolean.class).invoke(b, true);
        builderClass.getMethod("setShouldShowEntitlementUi", boolean.class).invoke(b, false);
        Object request = builderClass.getMethod("build").invoke(b);

        Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        CountDownLatch done = new CountDownLatch(1);
        String[] result = {"timeout"};
        Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass}, (proxy, m, a) -> {
            if (m.getName().equals("onTetheringStarted")) {
                result[0] = "ok";
                done.countDown();
            } else if (m.getName().equals("onTetheringFailed")) {
                result[0] = "failed, error " + a[0];
                done.countDown();
            }
            return null;
        });
        Executor direct = Runnable::run;
        tm.getClass().getMethod("startTethering", request.getClass(), Executor.class, cbClass)
                .invoke(tm, request, direct, cb);
        done.await(15, TimeUnit.SECONDS);
        System.out.println("hotspot " + (local != null ? "on " + local + ", car " + client : "on (normal)") + ": " + result[0]);
        if (!result[0].equals("ok")) System.exit(1);
    }
}
