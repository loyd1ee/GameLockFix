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
import android.view.View;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;

public class MainHook extends XposedModule {
    private static final String TAG = "GameLockFix";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String KEY = "ax_gaming_gesture_lock";
    private static final int DISABLE_EXPAND = 0x00010000 | 0x00020000 | 0x00100000 | 0x00800000;
    private static final int DISABLE2_QUICK_SETTINGS = 1 | (1 << 1);
    private static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;
    private static final int USER_CURRENT = -2;
    private static final int USER_ALL = -1;

    private static boolean installed;
    private static volatile boolean sLocked;
    private static final List<WeakReference<View>> sViews = new CopyOnWriteArrayList<>();

    private void l(String m) {
        Log.i(TAG, m);
        try { log(Log.INFO, TAG, m); } catch (Throwable ignored) { }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!SYSTEMUI.equals(param.getPackageName())) return;
        l("loaded in " + SYSTEMUI);

        ClassLoader cl = param.getClassLoader();

        try {
            Class<?> nc = cl.loadClass("com.android.systemui.navigationbar.views.NavigationBarView");
            hook(nc.getDeclaredMethod("onAttachedToWindow")).intercept(chain -> {
                Object r = chain.proceed();
                Object o = chain.getThisObject();
                if (o instanceof View) {
                    View v = (View) o;
                    sViews.add(new WeakReference<>(v));
                    setHidden(v, sLocked);
                    Object p = v.getParent();
                    if (p instanceof View
                            && p.getClass().getName().endsWith("NavigationBarFrame")) {
                        sViews.add(new WeakReference<>((View) p));
                        setHidden((View) p, sLocked);
                    }
                    l("nav bar attached");
                }
                return r;
            });
        } catch (Throwable t) {
            l("nav bar hook failed: " + t);
        }

        hookReapply(cl, "com.android.systemui.navigationbar.views.NavigationBar",
                "repositionNavigationBar");
        hookReapply(cl, "com.android.systemui.navigationbar.views.NavigationBarView",
                "onConfigurationChanged", "setSlippery");

        try {
            Class<?> c = cl.loadClass("com.android.systemui.statusbar.window.StatusBarWindowView");
            Method m = c.getDeclaredMethod("onAttachedToWindow");
            hook(m).intercept(chain -> {
                Object r = chain.proceed();
                Object o = chain.getThisObject();
                if (o instanceof View) {
                    View v = (View) o;
                    sViews.add(new WeakReference<>(v));
                    setHidden(v, sLocked);
                    l("status bar window attached");
                }
                return r;
            });
        } catch (Throwable t) {
            l("window hook failed: " + t);
        }

        try {
            Class<?> c = cl.loadClass("com.android.systemui.SystemUIApplication");
            hook(c.getDeclaredMethod("onCreate")).intercept(chain -> {
                Object r = chain.proceed();
                Object o = chain.getThisObject();
                if (o instanceof Application) installOnce((Application) o);
                return r;
            });
        } catch (Throwable t) {
            l("SystemUIApplication hook failed: " + t);
            try {
                hook(Application.class.getDeclaredMethod("onCreate")).intercept(chain -> {
                    Object r = chain.proceed();
                    Object o = chain.getThisObject();
                    if (o instanceof Application
                            && SYSTEMUI.equals(((Application) o).getPackageName())) {
                        installOnce((Application) o);
                    }
                    return r;
                });
            } catch (Throwable t2) {
                l("Application hook failed: " + t2);
            }
        }

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Application) installOnce((Application) app);
        } catch (Throwable ignored) { }
    }

    private static final java.util.Map<View, Boolean> sOrigNotTouchable =
            new java.util.WeakHashMap<View, Boolean>();

    private static void setHidden(final View v, final boolean hidden) {
        v.post(new Runnable() {
            @Override
            public void run() {
                v.setAlpha(hidden ? 0f : 1f);
                try {
                    Object lp = v.getLayoutParams();
                    if (!(lp instanceof android.view.WindowManager.LayoutParams)) return;
                    android.view.WindowManager.LayoutParams wl =
                            (android.view.WindowManager.LayoutParams) lp;
                    int flag = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    boolean orig;
                    synchronized (sOrigNotTouchable) {
                        Boolean o = sOrigNotTouchable.get(v);
                        if (o == null) {
                            o = (wl.flags & flag) != 0;
                            sOrigNotTouchable.put(v, o);
                        }
                        orig = o;
                    }
                    int newFlags = (hidden || orig) ? (wl.flags | flag) : (wl.flags & ~flag);
                    float newAlpha = hidden ? 0f : 1f;
                    if (newFlags != wl.flags || wl.alpha != newAlpha) {
                        wl.flags = newFlags;
                        wl.alpha = newAlpha;
                        ((android.view.ViewManager) v.getContext().getSystemService("window"))
                                .updateViewLayout(v, wl);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "window toggle failed: " + t);
                }
            }
        });
    }

    private static void reapply() {
        for (WeakReference<View> ref : sViews) {
            View v = ref.get();
            if (v != null) setHidden(v, true);
        }
    }

    private void hookReapply(ClassLoader cl, String cls, String... names) {
        try {
            Class<?> c = cl.loadClass(cls);
            for (Method m : c.getDeclaredMethods()) {
                for (String n : names) {
                    if (m.getName().equals(n)) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (sLocked) reapply();
                            return r;
                        });
                    }
                }
            }
        } catch (Throwable t) {
            l("reapply hook failed for " + cls + ": " + t);
        }
    }

    private void installOnce(Application app) {
        synchronized (MainHook.class) {
            if (installed) return;
            installed = true;
        }
        try {
            install(app);
        } catch (Throwable t) {
            l("install failed: " + t);
        }
    }

    private void install(final Context ctx) {
        final ContentResolver cr = ctx.getContentResolver();
        final Uri uri = Settings.Secure.getUriFor(KEY);
        try { Settings.Secure.putInt(cr, KEY, 0); } catch (Throwable t) { l("reset failed: " + t); }
        apply(ctx, false);
        ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                boolean locked = isLocked(cr);
                l("setting changed, locked=" + locked);
                apply(ctx, locked);
            }
        };
        try {
            cr.getClass().getMethod("registerContentObserver",
                    Uri.class, boolean.class, ContentObserver.class, int.class)
                    .invoke(cr, uri, false, obs, USER_ALL);
        } catch (Throwable t) {
            cr.registerContentObserver(uri, false, obs);
        }
        l("watching " + KEY);
    }

    private boolean isLocked(ContentResolver cr) {
        try {
            Method m = Settings.Secure.class.getMethod("getIntForUser",
                    ContentResolver.class, String.class, int.class, int.class);
            return ((Integer) m.invoke(null, cr, KEY, 0, USER_CURRENT)) == 1;
        } catch (Throwable t) {
            return Settings.Secure.getInt(cr, KEY, 0) == 1;
        }
    }

        private void apply(Context ctx, boolean locked) {
        sLocked = locked;
        for (WeakReference<View> ref : sViews) {
            View v = ref.get();
            if (v == null) sViews.remove(ref); else setHidden(v, locked);
        }
        try {
            Object sbm = ctx.getSystemService("statusbar");
            if (sbm == null) { l("no statusbar service"); return; }
            Class<?> k = sbm.getClass();
            k.getMethod("disable", int.class).invoke(sbm, locked ? DISABLE_EXPAND : 0);
            k.getMethod("disable2", int.class).invoke(sbm,
                    locked ? (DISABLE2_NOTIFICATION_SHADE | DISABLE2_QUICK_SETTINGS) : 0);
            l("status bar " + (locked ? "LOCKED" : "unlocked"));
        } catch (Throwable t) {
            l("apply failed: " + t);
        }
    }
}
