package com.thaweekarn.slipsync.worker

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class SlipSyncTileService : TileService() {

    override fun onClick() {
        super.onClick()
        // Enqueue unique work "slip-sync"
        WorkScheduler.enqueueSyncWork(this)

        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()

        // Revert to inactive state after triggering
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
