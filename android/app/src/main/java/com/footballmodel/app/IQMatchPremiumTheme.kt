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

data class PremiumLineMatch(
    val id: String,
    val league: String,
    val date: String,
    val home: String,
    val away: String,
    val signalTitle: String,
    val signalPositive: Boolean,
    val signalMetrics: List<String>,
    val signalContext: List<String>,
    val markets: List<Pair<String, List<String>>>,
    val quoteTime: String
)

@Composable
fun PremiumLineScreen(
    matches: List<PremiumLineMatch>,
    selectedLeague: String,
    onSelectLeague: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        "ALL" to "Все", "PL" to "АПЛ", "PD" to "Ла Лига", "SA" to "Серия А",
        "BL1" to "Бундеслига", "FL1" to "Лига 1", "UCL" to "ЛЧ", "UEL" to "ЛЕ", "UECL" to "ЛК"
    )
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.background(PremiumBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 18.dp, bottom = 22.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IQ MATCH  ·  MATCH CENTER", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Линия", color = PremiumText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Матчи с полным прогнозом и активной линией MelBet", color = PremiumMuted, fontSize = 12.sp)
            }
        }
        item {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(filters.size) { index ->
                    val (code, title) = filters[index]
                    val count = if (code == "ALL") matches.size else matches.count { match ->
                        when (code) {
                            "PL" -> match.league.contains("Premier League", true) || match.league.contains("АПЛ", true)
                            "PD" -> match.league.contains("Primera", true) || match.league.contains("La Liga", true)
                            "SA" -> match.league.contains("Serie A", true)
                            "BL1" -> match.league.contains("Bundesliga", true)
                            "FL1" -> match.league.contains("Ligue 1", true)
                            "UCL" -> match.league.contains("Champions League", true)
                            "UEL" -> match.league.contains("Europa League", true)
                            "UECL" -> match.league.contains("Conference League", true)
                            else -> false
                        }
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(22.dp))
                            .background(if (selectedLeague == code) PremiumSelected else PremiumPanel)
                            .clickable { onSelectLeague(code) }
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$title · $count", color = if (selectedLeague == code) PremiumBackground else PremiumMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        item {
            Text("МАТЧЕЙ В ЛИНИИ: ${matches.size}  ·  С ПОЛНЫМ ПРОГНОЗОМ: ${matches.size}", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (matches.isEmpty()) {
            item {
                StatsPanel(title = "НЕТ ДОСТУПНЫХ МАТЧЕЙ") {
                    Text("Нет предстоящих матчей с одновременно подтверждённой линией MelBet и полным прогнозом модели для выбранного фильтра.", color = PremiumMuted, fontSize = 12.sp)
                }
            }
        } else {
            items(matches.size, key = { matches[it].id }) { index ->
                val match = matches[index]
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(PremiumPanel, RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0xFF5B4E37), RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("${match.league.uppercase()}  ·  ${match.date}", color = PremiumGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("${match.home}  —  ${match.away}", color = PremiumText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (match.signalTitle.isNotBlank()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1D2E), RoundedCornerShape(13.dp)).padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("★  САМЫЙ СИЛЬНЫЙ ПРОГНОЗ В МАТЧЕ", color = PremiumGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(match.signalTitle, color = if (match.signalPositive) PremiumGold else Color(0xFFF2BE68), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            match.signalMetrics.forEach { Text(it, color = PremiumText, fontSize = 11.sp) }
                            match.signalContext.forEach { Text(it, color = PremiumMuted, fontSize = 10.sp) }
                            if (match.signalMetrics.isNotEmpty()) {
                                Text(if (match.signalPositive) "Выбран рынок с максимальной долей полного Kelly среди положительных EV и коэффициентов от 1.50." else "Нет рынка с положительным EV при коэффициенте от 1.50. Показан самый вероятный доступный вариант.", color = PremiumMuted, fontSize = 10.sp)
                            }
                        }
                    } else {
                        Text("Нет доступной котировки MelBet ни для одного из разрешённых рынков этого матча.", color = PremiumMuted, fontSize = 11.sp)
                    }
                    match.markets.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            pair.forEach { (title, rows) ->
                                Column(
                                    modifier = Modifier.weight(1f).background(Color(0xFF0D1D2E), RoundedCornerShape(11.dp)).padding(9.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(title, color = PremiumMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    rows.forEach { Text(it, color = PremiumText, fontSize = 10.sp) }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text(match.quoteTime, color = PremiumMuted, fontSize = 9.sp)
                }
            }
        }
        item {
            StatsPanel(title = "ВАЖНО") {
                Text("Пуассоновская модель. Исследовательские вероятности, не гарантия результата и не рекомендация для ставок.", color = PremiumMuted, fontSize = 11.sp)
                Text("Линия: API-Sport / MelBet · вероятности и xG: PitchAPI.", color = PremiumMuted, fontSize = 10.sp)
            }
        }
    }
}

data class PremiumHistoryBet(
    val id: String,
    val league: String,
    val date: String,
    val matchup: String,
    val details: String,
    val stake: String,
    val status: String,
    val result: String,
    val isPending: Boolean,
    val legs: List<String>
)

@Composable
fun PremiumBetHistoryScreen(
    bets: List<PremiumHistoryBet>,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onEditOdds: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val open = bets.filter { it.isPending }
    val completed = bets.filterNot { it.isPending }
    val visible = if (selectedTab == "OPEN") open else completed
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.background(PremiumBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 22.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IQ MATCH  ·  BET TRACKER", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Ставки", color = PremiumText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Открытые и завершённые ставки", color = PremiumMuted, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OPEN" to "Ожидает расчёта · ${open.size}", "COMPLETED" to "Завершённые · ${completed.size}").forEach { (key, label) ->
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selectedTab == key) PremiumSelected else PremiumPanel)
                            .clickable { onSelectTab(key) }.padding(vertical = 12.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (selectedTab == key) PremiumBackground else PremiumMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                StatsPanel(title = if (selectedTab == "OPEN") "ОТКРЫТЫЕ СТАВКИ" else "ЗАВЕРШЁННЫЕ") {
                    Text(if (selectedTab == "OPEN") "Ставок, ожидающих расчёта, пока нет." else "Завершённых ставок пока нет.", color = PremiumMuted, fontSize = 12.sp)
                }
            }
        } else {
            items(visible.size) { index ->
                val bet = visible[index]
                StatsPanel(title = "${bet.league} · ${bet.date}") {
                    Text(bet.matchup, color = PremiumText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(bet.details, color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(bet.stake, color = PremiumMuted, fontSize = 11.sp)
                    bet.legs.forEach { Text(it, color = PremiumMuted, fontSize = 10.sp) }
                    Text(bet.status, color = if (bet.status.startsWith("ВЫИГРЫШ")) PremiumGold else if (bet.status.startsWith("ПРОИГРЫШ")) Color(0xFFFF7777) else PremiumMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(bet.result, color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (bet.isPending) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF263A50)).clickable { onEditOdds(bet.id) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Text("Изменить кэф", color = PremiumText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A2730)).clickable { onDelete(bet.id) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Text("Удалить", color = Color(0xFFFFB3B3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A2730)).clickable { onDelete(bet.id) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text("Удалить", color = Color(0xFFFFB3B3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

data class PremiumTopSignal(
    val rank: Int,
    val league: String,
    val matchup: String,
    val kickoff: String,
    val side: String,
    val odds: String,
    val probability: String,
    val edge: String,
    val expectedValue: String,
    val baseline: String
)

data class PremiumTopBet(
    val rank: Int,
    val league: String,
    val matchup: String,
    val market: String,
    val odds: String,
    val stake: String,
    val probability: String,
    val status: String,
    val possibleReturn: String,
    val possibleProfit: String
)

@Composable
fun PremiumTopScreen(
    selectedTab: String,
    openSignals: List<PremiumTopSignal>,
    fixedBets: List<PremiumTopBet>,
    onSelectTab: (String) -> Unit,
    onRecord: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.background(PremiumBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IQ MATCH · MATCH INTELLIGENCE", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("ТОП", color = PremiumText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Новые сильные сигналы отдельно от уже зафиксированных бумажных ставок.", color = PremiumMuted, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopTabChip("Новые сигналы", openSignals.size, selectedTab == "OPEN", Modifier.weight(1f)) { onSelectTab("OPEN") }
                TopTabChip("В ставках", fixedBets.size, selectedTab == "FIXED", Modifier.weight(1f)) { onSelectTab("FIXED") }
            }
        }
        if (selectedTab == "OPEN") {
            item {
                Text("НОВЫЕ СИГНАЛЫ · ${openSignals.size}", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (openSignals.isEmpty()) {
                item {
                    TopPanel(title = "ПОКА НЕТ СИГНАЛОВ") {
                        Text("Новых сильных сигналов пока нет.", color = PremiumText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Здесь появятся матчи, прошедшие действующие фильтры ТОП.", color = PremiumMuted, fontSize = 12.sp)
                    }
                }
            } else {
                items(openSignals.size) { index ->
                    val signal = openSignals[index]
                    TopPanel(title = "МАТЧ #${signal.rank} · ${signal.league}") {
                        Text(signal.matchup, color = PremiumText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(signal.kickoff, color = PremiumMuted, fontSize = 11.sp)
                        Text("САМЫЙ СИЛЬНЫЙ СИГНАЛ", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(signal.side + " · MelBet " + signal.odds, color = PremiumGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Вероятность модели: ${signal.probability}", color = PremiumText, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("EDGE", color = PremiumMuted, fontSize = 9.sp)
                                Text(signal.edge, color = PremiumGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("EV", color = PremiumMuted, fontSize = 9.sp)
                                Text(signal.expectedValue, color = PremiumGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(signal.baseline, color = PremiumMuted, fontSize = 10.sp)
                        TopAction("Выбрать и зафиксировать", Modifier.fillMaxWidth()) { onRecord(index) }
                    }
                }
            }
        } else {
            item {
                Text("ЗАФИКСИРОВАННЫЕ СТАВКИ · ${fixedBets.size}", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (fixedBets.isEmpty()) {
                item {
                    TopPanel(title = "ИСТОРИЯ ПОКА ПУСТА") {
                        Text("Ставок ТОП, ожидающих расчёта, пока нет.", color = PremiumText, fontSize = 13.sp)
                        Text("После фиксации сигнала он появится здесь.", color = PremiumMuted, fontSize = 11.sp)
                    }
                }
            } else {
                items(fixedBets.size) { index ->
                    val bet = fixedBets[index]
                    TopPanel(title = "СТАВКА #${bet.rank} · ${bet.league}") {
                        Text(bet.matchup, color = PremiumText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(bet.market + " · кэф " + bet.odds, color = PremiumGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Ставка: ${bet.stake} · модель: ${bet.probability}", color = PremiumMuted, fontSize = 11.sp)
                        Text(bet.status, color = PremiumGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Возможная выплата: ${bet.possibleReturn} · чистый выигрыш: +${bet.possibleProfit}", color = PremiumText, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            TopPanel(title = "ПРАВИЛА ТОП") {
                Text("В ТОП попадают сильнейшие сигналы из раздела ЛИНИЯ: MelBet ≥1.50, вероятность модели ≥60%, edge ≥5 п.п. и EV ≥5%. Фиксация создаёт бумажную запись в истории приложения; букмекерские операции не выполняются.", color = PremiumMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TopTabChip(title: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(24.dp))
            .background(if (selected) PremiumSelected else PremiumPanel)
            .border(1.dp, if (selected) PremiumSelected else Color(0xFF37485D), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("$title · $count", color = if (selected) PremiumBackground else PremiumMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(PremiumPanel, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF293B52), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun TopAction(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF263C52))
            .border(1.dp, Color(0xFF8A744F), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(title, color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

data class PremiumSettingsState(
    val balance: String = "Не задан",
    val balanceDescription: String = "Задай стартовый банк, чтобы рассчитывать размер новых ставок.",
    val stakeMode: String = "MINIMUM",
    val minimumStake: String = "Не задана",
    val fixedStake: String = "Не задана",
    val minimumHint: String = "",
    val fixedHint: String = "",
    val showLine: Boolean = true
)

@Composable
fun PremiumSettingsScreen(
    settings: PremiumSettingsState,
    onEditBank: () -> Unit,
    onEditMinimum: () -> Unit,
    onEditFixed: () -> Unit,
    onModeChanged: (String) -> Unit,
    onShowLineChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.background(PremiumBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IQ MATCH · PERSONAL SETTINGS", color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Настройки", color = PremiumText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingsPanel(title = "ТЕКУЩИЙ БАЛАНС") {
                Text(settings.balance, color = PremiumText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(settings.balanceDescription, color = PremiumMuted, fontSize = 12.sp)
                SettingsAction(if (settings.balance == "Не задан") "Задать баланс" else "Изменить баланс", onEditBank)
            }
        }
        item {
            SettingsPanel(title = "НАСТРОЙКИ СУММЫ СТАВОК") {
                Text("РЕЖИМ РАСЧЁТА СТАВКИ", color = PremiumMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChoice("Минимальная", settings.stakeMode == "MINIMUM", Modifier.weight(1f)) { onModeChanged("MINIMUM") }
                    SettingsChoice("Фиксированная", settings.stakeMode == "FIXED", Modifier.weight(1f)) { onModeChanged("FIXED") }
                }
                Text("Минимальная сумма ставки", color = PremiumText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(settings.minimumStake, color = PremiumGold, fontSize = 15.sp)
                Text(settings.minimumHint, color = PremiumMuted, fontSize = 11.sp)
                SettingsAction("Изменить минимальную сумму", onEditMinimum)
                Text("Фиксированная сумма ставки", color = PremiumText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(settings.fixedStake, color = PremiumGold, fontSize = 15.sp)
                Text(settings.fixedHint, color = PremiumMuted, fontSize = 11.sp)
                SettingsAction(if (settings.fixedStake == "Не задана") "Задать фиксированную сумму" else "Изменить фиксированную сумму", onEditFixed)
            }
        }
        item {
            SettingsPanel(title = "ОТОБРАЖЕНИЕ РАЗДЕЛОВ") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onShowLineChanged(!settings.showLine) }.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Показывать раздел «ЛИНИЯ»", color = PremiumText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("При выключении вкладка ЛИНИЯ исчезает из нижней навигации.", color = PremiumMuted, fontSize = 11.sp)
                    }
                    Text(if (settings.showLine) "ВКЛ" else "ВЫКЛ", color = if (settings.showLine) PremiumGold else PremiumMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
        item {
            SettingsPanel(title = "СЛОВАРЬ ПОКАЗАТЕЛЕЙ") {
                GlossaryEntry("Вероятность модели", "Оценка вероятности выбранного исхода, а не гарантия результата конкретного матча.")
                GlossaryEntry("Коэффициент", "Показывает выплату вместе с возвращаемой суммой ставки; обратная величина даёт вероятность до учёта маржи.")
                GlossaryEntry("EV — ожидаемая доходность", "Вероятность модели × коэффициент − 1. Положительный EV — математическое ожидание на большой дистанции, не обещание прибыли.")
                GlossaryEntry("EDGE", "Разница между вероятностью модели и вероятностью, заложенной в коэффициенте, в процентных пунктах.")
                GlossaryEntry("Kelly", "Доля полного банка по формуле Келли. В приложении показатель используется для ранжирования сигналов, а не как команда ставить весь банк.")
                GlossaryEntry("xG — ожидаемые голы", "Модельная оценка качества голевых моментов и ожидаемого количества голов, не фактический счёт.")
                GlossaryEntry("Порог отбора ТОП", "Сигнал должен одновременно пройти пороги вероятности, EV, EDGE и коэффициента, а также ограничения по рынкам.")
                Text("Все модельные показатели являются оценками и не гарантируют выигрыш.", color = PremiumGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingsPanel(title = "О ПРИЛОЖЕНИИ") {
                Text("IQ MATCH · SMART FOOTBALL INTELLIGENCE", color = PremiumText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Матчи и коэффициенты: API-Sport / MelBet. Модельные вероятности и xG: PitchAPI.", color = PremiumMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(PremiumPanel, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF293B52), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = PremiumGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SettingsAction(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1D3042)).border(1.dp, Color(0xFF4B5260), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart
    ) { Text(title, color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SettingsChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) PremiumSelected else Color(0xFF1D3042))
            .border(1.dp, if (selected) PremiumSelected else Color(0xFF37485D), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (selected) PremiumBackground else PremiumText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GlossaryEntry(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = PremiumText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = PremiumMuted, fontSize = 11.sp)
    }
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
