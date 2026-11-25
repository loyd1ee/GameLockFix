package com.rr.gamelockfix;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GameSpace (io.chaldeaprjkt.gamespace) writes Settings.Secure "ax_gaming_gesture_lock" (0/1)
 * when you tap the lock button, but nothing in this ROM's SystemUI reads it.
 * This module runs inside SystemUI, watches that setting and turns the standard
 * status bar disable flags on/off, so the shade and quick settings can't be pulled down.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "GameLockFix";
    private static final String KEY = "ax_gaming_gesture_lock";

    // StatusBarManager flags
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE2_QUICK_SETTINGS = 1;
    private static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;

    private static final int USER_CURRENT = -2;
    private static final int USER_ALL = -1;

    private static boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.android.systemui".equals(lp.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application app = (Application) param.thisObject;
                if (!"com.android.systemui".equals(app.getPackageName())) return;
                synchronized (MainHook.class) {
                    if (installed) return;
                    installed = true;
                }
                try {
                    install(app);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": install failed: " + t);
                }
            }
        });
    }

    private void install(final Context ctx) {
        final ContentResolver cr = ctx.getContentResolver();
        final Uri uri = Settings.Secure.getUriFor(KEY);
        final Handler handler = new Handler(Looper.getMainLooper());

        // A stale "1" left by a crashed game session would lock the status bar forever.
        // GameSpace re-applies the lock every time a game starts, so reset on SystemUI start.
        try { Settings.Secure.putInt(cr, KEY, 0); } catch (Throwable ignored) { }
        apply(ctx, false);

        ContentObserver obs = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                apply(ctx, isLocked(cr));
            }
        };
        try {
            // registerContentObserver(Uri, boolean, ContentObserver, int userHandle) is hidden
            cr.getClass().getMethod("registerContentObserver",
                    Uri.class, boolean.class, ContentObserver.class, int.class)
                    .invoke(cr, uri, false, obs, USER_ALL);
        } catch (Throwable t) {
            cr.registerContentObserver(uri, false, obs);
        }
        XposedBridge.log(TAG + ": watching " + KEY);
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
            if (sbm == null) return;
            XposedHelpers.callMethod(sbm, "disable", locked ? DISABLE_EXPAND : 0);
            XposedHelpers.callMethod(sbm, "disable2",
                    locked ? (DISABLE2_NOTIFICATION_SHADE | DISABLE2_QUICK_SETTINGS) : 0);
            XposedBridge.log(TAG + ": status bar " + (locked ? "LOCKED" : "unlocked"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": apply failed: " + t);
        }
    }
}
