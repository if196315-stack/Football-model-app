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
        addText(matchesContainer, "ОЦЕНКА ОСНОВНЫХ РЫНКОВ", 10, true, accent)
        addText(matchesContainer, "Проверяем каждый рынок отдельно", 19, true)
        addText(matchesContainer, "П1 / X / П2 остаются внутренними вероятностями модели. Главная оценка — по рынкам 1X, X2, DNB и индивидуальным тоталам команд.", 11, false, muted)
        data class MetricRow(val name: String, val n: Int, val hits: Int, val brier: Double, val logLoss: Double)
        val metrics = mutableListOf<MetricRow>()
        val leagues = json.optJSONArray("competitions")
        fun collect(key: String, title: String, actual: (Int, Int) -> Boolean, excludeDraws: Boolean = false) {
            var n = 0; var hits = 0; var brier = 0.0; var logLoss = 0.0
            if (leagues != null) for (i in 0 until leagues.length()) {
                val matches = leagues.getJSONObject(i).optJSONArray("matches") ?: continue
                for (j in 0 until matches.length()) {
                    val m = matches.getJSONObject(j)
                    if (m.optString("status") != "FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) continue
                    val p = m.optJSONObject("prediction") ?: continue
                    if (!p.has(key) || p.isNull(key)) continue
                    val h = m.optInt("homeScore"); val aw = m.optInt("awayScore")
                    if (excludeDraws && h == aw) continue
                    val outcome = actual(h, aw)
                    val probability = p.optDouble(key).coerceIn(0.000000000001, 0.999999999999)
                    val y = if (outcome) 1.0 else 0.0
                    n++; if (outcome == (probability >= 0.5)) hits++
                    brier += (probability-y)*(probability-y)
                    logLoss += -(y*kotlin.math.ln(probability)+(1.0-y)*kotlin.math.ln(1.0-probability))
                }
            }
            if (n > 0) metrics.add(MetricRow(title,n,hits,brier/n,logLoss/n))
        }
        collect("1x", "1X · хозяева не проиграют") { h,a -> h >= a }
        collect("x2", "X2 · гости не проиграют") { h,a -> a >= h }
        collect("dnbHome", "DNB · победа хозяев без ничьей", { h,a -> h > a }, true)
        collect("homeOver15", "ИТБ 1.5 · хозяева") { h,_ -> h >= 2 }
        collect("awayOver15", "ИТБ 1.5 · гости") { _,a -> a >= 2 }
        if (metrics.isEmpty()) {
            val empty = card()
            addText(empty, "Пока нет завершённых матчей с сохранёнными прогнозами по основным рынкам.", 12, false, muted)
        } else metrics.forEach { metric ->
            val box = card()
            addText(box, metric.name, 14, true)
            addText(box, "Попадания при пороге 50%: ${metric.hits}/${metric.n} (${String.format(Locale.US, "%.1f", metric.hits*100.0/metric.n)}%)", 11, true, accent)
            addText(box, "Brier Score: ${String.format(Locale.US, "%.4f", metric.brier)} · Log Loss: ${String.format(Locale.US, "%.4f", metric.logLoss)}", 11, false, muted)
            addText(box, if (metric.name.startsWith("DNB")) "Ничьи исключены; вероятность условная, без ничьей." else "Бинарный рынок; оценка вероятностей по завершённым матчам.", 10, false, muted)
        }
        val explainer = card()
        addText(explainer, "КАК ЧИТАТЬ МЕТРИКИ", 10, true, accent)
        addText(explainer, "Brier Score и Log Loss оценивают качество вероятностей: чем ниже, тем лучше. Попадания — вспомогательная доля прогнозов выше 50%, а не доходность. П1 / X / П2 не являются главной статистикой.", 11, false, muted)
        val footer = card(color = panel)
        addText(footer, "Выборка включает завершённые матчи с сохранённым прогнозом. Это мониторинг, а не независимый тест модели.", 10, false, muted)
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
                    val mainMarkets = card(box)
                    addText(mainMarkets, "ОСНОВНЫЕ РЫНКИ МОДЕЛИ", 10, true, accent)
                    addText(mainMarkets, "1X  ${pct(pred, "1x")}     X2  ${pct(pred, "x2")}", 17, true, Color.WHITE)
                    addText(mainMarkets, "DNB · победа хозяев без ничьей: ${pct(pred, "dnbHome")}", 13, true, Color.rgb(180, 220, 205))
                    addText(mainMarkets, "ИТБ 1.5 хозяев: ${pct(pred, "homeOver15")}     ИТБ 1.5 гостей: ${pct(pred, "awayOver15")}", 12, true, Color.WHITE)
                    addText(box, "ВНУТРЕННИЕ ВЕРОЯТНОСТИ ИСХОДА", 10, true, muted)
                    addText(box, "П1 ${pct(pred, "1")}     X ${pct(pred, "x")}     П2 ${pct(pred, "2")}", 13, false, muted)
                    addText(box, "Дополнительные рынки: ТБ 2.5 ${pct(pred, "over25")} · Обе забьют ${pct(pred, "bttsYes")}", 11, false, muted)
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
