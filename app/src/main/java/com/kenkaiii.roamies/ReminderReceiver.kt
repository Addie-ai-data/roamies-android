package com.kenkaiii.roamies

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderText = intent.getStringExtra("reminder_text") ?: "You have a reminder from your Roamie!"
        showNotification(context, reminderText)
    }

    private fun showNotification(context: Context, text: String) {
        val channelId = "roamies_reminders"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Roamies Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for Roamies reminders down to the second"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Roamie Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}
