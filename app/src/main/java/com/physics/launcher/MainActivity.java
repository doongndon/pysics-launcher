package com.physics.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.nio.file.Files;

/** 설정·안내 화면 */
public class MainActivity extends Activity {
    SharedPreferences sp;
    TextView status;
    LinearLayout box;
    float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("p", 0);
        dp = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        box.setPadding(pad, pad, pad, pad);
        sv.addView(box);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
            box.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom);
            return in;
        });
        setContentView(sv);

        text("물리 홈", 26, true);
        status = text("", 15, false);

        head("1. 켜기");
        text("아래 버튼 → '설치된 앱' → 물리 홈 → 켜기", 15, false);
        button("접근성 설정 열기", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        text("'제한된 설정' 때문에 켜지지 않으면: 아래 버튼 → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 켜기", 14, false);
        button("앱 정보 열기", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        head("2. 쓰는 법");
        text("· 홈 화면에서 폰을 흔들면 아이콘과 위젯이 떨어져요\n"
                + "· 폰을 기울이면 그쪽으로 굴러가요\n"
                + "· 조각을 끌어서 던질 수 있어요\n"
                + "· 아이콘을 누르면 앱이 열려요\n"
                + "· 빈 곳을 옆으로 밀면 다른 페이지로 가요\n"
                + "· 빈 곳을 위로 쓸면 앱스 화면, 아래로 쓸면 알림창\n"
                + "· 다시 흔들거나, 뒤로 버튼, 빈 곳 길게 누르기 → 원래대로\n"
                + "· 흔들기 대신 빠른 설정 패널에 '물리 모드' 타일을 추가해 눌러도 돼요", 15, false);

        head("3. 흔들기 민감도");
        RadioGroup rg = new RadioGroup(this);
        String[] names = {"세게 흔들어야 시작", "보통", "살짝 흔들어도 시작"};
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(names[i]);
            r.setTextSize(15);
            r.setId(View.generateViewId());
            r.setTag(i);
            rg.addView(r);
            if (sp.getInt("sens", 1) == i) r.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> sp.edit().putInt("sens", (int) g.findViewById(id).getTag()).apply());
        box.addView(rg);

        head("4. 문제가 있을 때");
        button("배경 다시 찍기", v -> {
            sp.edit().putBoolean("bgDirty", true).apply();
            Toast.makeText(this, "다음에 흔들 때 배경화면을 다시 찍어요", Toast.LENGTH_SHORT).show();
        });
        button("진단 정보 보내기", v -> shareDiag());
        text("아이콘을 잘 못 찾거나 이상하게 보이면, 홈 화면에서 한 번 흔든 뒤 '진단 정보 보내기'로 내용을 보내 주세요. 화면 구조만 담기고 사진은 들어가지 않아요.", 13, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = MirrorService.instance != null;
        status.setText(on ? "상태: 켜짐 — 홈 화면에서 흔들어 보세요" : "상태: 꺼짐 — 아래 1번을 먼저 해 주세요");
        status.setTextColor(on ? 0xFF2E9E6A : 0xFFD0533A);
    }

    void shareDiag() {
        File f = new File(getFilesDir(), "diag.txt");
        String s;
        try { s = new String(Files.readAllBytes(f.toPath()), "UTF-8"); }
        catch (Exception e) { Toast.makeText(this, "아직 진단 정보가 없어요. 홈 화면에서 먼저 흔들어 주세요", Toast.LENGTH_LONG).show(); return; }
        if (s.length() > 90000) s = s.substring(0, 90000);
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, s), "진단 정보 보내기"));
    }

    TextView text(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setLineSpacing(0, 1.25f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(4), 0, px(4));
        box.addView(t);
        return t;
    }

    void head(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, px(22), 0, px(6));
    }

    void button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setGravity(Gravity.CENTER);
        box.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    int px(float v) { return Math.round(v * dp); }
}
