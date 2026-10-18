package com.physics.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;

/**
 * 홈 화면 전체. 페이지가 옆으로 이어진 하나의 공간.
 * 정리 모드: 아이콘·위젯이 칸에 맞춰 놓임. 물리 모드: 중력·기울기에 따라 떨어지고 굴러다님.
 */
public class World extends FrameLayout implements Choreographer.FrameCallback {

    interface Host {
        void launch(Body b, float sx, float sy);
        void onMenu();
        void onItemMenu(Body b, boolean inDock);
        void onSwipe(boolean up);
    }

    static class Body {
        float x, y, vx, vy, a, w, r, hw, hh, im, ii, hx, hy;
        boolean circle, placed;
        String key, label;
        CharSequence shown;
        Bitmap bmp;
        Intent launch;
        View view;
        int wid;
    }

    static final int COLS = 4;
    final ArrayList<Body> bodies = new ArrayList<>(), dock = new ArrayList<>();
    final Body WALL = new Body();
    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    final Random rnd = new Random();
    final float dp, iconR;
    final int slop;
    Host host;

    int pages = 1, minPages = 2, page;
    int idle;
    float vw, vh, insetT, insetB, floor, cellW, camX, camT, gx = 0, gy = 9.8f, sgx, sgy;
    boolean physics = true, tilt = true, running, paused;
    long last, edgeT;

    Body down, grab;
    boolean downDock, moved, panning, vertical, longDone;
    float fx, fy, downX, downY, camStart;
    long downT;
    VelocityTracker vt;

    final Runnable longPress = () -> {
        if (moved || panning) return;
        longDone = true;
        grab = null;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (down == null) host.onMenu();
        else host.onItemMenu(down, downDock);
    };

    public World(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        vw = getResources().getDisplayMetrics().widthPixels;
        vh = getResources().getDisplayMetrics().heightPixels;
        iconR = 27 * dp;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        text.setShadowLayer(3 * dp, 0, 0.5f * dp, 0xCC000000);
        setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
            insetT = i.top;
            insetB = i.bottom;
            layoutHome();
            return in;
        });
    }

    // ---------- 아이콘·위젯 ----------

    Body icon(String key, String label, Bitmap bmp, Intent launch) {
        Body b = new Body();
        b.circle = true; b.r = iconR; b.key = key; b.label = label; b.bmp = bmp; b.launch = launch;
        float m = b.r * b.r / 1000f;
        b.im = 1 / m; b.ii = 1 / (0.5f * m * b.r * b.r);
        return b;
    }

    void syncApps(List<Body> list) {
        HashMap<String, Body> want = new HashMap<>();
        for (Body b : list) want.put(b.key, b);
        for (Iterator<Body> it = bodies.iterator(); it.hasNext(); ) {
            Body b = it.next();
            if (b.view != null) continue;
            Body n = want.remove(b.key);
            if (n == null) it.remove(); else { b.bmp = n.bmp; b.launch = n.launch; b.label = n.label; }
        }
        bodies.addAll(want.values());
        layoutHome();
    }

    void setDock(List<Body> list) {
        dock.clear();
        dock.addAll(list);
        layoutHome();
    }

    void addWidget(int id, View v, int w, int h, String label) {
        Body b = new Body();
        b.hw = w / 2f; b.hh = h / 2f; b.view = v; b.wid = id; b.label = label;
        b.im = 1000f / (w * h);
        addView(v, new LayoutParams(w, h));
        bodies.add(b);
        layoutHome();
    }

    void removeWidget(int id) {
        for (Iterator<Body> it = bodies.iterator(); it.hasNext(); ) {
            Body b = it.next();
            if (b.view != null && b.wid == id) { removeView(b.view); it.remove(); }
        }
        layoutHome();
    }

    /** 칸 배치 계산: 위젯이 위쪽부터, 그다음 앱이 이름순으로 4칸씩 */
    void layoutHome() {
        if (vw <= 0 || vh <= 0) return;
        bodies.sort((a, b) -> a.view != null ? (b.view != null ? 0 : -1)
                : b.view != null ? 1 : String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        float dockH = (dock.isEmpty() ? 30 : 100) * dp;
        floor = vh - insetB - dockH;
        float top = insetT + 12 * dp, areaH = floor - 30 * dp - top;
        int rows = Math.max(4, Math.min(6, (int) (areaH / (100 * dp))));
        float ch = areaH / rows;
        cellW = vw / COLS;
        int p = 0, r = 0, c = 0;
        for (Body b : bodies) {
            if (b.view != null) {
                if (c > 0) { r++; c = 0; }
                int need = Math.max(1, Math.min(rows, (int) Math.ceil(b.hh * 2 / ch - 0.05f)));
                if (r + need > rows) { p++; r = 0; }
                b.hx = p * vw + vw / 2;
                b.hy = top + (r + need / 2f) * ch;
                r += need;
            } else {
                if (r >= rows) { p++; r = 0; }
                b.hx = p * vw + (c + 0.5f) * cellW;
                b.hy = top + r * ch + ch * 0.4f;
                b.shown = TextUtils.ellipsize(b.label, text, cellW - 10 * dp, TextUtils.TruncateAt.END);
                if (++c == COLS) { c = 0; r++; }
            }
            if (!b.placed) { b.x = b.hx; b.y = b.hy; b.placed = true; }
        }
        pages = Math.max(minPages, p + 1);
        for (int i = 0; i < dock.size(); i++) {
            Body b = dock.get(i);
            b.x = vw * (i + 0.5f) / dock.size();
            b.y = floor + dockH / 2;
        }
        goPage(page);
    }

    void goPage(int p) {
        page = Math.max(0, Math.min(pages - 1, p));
        camT = page * vw;
        wake();
    }

    void setPhysics(boolean on) {
        physics = on;
        grab = null;
        if (on) for (Body b : bodies) {
            b.vx = (rnd.nextFloat() - 0.5f) * 400 * dp;
            b.vy = -rnd.nextFloat() * 300 * dp;
            if (b.circle) b.w = (rnd.nextFloat() - 0.5f) * 10;
        }
        wake();
    }

    void setGravity(float x, float y) {
        if (!tilt) { x = 0; y = 9.8f; }
        gx += (x - gx) * 0.3f;
        gy += (y - gy) * 0.3f;
        if (physics && !running && Math.hypot(gx - sgx, gy - sgy) > 0.8) wake();
    }

    // ---------- 루프 ----------

    void wake() {
        idle = 0;
        if (!running && !paused) {
            running = true; last = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    void pause() { paused = true; running = false; }
    void resume() { paused = false; wake(); }

    @Override
    public void doFrame(long t) {
        if (!running) return;
        float dt = last == 0 ? 1 / 60f : Math.min((t - last) / 1e9f, 1 / 30f);
        last = t;
        boolean still;
        if (physics) {
            for (int i = 0; i < 3; i++) step(dt / 3);
            float maxV = 0;
            for (Body b : bodies) maxV = Math.max(maxV, b.vx * b.vx + b.vy * b.vy);
            still = maxV < 225 * dp * dp;
        } else still = settle(dt);
        if (!panning) camX += (camT - camX) * Math.min(1, dt * 14);
        edgeScroll();

        for (Body b : bodies) if (b.view != null) {
            b.view.setTranslationX(b.x - b.hw - camX);
            b.view.setTranslationY(b.y - b.hh);
        }
        invalidate();
        still &= grab == null && !panning && Math.abs(camX - camT) < 1;
        idle = still ? idle + 1 : 0;
        if (idle > (physics ? 45 : 2)) {
            running = false;
            camX = camT;
            for (Body b : bodies) b.vx = b.vy = b.w = 0;
            sgx = gx; sgy = gy;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** 정리 모드: 제자리로 미끄러져 돌아감 */
    boolean settle(float dt) {
        float k = 1 - (float) Math.exp(-dt * 12);
        boolean done = true;
        double tau = Math.PI * 2;
        for (Body b : bodies) {
            float ta = (float) (Math.round(b.a / tau) * tau);
            b.x += (b.hx - b.x) * k; b.y += (b.hy - b.y) * k; b.a += (ta - b.a) * k;
            b.vx = b.vy = b.w = 0;
            if (Math.abs(b.hx - b.x) > 0.5f || Math.abs(b.hy - b.y) > 0.5f || Math.abs(ta - b.a) > 0.005f) done = false;
            else { b.x = b.hx; b.y = b.hy; b.a = 0; }
        }
        return done;
    }

    void edgeScroll() {
        if (grab == null) { edgeT = 0; return; }
        float e = 28 * dp;
        int dir = fx < e ? -1 : fx > vw - e ? 1 : 0;
        if (dir == 0 || page + dir < 0 || page + dir >= pages) { edgeT = 0; return; }
        long now = SystemClock.uptimeMillis();
        if (edgeT == 0) edgeT = now;
        else if (now - edgeT > 450) { goPage(page + dir); edgeT = 0; }
    }

    // ---------- 물리 ----------

    void step(float h) {
        float W = pages * vw, ax = gx * 120 * dp, ay = gy * 120 * dp, cap = 3000 * dp;
        float drag = 1 - 0.3f * h, spin = 1 - 1.2f * h;
        for (Body b : bodies) {
            if (b == grab) {
                b.vx = (fx + camX - b.x) * 18;
                b.vy = (fy - b.y) * 18;
                b.w *= 0.9f;
            } else {
                b.vx = (b.vx + ax * h) * drag;
                b.vy = (b.vy + ay * h) * drag;
                b.w *= spin;
            }
            float s = (float) Math.hypot(b.vx, b.vy);
            if (s > cap) { b.vx *= cap / s; b.vy *= cap / s; }
            b.x += b.vx * h; b.y += b.vy * h; b.a += b.w * h;
        }
        int n = bodies.size();
        for (int it = 0; it < 2; it++) {
            for (int i = 0; i < n; i++) {
                Body A = bodies.get(i);
                for (int j = i + 1; j < n; j++) collide(A, bodies.get(j));
                walls(A, W);
            }
        }
    }

    // 막힌 벽: 첫 페이지 왼쪽 끝, 마지막 페이지 오른쪽 끝, 상태바 아래, 독 위. 페이지 사이는 열려 있음
    void walls(Body b, float W) {
        float ex = b.circle ? b.r : b.hw, ey = b.circle ? b.r : b.hh, k = b.circle ? b.r : 0;
        if (b.x - ex < 0) resolve(b, WALL, -1, 0, ex - b.x, -k, 0, 0, 0);
        if (b.x + ex > W) resolve(b, WALL, 1, 0, b.x + ex - W, k, 0, 0, 0);
        if (b.y - ey < insetT) resolve(b, WALL, 0, -1, insetT - (b.y - ey), 0, -k, 0, 0);
        if (b.y + ey > floor) resolve(b, WALL, 0, 1, b.y + ey - floor, 0, k, 0, 0);
    }

    void collide(Body A, Body B) {
        if (A.circle && B.circle) {
            float dx = B.x - A.x, dy = B.y - A.y, R = A.r + B.r, d2 = dx * dx + dy * dy;
            if (d2 >= R * R) return;
            float d = (float) Math.sqrt(d2), nx = d > 0 ? dx / d : 1, ny = d > 0 ? dy / d : 0;
            resolve(A, B, nx, ny, R - d, nx * A.r, ny * A.r, -nx * B.r, -ny * B.r);
        } else if (A.circle) circBox(A, B);
        else if (B.circle) circBox(B, A);
        else {
            float dx = B.x - A.x, dy = B.y - A.y;
            float ox = A.hw + B.hw - Math.abs(dx), oy = A.hh + B.hh - Math.abs(dy);
            if (ox <= 0 || oy <= 0) return;
            if (ox < oy) resolve(A, B, dx < 0 ? -1 : 1, 0, ox, 0, 0, 0, 0);
            else resolve(A, B, 0, dy < 0 ? -1 : 1, oy, 0, 0, 0, 0);
        }
    }

    void circBox(Body C, Body X) {
        float qx = Math.max(X.x - X.hw, Math.min(C.x, X.x + X.hw));
        float qy = Math.max(X.y - X.hh, Math.min(C.y, X.y + X.hh));
        float dx = qx - C.x, dy = qy - C.y, d2 = dx * dx + dy * dy, nx, ny, pen;
        if (d2 > C.r * C.r) return;
        if (d2 > 1e-4f) {
            float d = (float) Math.sqrt(d2);
            nx = dx / d; ny = dy / d; pen = C.r - d;
        } else {
            float ox = X.hw - Math.abs(C.x - X.x), oy = X.hh - Math.abs(C.y - X.y);
            if (ox < oy) { nx = C.x < X.x ? 1 : -1; ny = 0; pen = C.r + ox; }
            else { nx = 0; ny = C.y < X.y ? 1 : -1; pen = C.r + oy; }
        }
        resolve(C, X, nx, ny, pen, nx * C.r, ny * C.r, 0, 0);
    }

    void resolve(Body A, Body B, float nx, float ny, float pen,
                 float rax, float ray, float rbx, float rby) {
        float ims = A.im + B.im;
        if (ims == 0) return;
        float c = Math.max(pen - 0.3f, 0) * 0.6f / ims;
        A.x -= nx * c * A.im; A.y -= ny * c * A.im;
        B.x += nx * c * B.im; B.y += ny * c * B.im;

        float rvx = (B.vx - B.w * rby) - (A.vx - A.w * ray);
        float rvy = (B.vy + B.w * rbx) - (A.vy + A.w * rax);
        float vn = rvx * nx + rvy * ny;
        if (vn > 0) return;
        float ran = rax * ny - ray * nx, rbn = rbx * ny - rby * nx;
        float e = -vn < 60 * dp ? 0 : 0.35f;
        float j = -(1 + e) * vn / (ims + ran * ran * A.ii + rbn * rbn * B.ii);
        apply(A, B, nx * j, ny * j, rax, ray, rbx, rby);

        rvx = (B.vx - B.w * rby) - (A.vx - A.w * ray);
        rvy = (B.vy + B.w * rbx) - (A.vy + A.w * rax);
        float tx = -ny, ty = nx, vt = rvx * tx + rvy * ty;
        float rat = rax * ty - ray * tx, rbt = rbx * ty - rby * tx;
        float jt = -vt / (ims + rat * rat * A.ii + rbt * rbt * B.ii), mu = 0.4f * j;
        jt = Math.max(-mu, Math.min(mu, jt));
        apply(A, B, tx * jt, ty * jt, rax, ray, rbx, rby);
    }

    static void apply(Body A, Body B, float px, float py, float rax, float ray, float rbx, float rby) {
        A.vx -= px * A.im; A.vy -= py * A.im; A.w -= (rax * py - ray * px) * A.ii;
        B.vx += px * B.im; B.vy += py * B.im; B.w += (rbx * py - rby * px) * B.ii;
    }

    // ---------- 그리기 ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vw = w; vh = h;
        layoutHome();
        camX = camT;
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        float lbl = iconR + 16 * dp, near = 3 * dp;
        for (Body b : bodies) {
            if (b.bmp == null) continue;
            float sx = b.x - camX;
            if (sx < -cellW || sx > vw + cellW) continue;
            c.save();
            c.rotate(b.a * 57.29578f, sx, b.y);
            rect.set(sx - b.r, b.y - b.r, sx + b.r, b.y + b.r);
            c.drawBitmap(b.bmp, null, rect, paint);
            c.restore();
            if (!physics && Math.abs(b.x - b.hx) < near && Math.abs(b.y - b.hy) < near)
                c.drawText(b.shown, 0, b.shown.length(), sx, b.y + lbl, text);
        }
        if (grab != null && grab.label != null) {
            float ey = grab.circle ? grab.r : grab.hh;
            c.drawText(grab.label, grab.x - camX, Math.max(insetT + 16 * dp, grab.y - ey - 10 * dp), text);
        }
        for (Body b : dock) {
            rect.set(b.x - b.r, b.y - b.r, b.x + b.r, b.y + b.r);
            c.drawBitmap(b.bmp, null, rect, paint);
        }
        if (pages > 1) {
            float y = floor - 14 * dp;
            int cur = Math.round(camX / vw);
            for (int i = 0; i < pages; i++) {
                dot.setColor(i == cur ? 0xFFFFFFFF : 0x77FFFFFF);
                c.drawCircle(vw / 2 + (i - (pages - 1) / 2f) * 16 * dp, y, 3.5f * dp, dot);
            }
        }
    }

    // ---------- 터치 ----------

    Body hit(float sx, float sy) {
        float x = sx + camX, rr = iconR * 1.15f;
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (b.circle && (x - b.x) * (x - b.x) + (sy - b.y) * (sy - b.y) < rr * rr) return b;
        }
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (!b.circle && Math.abs(x - b.x) < b.hw && Math.abs(sy - b.y) < b.hh) return b;
        }
        return null;
    }

    Body hitDock(float sx, float sy) {
        if (sy < floor) return null;
        for (Body b : dock) if (Math.hypot(sx - b.x, sy - b.y) < iconR * 1.3f) return b;
        return null;
    }

    void down(MotionEvent e) {
        wake();
        downX = fx = e.getX(); downY = fy = e.getY();
        downT = SystemClock.uptimeMillis();
        moved = panning = vertical = longDone = false;
        grab = null;
        down = hitDock(downX, downY);
        downDock = down != null;
        if (!downDock) down = hit(downX, downY);
        camStart = camX;
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        removeCallbacks(longPress);
        postDelayed(longPress, 500);
    }

    void startPan(float dx, float dy) {
        panning = true;
        vertical = Math.abs(dy) > Math.abs(dx);
        camStart = camX;
        grab = null;
    }

    // 위젯 위에서는 살짝 누르면 위젯이 받고, 끌기 시작하면 이쪽이 가져옴
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            down(e);
            return down == null || down.view == null;
        }
        if (longDone) return true;
        if (act == MotionEvent.ACTION_MOVE && down != null && down.view != null) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (Math.hypot(dx, dy) > slop) {
                if (physics) { grab = down; moved = true; removeCallbacks(longPress); fx = e.getX(); fy = e.getY(); wake(); return true; }
                if (Math.abs(dx) > Math.abs(dy)) { moved = true; removeCallbacks(longPress); startPan(dx, dy); return true; }
            }
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) removeCallbacks(longPress);
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            down(e);
            if (physics && down != null && !downDock && down.view == null) grab = down;
        }
        if (vt != null) vt.addMovement(e);
        if (act == MotionEvent.ACTION_MOVE) {
            fx = e.getX(); fy = e.getY();
            float dx = fx - downX, dy = fy - downY;
            if (!moved && !longDone && Math.hypot(dx, dy) > slop) {
                moved = true;
                removeCallbacks(longPress);
                if (grab == null) {
                    if (physics && down != null && !downDock) grab = down;
                    else startPan(dx, dy);
                }
            }
            if (panning && !vertical) camX = camT = Math.max(0, Math.min((pages - 1) * vw, camStart - dx));
            wake();
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            boolean up = act == MotionEvent.ACTION_UP;
            if (up && !moved && !longDone && down != null && down.view == null
                    && SystemClock.uptimeMillis() - downT < 500) {
                host.launch(down, downDock ? down.x : down.x - camX, down.y);
            } else if (panning) {
                float dy = fy - downY;
                if (vertical) {
                    if (up && Math.abs(dy) > 70 * dp) host.onSwipe(dy < 0);
                } else if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getXVelocity(), f = camX / vw;
                    int p = Math.round(f);
                    if (v < -600 * dp) p = (int) Math.floor(f) + 1;
                    else if (v > 600 * dp) p = (int) Math.floor(f);
                    goPage(p);
                }
            }
            grab = down = null;
            panning = false;
            if (vt != null) { vt.recycle(); vt = null; }
            wake();
        }
        return true;
    }
}
