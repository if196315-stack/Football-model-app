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
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("⚽  FOOTBALL MODEL", 23, true, Color.WHITE))
        brand.addView(label("MATCH INTELLIGENCE  /  OPENPLAY 1.3", 9, true, cyan))
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
            letterSpacing = if (size <= 10) 0.07f else 0.005f
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun card(parent: LinearLayout = matchesContainer): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(17, 16, 17, 16)
            background = rounded(panel, 20)
            background = (background as GradientDrawable).apply { setStroke(1, Color.rgb(34, 52, 76)) }
            elevation = 2f
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 7, 0, 9)
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
        listOf("matches" to "⚽ Матчи", "history" to "▤ История", "stats" to "▥ Статистика", "help" to "ⓘ Справка").forEach { item ->
            val active = currentTab == item.first
            val t = label(item.second, 11, true, if (active) bg else muted).apply {
                gravity = Gravity.CENTER
                background = rounded(if (active) accent else Color.TRANSPARENT, 13)
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
        addText(matchesContainer, "ОЦЕНКА ОСНОВНЫХ РЫНКОВ", 10, true, accent)
        addText(matchesContainer, "Проверяем каждый рынок отдельно", 19, true)
        addText(matchesContainer, "П1 / X / П2 остаются внутренними вероятностями модели. Главная оценка — по рынкам 1X, X2, победе хозяев с возвратом при ничьей и индивидуальным тоталам команд.", 11, false, muted)
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
        collect("1x", "1X · хозяева не проиграют", { h,a -> h >= a })
        collect("x2", "X2 · гости не проиграют", { h,a -> a >= h })
        collect("dnbHome", "Победа хозяев (ничья — возврат)", { h,a -> h > a }, true)
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
            addText(box, if (metric.name.startsWith("Победа хозяев")) "Показан шанс победы хозяев среди матчей без ничьей; при ничьей ставка возвращается." else "Бинарный рынок; оценка вероятностей по завершённым матчам.", 10, false, muted)
        }
        val explainer = card()
        addText(explainer, "КАК ЧИТАТЬ МЕТРИКИ", 10, true, accent)
        addText(explainer, "Brier Score и Log Loss оценивают качество вероятностей: чем ниже, тем лучше. Попадания — вспомогательная доля прогнозов выше 50%, а не доходность. П1 / X / П2 не являются главной статистикой.", 11, false, muted)
        val footer = card()
        addText(footer, "Выборка включает завершённые матчи с сохранённым прогнозом. Это мониторинг, а не независимый тест модели.", 10, false, muted)
    }


    private fun saveBets() { betPrefs.edit().putString("bets", bets.toString()).apply() }
    private fun actionButton(parent: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply { text = title; textSize = 12f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; minHeight = 48; setPadding(16, 8, 16, 8); setTextColor(bg); background = rounded(accent,16); elevation = 0f; setOnClickListener { action() } }
        parent.addView(b, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 8 })
    }
    private fun showAddBetDialog(m: JSONObject, league: String) {
        val p = m.optJSONObject("prediction") ?: return
        val markets = listOf(
            BetMarket("1x","1X · хозяева не проиграют"), BetMarket("x2","X2 · гости не проиграют"),
            BetMarket("dnbHome","Победа хозяев (ничья — возврат)"), BetMarket("1","П1"),
            BetMarket("x","Ничья"), BetMarket("2","П2"), BetMarket("over25","ТБ 2.5"),
            BetMarket("under25","ТМ 2.5"), BetMarket("bttsYes","Обе забьют · Да"),
            BetMarket("bttsNo","Обе забьют · Нет"), BetMarket("homeOver15","ИТБ 1.5 хозяев"),
            BetMarket("awayOver15","ИТБ 1.5 гостей")
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
            "1x"->h>=a; "x2"->a>=h; "dnbHome"->if(h==a)null else h>a
            "1"->h>a; "x"->h==a; "2"->a>h; "over25"->h+a>=3; "under25"->h+a<=2
            "bttsYes"->h>0&&a>0; "bttsNo"->h==0||a==0; "homeOver15"->h>=2; "awayOver15"->a>=2
            else->null
        }
        b.put("status",when(won){true->"WON";false->"LOST";null->"PUSH"}); b.put("score","$h:$a")
    }
    private fun fmt(v: Double)=String.format(Locale.getDefault(),"%.2f",v)
    private fun renderBetHistory(j: JSONObject) {
        addText(matchesContainer,"ИСТОРИЯ СТАВОК",10,true,accent)
        addText(matchesContainer,"Ставки, расчёт и доходность",19,true)
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
        addText(matchesContainer, "СПРАВКА ПО МОДЕЛИ", 10, true, accent)
        addText(matchesContainer, "Как читать прогнозы и статистику", 19, true)
        addText(matchesContainer, "Руководство по вероятностям, рынкам и оценке качества.", 12, false, muted)
        section("1. ЧТО ДЕЛАЕТ МОДЕЛЬ",
            "Модель оценивает ожидаемые голы каждой команды по истории xG (ожидаемых голов). Затем независимая модель Пуассона строит распределение возможных счетов. Из него рассчитываются вероятности исходов и рынков. Это вероятности, а не прогноз точного счёта.")
        section("2. НАШИ ОСНОВНЫЕ РЫНКИ",
            "1X — хозяева не проиграют: победа хозяев или ничья. Успех, если голы хозяев не меньше голов гостей.\n\nX2 — гости не проиграют: ничья или победа гостей. Успех, если голы гостей не меньше голов хозяев.\n\nПобеда хозяев с возвратом при ничьей — ставка выигрывает, если хозяева победили; если матч закончился вничью, сумма ставки возвращается; если хозяева проиграли, ставка проиграна. Вероятность в приложении считается среди матчей без ничьей: П1 / (П1 + П2).\n\nИТБ 1.5 хозяев — хозяева забьют минимум 2 гола.\n\nИТБ 1.5 гостей — гости забьют минимум 2 гола.")
        section("3. П1, X И П2 — ЗАЧЕМ ОНИ ПОКАЗАНЫ",
            "П1 — вероятность победы хозяев; X — вероятность ничьей; П2 — вероятность победы гостей. Это вероятности нашей модели, не коэффициенты и не оценки букмекера. Они выводятся из распределения счетов. Из них следуют 1X = П1 + X и X2 = X + П2. Они показаны для контекста, но не являются главными рынками оценки модели.")
        section("4. ДОПОЛНИТЕЛЬНЫЕ РЫНКИ",
            "ТБ 2.5 — в матче будет не менее 3 голов. «Обе забьют» — каждая команда забьёт минимум 1 гол. Они видны в карточках как дополнительная информация. BTTS исключён из ядра модели, а ТБ 2.5 показал лишь слабое улучшение относительно простой базовой оценки.")
        section("5. КАК ЧИТАТЬ ПРОЦЕНТЫ",
            "Вероятность 1X = 70% означает, что модель оценивает шанс события «хозяева не проиграют» в 70%. Это не гарантия для конкретного матча. Вероятность — оценка неопределённости, а не уверенность в неизбежном исходе. Порог 50% используется только для вспомогательного подсчёта попаданий, это не порог выгодной ставки.")
        section("6. СТАТИСТИКА: ПОПАДАНИЯ",
            "Каждый рынок проверяется отдельно по завершённым матчам, для которых в данных сохранён прогноз. Для бинарного рынка попадание — когда прогноз выше или равен 50% и событие произошло, либо прогноз ниже 50% и событие не произошло. Доля попаданий = число совпадений / число матчей. Она не показывает, насколько точны сами проценты.")
        section("7. BRIER SCORE И LOG LOSS",
            "Brier Score — средний квадрат разницы между вероятностью p и фактом y (y = 1, если событие произошло, иначе 0): среднее (p − y)². Чем ближе к 0, тем лучше.\n\nLog Loss штрафует за уверенный прогноз, который оказался неверным: среднее −[y·ln(p) + (1−y)·ln(1−p)]. Чем ниже, тем лучше. Эти метрики оценивают качество вероятностей, а не прибыль.")
        section("8. КАК СРАВНИВАТЬ РЕЗУЛЬТАТЫ",
            "Сравнивайте один и тот же рынок на достаточно большой выборке. Маленькая выборка сильно колеблется. У разных рынков разные базовые частоты, поэтому процент попаданий между ними напрямую сравнивать нельзя. Brier Score и Log Loss полезнее для оценки вероятностей. Для рынка «Победа хозяев (ничья — возврат)» ничьи исключаются из расчёта вероятности.")
        section("9. ИСТОЧНИКИ И ОГРАНИЧЕНИЯ",
            "Исторические xG берутся из Openplay (Noah Bair), лицензия CC BY 4.0. Параметры версии: последние 30 матчей, half-life 12, shrinkage 3, независимый Пуассон. Если данные Openplay устарели, модель использует последнюю доступную историю xG; новые результаты и расписание сами по себе не обновляют xG.\n\nСтатистика в приложении — мониторинг сохранённых прогнозов, а не независимый перспективный тест. Она не доказывает преимущество над букмекером, value или ROI: для этого нужны коэффициенты на момент прогноза и отдельная проверка.")
        section("10. САМЫЙ СИЛЬНЫЙ СИГНАЛ В КАРТОЧКЕ",
            "Для каждого матча приложение сравнивает пять основных рынков с исторической частотой соответствующего события в этой лиге: 1X, X2, победу хозяев с возвратом при ничьей и ИТБ 1.5 каждой команды. Для каждой пары событий выбирается сторона с большей вероятностью модели. Затем считается разница между вероятностью модели и исторической базовой частотой в процентных пунктах. База использует только матчи лиги, сыгранные до даты рассматриваемой игры, с небольшим сглаживанием; минимум — 100 прошлых матчей. В карточке показывается рынок с наибольшим отклонением. Если все отклонения отрицательные, приложение прямо сообщает, что положительного сигнала нет. Это сравнение с простой исторической частотой, а не с коэффициентом букмекера: оно не означает value или прибыльность.")
        section("11. ЧТО СЧИТАТЬ ГЛАВНЫМ",
            "В первую очередь смотрите 1X, X2, победу хозяев с возвратом при ничьей, ИТБ 1.5 хозяев и ИТБ 1.5 гостей. П1/X/П2 — вспомогательные вероятности. ТБ 2.5 и «Обе забьют» — дополнительные рынки. Ни один прогноз не гарантирует результат.")
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

        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA")
        codes.forEachIndexed { index, code ->
            val b = TextView(this).apply {
                text = names[index]
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(15, 11, 15, 11)
                setTextColor(if (selectedLeague == code) bg else muted)
                background = rounded(if (selectedLeague == code) accent else surface, 28)
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
                    mainMarkets.background = rounded(Color.rgb(19, 47, 52), 17)
                    addText(mainMarkets, "✦  ОСНОВНЫЕ РЫНКИ МОДЕЛИ", 10, true, accent)
                    addText(mainMarkets, "1X  ${pct(pred, "1x")}     X2  ${pct(pred, "x2")}", 17, true, Color.WHITE)
                    addText(mainMarkets, "Победа хозяев (ничья — возврат): ${pct(pred, "dnbHome")}", 13, true, Color.rgb(180, 220, 205))
                    addText(mainMarkets, "ИТБ 1.5 хозяев: ${pct(pred, "homeOver15")}     ИТБ 1.5 гостей: ${pct(pred, "awayOver15")}", 12, true, Color.WHITE)
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
