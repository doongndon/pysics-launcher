package com.physics.launcher;

import android.app.Activity;
import android.os.Bundle;

/** 배경화면만 보이게 잠깐 떠서 배경화면을 찍게 해 주는 투명 화면 */
public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(() -> {
            MirrorService s = MirrorService.instance;
            if (s != null && !isFinishing()) s.onProbeReady(this);
            else finish();
        }, 350);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) finish();
    }
}
