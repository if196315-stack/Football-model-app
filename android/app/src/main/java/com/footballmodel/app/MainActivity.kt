package com.footballmodel.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val dataUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/data.json"
    private val bg = Color.rgb(7, 13, 25)
    private val panel = Color.rgb(17, 29, 47)
    private val surface = Color.rgb(23, 39, 61)
    private val muted = Color.rgb(143, 161, 187)
    private val accent = Color.rgb(89, 224, 176)
    private val cyan = Color.rgb(91, 190, 255)
    private val gold = Color.rgb(255, 194, 92)
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var refreshButton: Button
    private lateinit var leagueBar: LinearLayout
    private lateinit var leagueScroll: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private var currentTab = "matches"
    private lateinit var matchesContainer: LinearLayout
    private var data: JSONObject? = null
    private var bets = JSONArray()
    private val betPrefs by lazy { getSharedPreferences("bet_history", MODE_PRIVATE) }
    private data class BetMarket(val key: String, val label: String)
    private var selectedLeague = "ALL"
    private val expandedLeagues = mutableMapOf<String, Boolean>()
    // Сохраняем уже построенные экраны: повторное переключение вкладок не пересоздаёт сотни View.
    private val tabViewCache = mutableMapOf<String, Pair<List<View>, List<View>>>()
    private var renderedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; clipToPadding = false; setPadding(0, 0, 0, 18); overScrollMode = View.OVER_SCROLL_NEVER }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 34, 16, 28)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 14, 28), Color.rgb(10, 23, 38), Color.rgb(7, 13, 25)))
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("IQ Match", 25, true, Color.WHITE))
        brand.addView(label("SMART FOOTBALL INTELLIGENCE", 9, true, cyan))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = Button(this).apply {
            text = "↻"
            text = "↻"
            textSize = 21f
            isAllCaps = false
            elevation = 0f
            setTextColor(Color.WHITE)
            background = rounded(surface, 16)
            setOnClickListener { loadData() }
        }
        top.addView(refreshButton, LinearLayout.LayoutParams(52, 48))
        root.addView(top)

        statusView = label("Подключаюсь к серверу…", 12, false, muted)
        statusView.setPadding(dp(12), dp(9), dp(12), dp(9))
        statusView.background = GradientDrawable().apply {
            setColor(Color.rgb(14, 31, 47))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(35, 69, 84))
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(5, 5, 5, 5)
            background = rounded(Color.rgb(13, 23, 39), 17)
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 64).apply { topMargin = 12; bottomMargin = 14 })

        leagueBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Create the horizontal scroller once. Reparenting leagueBar during render()
        // caused "The specified child already has a parent" and crashed the app.
        leagueScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(leagueBar)
        }
        root.addView(leagueScroll)
        matchesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(matchesContainer)
        bets = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
        // Показываем рабочую оболочку сразу, не дожидаясь сети.
        renderTabs()
        leagueBar.visibility = View.VISIBLE
        val loadingCard = card()
        addText(loadingCard, "●  MATCH CENTER", 10, true, cyan)
        addText(loadingCard, "Интерфейс готов. Получаю матчи и прогнозы…", 13, false, muted)
        loadData()
    }

    private fun label(value: String, size: Int, bold: Boolean = false, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = if (size <= 10) 0.09f else 0.01f
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun card(parent: LinearLayout = matchesContainer): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(18, 34, 53), Color.rgb(9, 21, 36))).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(34, 57, 78)) }
            elevation = dp(2).toFloat()
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 7, 0, 9)
        return box.also { parent.addView(it, lp) }
    }

    private fun addText(box: LinearLayout, value: String, size: Int = 13, bold: Boolean = false, color: Int = Color.WHITE) {
        box.addView(label(value, size, bold, color))
    }

    private fun renderOdds(parent: LinearLayout, odds: JSONObject?) {
        val best = odds?.optJSONObject("best")
        if (best == null) {
            addText(parent, "КОЭФФИЦИЕНТЫ MELBET", 9, true, muted)
            addText(parent, "Нет свежих котировок для этого матча", 11, false, muted)
            return
        }
        val panelOdds = card(parent)
        panelOdds.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(39, 35, 29), Color.rgb(24, 29, 39))).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(105, 83, 52)) }
        addText(panelOdds, "КОЭФФИЦИЕНТЫ БУКМЕКЕРОВ", 9, true, gold)
        var shown = 0
        fun validPrice(obj: JSONObject, key: String): Boolean {
            if (!obj.has(key) || obj.isNull(key)) return false
            val price = obj.optDouble(key, Double.NaN)
            return price.isFinite() && price > 1.0
        }
        val dc = best.optJSONObject("doubleChance")
        if (dc != null) {
            val items = listOf("1x" to "1X", "x2" to "X2", "12" to "12")
                .filter { validPrice(dc, it.first) }
                .map { Triple(it.second, oddsText(dc, it.first), gold) }
            if (items.isNotEmpty()) {
                addText(panelOdds, "ДВОЙНОЙ ШАНС · лучшие цены", 10, true, muted)
                addMarketRow(panelOdds, items)
                shown += items.size
            }
        }
        val btts = best.optJSONObject("btts")
        if (btts != null) {
            val items = listOf("yes" to "ОЗ — да", "no" to "ОЗ — нет")
                .filter { validPrice(btts, it.first) }
                .map { Triple(it.second, oddsText(btts, it.first), gold) }
            if (items.isNotEmpty()) {
                addText(panelOdds, "ОБЕ КОМАНДЫ ЗАБЬЮТ · MELBET", 10, true, muted)
                addMarketRow(panelOdds, items)
                shown += items.size
            }
        }
        val matchTotals = best.optJSONArray("totals")
        if (matchTotals != null && matchTotals.length() > 0) {
            addText(panelOdds, "ТОТАЛ МАТЧА · MELBET", 10, true, muted)
            for (i in 0 until matchTotals.length()) {
                val line = matchTotals.optJSONObject(i) ?: continue
                val point = line.optDouble("point", Double.NaN)
                val price = line.optDouble("price", Double.NaN)
                val side = line.optString("side")
                if (!point.isFinite() || side !in listOf("over", "under") || !price.isFinite() || price <= 1.0) continue
                if (side != "over") continue
                addMarketRow(panelOdds, listOf(Triple("БОЛЬШЕ ${fmt(point)}", oddsText(line, "price"), gold)))
                shown++
            }
        }
        val spreads = best.optJSONArray("spreads")
        if (spreads != null && spreads.length() > 0) {
            var headingShown = false
            for (i in 0 until spreads.length()) {
                val line = spreads.optJSONObject(i) ?: continue
                val point = line.optDouble("point", Double.NaN)
                val price = line.optDouble("price", Double.NaN)
                if (!point.isFinite() || !price.isFinite() || price <= 1.0) continue
                if (!headingShown) {
                    addText(panelOdds, "ФОРЫ · лучшие цены MELBET", 10, true, muted)
                    headingShown = true
                }
                val team = if (line.optString("team") == "home") "Фора 1" else "Фора 2"
                val signedPoint = if (point > 0) "+${fmt(point)}" else fmt(point)
                addMarketRow(panelOdds, listOf(Triple("$team ($signedPoint)", oddsText(line,"price"), gold)))
                shown++
            }
        }
        val teamTotals = best.optJSONArray("teamTotals")
        if (teamTotals != null && teamTotals.length() > 0) {
            var headingShown = false
            for (i in 0 until teamTotals.length()) {
                val line = teamTotals.optJSONObject(i) ?: continue
                val price = line.optDouble("price", Double.NaN)
                val point = line.optDouble("point", Double.NaN)
                if (line.optString("side") != "over" || !price.isFinite() || price <= 1.0 || !point.isFinite()) continue
                if (!headingShown) {
                    addText(panelOdds, "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ · MELBET", 10, true, muted)
                    headingShown = true
                }
                val team = if (line.optString("team") == "home") "Хозяева" else "Гости"
                addMarketRow(panelOdds, listOf(Triple("$team БОЛЬШЕ ${fmt(point)}", oddsText(line, "price"), gold)))
                shown++
            }
        }
        if (shown == 0) {
            addText(panelOdds, "Источник пока не вернул котировки по нужным рынкам", 11, false, muted)
        }
        addText(panelOdds, "Обновлено: " + odds.optString("updatedAt","—").replace("T"," ").take(16) + " UTC", 9, false, muted)
    }

    private fun oddsText(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "—" else String.format(Locale.US, "%.2f", obj.optDouble(key))

    private fun addMarketRow(parent: LinearLayout, items: List<Triple<String, String, Int>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        items.forEach { item ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(29, 67, 74), Color.rgb(18, 42, 58))).apply { cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.rgb(55, 103, 105)) }
            }
            tile.addView(label(item.first, 9, true, muted))
            tile.addView(label(item.second, 19, true, item.third))
            row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) })
        }
        parent.addView(row)
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
                    tabViewCache.clear()
                    renderedKey = null
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
        val items = listOf("matches" to "ГЛАВНАЯ", "desk" to "AI", "history" to "БАНК", "stats" to "СТАТИСТИКА", "help" to "ПРОФИЛЬ")
        // Создаём кнопки меню один раз: при переключении меняем только оформление,
        // не пересоздавая сами View и обработчики нажатий.
        if (tabBar.childCount != items.size) {
            tabBar.removeAllViews()
            items.forEach { item ->
                val t = label(item.second, 11, true, muted).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    includeFontPadding = false
                    setPadding(dp(3), dp(5), dp(3), dp(5))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (currentTab != item.first) {
                            currentTab = item.first
                            render()
                        }
                    }
                }
                tabBar.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
        items.forEachIndexed { index, item ->
            val t = tabBar.getChildAt(index) as? TextView ?: return@forEachIndexed
            val active = currentTab == item.first
            t.setTextColor(if (active) bg else muted)
            t.background = if (active) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(89, 224, 176), Color.rgb(91, 190, 255))).apply {
                    cornerRadius = dp(15).toFloat()
                }
            } else {
                GradientDrawable().apply {
                    setColor(Color.rgb(21, 36, 55))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), Color.rgb(39, 61, 82))
                }
            }
            t.elevation = if (active) dp(3).toFloat() else 0f
        }
    }

    private data class EvaluatedMatch(
        val league: String, val home: String, val away: String, val score: String,
        val predicted: String, val actual: String, val correct: Boolean,
        val date: String, val confidence: Double
    )

    private fun renderStats(json: JSONObject) {
        addText(matchesContainer, "PERFORMANCE CENTER", 9, true, cyan)
        addText(matchesContainer, "Статистика и точность модели", 22, true)
        addText(matchesContainer, "Здесь учитываются только прогнозы, зафиксированные до начала матча. После финального счёта результат добавляется к архиву; исходный прогноз не пересчитывается.", 11, false, muted)
        data class BandRow(val label: String, val n: Int, val hits: Int)
        data class MetricRow(val name: String, val n: Int, val hits: Int, val brier: Double, val logLoss: Double, val bands: List<BandRow>)
        val metrics = mutableListOf<MetricRow>()
        val evaluatedMatches = json.optJSONArray("evaluationMatches")
        fun collect(key: String, title: String, actual: (Int, Int) -> Boolean, excludeDraws: Boolean = false) {
            var n = 0; var hits = 0; var brier = 0.0; var logLoss = 0.0
            val bandCounts = IntArray(4)
            val bandHits = IntArray(4)
            if (evaluatedMatches != null) for (i in 0 until evaluatedMatches.length()) {
                val m = evaluatedMatches.optJSONObject(i) ?: continue
                if (m.optString("status") != "FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) continue
                val p = m.optJSONObject("prediction") ?: continue
                if (!p.has(key) || p.isNull(key)) continue
                val h = m.optInt("homeScore"); val aw = m.optInt("awayScore")
                if (excludeDraws && h == aw) continue
                val outcome = actual(h, aw)
                val probability = p.optDouble(key).coerceIn(0.000000000001, 0.999999999999)
                val predictedYes = probability >= 0.5
                val confidence = maxOf(probability, 1.0 - probability)
                val band = when {
                    confidence < 0.6 -> 0
                    confidence < 0.7 -> 1
                    confidence < 0.8 -> 2
                    else -> 3
                }
                val correct = outcome == predictedYes
                val y = if (outcome) 1.0 else 0.0
                n++; if (correct) hits++
                bandCounts[band]++
                if (correct) bandHits[band]++
                brier += (probability-y)*(probability-y)
                logLoss += -(y*kotlin.math.ln(probability)+(1.0-y)*kotlin.math.ln(1.0-probability))
            }
            if (n > 0) {
                val labels = listOf("50–59%", "60–69%", "70–79%", "80%+")
                val bands = labels.indices.map { BandRow(labels[it], bandCounts[it], bandHits[it]) }
                metrics.add(MetricRow(title,n,hits,brier/n,logLoss/n,bands))
            }
        }
        collect("1x", "1X · хозяева победят ИЛИ будет ничья", { h,a -> h >= a })
        collect("x2", "X2 · гости победят ИЛИ будет ничья", { h,a -> a >= h })
        collect("homeOver15", "ИТБ 1.5 · хозяева", { h,_ -> h >= 2 })
        collect("awayOver15", "ИТБ 1.5 · гости", { _,a -> a >= 2 })
        collect("over25", "ТБ 2.5 · всего минимум 3 гола", { h,a -> h + a >= 3 })
        collect("bttsYes", "ОЗ — да", { h,a -> h >= 1 && a >= 1 })
        collect("bttsNo", "ОЗ — нет", { h,a -> h == 0 || a == 0 })
        collect("over15", "ТБ 1.5", { h,a -> h+a >= 2 })
        collect("over35", "ТБ 3.5", { h,a -> h+a >= 4 })
        collect("homeOver25", "ИТБ 2.5 · хозяева", { h,_ -> h >= 3 })
        collect("awayOver25", "ИТБ 2.5 · гости", { _,a -> a >= 3 })
        if (metrics.isEmpty()) {
            val empty = card()
            addText(empty, "Архив честных прогнозов только начинается. Матчи будут учитываться после фиксации прогноза до стартового свистка и получения финального счёта.", 12, false, muted)
        } else metrics.forEach { metric ->
            val box = card()
            addText(box, metric.name, 14, true)
            addText(box, "Попадания при пороге 50%: ${metric.hits}/${metric.n} (${String.format(Locale.US, "%.1f", metric.hits*100.0/metric.n)}%)", 11, true, accent)
            addText(box, "ПОПАДАНИЯ ПО УВЕРЕННОСТИ ПРОГНОЗА", 9, true, cyan)
            metric.bands.forEach { band ->
                val rate = if (band.n > 0) String.format(Locale.US, "%.1f%%", band.hits*100.0/band.n) else "—"
                addText(box, "${band.label}: ${band.hits}/${band.n} ($rate)", 11, false, if (band.n > 0) Color.WHITE else muted)
            }
            addText(box, "Brier Score: ${String.format(Locale.US, "%.4f", metric.brier)} · Log Loss: ${String.format(Locale.US, "%.4f", metric.logLoss)}", 11, false, muted)
            addText(box, "Бинарный рынок; оценка вероятностей по завершённым матчам.", 10, false, muted)
        }
        val explainer = card()
        addText(explainer, "КАК ЧИТАТЬ МЕТРИКИ", 10, true, accent)
        addText(explainer, "Диапазон — это уверенность в выбранной стороне: max(p, 1−p). Например, вероятность события 28% означает прогноз на противоположный исход с уверенностью 72% и попадёт в диапазон 70–79%. В каждом диапазоне показаны верные прогнозы / все прогнозы. Brier Score и Log Loss: чем ниже, тем лучше. Попадания не равны доходности.", 11, false, muted)
        val footer = card()
        addText(footer, "Учитываются только неизменяемые прогнозы, зафиксированные до начала матча. Старые пересчитываемые прогнозы в эту статистику не включены.", 10, false, muted)
    }


    private fun saveBets() {
        betPrefs.edit().putString("bets", bets.toString()).apply()
        tabViewCache.remove("history|ALL")
    }
    private fun actionButton(parent: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = title; textSize = 12f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8)); setTextColor(Color.rgb(5, 24, 31))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(89,224,176), Color.rgb(91,190,255))).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(2).toFloat(); setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 8 })
    }
    private fun showAddBetDialog(m: JSONObject, league: String) {
        val p = m.optJSONObject("prediction") ?: return
        val markets = mutableListOf(
            BetMarket("1x","1X · хозяева победят ИЛИ будет ничья"),
            BetMarket("x2","X2 · гости победят ИЛИ будет ничья")
        ).apply {
            add(BetMarket("homeOver15","ИТБ 1.5 · ${m.optString("home")}"))
            add(BetMarket("awayOver15","ИТБ 1.5 · ${m.optString("away")}"))
            add(BetMarket("bttsYes","ОЗ — да"))
            add(BetMarket("bttsNo","ОЗ — нет"))
            for (line in listOf("15", "25", "35")) {
                val label = when (line) { "15" -> "1.5"; "25" -> "2.5"; else -> "3.5" }
                add(BetMarket("over$line","ТБ $label"))
            }
            for ((teamKey, teamName) in listOf("home" to m.optString("home"), "away" to m.optString("away"))) {
                for (line in listOf("05", "15", "25", "35")) {
                    val label = when (line) { "05" -> "0.5"; "15" -> "1.5"; "25" -> "2.5"; else -> "3.5" }
                    add(BetMarket("${teamKey}Over$line","ИТБ $label · $teamName"))
                }
            }
        }.filter { p.has(it.key) && !p.isNull(it.key) }.toMutableList()
        val bestOdds = m.optJSONObject("odds")?.optJSONObject("best")
        val dcOdds = bestOdds?.optJSONObject("doubleChance")
        if (dcOdds != null) listOf("1x", "x2", "12").forEach { key ->
            if (dcOdds.has(key) && !dcOdds.isNull(key) && markets.none { it.key == key })
                markets.add(BetMarket(key, "Двойной шанс $key"))
        }
        val spreadLines = bestOdds?.optJSONArray("spreads")
        if (spreadLines != null) for (i in 0 until spreadLines.length()) {
            val line = spreadLines.optJSONObject(i) ?: continue
            val point = line.optDouble("point", 0.0)
            if (kotlin.math.abs(point) < 0.001 ||
                kotlin.math.abs(point * 2.0 - kotlin.math.round(point * 2.0)) > 0.001) continue
            val team = line.optString("team")
            val key = "spread:$team:${String.format(Locale.US, "%.1f", point)}"
            val teamName = if (team == "home") m.optString("home") else m.optString("away")
            val signed = if (point > 0) "+${fmt(point)}" else fmt(point)
            markets.add(BetMarket(key, "Фора $teamName $signed"))
        }
        if (markets.isEmpty()) return
        val form = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,0) }
        form.addView(label(m.optString("home")+" — "+m.optString("away"),16,true))
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,markets.map { it.label })
        form.addView(spin)
        fun field(h: String) = EditText(this).apply { hint=h; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val odds=field("Коэффициент (например 1.85)"); val stake=field("Сумма ставки · ровно 5% банка")
        val suggestedStake = currentBankroll() * 0.05
        stake.setText(String.format(Locale.US, "%.2f", suggestedStake))
        form.addView(odds); form.addView(stake)
        fun suggestedOdds(marketKey: String): Double? {
            val best = m.optJSONObject("odds")?.optJSONObject("best") ?: return null
            val h = best.optJSONObject("h2h"); val t = best.optJSONObject("totals")
            val value = when(marketKey) {
                "1" -> h?.optDouble("home", Double.NaN)
                "x" -> h?.optDouble("draw", Double.NaN)
                "2" -> h?.optDouble("away", Double.NaN)
                "1x", "x2", "12" -> best.optJSONObject("doubleChance")?.optDouble(marketKey, Double.NaN)
                "bttsYes", "bttsNo" -> best.optJSONObject("btts")?.optDouble(if (marketKey == "bttsYes") "yes" else "no", Double.NaN)
                else -> {
                    val side = when {
                        marketKey.startsWith("over") || marketKey.contains("Over") -> "over"
                        marketKey.startsWith("under") || marketKey.contains("Under") -> "under"
                        else -> null
                    }
                    val point = marketKey.takeLast(2).toDoubleOrNull()?.div(10.0)
                    if (side == null || point == null) null
                    else if (marketKey.startsWith("home") || marketKey.startsWith("away")) {
                        val team = if (marketKey.startsWith("home")) "home" else "away"
                        val rows = best.optJSONArray("teamTotals")
                        (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
                            .firstOrNull { it.optString("team") == team && it.optString("side") == side &&
                                kotlin.math.abs(it.optDouble("point") - point) < 0.001 }
                            ?.optDouble("price", Double.NaN)
                    } else {
                        val rows = best.optJSONArray("totals")
                        (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
                            .firstOrNull { it.optString("side") == side && kotlin.math.abs(it.optDouble("point") - point) < 0.001 }
                            ?.optDouble("price", Double.NaN)
                    }
                }
            }
            return value?.takeIf { it.isFinite() && it > 1.0 }
        }
        fun fillSuggested() {
            val value = suggestedOdds(markets[spin.selectedItemPosition].key)
            odds.setText(value?.let { String.format(Locale.US, "%.2f", it) } ?: "")
        }
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { fillSuggested() }
        }
        fillSuggested()
        val dlg=AlertDialog.Builder(this).setTitle("Новая ставка").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val o=odds.text.toString().replace(',','.').toDoubleOrNull()
                val st=stake.text.toString().replace(',','.').toDoubleOrNull()
                if(o==null || o<=1.0 || st==null || st<=0.0 || kotlin.math.abs(st - currentBankroll() * 0.05) > 0.01) { Toast.makeText(this,"Сумма ставки должна быть ровно 5% текущего банка",Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val mk=markets[spin.selectedItemPosition]
                bets.put(JSONObject().apply {
                    put("id",System.currentTimeMillis().toString())
                    put("matchId",m.optString("id",m.optString("utcDate")+m.optString("home")+m.optString("away")))
                    put("date",m.optString("utcDate")); put("league",league); put("home",m.optString("home")); put("away",m.optString("away"))
                    put("marketKey",mk.key); put("market",mk.label); put("probability",p.optDouble(mk.key)); put("odds",o); put("stake",st); put("status","PENDING")
                })
                saveBets(); dlg.dismiss(); currentTab="history"; render()
            }
        }
        dlg.show()
    }
    private fun findMatch(j: JSONObject,b: JSONObject): JSONObject? {
        val cs=j.optJSONArray("competitions") ?: return null
        for(i in 0 until cs.length()) { val ms=cs.getJSONObject(i).optJSONArray("matches") ?: continue
            for(k in 0 until ms.length()) { val m=ms.getJSONObject(k)
                val id=m.optString("id",m.optString("utcDate")+m.optString("home")+m.optString("away"))
                if(id==b.optString("matchId")) return m
            }
        }
        return null
    }
    private fun settleBet(b: JSONObject,m: JSONObject) {
        if(b.optString("status")!="PENDING" || m.optString("status")!="FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) return
        val h=m.optInt("homeScore"); val a=m.optInt("awayScore")
        val won: Boolean?=when(b.optString("marketKey")) {
            "1x"->h>=a; "x2"->a>=h; "12"->h!=a
            "1"->h>a; "x"->h==a; "2"->a>h; "over25"->h+a>=3; "under25"->h+a<=2
            "bttsYes"->h>0&&a>0; "bttsNo"->h==0||a==0; "homeOver15"->h>=2; "awayOver15"->a>=2
            else -> {
                val marketKey = b.optString("marketKey")
                val line = marketKey.takeLast(2).toDoubleOrNull()?.div(10.0)
                val wonTotal = when {
                    marketKey.startsWith("over") && line != null -> (h + a).toDouble() > line
                    marketKey.startsWith("under") && line != null -> (h + a).toDouble() < line
                    marketKey.startsWith("homeOver") && line != null -> h.toDouble() > line
                    marketKey.startsWith("homeUnder") && line != null -> h.toDouble() < line
                    marketKey.startsWith("awayOver") && line != null -> a.toDouble() > line
                    marketKey.startsWith("awayUnder") && line != null -> a.toDouble() < line
                    else -> false
                }
                if (marketKey.startsWith("spread:")) {
                val parts = b.optString("marketKey").split(":")
                val handicap = parts.getOrNull(2)?.toDoubleOrNull()
                    when(parts.getOrNull(1)) {
                        "home" -> handicap?.let { when { h + it > a -> true; h + it < a -> false; else -> null } }
                        "away" -> handicap?.let { when { a + it > h -> true; a + it < h -> false; else -> null } }
                        else -> null
                    }
                } else if (marketKey.startsWith("over") || marketKey.startsWith("under") ||
                    marketKey.startsWith("homeOver") || marketKey.startsWith("homeUnder") ||
                    marketKey.startsWith("awayOver") || marketKey.startsWith("awayUnder")) wonTotal
                else null
            }
        }
        b.put("status",when(won){true->"WON";false->"LOST";null->"PUSH"}); b.put("score","$h:$a")
    }
    private fun fmt(v: Double)=String.format(Locale.getDefault(),"%.2f",v)
    private fun renderBetHistory(j: JSONObject) {
        addText(matchesContainer,"BET TRACKER",9,true,cyan)
        addText(matchesContainer,"История ставок",22,true)
        addText(matchesContainer,"Записи хранятся только на этом устройстве. Коэффициенты вводятся вручную.",11,false,muted)
        var changed=false
        for(i in 0 until bets.length()) { val b=bets.optJSONObject(i) ?: continue; val m=findMatch(j,b)
            if(m!=null && b.optString("status")=="PENDING") { settleBet(b,m); if(b.optString("status")!="PENDING") changed=true }
        }
        if(changed) saveBets()
        var stakeAll=0.0; var turnover=0.0; var profit=0.0; var payout=0.0
        var wins=0; var losses=0; var pushes=0; var pending=0
        for(i in 0 until bets.length()) { val b=bets.optJSONObject(i) ?: continue
            val st=b.optDouble("stake"); val o=b.optDouble("odds"); stakeAll+=st
            when(b.optString("status")) {
                "WON"->{wins++;turnover+=st;profit+=st*(o-1);payout+=st*o}
                "LOST"->{losses++;turnover+=st;profit-=st}
                "PUSH"->{pushes++;payout+=st}
                else->pending++
            }
        }
        val sum=card()
        addText(sum,"СТАТИСТИКА СТАВОК",10,true,accent)
        addText(sum,"Всего: "+bets.length()+" · выиграно: "+wins+" · проиграно: "+losses+" · возврат: "+pushes+" · ожидают: "+pending,12,true)
        addText(sum,"Сумма ставок: "+fmt(stakeAll),13,true)
        addText(sum,"Выплаты: "+fmt(payout)+" · чистый результат: "+(if(profit>=0)"+" else "")+fmt(profit),12,true,if(profit>=0)accent else Color.RED)
        addText(sum,"ROI: "+(if(turnover>0)String.format(Locale.getDefault(),"%.1f%%",profit/turnover*100) else "—")+" · рассчитанный оборот: "+fmt(turnover),12,true)
        addText(sum,"ROI = чистый результат / сумма выигравших и проигравших ставок. Возвраты и ожидающие ставки не входят.",10,false,muted)
        if(bets.length()==0) { val e=card(); addText(e,"На вкладке «Матчи» нажми «Добавить ставку» в карточке игры.",12,false,muted); return }
        for(i in bets.length()-1 downTo 0) { val b=bets.optJSONObject(i) ?: continue; val box=card()
            val st=b.optString("status")
            val status=when(st){"WON"->"ВЫИГРЫШ";"LOST"->"ПРОИГРЫШ";"PUSH"->"ВОЗВРАТ";else->"ОЖИДАЕТ"}
            val col=when(st){"WON"->accent;"LOST"->Color.RED;else->Color.YELLOW}
            addText(box,b.optString("league")+" · "+date(b.optString("date")),10,false,muted)
            addText(box,b.optString("home")+" — "+b.optString("away"),15,true)
            addText(box,b.optString("market")+" · кэф "+fmt(b.optDouble("odds")),12,true,accent)
            addText(box,"Ставка: "+fmt(b.optDouble("stake"))+" · модель: "+String.format(Locale.getDefault(),"%.1f%%",b.optDouble("probability")*100),11,false,muted)
            addText(box,status+(if(b.has("score"))" · счёт "+b.optString("score") else ""),12,true,col)
            if(st=="WON") addText(box,"Чистая прибыль: +"+fmt(b.optDouble("stake")*(b.optDouble("odds")-1)),12,true,accent)
            if(st=="LOST") addText(box,"Убыток: −"+fmt(b.optDouble("stake")),12,true,col)
            if(st=="PUSH") addText(box,"Возврат: "+fmt(b.optDouble("stake")),12,true,accent)
            actionButton(box,"Удалить запись") {
                val id=b.optString("id"); val next=JSONArray()
                for(k in 0 until bets.length()) { val x=bets.optJSONObject(k) ?: continue; if(x.optString("id")!=id)next.put(x) }
                bets=next;saveBets();render()
            }
        }
    }

    private fun renderHelp() {
        fun section(title: String, body: String) {
            val box = card()
            addText(box, title, 14, true, accent)
            addText(box, body, 12, false, Color.WHITE)
        }
        addText(matchesContainer, "IQ MATCH · PROFILE", 9, true, cyan)
        addText(matchesContainer, "Профиль и справка", 22, true)
        val profile = card()
        addText(profile, "IQ MATCH", 18, true, Color.WHITE)
        addText(profile, "Аналитическая платформа футбольных матчей", 11, false, muted)
        addText(profile, "Локальных записей ставок: ${bets.length()} · текущий банк: ${String.format(Locale.US, "%.2f ₽", currentBankroll())}", 12, true, accent)
        addText(profile, "Матчи и xG: PitchAPI · котировки: API-Sport / Melbet", 10, false, muted)
        section("1. КАК РАБОТАЕТ МОДЕЛЬ",
            "Модель оценивает ожидаемые голы команд по историческим данным PitchAPI и рассчитывает вероятности с помощью независимого распределения Пуассона. Параметры: последние 30 матчей, half-life 12, shrinkage 3. Это вероятности, а не точный счёт.")
        section("2. ОСНОВНЫЕ РЫНКИ",
            "1X — победа хозяев или ничья.\nX2 — победа гостей или ничья.\nИТБ 1.5 — команда забьёт минимум 2 гола.\nДля ставок доступны только поддерживаемые ненулевые форы.")
        section("3. ДОПОЛНИТЕЛЬНЫЕ РЫНКИ",
            "ОЗ — да/нет: обе команды забьют хотя бы по одному голу или хотя бы одна команда не забьёт.\nВ карточках отображаются только тоталы БОЛЬШЕ: общие и индивидуальные.\nВероятности рассчитываются по xG независимым распределением Пуассона. Котировки доступны только при наличии свежих данных Melbet от API-Sport.")
        section("4. AI И СИЛЬНЕЙШИЙ СИГНАЛ",
            "В AI показываются матчи на сегодня и ближайшие 14 дней с положительным отклонением от исторической частоты и подтверждённой ценой Melbet от 1.50. Сильнейший сигнал выбирается среди поддерживаемых рынков. Для расчёта базы нужно не менее 100 прошлых матчей лиги. Это не гарантия value или прибыли.")
        section("5. АНАЛИЗ",
            "Для каждого рынка показываются попадания и их доля, разбивка по уверенности 50–59%, 60–69%, 70–79% и 80%+, Brier Score и Log Loss. Уверенность — max(p, 1−p), то есть вероятность выбранной стороны. Чем ниже Brier Score и Log Loss, тем лучше.")
        section("6. ИСТОЧНИКИ И ОГРАНИЧЕНИЯ",
            "PitchAPI — источник расписания, результатов и shot-level xG. API-Sport — источник котировок только Melbet. Неполный xG не заменяется нулём; матчи без парного xG не участвуют в xG-обучении. Статистика «Анализа» — мониторинг сохранённых прогнозов, а не независимый тест и не показатель доходности. Для оценки прибыли нужны реальные коэффициенты Melbet на момент прогноза и walk-forward проверка.")
    }

    private fun topSignalOdds(signal: JSONObject, match: JSONObject): Double? {
        val side = signal.optString("side")
        val best = match.optJSONObject("odds")?.optJSONObject("best") ?: return null
        if (side == "1X" || side == "X2") {
            val dc = best.optJSONObject("doubleChance") ?: return null
            val key = if (side == "1X") "1x" else "x2"
            val price = dc.optDouble(key, Double.NaN)
            return price.takeIf { it.isFinite() && it >= 1.50 }
        }
        if (side.startsWith("ИТБ 1.5 · ")) {
            val teamName = side.substringAfter("ИТБ 1.5 · ").trim()
            val team = when (teamName) {
                match.optString("home") -> "home"
                match.optString("away") -> "away"
                else -> return null
            }
            val rows = best.optJSONArray("teamTotals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val point = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (row.optString("team") == team && row.optString("side") == "over" &&
                    point.isFinite() && kotlin.math.abs(point - 1.5) < 0.001 &&
                    price.isFinite() && price >= 1.50) return price
            }
        }
        return null
    }

    // Bank = initial bankroll + settled net P/L - stakes reserved on pending bets.
    private fun currentBankroll(): Double {
        var bank = 2500.0
        for (i in 0 until bets.length()) {
            val b = bets.optJSONObject(i) ?: continue
            val stake = b.optDouble("stake", 0.0)
            val odds = b.optDouble("odds", 0.0)
            when (b.optString("status")) {
                "WON" -> bank += stake * (odds - 1.0)
                "LOST" -> bank -= stake
                "PENDING" -> bank -= stake
                // PUSH does not change the bankroll.
            }
        }
        return bank.coerceAtLeast(0.0)
    }

    private fun renderBettingDesk(json: JSONObject) {
        addText(matchesContainer, "AI · MATCH INTELLIGENCE", 9, true, cyan)
        addText(matchesContainer, "AI-аналитика и сигналы", 22, true)
        addText(matchesContainer, "Каждая рекомендация содержит обоснование, проверку цены и ограничения модели.", 11, false, muted)
        val target = card()
        addText(target, "ГОДОВАЯ ЦЕЛЬ · 2 500 ₽ → 100 000 ₽", 10, true, gold)
        addText(target, "Для роста в 40 раз за год нужен средний сложный рост около 35.9% в месяц. Это крайне амбициозная математическая цель, не прогноз и не обещание.", 12, true)
        addText(target, "Не будем повышать риск, догонять проигрыши или ставить ради выполнения календарного плана.", 11, false, muted)
        val bank = currentBankroll()
        val stakePlan = bank * 0.05
        val bankrollCard = card()
        addText(bankrollCard, "ТЕКУЩИЙ БАНК", 10, true, cyan)
        addText(bankrollCard, String.format(Locale.US, "%.2f ₽", bank), 24, true, Color.WHITE)
        addText(bankrollCard, "Ставка 5%: " + String.format(Locale.US, "%.2f ₽", stakePlan), 15, true, gold)
        addText(bankrollCard, "Размер фиксирован: при сохранении ставки сумма пересчитывается от текущего банка. Ожидающие ставки уже зарезервированы.", 10, false, muted)
        val candidates = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val matchDay = m.optString("utcDate").take(10)
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
                val end = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { add(java.util.Calendar.DAY_OF_YEAR, 14) }
                val endDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(end.time)
                if (matchDay < today || matchDay > endDay) continue
                val signal = m.optJSONObject("prediction")?.optJSONObject("strongestSignal") ?: continue
                if (signal.optBoolean("noPositiveSignal") || signal.optDouble("edgePp") <= 0.0) continue
                val price = topSignalOdds(signal, m) ?: continue
                if (price < 1.50) continue
                candidates.add(Triple(m, league.optString("name"), signal))
            }
        }
        val sorted = candidates.sortedByDescending { it.third.optDouble("edgePp") }
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "Нет подтверждённых рекомендаций.", 15, true, gold)
            addText(empty, "Нужны положительный сигнал и актуальная прематч-котировка Melbet от 1.50. Не подменяем отсутствующие цены котировками других букмекеров.", 11, false, muted)
            return
        }
        sorted.forEachIndexed { index, item ->
            val (m, league, signal) = item
            val p = signal.optDouble("modelProbability", Double.NaN)
            val baseline = signal.optDouble("baselineProbability", Double.NaN)
            val edge = signal.optDouble("edgePp", Double.NaN)
            val odds = topSignalOdds(signal, m) ?: return@forEachIndexed
            val implied = 1.0 / odds
            val ev = p * odds - 1.0
            val box = card()
            addText(box, "КАНДИДАТ #${index + 1} · ${league.uppercase(Locale.getDefault())}", 10, true, accent)
            addText(box, "${m.optString("home")} — ${m.optString("away")}", 16, true)
            addText(box, date(m.optString("utcDate")), 10, false, muted)
            addText(box, "Рынок: ${signal.optString("side")} · Melbet ${String.format(Locale.US, "%.2f", odds)}", 14, true, gold)
            addText(box, "ПОЧЕМУ ВЫБРАНО", 10, true, cyan)
            addText(box, "Модель: ${String.format(Locale.US, "%.1f", p * 100)}%; историческая база: ${String.format(Locale.US, "%.1f", baseline * 100)}% на ${signal.optInt("baselineMatches")} матчах.", 11, false, Color.WHITE)
            addText(box, "Отклонение модели от базы: +${String.format(Locale.US, "%.1f", edge)} п.п. Это фильтр для анализа, но не доказательство value относительно букмекера.", 11, false, muted)
            addText(box, "ПРОВЕРКА ЦЕНЫ", 10, true, cyan)
            addText(box, "Безубыточная вероятность по коэффициенту: ${String.format(Locale.US, "%.1f", implied * 100)}%. Простое EV модели: ${if (ev >= 0) "+" else ""}${String.format(Locale.US, "%.1f", ev * 100)}%.", 11, false, Color.WHITE)
            addText(box, "EV = p × коэффициент − 1; показатель не учитывает маржу, ошибку калибровки и неопределённость модели.", 10, false, muted)
            addText(box, "РИСКИ", 10, true, gold)
            addText(box, "Историческая частота может не отражать текущую силу команд. Перепроверь цену в Melbet перед ставкой; если коэффициент ниже 1.50 или ухудшился — пропускаем.", 11, false, muted)
            addText(box, "Это аналитический кандидат, не приказ ставить. При сомнительном преимуществе — NO BET.", 11, true, gold)
            actionButton(box, "＋ Зафиксировать ставку вручную") { showAddBetDialog(m, league) }
        }
        val footer = card()
        addText(footer, "ПРАВИЛА AI", 10, true, accent)
        addText(footer, "AI отбирает только сигналы с положительным edge и подтверждённой котировкой Melbet от 1.50. ОЗ и тоталы показываются в карточках матчей. Котировки — только API-Sport / Melbet.", 11, false, muted)
    }

    private fun renderDay(json: JSONObject) {
        addText(matchesContainer, "DAILY SIGNAL FILTER", 9, true, cyan)
        addText(matchesContainer, "Ставки дня", 22, true)
        addText(matchesContainer, "Только самые сильные положительные сигналы модели с подтверждённым коэффициентом от 1.50 на выбранную сторону.", 11, false, muted)
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val today = dayFormat.format(java.util.Date())
        val endCalendar = java.util.Calendar.getInstance(utc).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 14)
        }
        val endDate = dayFormat.format(endCalendar.time)
        val items = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED")) continue
                val matchDay = m.optString("utcDate").take(10)
                if (matchDay < today || matchDay > endDate) continue
                val prediction = m.optJSONObject("prediction") ?: continue
                val signal = prediction.optJSONObject("strongestSignal") ?: continue
                val edge = signal.optDouble("edgePp", 0.0)
                if (signal.optBoolean("noPositiveSignal") || edge <= 0.0) continue
                // Require a real quote for the exact strongest side, at decimal odds >= 1.50.
                if (topSignalOdds(signal, m) == null) continue
                items.add(Triple(m, league.optString("name"), signal))
            }
        }
        val sorted = items.sortedByDescending { it.third.optDouble("edgePp") }
        val summary = card()
        addText(summary, "СИЛЬНЫЕ СИГНАЛЫ · КЭФ ОТ 1.50", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Только матчи с рассчитанным прогнозом и положительным отклонением от исторической базы. Это фильтр модели, не рекомендация ставить.", 11, false, muted)
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "Нет матчей, одновременно прошедших фильтр сильнейшего сигнала и коэффициента от 1.50.", 13, true)
            addText(empty, "В TOP попадают только матчи с полноценным прогнозом, положительным сильнейшим сигналом и реальной котировкой от 1.50 именно на выбранный исход.", 11, false, muted)
            return
        }
        sorted.forEachIndexed { index, item ->
            val (m, leagueName, signal) = item
            val box = card()
            addText(box, "#${index + 1}  ·  ${leagueName.uppercase(Locale.getDefault())}  ·  ${date(m.optString("utcDate"))}", 10, true, accent)
            addText(box, "${m.optString("home")}  —  ${m.optString("away")}", 16, true, Color.WHITE)
            addText(box, signal.optString("side"), 14, true, gold)
            addText(box, "+${String.format(Locale.US, "%.1f", signal.optDouble("edgePp"))} п.п. к исторической базе", 13, true, accent)
            val signalOdds = topSignalOdds(signal, m)
            addText(box, "Коэффициент на сигнал: ${String.format(Locale.US, "%.2f", signalOdds ?: 0.0)}", 14, true, gold)
            addText(box, "Вероятность модели: ${String.format(Locale.US, "%.1f", signal.optDouble("modelProbability") * 100)}% · база: ${String.format(Locale.US, "%.1f", signal.optDouble("baselineProbability") * 100)}%", 11, false, muted)
            addText(box, "Историческая выборка: ${signal.optInt("baselineMatches")} матчей до даты игры", 10, false, muted)
            renderOdds(box, m.optJSONObject("odds"))
            actionButton(box, "＋ Добавить ставку") { showAddBetDialog(m, leagueName) }
        }
        val note = card()
        addText(note, "ВАЖНО", 10, true, gold)
        addText(note, "Сильный сигнал означает отличие от исторической частоты, а не доказанное преимущество над букмекерской линией. Проверяйте коэффициент и не воспринимайте список как гарантию доходности.", 11, false, muted)
    }

    private fun render() {
        val json = data ?: return
        val targetKey = currentTab + "|" + if (currentTab == "matches") selectedLeague else "ALL"

        // Снимаем текущие View с экрана и кладём в кеш, не уничтожая их.
        if (renderedKey != null && renderedKey != targetKey && matchesContainer.childCount > 0) {
            val oldContent = mutableListOf<View>()
            while (matchesContainer.childCount > 0) {
                val child = matchesContainer.getChildAt(0)
                matchesContainer.removeViewAt(0)
                oldContent.add(child)
            }
            val oldLeagueBar = mutableListOf<View>()
            while (leagueBar.childCount > 0) {
                val child = leagueBar.getChildAt(0)
                leagueBar.removeViewAt(0)
                oldLeagueBar.add(child)
            }
            tabViewCache[renderedKey!!] = oldContent to oldLeagueBar
        }

        matchesContainer.removeAllViews()
        leagueBar.removeAllViews()
        val leagues = json.optJSONArray("competitions")
        val state = json.optString("status", "unknown")
        statusView.text = "●  " + json.optString("notice", "Статус: " + state)
        renderTabs()

        // Возвращаем готовый экран мгновенно. Полная отрисовка нужна только при первом входе.
        val cached = tabViewCache[targetKey]
        if (cached != null) {
            cached.first.forEach { matchesContainer.addView(it) }
            cached.second.forEach { leagueBar.addView(it) }
            leagueBar.visibility = if (currentTab == "matches") View.VISIBLE else View.GONE
            renderedKey = targetKey
            return
        }
        renderedKey = targetKey

        if (currentTab == "matches") {
            val summary = card()
            addText(summary, "●  LIVE MODEL DASHBOARD", 9, true, cyan)
            addText(summary, "30 матчей  ·  half-life 12  ·  shrinkage 3", 11, false, muted)
            addText(summary, "Обновлено: ${json.optString("updatedAt", "—").replace("T", " ").take(16)}", 10, false, muted)
        }

        if (currentTab == "day") {
            leagueBar.visibility = View.GONE
            renderDay(json)
            return
        }
        if (currentTab == "desk") {
            leagueBar.visibility = View.GONE
            renderBettingDesk(json)
            return
        }
        if (currentTab == "history") {
            leagueBar.visibility = View.GONE
            renderBetHistory(json)
            return
        }
        if (currentTab == "stats") {
            leagueBar.visibility = View.GONE
            renderStats(json)
            return
        } else if (currentTab == "help") {
            leagueBar.visibility = View.GONE
            renderHelp()
            return
        } else {
            leagueBar.visibility = View.VISIBLE
        }

        val homeHero = card()
        homeHero.setPadding(dp(18), dp(18), dp(18), dp(18))
        homeHero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(25, 62, 67), Color.rgb(15, 35, 54), Color.rgb(12, 25, 43))
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.rgb(57, 112, 105))
        }
        addText(homeHero, "IQ MATCH  /  MATCH CENTER", 9, true, gold)
        addText(homeHero, "Матчи и прогнозы", 23, true, Color.WHITE)
        addText(homeHero, "Футбольная аналитика на основе статистики, xG и исторических данных.", 11, false, Color.rgb(190, 207, 221))
        val featureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("ВЕРОЯТНОСТИ", "ОЖИДАЕМЫЕ ГОЛЫ", "MELBET").forEachIndexed { index, caption ->
            val chip = label(caption, 8, true, if (index == 2) gold else accent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.argb(42, 4, 12, 22))
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.rgb(59, 91, 103))
                }
            }
            featureRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(2), dp(8), dp(2), dp(2))
            })
        }
        homeHero.addView(featureRow)
        addText(matchesContainer, "РАСПИСАНИЕ", 9, true, cyan)
        addText(matchesContainer, "Выбери лигу и открой карточку матча", 16, true, Color.WHITE)
        addText(matchesContainer, "ВЕРОЯТНОСТИ МОДЕЛИ · КОТИРОВКИ MELBET", 9, true, muted)
        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA")
        // League filters already live in the persistent horizontal scroller above the content.
        codes.forEachIndexed { index, code ->
            val b = TextView(this).apply {
                text = names[index]
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(if (selectedLeague == code) bg else muted)
                background = if (selectedLeague == code) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(89,224,176), Color.rgb(91,190,255))).apply { cornerRadius = dp(28).toFloat() } else rounded(surface, 28)
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
                // Do not show fixtures without a complete model prediction.
                // A scheduled match with insufficient paired xG/history stays out of the app.
                if (m.optString("status") in listOf("SCHEDULED", "TIMED") &&
                    m.optJSONObject("prediction") != null) {
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
                setPadding(15, 15, 15, 15)
                background = rounded(surface, 16)
                background = (background as GradientDrawable).apply { setStroke(1, Color.rgb(38, 58, 83)) }
                isClickable = true
                isFocusable = true
            }
            heading.addView(label(league.optString("name"), 16, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("${upcoming.size} матчей", 11, false, muted))
            val expandIcon = label(if (expanded) "  ▾" else "  ▸", 18, true, accent)
            heading.addView(expandIcon)
            heading.setOnClickListener {
                val nextExpanded = !(expandedLeagues[leagueCode] ?: true)
                expandedLeagues[leagueCode] = nextExpanded
                // Toggle the existing list in place; avoid rebuilding every match card.
                content.visibility = if (nextExpanded) View.VISIBLE else View.GONE
                expandIcon.text = if (nextExpanded) "  ▾" else "  ▸"
            }
            section.addView(heading)
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            section.addView(content)
            matchesContainer.addView(section)
            if (upcoming.isEmpty()) {
                val empty = card(content)
                addText(empty, "Нет предстоящих матчей в расписании поставщика", 12, false, muted)
            }
            upcoming.sortedBy { it.optString("utcDate") }.forEach { m ->
                val box = card(content)
                addText(box, date(m.optString("utcDate")), 11, true, accent)
                addText(box, m.optString("home") + "   —   " + m.optString("away"), 15, true)
                val pred = m.optJSONObject("prediction")
                if (pred == null) {
                    addText(box, m.optString("predictionWarning", "Прогноз пока недоступен"), 11, false, Color.rgb(242, 190, 104))
                } else {
                    val signal = pred.optJSONObject("strongestSignal")
                    val signalBox = card(box)
                    signalBox.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(32, 55, 66), Color.rgb(17, 34, 52))).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(67, 137, 119)) }
                    addText(signalBox, "✦  САМЫЙ СИЛЬНЫЙ СИГНАЛ", 10, true, gold)
                    if (signal == null) {
                        addText(signalBox, "Недостаточно исторических матчей для надёжной базовой частоты.", 11, false, muted)
                    } else {
                        val edge = signal.optDouble("edgePp")
                        if (signal.optBoolean("noPositiveSignal")) {
                            addText(signalBox, "Положительного отклонения нет. Ближе всего к базе: ${signal.optString("side")} · ${String.format(Locale.US, "%.1f", edge)} п.п.", 12, true, Color.rgb(242, 190, 104))
                        } else {
                            addText(signalBox, "${signal.optString("side")} · +${String.format(Locale.US, "%.1f", edge)} п.п. к базе", 15, true, accent)
                        }
                        addText(signalBox, "Вероятность модели: ${String.format(Locale.US, "%.1f", signal.optDouble("modelProbability")*100)}% · историческая база: ${String.format(Locale.US, "%.1f", signal.optDouble("baselineProbability")*100)}%", 11, false, Color.WHITE)
                        addText(signalBox, "${signal.optInt("baselineMatches")} прошлых матчей лиги; база рассчитана до даты игры.", 10, false, muted)
                    }
                    val mainMarkets = card(box)
                    mainMarkets.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(17, 52, 54), Color.rgb(14, 35, 49))).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(45, 102, 91)) }
                    addText(mainMarkets, "✦  ОСНОВНЫЕ ИСХОДЫ", 10, true, accent)
                    addMarketRow(mainMarkets, listOf(Triple("1X · хозяева или ничья", pct(pred, "1x"), Color.WHITE), Triple("X2 · гости или ничья", pct(pred, "x2"), Color.WHITE)))
                    addText(mainMarkets, "ПОБЕДА / НИЧЬЯ", 9, true, muted)
                    addText(mainMarkets, "П1 ${pct(pred, "1")}     X ${pct(pred, "x")}     П2 ${pct(pred, "2")}", 12, true, Color.WHITE)
                    addText(mainMarkets, "ОБЕ ЗАБЬЮТ", 9, true, muted)
                    addText(mainMarkets, "ОЗ: ДА ${pct(pred, "bttsYes")}     НЕТ ${pct(pred, "bttsNo")}", 11, true, Color.WHITE)
                    addText(mainMarkets, "ГОЛЫ · ТОЛЬКО БОЛЬШЕ", 9, true, muted)
                    addText(mainMarkets, "Тотал 1.5: Б ${pct(pred, "over15")}", 11, false, Color.WHITE)
                    addText(mainMarkets, "Тотал 2.5: Б ${pct(pred, "over25")}", 11, false, Color.WHITE)
                    addText(mainMarkets, "Тотал 3.5: Б ${pct(pred, "over35")}", 11, false, Color.WHITE)
                    val teamTotalsHeading = TextView(this).apply {
                        text = "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ · БОЛЬШЕ"
                        textSize = 10f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(gold)
                        setPadding(dp(10), dp(9), dp(10), dp(7))
                        background = GradientDrawable().apply {
                            setColor(Color.argb(38, 224, 180, 85))
                            cornerRadius = dp(9).toFloat()
                            setStroke(dp(1), Color.rgb(112, 88, 48))
                        }
                    }
                    mainMarkets.addView(teamTotalsHeading)
                    for ((teamKey, teamName) in listOf("home" to m.optString("home"), "away" to m.optString("away"))) {
                        addText(mainMarkets, "ИТБ · $teamName", 10, true, accent)
                        addMarketRow(mainMarkets, listOf(
                            Triple("0.5: Б", pct(pred, "${teamKey}Over05"), Color.WHITE),
                            Triple("1.5: Б", pct(pred, "${teamKey}Over15"), Color.WHITE),
                            Triple("2.5: Б", pct(pred, "${teamKey}Over25"), Color.WHITE),
                            Triple("3.5: Б", pct(pred, "${teamKey}Over35"), Color.WHITE)
                        ))
                    }
                    addText(mainMarkets, "ОЖИДАЕМЫЕ ГОЛЫ", 9, true, muted)
                    addText(mainMarkets, "${String.format(Locale.US, "%.2f", pred.optDouble("expectedHomeGoals"))} : ${String.format(Locale.US, "%.2f", pred.optDouble("expectedAwayGoals"))}", 13, true, Color.WHITE)

                    renderOdds(box, m.optJSONObject("odds"))
                    actionButton(box, "＋ Добавить ставку") { showAddBetDialog(m, league.optString("name")) }
                }
            }
        }

        val footer = card()
        addText(footer, "ПОКРЫТИЕ", 10, true, accent)
        addText(footer, "Предстоящих матчей: $totalUpcoming  ·  с прогнозом: $totalPredicted", 13, true)
        addText(footer, "Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", 11, false, muted)
        addText(footer, "Источники: PitchAPI (матчи и xG) · API-Sport / Melbet (котировки).", 10, false, muted)
    }
}