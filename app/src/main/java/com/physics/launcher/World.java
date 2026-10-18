package com.physics.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;

/** 여러 페이지가 이어진 하나의 물리 세계. 아이콘은 원, 위젯은 상자. */
public class World extends FrameLayout implements Choreographer.FrameCallback {

    static class Body {
        float x, y, vx, vy, a, w, r, hw, hh, im, ii;
        boolean circle;
        String key, label;
        Bitmap bmp;
        Intent launch;
        View view;
        int wid;
    }

    final ArrayList<Body> bodies = new ArrayList<>();
    final Body WALL = new Body();
    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    final Random rnd = new Random();
    final float dp, iconR;
    final int slop;

    int pages = 3, page = 0, idle;
    float vw, vh, camX, camT, gx = 0, gy = 9.8f, sgx, sgy;
    boolean tilt = true, running, paused;
    long last, edgeT;
    Runnable onMenu;

    Body grab, down;
    float fx, fy, downX, downY, camStart;
    long downT;
    boolean moved, panning;
    VelocityTracker vt;

    final Runnable longPress = () -> {
        if (!moved && grab == null) {
            panning = false;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (onMenu != null) onMenu.run();
        }
    };

    public World(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        vw = getResources().getDisplayMetrics().widthPixels;
        vh = getResources().getDisplayMetrics().heightPixels;
        iconR = 30 * dp;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        ui.setTextAlign(Paint.Align.CENTER);
        ui.setTextSize(14 * dp);
        ui.setShadowLayer(4 * dp, 0, 0, 0xFF000000);
    }

    // ---------- 몸체 관리 ----------

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
        for (Body b : want.values()) spawn(b);
        wake();
    }

    void addWidget(int id, View v, int w, int h, String label) {
        Body b = new Body();
        b.hw = w / 2f; b.hh = h / 2f; b.view = v; b.wid = id; b.label = label;
        b.im = 1000f / (w * h);
        addView(v, new LayoutParams(w, h));
        spawn(b);
        wake();
    }

    void removeWidget(int id) {
        for (Iterator<Body> it = bodies.iterator(); it.hasNext(); ) {
            Body b = it.next();
            if (b.view != null && b.wid == id) { removeView(b.view); it.remove(); }
        }
    }

    void spawn(Body b) {
        float ex = b.circle ? b.r : b.hw, ey = b.circle ? b.r : b.hh;
        b.x = page * vw + ex + rnd.nextFloat() * Math.max(1, vw - 2 * ex);
        b.y = ey + rnd.nextFloat() * Math.max(1, vh * 0.3f);
        bodies.add(b);
    }

    void setPages(int n) {
        pages = Math.max(1, Math.min(7, n));
        goPage(page);
        camX = camT;
        float W = pages * vw;
        for (Body b : bodies) {
            float ex = b.circle ? b.r : b.hw;
            if (b.x > W - ex) b.x = W - ex - rnd.nextFloat() * vw * 0.5f;
        }
    }

    void goPage(int p) {
        page = Math.max(0, Math.min(pages - 1, p));
        camT = page * vw;
        wake();
    }

    void shake() {
        for (Body b : bodies) {
            b.vx += (rnd.nextFloat() - 0.5f) * 2400 * dp;
            b.vy -= rnd.nextFloat() * 1600 * dp;
            b.w += (rnd.nextFloat() - 0.5f) * 20;
        }
        wake();
    }

    void setGravity(float x, float y) {
        if (!tilt) { x = 0; y = 9.8f; }
        gx += (x - gx) * 0.3f;
        gy += (y - gy) * 0.3f;
        if (!running && Math.hypot(gx - sgx, gy - sgy) > 0.8) wake();
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
        for (int i = 0; i < 3; i++) step(dt / 3);
        if (!panning) camX += (camT - camX) * Math.min(1, dt * 14);
        edgeScroll();

        float maxV = 0;
        for (Body b : bodies) {
            maxV = Math.max(maxV, b.vx * b.vx + b.vy * b.vy);
            if (b.view != null) {
                b.view.setTranslationX(b.x - b.hw - camX);
                b.view.setTranslationY(b.y - b.hh);
            }
        }
        invalidate();
        boolean still = grab == null && !panning && Math.abs(camX - camT) < 1 && maxV < 225 * dp * dp;
        idle = still ? idle + 1 : 0;
        if (idle > 45) {
            running = false;
            for (Body b : bodies) { b.vx = b.vy = b.w = 0; }
            sgx = gx; sgy = gy;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
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

    // 바깥 벽: 세계의 양 끝(첫 페이지 왼쪽, 마지막 페이지 오른쪽)과 위아래만 막힘
    void walls(Body b, float W) {
        float ex = b.circle ? b.r : b.hw, ey = b.circle ? b.r : b.hh, k = b.circle ? b.r : 0;
        if (b.x - ex < 0) resolve(b, WALL, -1, 0, ex - b.x, -k, 0, 0, 0);
        if (b.x + ex > W) resolve(b, WALL, 1, 0, b.x + ex - W, k, 0, 0, 0);
        if (b.y - ey < 0) resolve(b, WALL, 0, -1, ey - b.y, 0, -k, 0, 0);
        if (b.y + ey > vh) resolve(b, WALL, 0, 1, b.y + ey - vh, 0, k, 0, 0);
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

    // n은 A에서 B를 향하는 방향, r은 각 중심에서 접점까지
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
        camX = camT = page * vw;
        wake();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        for (Body b : bodies) {
            if (b.bmp == null) continue;
            float sx = b.x - camX;
            if (sx < -b.r || sx > vw + b.r) continue;
            c.save();
            c.rotate(b.a * 57.29578f, sx, b.y);
            rect.set(sx - b.r, b.y - b.r, sx + b.r, b.y + b.r);
            c.drawBitmap(b.bmp, null, rect, paint);
            c.restore();
        }
        if (grab != null && grab.label != null) {
            ui.setColor(0xFFFFFFFF);
            float ey = grab.circle ? grab.r : grab.hh;
            c.drawText(grab.label, grab.x - camX, Math.max(20 * dp, grab.y - ey - 10 * dp), ui);
        }
        if (pages > 1) {
            for (int i = 0; i < pages; i++) {
                ui.setColor(i == page ? 0xFFFFFFFF : 0x66FFFFFF);
                c.drawCircle(vw / 2 + (i - (pages - 1) / 2f) * 16 * dp, vh - 40 * dp, 4 * dp, ui);
            }
        }
    }

    // ---------- 터치 ----------

    Body hit(float sx, float sy) {
        float x = sx + camX;
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (b.circle && (x - b.x) * (x - b.x) + (sy - b.y) * (sy - b.y) < b.r * b.r) return b;
        }
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (!b.circle && Math.abs(x - b.x) < b.hw && Math.abs(sy - b.y) < b.hh) return b;
        }
        return null;
    }

    // 위젯 위에서는 살짝 탭하면 위젯이 받고, 끌기 시작하면 물리로 가져옴
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            down(e);
            return down == null || down.view == null;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE && down != null && down.view != null
                && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
            moved = true; grab = down; fx = e.getX(); fy = e.getY();
            wake();
            return true;
        }
        return false;
    }

    void down(MotionEvent e) {
        wake();
        downX = fx = e.getX(); downY = fy = e.getY();
        downT = SystemClock.uptimeMillis();
        down = hit(downX, downY);
        moved = panning = false; grab = null;
        camStart = camX;
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            down(e);
            if (down != null && down.view == null) grab = down;
            else if (down == null) { panning = true; postDelayed(longPress, 500); }
        }
        if (vt != null) vt.addMovement(e);
        if (act == MotionEvent.ACTION_MOVE) {
            fx = e.getX(); fy = e.getY();
            if (!moved && Math.hypot(fx - downX, fy - downY) > slop) { moved = true; removeCallbacks(longPress); }
            if (panning && moved) camX = camT = Math.max(0, Math.min((pages - 1) * vw, camStart - (fx - downX)));
            if (down != null && down.view != null && moved) grab = down;
            wake();
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            if (grab != null) {
                if (act == MotionEvent.ACTION_UP && !moved && grab.launch != null
                        && SystemClock.uptimeMillis() - downT < 400) {
                    try { getContext().startActivity(grab.launch); } catch (Exception ignored) { }
                }
            } else if (panning && vt != null) {
                vt.computeCurrentVelocity(1000);
                float v = vt.getXVelocity(), f = camX / vw;
                int p = Math.round(f);
                if (v < -600 * dp) p = (int) Math.floor(f) + 1;
                else if (v > 600 * dp) p = (int) Math.floor(f);
                goPage(p);
            }
            grab = down = null; panning = false;
            if (vt != null) { vt.recycle(); vt = null; }
            wake();
        }
        return true;
    }
}
