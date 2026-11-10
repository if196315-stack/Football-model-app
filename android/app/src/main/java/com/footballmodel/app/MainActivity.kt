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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {
    private val dataUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/data.json"
    private val observationSnapshotsUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/artifacts/odds-snapshots.jsonl"
    private val observationResultsUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/artifacts/market-observation-results.jsonl"
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
    private lateinit var headerBalanceView: TextView
    private lateinit var headerAiRoiView: TextView
    private lateinit var leagueBar: LinearLayout
    private lateinit var leagueScroll: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private var currentTab = "matches"
    private var statsSubsection = "working"
    private lateinit var matchesContainer: LinearLayout
    private var data: JSONObject? = null
    private var bets = JSONArray()
    private val betPrefs by lazy { getSharedPreferences("bet_history", MODE_PRIVATE) }
    private val settingsPrefs by lazy { getSharedPreferences("bank_settings", MODE_PRIVATE) }
    private val feedCache by lazy { getSharedPreferences("feed_cache_v1", MODE_PRIVATE) }
    private val loadInProgress = AtomicBoolean(false)
    @Volatile private var lastPayload: String? = null
    private data class BetMarket(val key: String, val label: String)
    private var selectedLeague = "ALL"
    private val expandedLeagues = mutableMapOf<String, Boolean>()
    // Сохраняем уже построенные экраны: повторное переключение вкладок не пересоздаёт сотни View.
    private val tabViewCache = mutableMapOf<String, Pair<List<View>, List<View>>>()
    private var renderedKey: String? = null
    private var workerStatusTextView: TextView? = null
    private var workerMessageTextView: TextView? = null
    private var workerCountTextView: TextView? = null
    private val liveRefreshHandler = Handler(Looper.getMainLooper())
    private val liveRefreshTask = object : Runnable {
        override fun run() {
            refreshWorkerStatusUi()
            if (!loadInProgress.get() && currentTab in listOf("matches", "history", "desk")) loadData()
            liveRefreshHandler.postDelayed(this, 60 * 1000L)
        }
    }

    private fun refreshWorkerStatusUi() {
        val lastRun = settingsPrefs.getLong("paper_worker_last_run_ms", 0L)
        val status = settingsPrefs.getString("paper_worker_last_status", "NOT_RUN") ?: "NOT_RUN"
        val message = settingsPrefs.getString("paper_worker_last_message", "Ожидается первая фоновая проверка") ?: "Ожидается первая фоновая проверка"
        workerStatusTextView?.text = if (status == "SUCCESS") "Последняя проверка: ${formatLocalTimestamp(lastRun)}"
            else if (status == "ERROR") "Ошибка · ${formatLocalTimestamp(lastRun)}"
            else "Ожидание первого запуска"
        workerStatusTextView?.setTextColor(if (status == "ERROR") Color.RED else Color.WHITE)
        workerMessageTextView?.text = message
        workerCountTextView?.text = "Бумажных ставок: ${settingsPrefs.getInt("paper_worker_total_bets", 0)} · ожидают: ${settingsPrefs.getInt("paper_worker_pending_auto", 0)} · период проверки: от 15 минут"
    }

    private fun scheduleAutoPaperWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AutoPaperWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "iq_match_auto_paper_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        val immediate = OneTimeWorkRequestBuilder<AutoPaperWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "iq_match_auto_paper_initial",
            ExistingWorkPolicy.KEEP,
            immediate
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = intent.getStringExtra("open_tab") ?: "matches"
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
        PaperBetNotifications.ensureChannel(this)
        PaperBetNotifications.requestPermissionIfNeeded(this)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val crest = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "IQ Match · нажмите для обновления"
            isClickable = true
            isFocusable = true
            setOnClickListener { loadData() }
        }
        top.addView(crest, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(11) })
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Обновить базу данных"
            setOnClickListener { loadData() }
        }
        brand.addView(label("IQ MATCH", 24, true, Color.WHITE))
        brand.addView(label("SMART FOOTBALL INTELLIGENCE", 8, true, cyan))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val headerRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val notificationsButton = TextView(this).apply {
            text = "🔔"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(surface, 13)
            contentDescription = "Ежедневный отчёт и аналитика"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentTab = "notifications"
                tabViewCache.clear()
                renderedKey = null
                if (data != null) render()
                loadData()
            }
        }
        headerRight.addView(notificationsButton, LinearLayout.LayoutParams(dp(42), dp(38)).apply { bottomMargin = dp(3) })
        headerBalanceView = label("-- ₽", 12, true, Color.WHITE).apply { gravity = Gravity.END; maxLines = 1 }
        headerRight.addView(headerBalanceView)
        headerAiRoiView = label("AI ROI --", 10, true, accent).apply { gravity = Gravity.END; maxLines = 1 }
        headerRight.addView(headerAiRoiView)
        top.addView(headerRight, LinearLayout.LayoutParams(dp(100), -2))
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
        root.addView(leagueScroll, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        matchesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(matchesContainer)
        bets = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
        scheduleAutoPaperWorker()
        // Восстанавливаем сохранённый снимок в фоне: большой JSON не должен
        // блокировать первый показ интерфейса на главном потоке.
        thread(name = "iq-match-cache-restore") {
            val cached = feedCache.getString("merged_json", null)
            if (!cached.isNullOrBlank()) {
                val restored = try { JSONObject(cached) } catch (_: Exception) { null }
                if (restored == null) {
                    feedCache.edit().remove("merged_json").apply()
                } else {
                    runOnUiThread {
                        // Если сеть уже успела вернуть свежие данные, старый кэш не перезаписывает их.
                        if (data == null) {
                            data = restored
                            lastPayload = cached
                            render()
                        }
                    }
                }
            }
        }
        // Сначала показываем оболочку, затем параллельно восстанавливаем кэш и обновляем сеть.
        renderTabs()
        leagueBar.visibility = View.VISIBLE
        val loadingCard = card()
        addText(loadingCard, "●  MATCH CENTER", 10, true, cyan)
        addText(loadingCard, "Интерфейс готов. Получаю матчи и прогнозы…", 13, false, muted)
        loadData()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent.getStringExtra("open_tab")
        if (requestedTab == "desk") {
            currentTab = "desk"
            tabViewCache.clear()
            renderedKey = null
            if (data != null) render() else loadData()
        }
    }

    override fun onResume() {
        super.onResume()
        val latestBets = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
        if (latestBets.toString() != bets.toString()) {
            bets = latestBets
            tabViewCache.clear()
            renderedKey = null
            if (data != null) render()
        }
        liveRefreshHandler.removeCallbacks(liveRefreshTask)
        liveRefreshHandler.postDelayed(liveRefreshTask, 60 * 1000L)
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

    // Compact section heading: no oversized description banner.
    private fun screenHeader(eyebrow: String, title: String, description: String) {
        val heading = label(title, 20, true, Color.WHITE).apply {
            setPadding(dp(2), dp(5), dp(2), dp(8))
        }
        matchesContainer.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(2)
        })
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
            val items = listOf("yes" to "ОЗ — да")
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
        addText(panelOdds, "Обновлено: " + formatSourceTimestamp(odds.optString("updatedAt")) + " · местное время", 9, false, muted)
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

    private fun kickoffTimestamp(match: JSONObject): Long? {
        val timestamp = match.optLong("startTimestamp", 0L)
        if (timestamp > 0L) return if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        val raw = match.optString("utcDate").ifBlank { match.optString("dateEvent") }
        if (raw.isBlank()) return null
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val parsed = parser.parse(raw) ?: continue
                return parsed.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun kickoffLabel(match: JSONObject): String {
        val raw = match.optString("utcDate").ifBlank { match.optString("dateEvent") }
        val millis = kickoffTimestamp(match) ?: return "Дата и время начала не указаны"
        val hasTime = raw.contains("T") || match.optLong("startTimestamp", 0L) > 0L
        val formatted = SimpleDateFormat(if (hasTime) "EEE, dd MMM · HH:mm" else "EEE, dd MMM", Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getDefault() }.format(Date(millis))
        return if (hasTime) "$formatted · местное время" else "$formatted · время начала не указано"
    }

    private fun formatLocalTimestamp(millis: Long): String =
        if (millis <= 0L) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(millis))

    private fun formatSourceTimestamp(raw: String): String {
        if (raw.isBlank()) return "—"
        val millis = kickoffTimestamp(JSONObject().put("utcDate", raw)) ?: return raw.replace("T", " ").take(19)
        return formatLocalTimestamp(millis)
    }


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

    private fun jsonArrayFromJsonLines(body: String?): JSONArray {
        val rows = JSONArray()
        if (body.isNullOrBlank()) return rows
        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            try { rows.put(JSONObject(line)) } catch (_: Exception) { /* Ignore an incomplete/corrupt archive row. */ }
        }
        return rows
    }

    private fun loadData() {
        if (!loadInProgress.compareAndSet(false, true)) return
        statusView.text = if (data == null) "Загружаю данные…" else "Проверяю обновления…"
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
                // Download the append-only archives only when Stats is opened; match refreshes stay lightweight.
                if (currentTab == "stats" || currentTab == "notifications") {
                    val snapshotsBody = fetchFeed(observationSnapshotsUrl, "market_observation_snapshots", false)
                    val resultsBody = fetchFeed(observationResultsUrl, "market_observation_results", false)
                    json.put("marketObservationSnapshots", jsonArrayFromJsonLines(snapshotsBody))
                    json.put("marketObservationResults", jsonArrayFromJsonLines(resultsBody))
                } else {
                    json.put("marketObservationSnapshots", JSONArray())
                    json.put("marketObservationResults", JSONArray())
                }
                val payload = json.toString()
                feedCache.edit()
                    .putString("merged_json", payload)
                    .putLong("last_successful_check_ms", System.currentTimeMillis())
                    .apply()
                runOnUiThread {
                    val changed = payload != lastPayload
                    if (changed) {
                        data = json
                        lastPayload = payload
                        tabViewCache.clear()
                        renderedKey = null
                        render()
                    }
                    // Evaluate every successful feed check, even when the payload is unchanged.
                    // Duplicate protection makes this safe and lets newly valid quotes lock promptly.
                    val newAutoBets = processAutoPaperBets(json)
                    newAutoBets.forEach { PaperBetNotifications.show(this, it) }
                    if (!changed) {
                        statusView.text = "●  Данные актуальны · источник: ${formatSourceTimestamp(json.optString("updatedAt"))} · проверено: ${formatLocalTimestamp(feedCache.getLong("last_successful_check_ms", 0L))}"
                    }
                    loadInProgress.set(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val lastCheck = formatLocalTimestamp(feedCache.getLong("last_successful_check_ms", 0L))
                    statusView.text = (if (data != null) "Нет сети · показаны последние данные" else
                        "Нет соединения · ${e.message ?: "проверьте интернет"}") + " · последняя успешная проверка: $lastCheck"
                    loadInProgress.set(false)
                }
            }
        }
    }

    private fun renderTabs() {
        val items = listOf("matches" to "Линия", "desk" to "AI", "history" to "История", "stats" to "Stats", "help" to "Профиль")
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
                            if (currentTab == "desk" || currentTab == "stats") loadData()
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
        val switcher = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.rgb(13, 23, 39), 15)
        }
        listOf("working" to "РАБОЧИЕ СТАВКИ", "observed" to "НАБЛЮДАЕМЫЕ КОТИРОВКИ").forEach { (key, title) ->
            val selected = statsSubsection == key
            val tab = label(title, 10, true, if (selected) bg else muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(13), dp(8), dp(13))
                background = if (selected) rounded(accent, 11) else rounded(Color.TRANSPARENT, 11)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (statsSubsection != key) {
                        statsSubsection = key
                        render()
                    }
                }
            }
            switcher.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        matchesContainer.addView(switcher, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (statsSubsection == "observed") {
            renderObservedMarketStats(json)
            return
        }
        screenHeader("PERFORMANCE CENTER", "Статистика стратегии", "Точность рынков действующей стратегии. Уровни уверенности сохранены без изменений.")
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

        // IQ MATCH Strategy v1.1 — постоянно доступная памятка с правилами отбора и риска.
        val strategyIntro = card()
        addText(strategyIntro, "IQ MATCH STRATEGY · VERSION 1.1", 10, true, accent)
        addText(strategyIntro, "СТРОГИЙ ОТБОР · УМЕРЕННЫЙ РИСК · ОГРАНИЧЕННЫЙ ЗАПУСК", 13, true, Color.WHITE)
        addText(strategyIntro, "Ставка допускается только после проверки данных, вероятности, value и риска. Если хотя бы один обязательный фильтр не пройден — WAIT или REJECT. Отсутствие ставки является полноценным решением.", 11, false, muted)

        val strategyFilters = card()
        addText(strategyFilters, "1. ФИЛЬТРЫ ДОПУСКА", 11, true, accent)
        addText(strategyFilters, "Данные: матч сопоставлен однозначно; котировка только BetBoom через API-Sport; реальная цена и точное совпадение рынка, стороны и линии обязательны. Для автофиксации бумажной ставки котировка должна обновиться в последние 12 часов до матча; жёсткого фильтра возраста 5 минут нет.", 11, false, Color.WHITE)
        addText(strategyFilters, "Сигнал A: вероятность модели ≥ 60%; преимущество над break-even ≥ 5 п.п.; EV ≥ +5%; модель проверена на данном типе рынка; сигнал устойчив к небольшим изменениям предположений.", 11, false, Color.WHITE)
        addText(strategyFilters, "B = WAIT: интересный сигнал, но не хватает подтверждений или данных. C = REJECT: отрицательное/недостаточное value, плохие данные или неприемлемый риск.", 11, false, muted)

        val strategyValue = card()
        addText(strategyValue, "2. VALUE И EV", 11, true, accent)
        addText(strategyValue, "Break-even probability = 1 / коэффициент. EV = вероятность модели × коэффициент − 1. Для BET стартовый минимум EV — +5%, а преимущество над break-even — не менее 5 процентных пунктов. Высокая вероятность сама по себе не означает выгодную ставку.", 11, false, Color.WHITE)
        addText(strategyValue, "Пример: вероятность 60%, коэффициент 1.90 → break-even 52.63%, EV +14%. Пример иллюстративный; калибровка модели и фактическая цена обязательны.", 10, false, muted)

        val strategyRisk = card()
        addText(strategyRisk, "3. БАНК И РИСК", 11, true, accent)
        addText(strategyRisk, "Размер ставки — 1% текущего банка, но не менее минимальной ставки BetBoom 25 ₽. Поэтому при балансе 2 500 ₽ ставка 25 ₽, а при 2 499 ₽ (1% = 24,99 ₽) всё равно ставится 25 ₽ — это обязательный минимум букмекера. Дневного лимита суммарной нагрузки нет: фиксируются все сигналы, прошедшие критерии стратегии.", 11, false, Color.WHITE)
        addText(strategyRisk, "Запрещены догон, увеличение ставки после выигрыша и автоматический рост риска. Полный Kelly не используется. Просадка 5% от локального максимума — пауза и аудит; 10% — остановка реальных ставок до пересмотра.", 11, false, muted)

        val strategyMarkets = card()
        addText(strategyMarkets, "4. РЫНКИ И ИСТОЧНИК", 11, true, accent)
        addText(strategyMarkets, "Только BetBoom через API-Sport. Рабочие рынки: Double Chance 1X/X2, Match Total OVER, Team Total OVER и BTTS YES — только при точной загруженной цене и прохождении фильтров AI. BTTS NO, UNDER, 12, 1X2 без подтверждённой цены и форы отображаются только как наблюдаемые рынки и не создают paper-ставки.", 11, false, Color.WHITE)
        addText(strategyMarkets, "Нельзя подставлять коэффициенты другого букмекера, угадывать отсутствующую цену или сравнивать разные линии.", 10, false, muted)

        val strategyTesting = card()
        addText(strategyTesting, "5. ТЕСТИРОВАНИЕ И ДОПУСК К ЗАПУСКУ", 11, true, accent)
        addText(strategyTesting, "Этап 1 — аудит данных и сопоставления. Этап 2 — хронологический бэктест без утечки будущих данных, с отдельными train/validation/test периодами. Этап 3 — paper trading на актуальных BetBoom котировках без реальных ставок. Этап 4 — ограниченный запуск с базовой ставкой 1% банка, без дневного лимита суммарной нагрузки. Этап 5 — регулярный аудит и пересмотр.", 11, false, Color.WHITE)
        addText(strategyTesting, "Стартовые ориентиры: ≥500 исторических сигналов и ≥500 paper-сигналов; желательно ≥150–200 наблюдений на оцениваемый рынок. Эти объёмы не гарантируют статистическую надёжность: показывать доверительные интервалы и учитывать неопределённость.", 11, false, muted)

        val strategyMetrics = card()
        addText(strategyMetrics, "6. ЧТО КОНТРОЛИРОВАТЬ В STATS", 11, true, accent)
        addText(strategyMetrics, "ROI, Yield, CLV, максимальная просадка, Brier Score, Log Loss, калибровка вероятностей, win rate, EV по каждому рынку и причины REJECT/WAIT. Win rate не является главным критерием. Сохранять все сигналы, включая отклонённые, коэффициент на момент решения и версию стратегии.", 11, false, Color.WHITE)
        addText(strategyMetrics, "Пороговые значения — стартовая конфигурация для проверки, а не доказанное преимущество и не гарантия прибыли. Изменения правил должны получать новую версию и проверяться на независимых данных.", 10, false, muted)
    }


    private fun renderObservedMarketStats(json: JSONObject) {
        screenHeader("MARKET RESEARCH", "Наблюдаемые котировки", "Архивные цены BetBoom. Снимки отображаются сразу, не дожидаясь завершения матча.")
        val snapshots = json.optJSONArray("marketObservationSnapshots") ?: JSONArray()
        val results = json.optJSONArray("marketObservationResults") ?: JSONArray()
        val summary = card()
        addText(summary, "ОБЪЁМ АРХИВА", 10, true, accent)
        addText(summary, "Снимков котировок: ${snapshots.length()}", 15, true, Color.WHITE)
        addText(summary, "Завершённых наблюдений: ${results.length()}", 13, true, Color.WHITE)
        var totalWins = 0
        var totalLosses = 0
        var totalPushes = 0
        var totalProfit = 0.0
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            when (row.optString("result")) {
                "WIN" -> totalWins++
                "LOSS" -> totalLosses++
                "PUSH" -> totalPushes++
            }
            totalProfit += row.optDouble("profit_units", 0.0)
        }
        if (results.length() > 0) {
            addText(summary, "WIN $totalWins · LOSS $totalLosses · PUSH $totalPushes", 12, true, accent)
            addText(summary, "Условный результат: ${String.format(Locale.US, "%+.2f", totalProfit)} ед. · ROI ${String.format(Locale.US, "%+.2f", totalProfit / results.length() * 100.0)}%", 12, true, gold)
            addText(summary, "ROI рассчитан по одной условной единице на каждое завершённое наблюдение.", 10, false, muted)
        } else {
            addText(summary, "Результатов завершённых матчей пока нет.", 11, false, muted)
        }

        // Сводка отдельно по каждому точному типу наблюдаемого рынка и линии.
        fun observationKey(row: JSONObject): String = listOf(
            row.optString("market"), row.optString("side"), row.optString("point"),
            row.optString("team"), row.optString("outcome")
        ).joinToString("|")
        fun observationTitle(row: JSONObject): String {
            val market = row.optString("market")
            val side = row.optString("side").uppercase(Locale.ROOT)
            val point = if (row.has("point") && !row.isNull("point")) " ${fmt(row.optDouble("point"))}" else ""
            return when (market) {
                "double_chance" -> "Двойной шанс ${row.optString("outcome").uppercase(Locale.ROOT)}"
                "match_total" -> "Тотал матча $side$point"
                "team_total" -> "${if (row.optString("team") == "home") "Хозяева" else "Гости"} · ИТ $side$point"
                "btts" -> "Обе забьют · ${if (row.optString("outcome") == "yes") "ДА" else "НЕТ"}"
                "handicap" -> "Фора ${if (row.optString("team") == "home") "хозяев" else "гостей"} ($point)"
                else -> market.ifBlank { "Рынок" } + point
            }
        }
        data class ObservationStats(var snapshots: Int = 0, var wins: Int = 0, var losses: Int = 0,
                                    var pushes: Int = 0, var pending: Int = 0, var profit: Double = 0.0)
        val grouped = linkedMapOf<String, Pair<JSONObject, ObservationStats>>()
        fun groupFor(row: JSONObject): ObservationStats {
            val key = observationKey(row)
            val existing = grouped[key]
            if (existing != null) return existing.second
            val stats = ObservationStats()
            grouped[key] = row to stats
            return stats
        }
        for (i in 0 until snapshots.length()) {
            val row = snapshots.optJSONObject(i) ?: continue
            groupFor(row).snapshots++
        }
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val stats = groupFor(row)
            when (row.optString("result")) {
                "WIN" -> stats.wins++
                "LOSS" -> stats.losses++
                "PUSH" -> stats.pushes++
                else -> stats.pending++
            }
            stats.profit += row.optDouble("profit_units", 0.0)
        }
        if (grouped.isNotEmpty()) {
            addText(matchesContainer, "РЕЗУЛЬТАТЫ ПО КАЖДОМУ РЫНКУ", 10, true, accent)
            grouped.values.sortedBy { observationTitle(it.first) }.forEach { (sample, stats) ->
                val settled = stats.wins + stats.losses + stats.pushes
                val turnover = stats.wins + stats.losses
                val box = card()
                addText(box, observationTitle(sample), 14, true, Color.WHITE)
                addText(box, "Снимков: ${stats.snapshots} · завершено: $settled · ожидают: ${stats.pending}", 11, false, muted)
                addText(box, "WIN ${stats.wins} · LOSS ${stats.losses} · PUSH ${stats.pushes}", 12, true, accent)
                if (settled > 0) {
                    val roi = if (turnover > 0) stats.profit / turnover * 100.0 else 0.0
                    addText(box, "Условный результат: ${String.format(Locale.US, "%+.2f", stats.profit)} ед. · ROI ${if (turnover > 0) String.format(Locale.US, "%+.2f%%", roi) else "—"}", 12, true, gold)
                    addText(box, "Попадания: ${stats.wins}/${stats.wins + stats.losses} · доля ${if (stats.wins + stats.losses > 0) String.format(Locale.US, "%.1f%%", stats.wins * 100.0 / (stats.wins + stats.losses)) else "—"}", 11, false, muted)
                } else {
                    addText(box, "Недостаточно завершённых наблюдений для ROI и процента попаданий.", 10, false, muted)
                }
            }
        } else {
            val emptyStats = card()
            addText(emptyStats, "Сводка по рынкам пока недоступна", 13, true, Color.WHITE)
            addText(emptyStats, "В архиве нет снимков и результатов. Сводки появятся после того, как workflow начнёт публиковать архив котировок.", 11, false, muted)
        }

        if (snapshots.length() > 0) {
            addText(matchesContainer, "АРХИВ КОТИРОВОК · ${snapshots.length()}", 10, true, accent)
            for (i in snapshots.length() - 1 downTo 0) {
                val row = snapshots.optJSONObject(i) ?: continue
                val market = row.optString("market")
                val side = row.optString("side").uppercase(Locale.ROOT)
                val point = if (row.has("point") && !row.isNull("point")) " ${fmt(row.optDouble("point"))}" else ""
                val marketName = when (market) {
                    "double_chance" -> "Двойной шанс ${row.optString("outcome").uppercase(Locale.ROOT)}"
                    "match_total" -> "Тотал матча $side$point"
                    "team_total" -> "${if (row.optString("team") == "home") "Хозяева" else "Гости"} · ИТ $side$point"
                    "btts" -> "Обе забьют · ${if (row.optString("outcome") == "yes") "ДА" else "НЕТ"}"
                    "handicap" -> "Фора ${if (row.optString("team") == "home") "хозяев" else "гостей"} (${fmt(row.optDouble("point"))})"
                    else -> market.ifBlank { "Рынок" }
                }
                val price = row.optDouble("price", Double.NaN)
                val probability = if (!row.isNull("model_probability")) "${String.format(Locale.US, "%.1f", row.optDouble("model_probability") * 100)}%" else "нет оценки модели"
                val item = card()
                addText(item, "${row.optString("home_team")} — ${row.optString("away_team")}", 13, true, Color.WHITE)
                addText(item, "${row.optString("league_code")} · ${formatSourceTimestamp(row.optString("kickoff_utc"))}", 10, false, muted)
                addText(item, marketName, 11, true, accent)
                addText(item, "BetBoom ${if (price.isFinite() && price > 1.0) String.format(Locale.US, "%.2f", price) else "—"} · модель $probability", 11, true, gold)
                addText(item, "Снимок: ${formatSourceTimestamp(row.optString("captured_at"))} · актуальность: ${row.optString("freshness_status", "не указана")}", 9, false, muted)
                val result = row.optString("result")
                if (result.isNotBlank()) addText(item, "Результат: $result", 10, true, accent)
            }
        } else {
            val empty = card()
            addText(empty, "Архив котировок пока пуст", 13, true, Color.WHITE)
            addText(empty, "Снимки появятся после успешного обновления BetBoom и публикации artifacts/odds-snapshots.jsonl. Если архив ещё не создавался, нужно дождаться первого запуска workflow после его включения.", 11, false, muted)
        }
        if (results.length() > 0) {
            val note = card()
            addText(note, "ВАЖНО", 10, true, gold)
            addText(note, "ROI считается по каждой сохранённой котировке как если бы на неё ставили по 1 условной единице. Это исследовательская метрика, не фактическая прибыль и не движение paper-банка.", 11, false, muted)
        }
    }

    private fun parseFeedTimeMillis(raw: String): Long? {
        return try {
            val value = raw.trim().take(19)
            if (value.length != 19) return null
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            format.parse(value)?.time
        } catch (_: Exception) { null }
    }

    private fun marketKeyForAutoSignal(signal: JSONObject, match: JSONObject): String? {
        val side = signal.optString("side")
        if (side == "1X") return "1x"
        if (side == "X2") return "x2"
        if (side == "ОЗ ДА") return "bttsYes"
        if (side.startsWith("ТБ ")) {
            val line = side.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            if (abs(line * 10.0 - (line * 10.0).toInt()) > 0.001) return null
            return "over" + (line * 10.0).toInt().toString().padStart(2, '0')
        }
        if (side.startsWith("ИТБ ")) {
            val line = side.removePrefix("ИТБ ").substringBefore("·").trim().toDoubleOrNull() ?: return null
            if (abs(line * 10.0 - (line * 10.0).toInt()) > 0.001) return null
            val team = side.substringAfter("·", "").trim()
            val prefix = when (team) {
                match.optString("home") -> "homeOver"
                match.optString("away") -> "awayOver"
                else -> return null
            }
            return prefix + (line * 10.0).toInt().toString().padStart(2, '0')
        }
        return null
    }

    // Lock a paper bet as soon as the visible AI candidate qualifies in the next-30-day window.
    // The stored odds and quote timestamp remain fixed through settlement.
    private fun processAutoPaperBets(json: JSONObject): List<JSONObject> {
        return synchronized(AutoPaperExecutionLock) { processAutoPaperBetsUnlocked(json) }
    }

    private fun processAutoPaperBetsUnlocked(json: JSONObject): List<JSONObject> {
        // Reload persisted worker changes before foreground processing.
        bets = try { JSONArray(betPrefs.getString("bets", bets.toString())) } catch (_: Exception) { bets }
        val now = System.currentTimeMillis()
        val localDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        var changed = false
        val newAutoBets = mutableListOf<JSONObject>()
        for (i in 0 until bets.length()) {
            val existing = bets.optJSONObject(i) ?: continue
            if (existing.optString("status") == "PENDING" && existing.optString("marketKey") != "COUPON") {
                val match = findMatch(json, existing) ?: continue
                val before = existing.optString("status")
                settleBet(existing, match)
                if (before != existing.optString("status")) changed = true
            }
        }
        val currentBankForRisk = currentBankroll()
        val storedHigh = settingsPrefs.getString("paper_high_watermark", null)?.toDoubleOrNull()
        val highWater = maxOf(storedHigh ?: currentBankForRisk, currentBankForRisk)
        if (storedHigh == null || highWater > storedHigh) settingsPrefs.edit().putString("paper_high_watermark", highWater.toString()).apply()
        val drawdown = if (highWater > 0.0) (highWater - currentBankForRisk) / highWater else 0.0
        if (drawdown >= 0.10) settingsPrefs.edit().putBoolean("paper_auto_stopped", true).apply()
        val autoStopped = settingsPrefs.getBoolean("paper_auto_stopped", false)
        val drawdownPaused = drawdown >= 0.05
        val competitions = json.optJSONArray("competitions")
        if (competitions != null) for (i in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(i) ?: continue
            val matches = competition.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                if (match.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val kickoff = parseFeedTimeMillis(match.optString("utcDate")) ?: continue
                val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val kickoffDate = dayFormat.format(java.util.Date(kickoff))
                val today = dayFormat.format(java.util.Date(now))
                val endDate = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 30)
                }.let { dayFormat.format(it.time) }
                if (kickoffDate < today || kickoffDate > endDate) continue
                val odds = match.optJSONObject("odds") ?: continue
                val quoteUpdatedAt = parseFeedTimeMillis(odds.optString("updatedAt")) ?: continue
                // Lock the visible BetBoom quote when the candidate first qualifies in the AI window.
                if (quoteUpdatedAt > now) continue
                val signal = (match.optJSONObject("prediction")?.optJSONObject("strongestSignal")
                    ?: if (competition.optString("code") in listOf("UCL", "UEL")) uefaPaperSignal(match) else null)
                    ?: continue
                if (signal.optBoolean("noPositiveSignal") || signal.optDouble("edgePp") <= 0.0 ||
                    signal.optString("side") == "ОЗ НЕТ") continue
                val price = topSignalOdds(signal, match) ?: continue
                val probability = signal.optDouble("modelProbability", Double.NaN)
                val edge = if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", Double.NaN) else (probability - 1.0 / price) * 100.0 + signal.optDouble("formAdjustmentPp", 0.0)
                val ev = probability * price - 1.0
                if (!probability.isFinite() || probability < 0.60 || !edge.isFinite() || edge < 5.0 || ev < 0.05 || price < 1.50) continue
                val marketKey = marketKeyForAutoSignal(signal, match) ?: continue
                val matchId = match.optString("id", match.optString("utcDate") + match.optString("home") + match.optString("away"))
                val id = "AI_AUTO:" + matchId + ":" + marketKey
                var duplicate = false
                for (k in 0 until bets.length()) {
                    val prior = bets.optJSONObject(k) ?: continue
                    if (prior.optString("id") == id ||
                        (prior.optString("matchId") == matchId && prior.optString("status") in listOf("PENDING", "WON", "LOST"))) {
                        duplicate = true
                        break
                    }
                }
                if (duplicate || autoStopped || drawdownPaused) continue
                val bank = currentBankroll()
                val stake = maxOf(bank * 0.01, 25.0)
                // No daily exposure cap: every qualifying AI signal is paper-logged.
                val newBet = JSONObject()
                    .put("id", id)
                    .put("source", "AI_AUTO")
                    .put("status", "PENDING")
                    .put("matchId", matchId)
                    .put("league", competition.optString("name"))
                    .put("date", match.optString("utcDate"))
                    .put("betDay", localDay)
                    .put("home", match.optString("home"))
                    .put("away", match.optString("away"))
                    .put("market", signal.optString("side"))
                    .put("marketKey", marketKey)
                    .put("odds", price)
                    .put("stake", stake)
                    .put("probability", probability)
                    .put("edgePp", edge)
                    .put("expectedValue", ev)
                    .put("quoteUpdatedAt", odds.optString("updatedAt"))
                    .put("createdAt", now)
                    .put("matchStatus", "SCHEDULED")
                    .put("score", "")
                bets.put(newBet)
                newAutoBets.add(JSONObject(newBet.toString()))
                changed = true
            }
        }
        if (changed) saveBets()
        return newAutoBets
    }

    private fun saveBets(preserveBackgroundAuto: Boolean = true) {
        synchronized(AutoPaperExecutionLock) {
            val persisted = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
            val merged = LinkedHashMap<String, JSONObject>()
            for (i in 0 until bets.length()) {
                val item = bets.optJSONObject(i) ?: continue
                val key = item.optString("id").ifBlank { "local:$i" }
                merged[key] = item
            }
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i) ?: continue
                if (item.optString("source") != "AI_AUTO") continue
                val key = item.optString("id")
                if (key.isBlank()) continue
                val local = merged[key]
                if (local == null && preserveBackgroundAuto) {
                    merged[key] = item
                } else if (local != null && local.optString("status") == "PENDING" &&
                    item.optString("status") in listOf("WON", "LOST", "PUSH")) {
                    merged[key] = item
                }
            }
            bets = JSONArray(merged.values.toList())
            betPrefs.edit().putString("bets", bets.toString()).commit()
        }
        tabViewCache.remove("history|ALL")
        tabViewCache.remove("desk|ALL")
        tabViewCache.remove("stats|ALL")
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
            if (match == null || match.isNull("homeScore") || match.isNull("awayScore")) {
                allSettled = false
                continue
            }
            val matchStatus = match.optString("status")
            val scoreLabel = pick.optString("home") + " " + match.optInt("homeScore") + ":" + match.optInt("awayScore") + " " + pick.optString("away")
            if (matchStatus == "FINISHED") {
                val leg = JSONObject().put("status", "PENDING").put("marketKey", pick.optString("marketKey"))
                settleBet(leg, match)
                val result = leg.optString("status")
                scores.add(scoreLabel + " · ФИНАЛ")
                if (result == "LOST") lost = true
                if (result == "WON") effectiveOdds *= pick.optDouble("odds", 1.0)
            } else {
                allSettled = false
                if (matchStatus in listOf("IN_PLAY", "LIVE", "PAUSED")) scores.add(scoreLabel + " · LIVE")
            }
        }
        bet.put("couponScores", scores.joinToString(" · "))
        val anyLive = scores.any { it.contains("LIVE") }
        bet.put("matchStatus", if (anyLive) "IN_PLAY" else if (allSettled) "FINISHED" else "SCHEDULED")
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
        screenHeader("BET TRACKER", "История", "Текущий баланс, правила расчёта ставки и история бумажных ставок.")
        val bank = currentBankroll()
        val bankrollCard = card()
        addText(bankrollCard, "ТЕКУЩИЙ БАЛАНС", 10, true, cyan)
        addText(bankrollCard, String.format(Locale.US, "%.2f ₽", bank), 28, true, Color.WHITE)
        addText(bankrollCard, "Баланс учитывает стартовый банк, чистый результат рассчитанных ставок и суммы, зарезервированные по ожидающим ставкам.", 11, false, muted)

        val strategyCard = card()
        addText(strategyCard, "КАК РАБОТАЕТ СТРАТЕГИЯ", 11, true, accent)
        addText(strategyCard, "Размер одной ставки — 1% от текущего баланса, но не меньше 25 ₽: это минимальная сумма, которую принимает BetBoom.", 11, false, Color.WHITE)
        addText(strategyCard, "Примеры: баланс 2 500 ₽ → 1% = 25 ₽, ставка 25 ₽; баланс 2 499 ₽ → 1% = 24,99 ₽, но фактическая ставка всё равно 25 ₽ из-за минимума букмекера.", 11, false, muted)
        addText(strategyCard, "Обычный расчёт — 1% банка на матч, но если 1% меньше 25 ₽, применяется минимум BetBoom 25 ₽. Поэтому при балансе ниже 2 500 ₽ фактическая ставка может быть чуть больше 1%. Дневного лимита суммарной нагрузки нет: все сигналы, прошедшие критерии стратегии, автоматически фиксируются как paper-ставки.", 11, false, Color.WHITE)
        addText(strategyCard, "Это автоматические бумажные ставки для проверки стратегии, а не размещение реальных ставок у букмекера. Догон и увеличение риска после проигрыша не используются.", 10, false, muted)
        var changed=false
        for(i in 0 until bets.length()) { val b=bets.optJSONObject(i) ?: continue
            if (b.optString("marketKey") == "COUPON") {
                val before = b.optString("status")
                val beforeScores = b.optString("couponScores")
                val beforeMatchStatus = b.optString("matchStatus")
                settleCoupon(j, b)
                if (b.optString("status") != before || b.optString("couponScores") != beforeScores ||
                    b.optString("matchStatus") != beforeMatchStatus) changed = true
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
                "PUSH"->{pushes++;turnover+=st;payout+=st}
                else->pending++
            }
        }
        val sum=card()
        addText(sum,"СТАТИСТИКА СТАВОК",10,true,accent)
        addText(sum,"Всего: "+bets.length()+" · выигрыш: "+wins+" · проигрыш: "+losses+" · возврат: "+pushes+" · не рассчитаны: "+pending,12,true)
        addText(sum,"Сумма ставок: "+fmt(stakeAll),13,true)
        addText(sum,"Выплаты: "+fmt(payout)+" · чистый результат: "+(if(profit>=0)"+" else "")+fmt(profit),12,true,if(profit>=0)accent else Color.RED)
        addText(sum,"ROI: "+(if(turnover>0)String.format(Locale.getDefault(),"%.1f%%",profit/turnover*100) else "—")+" · рассчитанный оборот: "+fmt(turnover),12,true)
        addText(sum,"ОБЩИЙ ROI · все бумажные ставки",10,true,cyan)
        addText(sum,"ROI = чистый результат / рассчитанный оборот. Возвраты и ожидающие ставки не входят.",10,false,muted)
        var aiTurnover=0.0; var aiProfit=0.0; var aiWins=0; var aiLosses=0; var aiPushes=0; var aiPending=0
        for(i in 0 until bets.length()) {
            val b=bets.optJSONObject(i) ?: continue
            if(b.optString("source")!="AI_AUTO") continue
            val st=b.optDouble("stake"); val o=b.optDouble("odds")
            when(b.optString("status")) {
                "WON"->{aiWins++;aiTurnover+=st;aiProfit+=st*(o-1.0)}
                "LOST"->{aiLosses++;aiTurnover+=st;aiProfit-=st}
                "PUSH"->{aiPushes++;aiTurnover+=st}
                "PENDING"->aiPending++
            }
        }
        val ai=card()
        addText(ai,"AI · АВТОМАТИЧЕСКИЕ БУМАЖНЫЕ СТАВКИ",10,true,accent)
        addText(ai,"Ставок: "+(aiWins+aiLosses+aiPushes+aiPending)+" · выигрыш: "+aiWins+" · проигрыш: "+aiLosses+" · возврат: "+aiPushes+" · ожидают: "+aiPending,12,true)
        addText(ai,"Чистый результат: "+(if(aiProfit>=0) "+" else "")+fmt(aiProfit)+" ₽ · оборот: "+fmt(aiTurnover)+" ₽",12,true)
        addText(ai,"AI ROI: "+(if(aiTurnover>0.0) String.format(Locale.US,"%.1f%%",aiProfit/aiTurnover*100.0) else "—"),18,true,if(aiProfit>=0) accent else Color.RED)
        addText(ai,"Учитываются только ставки, автоматически открытые из AI. Ожидающие и возвраты не входят в оборот.",10,false,muted)
        if(bets.length()==0) { val e=card(); addText(e,"Записанных ставок пока нет.",12,false,muted); return }
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
                bets=next;saveBets(preserveBackgroundAuto = false);render()
            }
        }
    }

    private fun renderHelp() {
        screenHeader("IQ MATCH · PROFILE", "Профиль", "Настройки банка и состояние фоновых обновлений.")
        val profile = card()
        addText(profile, "IQ MATCH", 18, true, Color.WHITE)
        addText(profile, "Локальных записей ставок: ${bets.length()} · текущий банк: ${String.format(Locale.US, "%.2f ₽", currentBankroll())}", 12, true, accent)
        addText(profile, "Стартовый банк: ${fmt(initialBankroll())} ₽", 12, false, muted)
        actionButton(profile, "Изменить стартовый банк") { showBankSettingDialog("initial_bankroll", "Стартовый банк", initialBankroll(), "Укажи сумму банка, от которой считать результат. Записанные ставки и результаты сохраняются.") }

        val workerCard = card()
        val workerRunAt = settingsPrefs.getLong("paper_worker_last_run_ms", 0L)
        val workerStatus = settingsPrefs.getString("paper_worker_last_status", "NOT_RUN") ?: "NOT_RUN"
        val workerMessage = settingsPrefs.getString("paper_worker_last_message", "Ожидается первая фоновая проверка") ?: "Ожидается первая фоновая проверка"
        val workerTime = if (workerRunAt > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(workerRunAt)) else "ещё не запускался"
        addText(workerCard, "ФОНОВОЕ ОБНОВЛЕНИЕ", 10, true, cyan)
        workerStatusTextView = label(if (workerStatus == "SUCCESS") "Последняя проверка: $workerTime" else if (workerStatus == "ERROR") "Ошибка · $workerTime" else "Ожидание первого запуска", 12, true, if (workerStatus == "ERROR") Color.RED else Color.WHITE)
        workerMessageTextView = label(workerMessage, 11, false, muted)
        workerCountTextView = label("Бумажных ставок: ${settingsPrefs.getInt("paper_worker_total_bets", 0)} · ожидают: ${settingsPrefs.getInt("paper_worker_pending_auto", 0)} · период проверки: от 15 минут", 10, false, muted)
        workerCard.addView(workerStatusTextView)
        workerCard.addView(workerMessageTextView)
        workerCard.addView(workerCountTextView)
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
            if (sameQualifyingPrice &&
                probability.isFinite() && probability >= 0.60 &&
                edge.isFinite() && edge >= 0.05 &&
                expectedValue.isFinite() && expectedValue >= 0.05) candidates.add(signal)
        }
        return candidates.maxByOrNull {
            val price = topSignalOdds(it, match) ?: 0.0
            it.optDouble("modelProbability") * price - 1.0
        }
    }

    private fun initialBankroll(): Double = settingsPrefs.getString("initial_bankroll", "2500")
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 2500.0

    private fun targetBankroll(): Double = settingsPrefs.getString("target_bankroll", "100000")
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 100000.0

    private fun showBankSettingDialog(key: String, title: String, current: Double, message: String) {
        val field = EditText(this).apply {
            hint = "Сумма в рублях"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.US, "%.2f", current))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || !value.isFinite() || value <= 0.0) {
                    Toast.makeText(this, "Введи сумму больше нуля", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settingsPrefs.edit().putString(key, value.toString()).apply()
                // Стартовый банк отображается в профиле и на экране «Банк».
                // Сбрасываем оба кеша, чтобы новые значения появились сразу без перезапуска.
                if (key == "initial_bankroll") {
                    tabViewCache.remove("history|ALL")
                    tabViewCache.remove("help|ALL")
                }
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    // Bank = user-defined starting bankroll + settled net P/L - stakes reserved on pending bets.
    private fun currentBankroll(): Double {
        var bank = initialBankroll()
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

    private fun aiPaperRoiPercent(): Pair<Double?, Int> {
        var profit = 0.0
        var turnover = 0.0
        var settledCount = 0
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("source") != "AI_AUTO") continue
            val stake = bet.optDouble("stake", 0.0)
            val odds = bet.optDouble("odds", 0.0)
            when (bet.optString("status")) {
                "WON" -> { profit += stake * (odds - 1.0); turnover += stake; settledCount++ }
                "LOST" -> { profit -= stake; turnover += stake; settledCount++ }
                "PUSH" -> { turnover += stake; settledCount++ }
            }
        }
        return (if (turnover > 0.0) profit / turnover * 100.0 else null) to settledCount
    }

    private fun refreshHeaderFinancials() {
        if (!::headerBalanceView.isInitialized || !::headerAiRoiView.isInitialized) return
        headerBalanceView.text = String.format(Locale.US, "%.0f ₽", currentBankroll())
        val (roi, count) = aiPaperRoiPercent()
        headerAiRoiView.text = if (roi == null) "AI ROI —" else String.format(Locale.US, "AI ROI %+.1f%% · %d", roi, count)
        headerAiRoiView.setTextColor(if (roi == null) muted else if (roi >= 0.0) accent else gold)
    }

    private fun renderDailyInsights(json: JSONObject) {
        screenHeader("IQ MATCH · DAILY REVIEW", "Отчёт и наблюдения", "Разбор результатов, качества сигналов и идей для улучшения. Аналитика не меняет правила ставок.")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dayBets = mutableListOf<JSONObject>()
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val day = bet.optString("betDay").ifBlank { bet.optString("date").take(10) }
            if (day == today) dayBets.add(bet)
        }
        val wins = dayBets.count { it.optString("status") == "WON" }
        val losses = dayBets.count { it.optString("status") == "LOST" }
        val pushes = dayBets.count { it.optString("status") == "PUSH" }
        val pending = dayBets.count { it.optString("status") == "PENDING" }
        val profit = dayBets.sumOf {
            val stake = it.optDouble("stake", 0.0)
            when (it.optString("status")) {
                "WON" -> stake * (it.optDouble("odds", 0.0) - 1.0)
                "LOST" -> -stake
                else -> 0.0
            }
        }
        val summary = card()
        addText(summary, "СЕГОДНЯ · $today", 10, true, cyan)
        addText(summary, "Бумажные ставки: ${dayBets.size}", 16, true, Color.WHITE)
        addText(summary, "WIN $wins · LOSS $losses · возврат $pushes · ожидают $pending", 12, true, accent)
        addText(summary, "Результат рассчитанных ставок: ${String.format(Locale.US, "%+.2f ₽", profit)}", 13, true, if (profit >= 0) accent else gold)
        addText(summary, "Текущий бумажный банк: ${String.format(Locale.US, "%.2f ₽", currentBankroll())}", 12, false, muted)

        val observations = json.optJSONArray("marketObservationResults") ?: JSONArray()
        val snapshots = json.optJSONArray("marketObservationSnapshots") ?: JSONArray()
        var observedWins = 0
        var observedLosses = 0
        var observedPushes = 0
        var observedPending = 0
        var observedProfit = 0.0
        for (i in 0 until observations.length()) {
            val row = observations.optJSONObject(i) ?: continue
            when (row.optString("result")) {
                "WIN" -> observedWins++
                "LOSS" -> observedLosses++
                "PUSH" -> observedPushes++
                else -> observedPending++
            }
            observedProfit += row.optDouble("profit_units", 0.0)
        }
        val observationCard = card()
        addText(observationCard, "НАБЛЮДАТЕЛЬНЫЕ СТАВКИ · НАКОПИТЕЛЬНО", 10, true, cyan)
        addText(observationCard, "Снимков котировок: ${snapshots.length()} · записей архива: ${observations.length()}", 12, true, Color.WHITE)
        addText(observationCard, "WIN $observedWins · LOSS $observedLosses · PUSH $observedPushes · ожидают $observedPending", 12, true, accent)
        if (observations.length() > 0) {
            val turnover = observedWins + observedLosses
            val roi = if (turnover > 0) observedProfit / turnover * 100.0 else 0.0
            addText(observationCard, "Условный результат: ${String.format(Locale.US, "%+.2f ед.", observedProfit)} · ROI ${String.format(Locale.US, "%+.2f%%", roi)}", 12, true, gold)
            addText(observationCard, "Это исследовательская статистика архива, не фактическая прибыль и не изменение бумажного банка.", 10, false, muted)
        } else {
            addText(observationCard, "Архив результатов пока пуст. Выводы по наблюдениям появятся после накопления и расчёта матчей.", 11, false, muted)
        }

        val strengths = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        if (dayBets.isEmpty()) strengths.add("Сегодня нет записей ставок по дате матча. Подходящих сигналов могло не быть.")
        else {
            if (wins > losses) strengths.add("По рассчитанным paper-ставкам больше побед, чем поражений ($wins:$losses).")
            if (pending > 0) strengths.add("$pending ставок ещё ожидают расчёта — не считаем их выигрышами или проигрышами.")
            if (losses > wins) improvements.add("Поражений больше, чем побед ($losses против $wins). Проверим рынки и цену, но не будем менять пороги по одному дню.")
            if (profit < 0) improvements.add("Дневной результат отрицательный. Сначала накопим выборку и сравним рынки, не повышая риск.")
        }
        if (observations.length() == 0) {
            improvements.add("Нужно накопить завершённые наблюдательные матчи: пока нельзя честно выделить лучшие рынки.")
        } else {
            val settled = observedWins + observedLosses
            if (settled >= 10 && observedLosses > observedWins) improvements.add("В архиве наблюдений поражений больше побед. Проверим точные рынки и линии отдельно, прежде чем что-либо менять.")
            else if (settled >= 10 && observedWins > observedLosses) strengths.add("Наблюдательная выборка показывает больше WIN, чем LOSS. Проверим устойчивость результата по рынкам.")
            else improvements.add("Выборка наблюдений пока небольшая; выводы по ROI и отдельным рынкам предварительные.")
        }
        val strengthsCard = card()
        addText(strengthsCard, "ЧТО ХОРОШО", 10, true, accent)
        strengths.forEach { addText(strengthsCard, "• $it", 12, false, Color.WHITE) }
        val improveCard = card()
        addText(improveCard, "ЧТО УЛУЧШИТЬ", 10, true, gold)
        improvements.forEach { addText(improveCard, "• $it", 12, false, Color.WHITE) }

        val recommendations = card()
        addText(recommendations, "МОИ ВЫВОДЫ", 10, true, cyan)
        addText(recommendations, "1. Сравнивать рабочие ставки и наблюдательные сигналы отдельно: это разные выборки.", 12, false, Color.WHITE)
        addText(recommendations, "2. Искать устойчивые закономерности по рынку и линии только на завершённых матчах с достаточным объёмом данных.", 12, false, Color.WHITE)
        addText(recommendations, "3. Не менять пороги вероятности, edge, EV и риск-менеджмент автоматически. Любые изменения — только после отдельной проверки и согласования.", 12, false, muted)
    }

    private fun renderBettingDesk(json: JSONObject) {
        screenHeader("AI · MATCH INTELLIGENCE", "AI-аналитика и сигналы", "Каждый кандидат проходит проверку исторической базы, цены BetBoom и ограничений модели.")
        val bank = currentBankroll()

        val candidates = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val matchDay = m.optString("utcDate").take(10).ifBlank {
                    m.optString("dateEvent").take(10).ifBlank {
                        kickoffTimestamp(m)?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date(it)) }.orEmpty()
                    }
                }
                if (matchDay.isBlank()) continue
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
            val ev = probability * price - 1.0
            if (signal.optBoolean("paperOnly")) {
                probability >= 0.60 && signal.optDouble("edgePp") >= 5.0 && ev >= 0.05
            } else {
                val impliedEdge = probability - 1.0 / price + signal.optDouble("formAdjustmentPp", 0.0) / 100.0
                probability >= 0.60 && impliedEdge >= 0.05 && ev >= 0.05
            }
        }.sortedWith(
            compareBy<Triple<JSONObject, String, JSONObject>> { kickoffTimestamp(it.first) ?: Long.MAX_VALUE }
                .thenByDescending {
                    val price = topSignalOdds(it.third, it.first) ?: 0.0
                    it.third.optDouble("modelProbability") * price - 1.0
                }
        )
        val betsCountCard = card()
        addText(betsCountCard, "НАЙДЕНО AI-СИГНАЛОВ", 10, true, cyan)
        addText(betsCountCard, sorted.size.toString(), 26, true, Color.WHITE)
        addText(betsCountCard, "Матчей, прошедших фильтры раздела AI · это не количество уже записанных ставок", 11, false, muted)
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "Нет подтверждённых рекомендаций.", 15, true, gold)
            addText(empty, "Нужны положительный сигнал, доступная прематч-котировка BetBoom от 1.50 и выполнение порогов стратегии. Возраст цены показываем отдельно; перед ставкой проверь актуальный коэффициент в BetBoom.", 11, false, muted)
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
            addText(box, kickoffLabel(m), 10, false, muted)
            addText(box, "Рынок: ${signal.optString("side")} · BetBoom ${String.format(Locale.US, "%.2f", odds)}", 14, true, gold)
            addText(box, "ПОЧЕМУ ВЫБРАНО", 10, true, cyan)
            if (signal.optBoolean("paperOnly")) {
                addText(box, "Модель: ${String.format(Locale.US, "%.1f", p * 100)}%; рыночный порог для оценки: ${String.format(Locale.US, "%.1f", baseline * 100)}%.", 11, false, Color.WHITE)
                addText(box, "Преимущество над рыночным порогом: +${String.format(Locale.US, "%.1f", edge)} п.п. · сигнал прошёл серверную проверку UEFA.", 11, false, muted)
            } else {
                addText(box, "Модель: ${String.format(Locale.US, "%.1f", p * 100)}%; историческая база: ${String.format(Locale.US, "%.1f", baseline * 100)}% на ${signal.optInt("baselineMatches")} матчах.", 11, false, Color.WHITE)
                addText(box, "Отклонение модели от базы: +${String.format(Locale.US, "%.1f", edge)} п.п.", 11, false, muted)
            }
            addText(box, "ПРОВЕРКА ЦЕНЫ", 10, true, cyan)
            addText(box, "Безубыточная вероятность по коэффициенту: ${String.format(Locale.US, "%.1f", implied * 100)}%. Простое EV модели: ${if (ev >= 0) "+" else ""}${String.format(Locale.US, "%.1f", ev * 100)}%.", 11, false, Color.WHITE)
        }
        val footer = card()
        addText(footer, "ПРАВИЛА AI", 10, true, accent)
        addText(footer, "Окно отбора — 30 дней. UEFA: серверный edge после удаления маржи, p ≥60%, EV ≥5% и совпадение реальной цены с квалифицирующим снимком. Остальные лиги: p ≥60%, break-even edge ≥5 п.п., EV ≥5%. Используются реальные котировки BetBoom.", 11, false, muted)
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
        addText(summary, "АВТОМАТИЧЕСКИЙ ОТБОР · AI-СИГНАЛЫ", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Допуск: p модели ≥60%, преимущество над break-even ≥5 п.п., EV ≥5% и реальная цена BetBoom. Фильтра давности в 5 минут нет; бумажная запись не является реальной ставкой. Риск: 1% банка на ставку, не более 1% на матч без дневного лимита суммарной нагрузки.", 11, false, muted)
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
        }
        val note = card()
        addText(note, "ВАЖНО", 10, true, gold)
        addText(note, "Сильный сигнал означает отличие от исторической частоты, а не доказанное преимущество над букмекерской линией. Проверяйте коэффициент и не воспринимайте список как гарантию доходности.", 11, false, muted)
    }

    private fun render() {
        val json = data ?: return
        refreshHeaderFinancials()
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
        val checkedAt = feedCache.getLong("last_successful_check_ms", 0L)
        val sourceUpdated = formatSourceTimestamp(json.optString("updatedAt"))
        statusView.text = "●  Источник обновлён: $sourceUpdated · проверено: ${formatLocalTimestamp(checkedAt)}"
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

        if (currentTab == "day") {
            leagueBar.visibility = View.GONE
            renderDay(json)
            return
        }
        if (currentTab == "notifications") {
            leagueBar.visibility = View.GONE
            renderDailyInsights(json)
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

        // Keep the fixture feed focused on the next calendar month without changing
        // ingestion, cached payloads, predictions, or historical/live tracked matches.
        val fixtureUtc = java.util.TimeZone.getTimeZone("UTC")
        val fixtureDayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = fixtureUtc }
        val fixtureToday = fixtureDayFormat.format(Date())
        val fixtureMonthEnd = java.util.Calendar.getInstance(fixtureUtc).apply {
            add(java.util.Calendar.MONTH, 1)
        }
        val fixtureEndDay = fixtureDayFormat.format(fixtureMonthEnd.time)

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
                val fixtureDate = m.optString("utcDate").take(10).ifBlank {
                    m.optString("dateEvent").take(10).ifBlank {
                        kickoffTimestamp(m)?.let { fixtureDayFormat.format(Date(it)) }.orEmpty()
                    }
                }
                val withinNextMonth = fixtureDate.isNotBlank() &&
                    fixtureDate >= fixtureToday && fixtureDate <= fixtureEndDay
                if ((matchStatus in listOf("SCHEDULED", "TIMED") &&
                        withinNextMonth && m.optJSONObject("prediction") != null) ||
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
                    addMarketTitle(mainMarkets, "◈", "РЫНКИ И КОТИРОВКИ")
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
                                rowPrice.isFinite() && rowPrice > 1.0) return String.format(Locale.US, "%.2f", rowPrice)
                        }
                        return "—"
                    }
                    fun marketRow(title: String, probability: String, price: String) {
                        addText(mainMarkets, "$title · $probability · кэф $price", 11, true, Color.WHITE)
                    }
                    addText(mainMarkets, "ДВОЙНОЙ ШАНС", 9, true, muted)
                    marketRow("1X", pct(pred, "1x"), quote("1x"))
                    marketRow("X2", pct(pred, "x2"), quote("x2"))
                    addText(mainMarkets, "ОБЕ КОМАНДЫ ЗАБЬЮТ", 9, true, muted)
                    marketRow("ОЗ ДА", pct(pred, "bttsYes"), quote("bttsYes"))
                    addText(mainMarkets, "ТОТАЛ МАТЧА", 9, true, muted)
                    for ((line, key) in listOf(1.5 to "over15", 2.5 to "over25", 3.5 to "over35")) {
                        marketRow("ТБ ${fmt(line)}", pct(pred, key), totalQuote(line, "over"))
                    }
                    addText(mainMarkets, "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ", 9, true, muted)
                    for ((teamKey, teamName, sideKey) in listOf(Triple("home", m.optString("home"), "home"), Triple("away", m.optString("away"), "away"))) {
                        addText(mainMarkets, teamName, 11, true, accent)
                        for ((line, suffix) in listOf(0.5 to "05", 1.5 to "15", 2.5 to "25", 3.5 to "35")) {
                            val probKey = "${teamKey}Over${suffix}"
                            marketRow("ИТБ ${fmt(line)}", pct(pred, probKey), totalQuote(line, "over", sideKey))
                        }
                    }
                    addText(mainMarkets, "ОЖИДАЕМЫЕ ГОЛЫ", 9, true, muted)
                    addText(mainMarkets, "${String.format(Locale.US, "%.2f", pred.optDouble("expectedHomeGoals"))} : ${String.format(Locale.US, "%.2f", pred.optDouble("expectedAwayGoals"))}", 13, true, Color.WHITE)

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