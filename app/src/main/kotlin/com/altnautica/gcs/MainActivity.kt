package com.altnautica.gcs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.input.InputRouter
import com.altnautica.gcs.feature.settings.AppLocales
import com.altnautica.gcs.feature.settings.AppSettingsRepository
import com.altnautica.gcs.feature.settings.AppearanceSettings
import com.altnautica.gcs.navigation.PairLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var inputRouter: InputRouter

    @Inject
    lateinit var appSettings: AppSettingsRepository

    /** Pair deep links (`adosgcs://pair?code=&host=`), from launch and from later intents. */
    private val pairLinks = Channel<PairLink>(Channel.BUFFERED)
    private val pairLinkFlow = pairLinks.receiveAsFlow()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A restored activity already shows whatever the original link opened.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val appearance by appSettings.appearance.collectAsStateWithLifecycle(AppearanceSettings())
            AdosTheme(themeId = appearance.theme, gloveMode = appearance.gloveMode) {
                AppRoot(pairLinks = pairLinkFlow)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        parsePairLink(intent)?.let { pairLinks.trySend(it) }
    }

    // Gamepad axes and hardware keys reach the input router before the view
    // hierarchy so flight bindings work regardless of which view has focus.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        inputRouter.onMotion(event) || super.dispatchGenericMotionEvent(event)

    // Activity.dispatchKeyEvent is public platform API; the restriction marker
    // sits on the androidx.core ComponentActivity override in between.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputRouter.onKey(event) || super.dispatchKeyEvent(event)

    companion object {
        /** `adosgcs://pair?code=ABCD&host=192.168.1.50`; null for any other intent. */
        fun parsePairLink(intent: Intent?): PairLink? {
            val uri = intent?.data ?: return null
            if (intent.action != Intent.ACTION_VIEW || uri.scheme != "adosgcs" || uri.host != "pair") return null
            val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            val host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }
            return if (code == null && host == null) null else PairLink(code, host)
        }
    }
}
