package com.altnautica.gcs

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.input.InputRouter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var inputRouter: InputRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AdosTheme {
                AppRoot()
            }
        }
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
}
