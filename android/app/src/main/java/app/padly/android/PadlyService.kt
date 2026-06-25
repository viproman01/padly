package app.padly.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the WSClient alive while the trackpad is in
 * use, even when the phone is locked or the activity has been swept out of the
 * recents list.
 *
 * Stays alive while there is at least one active connection; on disconnect
 * after a grace period the activity stops the service.
 */
class PadlyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.notif_active)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notif_active_desc)
        startForeground(NOTIF_ID, buildNotification(title, text))
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, PadlyApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, PadlyService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PadlyService::class.java))
        }
    }
}
