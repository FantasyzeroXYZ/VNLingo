package com.tyranor.next.ui.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.ui.common.AppScreenActivity

/**
 * 引擎管理页 Activity：由设置页进入。列表即总控——每行聚合引擎的
 * 安装/启用状态、点击直达该引擎细分设置（原「引擎设置列表」已并入，
 * 见 [EngineScreen]），状态图标点开版本条目/安装管理弹窗。
 */
class EngineManageActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            EngineScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, EngineManageActivity::class.java)
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
