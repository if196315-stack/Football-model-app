package com.footballmodel.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DailyReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val betsPrefs = applicationContext.getSharedPreferences("bet_history", Context.MODE_PRIVATE)
        val intelligence = applicationContext.getSharedPreferences("intelligence_center", Context.MODE_PRIVATE)
        val bets = try { JSONArray(betsPrefs.getString("bets", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val day = dayFormat.format(Date(now))
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = calendar.timeInMillis
        val dayEnd = dayStart + 24L * 60L * 60L * 1000L

        var dailyBets = 0
        var wins = 0
        var losses = 0
        var pending = 0
        var profit = 0.0
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            // createdAt is the placement timestamp. Match kickoff date is not a valid
            // substitute because a bet may be placed days before the match.
            val createdAt = bet.optLong("createdAt", -1L)
            if (createdAt < dayStart || createdAt >= dayEnd) continue
            dailyBets++
            when (bet.optString("status")) {
                "WON" -> { wins++; profit += bet.optDouble("stake") * (bet.optDouble("odds") - 1.0) }
                "LOST" -> { losses++; profit -= bet.optDouble("stake") }
                "PENDING" -> pending++
            }
        }

        val report = JSONObject()
            .put("date", day).put("createdAt", now).put("bets", dailyBets)
            .put("wins", wins).put("losses", losses).put("pending", pending)
            .put("profit", profit)
        val history = try { JSONArray(intelligence.getString("daily_reports", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val isNewReport = history.length() == 0 || history.optJSONObject(history.length() - 1)?.optString("date") != day
        if (isNewReport) history.put(report)
        while (history.length() > 60) history.remove(0)
        val unread = intelligence.getInt("unread_count", 0) + if (isNewReport) 1 else 0
        intelligence.edit().putString("daily_reports", history.toString()).putInt("unread_count", unread).commit()
        if (isNewReport) showNotification(wins, losses, pending, dailyBets)
        return Result.success()
    }

    private fun showNotification(wins: Int, losses: Int, pending: Int, total: Int) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "daily_intelligence_reports"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Ежедневные отчёты IQ MATCH", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Утренние сводки paper-статистики"
            })
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_tab", "intel")
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 7401, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "Ставки сегодня: $total · WIN $wins · LOSS $losses · ожидают $pending"
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(applicationContext, channelId) else Notification.Builder(applicationContext)
        manager.notify(7401, notification.setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("IQ MATCH · Ежедневный отчёт").setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(pendingIntent)
            .setAutoCancel(true).build())
    }
}
