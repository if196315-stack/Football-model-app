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
    private val bg = Color.rgb(11, 18, 32)
    private val panel = Color.rgb(22, 32, 51)
    private val muted = Color.rgb(157, 172, 195)
    private val accent = Color.rgb(89, 190, 145)
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var refreshButton: Button
    private lateinit var leagueBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private var currentTab = "matches"
    private lateinit var matchesContainer: LinearLayout
    private var data: JSONObject? = null
    private var selectedLeague = "ALL"
    private val expandedLeagues = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 32)
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("⚽  FOOTBALL MODEL", 22, true, Color.WHITE))
        brand.addView(label("OPENPLAY v1.3  ·  MATCH CENTER", 10, true, accent))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = Button(this).apply {
            text = "↻"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(39, 57, 78), 16)
            setOnClickListener { loadData() }
        }
        top.addView(refreshButton, LinearLayout.LayoutParams(52, 48))
        root.addView(top)

        statusView = label("Подключаюсь к серверу…", 12, false, muted)
        statusView.setPadding(0, 8, 0, 14)
        root.addView(statusView)

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
            background = rounded(panel, 16)
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 12; bottomMargin = 8 })

        leagueBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(leagueBar)
        matchesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(matchesContainer)
        loadData()
    }

    private fun label(value: String, size: Int, bold: Boolean = false, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun card(parent: LinearLayout = matchesContainer): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = rounded(panel, 20)
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 7, 0, 7)
        return box.also { parent.addView(it, lp) }
    }

    private fun addText(box: LinearLayout, value: String, size: Int = 13, bold: Boolean = false, color: Int = Color.WHITE) {
        box.addView(label(value, size, bold, color))
    }

    private fun pct(o: JSONObject, key: String): String =
        if (o.isNull(key)) "—" else String.format(Locale.US, "%.0f%%", o.optDouble(key) * 100.0)

    private fun date(raw: String): String = try {
        val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        input.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val parsed = input.parse(raw)!!
        SimpleDateFormat("EEE, dd MMM  ·  HH:mm", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(parsed)
    } catch (_: Exception) { raw.replace("T", " ").take(16) }

    private fun loadData() {
        statusView.text = "Обновляю данные…"
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
                runOnUiThread {
                    data = json
                    refreshButton.isEnabled = true
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Нет соединения · ${e.message ?: "проверьте интернет"}"
                    refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        listOf("matches" to "⚽  Матчи", "stats" to "▥  Статистика").forEach { item ->
            val active = currentTab == item.first
            val t = label(item.second, 13, true, if (active) bg else Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = rounded(if (active) accent else Color.TRANSPARENT, 13)
                setOnClickListener { currentTab = item.first; render() }
            }
            tabBar.addView(t, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private data class EvaluatedMatch(
        val league: String, val home: String, val away: String, val score: String,
        val predicted: String, val actual: String, val correct: Boolean,
        val date: String, val confidence: Double
    )

    private fun renderStats(json: JSONObject) {
        addText(matchesContainer, "РЕЗУЛЬТАТЫ МОДЕЛИ", 10, true, accent)
        addText(matchesContainer, "Насколько точны прогнозы 1X2", 19, true)
        addText(matchesContainer, "Сравниваем самый вероятный исход с фактическим счётом.", 11, false, muted)
        val rows = mutableListOf<EvaluatedMatch>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.getJSONObject(i)
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.getJSONObject(j)
                if (m.optString("status") != "FINISHED") continue
                val p = m.optJSONObject("prediction") ?: continue
                if (m.isNull("homeScore") || m.isNull("awayScore")) continue
                val hs = m.optInt("homeScore")
                val ascore = m.optInt("awayScore")
                val actual = when { hs > ascore -> "1"; hs < ascore -> "2"; else -> "X" }
                val options = listOf("1" to p.optDouble("1"), "X" to p.optDouble("x"), "2" to p.optDouble("2"))
                val best = options.maxByOrNull { it.second } ?: continue
                rows.add(EvaluatedMatch(league.optString("code"), m.optString("home"), m.optString("away"),
                    "$hs:$ascore", best.first, actual, best.first == actual, m.optString("utcDate"), best.second))
            }
        }
        rows.sortByDescending { it.date }
        val total = rows.size
        val wins = rows.count { it.correct }
        val misses = total - wins
        val accuracy = if (total > 0) wins * 100.0 / total else 0.0

        val hero = card(color = Color.rgb(16, 48, 42))
        addText(hero, "ТОЧНОСТЬ УГАДЫВАНИЯ ИСХОДА", 10, true, accent)
        addText(hero, if (total == 0) "—" else String.format(Locale.US, "%.1f%%", accuracy), 36, true)
        addText(hero, "$wins из $total матчей", 13, true)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(Color.rgb(54, 67, 81), 8) }
        if (wins > 0) bar.addView(View(this).apply { setBackground(rounded(accent, 8)) }, LinearLayout.LayoutParams(0, 9, wins.toFloat()))
        if (misses > 0) bar.addView(View(this).apply { setBackground(rounded(Color.rgb(255, 99, 111), 8)) }, LinearLayout.LayoutParams(0, 9, misses.toFloat()))
        hero.addView(bar, LinearLayout.LayoutParams(-1, 9).apply { topMargin = 12; bottomMargin = 8 })
        addText(hero, "✓  Угадано: $wins       ✕  Ошибок: $misses", 12, true)

        val explainer = card(color = panel)
        addText(explainer, "КАК СЧИТАЕТСЯ", 10, true, accent)
        addText(explainer, "Берётся максимальная вероятность из П1 / X / П2. Она сравнивается с итогом матча. Матчи без прогноза исключены. Это точность исходов, не доходность ставок.", 11, false, muted)

        addText(matchesContainer, "ПОСЛЕДНИЕ РЕЗУЛЬТАТЫ", 10, true, muted)
        if (rows.isEmpty()) {
            val empty = card()
            addText(empty, "Пока нет завершённых матчей с готовым прогнозом", 13, true)
        } else {
            rows.take(40).forEach { item ->
                val line = card()
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(label(item.league, 10, true, muted), LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(label(if (item.correct) "✓ УГАДАНО" else "✕ ПРОМАХ", 10, true,
                    if (item.correct) accent else Color.rgb(255, 99, 111)))
                line.addView(head)
                addText(line, item.home + " — " + item.away, 13, true)
                addText(line, "Прогноз: " + item.predicted + "   ·   Итог: " + item.actual + "   ·   " + item.score, 11, false, muted)
                addText(line, "Вероятность выбранного исхода: " + String.format(Locale.US, "%.0f%%", item.confidence * 100), 10, false, muted)
            }
        }
        val footer = card(color = panel)
        addText(footer, "Важно: в статистику попадают только завершённые матчи с сохранённым прогнозом. Точность на небольшой выборке может заметно меняться.", 10, false, muted)
    }

    private fun render() {
        val json = data ?: return
        matchesContainer.removeAllViews()
        leagueBar.removeAllViews()
        val leagues = json.optJSONArray("competitions")
        val state = json.optString("status", "unknown")
        val through = json.optString("openplayDataThrough", "—")
        statusView.text = "●  " + json.optString("notice", "Статус: " + state)
        renderTabs()

        val summary = card()
        addText(summary, "МОДЕЛЬ И ДАННЫЕ", 10, true, accent)
        addText(summary, "История xG до  $through", 16, true)
        addText(summary, "30 матчей  ·  half-life 12  ·  shrinkage 3", 12, false, muted)
        addText(summary, "Обновлено: ${json.optString("updatedAt", "—").replace("T", " ").take(16)}", 11, false, muted)
        val warning = json.optString("dataWarning", "")
        if (warning.isNotBlank()) addText(summary, "⚠  $warning", 11, false, Color.rgb(242, 190, 104))

        if (currentTab == "stats") {
            leagueBar.visibility = View.GONE
            renderStats(json)
            return
        } else {
            leagueBar.visibility = View.VISIBLE
        }

        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA")
        codes.forEachIndexed { index, code ->
            val b = TextView(this).apply {
                text = names[index]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(13, 10, 13, 10)
                setTextColor(if (selectedLeague == code) bg else Color.WHITE)
                background = rounded(if (selectedLeague == code) accent else panel, 28)
                setOnClickListener { selectedLeague = code; render() }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(0, 8, 7, 12)
            leagueBar.addView(b, lp)
        }

        if (leagues == null || leagues.length() == 0) {
            val empty = card()
            addText(empty, "Матчи пока не загружены", 15, true)
            return
        }

        var totalUpcoming = 0
        var totalPredicted = 0
        for (i in 0 until leagues.length()) {
            val league = leagues.getJSONObject(i)
            if (selectedLeague != "ALL" && league.optString("code") != selectedLeague) continue
            val matches = league.optJSONArray("matches") ?: continue
            val upcoming = mutableListOf<JSONObject>()
            for (j in 0 until matches.length()) {
                val m = matches.getJSONObject(j)
                if ((m.optString("status") == "SCHEDULED" || m.optString("status") == "TIMED")
                    && m.optJSONObject("prediction") != null) {
                    upcoming.add(m)
                    totalUpcoming++
                    totalPredicted++
                }
            }
            val leagueCode = league.optString("code")
            val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val expanded = expandedLeagues[leagueCode] ?: true
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 13, 12, 13)
                background = rounded(panel, 14)
                isClickable = true
                isFocusable = true
            }
            heading.addView(label(league.optString("name"), 16, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("${upcoming.size} матчей", 11, false, muted))
            heading.addView(label(if (expanded) "  ▾" else "  ▸", 18, true, accent))
            heading.setOnClickListener {
                expandedLeagues[leagueCode] = !(expandedLeagues[leagueCode] ?: true)
                render()
            }
            section.addView(heading)
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            section.addView(content)
            matchesContainer.addView(section)
            if (upcoming.isEmpty()) {
                val empty = card(content)
                addText(empty, "Нет предстоящих матчей с готовым прогнозом", 12, false, muted)
            }
            upcoming.sortedBy { it.optString("utcDate") }.forEach { m ->
                val box = card(content)
                addText(box, date(m.optString("utcDate")), 11, true, accent)
                addText(box, m.optString("home") + "   —   " + m.optString("away"), 15, true)
                val pred = m.optJSONObject("prediction")
                if (pred == null) {
                    addText(box, m.optString("predictionWarning", "Прогноз пока недоступен"), 11, false, Color.rgb(242, 190, 104))
                } else {
                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 8, 0, 4)
                    }
                    listOf("1" to "1", "X" to "x", "2" to "2").forEach { pair ->
                        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                        cell.addView(label(pair.first, 10, true, muted))
                        cell.addView(label(pct(pred, pair.second), 20, true, if (pair.first == "1") accent else Color.WHITE))
                        line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    box.addView(line)
                    addText(box, "1X ${pct(pred, "1x")}     X2 ${pct(pred, "x2")}     DNB 1 ${pct(pred, "dnbHome")}", 12, true, Color.rgb(180, 204, 232))
                    addText(box, "ТБ 2.5 ${pct(pred, "over25")}   ·   Обе забьют ${pct(pred, "bttsYes")}", 12, false, muted)
                    addText(box, "ИТБ 1.5: хозяева ${pct(pred, "homeOver15")}  /  гости ${pct(pred, "awayOver15")}", 12, false, muted)
                    addText(box, "Ожидаемые голы  ${String.format(Locale.US, "%.2f : %.2f", pred.optDouble("expectedHomeGoals"), pred.optDouble("expectedAwayGoals"))}", 12, true)
                }
            }
        }

        val footer = card()
        addText(footer, "ПОКРЫТИЕ", 10, true, accent)
        addText(footer, "Предстоящих матчей: $totalUpcoming  ·  с прогнозом: $totalPredicted", 13, true)
        addText(footer, "Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", 11, false, muted)
        addText(footer, "xG: openplay (Noah Bair), CC BY 4.0.", 10, false, muted)
    }
}
