package com.footballmodel.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.Locale

internal object PaperBetNotifications {
    private const val CHANNEL_ID = "auto_paper_bets"
    private const val CHANNEL_NAME = "Автоматические paper-ставки"
    private const val PERMISSION_REQUEST_CODE = 7301

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых автоматических бумажных ставках IQ Match"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
    }

    fun show(context: Context, bet: JSONObject) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        ensureChannel(context)

        val home = bet.optString("home", "Команда 1")
        val away = bet.optString("away", "Команда 2")
        val market = bet.optString("market", "Рынок")
        val stake = bet.optDouble("stake", 0.0)
        val odds = bet.optDouble("odds", 0.0)
        val amount = String.format(Locale("ru", "RU"), "%.0f ₽", stake)
        val price = String.format(Locale.US, "%.2f", odds)
        val title = "Новая AI paper-ставка"
        val body = "$home — $away · $market · $amount · кэф $price"

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val requestCode = bet.optString("id").hashCode() and 0x7fffffff
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(requestCode, notification)
    }
}
