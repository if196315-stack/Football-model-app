package com.footballmodel.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PremiumBackground = Color(0xFF070D19)
private val PremiumPanel = Color(0xFF111D2F)
private val PremiumSelected = Color(0xFFDAB870)
private val PremiumGold = Color(0xFFEBD7AE)
private val PremiumText = Color(0xFFF5F6F8)
private val PremiumMuted = Color(0xFF9BA9B8)

@Composable
fun IQMatchPremiumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = PremiumBackground,
            surface = PremiumPanel,
            primary = PremiumSelected,
            onPrimary = PremiumBackground,
            onBackground = PremiumText,
            onSurface = PremiumText,
            secondary = PremiumGold
        ),
        content = content
    )
}

data class PremiumStatsBand(val label: String, val n: Int, val hits: Int)
data class PremiumStatsMetric(
    val title: String,
    val n: Int,
    val hits: Int,
    val brier: Double,
    val logLoss: Double,
    val bands: List<PremiumStatsBand>
)

@Composable
fun PremiumStatsScreen(
    metrics: List<PremiumStatsMetric>,
    summaryLines: List<String>,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier
            .background(PremiumBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 22.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IQ MATCH  ·  PERFORMANCE CENTER", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Статистика", color = PremiumText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            StatsPanel(title = "СТАТИСТИКА ТОП СИГНАЛОВ") {
                if (summaryLines.isEmpty()) {
                    Text("История ставок пока пуста", color = PremiumMuted, fontSize = 12.sp)
                } else summaryLines.forEach { line ->
                    Text(line, color = PremiumText, fontSize = 12.sp)
                }
            }
        }
        if (loading) {
            item {
                StatsPanel(title = "ОБЩАЯ СВОДКА") {
                    Text("Подготавливаем статистику…", color = PremiumText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Расчёт выполняется в фоне. Интерфейс остаётся доступным.", color = PremiumMuted, fontSize = 12.sp)
                }
            }
        } else if (metrics.isEmpty()) {
            item {
                StatsPanel(title = "ОБЩАЯ СВОДКА") {
                    Text(
                        "Архив честных прогнозов только начинается. Матчи будут учитываться после фиксации прогноза до стартового свистка и получения финального счёта.",
                        color = PremiumMuted, fontSize = 12.sp
                    )
                }
            }
        } else {
            item {
                Text("ОБЩАЯ СВОДКА", color = PremiumGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            items(metrics.size) { index ->
                val metric = metrics[index]
                StatsPanel(title = metric.title) {
                    val rate = metric.hits * 100.0 / metric.n
                    Text(
                        "Попадания при пороге 50%: ${metric.hits}/${metric.n} (${String.format(java.util.Locale.US, "%.1f", rate)}%)",
                        color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text("ПОПАДАНИЯ ПО УВЕРЕННОСТИ ПРОГНОЗА", color = PremiumGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    metric.bands.forEach { band ->
                        val bandRate = if (band.n > 0) String.format(java.util.Locale.US, "%.1f%%", band.hits * 100.0 / band.n) else "—"
                        Text("${band.label}: ${band.hits}/${band.n} ($bandRate)", color = if (band.n > 0) PremiumText else PremiumMuted, fontSize = 12.sp)
                    }
                    Text(
                        "Brier Score: ${String.format(java.util.Locale.US, "%.4f", metric.brier)} · Log Loss: ${String.format(java.util.Locale.US, "%.4f", metric.logLoss)}",
                        color = PremiumMuted, fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PremiumPanel, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF293B52), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

private data class PremiumTab(val id: String, val title: String)

@Composable
fun IQMatchApp(
    selectedTab: String,
    showLine: Boolean,
    onSelectTab: (String) -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val tabs = buildList {
        if (showLine) add(PremiumTab("matches", "ЛИНИЯ"))
        add(PremiumTab("desk", "ТОП"))
        add(PremiumTab("history", "СТАВКИ"))
        add(PremiumTab("stats", "СТАТИСТИКА"))
    }
    androidx.compose.material3.Scaffold(
        containerColor = PremiumBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PremiumBackground)
                    .border(width = 1.dp, color = Color(0xFF24364C))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    PremiumNavigationItem(
                        tab = tab,
                        selected = selectedTab == tab.id,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectTab(tab.id) }
                    )
                }
            }
        },
        content = content
    )
}

@Composable
private fun PremiumNavigationItem(
    tab: PremiumTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) PremiumSelected else Color(0xFF1D3042),
        animationSpec = tween(durationMillis = 220),
        label = "tab-background"
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) PremiumBackground else PremiumGold,
        animationSpec = tween(durationMillis = 220),
        label = "tab-foreground"
    )
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tab.title,
            color = foreground,
            fontSize = if (tab.id == "stats") 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
