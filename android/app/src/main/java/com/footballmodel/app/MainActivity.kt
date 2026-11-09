package com.footballmodel.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val dataUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/data.json"
    private val uefaDataUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/uefa-data.json"
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
    private var coupon = JSONArray()
    private val betPrefs by lazy { getSharedPreferences("bet_history", MODE_PRIVATE) }
    private val couponPrefs by lazy { getSharedPreferences("bet_coupon", MODE_PRIVATE) }
    private val feedCache by lazy { getSharedPreferences("feed_cache_v1", MODE_PRIVATE) }
    private val loadInProgress = AtomicBoolean(false)
    @Volatile private var lastPayload: String? = null
    private data class BetMarket(val key: String, val label: String)
    private var selectedLeague = "ALL"
    private val expandedLeagues = mutableMapOf<String, Boolean>()
    // Сохраняем уже построенные экраны: повторное переключение вкладок не пересоздаёт сотни View.
    private val tabViewCache = mutableMapOf<String, Pair<List<View>, List<View>>>()
    private var renderedKey: String? = null
    private val liveRefreshHandler = Handler(Looper.getMainLooper())
    private val liveRefreshTask = object : Runnable {
        override fun run() {
            if (refreshButton.isEnabled && currentTab in listOf("matches", "history")) loadData()
            liveRefreshHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(18))
            overScrollMode = View.OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 14, 28), Color.rgb(10, 23, 38), Color.rgb(7, 13, 25)))
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val crest = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "IQ Match"
        }
        top.addView(crest, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(11) })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("IQ MATCH", 24, true, Color.WHITE))
        brand.addView(label("SMART FOOTBALL INTELLIGENCE", 8, true, cyan))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = Button(this).apply {
            text = "↻"
            textSize = 21f
            isAllCaps = false
            elevation = 0f
            setTextColor(Color.WHITE)
            background = rounded(surface, 16)
            setOnClickListener { loadData() }
        }
        top.addView(refreshButton, LinearLayout.LayoutParams(52, 48))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

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
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 16; bottomMargin = 14 })

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
        coupon = try { JSONArray(couponPrefs.getString("coupon", "[]")) } catch (_: Exception) { JSONArray() }
        // Мгновенный старт: показываем последний успешный снимок до сетевого обновления.
        feedCache.getString("merged_json", null)?.let { cached ->
            try {
                data = JSONObject(cached)
                lastPayload = cached
                render()
            } catch (_: Exception) {
                feedCache.edit().remove("merged_json").apply()
            }
        }
        // Показываем рабочую оболочку сразу, не дожидаясь сети.
        renderTabs()
        leagueBar.visibility = View.VISIBLE
        val loadingCard = card()
        addText(loadingCard, "●  MATCH CENTER", 10, true, cyan)
        addText(loadingCard, "Интерфейс готов. Получаю матчи и прогнозы…", 13, false, muted)
        loadData()
    }

    override fun onResume() {
        super.onResume()
        liveRefreshHandler.removeCallbacks(liveRefreshTask)
        liveRefreshHandler.postDelayed(liveRefreshTask, 30_000)
    }

    override fun onPause() {
        liveRefreshHandler.removeCallbacks(liveRefreshTask)
        super.onPause()
    }

    private fun countryFlagView(flag: String): View =
        label(flag, 15, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(20)).apply {
                rightMargin = dp(8)
            }
            contentDescription = "Флаг страны"
        }

    private fun englandFlagView(): View = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.color = Color.rgb(206, 17, 38)
            val vertical = w * 0.22f
            val horizontal = h * 0.34f
            canvas.drawRect((w - vertical) / 2f, 0f, (w + vertical) / 2f, h, paint)
            canvas.drawRect(0f, (h - horizontal) / 2f, w, (h + horizontal) / 2f, paint)
        }
    }.apply {
        setWillNotDraw(false)
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(20)).apply { rightMargin = dp(8) }
        contentDescription = "Флаг Англии"
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
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(18, 34, 53), Color.rgb(9, 21, 36))).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), Color.rgb(43, 68, 91)) }
            elevation = 0f
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 7, 0, 9)
        return box.also { parent.addView(it, lp) }
    }

    private fun addText(box: LinearLayout, value: String, size: Int = 13, bold: Boolean = false, color: Int = Color.WHITE) {
        box.addView(label(value, size, bold, color))
    }

    private fun addMatchup(parent: LinearLayout, home: String, away: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(7))
        }
        val homeName = label(home, 14, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
        }
        val awayName = label(away, 14, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
        }
        val homeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        homeBlock.addView(teamCrest(home, Color.rgb(35, 112, 132)), LinearLayout.LayoutParams(dp(44), dp(44)))
        homeBlock.addView(homeName)
        val awayBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        awayBlock.addView(teamCrest(away, Color.rgb(119, 77, 91)), LinearLayout.LayoutParams(dp(44), dp(44)))
        awayBlock.addView(awayName)
        val versus = label("VS", 10, true, gold).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.rgb(43, 48, 58))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(114, 91, 54))
            }
        }
        row.addView(homeBlock, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(versus, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        row.addView(awayBlock, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
    }

    // Shared premium title panel for every secondary screen.
    private fun screenHeader(eyebrow: String, title: String, description: String) {
        val hero = card()
        hero.setPadding(dp(18), dp(17), dp(18), dp(17))
        hero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(28, 59, 66), Color.rgb(17, 35, 54), Color.rgb(11, 24, 41))
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(58, 112, 105))
        }
        addText(hero, eyebrow.uppercase(Locale.getDefault()), 9, true, gold)
        addText(hero, title, 22, true, Color.WHITE)
        addText(hero, description, 11, false, Color.rgb(190, 207, 221))
    }

    private fun renderOdds(parent: LinearLayout, odds: JSONObject?) {
        val best = odds?.optJSONObject("best")
        if (best == null) {
            addText(parent, "КОЭФФИЦИЕНТЫ BETBOOM", 9, true, muted)
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
                addText(panelOdds, "ОБЕ КОМАНДЫ ЗАБЬЮТ · BETBOOM", 10, true, muted)
                addMarketRow(panelOdds, items)
                shown += items.size
            }
        }
        val matchTotals = best.optJSONArray("totals")
        if (matchTotals != null && matchTotals.length() > 0) {
            addText(panelOdds, "ТОТАЛ МАТЧА · BETBOOM", 10, true, muted)
            for (i in 0 until matchTotals.length()) {
                val line = matchTotals.optJSONObject(i) ?: continue
                val point = line.optDouble("point", Double.NaN)
                val price = line.optDouble("price", Double.NaN)
                val side = line.optString("side")
                // Показываем котировки тоталов только для БОЛЬШЕ; UNDER не выводим ни для матча, ни для команд.\n                if (!point.isFinite() || !side.equals("over", ignoreCase = true) || !price.isFinite() || price <= 1.0) continue
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
                    addText(panelOdds, "ФОРЫ · лучшие цены BETBOOM", 10, true, muted)
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
                val side = line.optString("side")
                // Индивидуальные UNDER-котировки скрыты; в карточке остаются только OVER.\n                if (!side.equals("over", ignoreCase = true) || !price.isFinite() || price <= 1.0 || !point.isFinite()) continue
                if (!headingShown) {
                    addText(panelOdds, "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ · BETBOOM", 10, true, muted)
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

    private fun teamCrest(team: String, tint: Int): TextView {
        val initials = team.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            .ifBlank { "FC" }
        return label(initials, 14, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(tint, Color.rgb(17, 31, 49))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(91, 190, 174))
            }
            elevation = 0f
        }
    }

    private fun addStadiumBanner(parent: LinearLayout) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(15), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 39, 61), Color.rgb(8, 23, 42), Color.rgb(10, 54, 57))).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(35, 111, 137))
            }
            elevation = dp(3).toFloat()
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label("IQ MATCH  /  MATCH CENTER", 9, true, gold))
        copy.addView(label("Матчи и прогнозы", 22, true, Color.WHITE))
        copy.addView(label("SMART FOOTBALL INTELLIGENCE", 8, true, cyan))
        val features = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("ВЕРОЯТНОСТИ", "ОЖИДАЕМЫЕ ГОЛЫ", "BETBOOM").forEachIndexed { index, caption ->
            val chip = label(caption, 7, true, if (index == 2) gold else accent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(6), dp(5), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.argb(45, 4, 12, 22))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.rgb(59, 91, 103))
                }
            }
            features.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(2), dp(7), dp(2), 0)
            })
        }
        copy.addView(features)
        banner.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val stadium = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ночной футбольный стадион"
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(72, 168, 186))
            }
            clipToOutline = true
            alpha = 0.96f
        }
        banner.addView(stadium, LinearLayout.LayoutParams(dp(124), dp(104)).apply {
            leftMargin = dp(8)
        })
        thread {
            try {
                val connection = URL("https://images.unsplash.com/photo-1652541090220-c9a0d15edbe4?auto=format&fit=crop&w=900&q=80").openConnection()
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.getInputStream().use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    runOnUiThread { stadium.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    stadium.setImageDrawable(null)
                    stadium.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(7, 48, 72), Color.rgb(13, 95, 102))).apply {
                        cornerRadius = dp(15).toFloat()
                        setStroke(dp(1), Color.rgb(72, 168, 186))
                    }
                }
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(8)
        }
        parent.addView(banner, lp)
    }

    private fun addMarketTitle(parent: LinearLayout, symbol: String, title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val icon = label(symbol, 17, true, gold).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 67, 69))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(66, 131, 116))
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        row.addView(label(title, 10, true, accent), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun addTeamMarketTitle(parent: LinearLayout, team: String, side: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        val crest = label(side, 11, true, gold).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 49, 60))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(112, 91, 58))
            }
        }
        row.addView(crest, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(9) })
        row.addView(label(team, 11, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    // Compact two-column market grid: four team totals remain readable on phone screens.
    private fun addMarketRow(parent: LinearLayout, items: List<Triple<String, String, Int>>, labelColor: Int = Color.rgb(177, 198, 207)) {
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pair.forEach { item ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(31, 70, 76), Color.rgb(17, 39, 56))).apply {
                        cornerRadius = dp(15).toFloat()
                        setStroke(dp(1), Color.rgb(58, 111, 111))
                    }
                }
                tile.addView(label(item.first, 10, true, labelColor))
                tile.addView(label(item.second, 20, true, item.third))
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), dp(5))
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            parent.addView(row)
        }
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

    // Conditional HTTP requests avoid downloading identical JSON on every 30-second refresh.
    // The last good response is retained locally and used when the network is unavailable.
    private fun fetchFeed(url: String, cacheKey: String, required: Boolean): String? {
        val cached = feedCache.getString(cacheKey, null)
        val etag = feedCache.getString("${cacheKey}_etag", null)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                useCaches = true
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", "IQMatch-Android")
                if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) return cached
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val editor = feedCache.edit().putString(cacheKey, body)
            conn.getHeaderField("ETag")?.let { editor.putString("${cacheKey}_etag", it) }
            editor.apply()
            return body
        } catch (e: Exception) {
            if (cached != null) return cached
            if (required) throw e
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun loadData() {
        if (!loadInProgress.compareAndSet(false, true)) return
        statusView.text = if (data == null) "Загружаю данные…" else "Проверяю обновления…"
        refreshButton.isEnabled = false
        thread(name = "iq-match-feed-refresh") {
            try {
                val body = fetchFeed(dataUrl, "domestic_json", true)
                    ?: throw IllegalStateException("Основной фид недоступен")
                val json = JSONObject(body)
                // UEFA is optional and independently cached: its outage cannot block domestic leagues.
                val uefaBody = fetchFeed(uefaDataUrl, "uefa_json", false)
                if (!uefaBody.isNullOrBlank()) {
                    try {
                        val uefa = JSONObject(uefaBody)
                        val target = json.optJSONArray("competitions") ?: JSONArray().also { json.put("competitions", it) }
                        val extra = uefa.optJSONArray("competitions")
                        if (extra != null) for (i in 0 until extra.length()) {
                            val competition = extra.optJSONObject(i) ?: continue
                            val code = competition.optString("code")
                            if (code == "UCL" || code == "UEL") {
                                for (j in target.length() - 1 downTo 0) {
                                    if (target.optJSONObject(j)?.optString("code") == code) target.remove(j)
                                }
                                target.put(competition)
                            }
                        }
                    } catch (_: Exception) {
                        // Keep the valid domestic feed if UEFA data is malformed.
                    }
                }
                val payload = json.toString()
                feedCache.edit().putString("merged_json", payload).apply()
                runOnUiThread {
                    val changed = payload != lastPayload
                    if (changed) {
                        data = json
                        lastPayload = payload
                        tabViewCache.clear()
                        renderedKey = null
                        render()
                    } else {
                        statusView.text = "●  Данные актуальны · без изменений"
                    }
                    refreshButton.isEnabled = true
                    loadInProgress.set(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = if (data != null) "Нет сети · показаны последние данные" else
                        "Нет соединения · ${e.message ?: "проверьте интернет"}"
                    refreshButton.isEnabled = true
                    loadInProgress.set(false)
                }
            }
        }
    }

    private fun renderTabs() {
        val items = listOf("matches" to "Линия", "coupon" to "Купон", "desk" to "AI", "history" to "Банк", "stats" to "Stats", "help" to "Профиль")
        // Создаём кнопки меню один раз: при переключении меняем только оформление,
        // не пересоздавая сами View и обработчики нажатий.
        if (tabBar.childCount != items.size) {
            tabBar.removeAllViews()
            items.forEach { item ->
                val t = label(item.second, 11, true, muted).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
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
        screenHeader("PERFORMANCE CENTER", "Статистика и точность модели", "Только прогнозы, зафиксированные до начала матча. Исходные вероятности не пересчитываются после финального свистка.")
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

        // IQ MATCH Strategy v1.0 — постоянно доступная памятка с правилами отбора и риска.
        val strategyIntro = card()
        addText(strategyIntro, "IQ MATCH STRATEGY · VERSION 1.0", 10, true, accent)
        addText(strategyIntro, "СТРОГИЙ ОТБОР · УМЕРЕННЫЙ РИСК · ОГРАНИЧЕННЫЙ ЗАПУСК", 13, true, Color.WHITE)
        addText(strategyIntro, "Ставка допускается только после проверки данных, вероятности, value и риска. Если хотя бы один обязательный фильтр не пройден — WAIT или REJECT. Отсутствие ставки является полноценным решением.", 11, false, muted)

        val strategyFilters = card()
        addText(strategyFilters, "1. ФИЛЬТРЫ ДОПУСКА", 11, true, accent)
        addText(strategyFilters, "Данные: матч сопоставлен однозначно; котировка только BetBoom через API-Sport; возраст котировки не более 5 минут; точное совпадение рынка, стороны и линии; нет критических пропусков.", 11, false, Color.WHITE)
        addText(strategyFilters, "Сигнал A: вероятность модели ≥ 60%; преимущество над break-even ≥ 5 п.п.; EV ≥ +5%; модель проверена на данном типе рынка; сигнал устойчив к небольшим изменениям предположений.", 11, false, Color.WHITE)
        addText(strategyFilters, "B = WAIT: интересный сигнал, но не хватает подтверждений или данных. C = REJECT: отрицательное/недостаточное value, плохие данные или неприемлемый риск.", 11, false, muted)

        val strategyValue = card()
        addText(strategyValue, "2. VALUE И EV", 11, true, accent)
        addText(strategyValue, "Break-even probability = 1 / коэффициент. EV = вероятность модели × коэффициент − 1. Для BET стартовый минимум EV — +5%, а преимущество над break-even — не менее 5 процентных пунктов. Высокая вероятность сама по себе не означает выгодную ставку.", 11, false, Color.WHITE)
        addText(strategyValue, "Пример: вероятность 60%, коэффициент 1.90 → break-even 52.63%, EV +14%. Пример иллюстративный; калибровка модели и фактическая цена обязательны.", 10, false, muted)

        val strategyRisk = card()
        addText(strategyRisk, "3. БАНК И РИСК", 11, true, accent)
        addText(strategyRisk, "Базовая ставка — 0.5% текущего банка; сниженная — 0.25%; максимум — 1% на одну ставку. Суммарно на матч — не более 1%; дневная экспозиция — не более 2%. Коррелированные ставки считаются совместно.", 11, false, Color.WHITE)
        addText(strategyRisk, "Запрещены догон, увеличение ставки после выигрыша и автоматический рост риска. Полный Kelly не используется. Просадка 5% от локального максимума — пауза и аудит; 10% — остановка реальных ставок до пересмотра.", 11, false, muted)

        val strategyMarkets = card()
        addText(strategyMarkets, "4. РЫНКИ И ИСТОЧНИК", 11, true, accent)
        addText(strategyMarkets, "Только BetBoom через API-Sport. Допускать только рынки с точной загруженной ценой и соответствующей вероятностью модели: Double Chance 1X/X2, Match Total OVER, Team Total OVER, BTTS YES/NO. Форы — только при наличии точной линии и коэффициента. 1X2 не допускать без корректной загрузки цены. UNDER не показывать в текущем пользовательском сценарии.", 11, false, Color.WHITE)
        addText(strategyMarkets, "Нельзя подставлять коэффициенты другого букмекера, угадывать отсутствующую цену или сравнивать разные линии.", 10, false, muted)

        val strategyTesting = card()
        addText(strategyTesting, "5. ТЕСТИРОВАНИЕ И ДОПУСК К ЗАПУСКУ", 11, true, accent)
        addText(strategyTesting, "Этап 1 — аудит данных и сопоставления. Этап 2 — хронологический бэктест без утечки будущих данных, с отдельными train/validation/test периодами. Этап 3 — paper trading на актуальных BetBoom котировках без реальных ставок. Этап 4 — ограниченный запуск с 0.25–0.5% банка. Этап 5 — регулярный аудит и пересмотр.", 11, false, Color.WHITE)
        addText(strategyTesting, "Стартовые ориентиры: ≥500 исторических сигналов и ≥500 paper-сигналов; желательно ≥150–200 наблюдений на оцениваемый рынок. Эти объёмы не гарантируют статистическую надёжность: показывать доверительные интервалы и учитывать неопределённость.", 11, false, muted)

        val strategyMetrics = card()
        addText(strategyMetrics, "6. ЧТО КОНТРОЛИРОВАТЬ В STATS", 11, true, accent)
        addText(strategyMetrics, "ROI, Yield, CLV, максимальная просадка, Brier Score, Log Loss, калибровка вероятностей, win rate, EV по каждому рынку и причины REJECT/WAIT. Win rate не является главным критерием. Сохранять все сигналы, включая отклонённые, коэффициент на момент решения и версию стратегии.", 11, false, Color.WHITE)
        addText(strategyMetrics, "Пороговые значения — стартовая конфигурация для проверки, а не доказанное преимущество и не гарантия прибыли. Изменения правил должны получать новую версию и проверяться на независимых данных.", 10, false, muted)
    }


    private fun saveBets() {
        betPrefs.edit().putString("bets", bets.toString()).apply()
        tabViewCache.remove("history|ALL")
        tabViewCache.remove("desk|ALL")
    }
    private fun saveCoupon() {
        couponPrefs.edit().putString("coupon", coupon.toString()).apply()
        tabViewCache.remove("coupon|ALL")
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
        val odds=field("Коэффициент (например 1.85)")
        form.addView(odds)
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
                if(o==null || !o.isFinite() || o<=1.0) {
                    Toast.makeText(this,"Введи корректный коэффициент больше 1.00",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val matchId = m.optString("id",m.optString("utcDate")+m.optString("home")+m.optString("away"))
                val mk=markets[spin.selectedItemPosition]
                coupon.put(JSONObject().apply {
                    put("id", System.currentTimeMillis().toString())
                    put("matchId", matchId)
                    put("date", m.optString("utcDate")); put("league", league)
                    put("home", m.optString("home")); put("away", m.optString("away"))
                    put("marketKey", mk.key); put("market", mk.label)
                    put("probability", p.optDouble(mk.key)); put("odds", o)
                    put("addedAt", System.currentTimeMillis())
                })
                saveCoupon()
                dlg.dismiss()
                currentTab = "coupon"
                render()
            }
        }
        dlg.show()
    }

    private fun renderCoupon() {
        screenHeader("BET SLIP · LOCAL COUPON", "Купон", "Собери экспресс из выбранных исходов. Коэффициенты можно изменить до фиксации тестового купона.")
        if (coupon.length() == 0) {
            val empty = card()
            addText(empty, "Купон пока пуст", 16, true, Color.WHITE)
            addText(empty, "Открой матч в разделе «Линия» или выбери сигнал в AI и нажми «Добавить в купон».", 12, false, muted)
            actionButton(empty, "Перейти к линии") { currentTab = "matches"; render() }
            return
        }
        var combinedOdds = 1.0
        for (i in 0 until coupon.length()) combinedOdds *= coupon.optJSONObject(i)?.optDouble("odds", 1.0) ?: 1.0
        val summary = card()
        addText(summary, "СОБЫТИЙ В КУПОНЕ", 10, true, cyan)
        addText(summary, coupon.length().toString(), 28, true, Color.WHITE)
        addText(summary, "Общий коэффициент · " + String.format(Locale.US, "%.2f", combinedOdds), 15, true, gold)
        addText(summary, "Экспресс: все исходы должны пройти. Купон ведётся внутри IQ MATCH и не отправляется букмекеру.", 10, false, muted)
        for (i in coupon.length() - 1 downTo 0) {
            val pick = coupon.optJSONObject(i) ?: continue
            val box = card()
            addText(box, pick.optString("league") + " · " + date(pick.optString("date")), 9, true, muted)
            addText(box, pick.optString("home") + " — " + pick.optString("away"), 14, true, Color.WHITE)
            addText(box, pick.optString("market"), 12, true, accent)
            val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            priceRow.addView(label("Коэффициент: " + String.format(Locale.US, "%.2f", pick.optDouble("odds")), 13, true, gold), LinearLayout.LayoutParams(0, -2, 1f))
            val edit = Button(this).apply {
                text = "Изменить"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(surface, 12)
                setOnClickListener {
                    val field = EditText(this@MainActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true); setText(String.format(Locale.US, "%.2f", pick.optDouble("odds")))
                    }
                    AlertDialog.Builder(this@MainActivity).setTitle("Коэффициент купона").setView(field)
                        .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                            val v = field.text.toString().replace(',', '.').toDoubleOrNull()
                            if (v != null && v.isFinite() && v > 1.0) { pick.put("odds", v); saveCoupon(); render() }
                            else Toast.makeText(this@MainActivity, "Коэффициент должен быть больше 1.00", Toast.LENGTH_SHORT).show()
                        }.show()
                }
            }
            priceRow.addView(edit); box.addView(priceRow)
            actionButton(box, "Убрать из купона") {
                val next = JSONArray()
                for (k in 0 until coupon.length()) if (k != i) coupon.optJSONObject(k)?.let { next.put(it) }
                coupon = next; saveCoupon(); render()
            }
        }
        val stakeCard = card()
        addText(stakeCard, "СУММА КУПОНА", 10, true, cyan)
        val stakeField = EditText(this).apply {
            hint = "Сумма в валюте банка"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true); setText(String.format(Locale.US, "%.2f", currentBankroll() * 0.005))
            setTextColor(Color.WHITE); setHintTextColor(muted); background = rounded(surface, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        stakeCard.addView(stakeField, LinearLayout.LayoutParams(-1, -2))
        val payoutView = label("Потенциальная выплата: " + String.format(Locale.US, "%.2f", currentBankroll() * 0.005 * combinedOdds), 13, true, gold)
        stakeCard.addView(payoutView)
        stakeField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val amount = s?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                payoutView.text = "Потенциальная выплата: " + String.format(Locale.US, "%.2f", amount * combinedOdds)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addText(stakeCard, "Сумма 0,25–1% банка; лимит на матч 1%, на день 2%.", 10, false, muted)
        actionButton(stakeCard, "Зафиксировать тестовый купон") {
            val stake = stakeField.text.toString().replace(',', '.').toDoubleOrNull()
            val bank = currentBankroll()
            if (stake == null || stake < bank * 0.0025 || stake > bank * 0.01) {
                Toast.makeText(this, "Сумма должна быть в диапазоне 0,25–1% банка", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val selections = JSONArray()
            for (k in 0 until coupon.length()) coupon.optJSONObject(k)?.let { selections.put(JSONObject(it.toString())) }
            val matchIds = (0 until selections.length()).mapNotNull { selections.optJSONObject(it)?.optString("matchId") }.distinct()
            val days = (0 until selections.length()).mapNotNull { selections.optJSONObject(it)?.optString("date")?.take(10) }.distinct()
            fun existingTouchesMatch(b: JSONObject, matchId: String): Boolean {
                if (b.optString("marketKey") != "COUPON") return b.optString("matchId") == matchId
                val legs = b.optJSONArray("selections") ?: return false
                return (0 until legs.length()).any { legs.optJSONObject(it)?.optString("matchId") == matchId }
            }
            fun existingTouchesDay(b: JSONObject, day: String): Boolean {
                if (b.optString("marketKey") != "COUPON") return b.optString("date").take(10) == day
                val legs = b.optJSONArray("selections") ?: return false
                return (0 until legs.length()).any { legs.optJSONObject(it)?.optString("date")?.take(10) == day }
            }
            val matchViolation = matchIds.any { matchId ->
                val exposure = (0 until bets.length()).sumOf { k ->
                    val b = bets.optJSONObject(k)
                    if (b != null && b.optString("status") == "PENDING" && existingTouchesMatch(b, matchId)) b.optDouble("stake") else 0.0
                }
                exposure + stake > bank * 0.01
            }
            val dailyViolation = days.any { day ->
                val exposure = (0 until bets.length()).sumOf { k ->
                    val b = bets.optJSONObject(k)
                    if (b != null && b.optString("status") == "PENDING" && existingTouchesDay(b, day)) b.optDouble("stake") else 0.0
                }
                exposure + stake > bank * 0.02
            }
            if (matchViolation || dailyViolation) {
                Toast.makeText(this, "Лимит экспозиции: на матч не более 1%, на день не более 2% банка", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val effectiveCombinedOdds = (0 until selections.length()).fold(1.0) { acc, k -> acc * (selections.optJSONObject(k)?.optDouble("odds", 1.0) ?: 1.0) }
            val first = selections.optJSONObject(0)
            bets.put(JSONObject().apply {
                put("id", System.currentTimeMillis().toString()); put("marketKey", "COUPON")
                put("market", "Экспресс · " + selections.length() + " событий")
                put("selections", selections); put("odds", effectiveCombinedOdds); put("stake", stake)
                put("status", "PENDING"); put("date", first?.optString("date") ?: "")
                put("home", "Купон"); put("away", ""); put("matchId", first?.optString("matchId") ?: "")
                put("league", "IQ MATCH")
            })
            saveBets(); coupon = JSONArray(); saveCoupon(); currentTab = "history"; render()
        }
        actionButton(stakeCard, "Очистить купон") { coupon = JSONArray(); saveCoupon(); render() }
    }

    private fun settleCoupon(j: JSONObject, bet: JSONObject) {
        if (bet.optString("status") != "PENDING") return
        val selections = bet.optJSONArray("selections") ?: return
        var allSettled = true
        var lost = false
        var effectiveOdds = 1.0
        val scores = mutableListOf<String>()
        for (i in 0 until selections.length()) {
            val pick = selections.optJSONObject(i) ?: continue
            val match = findMatch(j, pick)
            if (match == null || match.optString("status") != "FINISHED" || match.isNull("homeScore") || match.isNull("awayScore")) {
                allSettled = false
                continue
            }
            val leg = JSONObject().put("status", "PENDING").put("marketKey", pick.optString("marketKey"))
            settleBet(leg, match)
            val result = leg.optString("status")
            scores.add(pick.optString("home") + " " + match.optInt("homeScore") + ":" + match.optInt("awayScore") + " " + pick.optString("away"))
            if (result == "LOST") lost = true
            if (result == "WON") effectiveOdds *= pick.optDouble("odds", 1.0)
        }
        bet.put("couponScores", scores.joinToString(" · "))
        if (lost) bet.put("status", "LOST")
        else if (allSettled) {
            bet.put("status", if (effectiveOdds > 1.0) "WON" else "PUSH")
            bet.put("odds", effectiveOdds)
        }
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
    private fun showEditBetOddsDialog(b: JSONObject) {
        val field = EditText(this).apply {
            hint = "Фактический коэффициент"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.US, "%.2f", b.optDouble("odds")))
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Изменить коэффициент")
            .setMessage("Введи коэффициент, по которому купон действительно принят букмекером.")
            .setView(field).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = field.text.toString().replace(',', '.').toDoubleOrNull()
                        if (value == null || !value.isFinite() || value <= 1.0) {
                            Toast.makeText(this, "Проверь коэффициент", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        b.put("odds", value)
                        b.put("oddsEditedAt", System.currentTimeMillis())
                        saveBets()
                        dialog.dismiss()
                        render()
                    }
                }
            }.show()
    }

    private fun renderBetHistory(j: JSONObject) {
        screenHeader("BET TRACKER", "Ставки и купоны", "Все подтверждённые вручную купоны · фактический коэффициент можно исправить.")
        var changed=false
        for(i in 0 until bets.length()) { val b=bets.optJSONObject(i) ?: continue
            if (b.optString("marketKey") == "COUPON") {
                val before = b.optString("status")
                settleCoupon(j, b)
                if (b.optString("status") != before) changed = true
                continue
            }
            val m=findMatch(j,b)
            if(m!=null) {
                val matchStatus = m.optString("status")
                val score = if (!m.isNull("homeScore") && !m.isNull("awayScore")) "${m.optInt("homeScore")}:${m.optInt("awayScore")}" else ""
                if (score.isNotEmpty() && b.optString("score") != score) { b.put("score", score); changed=true }
                if (matchStatus.isNotEmpty() && b.optString("matchStatus") != matchStatus) { b.put("matchStatus", matchStatus); changed=true }
                if(b.optString("status")=="PENDING") {
                    val before = b.optString("status")
                    settleBet(b,m)
                    if(b.optString("status") != before) changed=true
                }
            }
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
        addText(sum,"Всего: "+bets.length()+" · выигрыш: "+wins+" · проигрыш: "+losses+" · возврат: "+pushes+" · не рассчитаны: "+pending,12,true)
        addText(sum,"Сумма ставок: "+fmt(stakeAll),13,true)
        addText(sum,"Выплаты: "+fmt(payout)+" · чистый результат: "+(if(profit>=0)"+" else "")+fmt(profit),12,true,if(profit>=0)accent else Color.RED)
        addText(sum,"ROI: "+(if(turnover>0)String.format(Locale.getDefault(),"%.1f%%",profit/turnover*100) else "—")+" · рассчитанный оборот: "+fmt(turnover),12,true)
        addText(sum,"ROI = чистый результат / сумма выигравших и проигравших ставок. Возвраты и ожидающие ставки не входят.",10,false,muted)
        if(bets.length()==0) { val e=card(); addText(e,"На вкладке «Линия» выбери исход в карточке матча и добавь его в купон.",12,false,muted); return }
        for(i in bets.length()-1 downTo 0) { val b=bets.optJSONObject(i) ?: continue; val box=card()
            val st=b.optString("status")
            val status=when(st){"WON"->"ВЫИГРЫШ";"LOST"->"ПРОИГРЫШ";"PUSH"->"ВОЗВРАТ";else->"НЕ РАССЧИТАНА"}
            val col=when(st){"WON"->accent;"LOST"->Color.RED;else->Color.YELLOW}
            addText(box,b.optString("league")+" · "+date(b.optString("date")),10,false,muted)
            addText(box, if (b.optString("marketKey") == "COUPON") "Экспресс · " + (b.optJSONArray("selections")?.length() ?: 0) + " исходов" else b.optString("home")+" — "+b.optString("away"),15,true)
            addText(box,b.optString("market")+" · кэф "+fmt(b.optDouble("odds")),12,true,accent)
            if (b.has("couponScores")) addText(box, b.optString("couponScores"), 10, false, muted)
            if (b.optString("marketKey") == "COUPON") {
                addText(box, "Сумма купона: " + fmt(b.optDouble("stake")), 11, false, muted)
                val legs = b.optJSONArray("selections")
                for (legIndex in 0 until (legs?.length() ?: 0)) {
                    val leg = legs?.optJSONObject(legIndex) ?: continue
                    addText(box, (legIndex + 1).toString() + ". " + leg.optString("home") + " — " + leg.optString("away") +
                        " · " + leg.optString("market") + " · " + fmt(leg.optDouble("odds")), 10, false, muted)
                }
            } else {
                addText(box,"Ставка: "+fmt(b.optDouble("stake"))+" · модель: "+String.format(Locale.getDefault(),"%.1f%%",b.optDouble("probability")*100),11,false,muted)
            }
            val liveStatus = when(b.optString("matchStatus")) {
                "IN_PLAY", "LIVE", "PAUSED" -> " · LIVE"
                "FINISHED" -> " · ФИНАЛ"
                "SCHEDULED", "TIMED" -> " · ЕЩЁ НЕ НАЧАЛСЯ"
                else -> ""
            }
            addText(box,status+liveStatus+(if(b.has("score"))" · счёт "+b.optString("score") else ""),12,true,col)
            if(st=="PENDING") actionButton(box,"Изменить фактический коэффициент") { showEditBetOddsDialog(b) }
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
        screenHeader("IQ MATCH · PROFILE", "Профиль и справка", "О платформе, модели, источниках данных и правилах использования.")
        val profile = card()
        addText(profile, "IQ MATCH", 18, true, Color.WHITE)
        addText(profile, "Аналитическая платформа футбольных матчей", 11, false, muted)
        addText(profile, "Локальных записей ставок: ${bets.length()} · текущий банк: ${String.format(Locale.US, "%.2f ₽", currentBankroll())}", 12, true, accent)
        addText(profile, "Матчи и xG: PitchAPI · котировки: API-Sport / BetBoom", 10, false, muted)
        section("1. КАК РАБОТАЕТ МОДЕЛЬ",
            "Модель оценивает ожидаемые голы команд по историческим данным PitchAPI и рассчитывает вероятности с помощью независимого распределения Пуассона. Параметры: последние 30 матчей, half-life 12, shrinkage 3. Это вероятности, а не точный счёт.")
        section("2. ОСНОВНЫЕ РЫНКИ",
            "1X — победа хозяев или ничья.\nX2 — победа гостей или ничья.\nИТБ 1.5 — команда забьёт минимум 2 гола.\nДля ставок доступны только поддерживаемые ненулевые форы.")
        section("3. ДОПОЛНИТЕЛЬНЫЕ РЫНКИ",
            "ОЗ — да/нет: обе команды забьют хотя бы по одному голу или хотя бы одна команда не забьёт.\nВ карточках отображаются только тоталы БОЛЬШЕ: общие и индивидуальные.\nВероятности рассчитываются по xG независимым распределением Пуассона. Котировки доступны только при наличии свежих данных BetBoom от API-Sport.")
        section("4. AI И СИЛЬНЕЙШИЙ СИГНАЛ",
            "В AI показываются матчи на сегодня и ближайшие 30 дней. Для UEFA используются серверные paper-сигналы с edge после удаления маржи, p ≥60%, EV ≥5% и актуальной ценой BetBoom. Для остальных лиг — положительный сигнал, цена от 1.50 и свежесть до 5 минут. Это не гарантия value или прибыли.")
        section("5. АНАЛИЗ",
            "Для каждого рынка показываются попадания и их доля, разбивка по уверенности 50–59%, 60–69%, 70–79% и 80%+, Brier Score и Log Loss. Уверенность — max(p, 1−p), то есть вероятность выбранной стороны. Чем ниже Brier Score и Log Loss, тем лучше.")
        section("6. ИСТОЧНИКИ И ОГРАНИЧЕНИЯ",
            "PitchAPI — источник расписания, результатов и shot-level xG. API-Sport — источник котировок только BetBoom. Неполный xG не заменяется нулём; матчи без парного xG не участвуют в xG-обучении. Статистика «Анализа» — мониторинг сохранённых прогнозов, а не независимый тест и не показатель доходности. Для оценки прибыли нужны реальные коэффициенты BetBoom на момент прогноза и walk-forward проверка.")
    }

    private fun topSignalOdds(signal: JSONObject, match: JSONObject): Double? {
        val side = signal.optString("side")
        val best = match.optJSONObject("odds")?.optJSONObject("best") ?: return null
        fun acceptable(value: Double) = value.isFinite() && value >= 1.50
        if (side == "1X" || side == "X2") {
            val dc = best.optJSONObject("doubleChance") ?: return null
            return dc.optDouble(if (side == "1X") "1x" else "x2", Double.NaN).takeIf { acceptable(it) }
        }
        if (side.startsWith("ИТБ ")) {
            val point = Regex("ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)").find(side)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val teamName = side.substringAfter("·", "").trim()
            val team = when (teamName) {
                match.optString("home") -> "home"
                match.optString("away") -> "away"
                else -> return null
            }
            val rows = best.optJSONArray("teamTotals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val line = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (row.optString("team") == team && row.optString("side") == "over" && line.isFinite() && kotlin.math.abs(line - point) < 0.001 && acceptable(price)) return price
            }
        }
        if (side.startsWith("ТБ ")) {
            val point = side.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            val rows = best.optJSONArray("totals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val line = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (row.optString("side") == "over" && line.isFinite() && kotlin.math.abs(line - point) < 0.001 && acceptable(price)) return price
            }
        }
        if (side == "ОЗ ДА" || side == "ОЗ НЕТ") {
            val btts = best.optJSONObject("btts") ?: return null
            return btts.optDouble(if (side == "ОЗ ДА") "yes" else "no", Double.NaN).takeIf { acceptable(it) }
        }
        return null
    }

    // Recalculate quote age from the bookmaker board timestamp; the stored feed age becomes stale.
    private fun currentBookmakerQuoteAgeSeconds(odds: JSONObject?): Double? {
        if (odds == null || odds.optString("freshnessStatus") != "fresh") return null
        val raw = odds.optString("updatedAt").trim()
        if (raw.isBlank()) return null
        return try {
            var normalized = raw.replace(Regex("\\.\\d+"), "")
            normalized = normalized.replace("T", " ").replace("Z", "+0000")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).apply { isLenient = false }
            val timestamp = format.parse(normalized)?.time ?: return null
            (System.currentTimeMillis() - timestamp) / 1000.0
        } catch (_: Exception) { null }
    }

    private fun hasFreshBookmakerQuote(match: JSONObject): Boolean {
        val age = currentBookmakerQuoteAgeSeconds(match.optJSONObject("odds")) ?: return false
        return age in 0.0..300.0
    }

    private fun uefaPaperSignal(match: JSONObject): JSONObject? {
        val rows = match.optJSONArray("paperSignals") ?: return null
        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val side = when (row.optString("market")) {
                "double_chance" -> when (row.optString("outcome")) { "1x" -> "1X"; "x2" -> "X2"; else -> "" }
                "match_total_over" -> "ТБ " + row.optDouble("point").toString()
                "team_total_over" -> {
                    val team = when (row.optString("team")) {
                        "home" -> match.optString("home")
                        "away" -> match.optString("away")
                        else -> ""
                    }
                    "ИТБ " + row.optDouble("point").toString() + " · " + team
                }
                "btts" -> if (row.optString("outcome") == "yes") "ОЗ ДА" else ""
                else -> ""
            }
            if (side.isBlank()) continue
            val probability = row.optDouble("model_probability", Double.NaN)
            val signal = JSONObject().apply {
                put("side", side)
                put("modelProbability", probability)
                put("baselineProbability", probability - row.optDouble("edge", 0.0))
                put("edgePp", row.optDouble("edge", 0.0) * 100.0)
                put("baselineMatches", 0)
                put("paperSignalId", row.optString("signal_id"))
                put("paperOnly", true)
            }
            val price = topSignalOdds(signal, match) ?: continue
            val archivedPrice = row.optDouble("price", Double.NaN)
            val edge = row.optDouble("edge", Double.NaN)
            val expectedValue = row.optDouble("expected_value", Double.NaN)
            val sameQualifyingPrice = archivedPrice.isFinite() && kotlin.math.abs(price - archivedPrice) < 0.005
            if (hasFreshBookmakerQuote(match) && sameQualifyingPrice &&
                probability.isFinite() && probability >= 0.60 &&
                edge.isFinite() && edge >= 0.05 &&
                expectedValue.isFinite() && expectedValue >= 0.05) candidates.add(signal)
        }
        return candidates.maxByOrNull {
            val price = topSignalOdds(it, match) ?: 0.0
            it.optDouble("modelProbability") * price - 1.0
        }
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
        screenHeader("AI · MATCH INTELLIGENCE", "AI-аналитика и сигналы", "Каждый кандидат проходит проверку исторической базы, цены BetBoom и ограничений модели.")
        val pendingBets = (0 until bets.length()).count { bets.optJSONObject(it)?.optString("status") == "PENDING" }
        val betsCountCard = card()
        addText(betsCountCard, "СТАВКИ", 10, true, cyan)
        addText(betsCountCard, bets.length().toString(), 26, true, Color.WHITE)
        addText(betsCountCard, "Всего зафиксировано · ожидают результата: $pendingBets", 11, false, muted)
        val target = card()
        addText(target, "ГОДОВАЯ ЦЕЛЬ · 2 500 ₽ → 100 000 ₽", 10, true, gold)
        addText(target, "Для роста в 40 раз за год нужен средний сложный рост около 35.9% в месяц. Это крайне амбициозная математическая цель, не прогноз и не обещание.", 12, true)
        addText(target, "Не будем повышать риск, догонять проигрыши или ставить ради выполнения календарного плана.", 11, false, muted)
        val bank = currentBankroll()
        val stakePlan = bank * 0.005
        val bankrollCard = card()
        addText(bankrollCard, "ТЕКУЩИЙ БАНК", 10, true, cyan)
        addText(bankrollCard, String.format(Locale.US, "%.2f ₽", bank), 24, true, Color.WHITE)
        addText(bankrollCard, "Базовая ставка 0,5%: " + String.format(Locale.US, "%.2f ₽", stakePlan), 15, true, gold)
        addText(bankrollCard, "Базовый размер по стратегии. Допустимый диапазон 0,25–1%; лимит на матч 1%, на день 2%.", 10, false, muted)
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
                val end = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { add(java.util.Calendar.DAY_OF_YEAR, 30) }
                val endDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(end.time)
                if (matchDay < today || matchDay > endDay) continue
                val signal = m.optJSONObject("prediction")?.optJSONObject("strongestSignal")
                    ?: (if (league.optString("code") == "UCL" || league.optString("code") == "UEL") uefaPaperSignal(m) else null)
                    ?: continue
                if (signal.optBoolean("noPositiveSignal") || signal.optDouble("edgePp") <= 0.0 || signal.optString("side").equals("ОЗ НЕТ", ignoreCase = true)) continue
                val price = topSignalOdds(signal, m) ?: continue
                if (price < 1.50) continue
                candidates.add(Triple(m, league.optString("name"), signal))
            }
        }
        val sorted = candidates.filter { item ->
            val m = item.first
            val signal = item.third
            val price = topSignalOdds(signal, m) ?: return@filter false
            val probability = signal.optDouble("modelProbability", Double.NaN)
            val fresh = hasFreshBookmakerQuote(m)
            val ev = probability * price - 1.0
            if (signal.optBoolean("paperOnly")) {
                fresh && probability >= 0.60 && signal.optDouble("edgePp") >= 5.0 && ev >= 0.05
            } else {
                val impliedEdge = probability - 1.0 / price
                fresh && probability >= 0.60 && impliedEdge >= 0.05 && ev >= 0.05
            }
        }.sortedByDescending {
            val price = topSignalOdds(it.third, it.first) ?: 0.0
            it.third.optDouble("modelProbability") * price - 1.0
        }
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "Нет подтверждённых рекомендаций.", 15, true, gold)
            addText(empty, "Нужны положительный сигнал и актуальная прематч-котировка BetBoom от 1.50. Не подменяем отсутствующие цены котировками других букмекеров.", 11, false, muted)
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
            addText(box, "Рынок: ${signal.optString("side")} · BetBoom ${String.format(Locale.US, "%.2f", odds)}", 14, true, gold)
            addText(box, "ПОЧЕМУ ВЫБРАНО", 10, true, cyan)
            if (signal.optBoolean("paperOnly")) {
                addText(box, "Модель: ${String.format(Locale.US, "%.1f", p * 100)}%; рыночный порог для оценки: ${String.format(Locale.US, "%.1f", baseline * 100)}%.", 11, false, Color.WHITE)
                addText(box, "Преимущество над рыночным порогом: +${String.format(Locale.US, "%.1f", edge)} п.п. · сигнал прошёл серверную проверку UEFA.", 11, false, muted)
            } else {
                addText(box, "Модель: ${String.format(Locale.US, "%.1f", p * 100)}%; историческая база: ${String.format(Locale.US, "%.1f", baseline * 100)}% на ${signal.optInt("baselineMatches")} матчах.", 11, false, Color.WHITE)
                addText(box, "Отклонение модели от базы: +${String.format(Locale.US, "%.1f", edge)} п.п. Это фильтр для анализа, но не доказательство value относительно букмекера.", 11, false, muted)
            }
            addText(box, "ПРОВЕРКА ЦЕНЫ", 10, true, cyan)
            addText(box, "Безубыточная вероятность по коэффициенту: ${String.format(Locale.US, "%.1f", implied * 100)}%. Простое EV модели: ${if (ev >= 0) "+" else ""}${String.format(Locale.US, "%.1f", ev * 100)}%.", 11, false, Color.WHITE)
            addText(box, "EV = p × коэффициент − 1; показатель не учитывает маржу, ошибку калибровки и неопределённость модели.", 10, false, muted)
            addText(box, "РИСКИ", 10, true, gold)
            addText(box, "Историческая частота может не отражать текущую силу команд. Перепроверь цену в BetBoom перед ставкой; если коэффициент ниже 1.50 или ухудшился — пропускаем.", 11, false, muted)
            addText(box, "Купон не отправляется букмекеру автоматически. Сверь цену в приложении BetBoom, исправь её при необходимости и только затем подтверди фиксацию.", 11, true, gold)
            actionButton(box, "Проверить коэффициент и подготовить купон") { showAddBetDialog(m, league) }
        }
        val footer = card()
        addText(footer, "ПРАВИЛА AI", 10, true, accent)
        addText(footer, "Окно отбора — 30 дней. UEFA: серверный edge после удаления маржи, p ≥60%, EV ≥5%, совпадение цены с квалифицирующим снимком и фактическая свежесть до 5 минут. Остальные лиги: p ≥60%, break-even edge ≥5 п.п., EV ≥5%. Только BetBoom.", 11, false, muted)
    }

    private fun renderDay(json: JSONObject) {
        screenHeader("DAILY SIGNAL FILTER", "Ставки дня", "Сильные положительные сигналы с подтверждённой котировкой от 1.50 на выбранную сторону.")
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
        addText(summary, "АВТОМАТИЧЕСКИЙ ОТБОР · КУПОНЫ К ПОДТВЕРЖДЕНИЮ", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Допуск: p модели ≥60%, преимущество над break-even ≥5 п.п., EV ≥5%, свежая цена BetBoom не старше 5 минут. Каждый купон требует твоей проверки и подтверждения.", 11, false, muted)
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
            actionButton(box, "＋ В купон") { showAddBetDialog(m, leagueName) }
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
        if (currentTab == "coupon") {
            leagueBar.visibility = View.GONE
            renderCoupon()
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

        addText(matchesContainer, "РАСПИСАНИЕ", 9, true, cyan)
        addText(matchesContainer, "Выбери лигу и открой карточку матча", 16, true, Color.WHITE)
        addText(matchesContainer, "ВЕРОЯТНОСТИ МОДЕЛИ · КОТИРОВКИ BETBOOM", 9, true, muted)
        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL")
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
                val matchStatus = m.optString("status")
                val matchId = m.optString("id",m.optString("utcDate")+m.optString("home")+m.optString("away"))
                val hasTrackedBet = (0 until bets.length()).any { bets.optJSONObject(it)?.optString("matchId") == matchId }
                if ((matchStatus in listOf("SCHEDULED", "TIMED") && m.optJSONObject("prediction") != null) ||
                    (matchStatus in listOf("IN_PLAY", "LIVE", "PAUSED")) ||
                    (matchStatus == "FINISHED" && hasTrackedBet)) {
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
            if (leagueCode == "PL") {
                heading.addView(englandFlagView())
            } else {
                val flag = when (leagueCode) { "PD" -> "🇪🇸"; "SA" -> "🇮🇹"; "BL1" -> "🇩🇪"; "FL1" -> "🇫🇷"; else -> "⚽" }
                heading.addView(countryFlagView(flag))
            }
            heading.addView(label(league.optString("name"), 14, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("${upcoming.size} матчей", 10, false, muted))
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
                box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(16, 42, 61), Color.rgb(8, 22, 39), Color.rgb(9, 35, 47))).apply {
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(40, 121, 128))
                }
                addText(box, "✦  MATCH FIXTURE  ·  ${date(m.optString("utcDate"))}", 9, true, accent)
                addMatchup(box, m.optString("home"), m.optString("away"))
                val scoreAvailable = !m.isNull("homeScore") && !m.isNull("awayScore")
                val scoreText = if (scoreAvailable) " · ${m.optInt("homeScore")} : ${m.optInt("awayScore")}" else ""
                val matchStatusLabel = when(m.optString("status")) {
                    "IN_PLAY", "LIVE" -> "● LIVE"
                    "PAUSED" -> "ПЕРЕРЫВ"
                    "FINISHED" -> "МАТЧ ЗАВЕРШЁН"
                    "SCHEDULED", "TIMED" -> "ЕЩЁ НЕ НАЧАЛСЯ"
                    else -> m.optString("status")
                }
                addText(box, matchStatusLabel + scoreText, 12, true,
                    if (m.optString("status") in listOf("IN_PLAY", "LIVE")) accent else muted)
                val pred = m.optJSONObject("prediction")
                if (pred == null) {
                    if ((m.optJSONArray("paperSignals")?.length() ?: 0) > 0) {
                        addText(box, "Матч завершён · исторический сигнал модели ниже", 11, false, muted)
                    } else {
                        addText(box, m.optString("predictionWarning", "Прогноз пока недоступен"), 11, false, Color.rgb(242, 190, 104))
                    }
                } else {
                    val signal = pred.optJSONObject("strongestSignal")
                    val signalBox = card(box)
                    signalBox.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(32, 55, 66), Color.rgb(17, 34, 52))).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(67, 137, 119)) }
                    addText(signalBox, "✦  САМЫЙ СИЛЬНЫЙ СИГНАЛ", 10, true, gold)
                    if (signal == null) {
                        addText(signalBox, if (leagueCode == "UCL" || leagueCode == "UEL") "Вероятности рассчитаны по историческим xG; проверенные тестовые сигналы и их результаты — ниже." else "Недостаточно исторических матчей для надёжной базовой частоты.", 11, false, muted)
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
                    addMarketTitle(mainMarkets, "◈", "ОСНОВНЫЕ ИСХОДЫ")
                    val bestOdds = m.optJSONObject("odds")?.optJSONObject("best")
                    fun quote(key: String): String {
                        val dc = bestOdds?.optJSONObject("doubleChance")
                        val btts = bestOdds?.optJSONObject("btts")
                        val value = when {
                            key in listOf("1x", "x2", "12") -> dc?.optDouble(key, Double.NaN)
                            key == "bttsYes" -> btts?.optDouble("yes", Double.NaN)
                            key == "bttsNo" -> btts?.optDouble("no", Double.NaN)
                            else -> null
                        }
                        return if (value != null && value.isFinite() && value > 1.0) String.format(Locale.US, "%.2f", value) else "—"
                    }

                    fun totalQuote(point: Double, side: String, team: String? = null): String {
                        val rows = bestOdds?.optJSONArray(if (team == null) "totals" else "teamTotals") ?: return "—"
                        for (i in 0 until rows.length()) {
                            val row = rows.optJSONObject(i) ?: continue
                            val rowPoint = row.optDouble("point", Double.NaN)
                            val rowPrice = row.optDouble("price", Double.NaN)
                            if (row.optString("side").equals(side, ignoreCase = true) &&
                                (team == null || row.optString("team") == team) &&
                                rowPoint.isFinite() && kotlin.math.abs(rowPoint - point) < 0.001 &&
                                rowPrice.isFinite() && rowPrice > 1.0) {
                                return String.format(Locale.US, "%.2f", rowPrice)
                            }
                        }
                        return "—"
                    }
                    addText(mainMarkets, "1X · ${pct(pred, "1x")} · кэф ${quote("1x")}       X2 · ${pct(pred, "x2")} · кэф ${quote("x2")}", 12, true, Color.WHITE)
                    addText(mainMarkets, "ПОБЕДА / НИЧЬЯ · без котировок в текущем фиде", 9, true, muted)
                    addText(mainMarkets, "П1 ${pct(pred, "1")}     X ${pct(pred, "x")}     П2 ${pct(pred, "2")}", 12, true, Color.WHITE)
                    addText(mainMarkets, "ОБЕ КОМАНДЫ ЗАБЬЮТ", 9, true, muted)
                    addText(mainMarkets, "ОЗ: ДА ${pct(pred, "bttsYes")} · кэф ${quote("bttsYes")}     НЕТ ${pct(pred, "bttsNo")} · кэф ${quote("bttsNo")}", 11, true, Color.WHITE)
                    addText(mainMarkets, "ТОТАЛ МАТЧА", 9, true, muted)
                    for ((line, key) in listOf(1.5 to "over15", 2.5 to "over25", 3.5 to "over35")) {
                        addText(mainMarkets, "ТБ ${fmt(line)}: ${pct(pred, key)} · кэф ${totalQuote(line, "over")}", 11, false, Color.WHITE)
                    }
                    addText(mainMarkets, "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ", 9, true, muted)
                    for ((teamKey, teamName, sideKey) in listOf(Triple("home", m.optString("home"), "home"), Triple("away", m.optString("away"), "away"))) {
                        addText(mainMarkets, teamName, 11, true, accent)
                        for ((line, suffix) in listOf(0.5 to "05", 1.5 to "15", 2.5 to "25", 3.5 to "35")) {
                            val probKey = "${teamKey}Over${suffix}"
                            addText(mainMarkets, "ИТБ ${fmt(line)}: ${pct(pred, probKey)} · кэф ${totalQuote(line, "over", sideKey)}", 11, false, Color.WHITE)
                        }
                    }
                    addText(mainMarkets, "ОЖИДАЕМЫЕ ГОЛЫ", 9, true, muted)
                    addText(mainMarkets, "${String.format(Locale.US, "%.2f", pred.optDouble("expectedHomeGoals"))} : ${String.format(Locale.US, "%.2f", pred.optDouble("expectedAwayGoals"))}", 13, true, Color.WHITE)

                    actionButton(box, "＋ Добавить ставку") { showAddBetDialog(m, league.optString("name")) }
                }
            }
        }

        val footer = card()
        addText(footer, "ПОКРЫТИЕ", 10, true, accent)
        addText(footer, "Предстоящих матчей: $totalUpcoming  ·  с прогнозом: $totalPredicted", 13, true)
        addText(footer, "Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", 11, false, muted)
        addText(footer, "Источники: PitchAPI (матчи и xG) · API-Sport / BetBoom (котировки).", 10, false, muted)
    }
}