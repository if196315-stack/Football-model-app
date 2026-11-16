package com.footballmodel.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
