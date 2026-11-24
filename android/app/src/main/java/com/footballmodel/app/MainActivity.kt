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
    private val marketRoiUrl = "https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/market-roi.json"
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
        CrashDiagnostics.install(applicationContext)
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
        applyEdgeToEdgeInsets(root)
        UiMotion.installPressFeedback(root)

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
            val cached = readFeedCache("merged_json")
            if (!cached.isNullOrBlank()) {
                val restored = try { JSONObject(cached) } catch (_: Exception) { null }
                if (restored == null) {
                    FeedCacheStore.delete(filesDir, "merged_json")
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
        CrashDiagnostics.showPendingReport(this)
        if (PrivateHistorySync.hasToken(this)) {
            syncPrivateHistory(showResult = false) { loadData() }
        } else {
            loadData()
        }
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
            if (data != null) animateContentRefresh { render() } else loadData()
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
            if (data != null) animateContentRefresh { render() }
        }
        liveRefreshHandler.removeCallbacks(liveRefreshTask)
        liveRefreshHandler.postDelayed(liveRefreshTask, 60 * 1000L)
    }

    override fun onPause() {
        liveRefreshHandler.removeCallbacks(liveRefreshTask)
        if (PrivateHistorySync.hasToken(this)) PrivateHistorySync.syncAsync(this)
        super.onPause()
    }

    /**
     * Android 15 enforces edge-to-edge for targetSdk 35. Add system-bar and
     * display-cutout insets to the existing content padding so the first and
     * last controls remain reachable without changing the visual spacing.
     */
    private fun applyEdgeToEdgeInsets(content: View) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + safe.left,
                baseTop + safe.top,
                baseRight + safe.right,
                baseBottom + safe.bottom
            )
            insets
        }
        content.requestApplyInsets()
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
        addText(panelOdds, "КОЭФФИЦИЕНТЫ MELBET · РАБОЧИЕ РЫНКИ", 9, true, gold)
        var shown = 0
        fun validPrice(obj: JSONObject?, key: String): Boolean {
            val price = obj?.optDouble(key, Double.NaN) ?: Double.NaN
            return price.isFinite() && price > 1.0
        }
        val dc = best.optJSONObject("doubleChance")
        val dcItems = listOf("1x" to "1X", "x2" to "X2")
            .filter { validPrice(dc, it.first) }
            .map { Triple(it.second, oddsText(dc!!, it.first), gold) }
        if (dcItems.isNotEmpty()) {
            addText(panelOdds, "ДВОЙНОЙ ШАНС", 10, true, muted)
            addMarketRow(panelOdds, dcItems)
            shown += dcItems.size
        }
        val btts = best.optJSONObject("btts")
        if (validPrice(btts, "yes")) {
            addText(panelOdds, "ОБЕ КОМАНДЫ ЗАБЬЮТ", 10, true, muted)
            addMarketRow(panelOdds, listOf(Triple("ОЗ — да", oddsText(btts!!, "yes"), gold)))
            shown++
        }
        val matchTotals = best.optJSONArray("totals")
        if (matchTotals != null) {
            var headingShown = false
            for (i in 0 until matchTotals.length()) {
                val line = matchTotals.optJSONObject(i) ?: continue
                val point = line.optDouble("point", Double.NaN)
                val price = line.optDouble("price", Double.NaN)
                if (!point.isFinite() || kotlin.math.abs(point - 2.5) >= 0.001 ||
                    !line.optString("side").equals("over", true) || !price.isFinite() || price <= 1.0) continue
                if (!headingShown) {
                    addText(panelOdds, "ОБЩИЙ ТОТАЛ", 10, true, muted)
                    headingShown = true
                }
                addMarketRow(panelOdds, listOf(Triple("ТБ 2.5", oddsText(line, "price"), gold)))
                shown++
            }
        }
        val teamTotals = best.optJSONArray("teamTotals")
        if (teamTotals != null) {
            var headingShown = false
            for (i in 0 until teamTotals.length()) {
                val line = teamTotals.optJSONObject(i) ?: continue
                val price = line.optDouble("price", Double.NaN)
                val point = line.optDouble("point", Double.NaN)
                val side = line.optString("side")
                if (!side.equals("over", true) || !price.isFinite() || price <= 1.0 ||
                    !point.isFinite() || (kotlin.math.abs(point - 0.5) >= 0.001 && kotlin.math.abs(point - 1.5) >= 0.001)) continue
                if (!headingShown) {
                    addText(panelOdds, "ИНДИВИДУАЛЬНЫЕ ТОТАЛЫ", 10, true, muted)
                    headingShown = true
                }
                val team = if (line.optString("team") == "home") "Хозяева" else "Гости"
                addMarketRow(panelOdds, listOf(Triple("$team ИТБ ${fmt(point)}", oddsText(line, "price"), gold)))
                shown++
            }
        }
        if (shown == 0) addText(panelOdds, "Источник пока не вернул котировки по рабочим рынкам", 11, false, muted)
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
        val raw = match.optString("utcDate").ifBlank {
            match.optString("dateEvent").ifBlank { match.optString("date") }
        }
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

    private fun matchTimeZone(): java.util.TimeZone =
        java.util.TimeZone.getTimeZone("Asia/Yekaterinburg")

    private fun kickoffLabel(match: JSONObject): String {
        val raw = match.optString("utcDate").ifBlank {
            match.optString("dateEvent").ifBlank { match.optString("date") }
        }
        val millis = kickoffTimestamp(match) ?: return "Дата и время начала не указаны"
        val hasTime = raw.contains("T") || match.optLong("startTimestamp", 0L) > 0L
        val formatted = SimpleDateFormat(if (hasTime) "EEE, dd MMM · HH:mm" else "EEE, dd MMM", Locale.getDefault())
            .apply { timeZone = matchTimeZone() }.format(Date(millis))
        return if (hasTime) "$formatted · Екатеринбург (UTC+5)" else "$formatted · время начала не указано"
    }

    private fun formatLocalTimestamp(millis: Long): String =
        if (millis <= 0L) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .apply { timeZone = matchTimeZone() }.format(Date(millis))

    private fun formatSourceTimestamp(raw: String): String {
        if (raw.isBlank()) return "—"
        val millis = kickoffTimestamp(JSONObject().put("utcDate", raw)) ?: return raw.replace("T", " ").take(19)
        return formatLocalTimestamp(millis)
    }

    private fun date(raw: String): String {
        if (raw.isBlank()) return "—"
        val millis = kickoffTimestamp(JSONObject().put("utcDate", raw)) ?: return raw.replace("T", " ").take(16)
        val hasTime = raw.contains("T")
        return SimpleDateFormat(if (hasTime) "EEE, dd MMM  ·  HH:mm" else "EEE, dd MMM", Locale.getDefault())
            .apply { timeZone = matchTimeZone() }.format(Date(millis))
    }

    // Large feed bodies live in private files, not SharedPreferences. Preferences
    // retain only small metadata (ETags and timestamps).
    private fun readFeedCache(cacheKey: String): String? {
        FeedCacheStore.read(filesDir, cacheKey)?.let { cached ->
            // Also clean up a legacy SharedPreferences value if a previous process
            // was interrupted after writing the file but before removing the old key.
            if (feedCache.contains(cacheKey)) feedCache.edit().remove(cacheKey).apply()
            return cached
        }
        val legacy = feedCache.getString(cacheKey, null) ?: return null
        if (FeedCacheStore.write(filesDir, cacheKey, legacy)) {
            feedCache.edit().remove(cacheKey).apply()
        }
        return legacy
    }

    // Conditional HTTP requests avoid downloading identical JSON on every refresh.
    // The last good response is retained locally and used when the network is unavailable.
    private fun fetchFeed(url: String, cacheKey: String, required: Boolean): String? {
        val cached = readFeedCache(cacheKey)
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
                // A conditional request is valid only when its matching body is cached.
                if (cached != null && !etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (cached != null) return cached
                throw IllegalStateException("HTTP 304 without cached feed body")
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bodyCached = FeedCacheStore.write(filesDir, cacheKey, body)
            val editor = feedCache.edit()
            if (bodyCached) editor.remove(cacheKey)
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
        UiMotion.animateTextChange(statusView, if (data == null) "Загружаю данные…" else "Проверяю обновления…")
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
                        FeedMerger.mergeUefa(json, uefa)
                    } catch (_: Exception) {
                        // Keep the valid domestic feed if UEFA data is malformed.
                    }
                }
                // Historical MelBet ROI is a separate compact feed. Its outage must
                // never block match data, predictions or navigation.
                val marketRoiBody = fetchFeed(marketRoiUrl, "market_roi_json", false)
                if (!marketRoiBody.isNullOrBlank()) {
                    try { json.put("marketRoi", JSONObject(marketRoiBody)) }
                    catch (_: Exception) { /* Keep the last valid market ROI cache if present. */ }
                }
                // Prepare expensive navigation summaries and TOP candidates off the main thread.
                prepareAvailableMatchCounts(json)
                val aiMatchCandidates = collectAiCandidates(json)
                aiCandidatesCache = aiMatchCandidates
                aiCandidatesCacheKey = statsCacheKey(json)
                updateAiSignalArchive(json, aiMatchCandidates)
                val payload = json.toString()
                // Avoid rewriting a multi-megabyte feed on every unchanged 60-second poll.
                if (payload != lastPayload) FeedCacheStore.write(filesDir, "merged_json", payload)
                feedCache.edit()
                    .remove("merged_json")
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
                        val hadPreviousData = data != null
                        data = json
                        lastPayload = payload
                        tabViewCache.clear()
                        renderedKey = null
                        if (hadPreviousData) animateContentRefresh { render() } else render()
                    }
                    // Refresh the history immediately when foreground or background settlement changes a bet.
                    val betsChanged = processAutoPaperBets(json)
                    if (betsChanged && currentTab == "history") {
                        // Do not cache the currently visible pre-settlement rows.
                        renderedKey = null
                        animateContentRefresh { render() }
                    }
                    if (!changed) {
                        UiMotion.animateTextChange(statusView, "●  Данные актуальны · источник: ${formatSourceTimestamp(json.optString("updatedAt"))} · проверено: ${formatLocalTimestamp(feedCache.getLong("last_successful_check_ms", 0L))}")
                    }
                    loadInProgress.set(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val lastCheck = formatLocalTimestamp(feedCache.getLong("last_successful_check_ms", 0L))
                    UiMotion.animateTextChange(statusView, (if (data != null) "Нет сети · показаны последние данные" else
                        "Нет соединения · ${e.message ?: "проверьте интернет"}") + " · последняя успешная проверка: $lastCheck")
                    loadInProgress.set(false)
                }
            }
        }
    }

    private var tabRenderGeneration = 0

    private fun animateTabEntry(view: View) {
        UiMotion.animateEntry(view, dp(6).toFloat())
    }

    private fun selectTab(tab: String) {
        if (currentTab == tab) return
        if (tab == "matches" && !isLineSectionVisible()) return
        val availableTabs = navigationTabs()
        currentTab = tab
        val generation = ++tabRenderGeneration

        // Update navigation immediately. A previously rendered tab is attached
        // synchronously from the view cache; only first-time screens need a frame
        // to construct their content.
        renderTabs()
        val targetKey = tab + "|" + when (tab) {
            "matches" -> selectedLeague
            "history" -> selectedHistoryTab
            "desk" -> selectedTopTab
            "stats" -> "ALL"
            else -> "ALL"
        }
        if (tab == "matches") {
            UiMotion.animateVisibility(lineHeadingView, true, dp(6).toFloat())
        } else {
            UiMotion.animateVisibility(lineHeadingView, false, dp(6).toFloat())
        }
        UiMotion.animateVisibility(leagueBar, tab == "matches", dp(6).toFloat())
        UiMotion.animateVisibility(leagueScroll, tab == "matches", dp(6).toFloat())
        if (tabViewCache.containsKey(targetKey) && data != null) {
            render()
            matchesContainer.visibility = View.VISIBLE
            animateTabEntry(matchesContainer)
            if (tab == "matches") animateTabEntry(leagueScroll)
        } else {
            matchesContainer.visibility = View.INVISIBLE
            matchesContainer.postOnAnimation {
                if (generation != tabRenderGeneration || currentTab != tab) return@postOnAnimation
                render()
                animateTabEntry(matchesContainer)
                if (tab == "matches") animateTabEntry(leagueScroll)
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
                    animateContentRefresh { render() }
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
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        setAutoSizeTextTypeUniformWithConfiguration(
                            8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                        )
                    }
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
            // Filter-driven renders must not replay the tab selection animation.
            // Animate only when the selected tab actually changes.
            if (tab.tag != active) {
                tab.tag = active
                UiMotion.animateTextColor(title, if (active) bg else Color.WHITE)
                val nextBackground = if (active) {
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
                UiMotion.transitionBackground(tab, nextBackground)
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
    private data class StatsRoiAggregate(
        var count: Int = 0, var wins: Int = 0, var losses: Int = 0, var pushes: Int = 0,
        var profit: Double = 0.0, var turnover: Double = 0.0,
        var oddsTotal: Double = 0.0, var oddsCount: Int = 0
    )
    private val statsRoiMarketLabels = linkedMapOf(
        "1x" to "1X · хозяева или ничья",
        "x2" to "X2 · гости или ничья",
        "over25" to "ТБ 2,5 · всего",
        "homeOver15" to "ИТБ хозяев 1,5",
        "awayOver15" to "ИТБ гостей 1,5",
        "homeOver05" to "ИТБ хозяев 0,5",
        "awayOver05" to "ИТБ гостей 0,5",
        "bttsYes" to "ОЗ · да"
    )
    private data class StatsMetricRow(
        val name: String, val n: Int, val hits: Int, val brier: Double,
        val logLoss: Double, val bands: List<StatsBandRow>
    )
    @Volatile private var statsMetricsCacheKey: String? = null
    @Volatile private var statsMetricsCache: List<StatsMetricRow> = emptyList()
    @Volatile private var statsMetricsError: String? = null
    private val statsCalculationInProgress = AtomicBoolean(false)
    private var selectedStatsPeriod = "ALL"
    private var selectedStatsSource = "TOP"
    private data class StatsBetInput(
        val id: String, val betDay: String, val date: String, val createdAt: Long,
        val source: String, val status: String, val stake: Double, val odds: Double,
        val market: String, val marketKey: String, val league: String, val isCoupon: Boolean
    )
    private data class StatsBetViewCache(
        val key: String, val current: BetAnalyticsReport
    )
    @Volatile private var statsBetViewCache: StatsBetViewCache? = null
    @Volatile private var statsBetCalculationError: String? = null
    private val statsBetCalculationInProgress = AtomicBoolean(false)
    private val statsBetCacheGeneration = AtomicLong(0L)
    private val statsFilterButtons = linkedMapOf<String, TextView>()
    // Keep the intended stats scroll offset while a newly selected filter is calculated.
    private var pendingStatsScrollRestore: Int? = null

    private fun animateContentRefresh(refresh: () -> Unit) {
        if (!::matchesContainer.isInitialized) {
            refresh()
            return
        }
        UiMotion.animateContentChange(matchesContainer, dp(6).toFloat(), refresh)
    }

    private fun hideLineHeadingImmediately() {
        lineHeadingView.animate().cancel()
        lineHeadingView.visibility = View.GONE
        lineHeadingView.alpha = 1f
        lineHeadingView.translationY = 0f
    }

    private fun animateStakeModeVisibility(view: View, show: Boolean) {
        // Use the shared app-wide fade transition while preserving the panel's contents.
        UiMotion.animateVisibility(view, show, 0f)
    }

    private fun animateStakeModeHint(view: TextView, text: String) {
        UiMotion.animateTextChange(view, text)
    }

    private fun styleStatsFilterButton(button: TextView, selected: Boolean) {
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

    private fun updateStatsFilterButtonStyles() {
        statsFilterButtons.forEach { (key, button) ->
            val selected = if (key.startsWith("source:")) {
                selectedStatsSource == key.substringAfter("source:")
            } else {
                selectedStatsPeriod == key.substringAfter("period:")
            }
            styleStatsFilterButton(button, selected)
        }
    }

    private fun changeStatsFilter(update: () -> Unit) {
        // Keep the current report and layout visible while the next filter is calculated.
        tabScrollPositions["stats|ALL"] = mainScrollView.scrollY
        update()
        statsBetCacheGeneration.incrementAndGet()
        updateStatsFilterButtonStyles()
        val now = System.currentTimeMillis()
        val key = listOf(selectedStatsPeriod, selectedStatsSource,
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(now))).joinToString("|")
        requestStatsBetCalculation(key, bets, selectedStatsPeriod, selectedStatsSource, now)
    }

    private fun restorePendingStatsScrollPosition() {
        val requestedY = pendingStatsScrollRestore ?: return
        // Wait until the rebuilt report has been measured; the loading placeholder
        // is shorter and would otherwise clamp scrollY to zero.
        mainScrollView.post {
            mainScrollView.postOnAnimation {
                if (currentTab != "stats" || pendingStatsScrollRestore != requestedY) return@postOnAnimation
                val contentHeight = mainScrollView.getChildAt(0)?.height ?: 0
                val maxScroll = (contentHeight - mainScrollView.height).coerceAtLeast(0)
                val restoredY = requestedY.coerceAtMost(maxScroll)
                mainScrollView.scrollTo(0, restoredY)
                tabScrollPositions["stats|ALL"] = restoredY
                pendingStatsScrollRestore = null
            }
        }
    }

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
                    bet.optLong("createdAt", 0L), bet.optString("source"), bet.optString("status"),
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
                    AnalyticsBet(item.id, day, item.source, item.status, item.stake, item.odds,
                        item.market.ifBlank { item.marketKey }, item.league, item.isCoupon,
                        marketKey = item.marketKey)
                }
                val current = BetAnalyticsCalculator.calculate(records, bounds.first, bounds.second, source)
                StatsBetViewCache(key, current)
            } catch (error: Throwable) {
                android.util.Log.e("IQMatchStats", "Unable to calculate terminal betting analytics", error)
                calculationError = error.javaClass.simpleName
                StatsBetViewCache(key, BetAnalyticsCalculator.calculate(emptyList(), 0L, Long.MAX_VALUE, source))
            }
            runOnUiThread {
                val isLatestRequest = generation == statsBetCacheGeneration.get()
                statsBetCalculationInProgress.set(false)
                if (!isLatestRequest) {
                    // A newer filter or history change arrived during this calculation.
                    // Discard the stale snapshot and calculate only the latest selection.
                    if (currentTab == "stats") {
                        val latestNow = System.currentTimeMillis()
                        val latestKey = listOf(selectedStatsPeriod, selectedStatsSource,
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.format(Date(latestNow))).joinToString("|")
                        requestStatsBetCalculation(latestKey, bets, selectedStatsPeriod, selectedStatsSource, latestNow)
                    }
                    return@runOnUiThread
                }
                statsBetViewCache = calculated
                statsBetCalculationError = calculationError
                // A cached stats screen may have been detached while this calculation
                // ran. Always invalidate it, even if the user is on another tab now.
                tabViewCache.remove("stats|ALL")
                if (currentTab == "stats") {
                    // Rebuild without a fade; keep the existing scroll position.
                    render()
                }
            }
        }
    }

    private fun betStatsDate(bet: StatsBetInput): Long? {
        // Open bets must appear immediately in the period when the user recorded them,
        // even when the fixture is scheduled in the future. Settled bets use fixture date.
        if (bet.status == "PENDING") {
            StatsPredictionScope.matchDateMillis(bet.betDay)?.let { return it }
            if (bet.createdAt > 0L) {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(bet.createdAt))
                return StatsPredictionScope.matchDateMillis(day)
            }
        }
        return StatsPredictionScope.matchDateMillis(bet.date)
    }

    private fun statsPeriodBounds(period: String, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        // Use complete UTC fixture dates: WEEK is today plus the six preceding calendar days.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val from = cal.timeInMillis
        when (period) {
            "WEEK" -> cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
            "MONTH" -> cal.add(java.util.Calendar.MONTH, -1)
            "QUARTER" -> cal.add(java.util.Calendar.MONTH, -3)
            "HALF" -> cal.add(java.util.Calendar.MONTH, -6)
            "YEAR" -> cal.add(java.util.Calendar.YEAR, -1)
            else -> return 0L to Long.MAX_VALUE
        }
        val end = from + java.util.concurrent.TimeUnit.DAYS.toMillis(1) - 1L
        return cal.timeInMillis to end
    }

    private fun terminalSection(parent: LinearLayout, title: String, subtitle: String = ""): LinearLayout {
        val box = card(parent)
        box.setPadding(dp(14), dp(13), dp(14), dp(13))
        box.background = GradientDrawable().apply {
            setColor(Color.rgb(14, 24, 38)); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(91, 78, 55))
        }
        box.addView(label(title.uppercase(Locale.ROOT), 10, true, gold).apply {
            typeface = Typeface.MONOSPACE; letterSpacing = 0.10f
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
        terminalMetric(first, "ЧИСТАЯ ПРИБЫЛЬ", fmt(summary.profit), if (summary.profit >= 0) accent else Color.rgb(210, 112, 108))
        terminalMetric(first, "ROI · ДОХОДНОСТЬ", String.format(Locale.US, "%.2f%%", summary.roi), if (summary.roi >= 0) accent else Color.rgb(210, 112, 108))
        terminalMetric(first, "ДОЛЯ ПОБЕД", String.format(Locale.US, "%.1f%%", summary.winRate), Color.WHITE)
        box.addView(first)
        val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalMetric(second, "В / П / ВОЗВРАТ", summary.wins.toString() + "/" + summary.losses + "/" + summary.pushes, Color.WHITE)
        terminalMetric(second, "ОБОРОТ", fmt(summary.turnover), Color.WHITE)
        terminalMetric(second, "СРЕДНИЙ КЭФ.", if (summary.averageOdds > 0) String.format(Locale.US, "%.2f", summary.averageOdds) else "—", Color.WHITE)
        box.addView(second)
    }

    private fun addTerminalEquityChart(parent: LinearLayout, points: List<AnalyticsPoint>, showRoi: Boolean = false) {
        val box = if (showRoi) terminalSection(parent, "ДИНАМИКА ROI", "НАКОПИТЕЛЬНАЯ ДОХОДНОСТЬ · ПРИБЫЛЬ / ОБОРОТ × 100%")
            else terminalSection(parent, "ДИНАМИКА ПРИБЫЛИ", "ТОЛЬКО РАССЧИТАННЫЕ СТАВКИ · НЕ БАЛАНС СЧЁТА")
        if (points.isEmpty()) {
            box.addView(label("Нет рассчитанных ставок за выбранный период.", 11, false, muted))
            return
        }
        val chart = object : View(this) {
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
            private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 105, 116); strokeWidth = dp(1).toFloat() }
            private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = dp(9).toFloat(); typeface = Typeface.MONOSPACE }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = dp(43).toFloat(); val right = width - dp(8).toFloat()
                val top = dp(12).toFloat(); val bottom = height - dp(22).toFloat()
                if (right <= left || bottom <= top) return
                var totalProfit = 0.0
                var totalTurnover = 0.0
                val values = points.map {
                    totalProfit += it.profit
                    totalTurnover += it.turnover
                    if (showRoi) if (totalTurnover > 0.0) totalProfit / totalTurnover * 100.0 else 0.0
                    else totalProfit
                }
                val rawLow = minOf(0.0, values.minOrNull() ?: 0.0)
                val rawHigh = maxOf(0.0, values.maxOrNull() ?: 0.0)
                val rawSpan = (rawHigh - rawLow).coerceAtLeast(1.0)
                val roughStep = rawSpan / 4.0
                val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(roughStep)))
                val normalizedStep = roughStep / magnitude
                val stepFactor = when {
                    normalizedStep <= 1.0 -> 1.0
                    normalizedStep <= 2.0 -> 2.0
                    normalizedStep <= 5.0 -> 5.0
                    else -> 10.0
                }
                val step = stepFactor * magnitude
                val low = kotlin.math.floor(rawLow / step) * step
                val high = kotlin.math.ceil(rawHigh / step) * step
                val span = (high - low).coerceAtLeast(step)
                fun chartY(value: Double): Float =
                    bottom - ((value - low) / span * (bottom - top)).toFloat()
                for (i in 0..4) {
                    val value = high - step * i
                    val y = chartY(value)
                    canvas.drawLine(left, y, right, y, grid)
                    val axisLabel = if (showRoi) String.format(Locale.US, "%.1f%%", value)
                        else if (step < 1.0) String.format(Locale.US, "%.1f", value)
                        else String.format(Locale.US, "%.0f", value)
                    canvas.drawText(axisLabel, 0f, y + dp(3), txt)
                }
                val zeroY = chartY(0.0)
                val zeroLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(145, 157, 168)
                    strokeWidth = dp(1).toFloat()
                }
                canvas.drawLine(left, zeroY, right, zeroY, zeroLine)
                val strokeColor = if ((values.lastOrNull() ?: 0.0) >= 0) accent else Color.rgb(230, 105, 101)
                val area = android.graphics.Path()
                val linePath = android.graphics.Path()
                // Include the zero-profit starting point so even a single settled day
                // produces a visible segment instead of a path containing only moveTo().
                val plottedValues = listOf(0.0) + values
                plottedValues.forEachIndexed { i, value ->
                    val x = left + (right - left) * i / (plottedValues.size - 1).toFloat()
                    val y = chartY(value)
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        area.moveTo(x, zeroY)
                        area.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(right, zeroY)
                area.close()
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokeColor
                    alpha = 58
                    style = Paint.Style.FILL
                }
                canvas.drawPath(area, fill)
                line.color = strokeColor
                canvas.drawPath(linePath, line)
                val firstPointDate = points.minOfOrNull { it.dayMillis }?.let { day ->
                    SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(day))
                } ?: "—"
                canvas.drawText(firstPointDate, left, height - dp(4).toFloat(), txt)
                canvas.drawText("СЕЙЧАС", right - dp(38), height - dp(4).toFloat(), txt)
            }
        }
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(170)))
    }

    private fun terminalBreakdown(parent: LinearLayout, title: String, rows: List<AnalyticsBreakdown>, limit: Int = 8, showAverageOdds: Boolean = false) {
        val box = terminalSection(parent, title)
        if (showAverageOdds) {
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
            header.addView(label("РЫНОК", 8, true, muted), LinearLayout.LayoutParams(0, -2, 1.5f))
            header.addView(label("СТАВКИ", 8, true, muted), LinearLayout.LayoutParams(0, -2, 0.8f))
            header.addView(label("СР.КЭФ", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 0.55f))
            header.addView(label("ROI", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 0.65f))
            header.addView(label("Руб", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 0.65f))
            box.addView(header)
        }
        if (rows.isEmpty()) {
            box.addView(label("Недостаточно рассчитанных данных.", 11, false, muted))
            return
        }
        rows.take(limit).forEachIndexed { index, item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(label((index + 1).toString() + ". " + item.label, 10, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1.5f))
            row.addView(label(item.count.toString() + " · " + item.wins + "/" + item.losses, 9, false, muted).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.8f))
            if (showAverageOdds) {
                row.addView(label(if (item.count > 0) String.format(Locale.US, "%.2f", item.averageOdds) else "—", 9, true, Color.WHITE).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.55f))
            }
            row.addView(label(if (item.count > 0) String.format(Locale.US, "%+.1f%%", item.roi) else "—", 9, true, when { item.count == 0 -> muted; item.roi > 0 -> accent; item.roi < 0 -> Color.rgb(210, 112, 108); else -> Color.WHITE }).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.7f))
            row.addView(label(fmt(item.profit), 9, true, if (item.profit >= 0) accent else Color.rgb(210, 112, 108)).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 0.65f))
            box.addView(row)
        }
    }

    private fun renderMarketProbabilityRoi(parent: LinearLayout, title: String, rows: List<JSONObject>, source: String) {
        val periodLabel = if (source == "TOP") "ПО ФАКТИЧЕСКИМ БУМАЖНЫМ СТАВКАМ" else "ПО ИСТОРИЧЕСКИМ КОТИРОВКАМ MELBET · 1 ЕДИНИЦА НА ПРОГНОЗ"
        val section = terminalSection(parent, title, periodLabel)
        val bandLabels = if (source == "TOP") listOf("60–69%", "70–79%", "80%+") else
            listOf("50–59%", "60–69%", "70–79%", "80%+")
        statsRoiMarketLabels.forEach { (marketKey, marketLabel) ->
            val marketRows = rows.filter { it.optString("market_key", it.optString("marketKey")) == marketKey }
            val total = StatsRoiAggregate()
            val bands = bandLabels.map { StatsRoiAggregate() }
            marketRows.forEach rowLoop@ { row ->
                val status = row.optString("status").uppercase(Locale.ROOT)
                if (status != "SETTLED" && status !in setOf("WON", "LOST", "PUSH")) return@rowLoop
                val odds = row.optDouble(if (source == "TOP") "odds" else "price", Double.NaN)
                if (!odds.isFinite() || odds <= 1.0) return@rowLoop
                val stake = if (source == "TOP") row.optDouble("stake", Double.NaN) else 1.0
                if (!stake.isFinite() || stake <= 0.0) return@rowLoop
                val profit = if (source == "TOP") when (status) {
                    "WON" -> stake * (odds - 1.0)
                    "LOST" -> -stake
                    "PUSH" -> 0.0
                    else -> 0.0
                } else row.optDouble("profit_units", Double.NaN)
                if (!profit.isFinite()) return@rowLoop
                total.count++
                total.turnover += stake
                total.profit += profit
                total.oddsTotal += odds
                total.oddsCount++
                when {
                    status == "WON" || (status == "SETTLED" && row.optString("result").equals("WIN", true)) -> total.wins++
                    status == "LOST" || (status == "SETTLED" && row.optString("result").equals("LOSS", true)) -> total.losses++
                    status == "PUSH" || (status == "SETTLED" && row.optString("result").equals("PUSH", true)) -> total.pushes++
                }
                val probability = row.optDouble(if (source == "TOP") "probability" else "model_probability", Double.NaN)
                val bandIndex = if (probability.isFinite()) StatsPredictionScope.confidenceBand(source, probability) else null
                if (bandIndex != null && bandIndex in bands.indices) {
                    val band = bands[bandIndex]
                    band.count++
                    band.turnover += stake
                    band.profit += profit
                    band.oddsTotal += odds
                    band.oddsCount++
                    when {
                        status == "WON" || (status == "SETTLED" && row.optString("result").equals("WIN", true)) -> band.wins++
                        status == "LOST" || (status == "SETTLED" && row.optString("result").equals("LOSS", true)) -> band.losses++
                        status == "PUSH" || (status == "SETTLED" && row.optString("result").equals("PUSH", true)) -> band.pushes++
                    }
                }
            }
            val marketCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = rounded(Color.rgb(13, 25, 41), 10)
            }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(label(marketLabel, 10, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1.2f))
            val totalRoi = if (total.turnover > 0.0) total.profit / total.turnover * 100.0 else Double.NaN
            titleRow.addView(label(
                if (totalRoi.isFinite()) String.format(Locale.US, "%+.2f%%", totalRoi) else "ROI —",
                11, true, when { !totalRoi.isFinite() -> muted; totalRoi > 0 -> accent; totalRoi < 0 -> Color.rgb(210, 112, 108); else -> Color.WHITE }
            ).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE })
            marketCard.addView(titleRow)
            marketCard.addView(label(
                "Рассчитано: ${total.count} · ср. кэф.: ${if (total.oddsCount > 0) String.format(Locale.US, "%.2f", total.oddsTotal / total.oddsCount) else "—"} · W/L/P: ${total.wins}/${total.losses}/${total.pushes}",
                9, false, muted
            ))
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(2)) }
            header.addView(label("ВЕРОЯТНОСТЬ", 8, true, muted), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(label("МАТЧИ", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(43), -2))
            header.addView(label("СР.КЭФ", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(55), -2))
            header.addView(label("ROI", 8, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(64), -2))
            marketCard.addView(header)
            bands.forEachIndexed { index, band ->
                val bandRoi = if (band.turnover > 0.0) band.profit / band.turnover * 100.0 else Double.NaN
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
                row.addView(label(bandLabels[index], 9, true, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(band.count.toString(), 9, false, muted).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(43), -2))
                row.addView(label(if (band.oddsCount > 0) String.format(Locale.US, "%.2f", band.oddsTotal / band.oddsCount) else "—", 9, true, Color.WHITE).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(55), -2))
                row.addView(label(if (bandRoi.isFinite()) String.format(Locale.US, "%+.2f%%", bandRoi) else "—", 9, true,
                    when { !bandRoi.isFinite() -> muted; bandRoi > 0 -> accent; bandRoi < 0 -> Color.rgb(210, 112, 108); else -> Color.WHITE }
                ).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(64), -2))
                marketCard.addView(row)
            }
            section.addView(marketCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
    }

    private fun renderBetPerformanceStats(json: JSONObject) {
        val now = System.currentTimeMillis()
        // Keep the analytics cache key stable for the whole local calendar day.
        // Including bounds.first (which contains the current time down to milliseconds)
        // caused every render to invalidate a calculation that had just completed.
        val key = listOf(selectedStatsPeriod, selectedStatsSource,
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(now))).joinToString("|")
        val cached = statsBetViewCache
        if (cached?.key != key) {
            if (!statsBetCalculationInProgress.get()) {
                requestStatsBetCalculation(key, bets, selectedStatsPeriod, selectedStatsSource, now)
            }
            // Keep the last complete report on screen while the new filter is calculated.
            // Replacing it with a short loading card changes the scroll content height abruptly.
            if (cached == null) {
                val loading = terminalSection(matchesContainer, "РАСЧЁТ АНАЛИТИКИ", "СБОР ДАННЫХ → ПРОВЕРКА → РАСЧЁТ")
                loading.addView(label("Рассчитываем показатели в фоновом режиме…", 12, true, Color.WHITE))
                loading.addView(label("История ставок не изменяется. Приложение остаётся отзывчивым.", 10, false, muted))
                return
            }
        }

        val controls = terminalSection(matchesContainer, "ФИЛЬТРЫ ОТЧЁТА", "ПЕРИОД / ИСТОЧНИК")
        statsFilterButtons.clear()
        // Keep the report source selector at the top; time periods follow beneath it.
        val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("TOP" to "ТОП", "ALL" to "ВСЕ").forEach { option ->
            val active = selectedStatsSource == option.first
            val button = TextView(this).apply {
                text = option.second; gravity = Gravity.CENTER; textSize = 10f; typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(10), dp(8), dp(10))
                styleStatsFilterButton(this, active)
                setOnClickListener { if (selectedStatsSource != option.first) changeStatsFilter { selectedStatsSource = option.first } }
            }
            statsFilterButtons["source:${option.first}"] = button
            sourceRow.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        controls.addView(sourceRow)
        val periods = listOf(listOf("WEEK" to "7 ДН", "MONTH" to "1 МЕС", "QUARTER" to "3 МЕС"),
            listOf("HALF" to "6 МЕС", "YEAR" to "1 ГОД", "ALL" to "ВСЁ ВРЕМЯ"))
        periods.forEach { options ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEach { option ->
                val active = selectedStatsPeriod == option.first
                val button = TextView(this).apply {
                    text = option.second; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.MONOSPACE
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    styleStatsFilterButton(this, active)
                    setOnClickListener { if (selectedStatsPeriod != option.first) changeStatsFilter { selectedStatsPeriod = option.first } }
                }
                statsFilterButtons["period:${option.first}"] = button
                row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            controls.addView(row)
        }
        val report = cached!!.current
        if (selectedStatsSource == "TOP") statsBetCalculationError?.let { code ->
            val warning = terminalSection(matchesContainer, "ПРЕДУПРЕЖДЕНИЕ О ДАННЫХ")
            warning.addView(label("Расчёт завершён с ошибкой: " + code + ". Проверьте исходную историю.", 11, true, gold))
        }
        if (selectedStatsSource == "ALL") {
            // ALL is based on the settled archive of the eight prediction markets,
            // never on saved paper bets. TOP retains its existing AI_MANUAL calculation.
            val roiData = json.optJSONObject("marketRoi")
            val periodReport = roiData?.optJSONObject("periods")?.optJSONObject(selectedStatsPeriod)
            val settledCount = periodReport?.optInt("settled", 0) ?: 0
            val roi = periodReport?.optDouble("roi", Double.NaN) ?: Double.NaN
            val averageOdds = periodReport?.optDouble("averageOdds", Double.NaN) ?: Double.NaN
            val roiColor = when {
                roi.isFinite() && roi > 0.0 -> accent
                roi.isFinite() && roi < 0.0 -> Color.rgb(210, 112, 108)
                else -> Color.WHITE
            }
            val summaryCard = terminalSection(matchesContainer, "ДОХОДНОСТЬ И СРЕДНИЙ КЭФФИЦИЕНТ")
            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val roiMetric = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            roiMetric.addView(label("ROI", 9, true, muted))
            roiMetric.addView(label(
                if (settledCount > 0 && roi.isFinite()) String.format(Locale.US, "%.2f%%", roi) else "—",
                25, true, roiColor
            ).apply { typeface = Typeface.create("monospace", Typeface.BOLD) })
            metricsRow.addView(roiMetric, LinearLayout.LayoutParams(0, -2, 1f))
            val oddsMetric = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            oddsMetric.addView(label("СРЕДНИЙ КОЭФФИЦИЕНТ", 9, true, muted))
            oddsMetric.addView(label(
                if (settledCount > 0 && averageOdds.isFinite() && averageOdds > 0.0)
                    String.format(Locale.US, "%.2f", averageOdds) else "—",
                25, true, Color.WHITE
            ).apply { typeface = Typeface.create("monospace", Typeface.BOLD) })
            metricsRow.addView(oddsMetric, LinearLayout.LayoutParams(0, -2, 1f))
            summaryCard.addView(metricsRow)
            val countsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(countsRow, "РАССЧИТАНО", settledCount.toString(), Color.WHITE)
            terminalMetric(countsRow, "ПОБЕДЫ", (periodReport?.optInt("wins", 0) ?: 0).toString(), accent)
            terminalMetric(countsRow, "ПОРАЖЕНИЯ", (periodReport?.optInt("losses", 0) ?: 0).toString(), Color.rgb(210, 112, 108))
            terminalMetric(countsRow, "ВОЗВРАТЫ", (periodReport?.optInt("pushes", 0) ?: 0).toString(), Color.WHITE)
            summaryCard.addView(countsRow)
            summaryCard.addView(label(
                "Все восемь рынков общей сводки · вероятность модели от 50% · только исторические коэффициенты MelBet · 1 условная единица на прогноз. Прогнозы без исторической котировки в ROI не входят.",
                9, false, muted
            ))
            if (roiData == null) {
                summaryCard.addView(label("Архив исторических коэффициентов пока недоступен. Текущие коэффициенты вместо исторических не используются.", 9, true, gold))
            }
            val periodBounds = statsPeriodBounds(selectedStatsPeriod, now)
            val roiRows = mutableListOf<JSONObject>()
            val archivedRows = roiData?.optJSONArray("records") ?: JSONArray()
            for (index in 0 until archivedRows.length()) {
                val row = archivedRows.optJSONObject(index) ?: continue
                val day = StatsPredictionScope.matchDateMillis(row.optString("kickoff_utc")) ?: continue
                if (day !in periodBounds.first..periodBounds.second) continue
                if (!row.optString("bookmaker").equals("MELBET", true)) continue
                if (!row.optString("status").equals("SETTLED", true)) continue
                roiRows.add(row)
            }
            // Build the ALL-filter equity curve from the same settled historical MelBet
            // records and eligibility rules used by the market ROI breakdown below.
            val dailyRoiTotals = sortedMapOf<Long, Pair<Double, Double>>()
            roiRows.forEach { row ->
                val probability = row.optDouble("model_probability", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                val profitUnits = row.optDouble("profit_units", Double.NaN)
                val day = StatsPredictionScope.matchDateMillis(row.optString("kickoff_utc"))
                if (day != null && StatsPredictionScope.confidenceBand("ALL", probability) != null &&
                    price.isFinite() && price > 1.0 && profitUnits.isFinite()) {
                    val previous = dailyRoiTotals[day] ?: (0.0 to 0.0)
                    dailyRoiTotals[day] = (previous.first + profitUnits) to (previous.second + 1.0)
                }
            }
            addTerminalEquityChart(matchesContainer,
                dailyRoiTotals.map { (day, totals) -> AnalyticsPoint(day, totals.first, totals.second) },
                showRoi = true)
            renderMarketProbabilityRoi(matchesContainer, "ROI ПО РЫНКАМ И ВЕРОЯТНОСТЯМ", roiRows, "ALL")
        } else {
            val hero = terminalSection(matchesContainer, "ОБЩИЙ РЕЗУЛЬТАТ", "ТОЛЬКО СТАВКИ, ПОДТВЕРЖДЁННЫЕ ИЗ РАЗДЕЛА ТОП")
            val pnlColor = if (report.overall.profit >= 0) accent else Color.rgb(210, 112, 108)
            hero.addView(label((if (report.overall.profit > 0) "+" else "") + fmt(report.overall.profit), 29, true, pnlColor).apply { typeface = Typeface.create("monospace", Typeface.BOLD) })
            hero.addView(label("ЧИСТАЯ ПРИБЫЛЬ / УБЫТОК", 9, true, muted).apply { typeface = Typeface.MONOSPACE })
            val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(heroRow, "ROI · ДОХОДНОСТЬ", String.format(Locale.US, "%.2f%%", report.overall.roi), pnlColor)
            terminalMetric(heroRow, "ДОЛЯ ПОБЕД", String.format(Locale.US, "%.1f%%", report.overall.winRate), Color.WHITE)
            terminalMetric(heroRow, "РАССЧИТАНО", report.overall.settled.toString(), Color.WHITE)
            hero.addView(heroRow)
            val periodBounds = statsPeriodBounds(selectedStatsPeriod, now)
            val topRoiRows = mutableListOf<JSONObject>()
            val topPendingRecords = mutableListOf<AnalyticsBet>()
            for (index in 0 until bets.length()) {
                val bet = bets.optJSONObject(index) ?: continue
                if (!bet.optString("source").equals("AI_MANUAL", ignoreCase = true)) continue
                val status = bet.optString("status").uppercase(Locale.ROOT)
                val periodDate = if (status == "PENDING") {
                    StatsPredictionScope.matchDateMillis(bet.optString("betDay"))
                        ?: bet.optLong("createdAt", 0L).takeIf { it > 0L }?.let {
                            StatsPredictionScope.matchDateMillis(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.format(Date(it)))
                        }
                } else StatsPredictionScope.matchDateMillis(bet.optString("date"))
                val day = periodDate ?: continue
                if (day !in periodBounds.first..periodBounds.second) continue
                if (status == "PENDING") {
                    topPendingRecords.add(AnalyticsBet(
                        bet.optString("id"), day, bet.optString("source"), status,
                        bet.optDouble("stake", Double.NaN), bet.optDouble("odds", Double.NaN),
                        bet.optString("market"), bet.optString("league"),
                        bet.optString("marketKey") == "COUPON",
                        marketKey = bet.optString("marketKey")
                    ))
                }
                if (bet.optString("marketKey") !in statsRoiMarketLabels.keys) continue
                if (bet.optString("marketKey") == "COUPON") continue
                val odds = bet.optDouble("odds", Double.NaN)
                val stake = bet.optDouble("stake", Double.NaN)
                if (!odds.isFinite() || odds <= 1.0 || !stake.isFinite() || stake <= 0.0) continue
                if (status !in setOf("PENDING", "WON", "LOST", "PUSH")) continue
                topRoiRows.add(JSONObject().put("market_key", bet.optString("marketKey"))
                    .put("probability", bet.optDouble("probability", Double.NaN))
                    .put("odds", odds).put("stake", stake).put("status", status)
                    .put("result", when (status) { "WON" -> "WIN"; "LOST" -> "LOSS"; "PUSH" -> "PUSH"; else -> "" }))
            }
            val pendingSummary = BetAnalyticsCalculator.summarizePendingTop(topPendingRecords)
            val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(second, "ОБОРОТ", fmt(report.overall.turnover), Color.WHITE)
            terminalMetric(second, "СРЕДНИЙ КЭФ.", if (report.overall.averageOdds > 0) String.format(Locale.US, "%.2f", report.overall.averageOdds) else "—", Color.WHITE)
            terminalMetric(second, "ОТКРЫТЫЕ СТАВКИ", pendingSummary.count.toString(), gold)
            hero.addView(second)
            val pendingPotential = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(pendingPotential, "ВОЗМОЖНАЯ ВЫПЛАТА", if (pendingSummary.count > 0) fmt(pendingSummary.payout) else "—", accent)
            terminalMetric(pendingPotential, "ВОЗМОЖНАЯ ЧИСТАЯ ПРИБЫЛЬ", if (pendingSummary.count > 0) fmt(pendingSummary.netProfit) else "—", accent)
            hero.addView(pendingPotential)
            hero.addView(label("Открытые ставки и возможная выплата рассчитаны за выбранный период по тем же правилам отбора, что и статистика ТОП.", 9, false, muted))
            val bankRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(bankRow, "СРЕДНЯЯ СТАВКА", if (report.overall.averageStake > 0) fmt(report.overall.averageStake) else "—", Color.WHITE)
            terminalMetric(bankRow, "РАСЧЁТНЫЙ БАНК", if (hasInitialBankroll()) fmt(currentBankroll()) else "НЕ ЗАДАН", gold)
            hero.addView(bankRow)
            hero.addView(label(
                "Это текущий остаток: стартовый банк + чистая прибыль рассчитанных ставок − проигранные и зарезервированные открытые ставки. Считается по всем ставкам за всё время и не меняется при выборе периода.",
                9, false, muted
            ))

            addTerminalEquityChart(matchesContainer, report.overall.points)
            val risk = terminalSection(matchesContainer, "РИСКИ И СЕРИИ", "МАКСИМАЛЬНАЯ ПРОСАДКА ПРИБЫЛИ · НЕ ИЗМЕНЕНИЕ ДЕНЕЖНОГО БАЛАНСА")
            val riskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(riskRow, "МАКС. ПРОСАДКА", fmt(report.maxDrawdown), Color.rgb(210, 112, 108))
            terminalMetric(riskRow, "ПРОСАДКА, %", String.format(Locale.US, "%.1f%%", report.maxDrawdownPercent), Color.rgb(210, 112, 108))
            terminalMetric(riskRow, "ТЕКУЩАЯ СЕРИЯ", report.currentStreak, Color.WHITE)
            risk.addView(riskRow)
            val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            terminalMetric(runRow, "ЛУЧШАЯ СЕРИЯ ПОБЕД", report.longestWinStreak.toString(), accent)
            terminalMetric(runRow, "ХУДШАЯ СЕРИЯ ПОРАЖЕНИЙ", report.longestLossStreak.toString(), Color.rgb(210, 112, 108))
            risk.addView(runRow)

            terminalBreakdown(matchesContainer, "РЕЗУЛЬТАТЫ ПО РЫНКАМ", report.byMarket, 12, true)
            renderMarketProbabilityRoi(matchesContainer, "ROI ПО РЫНКАМ И ВЕРОЯТНОСТЯМ", topRoiRows, "TOP")
        }
        restorePendingStatsScrollPosition()
    }

    private fun emptyPredictionMetrics(source: String): List<StatsMetricRow> {
        val labels = listOf(
            "1X · хозяева победят ИЛИ будет ничья",
            "X2 · гости победят ИЛИ будет ничья",
            "ИТБ 1.5 · хозяева",
            "ИТБ 1.5 · гости",
            "ТБ 2.5 · всего минимум 3 гола",
            "ОЗ — да",
            "ИТБ 0.5 · хозяева",
            "ИТБ 0.5 · гости"
        )
        val bands = if (source == "TOP") listOf("60–69%", "70–79%", "80%+")
            else listOf("50–59%", "60–69%", "70–79%", "80%+")
        return labels.map { name ->
            StatsMetricRow(name, 0, 0, Double.NaN, Double.NaN,
                bands.map { StatsBandRow(it, 0, 0) })
        }
    }

    private fun evaluationMatchDayMillis(match: JSONObject): Long? {
        val raw = match.optString("utcDate").ifBlank {
            match.optString("dateEvent").ifBlank { match.optString("date") }
        }
        return StatsPredictionScope.matchDateMillis(raw)
    }

    private fun calculateStatsMetrics(json: JSONObject, source: String, topSelections: Set<String>, from: Long, to: Long): List<StatsMetricRow> {
        val metrics = mutableListOf<StatsMetricRow>()
        val evaluatedMatches = json.optJSONArray("evaluationMatches")
        fun collect(key: String, title: String, actual: (Int, Int) -> Boolean, excludeDraws: Boolean = false) {
            var n = 0
            var hits = 0
            var brier = 0.0
            var logLoss = 0.0
            val topOnly = source == "TOP"
            val bandCounts = IntArray(if (topOnly) 3 else 4)
            val bandHits = IntArray(if (topOnly) 3 else 4)
            if (evaluatedMatches != null) for (i in 0 until evaluatedMatches.length()) {
                val m = evaluatedMatches.optJSONObject(i) ?: continue
                if (m.optString("status") != "FINISHED" || m.isNull("homeScore") || m.isNull("awayScore")) continue
                val matchDay = evaluationMatchDayMillis(m) ?: continue
                if (matchDay !in from..to) continue
                val marketKey = key
                val matchId = m.optString("id")
                if (!StatsPredictionScope.includes(source, matchId, marketKey, topSelections)) continue
                val p = m.optJSONObject("prediction") ?: continue
                if (!p.has(key) || p.isNull(key)) continue
                val h = m.optInt("homeScore")
                val aw = m.optInt("awayScore")
                if (excludeDraws && h == aw) continue
                val outcome = actual(h, aw)
                val probability = p.optDouble(key).coerceIn(0.000000000001, 0.999999999999)
                // TOP admits model confidence from 60% only. Keep the same
                // threshold and band mapping covered by StatsPredictionScope tests.
                val band = StatsPredictionScope.confidenceBand(source, probability) ?: continue
                val correct = outcome == (probability >= 0.5)
                val y = if (outcome) 1.0 else 0.0
                n++
                if (correct) hits++
                bandCounts[band]++
                if (correct) bandHits[band]++
                brier += (probability - y) * (probability - y)
                logLoss += -(y * kotlin.math.ln(probability) + (1.0 - y) * kotlin.math.ln(1.0 - probability))
            }
            // Keep every active market visible in both ALL and TOP, even when
            // the selected TOP archive has no observations for a particular market.
            val labels = if (topOnly) listOf("60–69%", "70–79%", "80%+") else
                listOf("50–59%", "60–69%", "70–79%", "80%+")
            val bands = labels.indices.map { StatsBandRow(labels[it], bandCounts[it], bandHits[it]) }
            metrics.add(StatsMetricRow(
                title, n, hits,
                if (n > 0) brier / n else Double.NaN,
                if (n > 0) logLoss / n else Double.NaN,
                bands
            ))
        }
        collect("1x", "1X · хозяева победят ИЛИ будет ничья", { h, a -> h >= a })
        collect("x2", "X2 · гости победят ИЛИ будет ничья", { h, a -> a >= h })
        collect("homeOver15", "ИТБ 1.5 · хозяева", { h, _ -> h >= 2 })
        collect("awayOver15", "ИТБ 1.5 · гости", { _, a -> a >= 2 })
        collect("over25", "ТБ 2.5 · всего минимум 3 гола", { h, a -> h + a >= 3 })
        collect("bttsYes", "ОЗ — да", { h, a -> h >= 1 && a >= 1 })
        collect("homeOver05", "ИТБ 0.5 · хозяева", { h, _ -> h >= 1 })
        collect("awayOver05", "ИТБ 0.5 · гости", { _, a -> a >= 1 })
        return metrics
    }

    private fun topPredictionSelections(): Set<String> {
        val selections = linkedSetOf<String>()
        // Use the persisted TOP recommendation archive as the primary source.
        // Manual TOP bets remain included for compatibility with older app data.
        val archive = try {
            JSONArray(aiSignalPrefs.getString("signals", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until archive.length()) {
            val row = archive.optJSONObject(i) ?: continue
            val matchId = row.optString("matchId").trim()
            if (matchId.isBlank()) continue
            val marketKey = StatsPredictionScope.marketKey(
                row.optString("side"), row.optString("home"), row.optString("away")
            ) ?: continue
            selections.add(StatsPredictionScope.key(matchId, marketKey))
        }
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (!bet.optString("source").equals("AI_MANUAL", true)) continue
            if (bet.optString("marketKey") !in setOf("1x", "x2", "homeOver15", "awayOver15", "over25", "bttsYes", "homeOver05", "awayOver05")) continue
            val matchId = bet.optString("matchId").trim()
            if (matchId.isNotBlank()) selections.add(StatsPredictionScope.key(matchId, bet.optString("marketKey")))
        }
        return selections
    }

    private fun statsCacheKey(json: JSONObject): String {
        // Shared lightweight feed signature for AI candidate ordering caches.
        return json.optString("updatedAt") + "|" + json.optString("uefaEvaluationUpdatedAt") + "|" +
            (json.optJSONArray("evaluationMatches")?.length() ?: 0)
    }

    private fun statsPredictionCacheKey(json: JSONObject, source: String, topSelections: Set<String>, period: String): String {
        // Prediction metrics depend on source, TOP archive, feed version and selected match-date period.
        val scopeSignature = if (source == "TOP") topSelections.sorted().joinToString(",") else "ALL"
        val utcDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        return json.optString("updatedAt") + "|" + json.optString("uefaEvaluationUpdatedAt") + "|" +
            (json.optJSONArray("evaluationMatches")?.length() ?: 0) + "|" + source + "|" + period + "|" + utcDay + "|" + scopeSignature
    }


    private fun renderStats(json: JSONObject) {
        screenHeader("PRO TERMINAL · АНАЛИТИКА СТАВОК", "Статистика", "Профессиональная аналитика ставок")
        renderBetPerformanceStats(json)
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

        val findIndexedMatch = buildMatchLookup(json)
        for (i in 0 until bets.length()) {
            val existing = bets.optJSONObject(i) ?: continue
            if (existing.optString("status") == "PENDING" && existing.optString("marketKey") != "COUPON") {
                val match = findIndexedMatch(existing) ?: continue
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
                val key = item.optString("id")
                if (key.isBlank()) continue
                val local = merged[key]
                // Keep background-only automatic rows, and never overwrite a settled
                // persisted result with a stale PENDING copy from the foreground.
                if (local == null && preserveBackgroundAuto && item.optString("source") == "AI_AUTO") {
                    merged[key] = item
                } else if (local != null && local.optString("status") == "PENDING" &&
                    item.optString("status") in listOf("WON", "LOST", "PUSH")) {
                    merged[key] = item
                }
            }
            bets = JSONArray(merged.values.toList())
            betPrefs.edit().putString("bets", bets.toString()).apply()
        }
        // History screen cache keys use the selected tab label (ОЖИДАЮТ / COMPLETED),
        // not the legacy ALL / OPEN aliases. Clear every history variant so counts
        // and rendered bet rows are always rebuilt from the same current bets array.
        tabViewCache.keys.filter { it.startsWith("history|") }.toList().forEach {
            tabViewCache.remove(it)
        }
        // Bet status changes affect both TOP subtabs; never reuse a stale count or list.
        tabViewCache.remove("desk|ALL")
        tabViewCache.remove("desk|OPEN")
        tabViewCache.remove("desk|FIXED")
        tabViewCache.remove("stats|ALL")
        invalidateStatsBetCache()
        if (PrivateHistorySync.hasToken(this)) PrivateHistorySync.syncAsync(this)
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
    private fun settleCoupon(j: JSONObject, bet: JSONObject, findIndexedMatch: (JSONObject) -> JSONObject?) {
        if (bet.optString("status") != "PENDING") return
        val selections = bet.optJSONArray("selections") ?: return
        var allSettled = true
        var lost = false
        var effectiveOdds = 1.0
        val scores = mutableListOf<String>()
        for (i in 0 until selections.length()) {
            val pick = selections.optJSONObject(i) ?: continue
            val match = findIndexedMatch(pick)
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

    // Build once per feed snapshot instead of scanning every competition for each bet.
    private fun buildMatchLookup(feed: JSONObject): (JSONObject) -> JSONObject? {
        val byId = HashMap<String, JSONObject>()
        val byTeamsAndDate = HashMap<String, JSONObject>()
        val byTeams = HashMap<String, MutableList<JSONObject>>()
        val competitions = feed.optJSONArray("competitions")
        if (competitions != null) for (i in 0 until competitions.length()) {
            val matches = competitions.optJSONObject(i)?.optJSONArray("matches") ?: continue
            for (k in 0 until matches.length()) {
                val match = matches.optJSONObject(k) ?: continue
                val matchId = match.optString("id").trim().ifBlank {
                    match.optString("utcDate") + match.optString("home") + match.optString("away")
                }
                if (matchId.isNotBlank()) byId[matchId] = match
                val home = normalizedTeamName(match.optString("home"))
                val away = normalizedTeamName(match.optString("away"))
                if (home.isBlank() || away.isBlank()) continue
                val teamsKey = "$home|$away"
                byTeams.getOrPut(teamsKey) { mutableListOf() }.add(match)
                val matchDate = match.optString("utcDate").take(10).ifBlank {
                    match.optString("dateEvent").take(10)
                }
                if (matchDate.isNotBlank()) byTeamsAndDate["$teamsKey|$matchDate"] = match
            }
        }
        return { bet ->
            val targetId = bet.optString("matchId").trim()
            val exact = if (targetId.isNotBlank()) byId[targetId] else null
            if (exact != null) exact else {
                val home = normalizedTeamName(bet.optString("home"))
                val away = normalizedTeamName(bet.optString("away"))
                if (home.isBlank() || away.isBlank()) null else {
                    val teamsKey = "$home|$away"
                    val targetDate = bet.optString("date").take(10)
                    val byDate = if (targetDate.isNotBlank()) byTeamsAndDate["$teamsKey|$targetDate"] else null
                    byDate ?: byTeams[teamsKey]?.singleOrNull()
                }
            }
        }
    }

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
        val homeScore = if (m.isNull("homeScore")) null else m.optInt("homeScore")
        val awayScore = if (m.isNull("awayScore")) null else m.optInt("awayScore")
        val result = BetSettlement.settledResult(
            b.optString("status"), m.optString("status"),
            hasFinalFlag, finalFlag, homeScore, awayScore, b.optString("marketKey")
        ) ?: return false
        b.put("status", result)
            .put("score", "$homeScore:$awayScore")
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
                        animateContentRefresh { render() }
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

        val findIndexedMatch = buildMatchLookup(j)
        var changed = false
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("marketKey") == "COUPON") {
                val before = bet.optString("status")
                val beforeScores = bet.optString("couponScores")
                val beforeMatchStatus = bet.optString("matchStatus")
                settleCoupon(j, bet, findIndexedMatch)
                if (bet.optString("status") != before || bet.optString("couponScores") != beforeScores ||
                    bet.optString("matchStatus") != beforeMatchStatus) changed = true
                continue
            }
            val match = findIndexedMatch(bet)
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
            if (BetSettlement.isSettledStatus(bet.optString("status"))) completedBets.add(bet) else openBets.add(bet)
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
                    "WON" -> Color.rgb(0, 230, 118)
                    "LOST" -> Color.rgb(169, 130, 135) // Muted grey-red for a calmer loss indicator.
                    else -> Color.YELLOW
                }
                val fixtureForDisplay = if (bet.optString("marketKey") == "COUPON") null else findIndexedMatch(bet)
                val kickoffForDisplay = fixtureForDisplay?.let { kickoffLabel(it) }
                    ?: date(bet.optString("kickoff").ifBlank { bet.optString("date") })
                addText(box, displayLeagueName(bet.optString("league")) + " · " + kickoffForDisplay, 10, false, muted)
                addText(box, if (bet.optString("marketKey") == "COUPON")
                    "Экспресс · " + (bet.optJSONArray("selections")?.length() ?: 0) + " исходов"
                    else bet.optString("home") + " — " + bet.optString("away"), 15, true)
                addText(box, bet.optString("market") + " · кэф " + fmt(bet.optDouble("odds")), 12, true, accent)
                if (bet.has("couponScores")) addText(box, bet.optString("couponScores"), 10, false, muted)
                addText(box, "СУММА СТАВКИ: " + fmt(bet.optDouble("stake")) + " ₽", 12, true, Color.WHITE)
                if (bet.optString("marketKey") == "COUPON") {
                    val legs = bet.optJSONArray("selections")
                    for (legIndex in 0 until (legs?.length() ?: 0)) {
                        val leg = legs?.optJSONObject(legIndex) ?: continue
                        addText(box, (legIndex + 1).toString() + ". " + leg.optString("home") + " — " +
                            leg.optString("away") + " · " + leg.optString("market") + " · " + fmt(leg.optDouble("odds")), 10, false, muted)
                    }
                } else {
                    addText(box, "Модель: " +
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
                if (state == "WON") addText(box, "Чистая прибыль: +" + fmt(stake * (odds - 1)), 12, true, Color.rgb(0, 230, 118))
                if (state == "LOST") addText(box, "Убыток: −" + fmt(stake), 12, true, color)
                if (state == "PUSH") addText(box, "Возврат: " + fmt(stake), 12, true, accent)
                // Completed bets remain in the history permanently; only open bets can be deleted.
                if (!BetSettlement.isSettledStatus(state)) {
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
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
                        animateContentRefresh {
                            render()
                            mainScrollView.post { mainScrollView.scrollTo(0, previousScrollY) }
                        }
                    }
                    box.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
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
                    animateContentRefresh {
                        render()
                        mainScrollView.post { mainScrollView.scrollTo(0, previousScrollY) }
                    }
                }
            }
            historyTabs.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(8), dp(7), dp(12)) })
        }
        matchesContainer.addView(historyTabs)
        if (selectedHistoryTab == "ОЖИДАЮТ") {
            val sortedOpenBets = openBets.sortedWith(
                compareBy<JSONObject> { kickoffTimestamp(it) ?: Long.MAX_VALUE }
                    .thenBy { it.optLong("createdAt", Long.MAX_VALUE) }
            )
            renderBetSection("ОТКРЫТЫЕ СТАВКИ", sortedOpenBets, "Ставок, ожидающих расчёта, пока нет.")
        } else {
            renderBetSection("ЗАВЕРШЁННЫЕ", completedBets, "Завершённых ставок пока нет.")
        }
    }

    private fun showPrivateHistoryTokenDialog() {
        val tokenInput = EditText(this).apply {
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("GitHub · резервная копия истории")
            .setMessage(
                "Создай fine-grained personal access token для if196315-stack/Football-model-app. " +
                    "Repository permissions → Contents: Read and write. " +
                    "История ставок, сигналы и настройки будут храниться в открытом JSON-файле репозитория. " +
                    "Токен останется только в памяти приложения на телефоне; после переустановки его нужно ввести повторно."
            )
            .setView(tokenInput)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                try {
                    PrivateHistorySync.saveToken(this, tokenInput.text?.toString().orEmpty())
                    syncPrivateHistory(showResult = true) {
                        loadData()
                    }
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Не удалось сохранить token", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun syncPrivateHistory(
        showResult: Boolean,
        afterSync: (() -> Unit)? = null
    ) {
        if (!PrivateHistorySync.hasToken(this)) {
            if (showResult) showPrivateHistoryTokenDialog()
            return
        }
        PrivateHistorySync.syncAsync(this) { success, message ->
            if (success) {
                bets = try { JSONArray(betPrefs.getString("bets", "[]")) } catch (_: Exception) { JSONArray() }
                invalidateStatsBetCache()
                tabViewCache.clear()
                renderedKey = null
                if (data != null) render()
            }
            afterSync?.invoke()
            if (showResult) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (!success) android.util.Log.w("IQMatchHistorySync", message)
        }
    }

    private fun checkForAppUpdate() {
        Toast.makeText(this, "Проверяю наличие новой версии…", Toast.LENGTH_SHORT).show()
        thread(name = "iq-match-update-check") {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/if196315-stack/Football-model-app/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Не удалось получить информацию о релизе")
                }
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val latestTag = release.optString("tag_name").removePrefix("v")
                val apkUrl = release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                        .firstOrNull { it.optString("name") == "app-release.apk" }
                        ?.optString("browser_download_url")
                }.orEmpty()
                val currentVersion = (packageManager.getPackageInfo(packageName, 0).versionName ?: "")
                fun versionParts(value: String) = value.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
                fun isNewer(latest: String, current: String): Boolean {
                    val a = versionParts(latest)
                    val b = versionParts(current)
                    for (i in 0 until maxOf(a.size, b.size)) {
                        val av = a.getOrElse(i) { 0 }
                        val bv = b.getOrElse(i) { 0 }
                        if (av != bv) return av > bv
                    }
                    return false
                }
                runOnUiThread {
                    if (latestTag.isBlank() || apkUrl.isBlank()) {
                        Toast.makeText(this, "Не удалось найти APK в последнем релизе.", Toast.LENGTH_LONG).show()
                    } else if (isNewer(latestTag, currentVersion)) {
                        AlertDialog.Builder(this)
                            .setTitle("Доступно обновление IQ Match")
                            .setMessage("Установлена версия $currentVersion. Доступна версия $latestTag. Скачать обновление?")
                            .setNegativeButton("Позже", null)
                            .setPositiveButton("Скачать") { _, _ ->
                                try {
                                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl)))
                                } catch (_: Exception) {
                                    Toast.makeText(this, "Не удалось открыть ссылку для скачивания.", Toast.LENGTH_LONG).show()
                                }
                            }
                            .show()
                    } else {
                        Toast.makeText(this, "У тебя установлена последняя версия ($currentVersion).", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не удалось проверить обновление. Проверь интернет и попробуй ещё раз.", Toast.LENGTH_LONG).show()
                }
                android.util.Log.w("IQMatchUpdater", "Update check failed", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun renderHelp() {
        screenHeader("IQ MATCH · SETTINGS", "Настройки", "Настройки приложения и параметров ставок.")

        val historyBackup = card()
        addText(historyBackup, "РЕЗЕРВНАЯ КОПИЯ ИСТОРИИ", 10, true, accent)
        addText(historyBackup, if (PrivateHistorySync.hasToken(this)) "GitHub подключён" else "GitHub не подключён", 12, true, if (PrivateHistorySync.hasToken(this)) cyan else gold)
        addText(historyBackup, "Ставки, сигналы ТОП и настройки сохраняются в репозитории и восстанавливаются после переустановки. Файл истории открыт и доступен всем, кто видит публичный репозиторий.", 11, false, muted)
        actionButton(historyBackup, if (PrivateHistorySync.hasToken(this)) "Изменить GitHub token" else "Подключить GitHub") {
            showPrivateHistoryTokenDialog()
        }
        actionButton(historyBackup, "Синхронизировать сейчас") {
            syncPrivateHistory(showResult = true)
        }

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
        lateinit var fixedStakeButton: Button
        lateinit var minimumStakeButton: Button
        fun styleStakeModeButton(button: Button, selected: Boolean) {
            UiMotion.animateTextColor(button, if (selected) Color.rgb(28, 22, 13) else Color.WHITE)
            val nextBackground = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (selected) intArrayOf(Color.rgb(244, 211, 145), Color.rgb(196, 151, 76))
                else intArrayOf(Color.rgb(25, 42, 63), Color.rgb(17, 29, 47))
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (selected) accent else Color.rgb(55, 72, 93))
            }
            UiMotion.transitionBackground(button, nextBackground)
        }
        val minimumStakeSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fixedStakeSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                    if (!settingsPrefs.edit().putString("stake_mode", mode).commit()) {
                        Toast.makeText(this@MainActivity, "Не удалось сохранить режим ставки", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    stakeModeButtons.forEach { (key, value) -> styleStakeModeButton(value, key == mode) }
                    // Switch the complete mode-specific section: labels, saved value, hint and action.
                    animateStakeModeVisibility(minimumStakeSettings, mode == "MINIMUM")
                    animateStakeModeVisibility(fixedStakeSettings, mode == "FIXED")
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
        fixedStakeHint = label(
            if (fixedStake() > 0.0) "Активна: каждая новая ставка будет ровно ${fmt(fixedStake())} ₽."
            else "Режим выбран, но сумма не задана. Укажи фиксированную сумму ниже.",
            11, false, muted
        )
        minimumStakeHint = label(minimumStakeDescription(), 11, false, muted)
        settings.addView(stakeModeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        addText(minimumStakeSettings, "Минимальная сумма ставки", 12, true, Color.WHITE)
        addText(minimumStakeSettings, if (minimumStake() > 0.0) fmt(minimumStake()) + " ₽" else "Не задана", 13, false, muted)
        minimumStakeSettings.addView(minimumStakeHint)
        minimumStakeButton = actionButton(minimumStakeSettings, "Изменить минимальную сумму ставки") {
            showBankSettingDialog("minimum_stake", "Минимальная сумма ставки", minimumStake(), "Укажи минимум для процентного режима. Введи 0, чтобы снять минимум.")
        }
        settings.addView(minimumStakeSettings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        addText(fixedStakeSettings, "Фиксированная сумма ставки", 12, true, Color.WHITE)
        addText(fixedStakeSettings, if (fixedStake() > 0.0) fmt(fixedStake()) + " ₽ за ставку" else "Не задана", 13, false, muted)
        fixedStakeSettings.addView(fixedStakeHint)
        fixedStakeButton = actionButton(fixedStakeSettings, if (fixedStake() > 0.0) "Изменить фиксированную сумму" else "Задать фиксированную сумму") {
            showBankSettingDialog("fixed_stake", "Фиксированная сумма ставки", fixedStake(), "Укажи сумму в рублях для каждой новой ставки. Введи 0, чтобы очистить значение.")
        }
        settings.addView(fixedStakeSettings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val selectedStakeMode = stakeMode()
        minimumStakeSettings.visibility = if (selectedStakeMode == "MINIMUM") View.VISIBLE else View.GONE
        fixedStakeSettings.visibility = if (selectedStakeMode == "FIXED") View.VISIBLE else View.GONE

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
        addText(glossary, "Вероятность модели", 12, true, Color.WHITE)
        addText(glossary, "Оценка шанса выбранного исхода. 65% — оценка модели, а не гарантия результата.", 10, false, muted)
        addText(glossary, "Коэффициент MelBet", 12, true, Color.WHITE)
        addText(glossary, "Выплата вместе с возвращаемой ставкой. При коэффициенте 2.00 выигрыш возвращает 2 ₽ на каждый поставленный 1 ₽.", 10, false, muted)
        addText(glossary, "Историческая база", 12, true, Color.WHITE)
        addText(glossary, "Доля таких исходов в похожих матчах до даты игры; рядом указано число матчей в выборке. Это сравнение с историей, не обещание результата.", 10, false, muted)
        addText(glossary, "Edge — преимущество", 12, true, Color.WHITE)
        addText(glossary, "Разница между вероятностью модели и вероятностью безубыточности по коэффициенту, в процентных пунктах. В ТОП может учитываться поправка на форму.", 10, false, muted)
        addText(glossary, "EV — ожидаемая ценность", 12, true, Color.WHITE)
        addText(glossary, "Вероятность модели × коэффициент − 1. EV +5% — расчётное ожидание +5 копеек на 1 ₽ ставки на длинной дистанции, а не прибыль в конкретном матче.", 10, false, muted)
        addText(glossary, "ROI — доходность", 12, true, Color.WHITE)
        addText(glossary, "Чистая прибыль или убыток, делённые на оборот рассчитанных ставок или прогнозов. Ожидающие расчёта позиции в ROI не входят.", 10, false, muted)
        addText(glossary, "Отбор ТОП", 12, true, Color.WHITE)
        addText(glossary, "Сигнал проходит отбор при вероятности от 60%, EV от +5%, Edge от +5 п.п. и коэффициенте MelBet от 1.50, если рынок поддерживается приложением.", 10, false, muted)
        addText(glossary, "Показатели помогают сравнивать варианты, но не гарантируют выигрыш.", 10, true, gold)

        val info = card()
        addText(info, "О ПРИЛОЖЕНИИ", 10, true, accent)
        addText(info, "IQ MATCH · SMART FOOTBALL INTELLIGENCE", 13, true, Color.WHITE)
        addText(info, "Матчи и коэффициенты: API-Sport / MelBet. Модельные вероятности и xG: PitchAPI.", 11, false, muted)
        addText(info, "Текущая версия: ${(packageManager.getPackageInfo(packageName, 0).versionName ?: "")}", 11, false, muted)
        actionButton(info, "Проверить обновление") { checkForAppUpdate() }
    }

    // Only show a strongest signal when its market is actually listed in the match card.
    // Under markets are intentionally excluded; team totals shown in the card are OVER (ИТБ) only.
    private fun strongestSignalDisplayLabel(signal: JSONObject, match: JSONObject): String? {
        val side = signal.optString("side").trim().replace(Regex("\\s+"), " ")
        if (side.isBlank()) return null
        if (side.contains("ИТМ", ignoreCase = true) || side.startsWith("ТМ ", ignoreCase = true) ||
            side.contains("UNDER", ignoreCase = true) || side.equals("Не X2", ignoreCase = true)) return null
        if (side.equals("1X", ignoreCase = true) || side.equals("X2", ignoreCase = true) ||
            side.equals("ОЗ ДА", ignoreCase = true) || side.equals("ТБ 2.5", ignoreCase = true)) return side
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
            val ranking = positiveValue.map {
                it.optDouble("modelProbability", Double.NaN) to it.optDouble("lineOdds", Double.NaN)
            }
            positiveValue[TopSignalRanking.bestIndex(ranking) ?: 0]
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
            "1x", "x2", "over25",
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
        if (hasTotal("totals", "over25", 2.5)) return true

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
        return hasOverQuote("totals", setOf(2.5)) || hasOverQuote("teamTotals", setOf(0.5, 1.5))
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
                animateContentRefresh { render() }
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
        UiMotion.animateTextChange(headerBalanceView,
            if (hasInitialBankroll()) String.format(Locale.US, "%.0f ₽", currentBankroll()) else "Баланс —")
        val (roi, count) = aiPaperRoiPercent()
        UiMotion.animateTextChange(headerAiRoiView,
            if (roi == null) "ТОП ROI —" else String.format(Locale.US, "ТОП ROI %+.1f%% · %d", roi, count))
        UiMotion.animateTextColor(headerAiRoiView, if (roi == null) muted else if (roi >= 0.0) accent else gold)
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
                animateContentRefresh { render() }
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
                    selectTab(destination)
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
            Thread(Runnable {
                saveBets()
                runOnUiThread {
                    Toast.makeText(this, "Ставка ТОП добавлена в историю", Toast.LENGTH_SHORT).show()
                    onRecorded?.invoke()
                    if (currentTab == "stats") {
                        tabViewCache.remove("stats|ALL")
                        animateContentRefresh { render() }
                    }
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
        if (PrivateHistorySync.hasToken(this)) PrivateHistorySync.syncAsync(this)
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
                        UiMotion.animateTextChange(recordButton, "✓ Все сигналы записаны")
                        recordButton.isEnabled = false
                        return@actionButton
                    }
                    val onRecorded: (JSONObject) -> Unit = { signal ->
                        statusViews[signal]?.apply {
                            UiMotion.animateTextChange(this, "Ожидает расчёт")
                            UiMotion.animateTextColor(this, accent)
                        }
                        // Update the visible TOP lists and counters in place; avoid a full
                        // Activity render so the screen stays responsive after saving.
                        UiMotion.animateTextChange(recordButton, "✓ Ставка зафиксирована")
                        recordButton.isEnabled = false
                        openSignalsCount = (openSignalsCount - 1).coerceAtLeast(0)
                        UiMotion.animateTextChange(openIntroCountView, "НОВЫЕ СИГНАЛЫ · $openSignalsCount")
                        val stillOpenInMatch = statusViews.keys.count { it !== signal && !isAiBetRecorded(match, it) }
                        if (stillOpenInMatch == 0) UiMotion.animateVisibility(box, false, dp(6).toFloat())
                        if (openSignalsCount == 0) {
                            emptyOpenCard?.let { UiMotion.animateVisibility(it, true, dp(6).toFloat()) }
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
        addText(summary, "Допуск: p модели ≥60%, преимущество над break-even ≥5 п.п., EV ≥5% и реальная цена MelBet. Фильтра давности в 5 минут нет; бумажная запись не является реальной ставкой. Финансовые лимиты риска не применяются: нет ограничений по просадке, дневной/матчевой экспозиции или максимальной доле ставки. Сумма бумажной ставки задаётся пользователем. Разные рынки одного матча разрешены; повтор того же рынка блокируется.", 11, false, muted)
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
        if (renderedKey == targetKey && !(targetKey == "stats|ALL" && pendingStatsScrollRestore != null)) {
            tabScrollPositions[targetKey] = mainScrollView.scrollY
        }

        // Снимаем текущие View с экрана и кладём в кеш, не уничтожая их.
        if (renderedKey != null && renderedKey != targetKey && matchesContainer.childCount > 0) {
            if (!(renderedKey == "stats|ALL" && pendingStatsScrollRestore != null)) {
                tabScrollPositions[renderedKey!!] = mainScrollView.scrollY
            }
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
        UiMotion.animateTextChange(statusView, "●  Источник обновлён: $sourceUpdated · проверено: ${formatLocalTimestamp(checkedAt)}")
        renderTabs()
        if (currentTab == "matches") renderLeagueTabs()
        if (currentTab == "stats") {
            leagueBar.animate().cancel()
            leagueBar.visibility = View.GONE
            leagueBar.alpha = 1f
            leagueBar.translationY = 0f
        } else {
            UiMotion.animateVisibility(leagueBar, currentTab == "matches", dp(6).toFloat())
        }

        // Возвращаем готовый экран мгновенно. Полная отрисовка нужна только при первом входе.
        val cached = tabViewCache[targetKey]
        if (cached != null) {
            cached.first.forEach { matchesContainer.addView(it) }
            cached.second.forEach { leagueBar.addView(it) }
            UiMotion.animateVisibility(leagueBar, currentTab == "matches", dp(6).toFloat())
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
            hideLineHeadingImmediately()
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            renderDay(json)
            return
        }
        if (currentTab == "notifications") {
            hideLineHeadingImmediately()
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            renderNotificationCenter(json)
            renderDailyInsights(json)
            return
        }
        if (currentTab == "desk") {
            hideLineHeadingImmediately()
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            renderBettingDesk(json)
            return
        }
        if (currentTab == "history") {
            hideLineHeadingImmediately()
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            renderBetHistory(json)
            return
        }
        if (currentTab == "stats") {
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            UiMotion.animateVisibility(lineHeadingView, false, dp(6).toFloat())
            try {
                renderStats(json)
            } catch (error: Throwable) {
                android.util.Log.e("IQMatchStats", "Unable to render statistics screen", error)
                CrashDiagnostics.recordHandledException(applicationContext, "Statistics screen rendering failed", error)
                matchesContainer.removeAllViews()
                // card() attaches itself to matchesContainer; do not add the same child twice.
                val failure = card()
                addText(failure, "Не удалось открыть статистику", 15, true, Color.WHITE)
                addText(failure, "Диагностический отчёт сохранён. Вернитесь на другой раздел и попробуйте снова.", 11, false, muted)
            }
            return
        } else if (currentTab == "help") {
            hideLineHeadingImmediately()
            UiMotion.animateVisibility(leagueBar, false, dp(6).toFloat())
            renderHelp()
            return
        } else {
            UiMotion.animateVisibility(lineHeadingView, true, dp(6).toFloat())
            UiMotion.animateVisibility(leagueBar, true, dp(6).toFloat())
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
                UiMotion.animateVisibility(content, nextExpanded, dp(6).toFloat())
                UiMotion.animateTextChange(expandIcon, if (nextExpanded) "  ▾" else "  ▸")
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
            upcoming.sortedWith(compareBy<JSONObject> { kickoffTimestamp(it) ?: Long.MAX_VALUE }).forEach { m ->
                val box = card(content)
                // Match the LINE fixture card palette to Statistics → RESULTS BY MARKETS.
                box.setPadding(dp(13), dp(12), dp(13), dp(12))
                box.background = GradientDrawable().apply {
                    setColor(Color.rgb(14, 24, 38))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.rgb(91, 78, 55))
                }
                (box.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = dp(5)
                    bottomMargin = dp(6)
                }
                addText(box, "★  " + kickoffLabel(m), 10, true, gold)
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
                addText(box, matchStatusLabel + scoreText, 11, true,
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
                    signalBox.setPadding(dp(9), dp(9), dp(9), dp(9))
                    signalBox.background = rounded(Color.rgb(13, 29, 46), 12)
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
                    mainMarkets.setPadding(dp(9), dp(9), dp(9), dp(9))
                    mainMarkets.background = rounded(Color.rgb(13, 29, 46), 12)
                    addMarketTitle(mainMarkets, "!", "РЫНКИ И КОТИРОВКИ")
                    val bestOdds = m.optJSONObject("odds")?.optJSONObject("best")
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
                    fun scalarQuote(obj: JSONObject?, key: String): String {
                        val price = obj?.optDouble(key, Double.NaN) ?: Double.NaN
                        return if (price.isFinite() && price > 1.0) String.format(Locale.US, "%.2f", price) else "—"
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

                    val doubleChance = bestOdds?.optJSONObject("doubleChance")
                    val btts = bestOdds?.optJSONObject("btts")
                    addMarketPair(
                        marketGroup("ДВОЙНОЙ ШАНС", listOf(
                            marketLine("1X", pct(pred, "1x"), scalarQuote(doubleChance, "1x")),
                            marketLine("X2", pct(pred, "x2"), scalarQuote(doubleChance, "x2"))
                        )),
                        marketGroup("ОБЕ КОМАНДЫ ЗАБЬЮТ", listOf(
                            marketLine("ОЗ — да", pct(pred, "bttsYes"), scalarQuote(btts, "yes"))
                        ))
                    )
                    addMarketPair(
                        marketGroup("ОБЩИЙ ТОТАЛ", listOf(
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