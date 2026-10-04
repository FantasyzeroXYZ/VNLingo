package com.tyranor.next.ui.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.settings.EngineSettingsActivity
import com.tyranor.next.ui.settings.engineSettingsKindTitle
import com.tyranor.next.ui.game.startActivityWithPageTransition
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 引擎管理页 Activity：由设置页进入，聚合引擎管理（安装状态/运行时/版本弹窗）
 * 与各引擎细分设置入口（原引擎设置菜单合并于此）。
 */
class EngineManageActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            EngineManageScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, EngineManageActivity::class.java)
    }
}

/** 引擎管理页：引擎细分设置入口卡片 + 引擎列表（EngineScreen 同源）。 */
@Composable
private fun EngineManageScreen() {
    val ctx = LocalContext.current
    EngineScreen(Modifier) {
        MiuixSettingsTheme {
            MiuixCard(
                modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                cornerRadius = AppComponentCornerRadius,
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    EngineSettingsKind.entries.forEach { kind ->
                        val title = engineSettingsKindTitle(kind)
                        ArrowPreference(
                            title = title,
                            startAction = {
                                Icon(
                                    painter = painterResource(kind.iconRes),
                                    contentDescription = title,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 6.dp).size(24.dp),
                                )
                            },
                            onClick = {
                                startActivityWithPageTransition(ctx, EngineSettingsActivity.createIntent(ctx, kind))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 引擎细分设置类型：与引擎设置 Activity 共用，标识各引擎配置页（随引擎支持裁剪收敛）。 */
enum class EngineSettingsKind(@param:StringRes val titleRes: Int, @param:DrawableRes val iconRes: Int) {
    KRKR(R.string.engine_settings_krkr_title, R.drawable.ic_settings_engine),
    ONS(R.string.engine_settings_ons_title, R.drawable.ic_settings_engine),
    ARTEMIS(R.string.engine_settings_artemis_title, R.drawable.ic_settings_engine),
    RPG_MAKER(R.string.engine_settings_rpg_maker_title, R.drawable.ic_settings_engine),
    TYRANO(R.string.engine_settings_tyrano_title, R.drawable.ic_settings_engine),
    RENPY(R.string.engine_settings_renpy_title, R.drawable.ic_settings_engine),
}
