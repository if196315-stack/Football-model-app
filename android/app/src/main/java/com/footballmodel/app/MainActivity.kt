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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; clipToPadding = false; setPadding(0, 0, 0, 18); overScrollMode = View.OVER_SCROLL_NEVER }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 22, 18, 34)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 14, 28), Color.rgb(10, 23, 38), Color.rgb(7, 13, 25)))
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("FOOTBALL MODEL", 23, true, Color.WHITE))
        brand.addView(label("MATCH INTELLIGENCE  ·  OPENPLAY 1.3", 9, true, cyan))
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
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 14; bottomMargin = 12 })

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
        listOf("matches" to "МАТЧИ", "week" to "НЕДЕЛЯ", "history" to "СТАВКИ", "stats" to "АНАЛИЗ", "help" to "СПРАВКА").forEach { item ->
            val active = currentTab == item.first
            val t = label(item.second, 11, true, if (active) bg else muted).apply {
                gravity = Gravity.CENTER
                background = if (active) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(89,224,176), Color.rgb(91,190,255))).apply { cornerRadius = dp(13).toFloat() } else rounded(Color.TRANSPARENT, 13)
                setPadding(3, 0, 3, 0)
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
        addText(matchesContainer, "PERFORMANCE CENTER", 9, true, cyan)
        addText(matchesContainer, "Качество прогнозов", 22, true)
        addText(matchesContainer, "П1 / X / П2 остаются внутренними вероятностями модели. Главная оценка — по рынкам 1X, X2, фора 0 на каждую команду и индивидуальным тоталам команд.", 11, false, muted)
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
        collect("1x", "1X · хозяева победят ИЛИ будет ничья", { h,a -> h >= a })
        collect("x2", "X2 · гости победят ИЛИ будет ничья", { h,a -> a >= h })
        collect("dnbHome", "Фора 0 · хозяева", { h,a -> h > a }, true)
        collect("dnbAway", "Фора 0 · гости", { h,a -> a > h }, true)
        collect("homeOver15", "ИТБ 1.5 · хозяева", { h,_ -> h >= 2 })
        collect("awayOver15", "ИТБ 1.5 · гости", { _,a -> a >= 2 })
        if (metrics.isEmpty()) {
            val empty = card()
            addText(empty, "Пока нет завершённых матчей с сохранёнными прогнозами по основным рынкам.", 12, false, muted)
        } else metrics.forEach { metric ->
            val box = card()
            addText(box, metric.name, 14, true)
            addText(box, "Попадания при пороге 50%: ${metric.hits}/${metric.n} (${String.format(Locale.US, "%.1f", metric.hits*100.0/metric.n)}%)", 11, true, accent)
            addText(box, "Brier Score: ${String.format(Locale.US, "%.4f", metric.brier)} · Log Loss: ${String.format(Locale.US, "%.4f", metric.logLoss)}", 11, false, muted)
            addText(box, if (metric.name.startsWith("Фора 0")) "Фора 0: победа — выигрыш, ничья — возврат, поражение — проигрыш. Вероятность считается без ничьих." else "Бинарный рынок; оценка вероятностей по завершённым матчам.", 10, false, muted)
        }
        val explainer = card()
        addText(explainer, "КАК ЧИТАТЬ МЕТРИКИ", 10, true, accent)
        addText(explainer, "Brier Score и Log Loss оценивают качество вероятностей: чем ниже, тем лучше. Попадания — вспомогательная доля прогнозов выше 50%, а не доходность. П1 / X / П2 не являются главной статистикой.", 11, false, muted)
        val footer = card()
        addText(footer, "Выборка включает завершённые матчи с сохранённым прогнозом. Это мониторинг, а не независимый тест модели.", 10, false, muted)
    }


    private fun saveBets() { betPrefs.edit().putString("bets", bets.toString()).apply() }
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
        addText(matchesContainer, "MODEL GUIDE", 9, true, cyan)
        addText(matchesContainer, "Как читать прогнозы", 22, true)
        addText(matchesContainer, "Руководство по вероятностям, рынкам и оценке качества.", 12, false, muted)
        section("1. ЧТО ДЕЛАЕТ МОДЕЛЬ",
            "Модель оценивает ожидаемые голы каждой команды по истории xG (ожидаемых голов). Затем независимая модель Пуассона строит распределение возможных счетов. Из него рассчитываются вероятности исходов и рынков. Это вероятности, а не прогноз точного счёта.")
        section("2. НАШИ ОСНОВНЫЕ РЫНКИ",
            "1X — хозяева победят ИЛИ матч закончится вничью. Ставка выигрывает в обоих случаях.\n\nX2 — гости победят ИЛИ матч закончится вничью. Ставка выигрывает в обоих случаях.\n\nП1 с возвратом при ничьей: выигрыш при победе хозяев, возврат суммы при ничьей, проигрыш при победе гостей.\n\nП2 с возвратом при ничьей: выигрыш при победе гостей, возврат суммы при ничьей, проигрыш при победе хозяев. Вероятности этих двух рынков считаются среди матчей без ничьей.\n\nИТБ 1.5 — указанная в названии команда забьёт минимум 2 гола.")
        section("3. П1, X И П2 — ЗАЧЕМ ОНИ ПОКАЗАНЫ",
            "П1 — вероятность победы хозяев; X — вероятность ничьей; П2 — вероятность победы гостей. Это вероятности нашей модели, не коэффициенты и не оценки букмекера. Они выводятся из распределения счетов. Из них следуют 1X = П1 + X и X2 = X + П2. Они показаны для контекста, но не являются главными рынками оценки модели.")
        section("4. ДОПОЛНИТЕЛЬНЫЕ РЫНКИ",
            "ТБ 2.5 — в матче будет не менее 3 голов. «Обе забьют» — каждая команда забьёт минимум 1 гол. Они видны в карточках как дополнительная информация. BTTS исключён из ядра модели, а ТБ 2.5 показал лишь слабое улучшение относительно простой базовой оценки.")
        section("5. КАК ЧИТАТЬ ПРОЦЕНТЫ",
            "Вероятность 1X = 70% означает, что модель оценивает шанс события «хозяева победят или будет ничья» в 70%. Это не гарантия для конкретного матча. Вероятность — оценка неопределённости, а не уверенность в неизбежном исходе. Порог 50% используется только для вспомогательного подсчёта попаданий, это не порог выгодной ставки.")
        section("6. СТАТИСТИКА: ПОПАДАНИЯ",
            "Каждый рынок проверяется отдельно по завершённым матчам, для которых в данных сохранён прогноз. Для бинарного рынка попадание — когда прогноз выше или равен 50% и событие произошло, либо прогноз ниже 50% и событие не произошло. Доля попаданий = число совпадений / число матчей. Она не показывает, насколько точны сами проценты.")
        section("7. BRIER SCORE И LOG LOSS",
            "Brier Score — средний квадрат разницы между вероятностью p и фактом y (y = 1, если событие произошло, иначе 0): среднее (p − y)². Чем ближе к 0, тем лучше.\n\nLog Loss штрафует за уверенный прогноз, который оказался неверным: среднее −[y·ln(p) + (1−y)·ln(1−p)]. Чем ниже, тем лучше. Эти метрики оценивают качество вероятностей, а не прибыль.")
        section("8. КАК СРАВНИВАТЬ РЕЗУЛЬТАТЫ",
            "Сравнивайте один и тот же рынок на достаточно большой выборке. Маленькая выборка сильно колеблется. У разных рынков разные базовые частоты, поэтому процент попаданий между ними напрямую сравнивать нельзя. Brier Score и Log Loss полезнее для оценки вероятностей. Для рынка «Фора 0» ничьи исключаются из расчёта вероятности.")
        section("9. ИСТОЧНИКИ И ОГРАНИЧЕНИЯ",
            "Исторические xG берутся из Openplay (Noah Bair), лицензия CC BY 4.0. Параметры версии: последние 30 матчей, half-life 12, shrinkage 3, независимый Пуассон. Если данные Openplay устарели, модель использует последнюю доступную историю xG; новые результаты и расписание сами по себе не обновляют xG.\n\nСтатистика в приложении — мониторинг сохранённых прогнозов, а не независимый перспективный тест. Она не доказывает преимущество над букмекером, value или ROI: для этого нужны коэффициенты на момент прогноза и отдельная проверка.")
        section("10. САМЫЙ СИЛЬНЫЙ СИГНАЛ В КАРТОЧКЕ",
            "Для каждого матча приложение сравнивает пять основных рынков с исторической частотой соответствующего события в этой лиге: 1X, X2, П1 с возвратом при ничьей и ИТБ 1.5 каждой команды. Для каждой пары событий выбирается сторона с большей вероятностью модели. Затем считается разница между вероятностью модели и исторической базовой частотой в процентных пунктах. База использует только матчи лиги, сыгранные до даты рассматриваемой игры, с небольшим сглаживанием; минимум — 100 прошлых матчей. В карточке показывается рынок с наибольшим отклонением. Если все отклонения отрицательные, приложение прямо сообщает, что положительного сигнала нет. Это сравнение с простой исторической частотой, а не с коэффициентом букмекера: оно не означает value или прибыльность.")
        section("11. ЧТО СЧИТАТЬ ГЛАВНЫМ",
            "В первую очередь смотрите 1X, X2, фора 0 на хозяев, ИТБ 1.5 хозяев и ИТБ 1.5 гостей. П1/X/П2 — вспомогательные вероятности. ТБ 2.5 и «Обе забьют» — дополнительные рынки. Ни один прогноз не гарантирует результат.")
    }

    private fun renderWeek(json: JSONObject) {
        addText(matchesContainer, "WEEKLY MATCH CENTER", 9, true, cyan)
        addText(matchesContainer, "Матчи недели", 22, true)
        addText(matchesContainer, "Все пять лиг · ближайшие 7 дней · только матчи с готовым прогнозом", 11, false, muted)

        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 7) }
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val items = mutableListOf<Pair<JSONObject, String>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                if (m.optJSONObject("prediction") == null) continue
                val kickoff = try { parser.parse(m.optString("utcDate")) } catch (_: Exception) { null } ?: continue
                if (kickoff.time >= start.timeInMillis && kickoff.time < end.timeInMillis) {
                    items.add(m to league.optString("name"))
                }
            }
        }
        val sorted = items.sortedBy { it.first.optString("utcDate") }
        val overview = card()
        addText(overview, "МАТЧЕЙ В ЭТОМ ОКНЕ", 9, true, accent)
        addText(overview, sorted.size.toString(), 28, true, Color.WHITE)
        addText(overview, "С прогнозом модели Openplay 1.3", 11, false, muted)
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "На ближайшие 7 дней нет предстоящих матчей с готовым прогнозом.", 13, false, muted)
            return
        }
        sorted.forEach { (m, leagueName) ->
            val box = card()
            addText(box, leagueName.uppercase(Locale.getDefault()) + "  ·  " + date(m.optString("utcDate")), 10, true, accent)
            addText(box, m.optString("home") + "  —  " + m.optString("away"), 16, true, Color.WHITE)
            val p = m.optJSONObject("prediction") ?: return@forEach
            addMarketRow(box, listOf(
                Triple("1X", pct(p, "1x"), Color.WHITE),
                Triple("X2", pct(p, "x2"), Color.WHITE),
                Triple("ТБ 2.5", pct(p, "over25"), cyan)
            ))
            val signal = p.optJSONObject("strongestSignal")
            if (signal != null) {
                val edge = signal.optDouble("edgePp")
                addText(box, "Сильнейший сигнал: " + signal.optString("side") +
                    (if (signal.optBoolean("noPositiveSignal")) " · нет плюсового отклонения" else " · +" + String.format(Locale.US, "%.1f", edge) + " п.п."),
                    11, true, if (signal.optBoolean("noPositiveSignal")) Color.rgb(242,190,104) else gold)
            }
            addText(box, "Ожидаемые голы: " + String.format(Locale.US, "%.2f : %.2f", p.optDouble("expectedHomeGoals"), p.optDouble("expectedAwayGoals")), 11, false, muted)
        }
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
        addText(summary, "●  LIVE MODEL DASHBOARD", 9, true, cyan)
        addText(summary, "Футбольная аналитика", 22, true, Color.WHITE)
        addText(summary, "История xG до $through", 14, true, accent)
        addText(summary, "30 матчей  ·  half-life 12  ·  shrinkage 3", 11, false, muted)
        addText(summary, "Обновлено: ${json.optString("updatedAt", "—").replace("T", " ").take(16)}", 10, false, muted)
        val warning = json.optString("dataWarning", "")
        if (warning.isNotBlank()) addText(summary, "⚠  $warning", 11, false, Color.rgb(242, 190, 104))

        if (currentTab == "week") {\n            leagueBar.visibility = View.GONE\n            renderWeek(json)\n            return\n        }\n        if (currentTab == "history") {
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
                setPadding(15, 15, 15, 15)
                background = rounded(surface, 16)
                background = (background as GradientDrawable).apply { setStroke(1, Color.rgb(38, 58, 83)) }
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
        addText(footer, "xG: openplay (Noah Bair), CC BY 4.0.", 10, false, muted)
    }
}
