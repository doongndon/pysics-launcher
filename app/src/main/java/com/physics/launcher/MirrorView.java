package com.physics.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import java.util.*;

/**
 * 홈 화면 위에 덮는 '거울'. 홈 화면을 찍은 사진에서 아이콘·위젯을 오려 낸 조각들이 물리로 움직인다.
 * x는 페이지가 옆으로 이어진 세계 좌표(지금 페이지 = 0), y는 이 화면 기준 좌표.
 */
public class MirrorView extends View implements Choreographer.FrameCallback {

    interface Listener {
        void onTapPiece(Piece p);
        void onSwipe(boolean up);
        void onRestoreRequest();
    }

    static class Piece {
        float x, y, vx, vy, a, w, r, hw, hh, im, ii, homeX, homeY, ta;
        boolean circle, widget, placed;
        int page;
        final Rect src = new Rect(), screen = new Rect(), hole = new Rect();
        String label;
        ComponentName app;
    }

    static class Shot {
        int page;
        Bitmap bmp;
        final Rect win = new Rect();   // 찍은 창의 위치(화면 좌표)
        final Rect clip = new Rect();  // 조각 말고 남은 부분을 그릴 영역(화면 좌표)
    }

    final ArrayList<Piece> pieces = new ArrayList<>();
    final ArrayList<Shot> shots = new ArrayList<>();
    Listener ls;
    final Bitmap bg;
    final Rect bgSrc = new Rect();
    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF dst = new RectF();
    final Piece WALL = new Piece();
    final Random rnd = new Random();
    final float dp;
    final int slop, screenW;

    int top = -1, minPage, maxPage, idle;
    float vw, vh, camX, camT, gx = 0, gy = 9.8f, sgx, sgy;
    boolean running, returning;
    long last, edgeT;
    Runnable onHome;

    Piece grab;
    float fx, fy, downX, downY, camStart;
    long downT;
    boolean moved, panning;
    VelocityTracker vt;

    final Runnable longPress = () -> {
        if (!moved && grab == null && panning) {
            panning = false;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ls.onRestoreRequest();
        }
    };

    public MirrorView(Context c, Listener l, Bitmap bg, int screenW) {
        super(c);
        ls = l;
        this.bg = bg;
        this.screenW = screenW;
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        vw = screenW;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ---------- 조각 관리 ----------

    void addPage(Shot s, List<Piece> ps) {
        shots.add(s);
        minPage = Math.min(minPage, s.page);
        maxPage = Math.max(maxPage, s.page);
        for (Piece p : ps) { pieces.add(p); if (top >= 0) place(p); }
        wake();
    }

    Bitmap replacePage(int page, Bitmap b) {
        for (Shot s : shots) if (s.page == page) {
            Bitmap old = s.bmp;
            s.bmp = b;
            invalidate();
            return old;
        }
        return b;
    }

    void place(Piece p) {
        p.homeX = p.screen.exactCenterX() + p.page * vw;
        p.homeY = p.screen.exactCenterY() - top;
        p.x = p.homeX; p.y = p.homeY;
        if (p.widget) {
            p.circle = false;
            p.hw = p.screen.width() / 2f; p.hh = p.screen.height() / 2f;
            p.im = 1000f / (4 * p.hw * p.hh); p.ii = 0;
        } else {
            p.circle = true;
            p.r = Math.min(p.screen.width(), p.screen.height()) * 0.48f;
            float m = p.r * p.r / 1000f;
            p.im = 1 / m; p.ii = 1 / (0.5f * m * p.r * p.r);
            p.w = (rnd.nextFloat() - 0.5f) * 3;
            p.vx = (rnd.nextFloat() - 0.5f) * 60 * dp;
        }
        p.placed = true;
    }

    Shot shotOf(int page) {
        for (Shot s : shots) if (s.page == page) return s;
        return null;
    }

    void release() {
        running = false;
        for (Shot s : shots) if (s.bmp != null && s.bmp != bg) s.bmp.recycle();
        shots.clear();
        pieces.clear();
    }

    void kick(Piece p) {
        p.vy -= 900 * dp;
        p.w += (rnd.nextFloat() - 0.5f) * 16;
        wake();
    }

    void setGravity(float x, float y) {
        gx += (x - gx) * 0.3f;
        gy += (y - gy) * 0.3f;
        if (!running && Math.hypot(gx - sgx, gy - sgy) > 0.8) wake();
    }

    void returnHome(Runnable done) {
        if (top < 0) { done.run(); return; }
        returning = true;
        grab = null; panning = false;
        removeCallbacks(longPress);
        double tau = Math.PI * 2;
        for (Piece p : pieces) {
            p.ta = (float) (Math.round(p.a / tau) * tau);
            p.vx = p.vy = p.w = 0;
        }
        onHome = done;
        wake();
    }

    void goPage(int p) {
        p = Math.max(minPage, Math.min(maxPage, p));
        camT = p * vw;
        wake();
    }

    // ---------- 루프 ----------

    void wake() {
        idle = 0;
        if (!running && top >= 0) {
            running = true; last = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override
    public void doFrame(long t) {
        if (!running) return;
        float dt = last == 0 ? 1 / 60f : Math.min((t - last) / 1e9f, 1 / 30f);
        last = t;

        if (returning) {
            float k = 1 - (float) Math.exp(-dt * 11);
            camX += -camX * k; camT = 0;
            boolean done = Math.abs(camX) < 0.6f;
            for (Piece p : pieces) {
                p.x += (p.homeX - p.x) * k;
                p.y += (p.homeY - p.y) * k;
                p.a += (p.ta - p.a) * k;
                if (Math.abs(p.homeX - p.x) > 0.6f || Math.abs(p.homeY - p.y) > 0.6f || Math.abs(p.ta - p.a) > 0.01f) done = false;
            }
            invalidate();
            if (done) {
                running = false;
                Runnable r = onHome; onHome = null;
                if (r != null) r.run();
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        for (int i = 0; i < 3; i++) step(dt / 3);
        if (!panning) camX += (camT - camX) * Math.min(1, dt * 14);
        edgeScroll();
        invalidate();

        float maxV = 0;
        for (Piece p : pieces) maxV = Math.max(maxV, p.vx * p.vx + p.vy * p.vy);
        boolean still = grab == null && !panning && Math.abs(camX - camT) < 1 && maxV < 225 * dp * dp;
        idle = still ? idle + 1 : 0;
        if (idle > 45) {
            running = false;
            for (Piece p : pieces) p.vx = p.vy = p.w = 0;
            sgx = gx; sgy = gy;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    void edgeScroll() {
        if (grab == null) { edgeT = 0; return; }
        int page = Math.round(camT / vw);
        float e = 28 * dp;
        int dir = fx < e ? -1 : fx > vw - e ? 1 : 0;
        if (dir == 0 || page + dir < minPage || page + dir > maxPage) { edgeT = 0; return; }
        long now = SystemClock.uptimeMillis();
        if (edgeT == 0) edgeT = now;
        else if (now - edgeT > 450) { goPage(page + dir); edgeT = 0; }
    }

    // ---------- 물리 ----------

    void step(float h) {
        float L = minPage * vw, R = (maxPage + 1) * vw;
        float ax = gx * 120 * dp, ay = gy * 120 * dp, cap = 3000 * dp;
        float drag = 1 - 0.3f * h, spin = 1 - 1.2f * h;
        for (Piece b : pieces) {
            if (!b.placed) continue;
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
        int n = pieces.size();
        for (int it = 0; it < 2; it++) {
            for (int i = 0; i < n; i++) {
                Piece A = pieces.get(i);
                if (!A.placed) continue;
                for (int j = i + 1; j < n; j++) {
                    Piece B = pieces.get(j);
                    if (B.placed) collide(A, B);
                }
                walls(A, L, R);
            }
        }
    }

    // 막힌 벽: 첫 페이지 왼쪽 끝, 마지막 페이지 오른쪽 끝, 위, 아래. 페이지 사이는 열려 있음
    void walls(Piece b, float L, float R) {
        float ex = b.circle ? b.r : b.hw, ey = b.circle ? b.r : b.hh, k = b.circle ? b.r : 0;
        if (b.x - ex < L) resolve(b, WALL, -1, 0, L - (b.x - ex), -k, 0, 0, 0);
        if (b.x + ex > R) resolve(b, WALL, 1, 0, b.x + ex - R, k, 0, 0, 0);
        if (b.y - ey < 0) resolve(b, WALL, 0, -1, ey - b.y, 0, -k, 0, 0);
        if (b.y + ey > vh) resolve(b, WALL, 0, 1, b.y + ey - vh, 0, k, 0, 0);
    }

    void collide(Piece A, Piece B) {
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

    void circBox(Piece C, Piece X) {
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

    void resolve(Piece A, Piece B, float nx, float ny, float pen,
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

    static void apply(Piece A, Piece B, float px, float py, float rax, float ray, float rbx, float rby) {
        A.vx -= px * A.im; A.vy -= py * A.im; A.w -= (rax * py - ray * px) * A.ii;
        B.vx += px * B.im; B.vy += py * B.im; B.w += (rbx * py - rby * px) * B.ii;
    }

    // ---------- 그리기 ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vw = w; vh = h;
        post(() -> {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            top = loc[1];
            if (bg != null) {
                float s = bg.getWidth() / (float) screenW;
                bgSrc.set(0, Math.round(top * s), bg.getWidth(), Math.round((top + vh) * s));
            }
            for (Piece p : pieces) if (!p.placed) place(p);
            wake();
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        if (top < 0) return;
        if (bg != null) { dst.set(0, 0, vw, vh); c.drawBitmap(bg, bgSrc, dst, paint); }

        // 조각이 빠진 나머지(페이지 표시 점 등)는 제자리에 그대로
        for (Shot s : shots) {
            float ox = s.page * vw - camX;
            if (ox <= -vw || ox >= vw || s.bmp == null || s.bmp.isRecycled()) continue;
            c.save();
            c.translate(ox, -top);
            c.clipRect(s.clip);
            for (Piece p : pieces) if (p.page == s.page) c.clipOutRect(p.hole);
            dst.set(s.win);
            c.drawBitmap(s.bmp, null, dst, paint);
            c.restore();
        }

        for (Piece p : pieces) {
            if (!p.placed || p == grab) continue;
            drawPiece(c, p);
        }
        if (grab != null) drawPiece(c, grab);

        if (maxPage > minPage && Math.abs(camX) > 1) {
            int cur = Math.round(camX / vw);
            float cx = vw / 2, y = vh - 20 * dp;
            int n = maxPage - minPage + 1;
            for (int i = minPage; i <= maxPage; i++) {
                dot.setColor(i == cur ? 0xFFFFFFFF : 0x80FFFFFF);
                c.drawCircle(cx + (i - minPage - (n - 1) / 2f) * 14 * dp, y, 3.5f * dp, dot);
            }
        }
    }

    void drawPiece(Canvas c, Piece p) {
        Shot s = shotOf(p.page);
        if (s == null || s.bmp == null || s.bmp.isRecycled()) return;
        float sx = p.x - camX, hw = p.screen.width() / 2f, hh = p.screen.height() / 2f;
        float ext = hw + hh;
        if (sx < -ext || sx > vw + ext) return;
        c.save();
        c.rotate(p.a * 57.29578f, sx, p.y);
        dst.set(sx - hw, p.y - hh, sx + hw, p.y + hh);
        c.drawBitmap(s.bmp, p.src, dst, paint);
        c.restore();
    }

    // ---------- 터치 ----------

    Piece hit(float sx, float sy) {
        float x = sx + camX;
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece b = pieces.get(i);
            if (b.placed && b.circle && (x - b.x) * (x - b.x) + (sy - b.y) * (sy - b.y) < b.r * b.r * 1.2f) return b;
        }
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece b = pieces.get(i);
            if (b.placed && !b.circle && Math.abs(x - b.x) < b.hw && Math.abs(sy - b.y) < b.hh) return b;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (returning || top < 0) return true;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            wake();
            downX = fx = e.getX(); downY = fy = e.getY();
            downT = SystemClock.uptimeMillis();
            moved = panning = false;
            camStart = camX;
            if (vt != null) vt.recycle();
            vt = VelocityTracker.obtain();
            grab = hit(downX, downY);
            if (grab == null) { panning = true; postDelayed(longPress, 550); }
        }
        if (vt != null) vt.addMovement(e);
        if (act == MotionEvent.ACTION_MOVE) {
            fx = e.getX(); fy = e.getY();
            if (!moved && Math.hypot(fx - downX, fy - downY) > slop) { moved = true; removeCallbacks(longPress); }
            if (panning && moved) camX = camT = Math.max(minPage * vw, Math.min(maxPage * vw, camStart - (fx - downX)));
            wake();
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            boolean up = act == MotionEvent.ACTION_UP;
            if (grab != null) {
                Piece g = grab;
                grab = null;
                if (up && !moved && SystemClock.uptimeMillis() - downT < 350) ls.onTapPiece(g);
            } else if (panning && vt != null) {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity(), dx = fx - downX, dy = fy - downY;
                if (up && Math.abs(dy) > 90 * dp && Math.abs(dy) > 1.6f * Math.abs(dx)) {
                    camX = camT = camStart;
                    panning = false;
                    ls.onSwipe(dy < 0);
                } else {
                    float f = camX / vw;
                    int p = Math.round(f);
                    if (vx < -600 * dp) p = (int) Math.floor(f) + 1;
                    else if (vx > 600 * dp) p = (int) Math.floor(f);
                    goPage(p);
                }
            }
            panning = false;
            if (vt != null) { vt.recycle(); vt = null; }
            wake();
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) ls.onRestoreRequest();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
