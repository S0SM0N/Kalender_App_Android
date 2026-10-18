package de.sosmon.meinkalender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.kalender_app.Geburtstag
import com.example.kalender_app.MainActivity
import com.example.kalender_app.scheduleBirthday

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Termin"
        val text = intent.getStringExtra("text") ?: "Erinnerung"
        val id = intent.getIntExtra("id", 0)
        val channelId = "termine"
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Termine", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)

        // Geburtstage: Erinnerung für das nächste Jahr direkt neu planen
        val key = intent.getStringExtra("bday_key")
        if (key != null) {
            scheduleBirthday(
                context,
                Geburtstag(
                    key,
                    intent.getStringExtra("bday_name") ?: "",
                    intent.getIntExtra("bday_month", 1),
                    intent.getIntExtra("bday_day", 1),
                    intent.getIntExtra("bday_year", 0)
                )
            )
        }
    }
}