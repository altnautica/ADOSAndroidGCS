package com.altnautica.gcs.radio.rx.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.altnautica.gcs.radio.rx.bind.BindController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Starts "Bind a drone" from the fleet bench, the same bind the screen runs:
 *
 *     adb shell am broadcast -a com.altnautica.gcs.bench.BIND_DRONE -p <package>
 *
 * Progress and the result are logged under the `AdosPhoneBind` tag. Debug
 * builds only.
 */
@AndroidEntryPoint
class BenchBindReceiver : BroadcastReceiver() {

    @Inject lateinit var binder: BindController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val started = binder.start()
        Log.i(TAG, if (started) "bench bind started" else "bench bind refused: a bind is already running")
    }

    companion object {
        const val ACTION = "com.altnautica.gcs.bench.BIND_DRONE"
        private const val TAG = "AdosPhoneBind"
    }
}
