package com.footballmodel.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import android.view.MotionEvent
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
    private lateinit var notificationsButton: TextView
    private lateinit var leagueBar: LinearLayout
    private lateinit var leagueScroll: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private var currentTab = "matches"
    private val primaryTabs = listOf("matches", "desk", "history", "stats", "help")
    private lateinit var matchesContainer: LinearLayout
    private var data: JSONObject? = null
    private var bets = JSONArray()
    private val betPrefs by lazy { getSharedPreferences("bet_history", MODE_PRIVATE) }
    private val aiSignalPrefs by lazy { getSharedPreferences("ai_signal_history", MODE_PRIVATE) }
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
            refreshNotificationBell()
            if (!loadInProgress.get()) loadData()
            liveRefreshHandler.postDelayed(this, 60 * 1000L)
        }
    }

    private fun refreshNotificationBell() {
        if (!::notificationsButton.isInitialized) return
        val unread = settingsPrefs.getBoolean(SectionNotifications.UNREAD_KEY, false)
        notificationsButton.text = if (unread) SpannableString("🔔 •").apply {
            setSpan(ForegroundColorSpan(Color.rgb(255, 82, 96)), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else SpannableString("🔔")
        notificationsButton.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(13).toFloat()
            setStroke(dp(if (unread) 2 else 1), if (unread) Color.rgb(255, 82, 96) else Color.rgb(39, 61, 82))
        }
        notificationsButton.contentDescription = if (unread) "Новое в разделе «Отчёт и наблюдения»" else "Отчёт и наблюдения"
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
        if (currentTab == "notifications") SectionNotifications.markRead(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        val scroll = object : ScrollView(this) {
            private var gestureDownX = 0f
            private var gestureDownY = 0f
            private var swipeOriginInLeagueFilters = false
            private var horizontalSwipeCaptured = false

            // Some clickable descendants request that parents never intercept.
            // Keep that request for the league scroller only; elsewhere the outer
            // pager must retain horizontal gestures for tab navigation.
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                if (swipeOriginInLeagueFilters) {
                    super.requestDisallowInterceptTouchEvent(disallowIntercept)
                } else {
                    super.requestDisallowInterceptTouchEvent(false)
                }
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureDownX = event.x
                        gestureDownY = event.y
                        horizontalSwipeCaptured = false
                        swipeOriginInLeagueFilters = false
                        if (::leagueScroll.isInitialized) {
                            val location = IntArray(2)
                            leagueScroll.getLocationOnScreen(location)
                            val x = event.rawX.toInt()
                            val y = event.rawY.toInt()
                            swipeOriginInLeagueFilters =
                                x >= location[0] && x <= location[0] + leagueScroll.width &&
                                y >= location[1] && y <= location[1] + leagueScroll.height
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - gestureDownX
                        val dy = event.y - gestureDownY
                        if (!swipeOriginInLeagueFilters &&
                            // Capture horizontal motion before a small vertical drift
                            // lets ScrollView claim the gesture.
                            kotlin.math.abs(dx) > dp(12) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                            horizontalSwipeCaptured = true
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!horizontalSwipeCaptured) swipeOriginInLeagueFilters = false
                    }
                }
                return horizontalSwipeCaptured || super.onInterceptTouchEvent(event)
            }

            private fun isHorizontalTabSwipe(event: MotionEvent): Boolean {
                if (swipeOriginInLeagueFilters) return false
                val dx = event.x - gestureDownX
                val dy = event.y - gestureDownY
                return kotlin.math.abs(dx) > dp(12) &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
            }

            private fun finishTabSwipe(event: MotionEvent) {
                val dx = event.x - gestureDownX
                if (kotlin.math.abs(dx) > dp(48)) {
                    selectAdjacentTab(if (dx < 0f) 1 else -1)
                }
                horizontalSwipeCaptured = false
                swipeOriginInLeagueFilters = false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                // ScrollView can take a gesture for vertical scrolling before the
                // user's movement becomes predominantly horizontal. Continue
                // classifying the same gesture here, after interception as well.
                if (!horizontalSwipeCaptured && event.actionMasked == MotionEvent.ACTION_MOVE &&
                    isHorizontalTabSwipe(event)) {
                    horizontalSwipeCaptured = true
                }
                if (horizontalSwipeCaptured) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_UP -> {
                            finishTabSwipe(event)
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            horizontalSwipeCaptured = false
                            swipeOriginInLeagueFilters = false
                            return true
                        }
                        else -> return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
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
        SectionNotifications.ensureChannel(this)
        SectionNotifications.requestPermissionIfNeeded(this)

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
        notificationsButton = TextView(this).apply {
            text = "🔔"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(surface, 13)
            contentDescription = "Отчёт и наблюдения"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                SectionNotifications.markRead(this@MainActivity)
                refreshNotificationBell()
                currentTab = "notifications"
                tabViewCache.clear()
                renderedKey = null
                if (data != null) render()
                loadData()
            }
        }
        headerRight.addView(notificationsButton, LinearLayout.LayoutParams(dp(42), dp(38)).apply { bottomMargin = dp(3) })
        refreshNotificationBell()
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
            // Оставляем справа часть следующего фильтра видимой как подсказку
            // о горизонтальной прокрутке. Контент при этом можно пролистать полностью.
            clipToPadding = true
            setPadding(0, 0, 0, 0)
            addView(leagueBar)
        }
        // Keep a narrow viewport so the next league chip remains visibly peeking in.
        // Use the full available width; the next chip remains partially visible
        // at the right edge as a hint that the league row scrolls horizontally.
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
        if (requestedTab == "notifications") {
            currentTab = "notifications"
            SectionNotifications.markRead(this)
            refreshNotificationBell()
            tabViewCache.clear()
            renderedKey = null
            if (data != null) render() else loadData()
        } else if (requestedTab == "desk") {
            currentTab = "desk"
            tabViewCache.clear()
            renderedKey = null
            if (data != null) render() else loadData()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationBell()
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
            val items = listOf("yes" to "ОЗ — да")
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
                val side = line.optString("side")
                // Индивидуальные UNDER-котировки скрыты; в карточке остаются только OVER.\n                if (!side.equals("over", ignoreCase = true) || !price.isFinite() || price <= 1.0 || !point.isFinite()) continue
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
        listOf("ВЕРОЯТНОСТИ", "ОЖИДАЕМЫЕ ГОЛЫ", "MELBET").forEachIndexed { index, caption ->
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
                            if (code == "UCL" || code == "UEL" || code == "UECL") {
                                for (j in target.length() - 1 downTo 0) {
                                    if (target.optJSONObject(j)?.optString("code") == code) target.remove(j)
                                }
                                target.put(competition)
                            }
                        }
                        // Merge immutable UEFA forecast evaluations into the shared Stats dataset.
                        val combinedEvaluations = json.optJSONArray("evaluationMatches") ?: JSONArray()
                        val knownEvaluationIds = mutableSetOf<String>()
                        for (i in 0 until combinedEvaluations.length()) {
                            val row = combinedEvaluations.optJSONObject(i) ?: continue
                            knownEvaluationIds.add(row.optString("leagueCode") + ":" + row.optString("id"))
                        }
                        val uefaEvaluations = uefa.optJSONArray("evaluationMatches")
                        if (uefaEvaluations != null) for (i in 0 until uefaEvaluations.length()) {
                            val row = uefaEvaluations.optJSONObject(i) ?: continue
                            val key = row.optString("leagueCode") + ":" + row.optString("id")
                            if (key !in knownEvaluationIds) {
                                combinedEvaluations.put(row)
                                knownEvaluationIds.add(key)
                            }
                        }
                        json.put("evaluationMatches", combinedEvaluations)
                        json.put("uefaEvaluationUpdatedAt", uefa.optString("updatedAt"))
                    } catch (_: Exception) {
                        // Keep the valid domestic feed if UEFA data is malformed.
                    }
                }
                // Build the AI candidate snapshot off the main thread before checking notifications.
                val aiMatchCandidates = collectAiCandidates(json)
                updateAiSignalArchive(json, aiMatchCandidates)
                val aiCandidates = aiMatchCandidates.map { item ->
                    val match = item.first
                    val matchId = match.optString("id").ifBlank {
                        listOf(match.optString("utcDate"), match.optString("home"), match.optString("away")).joinToString("|")
                    }
                    val title = "${match.optString("home")} — ${match.optString("away")}"
                    matchId to title
                }
                AiMatchNotifications.observe(applicationContext, aiCandidates)
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
                    // Keep report notifications independent from the backend-only observation archive.
                    val currentBets = betPrefs.getString("bets", "[]") ?: "[]"
                    val reportDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val startingBank = settingsPrefs.getString("initial_bankroll", "") ?: ""
                    SectionNotifications.observeAndNotify(this, SectionNotifications.fingerprint(
                        reportDay + "|BANK|" + startingBank + "|BETS|" + currentBets
                    ))
                    // Keep existing paper-bet processing and settlement behavior unchanged.
                    processAutoPaperBets(json)
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

    private var tabRenderGeneration = 0

    private fun animateTabEntry(view: View, direction: Int) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationX = dp(22).toFloat() * direction.coerceIn(-1, 1)
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
            .withLayer()
            .start()
    }

    private fun selectTab(tab: String) {
        if (currentTab == tab) return
        val previousIndex = primaryTabs.indexOf(currentTab)
        val nextIndex = primaryTabs.indexOf(tab)
        val direction = if (previousIndex >= 0 && nextIndex >= 0) {
            if (nextIndex > previousIndex) 1 else -1
        } else 1
        currentTab = tab
        val generation = ++tabRenderGeneration

        // Update navigation immediately; defer screen construction until the next
        // frame, then ease the new content in instead of showing it abruptly.
        renderTabs()
        matchesContainer.animate().cancel()
        matchesContainer.visibility = View.INVISIBLE
        leagueScroll.animate().cancel()
        leagueScroll.visibility = if (tab == "matches") View.VISIBLE else View.GONE
        matchesContainer.postOnAnimation {
            if (generation != tabRenderGeneration || currentTab != tab) {
                if (generation == tabRenderGeneration) matchesContainer.visibility = View.VISIBLE
                return@postOnAnimation
            }
            render()
            animateTabEntry(matchesContainer, direction)
            if (tab == "matches") animateTabEntry(leagueScroll, direction)
        }

        // Navigation uses the latest in-memory feed. Network refresh stays on its
        // independent refresh schedule instead of starting on every tab change.
        if (data == null && (tab == "desk" || tab == "stats")) loadData()
    }

    private fun selectAdjacentTab(direction: Int) {
        val index = primaryTabs.indexOf(currentTab)
        if (index < 0) return
        val nextIndex = (index + direction).coerceIn(0, primaryTabs.lastIndex)
        if (nextIndex != index) selectTab(primaryTabs[nextIndex])
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
                    setOnClickListener { selectTab(item.first) }
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

    private data class StatsBandRow(val label: String, val n: Int, val hits: Int)
    private data class StatsMetricRow(
        val name: String, val n: Int, val hits: Int, val brier: Double,
        val logLoss: Double, val bands: List<StatsBandRow>
    )
    @Volatile private var statsMetricsCacheKey: String? = null
    @Volatile private var statsMetricsCache: List<StatsMetricRow> = emptyList()
    private val statsCalculationInProgress = AtomicBoolean(false)

    private fun calculateStatsMetrics(json: JSONObject): List<StatsMetricRow> {
        val metrics = mutableListOf<StatsMetricRow>()
        val evaluatedMatches = json.optJSONArray("evaluationMatches")
        fun collect(key: String, title: String, actual: (Int, Int) -> Boolean, excludeDraws: Boolean = false) {
            var n = 0
            var hits = 0
            var brier = 0.0
            var logLoss = 0.0
            val bandCounts = IntArray(4)
            val bandHits = IntArray(4)
            if (evaluatedMatches != null) for (i in 0 until evaluatedMatches.length()) {
                val m = evaluatedMatches.optJSONObject(i) ?: continue
                if (m.optString("status") != "FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) continue
                val p = m.optJSONObject("prediction") ?: continue
                if (!p.has(key) || p.isNull(key)) continue
                val h = m.optInt("homeScore")
                val aw = m.optInt("awayScore")
                if (excludeDraws && h == aw) continue
                val outcome = actual(h, aw)
                val probability = p.optDouble(key).coerceIn(0.000000000001, 0.999999999999)
                val confidence = maxOf(probability, 1.0 - probability)
                val band = when {
                    confidence < 0.6 -> 0
                    confidence < 0.7 -> 1
                    confidence < 0.8 -> 2
                    else -> 3
                }
                val correct = outcome == (probability >= 0.5)
                val y = if (outcome) 1.0 else 0.0
                n++
                if (correct) hits++
                bandCounts[band]++
                if (correct) bandHits[band]++
                brier += (probability - y) * (probability - y)
                logLoss += -(y * kotlin.math.ln(probability) + (1.0 - y) * kotlin.math.ln(1.0 - probability))
            }
            if (n > 0) {
                val labels = listOf("50–59%", "60–69%", "70–79%", "80%+")
                val bands = labels.indices.map { StatsBandRow(labels[it], bandCounts[it], bandHits[it]) }
                metrics.add(StatsMetricRow(title, n, hits, brier / n, logLoss / n, bands))
            }
        }
        collect("1x", "1X · хозяева победят ИЛИ будет ничья", { h, a -> h >= a })
        collect("x2", "X2 · гости победят ИЛИ будет ничья", { h, a -> a >= h })
        collect("homeOver15", "ИТБ 1.5 · хозяева", { h, _ -> h >= 2 })
        collect("awayOver15", "ИТБ 1.5 · гости", { _, a -> a >= 2 })
        collect("over25", "ТБ 2.5 · всего минимум 3 гола", { h, a -> h + a >= 3 })
        collect("bttsYes", "ОЗ — да", { h, a -> h >= 1 && a >= 1 })
        collect("over15", "ТБ 1.5", { h, a -> h + a >= 2 })
        collect("over35", "ТБ 3.5", { h, a -> h + a >= 4 })
        collect("homeOver05", "ИТБ 0.5 · хозяева", { h, _ -> h >= 1 })
        collect("awayOver05", "ИТБ 0.5 · гости", { _, a -> a >= 1 })
        collect("homeOver25", "ИТБ 2.5 · хозяева", { h, _ -> h >= 3 })
        collect("awayOver25", "ИТБ 2.5 · гости", { _, a -> a >= 3 })
        collect("homeOver35", "ИТБ 3.5 · хозяева", { h, _ -> h >= 4 })
        collect("awayOver35", "ИТБ 3.5 · гости", { _, a -> a >= 4 })
        return metrics
    }

    private fun statsCacheKey(json: JSONObject): String {
        // A cheap feed signature avoids serializing the entire match archive on the UI thread.
        return json.optString("updatedAt") + "|" + json.optString("uefaEvaluationUpdatedAt") + "|" +
            (json.optJSONArray("evaluationMatches")?.length() ?: 0)
    }

    private fun renderStats(json: JSONObject) {
        screenHeader("PERFORMANCE CENTER", "Статистика стратегии", "Точность рынков действующей стратегии. Уровни уверенности сохранены без изменений.")
        val statsKey = statsCacheKey(json)
        if (statsMetricsCacheKey != statsKey) {
            val loading = card()
            addText(loading, "Подготавливаем статистику…", 13, true, Color.WHITE)
            addText(loading, "Расчёт выполняется в фоне. Интерфейс остаётся доступным.", 11, false, muted)
            if (statsCalculationInProgress.compareAndSet(false, true)) {
                // The feed object is replaced, not mutated, on refresh; read it on the worker.
                thread(name = "stats-metrics-calculation") {
                    val calculated = calculateStatsMetrics(json)
                    runOnUiThread {
                        statsMetricsCache = calculated
                        statsMetricsCacheKey = statsKey
                        statsCalculationInProgress.set(false)
                        if (currentTab == "stats") {
                            // Rebuild even when this result became stale while calculating;
                            // renderStats will immediately schedule a calculation for the new feed.
                            tabViewCache.remove("stats|ALL")
                            render()
                        }
                    }
                }
            }
            return
        }
        val metrics = statsMetricsCache
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
        addText(strategyIntro, "IQ MATCH STRATEGY · VERSION 1.2", 10, true, accent)
        addText(strategyIntro, "СТРОГИЙ ОТБОР · УМЕРЕННЫЙ РИСК · ОГРАНИЧЕННЫЙ ЗАПУСК", 13, true, Color.WHITE)
        addText(strategyIntro, "Ставка допускается только после проверки данных, вероятности, value и риска. Если хотя бы один обязательный фильтр не пройден — WAIT или REJECT. Отсутствие ставки является полноценным решением.", 11, false, muted)

        val strategyFilters = card()
        addText(strategyFilters, "1. ФИЛЬТРЫ ДОПУСКА", 11, true, accent)
        addText(strategyFilters, "Данные: матч сопоставлен однозначно; котировка только MelBet через API-Sport; реальная цена и точное совпадение рынка, стороны и линии обязательны. Для автофиксации бумажной ставки котировка должна обновиться в последние 12 часов до матча; жёсткого фильтра возраста 5 минут нет.", 11, false, Color.WHITE)
        addText(strategyFilters, "Сигнал A: вероятность модели ≥ 60%; преимущество над break-even ≥ 5 п.п.; EV ≥ +5%; модель проверена на данном типе рынка; сигнал устойчив к небольшим изменениям предположений.", 11, false, Color.WHITE)
        addText(strategyFilters, "B = WAIT: интересный сигнал, но не хватает подтверждений или данных. C = REJECT: отрицательное/недостаточное value, плохие данные или неприемлемый риск.", 11, false, muted)

        val strategyValue = card()
        addText(strategyValue, "2. VALUE И EV", 11, true, accent)
        addText(strategyValue, "Break-even probability = 1 / коэффициент. EV = вероятность модели × коэффициент − 1. Для BET стартовый минимум EV — +5%, а преимущество над break-even — не менее 5 процентных пунктов. Высокая вероятность сама по себе не означает выгодную ставку.", 11, false, Color.WHITE)
        addText(strategyValue, "Пример: вероятность 60%, коэффициент 1.90 → break-even 52.63%, EV +14%. Пример иллюстративный; калибровка модели и фактическая цена обязательны.", 10, false, muted)

        val strategyRisk = card()
        addText(strategyRisk, "3. БАНК И РИСК", 11, true, accent)
        addText(strategyRisk, "Размер ставки — 1% текущего банка, но не менее установленного пользователем минимума. По умолчанию минимум не задан: тогда ставка равна ровно 1% банка. Дневного лимита суммарной нагрузки нет; разные рынки одного матча разрешены, повтор одной и той же ставки на тот же рынок блокируется.", 11, false, Color.WHITE)
        addText(strategyRisk, "Запрещены догон, увеличение ставки после выигрыша и автоматический рост риска. Полный Kelly не используется. Просадка 10% от локального максимума — пауза автоматической фиксации paper bets; 20% — остановка автоматической фиксации до явного пересмотра.", 11, false, muted)

        val strategyMarkets = card()
        addText(strategyMarkets, "4. РЫНКИ И ИСТОЧНИК", 11, true, accent)
        addText(strategyMarkets, "Только MelBet через API-Sport. Рабочие рынки: Double Chance 1X/X2, Match Total OVER, Team Total OVER и BTTS YES — только при точной загруженной цене и прохождении фильтров AI. BTTS NO, UNDER, 12, 1X2 без подтверждённой цены и форы отображаются только как наблюдаемые рынки и не создают paper-ставки.", 11, false, Color.WHITE)
        addText(strategyMarkets, "Нельзя подставлять коэффициенты другого букмекера, угадывать отсутствующую цену или сравнивать разные линии.", 10, false, muted)

        val strategyTesting = card()
        addText(strategyTesting, "5. ТЕСТИРОВАНИЕ И ДОПУСК К ЗАПУСКУ", 11, true, accent)
        addText(strategyTesting, "Этап 1 — аудит данных и сопоставления. Этап 2 — хронологический бэктест без утечки будущих данных, с отдельными train/validation/test периодами. Этап 3 — paper trading на актуальных MelBet котировках без реальных ставок. Этап 4 — ограниченный запуск с базовой ставкой 1% банка, без дневного лимита суммарной нагрузки. Этап 5 — регулярный аудит и пересмотр.", 11, false, Color.WHITE)
        addText(strategyTesting, "Стартовые ориентиры: ≥500 исторических сигналов и ≥500 paper-сигналов; желательно ≥150–200 наблюдений на оцениваемый рынок. Эти объёмы не гарантируют статистическую надёжность: показывать доверительные интервалы и учитывать неопределённость.", 11, false, muted)

        val strategyMetrics = card()
        addText(strategyMetrics, "6. ЧТО КОНТРОЛИРОВАТЬ В STATS", 11, true, accent)
        addText(strategyMetrics, "ROI, Yield, CLV, максимальная просадка, Brier Score, Log Loss, калибровка вероятностей, win rate, EV по каждому рынку и причины REJECT/WAIT. Win rate не является главным критерием. Сохранять все сигналы, включая отклонённые, коэффициент на момент решения и версию стратегии.", 11, false, Color.WHITE)
        addText(strategyMetrics, "Пороговые значения — стартовая конфигурация для проверки, а не доказанное преимущество и не гарантия прибыли. Изменения правил должны получать новую версию и проверяться на независимых данных.", 10, false, muted)
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

    // Lock a paper bet as soon as the visible AI candidate qualifies.
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
        // Automatic paper-bet creation is disabled. Keep settlement of existing bets above.
        if (changed) saveBets()
        return emptyList()
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

    private fun compactHistoryButton(parent: LinearLayout, title: String, destructive: Boolean = false, action: () -> Unit) {
        val tint = if (destructive) Color.rgb(218, 111, 119) else Color.rgb(150, 190, 205)
        val button = Button(this).apply {
            text = title
            textSize = 11f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(tint)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(22, 34, 49))
                setStroke(dp(1), if (destructive) Color.rgb(105, 55, 66) else Color.rgb(55, 79, 96))
            }
            elevation = 0f
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            marginEnd = dp(6)
        })
    }

    private fun isAiBetRecorded(match: JSONObject, signal: JSONObject): Boolean {
        val marketKey = marketKeyForAutoSignal(signal, match) ?: return false
        val matchId = match.optString("id").ifBlank {
            match.optString("utcDate") + match.optString("home") + match.optString("away")
        }
        return (0 until bets.length()).any { index ->
            val bet = bets.optJSONObject(index) ?: return@any false
            bet.optString("matchId") == matchId &&
                bet.optString("marketKey") == marketKey &&
                bet.optString("source") in listOf("AI_AUTO", "AI_MANUAL")
        }
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
        screenHeader("BET TRACKER", "История", "Открытые и завершённые бумажные ставки.")
        val bank = currentBankroll()
        val bankrollCard = card()
        addText(bankrollCard, "ТЕКУЩИЙ БАЛАНС", 10, true, cyan)
        addText(bankrollCard, if (hasInitialBankroll()) String.format(Locale.US, "%.2f ₽", bank) else "Не задан", 28, true, Color.WHITE)
        addText(bankrollCard, if (hasInitialBankroll()) "Баланс учитывает стартовый банк, чистый результат рассчитанных ставок и суммы, зарезервированные по ожидающим ставкам." else "Задай стартовый банк в профиле, чтобы рассчитывать размер ставок и текущий баланс.", 11, false, muted)
        addText(bankrollCard, "Правило ставки: " + minimumStakeDescription(), 12, true, accent)

        val strategyCard = card()
        addText(strategyCard, "ПРАВИЛО СТАВКИ", 11, true, accent)
        addText(strategyCard, "Размер каждой ставки: " + minimumStakeDescription(), 12, true, Color.WHITE)
        var changed = false
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("marketKey") == "COUPON") {
                val before = bet.optString("status")
                val beforeScores = bet.optString("couponScores")
                val beforeMatchStatus = bet.optString("matchStatus")
                settleCoupon(j, bet)
                if (bet.optString("status") != before || bet.optString("couponScores") != beforeScores ||
                    bet.optString("matchStatus") != beforeMatchStatus) changed = true
                continue
            }
            val match = findMatch(j, bet)
            if (match != null) {
                val matchStatus = match.optString("status")
                val score = if (!match.isNull("homeScore") && !match.isNull("awayScore"))
                    match.optInt("homeScore").toString() + ":" + match.optInt("awayScore") else ""
                if (score.isNotEmpty() && bet.optString("score") != score) { bet.put("score", score); changed = true }
                if (matchStatus.isNotEmpty() && bet.optString("matchStatus") != matchStatus) {
                    bet.put("matchStatus", matchStatus); changed = true
                }
                if (bet.optString("status") == "PENDING") {
                    val before = bet.optString("status")
                    settleBet(bet, match)
                    if (bet.optString("status") != before) changed = true
                }
            }
        }
        if (changed) saveBets()

        var stakeAll = 0.0
        var turnover = 0.0
        var profit = 0.0
        var payout = 0.0
        var possiblePayout = 0.0
        var possibleProfit = 0.0
        var wins = 0
        var losses = 0
        var pushes = 0
        var pending = 0
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val stake = bet.optDouble("stake")
            val odds = bet.optDouble("odds")
            stakeAll += stake
            when (bet.optString("status")) {
                "WON" -> { wins++; turnover += stake; profit += stake * (odds - 1); payout += stake * odds }
                "LOST" -> { losses++; turnover += stake; profit -= stake }
                "PUSH" -> { pushes++; turnover += stake; payout += stake }
                else -> { pending++; possiblePayout += stake * odds; possibleProfit += stake * (odds - 1.0) }
            }
        }
        val aiHistoryCard = card()
        addText(aiHistoryCard, "AI · АВТОИСТОРИЯ РЕКОМЕНДАЦИЙ", 10, true, cyan)
        addText(aiHistoryCard, "Каждый прошедший AI сильнейший сигнал с котировкой MelBet фиксируется по первой показанной цене и рассчитывается по финальному счёту. ROI рассчитан при 1 условной единице на сигнал. История локальная для этого устройства.", 9, false, muted)
        val aiHistory = try {
            JSONArray(aiSignalPrefs.getString("signals", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val aiLeagueNames = linkedMapOf(
            "PL" to "Англия · Премьер-лига",
            "PD" to "Испания · Ла Лига",
            "SA" to "Италия · Серия A",
            "BL1" to "Германия · Бундеслига",
            "FL1" to "Франция · Лига 1",
            "UCL" to "Лига чемпионов УЕФА",
            "UEL" to "Лига Европы УЕФА",
            "UECL" to "Лига конференций УЕФА",
        )
        val aiRowsByLeague = linkedMapOf<String, MutableList<JSONObject>>()
        aiLeagueNames.forEach { (code, _) -> aiRowsByLeague[code] = mutableListOf() }
        for (i in 0 until aiHistory.length()) {
            val row = aiHistory.optJSONObject(i) ?: continue
            val code = row.optString("leagueCode")
            aiRowsByLeague.getOrPut(code.ifBlank { row.optString("league") }) { mutableListOf() }.add(row)
        }
        aiRowsByLeague.forEach { (code, rows) ->
            val wins = rows.count { it.optString("status") == "WON" }
            val losses = rows.count { it.optString("status") == "LOST" }
            val pendingSignals = rows.count { it.optString("status") == "PENDING" }
            val settledSignals = wins + losses
            val profitUnits = rows.filter { it.optString("status") in listOf("WON", "LOST") }
                .sumOf { it.optDouble("profitUnits", 0.0) }
            val roi = if (settledSignals > 0) String.format(Locale.US, "%.1f%%", profitUnits / settledSignals * 100.0) else "—"
            val name = aiLeagueNames[code] ?: code
            addText(aiHistoryCard, "$name · сигналов ${rows.size} · W $wins / L $losses / ждут $pendingSignals · ROI $roi", 10, false, muted)
        }
        if (aiHistory.length() == 0) addText(aiHistoryCard, "Пока нет сохранённых AI-рекомендаций. Новые сигналы будут фиксироваться автоматически.", 10, false, muted)

        val sum = card()
        addText(sum, "СТАТИСТИКА СОХРАНЁННЫХ СТАВОК", 10, true, accent)
        addText(sum, "Всего: " + bets.length() + " · выигрыш: " + wins + " · проигрыш: " + losses + " · возврат: " + pushes + " · открытые: " + pending, 12, true)
        addText(sum, "Сумма ставок: " + fmt(stakeAll), 13, true)
        addText(sum, "Возможная выплата по открытым: " + fmt(possiblePayout) + " · чистый выигрыш: +" + fmt(possibleProfit), 12, true, accent)
        addText(sum, "Выплаты: " + fmt(payout) + " · чистый результат: " + (if (profit >= 0) "+" else "") + fmt(profit), 12, true, if (profit >= 0) accent else Color.RED)
        addText(sum, "ROI: " + (if (turnover > 0) String.format(Locale.getDefault(), "%.1f%%", profit / turnover * 100) else "—") + " · рассчитанный оборот: " + fmt(turnover), 12, true)
        addText(sum, "ОБЩИЙ ROI · все бумажные ставки", 10, true, cyan)
        addText(sum, "ROI = чистый результат / рассчитанный оборот. Возвраты и открытые ставки не входят.", 10, false, muted)
        addText(sum, "ПОЛЬЗОВАТЕЛЬСКИЕ СТАВКИ · ПО ТУРНИРАМ", 10, true, cyan)
        addText(sum, "Только ставки, которые пользователь сохранил отдельно; автоматическая история AI-рекомендаций показана выше.", 9, false, muted)
        val betsByLeague = linkedMapOf<String, MutableList<JSONObject>>()
        listOf(
            "Англия · Премьер-лига", "Испания · Ла Лига", "Италия · Серия A",
            "Германия · Бундеслига", "Франция · Лига 1",
            "Лига чемпионов УЕФА", "Лига Европы УЕФА", "Лига конференций УЕФА",
        ).forEach { betsByLeague[it] = mutableListOf() }
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val league = bet.optString("league").ifBlank { "Турнир не указан" }
            betsByLeague.getOrPut(league) { mutableListOf() }.add(bet)
        }
        betsByLeague.toSortedMap().forEach { (league, rows) ->
            var leagueWins = 0
            var leagueLosses = 0
            var leaguePushes = 0
            var leagueTurnover = 0.0
            var leagueProfit = 0.0
            rows.forEach { bet ->
                val stake = bet.optDouble("stake")
                val odds = bet.optDouble("odds")
                when (bet.optString("status")) {
                    "WON" -> { leagueWins++; leagueTurnover += stake; leagueProfit += stake * (odds - 1.0) }
                    "LOST" -> { leagueLosses++; leagueTurnover += stake; leagueProfit -= stake }
                    "PUSH" -> { leaguePushes++; leagueTurnover += stake }
                }
            }
            val settledCount = leagueWins + leagueLosses + leaguePushes
            val roi = if (leagueTurnover > 0.0) String.format(Locale.US, "%.1f%%", leagueProfit / leagueTurnover * 100.0) else "—"
            addText(sum, "$league · ${rows.size} ставок · W $leagueWins / L $leagueLosses / возврат $leaguePushes · ROI $roi", 10, false, muted)
            if (settledCount == 0) addText(sum, "Пока нет рассчитанных ставок", 9, false, muted)
        }

        if (bets.length() == 0) {
            val empty = card()
            addText(empty, "Записанных ставок пока нет.", 12, false, muted)
            return
        }

        val openBets = mutableListOf<JSONObject>()
        val completedBets = mutableListOf<JSONObject>()
        for (i in bets.length() - 1 downTo 0) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("status") == "PENDING") openBets.add(bet) else completedBets.add(bet)
        }

        fun renderBetSection(title: String, items: List<JSONObject>, emptyText: String) {
            val section = card()
            addText(section, title + " · " + items.size, 12, true, if (title == "ОТКРЫТЫЕ СТАВКИ") accent else cyan)
            if (items.isEmpty()) {
                addText(section, emptyText, 11, false, muted)
                return
            }
            items.forEach { bet ->
                val box = card()
                val state = bet.optString("status")
                val status = when (state) {
                    "WON" -> "ВЫИГРЫШ"
                    "LOST" -> "ПРОИГРЫШ"
                    "PUSH" -> "ВОЗВРАТ"
                    else -> "ОЖИДАЕТ РАСЧЁТА"
                }
                val color = when (state) {
                    "WON" -> accent
                    "LOST" -> Color.RED
                    else -> Color.YELLOW
                }
                addText(box, bet.optString("league") + " · " + date(bet.optString("date")), 10, false, muted)
                addText(box, if (bet.optString("marketKey") == "COUPON")
                    "Экспресс · " + (bet.optJSONArray("selections")?.length() ?: 0) + " исходов"
                    else bet.optString("home") + " — " + bet.optString("away"), 15, true)
                addText(box, bet.optString("market") + " · кэф " + fmt(bet.optDouble("odds")), 12, true, accent)
                if (bet.has("couponScores")) addText(box, bet.optString("couponScores"), 10, false, muted)
                if (bet.optString("marketKey") == "COUPON") {
                    addText(box, "Сумма купона: " + fmt(bet.optDouble("stake")), 11, false, muted)
                    val legs = bet.optJSONArray("selections")
                    for (legIndex in 0 until (legs?.length() ?: 0)) {
                        val leg = legs?.optJSONObject(legIndex) ?: continue
                        addText(box, (legIndex + 1).toString() + ". " + leg.optString("home") + " — " +
                            leg.optString("away") + " · " + leg.optString("market") + " · " + fmt(leg.optDouble("odds")), 10, false, muted)
                    }
                } else {
                    addText(box, "Ставка: " + fmt(bet.optDouble("stake")) + " · модель: " +
                        String.format(Locale.getDefault(), "%.1f%%", bet.optDouble("probability") * 100), 11, false, muted)
                }
                val liveStatus = when (bet.optString("matchStatus")) {
                    "IN_PLAY", "LIVE", "PAUSED" -> " · LIVE"
                    "FINISHED" -> " · ФИНАЛ"
                    "SCHEDULED", "TIMED" -> " · ЕЩЁ НЕ НАЧАЛСЯ"
                    else -> ""
                }
                addText(box, status + liveStatus + (if (bet.has("score")) " · счёт " + bet.optString("score") else ""), 12, true, color)
                val stake = bet.optDouble("stake")
                val odds = bet.optDouble("odds")
                if (state == "PENDING") {
                    val possible = stake * odds
                    addText(box, "Возможная выплата: " + fmt(possible) + " ₽ · чистый выигрыш: +" + fmt(possible - stake) + " ₽", 12, true, accent)
                }
                if (state == "WON") addText(box, "Чистая прибыль: +" + fmt(stake * (odds - 1)), 12, true, accent)
                if (state == "LOST") addText(box, "Убыток: −" + fmt(stake), 12, true, color)
                if (state == "PUSH") addText(box, "Возврат: " + fmt(stake), 12, true, accent)
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (state == "PENDING") compactHistoryButton(actions, "Кэф") { showEditBetOddsDialog(bet) }
                compactHistoryButton(actions, "Удалить", destructive = true) {
                    val id = bet.optString("id")
                    val next = JSONArray()
                    for (k in 0 until bets.length()) {
                        val item = bets.optJSONObject(k) ?: continue
                        if (item.optString("id") != id) next.put(item)
                    }
                    bets = next
                    saveBets(preserveBackgroundAuto = false)
                    render()
                }
                box.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }

        renderBetSection("ОТКРЫТЫЕ СТАВКИ", openBets, "Открытых ставок пока нет.")
        renderBetSection("ЗАВЕРШЁННЫЕ", completedBets, "Завершённых ставок пока нет.")
    }

    private fun renderHelp() {
        screenHeader("IQ MATCH · PROFILE", "Профиль", "Настройки банка и состояние фоновых обновлений.")
        val profile = card()
        addText(profile, "IQ MATCH", 18, true, Color.WHITE)
        addText(profile, "Локальных записей ставок: ${bets.length()} · текущий банк: ${if (hasInitialBankroll()) fmt(currentBankroll()) + " ₽" else "не задан"}", 12, true, accent)
        addText(profile, "Минимальная сумма ставки: ${if (minimumStake() > 0.0) fmt(minimumStake()) + " ₽" else "не задана"}", 12, true, Color.WHITE)
        addText(profile, "Правило: 1% от текущего баланса; пользовательский минимум можно задать отдельно.", 11, false, muted)
        actionButton(profile, "Изменить минимальную сумму ставки") { showBankSettingDialog("minimum_stake", "Минимальная сумма ставки", minimumStake(), "Укажи минимум ставки. Введи 0, чтобы снять минимум; если минимум не задан, ставка равна 1% текущего баланса.") }
        addText(profile, "Стартовый банк: ${if (hasInitialBankroll()) fmt(initialBankroll()) + " ₽" else "не задан"}", 12, false, muted)
        actionButton(profile, if (hasInitialBankroll()) "Изменить стартовый банк" else "Задать стартовый банк") { showBankSettingDialog("initial_bankroll", "Стартовый банк", initialBankroll(), "Укажи стартовый банк. Пока он не задан, новые AI paper bets автоматически не фиксируются. Записанные ставки и результаты сохраняются.") }

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

    // Only show a strongest signal when its market is actually listed in the match card.
    // Under markets are intentionally excluded; team totals shown in the card are OVER (ИТБ) only.
    private fun strongestSignalDisplayLabel(signal: JSONObject, match: JSONObject): String? {
        val side = signal.optString("side").trim().replace(Regex("\\s+"), " ")
        if (side.isBlank()) return null
        if (side.contains("ИТМ", ignoreCase = true) || side.startsWith("ТМ ", ignoreCase = true) ||
            side.contains("UNDER", ignoreCase = true) || side.equals("Не X2", ignoreCase = true)) return null
        if (side.equals("1X", ignoreCase = true) || side.equals("X2", ignoreCase = true) ||
            side.equals("ОЗ ДА", ignoreCase = true)) return side
        if (listOf("ТБ 1.5", "ТБ 2.5", "ТБ 3.5").any { side.equals(it, ignoreCase = true) }) return side
        if (side.startsWith("ИТБ ", ignoreCase = true)) {
            val point = Regex("ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(side)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val team = side.substringAfter("·", "").trim().replace(Regex("\\s+"), " ")
            val home = match.optString("home").trim().replace(Regex("\\s+"), " ")
            val away = match.optString("away").trim().replace(Regex("\\s+"), " ")
            if (point in listOf(0.5, 1.5, 2.5, 3.5) &&
                (team.equals(home, ignoreCase = true) || team.equals(away, ignoreCase = true))) return side
        }
        return null
    }

    // Choose the strongest model forecast with an exact MelBet quote in this match.
    // This is descriptive match-line ranking, not the stricter AI betting filter.
    private fun strongestDisplaySignal(prediction: JSONObject, match: JSONObject): JSONObject? {
        val candidates = mutableListOf<JSONObject>()
        prediction.optJSONObject("strongestSignal")?.let { candidates.add(it) }
        val signals = prediction.optJSONArray("signals")
        if (signals != null) {
            for (index in 0 until signals.length()) {
                signals.optJSONObject(index)?.let { candidate ->
                    val candidateKey = candidate.optString("side").trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
                    if (candidates.none {
                            it.optString("side").trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) == candidateKey
                        }) {
                        candidates.add(candidate)
                    }
                }
            }
        }

        // UEFA feeds publish the complete model probabilities but do not have the
        // domestic empirical-baseline signal objects. Build equivalent per-market
        // candidates from those probabilities so the match card still ranks all
        // supported markets instead of depending on paperSignals.
        val rawMarkets = listOf(
            "1x" to "1X",
            "x2" to "X2",
            "bttsYes" to "ОЗ ДА",
            "over15" to "ТБ 1.5",
            "over25" to "ТБ 2.5",
            "over35" to "ТБ 3.5",
            "homeOver05" to "ИТБ 0.5 · ${match.optString("home")}",
            "homeOver15" to "ИТБ 1.5 · ${match.optString("home")}",
            "homeOver25" to "ИТБ 2.5 · ${match.optString("home")}",
            "homeOver35" to "ИТБ 3.5 · ${match.optString("home")}",
            "awayOver05" to "ИТБ 0.5 · ${match.optString("away")}",
            "awayOver15" to "ИТБ 1.5 · ${match.optString("away")}",
            "awayOver25" to "ИТБ 2.5 · ${match.optString("away")}",
            "awayOver35" to "ИТБ 3.5 · ${match.optString("away")}"
        )
        rawMarkets.forEach { (field, side) ->
            val probability = prediction.optDouble(field, Double.NaN)
            if (!probability.isFinite() || probability !in 0.0..1.0) return@forEach
            val exists = candidates.any {
                it.optString("side").trim().replace(Regex("\\s+"), " ")
                    .equals(side.trim().replace(Regex("\\s+"), " "), ignoreCase = true)
            }
            if (!exists) {
                candidates.add(JSONObject().put("side", side).put("modelProbability", probability))
            }
        }

        // Legacy UEFA paper-only entries are retained only when no full model
        // probability candidate could be built.
        if (candidates.isEmpty() && match.optJSONArray("paperSignals") != null) {
            uefaPaperSignal(match)?.let { candidates.add(it) }
        }

        // The Line card optimizes probability and price together. Only exact
        // MelBet quotes >= 1.50 qualify; positive-EV candidates are ranked by
        // full-Kelly fraction, calculated from model probability and exact MelBet price.
        val available = candidates.mapNotNull { candidate ->
            if (strongestSignalDisplayLabel(candidate, match) == null) return@mapNotNull null
            val probability = candidate.optDouble("modelProbability", Double.NaN)
            val odds = topSignalOdds(candidate, match, minOdds = 1.50)
            if (!probability.isFinite() || probability !in 0.0..1.0 || odds == null) return@mapNotNull null
            val expectedValue = probability * odds - 1.0
            val kellyFraction = if (odds > 1.0) expectedValue / (odds - 1.0) else Double.NEGATIVE_INFINITY
            candidate.put("lineOdds", odds)
            candidate.put("lineExpectedValue", expectedValue)
            candidate.put("lineKellyFraction", kellyFraction)
            candidate
        }
        val positiveValue = available.filter { it.optDouble("lineExpectedValue", -1.0) > 0.0 }
        return if (positiveValue.isNotEmpty()) {
            positiveValue.maxByOrNull { it.optDouble("lineKellyFraction", Double.NEGATIVE_INFINITY) }
        } else {
            // Do not disguise a negative-EV quote as value: retain the most
            // probable qualifying market, with its negative EV shown explicitly.
            available.maxByOrNull { it.optDouble("modelProbability", Double.NEGATIVE_INFINITY) }
        }
    }

    private fun topSignalOdds(signal: JSONObject, match: JSONObject, minOdds: Double = 1.50): Double? {
        val side = signal.optString("side").trim()
        val oddsBoard = match.optJSONObject("odds") ?: return null
        val bookmaker = oddsBoard.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true) else oddsBoard.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet) return null
        val best = oddsBoard.optJSONObject("best") ?: return null
        fun acceptable(value: Double) = value.isFinite() && value >= minOdds
        if (side.equals("1X", ignoreCase = true) || side.equals("X2", ignoreCase = true)) {
            val dc = best.optJSONObject("doubleChance") ?: return null
            return dc.optDouble(if (side.equals("1X", ignoreCase = true)) "1x" else "x2", Double.NaN).takeIf { acceptable(it) }
        }
        if (side.startsWith("ИТБ ", ignoreCase = true)) {
            val point = Regex("ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(side)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val teamName = side.substringAfter("·", "").trim().replace(Regex("\\s+"), " ")
            val homeName = match.optString("home").trim().replace(Regex("\\s+"), " ")
            val awayName = match.optString("away").trim().replace(Regex("\\s+"), " ")
            val team = when {
                teamName.equals(homeName, ignoreCase = true) -> "home"
                teamName.equals(awayName, ignoreCase = true) -> "away"
                else -> return null
            }
            val rows = best.optJSONArray("teamTotals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val rowTeam = row.optString("team").trim()
                val rowTeamSide = when {
                    rowTeam.equals("home", ignoreCase = true) || rowTeam == "1" ||
                        rowTeam.equals("хозяева", ignoreCase = true) || rowTeam.equals(homeName, ignoreCase = true) -> "home"
                    rowTeam.equals("away", ignoreCase = true) || rowTeam == "2" ||
                        rowTeam.equals("гости", ignoreCase = true) || rowTeam.equals(awayName, ignoreCase = true) -> "away"
                    else -> ""
                }
                val rowSide = row.optString("side").trim().lowercase(Locale.ROOT)
                val line = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (rowTeamSide == team && rowSide == "over" && line.isFinite() &&
                    kotlin.math.abs(line - point) < 0.001 && acceptable(price)) return price
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

    // Main-line eligibility is the intersection of a complete model forecast and
    // an available MelBet quote from API-Sport; quote age is informational only.
    private fun hasCompleteModelPrediction(match: JSONObject): Boolean {
        val prediction = match.optJSONObject("prediction") ?: return false
        val probabilityFields = listOf(
            "1x", "x2", "over15", "over25", "over35",
            "homeOver05", "homeOver15", "homeOver25", "homeOver35",
            "awayOver05", "awayOver15", "awayOver25", "awayOver35",
            "bttsYes", "bttsNo"
        )
        if (!probabilityFields.all { field ->
                val value = prediction.optDouble(field, Double.NaN)
                value.isFinite() && value in 0.0..1.0
            }) return false
        val homeXg = prediction.optDouble("expectedHomeGoals", Double.NaN)
        val awayXg = prediction.optDouble("expectedAwayGoals", Double.NaN)
        return homeXg.isFinite() && homeXg > 0.0 && awayXg.isFinite() && awayXg > 0.0
    }

    private fun hasActiveMelBetPrematchLine(match: JSONObject): Boolean {
        val odds = match.optJSONObject("odds") ?: return false
        val bookmaker = odds.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true) else odds.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet) return false

        val best = odds.optJSONObject("best") ?: return false
        fun validPrice(value: Double): Boolean = value.isFinite() && value > 1.0
        val doubleChance = best.optJSONObject("doubleChance")
        if (doubleChance != null &&
            listOf("1x", "x2").any { validPrice(doubleChance.optDouble(it, Double.NaN)) }) return true

        val btts = best.optJSONObject("btts")
        if (btts != null &&
            listOf("yes", "no").any { validPrice(btts.optDouble(it, Double.NaN)) }) return true

        fun hasOverQuote(field: String): Boolean {
            val rows = best.optJSONArray(field) ?: return false
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("side").equals("over", ignoreCase = true) &&
                    validPrice(row.optDouble("price", Double.NaN))) return true
            }
            return false
        }
        return hasOverQuote("totals") || hasOverQuote("teamTotals")
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

    private fun hasInitialBankroll(): Boolean = settingsPrefs.getString("initial_bankroll", null)
        ?.replace(',', '.')?.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } ?: false

    private fun initialBankroll(): Double = settingsPrefs.getString("initial_bankroll", null)
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private fun targetBankroll(): Double = settingsPrefs.getString("target_bankroll", "100000")
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 100000.0

    private fun minimumStake(): Double = settingsPrefs.getString("minimum_stake", null)
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private fun minimumStakeDescription(): String = if (minimumStake() > 0.0)
        "1% от текущего баланса, но не менее ${fmt(minimumStake())} ₽."
    else "1% от текущего баланса; пользовательский минимум не задан."

    private fun showBankSettingDialog(key: String, title: String, current: Double, message: String) {
        val field = EditText(this).apply {
            hint = "Сумма в рублях"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            if (key != "initial_bankroll" || current > 0.0) setText(String.format(Locale.US, "%.2f", current))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawValue = field.text.toString().trim().replace(',', '.')
                val value = if (rawValue.isEmpty() && key == "initial_bankroll") 0.0 else rawValue.toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0.0) {
                    Toast.makeText(this, "Введи положительную сумму или оставь поле пустым", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (value == 0.0) settingsPrefs.edit().remove(key).apply()
                else settingsPrefs.edit().putString(key, value.toString()).apply()
                // Обновляем все закешированные экраны, зависящие от банка или правила ставки.
                if (key == "initial_bankroll" || key == "minimum_stake") {
                    tabViewCache.clear()
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
        headerBalanceView.text = if (hasInitialBankroll()) String.format(Locale.US, "%.0f ₽", currentBankroll()) else "Баланс —"
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
        addText(summary, "Текущий бумажный банк: ${if (hasInitialBankroll()) String.format(Locale.US, "%.2f ₽", currentBankroll()) else "не задан"}", 12, false, muted)

        val strengths = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        if (dayBets.isEmpty()) strengths.add("Сегодня нет записей ставок по дате матча. Подходящих сигналов могло не быть.")
        else {
            if (wins > losses) strengths.add("По рассчитанным paper-ставкам больше побед, чем поражений ($wins:$losses).")
            if (pending > 0) strengths.add("$pending ставок ещё ожидают расчёта — не считаем их выигрышами или проигрышами.")
            if (losses > wins) improvements.add("Поражений больше, чем побед ($losses против $wins). Проверим рынки и цену, но не будем менять пороги по одному дню.")
            if (profit < 0) improvements.add("Дневной результат отрицательный. Сначала накопим выборку и сравним рынки, не повышая риск.")
        }
        val strengthsCard = card()
        addText(strengthsCard, "ЧТО ХОРОШО", 10, true, accent)
        strengths.forEach { addText(strengthsCard, "• $it", 12, false, Color.WHITE) }
        val improveCard = card()
        addText(improveCard, "ЧТО УЛУЧШИТЬ", 10, true, gold)
        improvements.forEach { addText(improveCard, "• $it", 12, false, Color.WHITE) }

        val recommendations = card()
        addText(recommendations, "МОИ ВЫВОДЫ", 10, true, cyan)
        addText(recommendations, "1. Оценивать рабочие ставки только по завершённым матчам с достаточным объёмом данных.", 12, false, Color.WHITE)
        addText(recommendations, "2. Не менять пороги вероятности, edge, EV и риск-менеджмент автоматически. Любые изменения — только после отдельной проверки и согласования.", 12, false, muted)
    }

    private fun confirmManualAiSignalBet(match: JSONObject, league: String, signal: JSONObject, odds: Double) {
        val marketKey = marketKeyForAutoSignal(signal, match)
        if (marketKey.isNullOrBlank()) {
            Toast.makeText(this, "Не удалось определить рынок сигнала", Toast.LENGTH_SHORT).show()
            return
        }
        val matchId = match.optString("id").ifBlank { match.optString("utcDate") + match.optString("home") + match.optString("away") }
        val id = "AI_MANUAL:$matchId:$marketKey"
        if ((0 until bets.length()).any { bets.optJSONObject(it)?.optString("id") == id ||
                (bets.optJSONObject(it)?.optString("matchId") == matchId &&
                 bets.optJSONObject(it)?.optString("marketKey") == marketKey &&
                 bets.optJSONObject(it)?.optString("source") in listOf("AI_AUTO", "AI_MANUAL")) }) {
            Toast.makeText(this, "Ставка на этот рынок уже записана", Toast.LENGTH_SHORT).show()
            return
        }
        val probability = signal.optDouble("modelProbability", 0.0)
        val edge = if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", 0.0)
            else (probability - 1.0 / odds) * 100.0 + signal.optDouble("formAdjustmentPp", 0.0)
        val ev = probability * odds - 1.0
        if (!hasInitialBankroll()) {
            Toast.makeText(this, "Сначала задай стартовый банк в профиле", Toast.LENGTH_LONG).show()
            return
        }
        val stake = maxOf(currentBankroll() * 0.01, minimumStake())
        if (stake <= 0.0) return
        AlertDialog.Builder(this)
            .setTitle("Зафиксировать AI-ставку?")
            .setMessage("Матч: ${match.optString("home")} — ${match.optString("away")}\nРынок: ${signal.optString("side")}\nКоэффициент MelBet: ${fmt(odds)}\nСумма ставки: ${fmt(stake)} ₽\n\nБумажная ставка — запись в истории приложения. Букмекеру ничего не отправляется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Зафиксировать") { _, _ ->
                val now = System.currentTimeMillis()
                val bet = JSONObject()
                    .put("id", id).put("source", "AI_MANUAL").put("status", "PENDING")
                    .put("matchId", matchId).put("league", league)
                    .put("date", match.optString("utcDate"))
                    .put("betDay", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now)))
                    .put("home", match.optString("home")).put("away", match.optString("away"))
                    .put("market", signal.optString("side")).put("marketKey", marketKey)
                    .put("odds", odds).put("stake", stake).put("probability", probability)
                    .put("edgePp", edge).put("expectedValue", ev)
                    .put("quoteUpdatedAt", match.optJSONObject("odds")?.optString("updatedAt", ""))
                    .put("createdAt", now).put("matchStatus", match.optString("status")).put("score", "")
                bets.put(bet)
                saveBets()
                Toast.makeText(this, "AI-ставка добавлена в историю", Toast.LENGTH_SHORT).show()
                render()
            }.show()
    }

    private fun collectAiCandidates(json: JSONObject): List<Triple<JSONObject, String, JSONObject>> {
        val candidates = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions") ?: return emptyList()
        for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                if (match.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                if (!hasActiveMelBetPrematchLine(match)) continue
                val matchDay = match.optString("utcDate").take(10).ifBlank {
                    match.optString("dateEvent").take(10).ifBlank {
                        kickoffTimestamp(match)?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date(it)) }.orEmpty()
                    }
                }
                if (matchDay.isBlank()) continue
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date())
                if (matchDay < today) continue
                val prediction = match.optJSONObject("prediction") ?: continue
                // AI consumes exactly the one strongest signal already selected for Line.
                // It must not promote a second-best market when the Line leader fails AI filters.
                val signal = strongestDisplaySignal(prediction, match) ?: continue
                val probability = signal.optDouble("modelProbability", Double.NaN)
                val odds = topSignalOdds(signal, match, minOdds = 1.50) ?: continue
                if (!probability.isFinite() || probability < 0.60) continue
                val expectedValue = probability * odds - 1.0
                val edgePp = if (signal.optBoolean("paperOnly")) {
                    signal.optDouble("edgePp", Double.NaN)
                } else {
                    (probability - 1.0 / odds) * 100.0 + signal.optDouble("formAdjustmentPp", 0.0)
                }
                if (signal.optBoolean("noPositiveSignal") ||
                    signal.optString("side").equals("ОЗ НЕТ", ignoreCase = true) ||
                    !expectedValue.isFinite() || expectedValue < 0.05 ||
                    !edgePp.isFinite() || edgePp < 5.0) continue
                signal.put("aiExpectedValue", expectedValue)
                signal.put("aiEdgePp", edgePp)
                signal.put("aiKellyFraction", expectedValue / (odds - 1.0))
                val bundle = JSONObject().put("primary", signal).put("alternatives", JSONArray())
                candidates.add(Triple(match, league.optString("name"), bundle))
            }
        }
        return candidates.sortedByDescending { item ->
            item.third.optJSONObject("primary")?.optDouble("aiKellyFraction", Double.NEGATIVE_INFINITY)
                ?: Double.NEGATIVE_INFINITY
        }
    }

    private fun aiSignalOutcome(side: String, match: JSONObject): Boolean? {
        if (match.isNull("homeScore") || match.isNull("awayScore")) return null
        val homeScore = match.optInt("homeScore")
        val awayScore = match.optInt("awayScore")
        return when {
            side.equals("1X", ignoreCase = true) -> homeScore >= awayScore
            side.equals("X2", ignoreCase = true) -> awayScore >= homeScore
            side.equals("ОЗ ДА", ignoreCase = true) -> homeScore > 0 && awayScore > 0
            side.startsWith("ТБ ", ignoreCase = true) -> {
                val line = side.substringAfter("ТБ ").trim().toDoubleOrNull() ?: return null
                homeScore + awayScore > line
            }
            side.startsWith("ИТБ ", ignoreCase = true) -> {
                val line = side.substringAfter("ИТБ ").substringBefore("·").trim().toDoubleOrNull() ?: return null
                val team = side.substringAfter("·", "").trim()
                val teamScore = when {
                    team.equals(match.optString("home"), ignoreCase = true) -> homeScore
                    team.equals(match.optString("away"), ignoreCase = true) -> awayScore
                    else -> return null
                }
                teamScore > line
            }
            else -> null
        }
    }

    private fun updateAiSignalArchive(
        json: JSONObject,
        candidates: List<Triple<JSONObject, String, JSONObject>>
    ) {
        val archive = try {
            JSONArray(aiSignalPrefs.getString("signals", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val known = mutableSetOf<String>()
        for (i in 0 until archive.length()) {
            archive.optJSONObject(i)?.let { known.add(it.optString("signalKey")) }
        }
        val now = System.currentTimeMillis()
        candidates.forEach candidateLoop@ { (match, leagueName, bundle) ->
            val matchId = match.optString("id").ifBlank {
                listOf(match.optString("utcDate"), match.optString("home"), match.optString("away")).joinToString("|")
            }
            val kickoff = kickoffTimestamp(match) ?: return@candidateLoop
            if (kickoff <= now) return@candidateLoop
            val signals = mutableListOf<JSONObject>()
            bundle.optJSONObject("primary")?.let { signals.add(it) }
            val alternatives = bundle.optJSONArray("alternatives")
            if (alternatives != null) for (i in 0 until alternatives.length()) {
                alternatives.optJSONObject(i)?.let { signals.add(it) }
            }
            signals.forEach signalLoop@ { signal ->
                val side = signal.optString("side").trim()
                val price = topSignalOdds(signal, match) ?: return@signalLoop
                val signalKey = "$matchId|$side"
                if (signalKey in known) return@signalLoop
                val leagueCode = json.optJSONArray("competitions")?.let { competitions ->
                    (0 until competitions.length()).firstNotNullOfOrNull { index ->
                        val competition = competitions.optJSONObject(index) ?: return@firstNotNullOfOrNull null
                        val matches = competition.optJSONArray("matches") ?: return@firstNotNullOfOrNull null
                        val found = (0 until matches.length()).any { matches.optJSONObject(it)?.optString("id") == matchId }
                        if (found) competition.optString("code") else null
                    }
                }.orEmpty()
                archive.put(JSONObject()
                    .put("signalKey", signalKey)
                    .put("matchId", matchId)
                    .put("leagueCode", leagueCode)
                    .put("league", leagueName)
                    .put("home", match.optString("home"))
                    .put("away", match.optString("away"))
                    .put("kickoff", match.optString("utcDate"))
                    .put("side", side)
                    .put("market", signal.optString("market"))
                    .put("price", price)
                    .put("modelProbability", signal.optDouble("modelProbability", Double.NaN))
                    .put("edgePp", signal.optDouble("selectionEdgePp", signal.optDouble("edgePp", 0.0)))
                    .put("capturedAt", now)
                    .put("status", "PENDING"))
                known.add(signalKey)
            }
        }

        for (i in 0 until archive.length()) {
            val row = archive.optJSONObject(i) ?: continue
            if (row.optString("status") != "PENDING") continue
            val match = findMatch(json, JSONObject().put("matchId", row.optString("matchId"))) ?: continue
            when (match.optString("status")) {
                "FINISHED" -> {
                    val won = aiSignalOutcome(row.optString("side"), match) ?: continue
                    val price = row.optDouble("price", 1.0)
                    row.put("status", if (won) "WON" else "LOST")
                        .put("homeScore", match.optInt("homeScore"))
                        .put("awayScore", match.optInt("awayScore"))
                        .put("profitUnits", if (won) price - 1.0 else -1.0)
                        .put("settledAt", now)
                }
                "POSTPONED", "CANCELED", "CANCELLED" -> row.put("status", "VOID").put("settledAt", now)
            }
        }
        aiSignalPrefs.edit().putString("signals", archive.toString()).apply()
    }

    private fun renderAiSignalPerformanceSummary() {
        val archive = try {
            JSONArray(aiSignalPrefs.getString("signals", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        var wins = 0
        var losses = 0
        var pending = 0
        var profitUnits = 0.0
        for (i in 0 until archive.length()) {
            val row = archive.optJSONObject(i) ?: continue
            when (row.optString("status")) {
                "WON" -> { wins++; profitUnits += row.optDouble("profitUnits", 0.0) }
                "LOST" -> { losses++; profitUnits += row.optDouble("profitUnits", 0.0) }
                "PENDING" -> pending++
            }
        }
        val settled = wins + losses
        val roi = if (settled > 0) String.format(Locale.US, "%.1f%%", profitUnits / settled * 100.0) else "—"
        val summary = card()
        addText(summary, "AI · РЕЗУЛЬТАТ РЕКОМЕНДАЦИЙ", 10, true, cyan)
        addText(summary, "Зафиксировано: ${archive.length()} · W $wins / L $losses · ожидают: $pending", 12, true, Color.WHITE)
        addText(summary, "ROI: $roi · расчёт по 1 условной единице на каждый сигнал", 10, false, muted)
        if (archive.length() == 0) addText(summary, "История начнёт накапливаться после появления первых сигналов с котировкой MelBet.", 10, false, muted)
    }

    private fun renderBettingDesk(json: JSONObject) {
        screenHeader("AI · MATCH INTELLIGENCE", "AI-аналитика и сигналы", "AI проверяет только сильнейший сигнал каждого матча из раздела Линия: цена MelBet, вероятность, edge и EV.")
        renderAiSignalPerformanceSummary()
        val sorted = collectAiCandidates(json)
        val betsCountCard = card()
        addText(betsCountCard, "НАЙДЕНО AI-МАТЧЕЙ", 10, true, cyan)
        addText(betsCountCard, sorted.size.toString(), 26, true, Color.WHITE)
        addText(betsCountCard, "В AI попадает только сильнейший сигнал раздела Линия по критерию Kelly. Если он не проходит строгие фильтры, альтернативный рынок вместо него не подставляется.", 11, false, muted)
        if (sorted.isEmpty()) {
            val empty = card()
            addText(empty, "Нет подтверждённых рекомендаций.", 15, true, gold)
            addText(empty, "Нужны положительный сигнал, доступная прематч-котировка MelBet от 1.50 и выполнение порогов стратегии.", 11, false, muted)
            return
        }
        sorted.forEachIndexed { index, item ->
            val (m, league, bundle) = item
            val primary = bundle.optJSONObject("primary") ?: return@forEachIndexed
            val alternatives = bundle.optJSONArray("alternatives") ?: JSONArray()
            val box = card()
            addText(box, "МАТЧ #${index + 1} · ${league.uppercase(Locale.getDefault())}", 10, true, accent)
            addText(box, "${m.optString("home")} — ${m.optString("away")}", 16, true)
            addText(box, kickoffLabel(m), 10, false, muted)
            addText(box, "САМЫЙ СИЛЬНЫЙ СИГНАЛ", 10, true, cyan)
            addAiSignalDetails(box, primary, m, true)
            if (alternatives.length() > 0) {
                addText(box, "АЛЬТЕРНАТИВЫ · ТОЖЕ ПРОШЛИ ФИЛЬТРЫ", 10, true, cyan)
                for (k in 0 until alternatives.length()) {
                    val alt = alternatives.optJSONObject(k) ?: continue
                    addAiSignalDetails(box, alt, m, false)
                }
            }
            val available = mutableListOf<JSONObject>()
            available.add(primary)
            for (k in 0 until alternatives.length()) alternatives.optJSONObject(k)?.let { available.add(it) }
            val notRecorded = available.filterNot { isAiBetRecorded(m, it) }
            if (notRecorded.isEmpty()) {
                addText(box, "✓ Все прошедшие сигналы уже записаны в историю", 12, true, accent)
            } else {
                actionButton(box, if (notRecorded.size == 1) "Выбрать и зафиксировать" else "Выбрать исход для истории") {
                    if (notRecorded.size == 1) {
                        val signal = notRecorded.first()
                        val odds = topSignalOdds(signal, m) ?: return@actionButton
                        confirmManualAiSignalBet(m, league, signal, odds)
                    } else {
                        val labels = notRecorded.map { signal ->
                            val odds = topSignalOdds(signal, m) ?: 0.0
                            "${signal.optString("side")} · ${fmt(odds)} · EV ${String.format(Locale.US, "%+.1f", (signal.optDouble("modelProbability") * odds - 1.0) * 100)}%"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Выбери рынок для бумажной ставки")
                            .setItems(labels) { _, which ->
                                val signal = notRecorded[which]
                                val odds = topSignalOdds(signal, m) ?: return@setItems
                                confirmManualAiSignalBet(m, league, signal, odds)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
        }
        val footer = card()
        addText(footer, "ПРАВИЛА AI", 10, true, accent)
        addText(footer, "Один сильнейший сигнал на матч из раздела Линия: MelBet ≥1.50, вероятность модели ≥60%, edge ≥5 п.п. и EV ≥5%. Если лидер не проходит фильтры, матч исключается; другие рынки не подменяют его.", 11, false, muted)
    }

    private fun addAiSignalDetails(box: LinearLayout, signal: JSONObject, match: JSONObject, primary: Boolean) {
        val odds = topSignalOdds(signal, match) ?: return
        val probability = signal.optDouble("modelProbability", 0.0)
        val ev = probability * odds - 1.0
        val edge = if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", 0.0)
            else (probability - 1.0 / odds) * 100.0 + signal.optDouble("formAdjustmentPp", 0.0)
        addText(box, "${if (primary) "★ " else "• "}${signal.optString("side")} · MelBet ${fmt(odds)}", 14, true, if (primary) gold else Color.WHITE)
        addText(box, "Вероятность модели: ${String.format(Locale.US, "%.1f", probability * 100)}% · Edge: ${String.format(Locale.US, "%+.1f", edge)} п.п. · EV: ${String.format(Locale.US, "%+.1f", ev * 100)}%", 11, false, muted)
        if (signal.optBoolean("paperOnly")) {
            addText(box, "Серверный UEFA paper-сигнал", 10, false, muted)
        } else {
            addText(box, "Историческая база: ${String.format(Locale.US, "%.1f", signal.optDouble("baselineProbability") * 100)}% · ${signal.optInt("baselineMatches")} матчей", 10, false, muted)
        }
        if (isAiBetRecorded(match, signal)) addText(box, "✓ Уже в истории", 10, true, accent)
    }

    private fun renderDay(json: JSONObject) {
        screenHeader("DAILY SIGNAL FILTER", "Ставки дня", "Сильные положительные сигналы с подтверждённой котировкой от 1.50 на выбранную сторону.")
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val today = dayFormat.format(java.util.Date())
        val items = mutableListOf<Triple<JSONObject, String, JSONObject>>()
        val leagues = json.optJSONArray("competitions")
        if (leagues != null) for (i in 0 until leagues.length()) {
            val league = leagues.optJSONObject(i) ?: continue
            val matches = league.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.optJSONObject(j) ?: continue
                if (m.optString("status") !in listOf("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED")) continue
                val matchDay = m.optString("utcDate").take(10)
                if (matchDay.isBlank() || matchDay < today) continue
                val prediction = m.optJSONObject("prediction") ?: continue
                val signal = strongestDisplaySignal(prediction, m) ?: continue
                val edge = signal.optDouble("selectionEdgePp", signal.optDouble("edgePp", 0.0))
                if (edge <= 0.0 || strongestSignalDisplayLabel(signal, m) == null) continue
                // Require a real quote for the exact strongest eligible market, at decimal odds >= 1.50.
                if (topSignalOdds(signal, m) == null) continue
                items.add(Triple(m, league.optString("name"), signal))
            }
        }
        val sorted = items.sortedByDescending { it.third.optDouble("selectionEdgePp", it.third.optDouble("edgePp")) }
        val summary = card()
        addText(summary, "АВТОМАТИЧЕСКИЙ ОТБОР · AI-СИГНАЛЫ", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Допуск: p модели ≥60%, преимущество над break-even ≥5 п.п., EV ≥5% и реальная цена MelBet. Фильтра давности в 5 минут нет; бумажная запись не является реальной ставкой. Риск: 1% банка на ставку с необязательным пользовательским минимумом; разные рынки одного матча разрешены, повтор того же рынка блокируется; дневного лимита нет. Пауза при просадке 10%, остановка при 20%.", 11, false, muted)
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
            addText(box, "+${String.format(Locale.US, "%.1f", signal.optDouble("selectionEdgePp", signal.optDouble("edgePp")))} п.п. с учётом формы", 13, true, accent)
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

        addText(matchesContainer, "ЛИНИЯ", 9, true, cyan)
        addText(matchesContainer, "Матчи с действующими коэффициентами MelBet и прогнозом модели", 16, true, Color.WHITE)
        addText(matchesContainer, "API-SPORT · MELBET · PITCHAPI xG", 9, true, muted)
        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL", "UECL")
        val names = listOf("Все", "ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL")
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
                setOnClickListener {
                    if (selectedLeague == code) return@setOnClickListener
                    val previousIndex = codes.indexOf(selectedLeague)
                    val nextIndex = codes.indexOf(code)
                    val direction = if (nextIndex >= previousIndex) 1 else -1
                    selectedLeague = code
                    render()
                    // Keep the league chips fixed; animate only the match content.
                    matchesContainer.postOnAnimation {
                        if (selectedLeague == code && currentTab == "matches") {
                            animateTabEntry(matchesContainer, direction)
                        }
                    }
                }
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

        // Show every upcoming fixture in the loaded feed; no artificial date horizon.
        val fixtureUtc = java.util.TimeZone.getTimeZone("UTC")
        val fixtureDayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = fixtureUtc }
        val fixtureToday = fixtureDayFormat.format(Date())

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
                val fixtureDate = m.optString("utcDate").take(10).ifBlank {
                    m.optString("dateEvent").take(10).ifBlank {
                        kickoffTimestamp(m)?.let { fixtureDayFormat.format(Date(it)) }.orEmpty()
                    }
                }
                val isUpcoming = fixtureDate.isNotBlank() && fixtureDate >= fixtureToday
                // Apply the same visibility rule to every competition, including UECL:
                // show only upcoming fixtures with a complete model prediction and
                // an active MelBet prematch line. Keep raw feeds intact for settlement.
                if (matchStatus in listOf("SCHEDULED", "TIMED") && isUpcoming &&
                    hasCompleteModelPrediction(m) && hasActiveMelBetPrematchLine(m)) {
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
                addText(empty, "Нет матчей с одновременно подтверждённой линией MelBet и прогнозом модели", 12, false, muted)
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
                    val signal = strongestDisplaySignal(pred, m)
                    val signalBox = card(box)
                    signalBox.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(32, 55, 66), Color.rgb(17, 34, 52))).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(67, 137, 119)) }
                    addText(signalBox, "✦  САМЫЙ СИЛЬНЫЙ ПРОГНОЗ В МАТЧЕ", 10, true, gold)
                    if (signal == null) {
                        addText(signalBox, "Нет доступной котировки MelBet ни для одного из разрешённых рынков этого матча.", 11, false, muted)
                    } else {
                        val displayLabel = strongestSignalDisplayLabel(signal, m)
                        if (displayLabel == null) {
                            addText(signalBox, "Среди разрешённых рынков нет корректного сигнала для отображения.", 12, true, Color.rgb(242, 190, 104))
                        } else {
                            val probability = signal.optDouble("modelProbability", 0.0)
                            val odds = signal.optDouble("lineOdds", Double.NaN)
                            val expectedValue = signal.optDouble("lineExpectedValue", Double.NaN)
                            val positiveValue = expectedValue.isFinite() && expectedValue > 0.0
                            addText(signalBox, displayLabel, 15, true, if (positiveValue) accent else Color.rgb(242, 190, 104))
                            addText(signalBox, "Вероятность модели: ${String.format(Locale.US, "%.1f", probability * 100)}%  ·  Коэффициент MelBet: ${if (odds.isFinite()) String.format(Locale.US, "%.2f", odds) else "—"}", 12, true, Color.WHITE)
                            if (expectedValue.isFinite()) {
                                val evText = String.format(Locale.US, "%+.1f%%", expectedValue * 100.0)
                                addText(signalBox, "Ожидаемая доходность (EV): $evText", 11, true, if (positiveValue) accent else Color.rgb(242, 190, 104))
                                val kelly = signal.optDouble("lineKellyFraction", Double.NaN)
                                if (positiveValue && kelly.isFinite()) {
                                    addText(signalBox, "Доля полного Kelly для ранжирования: ${String.format(Locale.US, "%.1f", kelly * 100.0)}%", 10, false, muted)
                                }
                                addText(signalBox, if (positiveValue)
                                    "Выбран рынок с максимальной долей полного Kelly среди положительных EV и коэффициентов от 1.50."
                                else
                                    "Нет рынка с положительным EV при коэффициенте от 1.50. Показан самый вероятный доступный вариант.",
                                    10, false, muted)
                            }
                            if (signal.has("baselineProbability")) {
                                addText(signalBox, "Историческая база: ${String.format(Locale.US, "%.1f", signal.optDouble("baselineProbability") * 100)}% · ${signal.optInt("baselineMatches")} матчей своей лиги до игры.", 10, false, muted)
                                val priorMatches = signal.optInt("baselinePriorMatches", 0)
                                val priorStrength = signal.optInt("baselinePriorStrength", 0)
                                if (priorMatches > 0 && priorStrength > 0) {
                                    addText(signalBox, "Общая база других лиг: ${priorMatches} матчей; вес в расчёте — ${priorStrength} условных матчей.", 10, false, muted)
                                }
                            }
                            val formAdjustment = signal.optDouble("formAdjustmentPp", 0.0)
                            if (formAdjustment.isFinite() && formAdjustment != 0.0) {
                                addText(signalBox, "Контекст формы: ${String.format(Locale.US, "%+.1f", formAdjustment)} п.п. (справочно)", 10, false, muted)
                            }
                        }
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
                    val oddsTimestamp = m.optJSONObject("odds")?.optString("updatedAt").orEmpty()
                    val quoteTime = if (oddsTimestamp.isBlank()) "время обновления неизвестно" else formatSourceTimestamp(oddsTimestamp)
                    addText(mainMarkets, "Котировка MelBet обновлена: $quoteTime", 10, false, muted)

                }
            }
        }

        val footer = card()
        addText(footer, "ПОКРЫТИЕ", 10, true, accent)
        addText(footer, "Матчей в линии: $totalUpcoming  ·  с полным прогнозом: $totalPredicted", 13, true)
        addText(footer, "Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", 11, false, muted)
        addText(footer, "Линия: API-Sport / MelBet · вероятности и xG: PitchAPI.", 10, false, muted)
    }
}