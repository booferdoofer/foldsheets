package dev.foldblur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * One-time pairing with Wireless debugging. The code is typed into a notification,
 * so the Settings pairing box can stay open on screen while you type.
 */
object PairingNotif {
    private const val CHANNEL = "pairing"
    private const val ID = 7
    const val KEY = "code"

    fun show(c: Context, text: String, askForCode: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Fold Blur pairing")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (askForCode) {
            val pi = PendingIntent.getBroadcast(
                c, 0, Intent(c, PairingReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY).setLabel("6-digit pairing code").build()
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_app), "Enter code", pi)
                    .addRemoteInput(input).build()
            )
        }
        nm.notify(ID, b.build())
    }
}

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(PairingNotif.KEY)?.toString() ?: return
        val pending = goAsync()
        PairingNotif.show(c, "Pairing… keep the pairing box open", askForCode = false)
        ExactAngle.pair(c, "", code) { msg ->
            val ok = msg.startsWith("Paired")
            PairingNotif.show(c, if (ok) "Paired ✓  Open Fold Blur and tap \"Test exact angle\"." else "$msg\nTry again:", askForCode = !ok)
            pending.finish()
        }
    }
}
