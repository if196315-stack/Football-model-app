package com.footballmodel.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Locale

internal object AutoPaperNotifications {
    private const val CHANNEL_ID = "auto_paper_bets"
    private const val CHANNEL_NAME = "Автоматические paper-ставки"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых автоматических бумажных ставках"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, bet: JSONObject) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val home = bet.optString("home", "Команда 1")
        val away = bet.optString("away", "Команда 2")
        val market = bet.optString("market", "Рынок")
        val odds = bet.optDouble("odds", 0.0)
        val stake = bet.optDouble("stake", 0.0)
        val oddsText = String.format(Locale("ru", "RU"), "%.2f", odds)
        val stakeText = String.format(Locale("ru", "RU"), "%.0f ₽", stake)
        val notificationId = bet.optString("id").hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Новая автоматическая paper-ставка")
            .setContentText("$home — $away")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$home — $away\n$market · коэффициент $oddsText\nСумма: $stakeText"
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
