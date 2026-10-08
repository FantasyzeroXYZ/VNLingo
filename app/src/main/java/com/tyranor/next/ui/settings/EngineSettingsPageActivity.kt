package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.core.ons.EngineSettingsPageKind
import com.tyranor.next.ui.common.AppScreenActivity

/**
 * 引擎侧设置独立页（翻译 / Anki 制卡 / TTS）：样式与其它二级设置页完全一致
 * （[AppScreenActivity] + MiuixSettingsTheme + MiuixCard + Miuix preference），
 * 内容由 app 侧 Compose 实现（见 [EngineSettingsScreens]）——不再用 engine 的
 * 纯 Android View 弹窗内容，避免与全 App 设置页风格割裂。
 */
class EngineSettingsPageActivity : AppScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: EngineSettingsPageKind.TRANSLATE
        setAppScreenContent {
            when (kind) {
                EngineSettingsPageKind.ANKI -> AnkiCardSettingsScreen()
                EngineSettingsPageKind.TTS -> TtsSettingsScreen()
                else -> TranslateSettingsScreen()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "engine_settings_kind"

        fun createIntent(context: Context, kind: String): Intent =
            Intent(context, EngineSettingsPageActivity::class.java).putExtra(EXTRA_KIND, kind)
    }
}
