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
            setPadding(18, 46, 18, 34)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 14, 28), Color.rgb(10, 23, 38), Color.rgb(7, 13, 25)))
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("MATCHIQ", 23, true, Color.WHITE))
        brand.addView(label("FOOTBALL ANALYTICS  ·  MODEL 1.3", 9, true, cyan))
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
        statusView.setPadding(0, 8, 0, 14)
        root.addView(statusView)

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(5, 5, 5, 5)
            background = rounded(Color.rgb(13, 23, 39), 17)
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 72).apply { topMargin = 14; bottomMargin = 12 })

        leagueBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(leagueBar)
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
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(22, 39, 61), Color.rgb(13, 27, 45))).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(43, 68, 91)) }
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
        val books = odds?.optJSONArray("bookmakers")
        if (best == null || books == null || books.length() == 0) {
            addText(parent, "КОЭФФИЦИЕНТЫ БУКМЕКЕРОВ", 9, true, muted)
            addText(parent, "Нет свежих котировок для этого матча", 11, false, muted)
            return
        }
        val panelOdds = card(parent)
        panelOdds.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(39, 35, 29), Color.rgb(24, 29, 39))).apply { cornerRadius = 18f; setStroke(1, Color.rgb(105, 83, 52)) }
        addText(panelOdds, "КОЭФФИЦИЕНТЫ БУКМЕКЕРОВ", 9, true, gold)
        val h2h = best.optJSONObject("h2h")
        if (h2h != null) {
            addText(panelOdds, "1X2 · лучшие цены", 10, true, muted)
            addMarketRow(panelOdds, listOf(
                Triple("П1", oddsText(h2h,"home"), gold),
                Triple("НИЧЬЯ", oddsText(h2h,"draw"), gold),
                Triple("П2", oddsText(h2h,"away"), gold)
            ))
        }
        val totals = best.optJSONObject("totals")
        if (totals != null) {
            addText(panelOdds, "ТОТАЛ 2.5 · лучшие цены", 10, true, muted)
            addMarketRow(panelOdds, listOf(
                Triple("БОЛЬШЕ", oddsText(totals,"over25"), gold),
                Triple("МЕНЬШЕ", oddsText(totals,"under25"), gold)
            ))
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
                setPadding(11, 10, 11, 10)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(29, 67, 74), Color.rgb(18, 42, 58))).apply { cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.rgb(55, 103, 105)) }
            }
            tile.addView(label(item.first, 9, true, muted))
            tile.addView(label(item.second, 19, true, item.third))
            row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(3, 5, 3, 5) })
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
        val items = listOf("matches" to "МАТЧИ", "day" to "TOP", "history" to "ИСТОРИЯ", "stats" to "АНАЛИЗ", "help" to "СПРАВКА")
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
        addText(matchesContainer, "Качество прогнозов", 22, true)
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
        collect("dnbHome", "Фора 0 · хозяева", { h,a -> h > a }, true)
        collect("dnbAway", "Фора 0 · гости", { h,a -> a > h }, true)
        collect("homeOver15", "ИТБ 1.5 · хозяева", { h,_ -> h >= 2 })
        collect("awayOver15", "ИТБ 1.5 · гости", { _,a -> a >= 2 })
        collect("over25", "ТБ 2.5 · всего минимум 3 гола", { h,a -> h + a >= 3 })
        collect("bttsYes", "Обе забьют · обе команды минимум по 1 голу", { h,a -> h >= 1 && a >= 1 })
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
            addText(box, if (metric.name.startsWith("Фора 0")) "Фора 0: победа — выигрыш, ничья — возврат, поражение — проигрыш. Вероятность считается без ничьих." else "Бинарный рынок; оценка вероятностей по завершённым матчам.", 10, false, muted)
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
        val markets = listOf(
            BetMarket("1x","1X · хозяева победят ИЛИ будет ничья"), BetMarket("x2","X2 · гости победят ИЛИ будет ничья"),
            BetMarket("dnbHome","Фора 0 · ${m.optString("home")}"), BetMarket("dnbAway","Фора 0 · ${m.optString("away")}"), BetMarket("1","П1"),
            BetMarket("x","Ничья"), BetMarket("2","П2"), BetMarket("over25","ТБ 2.5"),
            BetMarket("under25","ТМ 2.5"), BetMarket("bttsYes","Обе забьют · Да"),
            BetMarket("bttsNo","Обе забьют · Нет"), BetMarket("homeOver15","ИТБ 1.5 · ${m.optString("home")}"),
            BetMarket("awayOver15","ИТБ 1.5 · ${m.optString("away")}")
        ).filter { p.has(it.key) && !p.isNull(it.key) }
        if (markets.isEmpty()) return
        val form = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,0) }
        form.addView(label(m.optString("home")+" — "+m.optString("away"),16,true))
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,markets.map { it.label })
        form.addView(spin)
        fun field(h: String) = EditText(this).apply { hint=h; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val odds=field("Коэффициент (например 1.85)"); val stake=field("Сумма ставки")
        form.addView(odds); form.addView(stake)
        fun suggestedOdds(marketKey: String): Double? {
            val best = m.optJSONObject("odds")?.optJSONObject("best") ?: return null
            val h = best.optJSONObject("h2h"); val t = best.optJSONObject("totals")
            val value = when(marketKey) {
                "1" -> h?.optDouble("home", Double.NaN)
                "x" -> h?.optDouble("draw", Double.NaN)
                "2" -> h?.optDouble("away", Double.NaN)
                "over25" -> t?.optDouble("over25", Double.NaN)
                "under25" -> t?.optDouble("under25", Double.NaN)
                else -> null
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
                if(o==null || o<=1.0 || st==null || st<=0.0) { Toast.makeText(this,"Проверь коэффициент и сумму",Toast.LENGTH_LONG).show(); return@setOnClickListener }
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
            "1x"->h>=a; "x2"->a>=h; "dnbHome"->if(h==a)null else h>a; "dnbAway"->if(h==a)null else a>h
            "1"->h>a; "x"->h==a; "2"->a>h; "over25"->h+a>=3; "under25"->h+a<=2
            "bttsYes"->h>0&&a>0; "bttsNo"->h==0||a==0; "homeOver15"->h>=2; "awayOver15"->a>=2
            else->null
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
        addText(matchesContainer, "КРАТКАЯ СПРАВКА", 9, true, cyan)
        addText(matchesContainer, "Как пользоваться моделью", 22, true)
        section("1. КАК РАБОТАЕТ МОДЕЛЬ",
            "Модель оценивает ожидаемые голы команд по историческим данным Openplay и рассчитывает вероятности с помощью независимого распределения Пуассона. Параметры: последние 30 матчей, half-life 12, shrinkage 3. Это вероятности, а не точный счёт.")
        section("2. ОСНОВНЫЕ РЫНКИ",
            "1X — победа хозяев или ничья.\nX2 — победа гостей или ничья.\nФора 0 — победа выбранной команды; при ничьей возврат.\nИТБ 1.5 — команда забьёт минимум 2 гола.")
        section("3. ДОПОЛНИТЕЛЬНЫЕ РЫНКИ",
            "ТБ 2.5 — в матче будет минимум 3 гола.\nОбе забьют (BTTS) — каждая команда забьёт минимум 1 гол.\nЭти рынки отображаются в карточках и оцениваются в разделе «Анализ», но пока не участвуют в выборе сильнейшего сигнала и списке TOP.")
        section("4. TOP И СИЛЬНЕЙШИЙ СИГНАЛ",
            "В TOP попадают матчи на сегодня с положительным отклонением от исторической частоты. Сильнейший сигнал выбирается среди 1X, X2, форы 0 и ИТБ 1.5 обеих команд. Для расчёта базы нужно не менее 100 прошлых матчей лиги. Это не гарантия value или прибыли.")
        section("5. АНАЛИЗ",
            "Для каждого рынка показываются попадания и их доля, разбивка по уверенности 50–59%, 60–69%, 70–79% и 80%+, Brier Score и Log Loss. Уверенность — max(p, 1−p), то есть вероятность выбранной стороны. Для форы 0 ничьи исключаются. Чем ниже Brier Score и Log Loss, тем лучше.")
        section("6. ИСТОЧНИКИ И ОГРАНИЧЕНИЯ",
            "Openplay — источник исторических xG-данных; Football-Data.org — расписание и результаты. Если команду нельзя надёжно сопоставить с Openplay или нет подходящих данных, прогноз не строится. Статистика «Анализа» — мониторинг сохранённых прогнозов, а не независимый тест и не показатель доходности. Для оценки прибыли нужны реальные коэффициенты на момент прогноза.")
    }

    private fun renderDay(json: JSONObject) {
        addText(matchesContainer, "DAILY SIGNAL FILTER", 9, true, cyan)
        addText(matchesContainer, "Ставки дня", 22, true)
        addText(matchesContainer, "Матчи на сегодня с положительным отклонением модели от исторической базы. Сначала самые сильные сигналы.", 11, false, muted)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val items = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED")) continue
                if (!m.optString("utcDate").startsWith(today)) continue
                val prediction = m.optJSONObject("prediction") ?: continue
                val signal = prediction.optJSONObject("strongestSignal") ?: continue
                val edge = signal.optDouble("edgePp", 0.0)
                if (signal.optBoolean("noPositiveSignal") || edge <= 0.0) continue
                items.add(Triple(m, league.optString("name"), signal))
            }
        }
        val sorted = items.sortedByDescending { it.third.optDouble("edgePp") }
        val summary = card()
        addText(summary, "СИЛЬНЫЕ СИГНАЛЫ СЕГОДНЯ", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Только матчи с рассчитанным прогнозом и положительным отклонением от исторической базы. Это фильтр модели, не рекомендация ставить.", 11, false, muted)
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "На сегодня нет матчей с положительным сильнейшим сигналом модели.", 13, true)
            addText(empty, "Матчи без прогноза, без надёжной исторической базы или без положительного отклонения сюда не попадают.", 11, false, muted)
            return
        }
        sorted.forEachIndexed { index, item ->
            val (m, leagueName, signal) = item
            val box = card()
            addText(box, "#${index + 1}  ·  ${leagueName.uppercase(Locale.getDefault())}  ·  ${date(m.optString("utcDate"))}", 10, true, accent)
            addText(box, "${m.optString("home")}  —  ${m.optString("away")}", 16, true, Color.WHITE)
            addText(box, signal.optString("side"), 14, true, gold)
            addText(box, "+${String.format(Locale.US, "%.1f", signal.optDouble("edgePp"))} п.п. к исторической базе", 13, true, accent)
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

        addText(matchesContainer, "ВЫБЕРИТЕ ЛИГУ", 9, true, muted)
        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA")
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
                if (m.optString("status") in listOf("SCHEDULED", "TIMED", "POSTPONED")) {
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
                    val mainMarkets = card(box)
                    mainMarkets.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(17, 52, 54), Color.rgb(14, 35, 49))).apply { cornerRadius = 20f; setStroke(1, Color.rgb(45, 102, 91)) }
                    addText(mainMarkets, "✦  ОСНОВНЫЕ РЫНКИ", 10, true, accent)
                    addMarketRow(mainMarkets, listOf(Triple("1X · хозяева победят ИЛИ будет ничья", pct(pred, "1x"), Color.WHITE), Triple("X2 · гости победят ИЛИ будет ничья", pct(pred, "x2"), Color.WHITE)))
                    addMarketRow(mainMarkets, listOf(Triple("Фора 0 · ${m.optString("home")}", pct(pred, "dnbHome"), accent), Triple("Фора 0 · ${m.optString("away")}", pct(pred, "dnbAway"), cyan)))
                    addText(mainMarkets, "ГОЛЫ КОМАНД · МИНИМУМ 2", 9, true, muted)
                    addText(mainMarkets, "ИТБ 1.5 · ${m.optString("home")}: ${pct(pred, "homeOver15")}", 12, true, Color.WHITE)
                    addText(mainMarkets, "ИТБ 1.5 · ${m.optString("away")}: ${pct(pred, "awayOver15")}", 12, true, Color.WHITE)
                    val signal = pred.optJSONObject("strongestSignal")
                    val signalBox = card(box)
                    signalBox.background = rounded(Color.rgb(24, 37, 58), 17)
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
                    addText(box, "ВНУТРЕННИЕ ВЕРОЯТНОСТИ ИСХОДА", 10, true, muted)
                    addText(box, "П1 ${pct(pred, "1")}     X ${pct(pred, "x")}     П2 ${pct(pred, "2")}", 13, false, muted)
                    addText(box, "Дополнительные рынки: ТБ 2.5 ${pct(pred, "over25")} · Обе забьют ${pct(pred, "bttsYes")}", 11, false, muted)
                    addText(box, "Ожидаемые голы  ${String.format(Locale.US, "%.2f : %.2f", pred.optDouble("expectedHomeGoals"), pred.optDouble("expectedAwayGoals"))}", 12, true)
                    renderOdds(box, m.optJSONObject("odds"))
                    actionButton(box, "＋ Добавить ставку") { showAddBetDialog(m, league.optString("name")) }
                }
            }
        }

        val footer = card()
        addText(footer, "ПОКРЫТИЕ", 10, true, accent)
        addText(footer, "Предстоящих матчей: $totalUpcoming  ·  с прогнозом: $totalPredicted", 13, true)
        addText(footer, "Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", 11, false, muted)
        addText(footer, "Источник исторических данных: Openplay (Noah Bair), CC BY 4.0.", 10, false, muted)
    }
}
