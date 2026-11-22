package com.footballmodel.app

import android.app.Activity
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.*
import android.widget.FrameLayout
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
import java.util.concurrent.atomic.AtomicLong
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
    private val muted = Color.rgb(166, 158, 143)
    private val accent = Color.rgb(218, 184, 112)
    private val cyan = Color.rgb(235, 215, 174)
    private val gold = Color.rgb(244, 205, 132)
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var headerBalanceView: TextView
    private lateinit var headerAiRoiView: TextView
    private lateinit var leagueBar: LinearLayout
    private lateinit var leagueScroll: HorizontalScrollView
    private lateinit var lineHeadingView: TextView
    private lateinit var tabBar: LinearLayout
    private var currentTab = "matches"
    private val primaryTabs = listOf("matches", "desk", "history", "stats", "help")
    private lateinit var matchesContainer: LinearLayout
    private lateinit var mainScrollView: ScrollView
    private var data: JSONObject? = null
    @Volatile private var leagueMatchCountsSource: JSONObject? = null
    @Volatile private var leagueMatchCountsCache: Map<String, Int> = emptyMap()
    private val leagueCountLock = Any()
    @Volatile private var aiCandidatesCacheKey: String? = null
    @Volatile private var aiCandidatesCache: List<Triple<JSONObject, String, JSONObject>> = emptyList()
    private var bets = JSONArray()
    private val betPrefs by lazy { getSharedPreferences("bet_history", MODE_PRIVATE) }
    private val aiSignalPrefs by lazy { getSharedPreferences("ai_signal_history", MODE_PRIVATE) }
    private val settingsPrefs by lazy { getSharedPreferences("bank_settings", MODE_PRIVATE) }
    private fun isLineSectionVisible(): Boolean = settingsPrefs.getBoolean("show_line_section", true)
    private fun navigationTabs(): List<String> =
        primaryTabs.filter { it != "matches" || isLineSectionVisible() }
    private val feedCache by lazy { getSharedPreferences("feed_cache_v1", MODE_PRIVATE) }
    private val loadInProgress = AtomicBoolean(false)
    @Volatile private var lastPayload: String? = null
    private data class BetMarket(val key: String, val label: String)
    private var selectedLeague = "ALL"
    private var selectedHistoryTab = "ОЖИДАЮТ"
    private var selectedTopTab = "ОЖИДАЮТ"
    private val expandedLeagues = mutableMapOf<String, Boolean>()
    // Сохраняем уже построенные экраны: повторное переключение вкладок не пересоздаёт сотни View.
    private val tabViewCache = mutableMapOf<String, Pair<List<View>, List<View>>>()
    private var renderedKey: String? = null
    // Keep the user's vertical position independently for each tab/filter screen.
    private val tabScrollPositions = mutableMapOf<String, Int>()
    private val liveRefreshHandler = Handler(Looper.getMainLooper())
    private val liveRefreshTask = object : Runnable {
        override fun run() {
            if (!loadInProgress.get()) loadData()
            liveRefreshHandler.postDelayed(this, 60 * 1000L)
        }
    }


    // Narrow, idempotent corrections for the two confirmed historical paper bets.
    // Never modify stake, odds, or any other financial fields.
    private fun applyHistoricalBetCorrections() {
        var changed = false
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val date = bet.optString("date").take(10)
            val home = bet.optString("home").trim().lowercase(Locale.ROOT)
            val away = bet.optString("away").trim().lowercase(Locale.ROOT)
            val market = bet.optString("marketKey")

            val isTroyesLeHavre = date == "2026-11-20" && market == "x2" &&
                home in setOf("troyes", "estac troyes", "труа") &&
                away in setOf("le havre", "havre ac", "le havre ac", "гавр")
            val isBayernCologne = date == "2026-11-20" && market == "awayOver05" &&
                home in setOf("bayern", "bayern munich", "fc bayern münchen", "fc bayern munich", "бавария") &&
                away in setOf("cologne", "koln", "köln", "1. fc köln", "1. fc koln", "fc koln", "кёльн", "кельн")

            val targetStatus = when {
                isTroyesLeHavre -> "LOST"
                isBayernCologne -> "WON"
                else -> null
            } ?: continue
            val targetScore = if (isTroyesLeHavre) "3:1" else "1:1"
            if (bet.optString("status") != targetStatus || bet.optString("score") != targetScore ||
                bet.optString("matchStatus") != "FINISHED") {
                bet.put("status", targetStatus)
                    .put("score", targetScore)
                    .put("matchStatus", "FINISHED")
                changed = true
            }
        }
        if (changed) saveBets()
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
        // Request notification permission immediately on app launch.
        AiMatchNotifications.requestPermissionIfNeeded(this)
        currentTab = intent.getStringExtra("open_tab")?.takeUnless { it == "notifications" } ?: "matches"
        if (currentTab == "matches" && !isLineSectionVisible()) currentTab = "desk"
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        mainScrollView = object : ScrollView(this) {
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
        mainScrollView.addView(root)
        setContentView(mainScrollView)

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
        headerBalanceView = label("-- ₽", 12, true, Color.WHITE).apply { gravity = Gravity.END; maxLines = 1 }
        headerRight.addView(headerBalanceView)
        val roiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerAiRoiView = label("ТОП ROI --", 10, true, accent).apply { gravity = Gravity.END; maxLines = 1 }
        roiRow.addView(headerAiRoiView, LinearLayout.LayoutParams(-1, -2))
        headerRight.addView(roiRow)
        val profileGear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_gold)
            contentDescription = "Настройки"
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { selectTab("help") }
        }
        headerRight.addView(profileGear, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            gravity = Gravity.END
        })
        top.addView(headerRight, LinearLayout.LayoutParams(dp(116), -2))
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
        root.addView(tabBar, LinearLayout.LayoutParams(-1, 80).apply { topMargin = 16; bottomMargin = 14 })

        leagueBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Create the horizontal scroller once. Reparenting leagueBar during render()
        // caused "The specified child already has a parent" and crashed the app.
        leagueScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Часть следующего фильтра остаётся видимой, чтобы обозначить свайп.
            clipToPadding = true
            setPadding(0, 0, 0, 0)
            addView(leagueBar)
        }
        lineHeadingView = label("ЛИНИЯ", 25, true, Color.WHITE).apply {
            setPadding(dp(2), dp(5), dp(2), dp(8))
            visibility = if (currentTab == "matches") View.VISIBLE else View.GONE
        }
        root.addView(lineHeadingView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(2)
        })
        val leagueScrollFrame = FrameLayout(this)
        leagueScrollFrame.addView(leagueScroll, FrameLayout.LayoutParams(-1, -2))
        root.addView(leagueScrollFrame, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        matchesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(matchesContainer)
        bets = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
        invalidateStatsBetCache()
        applyHistoricalBetCorrections()
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
                    // Precompute summaries on this cache-restore worker, never on the UI thread.
                    prepareAvailableMatchCounts(restored)
                    val cachedCandidates = collectAiCandidates(restored)
                    aiCandidatesCache = cachedCandidates
                    aiCandidatesCacheKey = statsCacheKey(restored)
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
            currentTab = "matches"
            SectionNotifications.markRead(this)
            tabViewCache.clear()
            renderedKey = null
            if (data != null) render() else loadData()
        } else if (requestedTab == "desk") {
            currentTab = "desk"
            selectedTopTab = "ОЖИДАЮТ"
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
            invalidateStatsBetCache()
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
            // Keep the flag artwork visually aligned with the 15sp emoji flags.
            val flagWidth = dp(18).toFloat()
            val flagHeight = dp(12).toFloat()
            val left = (width - flagWidth) / 2f
            val top = (height - flagHeight) / 2f
            val right = left + flagWidth
            val bottom = top + flagHeight
            paint.color = Color.WHITE
            canvas.drawRect(left, top, right, bottom, paint)
            paint.color = Color.rgb(206, 17, 38)
            val vertical = flagWidth * 0.22f
            val horizontal = flagHeight * 0.34f
            canvas.drawRect((left + right - vertical) / 2f, top, (left + right + vertical) / 2f, bottom, paint)
            canvas.drawRect(left, (top + bottom - horizontal) / 2f, right, (top + bottom + horizontal) / 2f, paint)
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
        val heading = label(title, 25, true, Color.WHITE).apply {
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
        val icon = label(symbol, 19, true, gold).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(54, 43, 31))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(139, 105, 57))
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
                // Prepare expensive navigation summaries and TOP candidates off the main thread.
                prepareAvailableMatchCounts(json)
                val aiMatchCandidates = collectAiCandidates(json)
                aiCandidatesCache = aiMatchCandidates
                aiCandidatesCacheKey = statsCacheKey(json)
                updateAiSignalArchive(json, aiMatchCandidates)
                val payload = json.toString()
                feedCache.edit()
                    .putString("merged_json", payload)
                    .putLong("last_successful_check_ms", System.currentTimeMillis())
                    .apply()
                runOnUiThread {
                    val openTopNotificationCandidates = aiMatchCandidates.filterNot { item ->
                        val signal = item.third.optJSONObject("primary") ?: return@filterNot false
                        isAiBetRecorded(item.first, signal)
                    }.mapNotNull { item ->
                        val match = item.first
                        val signal = item.third.optJSONObject("primary") ?: return@mapNotNull null
                        val matchId = match.optString("id").ifBlank {
                            listOf(match.optString("utcDate"), match.optString("home"), match.optString("away")).joinToString("|")
                        }
                        val marketKey = marketKeyForAutoSignal(signal, match) ?: return@mapNotNull null
                        "${matchId}::${marketKey}" to "${match.optString("home")} — ${match.optString("away")} · ${signal.optString("side")}"
                    }
                    AiMatchNotifications.observe(this, openTopNotificationCandidates)
                    val changed = payload != lastPayload
                    if (changed) {
                        data = json
                        lastPayload = payload
                        tabViewCache.clear()
                        renderedKey = null
                        render()
                    }
                    // Refresh the history immediately when foreground or background settlement changes a bet.
                    val betsChanged = processAutoPaperBets(json)
                    if (betsChanged && currentTab == "history") {
                        // Do not cache the currently visible pre-settlement rows.
                        renderedKey = null
                        render()
                    }
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
        if (tab == "matches" && !isLineSectionVisible()) return
        val availableTabs = navigationTabs()
        val previousIndex = availableTabs.indexOf(currentTab)
        val nextIndex = availableTabs.indexOf(tab)
        val direction = if (previousIndex >= 0 && nextIndex >= 0) {
            if (nextIndex > previousIndex) 1 else -1
        } else 1
        currentTab = tab
        val generation = ++tabRenderGeneration

        // Update navigation immediately. A previously rendered tab is attached
        // synchronously from the view cache; only first-time screens need a frame
        // to construct their content.
        renderTabs()
        matchesContainer.animate().cancel()
        leagueScroll.animate().cancel()
        lineHeadingView.visibility = if (tab == "matches") View.VISIBLE else View.GONE
        leagueScroll.visibility = if (tab == "matches") View.VISIBLE else View.GONE
        val targetKey = tab + "|" + when (tab) {
            "matches" -> selectedLeague
            "history" -> selectedHistoryTab
            "desk" -> selectedTopTab
            "stats" -> "ALL"
            else -> "ALL"
        }
        if (tabViewCache.containsKey(targetKey) && data != null) {
            render()
            matchesContainer.visibility = View.VISIBLE
            animateTabEntry(matchesContainer, direction)
            if (tab == "matches") animateTabEntry(leagueScroll, direction)
        } else {
            matchesContainer.visibility = View.INVISIBLE
            matchesContainer.postOnAnimation {
                if (generation != tabRenderGeneration || currentTab != tab) return@postOnAnimation
                render()
                animateTabEntry(matchesContainer, direction)
                if (tab == "matches") animateTabEntry(leagueScroll, direction)
            }
        }

        // Navigation uses the latest in-memory feed. Network refresh stays on its
        // independent refresh schedule instead of starting on every tab change.
        if (data == null && (tab == "desk" || tab == "stats")) loadData()
    }

    private fun selectAdjacentTab(direction: Int) {
        val availableTabs = navigationTabs()
        val index = availableTabs.indexOf(currentTab)
        if (index < 0) return
        val nextIndex = (index + direction).coerceIn(0, availableTabs.lastIndex)
        if (nextIndex != index) selectTab(availableTabs[nextIndex])
    }

    private fun leagueLabelForCode(code: String): String = when (code) {
        "PL" -> "АПЛ"; "PD" -> "Ла Лига"; "SA" -> "Серия А"; "BL1" -> "Бундеслига"
        "FL1" -> "Лига 1"; "UCL" -> "ЛЧ"; "UEL" -> "ЛЕ"; "UECL" -> "ЛК"
        else -> "Все"
    }

    // Precompute all league counters once per feed snapshot, not once per chip.
    private fun prepareAvailableMatchCounts(json: JSONObject) {
        synchronized(leagueCountLock) {
            if (leagueMatchCountsSource === json) return
            val counts = mutableMapOf(
                "ALL" to 0, "PL" to 0, "PD" to 0, "SA" to 0,
                "BL1" to 0, "FL1" to 0, "UCL" to 0, "UEL" to 0, "UECL" to 0
            )
            val competitions = json.optJSONArray("competitions")
            val fixtureDayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val today = fixtureDayFormat.format(Date())
            if (competitions != null) for (i in 0 until competitions.length()) {
                val league = competitions.optJSONObject(i) ?: continue
                val code = league.optString("code")
                val matches = league.optJSONArray("matches") ?: continue
                for (j in 0 until matches.length()) {
                    val match = matches.optJSONObject(j) ?: continue
                    val fixtureDate = match.optString("utcDate").take(10).ifBlank {
                        match.optString("dateEvent").take(10).ifBlank {
                            kickoffTimestamp(match)?.let { fixtureDayFormat.format(Date(it)) }.orEmpty()
                        }
                    }
                    if (match.optString("status") in listOf("SCHEDULED", "TIMED") &&
                        fixtureDate.isNotBlank() && fixtureDate >= today &&
                        hasStrategyPredictionWithActiveMelBetLine(match)
                    ) {
                        counts["ALL"] = (counts["ALL"] ?: 0) + 1
                        if (counts.containsKey(code)) counts[code] = (counts[code] ?: 0) + 1
                    }
                }
            }
            leagueMatchCountsCache = counts.toMap()
            leagueMatchCountsSource = json
        }
    }

    private fun availableMatchCountForLeague(code: String): Int {
        val snapshot = data ?: return 0
        if (leagueMatchCountsSource !== snapshot) prepareAvailableMatchCounts(snapshot)
        return leagueMatchCountsCache[code] ?: 0
    }

    private fun renderLeagueTabs() {
        val codes = listOf("ALL", "PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL", "UECL")
        val names = listOf("Все", "АПЛ", "Ла Лига", "Серия А", "Бундеслига", "Лига 1", "ЛЧ", "ЛЕ", "ЛК")
        leagueBar.removeAllViews()
        codes.forEachIndexed { index, code ->
            val active = selectedLeague == code
            val chip = TextView(this).apply {
                text = "${names[index]} · ${availableMatchCountForLeague(code)}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.03f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(16), dp(11), dp(16), dp(11))
                setTextColor(if (active) Color.rgb(28, 31, 36) else muted)
                background = if (active) GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))
                ).apply { cornerRadius = dp(24).toFloat() } else rounded(surface, 24)
                setOnClickListener {
                    if (selectedLeague == code) return@setOnClickListener
                    selectedLeague = code
                    tabViewCache.clear()
                    render()
                }
            }
            leagueBar.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(0, dp(7), dp(7), dp(9))
            })
        }
    }

    private fun renderTabs() {
        val items = buildList {
            if (isLineSectionVisible()) add("matches" to "ЛИНИЯ")
            add("desk" to "ТОП")
            add("history" to "СТАВКИ")
            add("stats" to "СТАТИСТИКА")
        }
        if (tabBar.childCount != items.size) {
            tabBar.removeAllViews()
            items.forEach { item ->
                val tab = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    minimumWidth = 0
                    isClickable = true
                    isFocusable = true
                    contentDescription = item.second
                    setOnClickListener { selectTab(item.first) }
                }
                val title = label(item.second, 10, true, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    setSingleLine(true)
                    setHorizontallyScrolling(false)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                    )
                    // Keep a small inset around the long Statistics label without clipping its last character.
                    setPadding(if (item.first == "stats") dp(4) else 0, 0, if (item.first == "stats") dp(4) else 0, 0)
                }
                // Text only: keep the label centered both horizontally and vertically.
                tab.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
                    gravity = Gravity.CENTER
                })
                tabBar.addView(tab, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
        items.forEachIndexed { index, item ->
            val tab = tabBar.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val title = tab.getChildAt(0) as? TextView ?: return@forEachIndexed
            val active = currentTab == item.first
            title.setTextColor(if (active) bg else Color.WHITE)
            tab.background = if (active) {
                GradientDrawable().apply {
                    setColor(Color.rgb(218, 184, 112))
                    cornerRadius = dp(16).toFloat()
                }
            } else {
                GradientDrawable().apply {
                    setColor(Color.rgb(29, 48, 66))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(39, 61, 82))
                }
            }
            tab.elevation = if (active) dp(3).toFloat() else 0f
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
        val logLoss: Double, val bands: List<StatsBandRow>, val byLeague: List<StatsBandRow>
    )
    @Volatile private var statsMetricsCacheKey: String? = null
    @Volatile private var statsMetricsCache: List<StatsMetricRow> = emptyList()
    @Volatile private var statsMetricsError: String? = null
    private val statsCalculationInProgress = AtomicBoolean(false)
    private var selectedStatsPeriod = "MONTH"
    private data class StatsBetInput(
        val id: String, val betDay: String, val date: String, val createdAt: Long,
        val source: String, val origin: String, val matchId: String, val probability: Double,
        val edgePp: Double, val expectedValue: Double, val quoteUpdatedAt: String,
        val status: String, val stake: Double, val odds: Double,
        val market: String, val marketKey: String, val league: String, val isCoupon: Boolean
    )
    private data class StatsBetViewCache(
        val key: String, val current: BetAnalyticsReport, val previous: BetAnalyticsReport?
    )
    @Volatile private var statsBetViewCache: StatsBetViewCache? = null
    @Volatile private var statsBetCalculationError: String? = null
    private val statsBetCalculationInProgress = AtomicBoolean(false)
    private val statsBetCacheGeneration = AtomicLong(0L)

    private fun invalidateStatsBetCache() {
        statsBetCacheGeneration.incrementAndGet()
        statsBetViewCache = null
        statsBetCalculationError = null
    }

    private fun requestStatsBetCalculation(key: String, sourceBets: JSONArray, period: String, source: String, now: Long) {
        if (!statsBetCalculationInProgress.compareAndSet(false, true)) return
        val generation = statsBetCacheGeneration.get()
        val snapshot = try {
            val inputs = mutableListOf<StatsBetInput>()
            for (index in 0 until sourceBets.length()) {
                val bet = sourceBets.optJSONObject(index) ?: continue
                inputs.add(StatsBetInput(
                    bet.optString("id"), bet.optString("betDay"), bet.optString("date"),
                    bet.optLong("createdAt", 0L), bet.optString("source"), bet.optString("origin"),
                    bet.optString("matchId"), bet.optDouble("probability", Double.NaN),
                    bet.optDouble("edgePp", Double.NaN), bet.optDouble("expectedValue", Double.NaN),
                    bet.optString("quoteUpdatedAt"), bet.optString("status"),
                    bet.optDouble("stake", Double.NaN), bet.optDouble("odds", Double.NaN),
                    bet.optString("market"), bet.optString("marketKey"), bet.optString("league"),
                    bet.optString("marketKey") == "COUPON"
                ))
            }
            inputs
        } catch (error: Throwable) {
            statsBetCalculationInProgress.set(false)
            android.util.Log.e("IQMatchStats", "Unable to snapshot bet history statistics", error)
            statsBetCalculationError = error.javaClass.simpleName
            return
        }
        thread(name = "terminal-bet-analytics") {
            var calculationError: String? = null
            val calculated = try {
                val bounds = statsPeriodBounds(period, now)
                val records = snapshot.mapNotNull { item ->
                    val day = betStatsDate(item) ?: return@mapNotNull null
                    val isTopSignal = item.source.equals("AI_MANUAL", true) ||
                        item.origin.equals("TOP_SIGNAL", true) ||
                        (item.source.equals("MANUAL", true) && item.matchId.isNotBlank() &&
                            item.marketKey.isNotBlank() && item.probability.isFinite() &&
                            item.quoteUpdatedAt.isNotBlank() &&
                            (item.edgePp.isFinite() || item.expectedValue.isFinite()))
                    AnalyticsBet(item.id, day, item.source, item.status, item.stake, item.odds,
                        item.market.ifBlank { item.marketKey }, item.league, item.isCoupon, isTopSignal)
                }
                val current = BetAnalyticsCalculator.calculate(records, bounds.first, bounds.second, "TOP")
                val previous = if (period == "ALL") null else {
                    val duration = (bounds.second - bounds.first).coerceAtLeast(1L)
                    BetAnalyticsCalculator.calculate(records, bounds.first - duration, bounds.first - 1L, "TOP")
                }
                StatsBetViewCache(key, current, previous)
            } catch (error: Throwable) {
                android.util.Log.e("IQMatchStats", "Unable to calculate terminal betting analytics", error)
                calculationError = error.javaClass.simpleName
                StatsBetViewCache(key, BetAnalyticsCalculator.calculate(emptyList(), 0L, Long.MAX_VALUE, source), null)
            }
            runOnUiThread {
                if (generation == statsBetCacheGeneration.get()) {
                    statsBetViewCache = calculated
                    statsBetCalculationError = calculationError
                }
                statsBetCalculationInProgress.set(false)
                if (currentTab == "stats") {
                    tabViewCache.remove("stats|ALL")
                    render()
                }
            }
        }
    }

    private fun betStatsDate(bet: StatsBetInput): Long? {
        val rawDay = bet.betDay.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        val raw = rawDay ?: bet.date.takeIf { it.isNotBlank() } ?: return bet.createdAt.takeIf { it > 0L }
        return try {
            val fmt = if (raw.length == 10) SimpleDateFormat("yyyy-MM-dd", Locale.US)
                else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.isLenient = false
            if (raw.length != 10) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(raw.take(if (raw.length == 10) 10 else 19))?.time
        } catch (_: Exception) { bet.createdAt.takeIf { it > 0L } }
    }

    private fun statsPeriodBounds(period: String, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val end = cal.timeInMillis
        when (period) {
            "WEEK" -> cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
            "MONTH" -> cal.add(java.util.Calendar.MONTH, -1)
            "QUARTER" -> cal.add(java.util.Calendar.MONTH, -3)
            "HALF" -> cal.add(java.util.Calendar.MONTH, -6)
            "YEAR" -> cal.add(java.util.Calendar.YEAR, -1)
            else -> return 0L to end
        }
        return cal.timeInMillis to end
    }

    private fun terminalSection(parent: LinearLayout, title: String, subtitle: String = ""): LinearLayout {
        val box = card(parent)
        box.setPadding(dp(15), dp(14), dp(15), dp(14))
        box.background = GradientDrawable().apply {
            setColor(Color.rgb(13, 25, 41)); cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.rgb(91, 78, 55))
        }
        box.addView(label(title.uppercase(Locale.ROOT), 10, true, gold).apply {
            typeface = Typeface.MONOSPACE; letterSpacing = 0.08f
        })
        if (subtitle.isNotBlank()) box.addView(label(subtitle, 9, false, muted).apply {
            typeface = Typeface.MONOSPACE
        })
        return box
    }

    private fun terminalMetric(parent: LinearLayout, name: String, value: String, tint: Int) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(7), dp(4), dp(7)) }
        col.addView(label(name.uppercase(Locale.ROOT), 8, true, muted).apply { typeface = Typeface.MONOSPACE })
        col.addView(label(value, 15, true, tint).apply { typeface = Typeface.create("monospace", Typeface.BOLD) })
        parent.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun terminalSummary(parent: LinearLayout, title: String, summary: AnalyticsSlice) {
        val box = terminalSection(parent, title, "РАССЧИТАНО: " + summary.settled + " · ОЖИДАЮТ: " + summary.pending)
        val first = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(first, "ЧИСТАЯ ПРИБЫЛЬ", fmt(summary.profit), if (summary.profit >= 0) Color.rgb(73, 218, 163) else Color.rgb(245, 105, 112))
        terminalMetric(first, "ROI · ДОХОДНОСТЬ", String.format(Locale.US, "%.2f%%", summary.roi), if (summary.roi >= 0) Color.rgb(73, 218, 163) else Color.rgb(245, 105, 112))
        terminalMetric(first, "ДОЛЯ ПОБЕД", String.format(Locale.US, "%.1f%%", summary.winRate), Color.WHITE)
        box.addView(first)
        val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(second, "В / П / ВОЗВРАТ", summary.wins.toString() + "/" + summary.losses + "/" + summary.pushes, Color.WHITE)
        terminalMetric(second, "ОБОРОТ", fmt(summary.turnover), Color.WHITE)
        terminalMetric(second, "СРЕДНИЙ КЭФ.", if (summary.averageOdds > 0) String.format(Locale.US, "%.2f", summary.averageOdds) else "—", Color.WHITE)
        box.addView(second)
    }

    private fun addTerminalEquityChart(parent: LinearLayout, points: List<AnalyticsPoint>) {
        val box = terminalSection(parent, "ДИНАМИКА ПРИБЫЛИ", "ТОЛЬКО РАССЧИТАННЫЕ СТАВКИ · НЕ БАЛАНС СЧЁТА")
        if (points.isEmpty()) {
            box.addView(label("Нет рассчитанных ставок за выбранный период.", 11, false, muted))
            return
        }
        val chart = object : View(this) {
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
            private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 105, 116); strokeWidth = dp(1).toFloat() }
            private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(133, 153, 164); textSize = dp(9).toFloat(); typeface = Typeface.MONOSPACE }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = dp(43).toFloat(); val right = width - dp(8).toFloat()
                val top = dp(12).toFloat(); val bottom = height - dp(22).toFloat()
                if (right <= left || bottom <= top) return
                var total = 0.0
                val values = points.map { total += it.profit; total }
                val low = minOf(0.0, values.minOrNull() ?: 0.0)
                val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
                val span = (high - low).coerceAtLeast(1.0)
                for (i in 0..3) {
                    val y = top + (bottom - top) * i / 3f
                    canvas.drawLine(left, y, right, y, grid)
                    canvas.drawText(String.format(Locale.US, "%.0f", high - span * i / 3.0), 0f, y + dp(3), txt)
                }
                val path = android.graphics.Path()
                values.forEachIndexed { i, value ->
                    val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * i / (values.size - 1).toFloat()
                    val y = bottom - ((value - low) / span * (bottom - top)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                line.color = if ((values.lastOrNull() ?: 0.0) >= 0) gold else Color.rgb(220, 112, 118)
                canvas.drawPath(path, line)
                canvas.drawText("НАЧАЛО", left, height - dp(4).toFloat(), txt)
                canvas.drawText("СЕЙЧАС", right - dp(38), height - dp(4).toFloat(), txt)
            }
        }
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(170)))
    }

    private fun terminalBreakdown(parent: LinearLayout, title: String, rows: List<AnalyticsBreakdown>, limit: Int = 8) {
        val box = terminalSection(parent, title)
        if (rows.isEmpty()) {
            box.addView(label("Недостаточно рассчитанных данных.", 11, false, muted))
            return
        }
        rows.take(limit).forEachIndexed { index, item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(label((index + 1).toString() + ". " + item.label, 10, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1.5f))
            row.addView(label(item.count.toString() + " · " + item.wins + "/" + item.losses, 9, false, muted).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.8f))
            row.addView(label(String.format(Locale.US, "%.1f%%", item.roi), 9, true, if (item.roi >= 0) gold else Color.rgb(220, 112, 118)).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.7f))
            row.addView(label(fmt(item.profit), 9, true, if (item.profit >= 0) gold else Color.rgb(220, 112, 118)).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.7f))
            box.addView(row)
        }
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun exportBetAnalyticsCsv() {
        try {
            val rows = mutableListOf("идентификатор,дата,источник,статус,тип,матч_или_экспресс,рынок,ставка,коэффициент,прибыль,чемпионат,исходы")
            for (i in 0 until bets.length()) {
                val bet = bets.optJSONObject(i) ?: continue
                val source = bet.optString("source")
                if (source.equals("AI_AUTO", true)) continue
                val status = bet.optString("status")
                val stake = bet.optDouble("stake", Double.NaN)
                val odds = bet.optDouble("odds", Double.NaN)
                val isCoupon = bet.optString("marketKey") == "COUPON"
                val validFinancials = stake.isFinite() && stake > 0.0 && odds.isFinite() && odds > 1.0
                val profit = if (!validFinancials) "" else when (status) {
                    "WON" -> (stake * (odds - 1.0)).toString()
                    "LOST" -> (-stake).toString()
                    "PUSH" -> "0"
                    else -> ""
                }
                val selections = bet.optJSONArray("selections")
                val match = if (isCoupon) "Coupon" else bet.optString("home") + " - " + bet.optString("away")
                val market = if (isCoupon) "Coupon" else bet.optString("market")
                val day = bet.optString("betDay").ifBlank { bet.optString("date") }
                val line = listOf(
                    bet.optString("id"), day, source.ifBlank { "MANUAL" }, status,
                    if (isCoupon) "COUPON" else "SINGLE", match, market,
                    stake.toString(), odds.toString(), profit, bet.optString("league"),
                    (selections?.length() ?: 0).toString()
                ).joinToString(",") { csvEscape(it) }
                rows.add(line)
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "История ставок Football Model")
                putExtra(android.content.Intent.EXTRA_TEXT, rows.joinToString("\n"))
            }
            startActivity(android.content.Intent.createChooser(intent, "Экспорт истории ставок"))
        } catch (error: Exception) {
            android.util.Log.e("IQMatchStats", "CSV export failed", error)
            Toast.makeText(this, "Не удалось подготовить CSV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderBetPerformanceStats() {
        val now = System.currentTimeMillis()
        val key = listOf("TOP", selectedStatsPeriod,
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))).joinToString("|")
        val cached = statsBetViewCache
        if (cached?.key != key) {
            val loading = terminalSection(matchesContainer, "СТАТИСТИКА ТОП", "ТОЛЬКО СТАВКИ, ЗАФИКСИРОВАННЫЕ ИЗ РАЗДЕЛА ТОП")
            loading.addView(label("Рассчитываем финансовые показатели…", 12, true, Color.WHITE))
            loading.addView(label("Прогнозы без зафиксированной ставки и автоматические записи в расчёт не входят.", 10, false, muted))
            if (!statsBetCalculationInProgress.get()) requestStatsBetCalculation(key, bets, selectedStatsPeriod, "TOP", now)
            return
        }

        val controls = terminalSection(matchesContainer, "ПЕРИОД СТАВОК ТОП", "ФИНАНСОВАЯ СТАТИСТИКА · ОТДЕЛЬНО ОТ АНАЛИТИКИ ПРОГНОЗОВ")
        val periods = listOf(listOf("WEEK" to "7 ДНЕЙ", "MONTH" to "1 МЕС", "QUARTER" to "3 МЕС"),
            listOf("HALF" to "6 МЕС", "YEAR" to "1 ГОД", "ALL" to "ВСЁ ВРЕМЯ"))
        periods.forEach { options ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEach { option ->
                val active = selectedStatsPeriod == option.first
                val button = TextView(this).apply {
                    text = option.second
                    gravity = Gravity.CENTER
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    minHeight = dp(42)
                    setPadding(dp(5), dp(9), dp(5), dp(9))
                    setTextColor(if (active) Color.rgb(31, 24, 15) else Color.rgb(207, 195, 174))
                    background = GradientDrawable().apply {
                        setColor(if (active) gold else Color.rgb(22, 36, 54))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), if (active) Color.rgb(244, 211, 145) else Color.rgb(73, 68, 57))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (selectedStatsPeriod != option.first) {
                            selectedStatsPeriod = option.first
                            tabViewCache.remove("stats|ALL")
                            render()
                        }
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                })
            }
            controls.addView(row)
        }
        val export = TextView(this).apply {
            text = "↓  ЭКСПОРТ ИСТОРИИ · CSV"
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(44)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextColor(gold)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 36, 54))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(91, 78, 55))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { exportBetAnalyticsCsv() }
        }
        controls.addView(export, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(3), dp(7), dp(3), dp(2))
        })

        val report = cached.current
        statsBetCalculationError?.let { code ->
            val warning = terminalSection(matchesContainer, "ПРЕДУПРЕЖДЕНИЕ О ДАННЫХ")
            warning.addView(label("Расчёт завершён с ошибкой: $code. Проверьте исходную историю.", 11, true, Color.rgb(245, 190, 95)))
        }
        val hero = terminalSection(matchesContainer, "ФИНАНСОВЫЙ РЕЗУЛЬТАТ СТАВОК ТОП",
            "РУЧНАЯ ФИКСАЦИЯ СИГНАЛА · КУПОНЫ И AI_AUTO НЕ ВКЛЮЧЕНЫ")
        val pnlColor = if (report.overall.profit >= 0) gold else Color.rgb(220, 112, 118)
        hero.addView(label((if (report.overall.profit > 0) "+" else "") + fmt(report.overall.profit), 29, true, pnlColor)
            .apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        hero.addView(label("ЧИСТАЯ ПРИБЫЛЬ / УБЫТОК", 9, true, muted))
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(heroRow, "ROI", String.format(Locale.US, "%.2f%%", report.overall.roi), pnlColor)
        terminalMetric(heroRow, "WIN RATE", String.format(Locale.US, "%.1f%%", report.overall.winRate), Color.WHITE)
        terminalMetric(heroRow, "РАССЧИТАНО", report.overall.settled.toString(), Color.WHITE)
        hero.addView(heroRow)
        val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(second, "ПОБЕДЫ", report.overall.wins.toString(), gold)
        terminalMetric(second, "ПОРАЖЕНИЯ", report.overall.losses.toString(), Color.rgb(220, 112, 118))
        terminalMetric(second, "ВОЗВРАТЫ", report.overall.pushes.toString(), Color.WHITE)
        hero.addView(second)
        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(third, "ОБОРОТ", fmt(report.overall.turnover), Color.WHITE)
        terminalMetric(third, "СРЕДНИЙ КЭФ.", if (report.overall.averageOdds > 0) String.format(Locale.US, "%.2f", report.overall.averageOdds) else "—", Color.WHITE)
        terminalMetric(third, "ОЖИДАЮТ", report.overall.pending.toString(), Color.rgb(245, 190, 95))
        hero.addView(third)
        val initialBank = settingsPrefs.getString("initial_bankroll", null)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val allTimeBankEstimate = if (selectedStatsPeriod == "ALL") initialBank?.let { it + report.overall.profit } else null
        val bankRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(bankRow, "СРЕДНЯЯ СТАВКА", if (report.overall.averageStake > 0) fmt(report.overall.averageStake) else "—", Color.WHITE)
        terminalMetric(bankRow, "БАНК · ОЦЕНКА", allTimeBankEstimate?.let { fmt(it) } ?: "—", gold)
        hero.addView(bankRow)
        hero.addView(label("Финансовая выборка содержит только подтверждённые ставки ТОП. PENDING не влияет на оборот, прибыль, ROI и win rate.", 9, false, muted))

        addTerminalEquityChart(matchesContainer, report.overall.points)
        val risk = terminalSection(matchesContainer, "РИСКИ И СЕРИИ", "ПО РЕАЛЬНО ЗАФИКСИРОВАННЫМ СТАВКАМ ТОП")
        val riskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(riskRow, "МАКС. ПРОСАДКА", fmt(report.maxDrawdown), Color.rgb(220, 112, 118))
        terminalMetric(riskRow, "ПРОСАДКА, %", String.format(Locale.US, "%.1f%%", report.maxDrawdownPercent), Color.rgb(220, 112, 118))
        terminalMetric(riskRow, "ТЕКУЩАЯ СЕРИЯ", report.currentStreak, Color.WHITE)
        risk.addView(riskRow)
        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(runRow, "ЛУЧШАЯ СЕРИЯ ПОБЕД", report.longestWinStreak.toString(), gold)
        terminalMetric(runRow, "ХУДШАЯ СЕРИЯ ПОРАЖЕНИЙ", report.longestLossStreak.toString(), Color.rgb(220, 112, 118))
        risk.addView(runRow)
        terminalBreakdown(matchesContainer, "РЕЗУЛЬТАТЫ ПО РЫНКАМ ТОП", report.byMarket, 12)
        terminalBreakdown(matchesContainer, "РЕЗУЛЬТАТЫ ПО ЧЕМПИОНАТАМ", report.byLeague, 10)
        terminalBreakdown(matchesContainer, "СЕГМЕНТЫ КОЭФФИЦИЕНТОВ", report.byOdds, 5)
        terminalBreakdown(matchesContainer, "РЕЗУЛЬТАТЫ ПО ДНЯМ НЕДЕЛИ", report.byWeekday, 7)

        val quality = terminalSection(matchesContainer, "ПРОВЕРКА ИСТОРИИ", "ТОЛЬКО СТАВКИ ТОП · ОЖИДАЮЩИЕ НЕ УЧТЕНЫ В ФИНАНСАХ")
        val integrity = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(integrity, "ЗАПИСЕЙ ТОП", report.recordsRead.toString(), Color.WHITE)
        terminalMetric(integrity, "ОШИБОЧНЫЕ", report.invalidRecords.toString(), if (report.invalidRecords > 0) Color.rgb(245, 190, 95) else gold)
        terminalMetric(integrity, "ДУБЛИ", report.duplicateRecords.toString(), if (report.duplicateRecords > 0) Color.rgb(245, 190, 95) else gold)
        quality.addView(integrity)
        quality.addView(label("Одна зафиксированная ставка считается один раз. Экспрессы и их отдельные исходы в статистику ТОП не попадают.", 10, false, muted))
        cached.previous?.let { previous ->
            val compare = terminalSection(matchesContainer, "СРАВНЕНИЕ ПЕРИОДОВ")
            val delta = report.overall.roi - previous.overall.roi
            compare.addView(label("Изменение ROI: " + String.format(Locale.US, "%+.2f п.п.", delta) +
                " · изменение прибыли: " + fmt(report.overall.profit - previous.overall.profit), 12, true,
                if (delta >= 0) gold else Color.rgb(220, 112, 118)))
            compare.addView(label("Предыдущий ROI: " + String.format(Locale.US, "%.2f%%", previous.overall.roi) +
                " · прибыль: " + fmt(previous.overall.profit) + " · рассчитано: " + previous.overall.settled, 10, false, muted))
        }
    }

    private fun parseStatsArchiveTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return try {
            val pattern = if (value.length == 10) "yyyy-MM-dd" else "yyyy-MM-dd'T'HH:mm:ss"
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(value.take(if (value.length == 10) 10 else 19))?.time
        } catch (_: Exception) { null }
    }

    private fun calculateStatsMetrics(json: JSONObject): List<StatsMetricRow> {
        val metrics = mutableListOf<StatsMetricRow>()
        val evaluatedMatches = json.optJSONArray("evaluationMatches")
        fun collect(key: String, title: String, actual: (Int, Int) -> Boolean) {
            var n = 0
            var hits = 0
            var brier = 0.0
            var logLoss = 0.0
            val bandCounts = IntArray(4)
            val bandHits = IntArray(4)
            val leagueCounts = linkedMapOf<String, IntArray>()
            if (evaluatedMatches != null) for (i in 0 until evaluatedMatches.length()) {
                val m = evaluatedMatches.optJSONObject(i) ?: continue
                if (m.optString("status") != "FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) continue
                val capturedAt = parseStatsArchiveTime(m.optString("predictionCapturedAt")) ?: continue
                val kickoffAt = parseStatsArchiveTime(m.optString("utcDate")) ?: continue
                if (capturedAt >= kickoffAt) continue
                val p = m.optJSONObject("prediction") ?: continue
                if (!p.has(key) || p.isNull(key)) continue
                val probabilityRaw = p.optDouble(key, Double.NaN)
                if (!probabilityRaw.isFinite() || probabilityRaw !in 0.0..1.0) continue
                val h = m.optInt("homeScore", -1)
                val aw = m.optInt("awayScore", -1)
                if (h < 0 || aw < 0) continue
                val outcome = actual(h, aw)
                val probability = probabilityRaw.coerceIn(0.000000000001, 0.999999999999)
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
                val league = m.optString("league").ifBlank { m.optString("leagueCode").ifBlank { "Не указан" } }
                val counts = leagueCounts.getOrPut(league) { IntArray(2) }
                counts[0]++
                if (correct) counts[1]++
            }
            if (n > 0) {
                val labels = listOf("50–59%", "60–69%", "70–79%", "80%+")
                val bands = labels.indices.map { StatsBandRow(labels[it], bandCounts[it], bandHits[it]) }
                val byLeague = leagueCounts.map { (league, values) ->
                    StatsBandRow(league, values[0], values[1])
                }.sortedWith(compareByDescending<StatsBandRow> { it.n }.thenBy { it.label })
                metrics.add(StatsMetricRow(title, n, hits, brier / n, logLoss / n, bands, byLeague))
            }
        }
        collect("1x", "ДВОЙНОЙ ШАНС · 1X") { h, a -> h >= a }
        collect("x2", "ДВОЙНОЙ ШАНС · X2") { h, a -> a >= h }
        collect("bttsYes", "ОБЕ КОМАНДЫ ЗАБЬЮТ · ДА") { h, a -> h > 0 && a > 0 }
        collect("bttsNo", "ОБЕ КОМАНДЫ ЗАБЬЮТ · НЕТ") { h, a -> h == 0 || a == 0 }
        collect("over15", "ТОТАЛ МАТЧА · ТБ 1.5") { h, a -> h + a >= 2 }
        collect("over25", "ТОТАЛ МАТЧА · ТБ 2.5") { h, a -> h + a >= 3 }
        collect("over35", "ТОТАЛ МАТЧА · ТБ 3.5") { h, a -> h + a >= 4 }
        collect("homeOver05", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ХОЗЯЕВА · ТБ 0.5") { h, _ -> h >= 1 }
        collect("homeOver15", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ХОЗЯЕВА · ТБ 1.5") { h, _ -> h >= 2 }
        collect("homeOver25", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ХОЗЯЕВА · ТБ 2.5") { h, _ -> h >= 3 }
        collect("homeOver35", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ХОЗЯЕВА · ТБ 3.5") { h, _ -> h >= 4 }
        collect("awayOver05", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ГОСТИ · ТБ 0.5") { _, a -> a >= 1 }
        collect("awayOver15", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ГОСТИ · ТБ 1.5") { _, a -> a >= 2 }
        collect("awayOver25", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ГОСТИ · ТБ 2.5") { _, a -> a >= 3 }
        collect("awayOver35", "ИНДИВИДУАЛЬНЫЙ ТОТАЛ · ГОСТИ · ТБ 3.5") { _, a -> a >= 4 }
        return metrics
    }

    private fun statsCacheKey(json: JSONObject): String {
        // A cheap feed signature avoids serializing the entire match archive on the UI thread.
        return json.optString("updatedAt") + "|" + json.optString("uefaEvaluationUpdatedAt") + "|" +
            (json.optJSONArray("evaluationMatches")?.length() ?: 0)
    }


    private fun renderStats(json: JSONObject) {
        screenHeader("PRO TERMINAL · ДВЕ НЕЗАВИСИМЫЕ ВЫБОРКИ", "Статистика", "Ставки ТОП · качество всех прогнозов отдельно")
        try {
            renderBetPerformanceStats()
        } catch (error: Throwable) {
            android.util.Log.e("IQMatchStats", "Unable to render bet performance section", error)
            val failure = card()
            addText(failure, "Не удалось построить статистику ставок. Сводка прогнозов будет доступна отдельно.", 12, true, gold)
            matchesContainer.addView(failure)
        }
        val statsKey = statsCacheKey(json)
        if (statsMetricsCacheKey != statsKey) {
            val loading = card()
            addText(loading, "Подготавливаем статистику…", 13, true, Color.WHITE)
            addText(loading, "Расчёт выполняется в фоне. Интерфейс остаётся доступным.", 11, false, muted)
            if (statsCalculationInProgress.compareAndSet(false, true)) {
                // The feed object is replaced, not mutated, on refresh; read it on the worker.
                thread(name = "stats-metrics-calculation") {
                    // A malformed historical row must never crash the whole Android process.
                    val calculated = try {
                        calculateStatsMetrics(json).also { statsMetricsError = null }
                    } catch (error: Throwable) {
                        android.util.Log.e("IQMatchStats", "Unable to calculate prediction statistics", error)
                        statsMetricsError = error.javaClass.simpleName
                        emptyList()
                    }
                    runOnUiThread {
                        statsMetricsCache = calculated
                        statsMetricsCacheKey = statsKey
                        statsCalculationInProgress.set(false)
                        if (currentTab == "stats") {
                            lineHeadingView.visibility = View.GONE
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
        val overallHeading = terminalSection(matchesContainer, "АНАЛИТИКА ВСЕХ ПРОГНОЗОВ", "ВСЕ МАТЧИ ИЗ АРХИВА · НЕ ЗАВИСИТ ОТ ФИКСАЦИИ СТАВКИ")
        if (metrics.isEmpty()) {
            val empty = terminalSection(matchesContainer, "АРХИВ ПРОГНОЗОВ")
            val calculationError = statsMetricsError
            if (calculationError != null) {
                empty.addView(label("Не удалось рассчитать сводку прогнозов. Остальная статистика доступна.", 12, true, gold))
                empty.addView(label("Код ошибки: $calculationError", 10, false, muted))
            } else {
                empty.addView(label("Архив проверенных прогнозов пока формируется. Матч попадёт в статистику после фиксации прогноза до начала игры и получения подтверждённого итогового счёта.", 11, false, muted))
            }
        } else {
            overallHeading.addView(label("В выборку входят все матчи с прогнозом, сохранённым до начала игры, и подтверждённым финальным счётом. Фиксация ставки ТОП не требуется.", 10, false, muted))
            metrics.forEach { metric ->
            val box = terminalSection(matchesContainer, metric.name, "ВЫБОРКА: ${metric.n} ЗАВЕРШЁННЫХ МАТЧЕЙ")
            val hitRate = metric.hits * 100.0 / metric.n
            val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(summaryRow, "ПОПАДАНИЯ", "${metric.hits} / ${metric.n}", Color.WHITE)
            terminalMetric(summaryRow, "ТОЧНОСТЬ", String.format(Locale.US, "%.1f%%", hitRate),
                if (hitRate >= 50.0) Color.rgb(73, 218, 163) else Color.rgb(245, 190, 95))
            box.addView(summaryRow)
            box.addView(label("ТОЧНОСТЬ ПО УРОВНЮ УВЕРЕННОСТИ", 9, true, cyan).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(7), 0, dp(3))
            })
            metric.bands.forEach { band ->
                val rate = if (band.n > 0) String.format(Locale.US, "%.1f%%", band.hits * 100.0 / band.n) else "—"
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("${band.label}", 10, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label("${band.hits} / ${band.n}", 10, false, muted).apply { typeface = Typeface.MONOSPACE })
                row.addView(label(rate, 10, true, if (band.n > 0) accent else muted).apply {
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(dp(58), -2))
                box.addView(row)
                val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (band.n > 0) (band.hits * 1000 / band.n) else 0
                    progressTintList = android.content.res.ColorStateList.valueOf(if (band.n > 0) accent else Color.rgb(47, 61, 72))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(27, 39, 50))
                }
                box.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(5)
                })
            }
            val qualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(qualityRow, "ОЦЕНКА БРАЙЕРА", String.format(Locale.US, "%.4f", metric.brier), Color.WHITE)
            terminalMetric(qualityRow, "ЛОГ. ПОТЕРЯ", String.format(Locale.US, "%.4f", metric.logLoss), Color.WHITE)
            box.addView(qualityRow)
            if (metric.byLeague.isNotEmpty()) {
                box.addView(label("ТОЧНОСТЬ ПО ЧЕМПИОНАТАМ", 9, true, gold).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(7), 0, dp(3))
                })
                metric.byLeague.take(5).forEach { league ->
                    val rate = if (league.n > 0) league.hits * 100.0 / league.n else 0.0
                    val leagueRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(3), 0, dp(3))
                    }
                    leagueRow.addView(label(league.label, 10, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                    leagueRow.addView(label("${league.hits}/${league.n}", 10, false, muted), LinearLayout.LayoutParams(dp(54), -2))
                    leagueRow.addView(label(String.format(Locale.US, "%.1f%%", rate), 10, true, gold).apply {
                        gravity = Gravity.END
                        typeface = Typeface.MONOSPACE
                    }, LinearLayout.LayoutParams(dp(58), -2))
                    box.addView(leagueRow)
                }
            }
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

    // Lock a paper bet as soon as the visible AI candidate qualifies.
    // The stored odds and quote timestamp remain fixed through settlement.
    private fun processAutoPaperBets(json: JSONObject): Boolean {
        return synchronized(AutoPaperExecutionLock) { processAutoPaperBetsUnlocked(json) }
    }

    private fun processAutoPaperBetsUnlocked(json: JSONObject): Boolean {
        // Detect status changes made by the background worker before reloading its persisted snapshot.
        val previousStatuses = mutableMapOf<String, String>()
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val key = bet.optString("id").ifBlank {
                "local:${bet.optString("matchId")}:${bet.optString("marketKey")}:${bet.optString("date")}:$i"
            }
            previousStatuses[key] = bet.optString("status")
        }

        val persistedBets = try { JSONArray(betPrefs.getString("bets", bets.toString())) } catch (_: Exception) { bets }
        var changed = false
        for (i in 0 until persistedBets.length()) {
            val bet = persistedBets.optJSONObject(i) ?: continue
            val key = bet.optString("id").ifBlank {
                "local:${bet.optString("matchId")}:${bet.optString("marketKey")}:${bet.optString("date")}:$i"
            }
            val previousStatus = previousStatuses[key]
            if (previousStatus != null && previousStatus != bet.optString("status")) changed = true
        }
        bets = persistedBets
        invalidateStatsBetCache()

        for (i in 0 until bets.length()) {
            val existing = bets.optJSONObject(i) ?: continue
            if (existing.optString("status") == "PENDING" && existing.optString("marketKey") != "COUPON") {
                val match = findMatch(json, existing) ?: continue
                if (settleBet(existing, match)) changed = true
            }
        }
        // Automatic paper-bet creation is disabled. Keep settlement of existing bets above.
        if (changed) saveBets()
        return changed
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
        tabViewCache.remove("history|OPEN")
        tabViewCache.remove("history|COMPLETED")
        // Bet status changes affect both TOP subtabs; never reuse a stale count or list.
        tabViewCache.remove("desk|ALL")
        tabViewCache.remove("desk|OPEN")
        tabViewCache.remove("desk|FIXED")
        tabViewCache.remove("stats|ALL")
        invalidateStatsBetCache()
    }
    private fun actionButton(parent: LinearLayout, title: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = title; textSize = 12f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8)); setTextColor(Color.rgb(28, 22, 13))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(2).toFloat(); setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 8 })
        return b
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
            setTextColor(if (destructive) Color.rgb(86, 37, 34) else Color.rgb(35, 27, 16))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), if (destructive) Color.rgb(132, 74, 54) else Color.rgb(224, 188, 120))
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
            if (isFinishedMatch(match)) {
                val leg = JSONObject().put("status", "PENDING").put("marketKey", pick.optString("marketKey"))
                settleBet(leg, match)
                val result = leg.optString("status")
                if (result !in listOf("WON", "LOST", "PUSH")) {
                    allSettled = false
                    continue
                }
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

    private fun isFinishedMatch(match: JSONObject): Boolean {
        val statuses = listOf(
            match.optString("status"),
            match.optString("statusShort"),
            match.optString("statusLong")
        ).map { it.trim().uppercase(Locale.ROOT) }
        return statuses.any { it in setOf("FINISHED", "FT", "AET", "PEN", "AFTER EXTRA TIME", "AFTER PENALTIES", "MATCH FINISHED") }
    }

    private fun normalizedTeamName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun findMatch(j: JSONObject, b: JSONObject): JSONObject? {
        val competitions = j.optJSONArray("competitions") ?: return null
        val targetId = b.optString("matchId").trim()
        val targetHome = normalizedTeamName(b.optString("home"))
        val targetAway = normalizedTeamName(b.optString("away"))
        val targetDate = b.optString("date").take(10)
        var nameAndDateFallback: JSONObject? = null
        var namesOnlyFallback: JSONObject? = null
        var namesOnlyCount = 0

        for (i in 0 until competitions.length()) {
            val matches = competitions.optJSONObject(i)?.optJSONArray("matches") ?: continue
            for (k in 0 until matches.length()) {
                val match = matches.optJSONObject(k) ?: continue
                val matchId = match.optString("id").trim().ifBlank {
                    match.optString("utcDate") + match.optString("home") + match.optString("away")
                }
                if (targetId.isNotBlank() && matchId == targetId) return match

                if (targetHome.isBlank() || targetAway.isBlank()) continue
                val sameTeams = normalizedTeamName(match.optString("home")) == targetHome &&
                    normalizedTeamName(match.optString("away")) == targetAway
                if (!sameTeams) continue
                namesOnlyFallback = match
                namesOnlyCount++
                val matchDate = match.optString("utcDate").take(10).ifBlank {
                    match.optString("dateEvent").take(10)
                }
                if (targetDate.isNotBlank() && matchDate == targetDate) nameAndDateFallback = match
            }
        }
        return nameAndDateFallback ?: if (namesOnlyCount == 1) namesOnlyFallback else null
    }

    private fun settleBet(b: JSONObject, m: JSONObject): Boolean {
        val hasFinalFlag = m.has("scoreStatusFinal")
        val finalFlag = if (hasFinalFlag && !m.isNull("scoreStatusFinal"))
            m.optBoolean("scoreStatusFinal") else null
        if (b.optString("status") != "PENDING" ||
            !BetSettlement.isConfirmedFinal(m.optString("status"), hasFinalFlag, finalFlag) ||
            m.isNull("homeScore") || m.isNull("awayScore")) return false
        val h = m.optInt("homeScore")
        val a = m.optInt("awayScore")
        val result = BetSettlement.result(b.optString("marketKey"), h, a) ?: return false
        b.put("status", result)
            .put("score", "$h:$a")
            .put("matchStatus", "FINISHED")
        return true
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

    private fun displayLeagueName(raw: String): String {
        return when (raw.trim().uppercase(Locale.ROOT)) {
            "ENG", "PL", "EPL", "PREMIER LEAGUE", "ENGLAND · PREMIER LEAGUE", "АНГЛИЯ · ПРЕМЬЕР-ЛИГА" -> "АПЛ"
            "ESP", "PD", "LL", "LA LIGA", "SPAIN · LA LIGA", "ИСПАНИЯ · ЛА ЛИГА" -> "Ла Лига"
            "ITA", "SA", "SERIE A", "ITALY · SERIE A", "ИТАЛИЯ · СЕРИЯ A" -> "Серия А"
            "GER", "BL1", "BUNDESLIGA", "GERMANY · BUNDESLIGA", "ГЕРМАНИЯ · БУНДЕСЛИГА" -> "Бундеслига"
            "FRA", "FL1", "LIGUE 1", "FRANCE · LIGUE 1", "ФРАНЦИЯ · ЛИГА 1" -> "Лига 1"
            "UCL", "UEFA CHAMPIONS LEAGUE", "ЛИГА ЧЕМПИОНОВ УЕФА" -> "ЛЧ"
            "UEL", "UEFA EUROPA LEAGUE", "ЛИГА ЕВРОПЫ УЕФА" -> "ЛЕ"
            "UECL", "UEFA CONFERENCE LEAGUE", "ЛИГА КОНФЕРЕНЦИЙ УЕФА" -> "ЛК"
            else -> raw.ifBlank { "Турнир не указан" }
        }
    }

    private fun renderBetHistory(j: JSONObject) {
        screenHeader("BET TRACKER", "Ставки", "Открытые и завершённые бумажные ставки.")
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

        val openBets = mutableListOf<JSONObject>()
        val completedBets = mutableListOf<JSONObject>()
        for (i in bets.length() - 1 downTo 0) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("status") == "PENDING") openBets.add(bet) else completedBets.add(bet)
        }

        fun renderBetSection(title: String, items: List<JSONObject>, emptyText: String) {
            if (items.isEmpty()) {
                val emptySection = card()
                addText(emptySection, emptyText, 11, false, muted)
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
                addText(box, displayLeagueName(bet.optString("league")) + " · " + date(bet.optString("date")), 10, false, muted)
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
                    // Keep the visible position stable when a bet is removed.
                    val previousScrollY = mainScrollView.scrollY
                    bets = next
                    saveBets(preserveBackgroundAuto = false)
                    // The cached history view contains the deleted row; force a fresh render.
                    tabViewCache.remove("history|" + selectedHistoryTab)
                    render()
                    mainScrollView.post {
                        mainScrollView.scrollTo(0, previousScrollY)
                    }
                }
                box.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }

        val historyTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("ОЖИДАЮТ" to "Ожидает расчёта", "COMPLETED" to "Завершённые").forEach { (key, title) ->
            val count = if (key == "ОЖИДАЮТ") openBets.size else completedBets.size
            val tab = TextView(this).apply {
                text = "$title · $count"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(if (selectedHistoryTab == key) Color.rgb(28, 31, 36) else muted)
                background = if (selectedHistoryTab == key)
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))).apply { cornerRadius = dp(28).toFloat() }
                else rounded(surface, 28)
                setOnClickListener {
                    if (selectedHistoryTab == key) return@setOnClickListener
                    // Keep the outer page at the same vertical position when
                    // switching between Open and Completed history subtabs.
                    val previousScrollY = mainScrollView.scrollY
                    selectedHistoryTab = key
                    matchesContainer.animate().cancel()
                    render()
                    mainScrollView.post {
                        mainScrollView.scrollTo(0, previousScrollY)
                        matchesContainer.alpha = 1f
                        matchesContainer.translationX = 0f
                    }
                }
            }
            historyTabs.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(8), dp(7), dp(12)) })
        }
        matchesContainer.addView(historyTabs)
        if (selectedHistoryTab == "ОЖИДАЮТ") {
            renderBetSection("ОТКРЫТЫЕ СТАВКИ", openBets, "Ставок, ожидающих расчёта, пока нет.")
        } else {
            renderBetSection("ЗАВЕРШЁННЫЕ", completedBets, "Завершённых ставок пока нет.")
        }
    }

    private fun renderHelp() {
        screenHeader("IQ MATCH · SETTINGS", "Настройки", "Настройки приложения и параметров ставок.")

        val bank = currentBankroll()
        val bankrollCard = card()
        addText(bankrollCard, "ТЕКУЩИЙ БАЛАНС", 10, true, cyan)
        addText(bankrollCard, if (hasInitialBankroll()) String.format(Locale.US, "%.2f ₽", bank) else "Не задан", 28, true, Color.WHITE)
        addText(bankrollCard, if (hasInitialBankroll()) "Баланс учитывает стартовый банк, чистый результат рассчитанных ставок и суммы, зарезервированные по ожидающим ставкам." else "Задай стартовый банк в профиле, чтобы рассчитывать размер ставок и текущий баланс.", 11, false, muted)
        actionButton(bankrollCard, if (hasInitialBankroll()) "Изменить баланс" else "Задать баланс") {
            showBankSettingDialog("initial_bankroll", "Баланс", initialBankroll(), "Укажи стартовый баланс. Пока он не задан, новые ставки ТОП автоматически не фиксируются. Записанные ставки и результаты сохраняются.")
        }

        val settings = card()
        addText(settings, "НАСТРОЙКИ СУММЫ СТАВОК", 10, true, accent)
        addText(settings, "РЕЖИМ РАСЧЁТА СТАВКИ", 11, true, Color.WHITE)
        val stakeModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stakeModeButtons = linkedMapOf<String, Button>()
        lateinit var fixedStakeHint: TextView
        lateinit var minimumStakeHint: TextView
        fun styleStakeModeButton(button: Button, selected: Boolean) {
            button.setTextColor(if (selected) Color.rgb(28, 22, 13) else Color.WHITE)
            button.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (selected) intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))
                else intArrayOf(Color.rgb(25, 42, 63), Color.rgb(17, 29, 47))
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (selected) accent else Color.rgb(55, 72, 93))
            }
        }
        fun addStakeModeButton(title: String, mode: String) {
            val button = Button(this).apply {
                text = title
                textSize = 11f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(44)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                styleStakeModeButton(this, stakeMode() == mode)
                setOnClickListener {
                    // Persist synchronously before changing the visible selection.
                    if (!settingsPrefs.edit().putString("stake_mode", mode).commit()) {
                        Toast.makeText(this@MainActivity, "Не удалось сохранить режим ставки", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    stakeModeButtons.forEach { (key, value) -> styleStakeModeButton(value, key == mode) }
                    fixedStakeHint.text = if (mode == "FIXED") {
                        if (fixedStake() > 0.0) "Активна: каждая новая ставка будет ровно ${fmt(fixedStake())} ₽."
                        else "Режим выбран, но сумма не задана. Укажи фиксированную сумму ниже."
                    } else "Сохранённое значение не применяется, пока выбран режим «Минимальная»."
                    minimumStakeHint.text = if (mode == "MINIMUM") minimumStakeDescription()
                        else "Сохранённое значение не применяется, пока выбран режим «Фиксированная»."
                    // Invalidate dependent tabs for their next visit without rebuilding this page.
                    tabViewCache.clear()
                    Toast.makeText(this@MainActivity,
                        if (mode == "FIXED") "Фиксированная сумма активна" else "Минимальный режим активен",
                        Toast.LENGTH_SHORT).show()
                }
            }
            stakeModeButtons[mode] = button
            stakeModeRow.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (mode == "FIXED") leftMargin = dp(8)
            })
        }
        addStakeModeButton("Минимальная", "MINIMUM")
        addStakeModeButton("Фиксированная", "FIXED")
        fixedStakeHint = label(if (stakeMode() == "FIXED") {
            if (fixedStake() > 0.0) "Активна: каждая новая ставка будет ровно ${fmt(fixedStake())} ₽."
            else "Режим выбран, но сумма не задана. Укажи фиксированную сумму ниже."
        } else "Сохранённое значение не применяется, пока выбран режим «Минимальная».", 11, false, muted)
        minimumStakeHint = label(if (stakeMode() == "MINIMUM") minimumStakeDescription()
            else "Сохранённое значение не применяется, пока выбран режим «Фиксированная».", 11, false, muted)
        settings.addView(stakeModeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        settings.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) })
        addText(settings, "Минимальная сумма ставки", 12, true, Color.WHITE)
        addText(settings, if (minimumStake() > 0.0) fmt(minimumStake()) + " ₽" else "Не задана", 13, false, muted)
        settings.addView(minimumStakeHint)
        actionButton(settings, "Изменить минимальную сумму ставки") {
            showBankSettingDialog("minimum_stake", "Минимальная сумма ставки", minimumStake(), "Укажи минимум для процентного режима. Введи 0, чтобы снять минимум.")
        }
        val settingsSectionGap = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) }
        settings.addView(settingsSectionGap)
        addText(settings, "Фиксированная сумма ставки", 12, true, Color.WHITE)
        addText(settings, if (fixedStake() > 0.0) fmt(fixedStake()) + " ₽ за ставку" else "Не задана", 13, false, muted)
        settings.addView(fixedStakeHint)
        actionButton(settings, if (fixedStake() > 0.0) "Изменить фиксированную сумму" else "Задать фиксированную сумму") {
            showBankSettingDialog("fixed_stake", "Фиксированная сумма ставки", fixedStake(), "Укажи сумму в рублях для каждой новой ставки. Введи 0, чтобы очистить значение.")
        }

        val displaySettings = card()
        addText(displaySettings, "ОТОБРАЖЕНИЕ РАЗДЕЛОВ", 10, true, accent)
        val showLine = CheckBox(this).apply {
            text = "Показывать раздел «ЛИНИЯ»"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = isLineSectionVisible()
            includeFontPadding = false
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.rgb(105, 116, 130))
            )
            setPadding(0, dp(5), 0, dp(5))
            setOnCheckedChangeListener { _, checked ->
                settingsPrefs.edit().putBoolean("show_line_section", checked).apply()
                // Update only navigation. Rebuilding the settings screen here
                // detached/recreated its content and caused a visible scroll jump.
                if (!checked && currentTab == "matches") {
                    selectTab("desk")
                } else {
                    renderTabs()
                }
            }
        }
        displaySettings.addView(showLine, LinearLayout.LayoutParams(-1, -2))
        addText(displaySettings, "Если выключить, вкладка ЛИНИЯ исчезнет из навигации, а остальные вкладки автоматически займут освободившееся место.", 11, false, muted)

        val glossary = card()
        addText(glossary, "СЛОВАРЬ ПОКАЗАТЕЛЕЙ", 10, true, cyan)
        addText(glossary, "Вероятность модели", 13, true, Color.WHITE)
        addText(glossary, "Оценка модели, что выбранный исход наступит. Например, 65% означает оценочную вероятность 65 из 100 аналогичных случаев, а не гарантию конкретного результата.", 11, false, muted)
        addText(glossary, "Коэффициент", 13, true, Color.WHITE)
        addText(glossary, "Коэффициент MelBet показывает выплату вместе с возвращаемой суммой ставки. Чем выше коэффициент, тем ниже заложенная букмекером вероятность исхода.", 11, false, muted)
        addText(glossary, "Вероятность по коэффициенту", 13, true, Color.WHITE)
        addText(glossary, "Обратная величина коэффициента: 1 / коэффициент. Например, при 2.00 это 50% до учёта маржи букмекера.", 11, false, muted)
        addText(glossary, "EV — ожидаемая доходность", 13, true, Color.WHITE)
        addText(glossary, "Формула модели: вероятность × коэффициент − 1. EV +5% означает математическое ожидание прибыли 5 копеек на каждый рубль ставки на большой дистанции; это не прогноз прибыли в одном матче.", 11, false, muted)
        addText(glossary, "EDGE — преимущество модели", 13, true, Color.WHITE)
        addText(glossary, "Разница между вероятностью модели и вероятностью, заложенной в коэффициенте, в процентных пунктах. Например, 65% против 55% — EDGE около +10 п.п. В ТОП дополнительно учитывается корректировка формы там, где она предусмотрена.", 11, false, muted)
        addText(glossary, "Kelly — критерий размера/ранжирования", 13, true, Color.WHITE)
        addText(glossary, "Доля полного банка по формуле Келли: EV / (коэффициент − 1). В нашей Линии и ТОП этот показатель используется для ранжирования сигналов. Это доля полного Kelly, а не указание автоматически ставить весь рассчитанный процент банка.", 11, false, muted)
        addText(glossary, "xG — ожидаемые голы", 13, true, Color.WHITE)
        addText(glossary, "Оценка качества созданных голевых моментов и ожидаемого количества голов. Это модельная метрика, а не фактический счёт.", 11, false, muted)
        addText(glossary, "Порог отбора ТОП", 13, true, Color.WHITE)
        addText(glossary, "Для попадания сигнала в ТОП нужны одновременно: вероятность от 60%, EV от +5%, EDGE от +5 п.п. и коэффициент MelBet от 1.50. Также действуют ограничения по допустимым рынкам.", 11, false, muted)
        addText(glossary, "Важно: вероятность, EV, EDGE, xG и Kelly — расчётные оценки. Они помогают сравнивать варианты, но не гарантируют выигрыш.", 11, true, gold)

        val info = card()
        addText(info, "О ПРИЛОЖЕНИИ", 10, true, accent)
        addText(info, "IQ MATCH · SMART FOOTBALL INTELLIGENCE", 13, true, Color.WHITE)
        addText(info, "Матчи и коэффициенты: API-Sport / MelBet. Модельные вероятности и xG: PitchAPI.", 11, false, muted)
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
        if (listOf("ТБ 1.5", "ТБ 2.5").any { side.equals(it, ignoreCase = true) }) return side
        if (side.startsWith("ИТБ ", ignoreCase = true)) {
            val point = Regex("ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(side)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val team = side.substringAfter("·", "").trim().replace(Regex("\\s+"), " ")
            val home = match.optString("home").trim().replace(Regex("\\s+"), " ")
            val away = match.optString("away").trim().replace(Regex("\\s+"), " ")
            if (point in listOf(0.5, 1.5) &&
                (team.equals(home, ignoreCase = true) || team.equals(away, ignoreCase = true))) return side
        }
        return null
    }

    // Choose the strongest model forecast with an exact MelBet quote in this match.
    // This is descriptive match-line ranking, not the stricter AI betting filter.
    private fun strongestDisplaySignal(prediction: JSONObject, match: JSONObject, aiEligibleOnly: Boolean = false): JSONObject? {
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
            "homeOver05" to "ИТБ 0.5 · ${match.optString("home")}",
            "homeOver15" to "ИТБ 1.5 · ${match.optString("home")}",
            "awayOver05" to "ИТБ 0.5 · ${match.optString("away")}",
            "awayOver15" to "ИТБ 1.5 · ${match.optString("away")}"
        )
        rawMarkets.forEach { (field, side) ->
            val probability = prediction.optDouble(field, Double.NaN)
            if (!probability.isFinite() || probability !in 0.0..1.0) return@forEach
            val existing = candidates.firstOrNull {
                it.optString("side").trim().replace(Regex("\\s+"), " ")
                    .equals(side.trim().replace(Regex("\\s+"), " "), ignoreCase = true)
            }
            if (existing == null) {
                candidates.add(JSONObject().put("side", side).put("modelProbability", probability))
            } else if (!existing.optDouble("modelProbability", Double.NaN).isFinite()) {
                existing.put("modelProbability", probability)
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
        // AI filtering must happen before ranking. Otherwise a high-Kelly market
        // below the probability/edge thresholds can hide another valid market.
        val eligible = if (aiEligibleOnly) available.filter { candidate ->
            val probability = candidate.optDouble("modelProbability", Double.NaN)
            val odds = candidate.optDouble("lineOdds", Double.NaN)
            val ev = candidate.optDouble("lineExpectedValue", Double.NaN)
            val edgePp = if (candidate.optBoolean("paperOnly")) {
                candidate.optDouble("edgePp", Double.NaN)
            } else {
                (probability - 1.0 / odds) * 100.0 + candidate.optDouble("formAdjustmentPp", 0.0)
            }
            probability.isFinite() && probability >= 0.60 &&
                odds.isFinite() && odds >= 1.50 &&
                ev.isFinite() && ev >= 0.05 &&
                edgePp.isFinite() && edgePp >= 5.0
        } else available
        val positiveValue = eligible.filter { it.optDouble("lineExpectedValue", -1.0) > 0.0 }
        return if (positiveValue.isNotEmpty()) {
            positiveValue.maxByOrNull { it.optDouble("lineKellyFraction", Double.NEGATIVE_INFINITY) }
        } else {
            eligible.maxByOrNull { it.optDouble("modelProbability", Double.NEGATIVE_INFINITY) }
        }
    }

    // Quotes older than the strategy's 12-hour limit are never eligible.
    // The timestamp is generated with the bookmaker odds snapshot, not the feed refresh time.
    private fun hasFreshMelBetQuote(match: JSONObject): Boolean {
        val odds = match.optJSONObject("odds") ?: return false
        val bookmaker = odds.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true)
            else odds.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet) return false
        if (!odds.optBoolean("prematchActive", false)) return false
        if (!odds.optString("freshnessStatus").equals("fresh", ignoreCase = true)) return false
        val raw = odds.optString("updatedAt").trim()
        if (raw.isBlank()) return false
        val normalizedTimestamp = raw.replace(Regex("\\.(\\d{3})\\d+(?=[+-]|Z$)"), ".$1")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        val timestamp = patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(normalizedTimestamp)?.time
            } catch (_: Exception) { null }
        } ?: return false
        val age = System.currentTimeMillis() - timestamp
        return age >= -5 * 60 * 1000L && age <= 12 * 60 * 60 * 1000L
    }

    private fun topSignalOdds(signal: JSONObject, match: JSONObject, minOdds: Double = 1.50): Double? {
        val side = signal.optString("side").trim()
        val oddsBoard = match.optJSONObject("odds") ?: return null
        val bookmaker = oddsBoard.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true) else oddsBoard.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet || !hasFreshMelBetQuote(match)) return null
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
        // A match needs a usable probability for at least one market in our active
        // strategy. Missing probabilities for other strategy markets must not hide it.
        // Markets outside this list (1X2, handicaps, BTTS NO, higher totals) are ignored.
        val probabilityFields = listOf(
            "1x", "x2", "over15", "over25",
            "homeOver05", "homeOver15", "awayOver05", "awayOver15",
            "bttsYes"
        )
        return probabilityFields.any { field ->
            val value = prediction.optDouble(field, Double.NaN)
            value.isFinite() && value in 0.0..1.0
        }
    }

    // Eligibility requires at least one strategy market with BOTH a valid model probability
    // and a fresh, active MelBet price for that exact market and line.
    private fun hasStrategyPredictionWithActiveMelBetLine(match: JSONObject): Boolean {
        val prediction = match.optJSONObject("prediction") ?: return false
        val odds = match.optJSONObject("odds") ?: return false
        val bookmaker = odds.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true)
            else odds.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet || !hasFreshMelBetQuote(match)) return false
        val best = odds.optJSONObject("best") ?: return false
        fun hasProbability(field: String): Boolean {
            val value = prediction.optDouble(field, Double.NaN)
            return value.isFinite() && value in 0.0..1.0
        }
        fun validPrice(value: Double) = value.isFinite() && value > 1.0

        val dc = best.optJSONObject("doubleChance")
        if (dc != null && ((hasProbability("1x") && validPrice(dc.optDouble("1x", Double.NaN))) ||
                (hasProbability("x2") && validPrice(dc.optDouble("x2", Double.NaN))))) return true
        val btts = best.optJSONObject("btts")
        if (hasProbability("bttsYes") && btts != null && validPrice(btts.optDouble("yes", Double.NaN))) return true

        fun hasTotal(field: String, probabilityField: String, point: Double): Boolean {
            if (!hasProbability(probabilityField)) return false
            val rows = best.optJSONArray(field) ?: return false
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val line = row.optDouble("point", Double.NaN)
                if (row.optString("side").equals("over", ignoreCase = true) && line.isFinite() &&
                    kotlin.math.abs(line - point) < 0.001 && validPrice(row.optDouble("price", Double.NaN))) return true
            }
            return false
        }
        if (hasTotal("totals", "over15", 1.5) || hasTotal("totals", "over25", 2.5)) return true

        val teamRows = best.optJSONArray("teamTotals")
        if (teamRows != null) {
            val homeName = match.optString("home").trim()
            val awayName = match.optString("away").trim()
            for (i in 0 until teamRows.length()) {
                val row = teamRows.optJSONObject(i) ?: continue
                if (!row.optString("side").equals("over", ignoreCase = true)) continue
                val point = row.optDouble("point", Double.NaN)
                val team = row.optString("team").trim()
                val isHome = team.equals("home", true) || team == "1" || team.equals("хозяева", true) || team.equals(homeName, true)
                val isAway = team.equals("away", true) || team == "2" || team.equals("гости", true) || team.equals(awayName, true)
                val price = row.optDouble("price", Double.NaN)
                if (!validPrice(price) || !point.isFinite()) continue
                if (isHome && ((kotlin.math.abs(point - 0.5) < 0.001 && hasProbability("homeOver05")) ||
                        (kotlin.math.abs(point - 1.5) < 0.001 && hasProbability("homeOver15")))) return true
                if (isAway && ((kotlin.math.abs(point - 0.5) < 0.001 && hasProbability("awayOver05")) ||
                        (kotlin.math.abs(point - 1.5) < 0.001 && hasProbability("awayOver15")))) return true
            }
        }
        return false
    }

    private fun hasActiveMelBetPrematchLine(match: JSONObject): Boolean {
        val odds = match.optJSONObject("odds") ?: return false
        val bookmaker = odds.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true) else odds.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet || !hasFreshMelBetQuote(match)) return false

        val best = odds.optJSONObject("best") ?: return false
        fun validPrice(value: Double): Boolean = value.isFinite() && value > 1.0
        val doubleChance = best.optJSONObject("doubleChance")
        if (doubleChance != null &&
            listOf("1x", "x2").any { validPrice(doubleChance.optDouble(it, Double.NaN)) }) return true

        val btts = best.optJSONObject("btts")
        if (btts != null && validPrice(btts.optDouble("yes", Double.NaN))) return true

        fun hasOverQuote(field: String, allowedPoints: Set<Double>): Boolean {
            val rows = best.optJSONArray(field) ?: return false
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val point = row.optDouble("point", Double.NaN)
                if (row.optString("side").equals("over", ignoreCase = true) &&
                    allowedPoints.any { kotlin.math.abs(point - it) < 0.001 } &&
                    validPrice(row.optDouble("price", Double.NaN))) return true
            }
            return false
        }
        return hasOverQuote("totals", setOf(1.5, 2.5)) || hasOverQuote("teamTotals", setOf(0.5, 1.5))
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

    private fun minimumStake(): Double = settingsPrefs.getString("minimum_stake", null)
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private fun fixedStake(): Double = settingsPrefs.getString("fixed_stake", null)
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    // Keep the two stake rules independent; legacy installs with a saved fixed amount
    // continue in fixed mode until the user explicitly selects another mode.
    private fun stakeMode(): String = settingsPrefs.getString("stake_mode", null)
        ?: if (fixedStake() > 0.0) "FIXED" else "MINIMUM"

    private fun calculatedStake(): Double = when (stakeMode()) {
        "FIXED" -> fixedStake()
        else -> maxOf(currentBankroll() * 0.01, minimumStake())
    }

    private fun minimumStakeDescription(): String = if (minimumStake() > 0.0)
        "Активный расчёт: 1% от текущего баланса, но не менее ${fmt(minimumStake())} ₽."
    else "Активный расчёт: 1% от текущего баланса; пользовательский минимум не задан."

    private fun stakeRuleDescription(): String = when (stakeMode()) {
        "FIXED" -> if (fixedStake() > 0.0) "фиксированная сумма ${fmt(fixedStake())} ₽ на каждую новую ставку; процент и минимум не применяются."
            else "фиксированный режим выбран, но сумма не задана — новые ставки не фиксируются."
        else -> minimumStakeDescription()
    }

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
                if (key == "initial_bankroll" || key == "minimum_stake" || key == "fixed_stake") {
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
            if (bet.optString("source") != "AI_MANUAL") continue
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
        headerAiRoiView.text = if (roi == null) "ТОП ROI —" else String.format(Locale.US, "ТОП ROI %+.1f%% · %d", roi, count)
        headerAiRoiView.setTextColor(if (roi == null) muted else if (roi >= 0.0) accent else gold)
    }

    private fun renderNotificationCenter(json: JSONObject) {
        screenHeader("IQ MATCH · ACTIVITY", "Центр уведомлений", "Важные события ТОП, расчёты ставок и обновления данных.")
        val allEvents = NotificationCenter.events(this)
        val unread = NotificationCenter.unreadCount(this)
        val summary = card()
        addText(summary, "СОБЫТИЯ · ${allEvents.length()}", 10, true, cyan)
        addText(summary, if (unread > 0) "Непрочитанных: $unread" else "Все события прочитаны", 14, true, Color.WHITE)
        val markAll = TextView(this).apply {
            text = "Отметить все прочитанными"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            setPadding(0, dp(8), 0, dp(2))
            isClickable = true
            setOnClickListener {
                NotificationCenter.markAllRead(this@MainActivity)
                settingsPrefs.edit().putBoolean(SectionNotifications.UNREAD_KEY, false).apply()
                tabViewCache.clear()
                renderedKey = null
                render()
            }
        }
        summary.addView(markAll)
        matchesContainer.addView(summary)

        if (allEvents.length() == 0) {
            val empty = card()
            addText(empty, "Пока нет событий", 15, true, Color.WHITE)
            addText(empty, "Здесь появятся новые матчи ТОП, расчёты ставок и важные изменения.", 12, false, muted)
            matchesContainer.addView(empty)
            return
        }

        for (i in 0 until allEvents.length()) {
            val event = allEvents.optJSONObject(i) ?: continue
            val type = event.optString("type")
            val title = event.optString("title")
            val message = event.optString("message")
            val isRead = event.optBoolean("read", false)
            val created = event.optLong("createdAt")
            val time = if (created > 0L) SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(created)) else ""
            val item = card()
            item.background = GradientDrawable().apply {
                setColor(if (isRead) panel else Color.rgb(22, 43, 62))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (isRead) Color.rgb(35, 55, 76) else Color.rgb(47, 111, 119))
            }
            val typeLabel = when (type) {
                "ai_match" -> "МАТЧ ТОП"
                "bet_settlement" -> "РАСЧЁТ СТАВКИ"
                "report" -> "ОТЧЁТ И НАБЛЮДЕНИЯ"
                "odds_change" -> "КОЭФФИЦИЕНТЫ"
                else -> "СОБЫТИЕ"
            }
            addText(item, (if (isRead) "○  " else "●  ") + typeLabel + if (time.isNotBlank()) " · $time" else "", 9, true, if (isRead) muted else accent)
            addText(item, title, 14, true, Color.WHITE)
            addText(item, message, 12, false, muted)
            item.isClickable = true
            item.setOnClickListener {
                val destination = event.optString("targetTab", "notifications")
                if (destination != "notifications" && destination in listOf("desk", "history", "matches", "stats")) {
                    currentTab = destination
                    tabViewCache.clear()
                    renderedKey = null
                    render()
                }
            }
            matchesContainer.addView(item)
        }
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

    private fun confirmManualAiSignalBet(match: JSONObject, league: String, signal: JSONObject, odds: Double, onRecorded: (() -> Unit)? = null) {
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
        val stake = calculatedStake()
        if (stake <= 0.0) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Зафиксировать ставку ТОП?")
            .setMessage("Матч: ${match.optString("home")} — ${match.optString("away")}\nРынок: ${signal.optString("side")}\nКоэффициент MelBet: ${fmt(odds)}\nСумма ставки: ${fmt(stake)} ₽\n\nБумажная ставка — запись в истории приложения. Букмекеру ничего не отправляется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Зафиксировать", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Close the confirmation immediately; disk persistence must not block the dialog animation.
            dialog.dismiss()
            val now = System.currentTimeMillis()
            val bet = JSONObject()
                .put("id", id).put("source", "AI_MANUAL").put("origin", "TOP_SIGNAL").put("status", "PENDING")
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
            Thread(Runnable {
                saveBets()
                runOnUiThread {
                    Toast.makeText(this, "Ставка ТОП добавлена в историю", Toast.LENGTH_SHORT).show()
                    onRecorded?.invoke()
                    // Do not rebuild the active screen here: a full render can block
                    // scrolling and taps after the confirmation dialog has closed.
                    // saveBets() invalidates the History caches; History renders fresh
                    // when the user opens that tab.
                }
            }, "save-manual-ai-bet").start()
        }
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
                // Apply AI thresholds to every available market before ranking; the Line
                // display leader is descriptive and may legitimately fail the AI filters.
                val signal = strongestDisplaySignal(prediction, match, aiEligibleOnly = true) ?: continue
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
        var settledOddsTotal = 0.0
        var settledOddsCount = 0
        for (i in 0 until archive.length()) {
            val row = archive.optJSONObject(i) ?: continue
            when (row.optString("status")) {
                "WON" -> {
                    wins++
                    profitUnits += row.optDouble("profitUnits", 0.0)
                    val price = row.optDouble("price", 0.0)
                    if (price > 1.0) { settledOddsTotal += price; settledOddsCount++ }
                }
                "LOST" -> {
                    losses++
                    profitUnits += row.optDouble("profitUnits", 0.0)
                    val price = row.optDouble("price", 0.0)
                    if (price > 1.0) { settledOddsTotal += price; settledOddsCount++ }
                }
                "PENDING" -> pending++
            }
        }
        val settled = wins + losses
        val roi = if (settled > 0) String.format(Locale.US, "%.1f%%", profitUnits / settled * 100.0) else "—"
        val averageOdds = if (settledOddsCount > 0) String.format(Locale.US, "%.2f", settledOddsTotal / settledOddsCount) else "—"
        val summary = card()
        addText(summary, "TOP · РЕЗУЛЬТАТ РЕКОМЕНДАЦИЙ", 10, true, cyan)
        addText(summary, "Зафиксировано: ${archive.length()} · W $wins / L $losses · ожидают: $pending", 12, true, Color.WHITE)
        addText(summary, "ROI: $roi · средний коэффициент: $averageOdds · расчёт по 1 условной единице на каждый сигнал", 10, false, muted)
        if (archive.length() == 0) addText(summary, "История начнёт накапливаться после появления первых сигналов с котировкой MelBet.", 10, false, muted)
    }

    private fun renderBettingDesk(json: JSONObject) {
        screenHeader("ТОП · MATCH INTELLIGENCE", "ТОП", "Новые сильные сигналы отдельно от уже зафиксированных ставок ТОП.")
        val sorted = if (aiCandidatesCacheKey == statsCacheKey(json)) {
            aiCandidatesCache
        } else {
            collectAiCandidates(json)
        }
        val openCandidates = sorted.mapNotNull { item ->
            val match = item.first
            val bundle = item.third
            val available = mutableListOf<JSONObject>()
            bundle.optJSONObject("primary")?.let { available.add(it) }
            val alternatives = bundle.optJSONArray("alternatives") ?: JSONArray()
            for (i in 0 until alternatives.length()) alternatives.optJSONObject(i)?.let { available.add(it) }
            val notRecorded = available.filterNot { isAiBetRecorded(match, it) }
            if (notRecorded.isEmpty()) null else Triple(item, notRecorded, available)
        }
        var openSignalsCount = openCandidates.sumOf { it.second.size }
        val openIntroCountView = label("НОВЫЕ СИГНАЛЫ · $openSignalsCount", 10, true, cyan)
        matchesContainer.addView(openIntroCountView)
            val emptyOpenCard = card().apply {
                addText(this, "Новых сильных сигналов пока нет.", 14, true, gold)
                addText(this, "Когда появятся матчи, прошедшие фильтры TOP, они будут показаны здесь.", 11, false, muted)
                if (openCandidates.isNotEmpty()) visibility = View.GONE
            }
            openCandidates.forEachIndexed { index, entry ->
                val (item, notRecorded, available) = entry
                val (match, league, bundle) = item
                val primary = bundle.optJSONObject("primary") ?: return@forEachIndexed
                val alternatives = bundle.optJSONArray("alternatives") ?: JSONArray()
                val box = card()
                addText(box, "МАТЧ #${index + 1} · ${league.uppercase(Locale.getDefault())}", 10, true, accent)
                addText(box, "${match.optString("home")} — ${match.optString("away")}", 16, true)
                addText(box, kickoffLabel(match), 10, false, muted)
                addText(box, "САМЫЙ СИЛЬНЫЙ СИГНАЛ", 10, true, cyan)
                val statusViews = linkedMapOf<JSONObject, TextView>()
                if (!isAiBetRecorded(match, primary)) statusViews[primary] = addAiSignalDetails(box, primary, match, true)
                if (alternatives.length() > 0) {
                    val unrecordedAlternatives = (0 until alternatives.length()).mapNotNull { alternatives.optJSONObject(it) }
                        .filterNot { isAiBetRecorded(match, it) }
                    if (unrecordedAlternatives.isNotEmpty()) {
                        addText(box, "АЛЬТЕРНАТИВЫ · ПРОШЛИ ФИЛЬТРЫ", 10, true, cyan)
                        unrecordedAlternatives.forEach { alt -> statusViews[alt] = addAiSignalDetails(box, alt, match, false) }
                    }
                }
                lateinit var recordButton: Button
                recordButton = actionButton(box, if (notRecorded.size == 1) "Выбрать и зафиксировать" else "Выбрать исход для истории") {
                    val remaining = available.filterNot { isAiBetRecorded(match, it) }
                    if (remaining.isEmpty()) {
                        recordButton.text = "✓ Все сигналы записаны"
                        recordButton.isEnabled = false
                        return@actionButton
                    }
                    val onRecorded: (JSONObject) -> Unit = { signal ->
                        statusViews[signal]?.apply {
                            text = "Ожидает расчёт"
                            setTextColor(accent)
                        }
                        // Update the visible TOP lists and counters in place; avoid a full
                        // Activity render so the screen stays responsive after saving.
                        recordButton.text = "✓ Ставка зафиксирована"
                        recordButton.isEnabled = false
                        openSignalsCount = (openSignalsCount - 1).coerceAtLeast(0)
                        openIntroCountView.text = "НОВЫЕ СИГНАЛЫ · $openSignalsCount"
                        val stillOpenInMatch = statusViews.keys.count { it !== signal && !isAiBetRecorded(match, it) }
                        if (stillOpenInMatch == 0) box.visibility = View.GONE
                        if (openSignalsCount == 0) {
                            emptyOpenCard?.visibility = View.VISIBLE
                        }
                        tabViewCache.remove("desk|OPEN")
                    }
                    if (remaining.size == 1) {
                        val signal = remaining.first()
                        val odds = topSignalOdds(signal, match) ?: return@actionButton
                        confirmManualAiSignalBet(match, league, signal, odds) { onRecorded(signal) }
                    } else {
                        val labels = remaining.map { signal ->
                            val odds = topSignalOdds(signal, match) ?: 0.0
                            "${signal.optString("side")} · ${fmt(odds)} · EV ${String.format(Locale.US, "%+.1f", (signal.optDouble("modelProbability") * odds - 1.0) * 100)}%"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Выбери рынок для бумажной ставки")
                            .setItems(labels) { _, which ->
                                val signal = remaining[which]
                                val odds = topSignalOdds(signal, match) ?: return@setItems
                                confirmManualAiSignalBet(match, league, signal, odds) { onRecorded(signal) }
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
        val footer = card()
        addText(footer, "ПРАВИЛА ТОП", 10, true, accent)
        addText(footer, "В TOP попадают сильнейшие сигналы из раздела Линия: MelBet ≥1.50, вероятность модели ≥60%, edge ≥5 п.п. и EV ≥5%. Фиксация в TOP сохраняет бумажную ставку в истории.", 11, false, muted)
    }

    private fun addAiSignalDetails(box: LinearLayout, signal: JSONObject, match: JSONObject, primary: Boolean): TextView {
        val odds = topSignalOdds(signal, match)
        val probability = signal.optDouble("modelProbability", 0.0)
        val ev = if (odds != null) probability * odds - 1.0 else Double.NaN
        val edge = if (odds != null) {
            if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", 0.0)
            else (probability - 1.0 / odds) * 100.0 + signal.optDouble("formAdjustmentPp", 0.0)
        } else Double.NaN
        addText(box, "${if (primary) "★ " else "• "}${signal.optString("side")} · MelBet ${odds?.let { fmt(it) } ?: "—"}", 14, true, if (primary) gold else Color.WHITE)
        addText(box, "Вероятность модели: ${String.format(Locale.US, "%.1f", probability * 100)}% · Edge: ${if (edge.isFinite()) String.format(Locale.US, "%+.1f", edge) else "—"} п.п. · EV: ${if (ev.isFinite()) String.format(Locale.US, "%+.1f", ev * 100) else "—"}%", 11, false, muted)
        if (signal.optBoolean("paperOnly")) {
            addText(box, "Серверный UEFA paper-сигнал", 10, false, muted)
        } else {
            addText(box, "Историческая база: ${String.format(Locale.US, "%.1f", signal.optDouble("baselineProbability") * 100)}% · ${signal.optInt("baselineMatches")} матчей", 10, false, muted)
        }
        val recorded = isAiBetRecorded(match, signal)
        return label(if (recorded) "Ожидает расчёт" else "○ Не добавлено", 10, true, if (recorded) accent else muted).also { box.addView(it) }
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
        addText(summary, "АВТОМАТИЧЕСКИЙ ОТБОР · СИГНАЛЫ ТОП", 9, true, accent)
        addText(summary, sorted.size.toString(), 28, true, Color.WHITE)
        addText(summary, "Допуск: p модели ≥60%, преимущество над break-even ≥5 п.п., EV ≥5% и реальная цена MelBet. Фильтра давности в 5 минут нет; бумажная запись не является реальной ставкой. Риск: ${stakeRuleDescription()} Разные рынки одного матча разрешены, повтор того же рынка блокируется; дневного лимита нет. Пауза при просадке 10%, остановка при 20%.", 11, false, muted)
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
        val targetKey = currentTab + "|" + when (currentTab) { "matches" -> selectedLeague; "history" -> selectedHistoryTab; "desk" -> selectedTopTab; else -> "ALL" }

        // A same-tab refresh (for example, after background statistics calculation)
        // must preserve the user's current scroll position instead of restoring zero.
        if (renderedKey == targetKey) {
            tabScrollPositions[targetKey] = mainScrollView.scrollY
        }

        // Снимаем текущие View с экрана и кладём в кеш, не уничтожая их.
        if (renderedKey != null && renderedKey != targetKey && matchesContainer.childCount > 0) {
            tabScrollPositions[renderedKey!!] = mainScrollView.scrollY
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
        if (currentTab == "matches") renderLeagueTabs()
        leagueBar.visibility = if (currentTab == "matches") View.VISIBLE else View.GONE

        // Возвращаем готовый экран мгновенно. Полная отрисовка нужна только при первом входе.
        val cached = tabViewCache[targetKey]
        if (cached != null) {
            cached.first.forEach { matchesContainer.addView(it) }
            cached.second.forEach { leagueBar.addView(it) }
            leagueBar.visibility = if (currentTab == "matches") View.VISIBLE else View.GONE
            renderedKey = targetKey
            mainScrollView.post {
                if (renderedKey == targetKey) {
                    mainScrollView.scrollTo(0, tabScrollPositions[targetKey] ?: 0)
                }
            }
            return
        }
        renderedKey = targetKey
        // Restore after the new children have been attached and measured. The
        // callback is guarded so a rapid second tap cannot scroll the next tab.
        mainScrollView.post {
            if (renderedKey == targetKey) {
                mainScrollView.scrollTo(0, tabScrollPositions[targetKey] ?: 0)
            }
        }

        if (currentTab == "day") {
            lineHeadingView.visibility = View.GONE
            leagueBar.visibility = View.GONE
            renderDay(json)
            return
        }
        if (currentTab == "notifications") {
            lineHeadingView.visibility = View.GONE
            leagueBar.visibility = View.GONE
            renderNotificationCenter(json)
            renderDailyInsights(json)
            return
        }
        if (currentTab == "desk") {
            lineHeadingView.visibility = View.GONE
            leagueBar.visibility = View.GONE
            renderBettingDesk(json)
            return
        }
        if (currentTab == "history") {
            lineHeadingView.visibility = View.GONE
            leagueBar.visibility = View.GONE
            renderBetHistory(json)
            return
        }
        if (currentTab == "stats") {
            leagueBar.visibility = View.GONE
            lineHeadingView.visibility = View.GONE
            try {
                renderStats(json)
            } catch (error: Throwable) {
                android.util.Log.e("IQMatchStats", "Unable to render statistics screen", error)
                matchesContainer.removeAllViews()
                val failure = card()
                addText(failure, "Не удалось открыть статистику", 15, true, Color.WHITE)
                addText(failure, "Ошибка обработана, приложение продолжает работать. Вернитесь на другой раздел и попробуйте снова.", 11, false, muted)
                matchesContainer.addView(failure)
            }
            return
        } else if (currentTab == "help") {
            lineHeadingView.visibility = View.GONE
            leagueBar.visibility = View.GONE
            renderHelp()
            return
        } else {
            lineHeadingView.visibility = View.VISIBLE
            leagueBar.visibility = View.VISIBLE
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
                // Preserve the original eligibility rule: only complete forecasts with a fresh MelBet prematch quote are shown.
                val matchStatus = m.optString("status")
                val fixtureDate = m.optString("utcDate").take(10).ifBlank {
                    m.optString("dateEvent").take(10).ifBlank {
                        kickoffTimestamp(m)?.let { fixtureDayFormat.format(Date(it)) }.orEmpty()
                    }
                }
                val isUpcoming = fixtureDate.isNotBlank() && fixtureDate >= fixtureToday
                if (matchStatus in listOf("SCHEDULED", "TIMED") && isUpcoming &&
                    hasStrategyPredictionWithActiveMelBetLine(m)) {
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
            matchesContainer.addView(section, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
            if (upcoming.isEmpty()) {
                val empty = card(content)
                addText(empty, "Нет предстоящих матчей в этом чемпионате на текущий момент.", 12, false, muted)
            }
            upcoming.sortedBy { it.optString("utcDate") }.forEach { m ->
                val box = card(content)
                // Match-only visual treatment: clearer hierarchy, softer navy gradient and a restrained gold outline.
                box.setPadding(dp(18), dp(18), dp(18), dp(18))
                box.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(23, 43, 66), Color.rgb(11, 25, 42), Color.rgb(9, 20, 35))
                ).apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.rgb(91, 78, 55))
                }
                box.elevation = dp(2).toFloat()
                (box.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = dp(10)
                    bottomMargin = dp(12)
                }
                addText(box, "★  MATCH FIXTURE  ·  ${date(m.optString("utcDate"))}", 9, true, gold)
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
                    signalBox.setPadding(dp(12), dp(12), dp(12), dp(12))
                    addText(signalBox, "★  САМЫЙ СИЛЬНЫЙ ПРОГНОЗ В МАТЧЕ", 10, true, gold)
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

                            fun metricCell(parent: LinearLayout, title: String, value: String, valueColor: Int = Color.WHITE) {
                                val cell = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dp(9), dp(7), dp(9), dp(7))
                                    background = rounded(Color.rgb(13, 29, 46), 12)
                                }
                                addText(cell, title, 9, true, muted)
                                addText(cell, value, 12, true, valueColor)
                                parent.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                            }
                            fun metricRow(leftTitle: String, leftValue: String, rightTitle: String, rightValue: String,
                                          leftColor: Int = Color.WHITE, rightColor: Int = Color.WHITE) {
                                val row = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                                metricCell(row, leftTitle, leftValue, leftColor)
                                metricCell(row, rightTitle, rightValue, rightColor)
                                (row.getChildAt(1).layoutParams as LinearLayout.LayoutParams).leftMargin = dp(7)
                                signalBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                            }
                            metricRow("ВЕРОЯТНОСТЬ МОДЕЛИ",
                                String.format(Locale.US, "%.1f%%", probability * 100),
                                "КОЭФФИЦИЕНТ MELBET",
                                if (odds.isFinite()) String.format(Locale.US, "%.2f", odds) else "—")
                            val evText = if (expectedValue.isFinite()) String.format(Locale.US, "%+.1f%%", expectedValue * 100.0) else "—"
                            val kelly = signal.optDouble("lineKellyFraction", Double.NaN)
                            metricRow("ОЖИДАЕМАЯ ДОХОДНОСТЬ (EV)", evText,
                                "ДОЛЯ KELLY ДЛЯ РАНЖИРОВАНИЯ",
                                if (positiveValue && kelly.isFinite()) String.format(Locale.US, "%.1f%%", kelly * 100.0) else "—",
                                if (positiveValue) accent else Color.rgb(242, 190, 104), Color.WHITE)
                            if (expectedValue.isFinite()) {
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
                    mainMarkets.setPadding(dp(12), dp(12), dp(12), dp(12))
                    addMarketTitle(mainMarkets, "!", "РЫНКИ И КОТИРОВКИ")
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
                    fun marketGroup(title: String, rows: List<String>): LinearLayout {
                        val group = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(9), dp(8), dp(9), dp(8))
                            background = rounded(Color.rgb(13, 29, 46), 12)
                        }
                        addText(group, title, 9, true, muted)
                        rows.forEach { addText(group, it, 10, true, Color.WHITE) }
                        return group
                    }
                    fun addMarketPair(left: LinearLayout, right: LinearLayout) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                        }
                        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
                        mainMarkets.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                    }
                    fun marketLine(title: String, probability: String, price: String): String =
                        "$title · $probability · кэф $price"

                    addMarketPair(
                        marketGroup("ДВОЙНОЙ ШАНС", listOf(
                            marketLine("1X", pct(pred, "1x"), quote("1x")),
                            marketLine("X2", pct(pred, "x2"), quote("x2"))
                        )),
                        marketGroup("ОБЕ КОМАНДЫ ЗАБЬЮТ", listOf(
                            marketLine("ОЗ ДА", pct(pred, "bttsYes"), quote("bttsYes"))
                        ))
                    )
                    addMarketPair(
                        marketGroup("ТОТАЛ МАТЧА", listOf(
                            marketLine("ТБ 1.5", pct(pred, "over15"), totalQuote(1.5, "over")),
                            marketLine("ТБ 2.5", pct(pred, "over25"), totalQuote(2.5, "over"))
                        )),
                        marketGroup("ОЖИДАЕМЫЕ ГОЛЫ", listOf(
                            "${String.format(Locale.US, "%.2f", pred.optDouble("expectedHomeGoals"))} : ${String.format(Locale.US, "%.2f", pred.optDouble("expectedAwayGoals"))}"
                        ))
                    )
                    val homeName = m.optString("home")
                    val awayName = m.optString("away")
                    fun teamTotalRows(teamKey: String, sideKey: String): List<String> =
                        listOf(0.5 to "05", 1.5 to "15").map { (line, suffix) ->
                            val probKey = "${teamKey}Over${suffix}"
                            marketLine("ИТБ ${fmt(line)}", pct(pred, probKey), totalQuote(line, "over", sideKey))
                        }
                    addMarketPair(
                        marketGroup(homeName, teamTotalRows("home", "home")),
                        marketGroup(awayName, teamTotalRows("away", "away"))
                    )
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