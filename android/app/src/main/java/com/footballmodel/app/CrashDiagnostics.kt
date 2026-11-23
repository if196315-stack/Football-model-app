package com.footballmodel.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Stores the last uncaught Java/Kotlin process exception locally so it can be
 * shared from the phone on the next launch without ADB or a computer.
 */
internal object CrashDiagnostics {
    private const val REPORT_FILE = "last-crash-report.txt"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                val report = buildString {
                    append("IQ Match crash report\n")
                    append("Time: ").append(timestamp).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ")
                        .append(android.os.Build.VERSION.SDK_INT).append(")\n")
                    append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append('\n')
                    append("\n").append(writer.toString())
                }
                File(appContext.filesDir, REPORT_FILE).writeText(report.take(60_000), Charsets.UTF_8)
            } catch (_: Throwable) {
                // Never prevent Android's original crash handling if report storage fails.
            }
            if (previous != null) previous.uncaughtException(thread, error) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun recordHandledException(context: Context, label: String, error: Throwable) {
        try {
            val appContext = context.applicationContext
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            val report = buildString {
                append("IQ Match handled error report\n")
                append("Time: ").append(timestamp).append('\n')
                append("Context: ").append(label).append('\n')
                append("Thread: ").append(Thread.currentThread().name).append('\n')
                append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ")
                    .append(android.os.Build.VERSION.SDK_INT).append(")\n")
                append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append('\n')
                append("\n").append(writer.toString())
            }
            File(appContext.filesDir, REPORT_FILE).writeText(report.take(60_000), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Diagnostics must never interfere with normal UI error handling.
        }
    }

    fun showPendingReport(activity: Activity) {
        val reportFile = File(activity.filesDir, REPORT_FILE)
        if (!reportFile.isFile) return
        val report = try { reportFile.readText(Charsets.UTF_8) } catch (_: Throwable) { return }
        if (report.isBlank()) return

        AlertDialog.Builder(activity)
            .setTitle("Отчёт о предыдущем сбое")
            .setMessage("Приложение сохранило технический отчёт о последнем аварийном закрытии. Отправьте его через меню «Поделиться», чтобы можно было определить точную причину.")
            .setPositiveButton("Поделиться отчётом") { _, _ ->
                try {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "IQ Match — отчёт об ошибке")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    activity.startActivity(Intent.createChooser(share, "Отправить отчёт"))
                    reportFile.delete()
                } catch (_: Throwable) {
                    Toast.makeText(activity, "Не удалось открыть меню отправки отчёта.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}
