package com.physics.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.hardware.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener {
    static final int HOST_ID = 2024, REQ_BIND = 1, REQ_CONF = 2;

    World world;
    AppWidgetHost host;
    AppWidgetManager awm;
    SharedPreferences sp;
    SensorManager sm;
    Sensor sensor;
    int pendingId = -1;
    AppWidgetProviderInfo pendingInfo;
    final ArrayList<ResolveInfo> apps = new ArrayList<>();

    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Window w = getWindow();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        sp = getSharedPreferences("p", 0);
        awm = AppWidgetManager.getInstance(this);
        host = new AppWidgetHost(this, HOST_ID);
        world = new World(this);
        world.tilt = sp.getBoolean("tilt", true);
        world.setPages(sp.getInt("pages", 3));
        world.onMenu = this::menu;
        setContentView(world);

        loadApps();
        for (String id : sp.getString("widgets", "").split(","))
            if (!id.isEmpty()) addWidgetView(Integer.parseInt(id));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, f, 2 /* RECEIVER_EXPORTED */);
        else registerReceiver(pkgRx, f);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    @Override protected void onStart() { super.onStart(); host.startListening(); }
    @Override protected void onStop() { super.onStop(); try { host.stopListening(); } catch (Exception ignored) { } }

    @Override protected void onResume() {
        super.onResume();
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        world.resume();
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        world.pause();
    }

    @Override protected void onDestroy() { super.onDestroy(); unregisterReceiver(pkgRx); }
    @Override public void onBackPressed() { }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); world.goPage(0); }
    @Override public void onSensorChanged(SensorEvent e) { world.setGravity(-e.values[0], e.values[1]); }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    // ---------- 앱 ----------

    void loadApps() {
        PackageManager pm = getPackageManager();
        apps.clear();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0))
            if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        Set<String> chosen = sp.getStringSet("apps", null);
        if (chosen == null) {
            chosen = new HashSet<>();
            for (int i = 0; i < Math.min(20, apps.size()); i++) chosen.add(key(apps.get(i)));
            sp.edit().putStringSet("apps", chosen).apply();
        }
        int size = Math.round(world.iconR * 2);
        ArrayList<World.Body> list = new ArrayList<>();
        for (ResolveInfo r : apps) {
            if (!chosen.contains(key(r))) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            list.add(world.icon(key(r), r.loadLabel(pm).toString(), bitmap(r.loadIcon(pm), size), launch));
        }
        world.syncApps(list);
    }

    static String key(ResolveInfo r) { return r.activityInfo.packageName + "/" + r.activityInfo.name; }

    static Bitmap bitmap(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, s, s);
        d.draw(new Canvas(b));
        return b;
    }

    void chooseApps() {
        PackageManager pm = getPackageManager();
        Set<String> cur = sp.getStringSet("apps", new HashSet<>());
        String[] names = new String[apps.size()];
        boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).loadLabel(pm).toString();
            on[i] = cur.contains(key(apps.get(i)));
        }
        dialog().setTitle("굴러다닐 앱 고르기")
                .setMultiChoiceItems(names, on, (d, i, v) -> on[i] = v)
                .setPositiveButton("확인", (d, x) -> {
                    HashSet<String> set = new HashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) set.add(key(apps.get(i)));
                    sp.edit().putStringSet("apps", set).apply();
                    loadApps();
                })
                .setNegativeButton("취소", null).show();
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
        dialog().setTitle("위젯 추가").setItems(names, (d, i) -> {
            pendingInfo = ps.get(i);
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
        if (pendingId == -1) return;
        if (res != RESULT_OK) { host.deleteAppWidgetId(pendingId); pendingId = -1; return; }
        if (req == REQ_BIND) configure();
        else if (req == REQ_CONF) finishWidget();
    }

    @SuppressWarnings("deprecation")
    void addWidgetView(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) return;
        AppWidgetHostView v = host.createView(getApplicationContext(), id, info);
        float d = world.dp;
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
        int w = Math.min(maxW, Math.max(info.minWidth, (int) (80 * d)));
        int h = Math.min((int) (world.vh * 0.5f), Math.max(info.minHeight, (int) (60 * d)));
        v.updateAppWidgetSize(null, (int) (w / d), (int) (h / d), (int) (w / d), (int) (h / d));
        world.addWidget(id, v, w, h, info.loadLabel(getPackageManager()));
    }

    void saveWidgets() {
        StringBuilder b = new StringBuilder();
        for (World.Body x : world.bodies) if (x.view != null) b.append(x.wid).append(',');
        sp.edit().putString("widgets", b.toString()).apply();
    }

    void removeWidgetMenu() {
        ArrayList<World.Body> ws = new ArrayList<>();
        for (World.Body x : world.bodies) if (x.view != null) ws.add(x);
        if (ws.isEmpty()) return;
        String[] names = new String[ws.size()];
        for (int i = 0; i < ws.size(); i++) names[i] = ws.get(i).label;
        dialog().setTitle("뺄 위젯").setItems(names, (d, i) -> {
            int id = ws.get(i).wid;
            world.removeWidget(id);
            host.deleteAppWidgetId(id);
            saveWidgets();
        }).show();
    }

    // ---------- 메뉴 (빈 곳 길게 누르기) ----------

    void menu() {
        String[] items = {"앱 고르기", "위젯 추가", "위젯 빼기", "페이지 추가 (지금 " + world.pages + "쪽)",
                "페이지 빼기", "기울기 중력: " + (world.tilt ? "켜짐" : "꺼짐"), "흔들어 섞기", "배경화면 바꾸기", "휴대폰 설정"};
        dialog().setItems(items, (d, i) -> {
            switch (i) {
                case 0: chooseApps(); break;
                case 1: pickWidget(); break;
                case 2: removeWidgetMenu(); break;
                case 3: case 4:
                    world.setPages(world.pages + (i == 3 ? 1 : -1));
                    sp.edit().putInt("pages", world.pages).apply();
                    break;
                case 5:
                    world.tilt = !world.tilt;
                    sp.edit().putBoolean("tilt", world.tilt).apply();
                    break;
                case 6: world.shake(); break;
                case 7: startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "배경화면")); break;
                case 8: startActivity(new Intent(Settings.ACTION_SETTINGS)); break;
            }
        }).show();
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }
}
