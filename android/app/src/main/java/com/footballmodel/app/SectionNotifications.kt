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
import java.security.MessageDigest

internal object SectionNotifications {
    const val UNREAD_KEY = "section_report_observations_unread"
    private const val FINGERPRINT_KEY = "section_report_observations_fingerprint"
    private const val CHANNEL_ID = "iq_match_report_observations"
    private const val CHANNEL_NAME = "Отчёт и наблюдения"
    private const val NOTIFICATION_ID = 7303
    private const val PERMISSION_REQUEST_CODE = 7303

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Новая информация в едином разделе «Отчёт и наблюдения»"
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

    fun markRead(context: Context) {
        context.getSharedPreferences("bank_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(UNREAD_KEY, false).apply()\n        NotificationCenter.markAllRead(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    fun fingerprint(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun observeAndNotify(context: Context, fingerprint: String) {
        val prefs = context.getSharedPreferences("bank_settings", Context.MODE_PRIVATE)
        val previous = prefs.getString(FINGERPRINT_KEY, null)
        if (previous == fingerprint) return
        val editor = prefs.edit().putString(FINGERPRINT_KEY, fingerprint)
        if (previous == null) {
            editor.apply()
            return
        }
        editor.putBoolean(UNREAD_KEY, true).apply()
        show(context)
    }

    private fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_tab", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Новое в отчёте и наблюдениях")
            .setContentText("В едином разделе появились новые данные. Нажмите, чтобы посмотреть.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
