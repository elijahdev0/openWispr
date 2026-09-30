package com.voicerewriter

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap from anywhere starts a dictation — the same "sheet appears, listen,
 * transcribe, insert, gone" flow as holding a volume key, for people who would rather not hold a key
 * (or whose volume keys are spoken for).
 *
 * There is no key to release here, so the take is not push-to-talk: it ends on the sheet's own stop
 * button, or on the VAD auto-stop when the speaker pauses.
 *
 * The activity goes through [startActivityAndCollapse] rather than a plain `startActivity`: a
 * TileService lost its background-activity-launch exemption in Android 14, so the launch has to be
 * handed to the platform. The [PendingIntent] overload is the sanctioned one from 34 up; below that
 * the deprecated Intent overload is still the only thing that exists.
 */
class DictateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@DictateTileService, R.drawable.ic_aperture)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = RewriteActivity.dictateIntent(this, pushToTalk = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
