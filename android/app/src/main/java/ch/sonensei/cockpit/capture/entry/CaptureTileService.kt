package ch.sonensei.cockpit.capture.entry

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick-settings tile (SPEC.md §4.5): a tap collapses the shade and opens the capture sheet, also from the lock screen
 * after unlocking. The subtitle shows how many captures wait to sync while the shade is open.
 */
@AndroidEntryPoint
class CaptureTileService : TileService() {
    @Inject lateinit var queue: CaptureQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val status = queue.status.first()
            val tile = qsTile ?: return@launch
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = pendingLabel(resources, status)
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openCapture) else openCapture()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // TileServiceCompat picks the PendingIntent variant from Android 14 on (the Intent one throws there).
    private fun openCapture() {
        val pending = PendingIntentActivityWrapper(this, 0, captureIntent(), PendingIntent.FLAG_UPDATE_CURRENT, false)
        TileServiceCompat.startActivityAndCollapse(this, pending)
    }

    internal fun captureIntent(): Intent = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
