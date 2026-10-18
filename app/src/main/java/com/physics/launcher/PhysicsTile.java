package com.physics.launcher;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 빠른 설정 타일: 흔들지 않고 누르기로 시작 */
public class PhysicsTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.setSubtitle(MirrorService.instance != null ? "홈 화면에서 누르기" : "권한 필요");
        t.updateTile();
    }

    @Override
    public void onClick() {
        MirrorService s = MirrorService.instance;
        if (s != null) { s.startFromTile(); return; }
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
    }
}
