package me.him188.ani.android.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.subject.episode.EpisodePage
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel

class EpisodeActivity : BaseComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val episodeId = intent.getIntExtra("episodeId", 0).takeIf { it != 0 } ?: run {
            finish()
            return
        }
        val subjectId = intent.getIntExtra("subjectId", 0).takeIf { it != 0 } ?: run {
            finish()
            return
        }
        enableDrawingToSystemBars()

        val vm = EpisodeViewModel(subjectId, episodeId)
        setContent {
            MaterialTheme(currentColorScheme) {
                Column(Modifier.fillMaxSize()) {
                    EpisodePage(vm, goBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun getIntent(context: android.content.Context, subjectId: Int, episodeId: Int): android.content.Intent {
            return android.content.Intent(context, EpisodeActivity::class.java).apply {
                putExtra("subjectId", subjectId)
                putExtra("episodeId", episodeId)
            }
        }
    }
}