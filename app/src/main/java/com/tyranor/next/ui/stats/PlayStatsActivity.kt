package com.tyranor.next.ui.stats

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.playtime.PlayTimeReader
import com.tyranor.next.core.playtime.PlayTimeSummary
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar

/** 游玩统计页：总时长/本周/本月 + 每游戏列表。 */
class PlayStatsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            PlayStatsScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, PlayStatsActivity::class.java)
    }
}

private fun formatDuration(context: Context, ms: Long): String = when {
    ms < 60_000L -> context.getString(R.string.play_stats_unit_min, 1L)
    ms < 3_600_000L -> context.getString(R.string.play_stats_unit_min, ms / 60_000L)
    else -> context.getString(R.string.play_stats_unit_hour, ms / 3_600_000.0)
}

@Composable
private fun PlayStatsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 双数据源：会话制（新）+ 旧 JSON（历史记录兼容，不清）
    val legacySummary = remember { PlayTimeReader.summarize(PlayTimeReader.readAll(context)) }
    val sessionTotals = remember {
        com.tyranor.next.core.play.PlaySessionTracker.allTotals(context)
    }
    val recentList = remember {
        com.tyranor.next.core.play.PlaySessionTracker.recentSessions(context, 50)
    }
    val merged = LinkedHashMap<String, Long>()
    for ((uri, ms) in sessionTotals) {
        merged[uri] = (merged[uri] ?: 0L) + ms
    }
    for (e in legacySummary.entries) {
        merged[e.gameKey] = (merged[e.gameKey] ?: 0L) + e.totalMs
    }
    val mergedList = merged.entries.sortedByDescending { it.value }
    val now = System.currentTimeMillis()
    val weekAgo = now - 7L * 24 * 3600 * 1000
    val monthAgo = now - 30L * 24 * 3600 * 1000
    val sessionTotal = sessionTotals.values.sum()
    val sessionWeek = com.tyranor.next.core.play.PlaySessionTracker
        .durationsBetween(context, weekAgo, now).values.sum()
    val sessionMonth = com.tyranor.next.core.play.PlaySessionTracker
        .durationsBetween(context, monthAgo, now).values.sum()
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.play_stats_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(
                        label = stringResource(R.string.play_stats_total),
                        value = formatDuration(context, sessionTotal + legacySummary.grandTotalMs),
                        modifier = Modifier.weight(1f),
                    )
                    SummaryCard(
                        label = stringResource(R.string.play_stats_week),
                        value = formatDuration(context, sessionWeek + legacySummary.weekMs),
                        modifier = Modifier.weight(1f),
                    )
                    SummaryCard(
                        label = stringResource(R.string.play_stats_month),
                        value = formatDuration(context, sessionMonth + legacySummary.monthMs),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (mergedList.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.play_stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(mergedList.size) { index ->
                val entry = mergedList[index]
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavWhite),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = PlayTimeReader.displayName(entry.key),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.play_stats_total) + " " +
                                formatDuration(context, entry.value),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 最近游玩记录（会话粒度）
            if (recentList.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.play_stats_recent),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(recentList.size) { index ->
                    val rec = recentList[index]
                    val fmt = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = NavWhite.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                text = rec.gameTitle.ifBlank { rec.gameUri },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = fmt.format(java.util.Date(rec.endTime)) + " - " +
                                    formatDuration(context, rec.duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
