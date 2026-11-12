package com.altnautica.gcs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandDispatcher
import com.altnautica.gcs.core.designsystem.component.console.ControllerUi
import com.altnautica.gcs.core.designsystem.component.console.GlyphSet
import com.altnautica.gcs.core.designsystem.component.console.LocalConsoleCommands
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputRouter
import com.altnautica.gcs.core.skills.input.controller.ControllerFamily
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.input.ControllerUiBridge
import com.altnautica.gcs.feature.home.DisclaimerGate
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

    /** The same router, for the controller UI stream and the last-input state. */
    @Inject
    lateinit var adosInputRouter: AdosInputRouter

    @Inject
    lateinit var controllerManager: ControllerManager

    @Inject
    lateinit var appSettings: AppSettingsRepository

    private val consoleCommands = ConsoleCommandDispatcher()

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
        ControllerUiBridge(this, adosInputRouter, consoleCommands).start()
        setContent {
            val appearance by appSettings.appearance.collectAsStateWithLifecycle(AppearanceSettings())
            val controllerLast by adosInputRouter.lastInputController.collectAsStateWithLifecycle()
            val pad by controllerManager.active.collectAsStateWithLifecycle()
            val playStation = pad?.family == ControllerFamily.PlayStation
            val controllerUi = ControllerUi(
                active = controllerLast,
                glyphs = if (playStation) GlyphSet.PlayStation else GlyphSet.Generic,
                batteryPct = pad?.batteryPct,
            )
            // Outside the cockpit a PlayStation light bar shows the theme accent.
            LaunchedEffect(pad?.deviceId, playStation, appearance.theme) {
                if (playStation) controllerManager.setLightbar(AdosColors.forTheme(appearance.theme).accentPrimary.toArgb())
            }
            // System bar icons follow the theme: dark icons on the light theme only.
            LaunchedEffect(appearance.theme) {
                val light = appearance.theme == AdosThemeId.BrandLight
                enableEdgeToEdge(
                    statusBarStyle = if (light) {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (light) {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    },
                )
            }
            CompositionLocalProvider(LocalConsoleCommands provides consoleCommands, LocalControllerUi provides controllerUi) {
                AdosTheme(themeId = appearance.theme, gloveMode = appearance.gloveMode) {
                    ProvideOverlayHost {
                        // Nothing, deep links included, opens until the current safety disclaimer is accepted.
                        DisclaimerGate { AppRoot(pairLinks = pairLinkFlow) }
                    }
                }
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

    // Touch makes touch the last input, which hides controller hints.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) inputRouter.onTouch()
        return super.dispatchTouchEvent(event)
    }

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
