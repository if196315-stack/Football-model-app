package com.footballmodel.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val dataUrl = "https://if196315-stack.github.io/Football-model-app/data.json"
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 36)
        }
        scroll.addView(content)
        setContentView(scroll)
        addText("FOOTBALL MODEL", 25, true)
        addText("Openplay v1.3 · исследовательская версия", 14, false)
        status = addText("Загружаю данные…", 16, true)
        val refresh = Button(this).apply {
            text = "Обновить"
            setOnClickListener { loadData() }
        }
        content.addView(refresh)
        loadData()
    }

    private fun addText(value: String, size: Int, bold: Boolean): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(0xFF172033.toInt())
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 10)
        }
        content.addView(view)
        return view
    }

    private fun loadData() {
        status.text = "Подключаюсь к серверу…"
        thread {
            try {
                val connection = (URL(dataUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(text)
                runOnUiThread { render(json) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Не удалось загрузить данные. Проверь интернет и настройку GitHub Pages.\n\n" + e.message }
            }
        }
    }

    private fun render(json: JSONObject) {
        content.removeViews(3, (content.childCount - 3).coerceAtLeast(0))
        val state = json.optString("status", "unknown")
        status.text = json.optString("notice", "Статус: $state")
        addText("Статус: $state", 16, true)
        addText("Модель: " + json.optString("modelVersion"), 14, false)
        addText("Параметры: 30 матчей · half-life 12 · shrinkage 3", 14, false)
        addText("Последняя дата xG: " + json.optString("openplayDataThrough"), 14, false)
        addText("Обновлено: " + json.optString("updatedAt"), 12, false)
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) {
            for (i in 0 until leagues.length()) {
                val league = leagues.getJSONObject(i)
                addText(league.optString("name"), 18, true)
                val matches = league.optJSONArray("matches")
                if (matches != null) for (j in 0 until matches.length()) {
                    val m = matches.getJSONObject(j)
                    val score = if (m.isNull("homeScore") || m.isNull("awayScore")) "" else " · ${m.opt("homeScore")}:${m.opt("awayScore")}"
                    addText("${m.optString("utcDate").take(16).replace("T", " ")}  ${m.optString("home")} — ${m.optString("away")}$score", 14, false)
                }
                league.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { addText("Ошибка API: $it", 12, false) }
            }
        }
        addText("Важно: приложение пока показывает расписание и результаты. Прогнозы появятся только после подтверждения свежих xG Openplay и корректного сопоставления команд. Это не рекомендация для ставок.", 13, true)
    }
}
