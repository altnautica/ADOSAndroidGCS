package com.altnautica.gcs.radio.rx.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.uplink.UplinkHandover
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the uplink handover from the fleet bench through the same calls the
 * Uplink section makes:
 *
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.UPLINK_TAKE --es node <box device id>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.UPLINK_GIVE_BACK
 *
 * The handover logs every state change under `AdosPhoneUplink`
 * (`uplink state <from> -> <to> reason=<…>`) and every take-back call it
 * receives; this receiver adds one result line per command. Debug builds only.
 */
@AndroidEntryPoint
class BenchUplinkReceiver : BroadcastReceiver() {

    @Inject lateinit var handover: UplinkHandover

    @Inject lateinit var store: InviteStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != TAKE && action != GIVE_BACK) return
        val node = intent.getStringExtra("node")
        val pending = goAsync()
        scope.launch {
            try {
                if (action == TAKE) {
                    val box = runCatching { store.load()?.bundle?.ground?.boxDeviceId }.getOrNull()
                    if (node == null || node != box) {
                        Log.i(TAG, "uplink bench take refused node=${node ?: "none"} invite_box=${box ?: "none"}")
                        return@launch
                    }
                    val s = handover.take()
                    Log.i(TAG, "uplink bench take result state=${s.phase.wire} note=${s.note?.english ?: "none"}")
                } else {
                    val s = handover.giveBack()
                    Log.i(TAG, "uplink bench give_back result state=${s.phase.wire} note=${s.note?.english ?: "none"}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAKE = "com.altnautica.gcs.bench.UPLINK_TAKE"
        const val GIVE_BACK = "com.altnautica.gcs.bench.UPLINK_GIVE_BACK"
        private const val TAG = "AdosPhoneUplink"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
