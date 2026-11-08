package com.footballmodel.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val dataUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/data.json"
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 40)
            setBackgroundColor(Color.rgb(245, 247, 251))
        }
        scroll.addView(root)
        setContentView(scroll)
        text("FOOTBALL MODEL", 25, true, Color.rgb(20, 48, 91))
        text("Openplay v1.3 · статистическая модель", 14, false, Color.DKGRAY)
        statusView = text("Загружаю данные…", 15, true, Color.rgb(25, 90, 55))
        refreshButton = Button(this).apply {
            text = "Обновить данные"
            setOnClickListener { loadData() }
        }
        root.addView(refreshButton)
        loadData()
    }

    private fun text(value: String, size: Int, bold: Boolean, color: Int): TextView {
        val v = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
        root.addView(v)
        return v
    }

    private fun card(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 22f
                setStroke(1, Color.rgb(225, 231, 240))
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 10, 0, 10)
        root.addView(box, lp)
        return box
    }

    private fun inside(box: LinearLayout, value: String, size: Int = 14, bold: Boolean = false, color: Int = Color.DKGRAY) {
        box.addView(TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 5, 0, 5)
        })
    }

    private fun pct(o: JSONObject, key: String): String {
        if (o.isNull(key)) return "—"
        return String.format(Locale.US, "%.1f%%", o.optDouble(key) * 100.0)
    }

    private fun date(raw: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            input.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val d = input.parse(raw)
            val output = SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault())
            output.timeZone = java.util.TimeZone.getDefault()
            output.format(d!!)
        } catch (_: Exception) { raw.replace("T", " ").take(16) }
    }

    private fun loadData() {
        statusView.text = "Подключаюсь к серверу…"
        refreshButton.isEnabled = false
        thread {
            try {
                val conn = (URL(dataUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 20000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                runOnUiThread { render(json) }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Не удалось загрузить данные: ${e.message ?: "проверьте интернет"}"
                    refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun render(json: JSONObject) {
        root.removeViews(3, (root.childCount - 3).coerceAtLeast(0))
        refreshButton.isEnabled = true
        val state = json.optString("status", "unknown")
        statusView.text = json.optString("notice", "Статус: $state")
        val info = card()
        inside(info, "Состояние модели: $state", 15, true, Color.rgb(20, 85, 55))
        inside(info, "Исторические xG Openplay до: ${json.optString("openplayDataThrough", "неизвестно")}")
        inside(info, "Параметры: последние 30 матчей · half-life 12 · shrinkage 3")
        inside(info, "Обновлено: ${json.optString("updatedAt", "—")}", 12)
        inside(info, json.optString("dataWarning", ""), 13, false, Color.rgb(145, 92, 20))

        val leagues = json.optJSONArray("competitions")
        if (leagues == null || leagues.length() == 0) {
            val empty = card()
            inside(empty, "Матчи пока не загружены", 16, true)
            return
        }
        for (i in 0 until leagues.length()) {
            val league = leagues.getJSONObject(i)
            val matches = league.optJSONArray("matches")
            val upcoming = mutableListOf<JSONObject>()
            if (matches != null) {
                for (j in 0 until matches.length()) {
                    val m = matches.getJSONObject(j)
                    if (m.optString("status") == "SCHEDULED" || m.optString("status") == "TIMED") upcoming.add(m)
                }
            }
            text(league.optString("name"), 19, true, Color.rgb(25, 52, 91))
            if (league.optString("error").isNotBlank() && league.optString("error") != "null") {
                text("Ошибка загрузки лиги: ${league.optString("error")}", 12, false, Color.RED)
            }
            if (upcoming.isEmpty()) {
                text("Нет предстоящих матчей в полученном расписании.", 13, false, Color.GRAY)
            }
            for (m in upcoming) {
                val box = card()
                inside(box, "${m.optString("home")}  —  ${m.optString("away")}", 17, true, Color.rgb(25, 45, 75))
                inside(box, date(m.optString("utcDate")), 12, false, Color.GRAY)
                val pred = m.optJSONObject("prediction")
                if (pred == null) {
                    inside(box, m.optString("predictionWarning", "Прогноз недоступен"), 13, true, Color.rgb(160, 90, 20))
                } else {
                    inside(box, "1       X       2", 12, true, Color.GRAY)
                    inside(box, "${pct(pred, "1")}     ${pct(pred, "x")}     ${pct(pred, "2")}", 19, true, Color.rgb(20, 70, 145))
                    inside(box, "1X: ${pct(pred, "1x")}    X2: ${pct(pred, "x2")}    DNB 1: ${pct(pred, "dnbHome")}", 13)
                    inside(box, "ТБ 2.5: ${pct(pred, "over25")}    ТМ 2.5: ${pct(pred, "under25")}", 13)
                    inside(box, "Обе забьют: Да ${pct(pred, "bttsYes")} / Нет ${pct(pred, "bttsNo")}", 13)
                    inside(box, "ИТБ 1.5: ${pct(pred, "homeOver15")}    ИТБ 2.5: ${pct(pred, "awayOver15")}", 13)
                    inside(box, "Ожидаемые голы: ${String.format(Locale.US, "%.2f", pred.optDouble("expectedHomeGoals"))} : ${String.format(Locale.US, "%.2f", pred.optDouble("expectedAwayGoals"))}", 13, true)
                }
            }
        }
        val foot = card()
        inside(foot, "Методика", 16, true)
        inside(foot, "Независимый Пуассоновский счётный модельный слой. Силы атаки и защиты оцениваются по xG/xGA команды, последние 30 матчей с half-life 12 и shrinkage 3.")
        inside(foot, "Это исследовательские вероятности, а не гарантия результата и не рекомендация для ставок.", 13, true, Color.rgb(145, 75, 20))
        inside(foot, "Источник xG: openplay (Noah Bair), CC BY 4.0.")
    }
}
