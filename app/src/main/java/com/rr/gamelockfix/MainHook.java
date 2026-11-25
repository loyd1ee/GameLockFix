package com.rr.gamelockfix;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "GameLockFix";
    private static final String KEY = "ax_gaming_gesture_lock";
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE2_QUICK_SETTINGS = 1;
    private static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;
    private static final int USER_CURRENT = -2;
    private static final int USER_ALL = -1;
    private static boolean installed;

    private static void log(String m) {
        Log.i(TAG, m);
        XposedBridge.log(TAG + ": " + m);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        log("loaded in " + lp.packageName + " / " + lp.processName);
        if (!"com.android.systemui".equals(lp.packageName)) return;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object cur = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (cur != null) {
                installOnce((Application) cur);
                return;
            }
        } catch (Throwable t) {
            log("currentApplication failed: " + t);
        }
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application app = (Application) param.thisObject;
                if ("com.android.systemui".equals(app.getPackageName())) installOnce(app);
            }
        });
    }

    private void installOnce(Application app) {
        synchronized (MainHook.class) {
            if (installed) return;
            installed = true;
        }
        try {
            install(app);
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private void install(final Context ctx) {
        final ContentResolver cr = ctx.getContentResolver();
        final Uri uri = Settings.Secure.getUriFor(KEY);
        try { Settings.Secure.putInt(cr, KEY, 0); } catch (Throwable t) { log("reset failed: " + t); }
        apply(ctx, false);
        ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                boolean l = isLocked(cr);
                log("setting changed, locked=" + l);
                apply(ctx, l);
            }
        };
        try {
            cr.getClass().getMethod("registerContentObserver",
                    Uri.class, boolean.class, ContentObserver.class, int.class)
                    .invoke(cr, uri, false, obs, USER_ALL);
        } catch (Throwable t) {
            cr.registerContentObserver(uri, false, obs);
        }
        log("watching " + KEY);
    }

    private boolean isLocked(ContentResolver cr) {
        try {
            return ((Integer) XposedHelpers.callStaticMethod(Settings.Secure.class,
                    "getIntForUser", cr, KEY, 0, USER_CURRENT)) == 1;
        } catch (Throwable t) {
            return Settings.Secure.getInt(cr, KEY, 0) == 1;
        }
    }

    private void apply(Context ctx, boolean locked) {
        try {
            Object sbm = ctx.getSystemService("statusbar");
            if (sbm == null) { log("no statusbar service"); return; }
            XposedHelpers.callMethod(sbm, "disable", locked ? DISABLE_EXPAND : 0);
            XposedHelpers.callMethod(sbm, "disable2",
                    locked ? (DISABLE2_NOTIFICATION_SHADE | DISABLE2_QUICK_SETTINGS) : 0);
            log("status bar " + (locked ? "LOCKED" : "unlocked"));
        } catch (Throwable t) {
            log("apply failed: " + t);
        }
    }
}
