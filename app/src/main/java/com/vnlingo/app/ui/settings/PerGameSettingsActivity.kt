package com.vnlingo.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.vnlingo.app.core.game.model.ScanGame
import com.vnlingo.app.core.game.model.ScanGameIntents
import com.vnlingo.app.ui.common.AppScreenActivity

class PerGameSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setAppScreenContent {
            PerGameSettingsScreen(game = game)
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, PerGameSettingsActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}
