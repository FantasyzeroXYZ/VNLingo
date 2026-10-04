package com.tyranor.next.ui.screenshots

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.core.ons.GameScreenshots
import com.tyranor.next.R
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截图管理页（主页入口）：展示游戏内「截图」键保存的截图
 * （getExternalFilesDir/screenshots/<游戏>/），支持查看大图、分享与删除。
 */
class ScreenshotManagerActivity : AppScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            ScreenshotManagerScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ScreenshotManagerActivity::class.java)
    }
}

private data class ScreenshotItem(
    val file: File,
    val game: String,
    val timeLabel: String,
)

/** 缩略图解码（inSampleSize 压到 ~256px 宽，网格滚动不卡顿）。 */
@Composable
private fun thumbnailFor(file: File): Bitmap? = produceState<Bitmap?>(initialValue = null, file) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 256) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}.value

@Composable
private fun ScreenshotManagerScreen() {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val shots = remember(reload) {
        GameScreenshots.list(context).map { file ->
            ScreenshotItem(
                file = file,
                game = GameScreenshots.gameOf(file),
                timeLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(file.lastModified())),
            )
        }
    }
    var preview by remember { mutableStateOf<ScreenshotItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.screenshots_title))

        if (shots.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.screenshots_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.screenshots_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            Text(
                stringResource(R.string.screenshots_count, shots.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = shots, key = { it.file.absolutePath }) { shot ->
                    ScreenshotCell(shot = shot, onClick = { preview = shot })
                }
            }
        }
    }

    preview?.let { shot ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = {
                Text(shot.game, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    val bmp = thumbnailFor(shot.file)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = shot.game,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(
                        shot.timeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareScreenshot(context, shot.file)
                }) { Text(stringResource(R.string.screenshots_share)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    shot.file.delete()
                    preview = null
                    reload++
                }) { Text(stringResource(R.string.screenshots_delete)) }
            },
        )
    }
}

@Composable
private fun ScreenshotCell(shot: ScreenshotItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        val bmp = thumbnailFor(shot.file)
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = shot.game,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text("…", style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                shot.game,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shot.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分享截图（FileProvider content URI，授权读取）。 */
private fun shareScreenshot(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (ignored: Throwable) {
    }
}
