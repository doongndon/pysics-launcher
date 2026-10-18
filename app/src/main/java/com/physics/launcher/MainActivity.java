package com.physics.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.appwidget.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener, World.Host {
    static final int HOST_ID = 2024, REQ_BIND = 1, REQ_CONF = 2, REQ_ROLE = 3, MAX_DOCK = 5;

    World world;
    FrameLayout root, drawer;
    GridView grid;
    EditText search;
    BaseAdapter adapter;
    AppWidgetHost host;
    AppWidgetManager awm;
    SharedPreferences sp;
    SensorManager sm;
    Sensor grav, lin;
    int pendingId = -1;
    AppWidgetProviderInfo pendingInfo;
    long firstPeak, lastPeak, lastShake;
    int peaks;
    float dp;

    final ArrayList<ResolveInfo> apps = new ArrayList<>(), shown = new ArrayList<>();
    final HashMap<String, String> labels = new HashMap<>();
    final HashMap<String, Drawable> icons = new HashMap<>();

    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { icons.clear(); loadApps(); }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Window w = getWindow();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.setDecorFitsSystemWindows(false);
        dp = getResources().getDisplayMetrics().density;

        sp = getSharedPreferences("p", 0);
        awm = AppWidgetManager.getInstance(this);
        host = new AppWidgetHost(this, HOST_ID);
        world = new World(this);
        world.host = this;
        world.tilt = sp.getBoolean("tilt", true);
        world.physics = sp.getBoolean("phys", true);
        world.minPages = sp.getInt("pages", 2);
        root = new FrameLayout(this);
        root.addView(world);
        buildDrawer();
        setContentView(root);

        loadApps();
        for (String id : sp.getString("widgets", "").split(","))
            if (!id.isEmpty()) addWidgetView(Integer.parseInt(id));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pkgRx, f);

        sm = getSystemService(SensorManager.class);
        grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (grav == null) grav = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    }

    @Override protected void onStart() { super.onStart(); host.startListening(); }
    @Override protected void onStop() { super.onStop(); try { host.stopListening(); } catch (Exception ignored) { } }

    @Override
    protected void onResume() {
        super.onResume();
        if (grav != null) sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME);
        if (lin != null) sm.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME);
        world.resume();
        askDefaultHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        world.pause();
    }

    @Override protected void onDestroy() { super.onDestroy(); unregisterReceiver(pkgRx); }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (drawerOpen()) closeDrawer(); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (drawerOpen()) closeDrawer();
        else world.goPage(0);
    }

    void askDefaultHome() {
        if (sp.getBoolean("askedHome", false)) return;
        sp.edit().putBoolean("askedHome", true).apply();
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME))
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE);
    }

    // ---------- 센서: 기울기, 흔들기 ----------

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        if (e.sensor == grav) { world.setGravity(-v[0], v[1]); return; }
        float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        long now = SystemClock.uptimeMillis();
        if (m > 15 && now - lastPeak > 90) {
            if (now - firstPeak > 800) { firstPeak = now; peaks = 0; }
            peaks++;
            lastPeak = now;
            if (peaks >= 3 && now - lastShake > 1500 && !drawerOpen()) {
                lastShake = now;
                peaks = 0;
                setPhysics(!world.physics);
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    void setPhysics(boolean on) {
        world.setPhysics(on);
        sp.edit().putBoolean("phys", on).apply();
        world.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    // ---------- 앱 ----------

    void loadApps() {
        PackageManager pm = getPackageManager();
        apps.clear();
        labels.clear();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            apps.add(r);
            labels.put(key(r), r.loadLabel(pm).toString());
        }
        apps.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(labels.get(key(a)), labels.get(key(b))));

        String dockS = sp.getString("dock", null);
        if (dockS == null) { dockS = defaultDock(); sp.edit().putString("dock", dockS).apply(); }
        List<String> dockKeys = Arrays.asList(dockS.split("\n"));
        Set<String> home = sp.getStringSet("apps", null);
        if (home == null) {
            home = new HashSet<>();
            for (ResolveInfo r : apps) if (home.size() < 16 && !dockKeys.contains(key(r))) home.add(key(r));
            sp.edit().putStringSet("apps", home).apply();
        }

        int size = Math.round(world.iconR * 2);
        HashMap<String, ResolveInfo> byKey = new HashMap<>();
        for (ResolveInfo r : apps) byKey.put(key(r), r);
        ArrayList<World.Body> list = new ArrayList<>(), dockList = new ArrayList<>();
        for (String k : home) {
            ResolveInfo r = byKey.get(k);
            if (r != null) list.add(world.icon(k, labels.get(k), bitmap(icon(r), size), launchIntent(r)));
        }
        for (String k : dockKeys) {
            ResolveInfo r = byKey.get(k);
            if (r != null) dockList.add(world.icon(k, labels.get(k), bitmap(icon(r), size), launchIntent(r)));
        }
        world.setDock(dockList);
        world.syncApps(list);
        filter();
    }

    String defaultDock() {
        PackageManager pm = getPackageManager();
        Intent[] want = {
                new Intent(Intent.ACTION_DIAL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)};
        ArrayList<String> out = new ArrayList<>();
        for (Intent i : want) {
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            for (ResolveInfo r : apps)
                if (r.activityInfo.packageName.equals(pkg) && !out.contains(key(r))) { out.add(key(r)); break; }
        }
        return TextUtils.join("\n", out);
    }

    static String key(ResolveInfo r) { return r.activityInfo.packageName + "/" + r.activityInfo.name; }

    Drawable icon(ResolveInfo r) {
        String k = key(r);
        Drawable d = icons.get(k);
        if (d == null) { d = r.loadIcon(getPackageManager()); icons.put(k, d); }
        return d;
    }

    static Intent launchIntent(ResolveInfo r) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    static Bitmap bitmap(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, s, s);
        d.draw(new Canvas(b));
        return b;
    }

    @Override
    public void launch(World.Body b, float sx, float sy) {
        int r = Math.round(world.iconR);
        start(b.launch, ActivityOptions.makeScaleUpAnimation(world, (int) sx - r, (int) sy - r, 2 * r, 2 * r));
    }

    void start(Intent i, ActivityOptions o) {
        try { startActivity(i, o == null ? null : o.toBundle()); }
        catch (Exception e) { Toast.makeText(this, "앱을 열지 못했어요", Toast.LENGTH_SHORT).show(); }
    }

    /** 앱 길게 누르기 메뉴(홈, 독, 앱 서랍 공통) */
    void appOptions(String k) {
        Set<String> home = new HashSet<>(sp.getStringSet("apps", new HashSet<>()));
        ArrayList<String> dock = new ArrayList<>(Arrays.asList(sp.getString("dock", "").split("\n")));
        dock.remove("");
        boolean onHome = home.contains(k), inDock = dock.contains(k);
        String[] items = {onHome ? "홈 화면에서 빼기" : "홈 화면에 추가", inDock ? "독에서 빼기" : "독에 추가", "앱 정보"};
        dialog().setTitle(labels.get(k)).setItems(items, (d, i) -> {
            if (i == 0) {
                if (onHome) home.remove(k); else home.add(k);
                sp.edit().putStringSet("apps", home).apply();
                loadApps();
            } else if (i == 1) {
                if (inDock) dock.remove(k);
                else if (dock.size() >= MAX_DOCK) { Toast.makeText(this, "독에는 " + MAX_DOCK + "개까지 넣을 수 있어요", Toast.LENGTH_SHORT).show(); return; }
                else dock.add(k);
                sp.edit().putString("dock", TextUtils.join("\n", dock)).apply();
                loadApps();
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + k.substring(0, k.indexOf('/')))));
            }
        }).show();
    }

    @Override
    public void onItemMenu(World.Body b, boolean inDock) {
        if (b.view == null) { appOptions(b.key); return; }
        dialog().setTitle(b.label).setItems(new String[]{"위젯 빼기"}, (d, i) -> {
            world.removeWidget(b.wid);
            host.deleteAppWidgetId(b.wid);
            saveWidgets();
        }).show();
    }

    @Override
    public void onSwipe(boolean up) {
        if (up) openDrawer();
        else expandNotifications();
    }

    void expandNotifications() {
        try {
            Object s = getSystemService("statusbar");
            s.getClass().getMethod("expandNotificationsPanel").invoke(s);
        } catch (Exception ignored) { }
    }

    // ---------- 앱 서랍 ----------

    void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF2101014);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setHint("앱 검색");
        search.setSingleLine();
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0x99FFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(24));
        search.setBackground(bg);
        search.setPadding(px(18), px(10), px(18), px(10));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { filter(); }
        });

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(px(16));
        grid.setSelector(new ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(px(8), px(4), px(8), px(16));
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout l = (LinearLayout) cv;
                if (l == null) {
                    l = new LinearLayout(MainActivity.this);
                    l.setOrientation(LinearLayout.VERTICAL);
                    l.setGravity(Gravity.CENTER_HORIZONTAL);
                    l.addView(new ImageView(MainActivity.this), new LinearLayout.LayoutParams(px(54), px(54)));
                    TextView t = new TextView(MainActivity.this);
                    t.setTextColor(0xFFFFFFFF);
                    t.setTextSize(12);
                    t.setSingleLine();
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
                    tl.topMargin = px(6);
                    l.addView(t, tl);
                }
                ResolveInfo r = shown.get(i);
                ((ImageView) l.getChildAt(0)).setImageDrawable(icon(r));
                ((TextView) l.getChildAt(1)).setText(labels.get(key(r)));
                return l;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) -> {
            Intent in = launchIntent(shown.get(i));
            start(in, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()));
            closeDrawer();
        });
        grid.setOnItemLongClickListener((p, v, i, id) -> { appOptions(key(shown.get(i))); return true; });
        float[] y0 = {0};
        grid.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) y0[0] = e.getY();
            else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !grid.canScrollVertically(-1)
                    && e.getY() - y0[0] > px(110)) closeDrawer();
            return false;
        });

        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(px(20), px(14), px(20), px(14));
        col.addView(search, sl);
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        drawer.addView(col);
        drawer.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(0, i.top, 0, i.bottom);
            return in;
        });
        root.addView(drawer);
    }

    void filter() {
        if (adapter == null) return;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (ResolveInfo r : apps)
            if (q.isEmpty() || labels.get(key(r)).toLowerCase(Locale.ROOT).contains(q)) shown.add(r);
        adapter.notifyDataSetChanged();
    }

    boolean drawerOpen() { return drawer.getVisibility() == View.VISIBLE && drawer.getTag() == null; }

    void openDrawer() {
        search.setText("");
        grid.setSelection(0);
        drawer.setTag(null);
        drawer.animate().cancel();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationY(root.getHeight());
        drawer.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
    }

    void closeDrawer() {
        if (!drawerOpen()) return;
        drawer.setTag("closing");
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        drawer.animate().cancel();
        drawer.animate().translationY(root.getHeight()).setDuration(200)
                .withEndAction(() -> { drawer.setVisibility(View.GONE); drawer.setTag(null); }).start();
    }

    // ---------- 위젯 ----------

    void pickWidget() {
        PackageManager pm = getPackageManager();
        List<AppWidgetProviderInfo> ps = awm.getInstalledProviders();
        String[] names = new String[ps.size()];
        for (int i = 0; i < ps.size(); i++) {
            AppWidgetProviderInfo p = ps.get(i);
            String app;
            try { app = pm.getApplicationLabel(pm.getApplicationInfo(p.provider.getPackageName(), 0)).toString(); }
            catch (Exception e) { app = p.provider.getPackageName(); }
            names[i] = app + " · " + p.loadLabel(pm);
        }
        Integer[] idx = new Integer[names.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(names[a], names[b]));
        String[] sorted = new String[names.length];
        for (int i = 0; i < idx.length; i++) sorted[i] = names[idx[i]];
        dialog().setTitle("위젯 추가").setItems(sorted, (d, i) -> {
            pendingInfo = ps.get(idx[i]);
            pendingId = host.allocateAppWidgetId();
            if (awm.bindAppWidgetIdIfAllowed(pendingId, pendingInfo.provider)) configure();
            else startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pendingInfo.provider), REQ_BIND);
        }).show();
    }

    void configure() {
        if (pendingInfo.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQ_CONF, null); return; }
            catch (Exception ignored) { }
        }
        finishWidget();
    }

    void finishWidget() {
        addWidgetView(pendingId);
        saveWidgets();
        pendingId = -1;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ROLE || pendingId == -1) return;
        if (res != RESULT_OK) {
            host.deleteAppWidgetId(pendingId);
            pendingId = -1;
            Toast.makeText(this, "위젯을 추가하지 못했어요 (허용 창에서 '만들기'를 눌러 주세요)", Toast.LENGTH_LONG).show();
            return;
        }
        if (req == REQ_BIND) configure();
        else if (req == REQ_CONF) finishWidget();
    }

    @SuppressWarnings("deprecation")
    void addWidgetView(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { Toast.makeText(this, "위젯 정보를 찾지 못했어요", Toast.LENGTH_SHORT).show(); return; }
        AppWidgetHostView v = host.createView(getApplicationContext(), id, info);
        // 홈 화면 칸(가로 4칸, 세로 약 100dp) 기준으로 위젯이 원하는 칸 수만큼 크기를 잡음
        float cw = getResources().getDisplayMetrics().widthPixels / 4f, ch = px(100);
        int sx = info.targetCellWidth > 0 ? info.targetCellWidth : (int) Math.ceil((info.minWidth + px(16)) / cw);
        int sy = info.targetCellHeight > 0 ? info.targetCellHeight : (int) Math.ceil((info.minHeight + px(16)) / ch);
        sx = Math.max(1, Math.min(4, sx));
        sy = Math.max(1, Math.min(4, sy));
        int w = Math.round(sx * cw - px(8));
        int h = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.45f), Math.round(sy * ch - px(8)));
        v.updateAppWidgetSize(null, Collections.singletonList(new android.util.SizeF(w / dp, h / dp)));
        world.addWidget(id, v, w, h, info.loadLabel(getPackageManager()));
    }

    void saveWidgets() {
        StringBuilder b = new StringBuilder();
        for (World.Body x : world.bodies) if (x.view != null) b.append(x.wid).append(',');
        sp.edit().putString("widgets", b.toString()).apply();
    }

    // ---------- 메뉴 (빈 곳 길게 누르기) ----------

    @Override
    public void onMenu() {
        String[] items = {"위젯 추가", "홈 화면에 둘 앱 고르기",
                world.physics ? "정리하기 (아이콘 제자리로)" : "물리 켜기 (아이콘 떨어뜨리기)",
                "기울기 중력: " + (world.tilt ? "켜짐" : "꺼짐"),
                "페이지 추가 (지금 최소 " + world.minPages + "쪽)", "페이지 빼기",
                "배경화면 바꾸기", "홈 화면 앱 바꾸기", "휴대폰 설정"};
        dialog().setItems(items, (d, i) -> {
            switch (i) {
                case 0: pickWidget(); break;
                case 1: chooseApps(); break;
                case 2: setPhysics(!world.physics); break;
                case 3:
                    world.tilt = !world.tilt;
                    sp.edit().putBoolean("tilt", world.tilt).apply();
                    break;
                case 4: case 5:
                    world.minPages = Math.max(1, Math.min(7, world.minPages + (i == 4 ? 1 : -1)));
                    sp.edit().putInt("pages", world.minPages).apply();
                    world.layoutHome();
                    break;
                case 6: start(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "배경화면"), null); break;
                case 7: start(new Intent(Settings.ACTION_HOME_SETTINGS), null); break;
                case 8: start(new Intent(Settings.ACTION_SETTINGS), null); break;
            }
        }).show();
    }

    void chooseApps() {
        Set<String> cur = sp.getStringSet("apps", new HashSet<>());
        String[] names = new String[apps.size()];
        boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = labels.get(key(apps.get(i)));
            on[i] = cur.contains(key(apps.get(i)));
        }
        dialog().setTitle("홈 화면에 둘 앱")
                .setMultiChoiceItems(names, on, (d, i, v) -> on[i] = v)
                .setPositiveButton("확인", (d, x) -> {
                    HashSet<String> set = new HashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) set.add(key(apps.get(i)));
                    sp.edit().putStringSet("apps", set).apply();
                    loadApps();
                })
                .setNegativeButton("취소", null).show();
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    int px(float v) { return Math.round(v * dp); }
}
