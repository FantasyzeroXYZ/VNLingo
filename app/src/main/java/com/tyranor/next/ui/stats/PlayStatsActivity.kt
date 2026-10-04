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
    val summary = PlayTimeReader.summarize(PlayTimeReader.readAll(context))
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
                        value = formatDuration(context, summary.grandTotalMs),
                        modifier = Modifier.weight(1f),
                    )
                    SummaryCard(
                        label = stringResource(R.string.play_stats_week),
                        value = formatDuration(context, summary.weekMs),
                        modifier = Modifier.weight(1f),
                    )
                    SummaryCard(
                        label = stringResource(R.string.play_stats_month),
                        value = formatDuration(context, summary.monthMs),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (summary.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.play_stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(summary.entries.size) { index ->
                val entry = summary.entries[index]
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavWhite),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = PlayTimeReader.displayName(entry.gameKey),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.play_stats_total) + " " +
                                formatDuration(context, entry.totalMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
