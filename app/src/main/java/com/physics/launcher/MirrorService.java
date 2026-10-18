package com.physics.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 거울 레이어의 두뇌.
 * 홈 화면에서 흔들면: 홈 화면 창을 찍고 → 아이콘·위젯 위치를 읽고 → 그 위에 거울을 덮어 조각들이 굴러다니게 함.
 * 거울 뒤에서 홈 화면 페이지를 넘기며 다른 페이지도 찍어 둠. 끝나면 원래 페이지로 돌려 놓음.
 */
public class MirrorService extends AccessibilityService implements SensorEventListener, MirrorView.Listener {
    static MirrorService instance;

    static final int IDLE = 0, PREP = 1, ACTIVE = 2, ENDING = 3;
    static final String SYSTEMUI = "com.android.systemui", VERSION = "2.0";

    final Handler h = new Handler(Looper.getMainLooper());
    Executor main;
    SharedPreferences sp;
    SensorManager sm;
    Sensor acc, grav;
    boolean linear, listenShake, listenGrav;
    WindowManager wm;
    String homePkg = "com.sec.android.app.launcher";
    volatile HashMap<String, ComponentName> apps = new HashMap<>();
    boolean homeLikely, screenOn = true, tileMode;
    int state = IDLE, gen, W, H, top, bottom, winId = -1, cur, phase;
    boolean capturing, restoreQueued;
    MirrorView view;
    Bitmap bg;
    boolean bgFallback;
    long lastProbe, firstPeak, lastPeak, lastShake;
    int peaks;
    float dp;
    String sig0, lastSig, dump = "";
    final Rect win = new Rect(), ws = new Rect();
    final StringBuilder log = new StringBuilder();

    final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (!screenOn) endInstant();
            updateListening();
        }
    };
    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------- 시작/종료 ----------

    @Override
    protected void onServiceConnected() {
        instance = this;
        main = getMainExecutor();
        sp = getSharedPreferences("p", 0);
        dp = getResources().getDisplayMetrics().density;
        wm = getSystemService(WindowManager.class);
        sm = getSystemService(SensorManager.class);
        acc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        linear = acc != null;
        if (acc == null) acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);

        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) {
            String p = ri.activityInfo.packageName;
            if (!"android".equals(p) && !getPackageName().equals(p)) homePkg = p;
        }
        loadApps();
        loadBg();

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f, Context.RECEIVER_EXPORTED);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf, Context.RECEIVER_EXPORTED);
        try {
            WallpaperManager.getInstance(this).addOnColorsChangedListener((colors, which) -> {
                if ((which & WallpaperManager.FLAG_SYSTEM) != 0) { bg = null; new File(getFilesDir(), "bg.png").delete(); }
            }, h);
        } catch (Exception ignored) { }

        AccessibilityNodeInfo r = getRootInActiveWindow();
        homeLikely = r != null && homePkg.equals(String.valueOf(r.getPackageName()));
        updateListening();
        if (!sp.getBoolean("welcomed", false)) {
            sp.edit().putBoolean("welcomed", true).apply();
            toast("홈 화면에서 폰을 흔들어 보세요");
        }
    }

    @Override
    public boolean onUnbind(Intent i) {
        endInstant();
        sm.unregisterListener(this);
        listenShake = listenGrav = false;
        try { unregisterReceiver(screenRx); unregisterReceiver(pkgRx); } catch (Exception ignored) { }
        instance = null;
        return super.onUnbind(i);
    }

    @Override public void onInterrupt() { endInstant(); }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (state != IDLE && state != PREP) endInstant();
    }

    void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            HashMap<String, ComponentName> m = new HashMap<>();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0))
                m.putIfAbsent(norm(r.loadLabel(pm)), new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
            apps = m;
        }).start();
    }

    static String norm(CharSequence s) {
        return s == null ? "" : s.toString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    ComponentName appFor(String label) {
        if (label == null) return null;
        ComponentName c = apps.get(norm(label));
        if (c == null) {
            int cut = label.indexOf(',');
            if (cut < 0) cut = label.indexOf('\n');
            if (cut > 0) c = apps.get(norm(label.substring(0, cut)));
        }
        return c;
    }

    // ---------- 홈 화면 감시 ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        String p = String.valueOf(e.getPackageName());
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (p.equals(getPackageName())) return;
            if (state == ACTIVE && !capturing) endInstant();
            if (p.equals(homePkg)) homeLikely = true;
            else if (!p.equals(SYSTEMUI)) homeLikely = false;
            updateListening();
        } else if (t == AccessibilityEvent.TYPE_VIEW_SCROLLED && state == ACTIVE && !capturing && p.equals(homePkg)) {
            // 거울이 덮여 있는데 홈 화면 페이지가 움직였다면 홈 버튼을 누른 것
            AccessibilityNodeInfo s = e.getSource();
            if (s != null && !ws.isEmpty()) {
                Rect r = new Rect();
                s.getBoundsInScreen(r);
                if (r.equals(ws)) endInstant();
            }
        }
    }

    void updateListening() {
        if (sm == null) return;
        boolean wantShake = screenOn && (homeLikely || state != IDLE);
        if (wantShake != listenShake && acc != null) {
            listenShake = wantShake;
            if (wantShake) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
            else sm.unregisterListener(this, acc);
        }
        boolean wantGrav = state != IDLE && grav != null;
        if (wantGrav != listenGrav) {
            listenGrav = wantGrav;
            if (wantGrav) sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME);
            else sm.unregisterListener(this, grav);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY || (type == Sensor.TYPE_ACCELEROMETER && grav == null)) {
            if (view != null) screenGravity(v[0], v[1]);
            if (type == Sensor.TYPE_GRAVITY) return;
        }
        float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!linear) m = Math.abs(m - 9.81f);
        int sens = sp.getInt("sens", 1);
        float th = sens == 0 ? 20 : sens == 2 ? 11 : 15;
        long now = SystemClock.uptimeMillis();
        if (m > th && now - lastPeak > 90) {
            if (now - firstPeak > 800) { firstPeak = now; peaks = 0; }
            peaks++;
            lastPeak = now;
            if (peaks >= 3 && now - lastShake > 1500) {
                lastShake = now;
                peaks = 0;
                onShake();
            }
        }
    }

    void screenGravity(float x, float y) {
        int rot = Surface.ROTATION_0;
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) rot = d.getRotation();
        float gx, gy;
        switch (rot) {
            case Surface.ROTATION_90: gx = y; gy = x; break;
            case Surface.ROTATION_180: gx = x; gy = -y; break;
            case Surface.ROTATION_270: gx = -y; gy = -x; break;
            default: gx = -x; gy = y;
        }
        view.setGravity(gx, gy);
    }

    void onShake() {
        if (state == ACTIVE) { onRestoreRequest(); return; }
        if (state == IDLE) start();
    }

    void startFromTile() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        h.postDelayed(() -> {
            if (state != IDLE) return;
            if (!start()) toast("홈 화면에서 다시 눌러 주세요");
        }, 700);
    }

    // ---------- 1단계: 찍기 ----------

    boolean start() {
        AccessibilityNodeInfo act = getRootInActiveWindow();
        if (act == null || !homePkg.equals(String.valueOf(act.getPackageName()))) return false;
        AccessibilityWindowInfo lw = launcherWindow();
        if (lw == null) return false;
        if (sp.getBoolean("bgDirty", false)) {
            sp.edit().putBoolean("bgDirty", false).apply();
            bg = null;
            new File(getFilesDir(), "bg.png").delete();
        }
        state = PREP;
        final int g = ++gen;
        log.setLength(0);
        dump = "";
        winId = lw.getId();
        lw.getBoundsInScreen(win);
        measureBars();
        log("model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " home=" + homePkg);
        log("screen=" + W + "x" + H + " top=" + top + " bottom=" + bottom + " win=" + win.toShortString());
        updateListening();
        shot(-1, C -> {
            if (g != gen) { C.recycle(); return; }
            shotLauncher(g, C, false);
        }, () -> abort(g, "화면을 찍지 못했어요"), 2);
        return true;
    }

    void shotLauncher(int g, Bitmap C, boolean probed) {
        shot(winId, L -> {
            if (g != gen) { L.recycle(); return; }
            afterShots(g, C, L, probed);
        }, () -> { if (g == gen) afterShots(g, C, null, probed); }, 2);
    }

    void afterShots(int g, Bitmap C, Bitmap L, boolean probed) {
        Bitmap Csw = sw(C);
        Bitmap Lsw = L == null ? null : sw(L);
        tileMode = Lsw == null || alphaEmpty(Lsw);
        if (tileMode) log("tile mode (launcher window capture " + (L == null ? "failed" : "empty") + ")");
        boolean need = bg == null || (!tileMode && Csw != null && mismatch(Csw, Lsw));
        if (need && !probed && (bg == null || SystemClock.elapsedRealtime() - lastProbe > 30 * 60_000L)) {
            log("probe wallpaper");
            if (Csw != null) Csw.recycle();
            if (Lsw != null) Lsw.recycle();
            if (L != null) L.recycle();
            probe(() -> {
                if (g != gen) return;
                AccessibilityWindowInfo lw = launcherWindow();
                if (lw == null) { abort(g, "홈 화면을 찾지 못했어요"); return; }
                winId = lw.getId();
                lw.getBoundsInScreen(win);
                shotLauncher(g, C, true);
            });
            return;
        }
        build(g, C, Csw, L, Lsw);
    }

    void build(int g, Bitmap C, Bitmap Csw, Bitmap L, Bitmap Lsw) {
        AccessibilityWindowInfo lw = launcherWindow();
        AccessibilityNodeInfo root = lw == null ? null : lw.getRoot();
        StringBuilder sb = new StringBuilder();
        ArrayList<Item> items = root == null ? new ArrayList<>() : scan(root, true, sb);
        dump = sb.toString();
        log("page 0 items=" + items.size() + " workspace=" + ws.toShortString());
        if (items.isEmpty() || Csw == null) {
            if (Csw != null) Csw.recycle();
            if (Lsw != null) Lsw.recycle();
            if (L != null) L.recycle();
            C.recycle();
            abort(g, "홈 화면에서 아이콘을 찾지 못했어요");
            return;
        }
        sig0 = lastSig = sig(items);

        Bitmap pageBmp = tileMode ? C : L;
        Bitmap pageSw = tileMode ? Csw : Lsw;
        Rect pageWin = tileMode ? new Rect(0, 0, W, H) : new Rect(win);
        if (!tileMode) C.recycle();
        if (bg == null) { bg = fallbackBg(Csw, items); bgFallback = true; log("background: fallback"); }

        ArrayList<MirrorView.Piece> ps = pieces(items, 0, pageSw, pageWin);
        if (pageSw != Csw) pageSw.recycle();
        if (bg != Csw) Csw.recycle();

        MirrorView.Shot s = new MirrorView.Shot();
        s.page = 0;
        s.bmp = pageBmp;
        s.win.set(pageWin);
        s.clip.set(0, top, W, H - bottom);

        view = new MirrorView(this, this, bg, W);
        view.addPage(s, ps);
        if (!addOverlay()) { view.release(); view = null; abort(g, "화면을 덮지 못했어요"); return; }
        state = ACTIVE;
        updateListening();
        buzz();
        writeDiag();

        if (!tileMode && !ws.isEmpty()) {
            capturing = true;
            cur = 0;
            phase = 0;
            h.postDelayed(() -> step(g), 200);
        } else finishCapture(g);
    }

    boolean addOverlay() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(W, H - top - bottom,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = top;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("PhysicsMirror");
        try { wm.addView(view, lp); return true; }
        catch (Exception e) { log("addView: " + e); return false; }
    }

    // ---------- 2단계: 거울 뒤에서 다른 페이지 찍기 ----------
    // phase 0: 왼쪽으로 넘기며 찍기, 1: 원래 페이지로, 2: 오른쪽으로 넘기며 찍기, 3: 원래 페이지로

    void step(int g) {
        if (g != gen || state != ACTIVE) return;
        if (phase == 1 && cur >= 0) { phase = 2; lastSig = sig0; }
        if (phase == 3 && cur <= 0) { finishCapture(g); return; }
        if (phase >= 4) { finishCapture(g); return; }
        boolean capture = phase == 0 || phase == 2;
        boolean back = phase == 0 || phase == 3;
        AccessibilityNodeInfo w = workspace();
        int a = back ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        boolean ok = w != null && Math.abs(cur) < 9 && w.performAction(a);
        if (!ok) {
            if (capture) { phase++; step(g); }
            else { log("could not return to start page (cur=" + cur + ")"); finishCapture(g); }
            return;
        }
        cur += back ? -1 : 1;
        h.postDelayed(() -> afterScroll(g, capture, 0), 600);
    }

    void afterScroll(int g, boolean capture, int retry) {
        if (g != gen || state != ACTIVE) return;
        if (!capture) { step(g); return; }
        shot(winId, L -> {
            if (g != gen || state != ACTIVE) { L.recycle(); return; }
            AccessibilityWindowInfo lw = launcherWindow();
            AccessibilityNodeInfo root = lw == null ? null : lw.getRoot();
            ArrayList<Item> items = root == null ? new ArrayList<>() : scan(root, false, null);
            String s = sig(items);
            if (s.equals(lastSig)) {
                L.recycle();
                if (retry < 1) { h.postDelayed(() -> afterScroll(g, true, retry + 1), 400); return; }
                cur += phase == 0 ? 1 : -1;   // 페이지가 안 넘어갔음 = 끝
                phase++;
                step(g);
                return;
            }
            if (items.isEmpty()) {
                L.recycle();
                log("no items on page " + cur);
                phase++;
                step(g);
                return;
            }
            lastSig = s;
            Bitmap Lsw = sw(L);
            ArrayList<MirrorView.Piece> ps = Lsw == null ? new ArrayList<>() : pieces(items, cur, Lsw, win);
            if (Lsw != null) Lsw.recycle();
            MirrorView.Shot sh = new MirrorView.Shot();
            sh.page = cur;
            sh.bmp = L;
            sh.win.set(win);
            sh.clip.set(ws);
            if (!sh.clip.intersect(0, top, W, H - bottom)) sh.clip.setEmpty();
            view.addPage(sh, ps);
            log("page " + cur + " items=" + items.size() + " pieces=" + ps.size());
            step(g);
        }, () -> { if (g == gen) { log("page shot failed at " + cur); phase++; step(g); } }, 2);
    }

    void finishCapture(int g) {
        capturing = false;
        writeDiag();
        if (g != gen) return;
        if (restoreQueued) { restoreQueued = false; onRestoreRequest(); return; }
        if (!tileMode) h.postDelayed(() -> refresh(g), 1000);
    }

    // 지금 페이지는 1초마다 다시 찍어서 위젯(시계 등)이 계속 바뀌게
    void refresh(int g) {
        if (g != gen || state != ACTIVE || capturing || view == null) return;
        shot(winId, L -> {
            if (g != gen || view == null || state != ACTIVE) { L.recycle(); return; }
            Bitmap old = view.replacePage(0, L);
            if (old != null && old != L) h.postDelayed(old::recycle, 700);
            h.postDelayed(() -> refresh(g), 1000);
        }, () -> h.postDelayed(() -> refresh(g), 2000), 0);
    }

    // ---------- 3단계: 끝내기 ----------

    @Override
    public void onRestoreRequest() {
        if (state != ACTIVE) return;
        if (capturing) { restoreQueued = true; return; }
        state = ENDING;
        final int g = gen;
        buzz();
        view.returnHome(() -> { if (g == gen) endInstant(); });
    }

    void endInstant() {
        gen++;
        h.removeCallbacksAndMessages(null);
        if (view != null) {
            try { wm.removeViewImmediate(view); } catch (Exception ignored) { }
            view.release();
            view = null;
        }
        if (bgFallback) { bg = null; bgFallback = false; }
        state = IDLE;
        capturing = restoreQueued = false;
        updateListening();
    }

    void abort(int g, String msg) {
        if (g != gen) return;
        log("abort: " + msg);
        writeDiag();
        endInstant();
        toast(msg);
    }

    @Override
    public void onTapPiece(MirrorView.Piece p) {
        if (state != ACTIVE) return;
        if (capturing) { view.kick(p); return; }
        if (p.app != null) {
            ComponentName c = p.app;
            endInstant();
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (Exception e) { toast("앱을 열지 못했어요"); }
            return;
        }
        if (p.widget) { view.kick(p); return; }
        // 폴더처럼 앱이 아닌 것: 원래대로 돌린 뒤 진짜 홈 화면의 그 자리를 누름
        final int page = p.page;
        final Rect r = new Rect(p.screen);
        state = ENDING;
        final int g = gen;
        view.returnHome(() -> {
            if (g != gen) return;
            endInstant();
            clickAt(gen, page, r);
        });
    }

    void clickAt(int g, int remaining, Rect r) {
        if (g != gen) return;
        if (remaining == 0) {
            AccessibilityWindowInfo lw = launcherWindow();
            AccessibilityNodeInfo n = lw == null ? null : nodeAt(lw.getRoot(), r.centerX(), r.centerY());
            if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        AccessibilityNodeInfo w = workspace();
        if (w == null) return;
        w.performAction(remaining > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        h.postDelayed(() -> clickAt(g, remaining > 0 ? remaining - 1 : remaining + 1, r), 550);
    }

    AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo n, int x, int y) {
        if (n == null) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = nodeAt(n.getChild(i), x, y);
            if (c != null) return c;
        }
        return n.isClickable() ? n : null;
    }

    @Override
    public void onSwipe(boolean up) {
        if (state != ACTIVE || capturing) return;
        endInstant();
        if (up) {
            h.postDelayed(() -> {
                Path p = new Path();
                p.moveTo(W / 2f, H * 0.72f);
                p.lineTo(W / 2f, H * 0.28f);
                dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null);
            }, 150);
        } else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    // ---------- 홈 화면 읽기 ----------

    static class Item { final Rect r = new Rect(); String label; boolean icon; }

    static class N {
        AccessibilityNodeInfo n;
        int parent;
        final Rect r = new Rect();
        boolean click, scroll;
        String label, cls;
    }

    AccessibilityWindowInfo launcherWindow() {
        AccessibilityWindowInfo best = null;
        long bestArea = 0;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !homePkg.equals(String.valueOf(root.getPackageName()))) continue;
            w.getBoundsInScreen(r);
            long a = (long) r.width() * r.height();
            if (a > bestArea) { best = w; bestArea = a; }
        }
        return best;
    }

    AccessibilityNodeInfo workspace() {
        AccessibilityWindowInfo lw = launcherWindow();
        if (lw == null) return null;
        ArrayList<N> all = new ArrayList<>();
        collect(lw.getRoot(), -1, 0, all, null);
        N best = null;
        for (N x : all)
            if (x.scroll && x.r.width() >= W * 0.8f && (best == null || area(x.r) > area(best.r))) best = x;
        return best == null ? null : best.n;
    }

    void measureBars() {
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        d.getRealSize(size);
        W = size.x;
        H = size.y;
        top = bottom = 0;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            w.getBoundsInScreen(r);
            if (r.width() < W * 0.9f || r.height() > H * 0.15f) continue;
            if (r.top <= 0) top = Math.max(top, r.bottom);
            else if (r.bottom >= H) bottom = Math.max(bottom, H - r.top);
        }
        if (top == 0) top = dimen("status_bar_height");
        if (bottom == 0) bottom = dimen("navigation_bar_height");
    }

    int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    static long area(Rect r) { return (long) Math.max(0, r.width()) * Math.max(0, r.height()); }

    void collect(AccessibilityNodeInfo n, int parent, int depth, ArrayList<N> all, StringBuilder sb) {
        if (n == null || depth > 40 || all.size() > 2500 || !n.isVisibleToUser()) return;
        N x = new N();
        x.n = n;
        x.parent = parent;
        n.getBoundsInScreen(x.r);
        x.click = n.isClickable() || n.isLongClickable();
        x.scroll = n.isScrollable();
        CharSequence t = n.getText(), d = n.getContentDescription();
        x.label = t != null && t.length() > 0 ? t.toString() : d != null && d.length() > 0 ? d.toString() : null;
        x.cls = String.valueOf(n.getClassName());
        int idx = all.size();
        all.add(x);
        if (sb != null && sb.length() < 60000) {
            for (int i = 0; i < depth; i++) sb.append(' ');
            String c = x.cls.substring(x.cls.lastIndexOf('.') + 1);
            String id = n.getViewIdResourceName();
            sb.append(c);
            if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));
            sb.append(' ').append(x.r.toShortString());
            if (n.isClickable()) sb.append(" C");
            if (n.isLongClickable()) sb.append(" LC");
            if (x.scroll) sb.append(" S");
            if (x.label != null) sb.append(" \"").append(x.label.length() > 30 ? x.label.substring(0, 30) : x.label).append('"');
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), idx, depth + 1, all, sb);
    }

    /** 홈 화면 창에서 아이콘·위젯 자리를 찾음. page0이 아니면 페이지 영역 안의 것만. */
    ArrayList<Item> scan(AccessibilityNodeInfo root, boolean page0, StringBuilder sb) {
        ArrayList<N> all = new ArrayList<>();
        collect(root, -1, 0, all, sb);
        float SA = (float) W * H;

        N wsN = null;
        for (N x : all)
            if (x.scroll && x.r.width() >= W * 0.8f && (wsN == null || area(x.r) > area(wsN.r))) wsN = x;
        if (wsN != null) ws.set(wsN.r); else if (page0) ws.setEmpty();

        // 아이콘 후보: 누를 수 있고 이름이 있는 작은 것
        ArrayList<Integer> cand = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            N x = all.get(i);
            long a = area(x.r);
            if (x.click && x.label != null && !x.scroll && a > 0 && a <= SA * 0.08f
                    && x.r.width() >= 24 * dp && x.r.height() >= 24 * dp && inside(x.r)) cand.add(i);
        }
        ArrayList<Integer> icons = new ArrayList<>(cand);
        if (cand.size() >= 3) {
            int[] ws_ = new int[cand.size()], hs = new int[cand.size()];
            for (int i = 0; i < cand.size(); i++) { ws_[i] = all.get(cand.get(i)).r.width(); hs[i] = all.get(cand.get(i)).r.height(); }
            Arrays.sort(ws_);
            Arrays.sort(hs);
            float mw = ws_[ws_.length / 2], mh = hs[hs.length / 2];
            icons.clear();
            for (int i : cand) {
                Rect r = all.get(i).r;
                if (r.width() >= mw * 0.55f && r.width() <= mw * 1.6f && r.height() >= mh * 0.55f && r.height() <= mh * 1.6f) icons.add(i);
            }
        }

        // 아이콘을 담은 큰 상자(페이지 칸, 독)를 찾고, 그 상자의 다른 자식은 위젯으로
        LinkedHashMap<Integer, Item> items = new LinkedHashMap<>();
        LinkedHashSet<Integer> boxes = new LinkedHashSet<>();
        for (int i : icons) {
            int c = i, p = all.get(i).parent;
            while (p >= 0 && area(all.get(p).r) < SA * 0.05f) { c = p; p = all.get(p).parent; }
            if (p < 0) continue;
            boxes.add(p);
            if (!items.containsKey(c)) {
                Item it = new Item();
                it.r.set(all.get(c).r);
                it.icon = true;
                it.label = all.get(i).label;
                items.put(c, it);
            }
        }
        for (int j = 0; j < all.size(); j++) {
            N x = all.get(j);
            if (items.containsKey(j)) continue;
            boolean sibling = boxes.contains(x.parent);
            boolean widgetClass = x.cls.contains("AppWidgetHostView");
            if (!sibling && !widgetClass) continue;
            long a = area(x.r);
            if (a < SA * 0.004f || a > SA * 0.6f || !inside(x.r)) continue;
            Item it = new Item();
            it.r.set(x.r);
            it.icon = false;
            it.label = x.label != null ? x.label : x.cls;
            items.put(j, it);
        }

        ArrayList<Item> out = new ArrayList<>();
        for (Item it : items.values()) {
            if (!page0 && !ws.isEmpty() && !ws.contains(it.r.centerX(), it.r.centerY())) continue;
            boolean nested = false;
            for (Item o : items.values())
                if (o != it && o.r.contains(it.r) && !o.r.equals(it.r)) { nested = true; break; }
            if (!nested) out.add(it);
        }
        return out;
    }

    boolean inside(Rect r) { return r.centerY() > top && r.centerY() < H - bottom; }

    static String sig(ArrayList<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item it : items) b.append(it.r.toShortString()).append(it.label).append('|');
        return b.toString();
    }

    ArrayList<MirrorView.Piece> pieces(ArrayList<Item> items, int page, Bitmap sw, Rect pageWin) {
        ArrayList<MirrorView.Piece> out = new ArrayList<>();
        float sx = sw.getWidth() / (float) pageWin.width(), sy = sw.getHeight() / (float) pageWin.height();
        int pad = Math.round(3 * dp);
        for (Item it : items) {
            Rect t = tileMode ? new Rect(it.r) : tight(sw, pageWin, it.r, sx, sy);
            if (t == null || t.width() < 8 * dp || t.height() < 8 * dp) continue;
            MirrorView.Piece p = new MirrorView.Piece();
            p.page = page;
            p.widget = !it.icon;
            p.label = it.label;
            p.screen.set(t);
            p.hole.set(t);
            p.hole.inset(-pad, -pad);
            p.src.set(Math.round((t.left - pageWin.left) * sx), Math.round((t.top - pageWin.top) * sy),
                    Math.round((t.right - pageWin.left) * sx), Math.round((t.bottom - pageWin.top) * sy));
            if (it.icon) p.app = appFor(it.label);
            out.add(p);
        }
        return out;
    }

    /** 투명한 테두리를 잘라 내 실제 그림이 있는 부분만 남김 */
    static Rect tight(Bitmap sw, Rect pageWin, Rect r, float sx, float sy) {
        int l = Math.max(0, Math.round((r.left - pageWin.left) * sx)), t = Math.max(0, Math.round((r.top - pageWin.top) * sy));
        int rr = Math.min(sw.getWidth(), Math.round((r.right - pageWin.left) * sx)), b = Math.min(sw.getHeight(), Math.round((r.bottom - pageWin.top) * sy));
        int w = rr - l, hh = b - t;
        if (w <= 0 || hh <= 0) return null;
        int[] px = new int[w * hh];
        sw.getPixels(px, 0, w, l, t, w, hh);
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = -1, maxy = -1;
        for (int y = 0; y < hh; y++)
            for (int x = 0, i = y * w; x < w; x++, i++)
                if ((px[i] >>> 24) > 24) {
                    if (x < minx) minx = x;
                    if (x > maxx) maxx = x;
                    if (y < miny) miny = y;
                    if (y > maxy) maxy = y;
                }
        if (maxx < 0) return null;
        return new Rect(pageWin.left + (int) Math.floor((l + minx - 1) / sx), pageWin.top + (int) Math.floor((t + miny - 1) / sy),
                pageWin.left + (int) Math.ceil((l + maxx + 2) / sx), pageWin.top + (int) Math.ceil((t + maxy + 2) / sy));
    }

    static boolean alphaEmpty(Bitmap b) {
        int step = Math.max(4, b.getWidth() / 60);
        for (int y = 0; y < b.getHeight(); y += step)
            for (int x = 0; x < b.getWidth(); x += step)
                if ((b.getPixel(x, y) >>> 24) > 8) return false;
        return true;
    }

    // ---------- 배경화면 ----------

    /** 홈 화면 창이 투명한 곳(배경화면이 보이는 곳)끼리 비교해 배경이 바뀌었는지 확인 */
    boolean mismatch(Bitmap Csw, Bitmap Lsw) {
        if (bg == null || bg.getWidth() != Csw.getWidth() || bg.getHeight() != Csw.getHeight()) return true;
        float lsx = Lsw.getWidth() / (float) win.width(), lsy = Lsw.getHeight() / (float) win.height();
        float csx = Csw.getWidth() / (float) W, csy = Csw.getHeight() / (float) H;
        int step = Math.round(12 * dp), n = 0, bad = 0;
        for (int y = top + step; y < H - bottom - step; y += step)
            for (int x = step / 2; x < W; x += step) {
                int lx = (int) ((x - win.left) * lsx), ly = (int) ((y - win.top) * lsy);
                if (lx < 0 || ly < 0 || lx >= Lsw.getWidth() || ly >= Lsw.getHeight()) continue;
                if ((Lsw.getPixel(lx, ly) >>> 24) > 8) continue;
                int cx = Math.min(Csw.getWidth() - 1, (int) (x * csx)), cy = Math.min(Csw.getHeight() - 1, (int) (y * csy));
                int a = Csw.getPixel(cx, cy), b = bg.getPixel(cx, cy);
                n++;
                if (Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                        + Math.abs(Color.blue(a) - Color.blue(b)) > 45) bad++;
            }
        log("background check samples=" + n + " differ=" + bad);
        return n >= 40 && bad > n * 0.2f;
    }

    Runnable probeThen;
    final Runnable probeTimeout = () -> {
        log("probe timeout");
        Runnable t = probeThen;
        probeThen = null;
        if (t != null) t.run();
    };

    /** 배경화면만 보이는 투명 화면을 잠깐 띄워 배경화면을 찍음(배경이 바뀌었을 때 한 번) */
    void probe(Runnable then) {
        probeThen = then;
        lastProbe = SystemClock.elapsedRealtime();
        try {
            startActivity(new Intent(this, ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) { log("probe start: " + e); }
        h.postDelayed(probeTimeout, 3000);
    }

    void onProbeReady(Activity a) {
        h.removeCallbacks(probeTimeout);
        if (probeThen == null) { a.finish(); return; }
        shot(-1, b -> {
            Bitmap s = sw(b);
            b.recycle();
            if (s != null) { bg = s; bgFallback = false; saveBg(s); log("background captured"); }
            finishProbe(a);
        }, () -> finishProbe(a), 2);
    }

    void finishProbe(Activity a) {
        a.finish();
        Runnable t = probeThen;
        probeThen = null;
        if (t != null) h.postDelayed(t, 550);
    }

    Bitmap fallbackBg(Bitmap Csw, ArrayList<Item> items) {
        Bitmap b = Csw.copy(Bitmap.Config.ARGB_8888, true);
        if (b == null) return Csw;
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        float sx = b.getWidth() / (float) W, sy = b.getHeight() / (float) H;
        for (Item it : items) {
            Rect r = new Rect(Math.round(it.r.left * sx), Math.round(it.r.top * sy), Math.round(it.r.right * sx), Math.round(it.r.bottom * sy));
            if (!r.intersect(0, 0, b.getWidth() - 1, b.getHeight() - 1)) continue;
            long rs = 0, gs = 0, bs = 0, n = 0;
            for (int x = r.left; x < r.right; x += 4) {
                for (int y : new int[]{r.top, r.bottom}) {
                    int px = Csw.getPixel(x, Math.min(y, b.getHeight() - 1));
                    rs += Color.red(px); gs += Color.green(px); bs += Color.blue(px); n++;
                }
            }
            if (n == 0) continue;
            p.setColor(Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n)));
            c.drawRect(r, p);
        }
        return b;
    }

    void saveBg(Bitmap b) {
        new Thread(() -> {
            try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "bg.png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (Exception ignored) { }
        }).start();
    }

    void loadBg() {
        new Thread(() -> {
            File f = new File(getFilesDir(), "bg.png");
            if (!f.exists()) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) h.post(() -> { if (bg == null) bg = b; });
        }).start();
    }

    // ---------- 도구 ----------

    void shot(int window, Consumer<Bitmap> ok, Runnable fail, int tries) {
        TakeScreenshotCallback cb = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                HardwareBuffer hb = r.getHardwareBuffer();
                ColorSpace cs = r.getColorSpace();
                Bitmap b = Bitmap.wrapHardwareBuffer(hb, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
                hb.close();
                if (b == null) fail.run(); else ok.accept(b);
            }
            @Override public void onFailure(int code) {
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && tries > 0)
                    h.postDelayed(() -> shot(window, ok, fail, tries - 1), 350);
                else { log("screenshot error " + code + " window=" + window); fail.run(); }
            }
        };
        try {
            if (window < 0) takeScreenshot(Display.DEFAULT_DISPLAY, main, cb);
            else takeScreenshotOfWindow(window, main, cb);
        } catch (Exception e) {
            log("screenshot exception " + e);
            fail.run();
        }
    }

    static Bitmap sw(Bitmap b) {
        try { return b.copy(Bitmap.Config.ARGB_8888, false); } catch (Exception e) { return null; }
    }

    void buzz() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } catch (Exception ignored) { }
    }

    void toast(String s) { h.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    void log(String s) {
        if (log.length() < 20000) log.append(SystemClock.uptimeMillis() % 100000).append(' ').append(s).append('\n');
    }

    void writeDiag() {
        String text = "물리 홈 진단 정보 v" + VERSION + "\n" + log + "\n--- 홈 화면 구조 ---\n" + dump;
        new Thread(() -> {
            try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "diag.txt"))) {
                o.write(text.getBytes("UTF-8"));
            } catch (Exception ignored) { }
        }).start();
    }
}
