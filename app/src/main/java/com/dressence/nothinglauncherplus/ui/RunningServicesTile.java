package com.dressence.nothinglauncherplus.ui;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile: Running services (Developer options' page). */
public final class RunningServicesTile extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        // The shade closes and the screen opens over the lock screen once unlocked.
        unlockAndRun(() -> {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(RunningServices.pendingIntent(this));
            } else {
                startActivityAndCollapse(new Intent(this, RunningServices.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }
}
