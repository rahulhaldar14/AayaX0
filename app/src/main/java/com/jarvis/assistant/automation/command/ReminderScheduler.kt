package com.jarvis.assistant.automation.command

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R

object ReminderScheduler {
    private const val CHANNEL_ID = "jarvis_reminders"

    fun schedule(context: Context, triggerAtMillis: Long, text: String) {
        val id = (System.currentTimeMillis() xor text.hashCode().toLong()).toInt()
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }

    internal fun show(context: Context, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bell)
            .setContentTitle("AYHA AI reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.show(context, intent.getStringExtra("text").orEmpty().ifBlank { "Your reminder is due." })
    }
}
