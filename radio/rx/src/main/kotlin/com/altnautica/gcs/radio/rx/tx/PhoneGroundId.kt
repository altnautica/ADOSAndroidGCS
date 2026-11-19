package com.altnautica.gcs.radio.rx.tx

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device id this phone announces itself under as a ground: twelve lower-case
 * hex characters, the shape an agent's own id takes, generated once and kept
 * for the life of the install. A drone shows it as its linked ground.
 */
@Singleton
class PhoneGroundId @Inject constructor(@ApplicationContext private val context: Context) {

    val value: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf(::isValid) ?: generate().also { prefs.edit().putString(KEY, it).apply() }
    }

    companion object {
        private const val PREFS = "phone_ground"
        private const val KEY = "device_id"
        private const val BYTES = 6

        fun isValid(id: String): Boolean = id.length == BYTES * 2 && id.all { it in '0'..'9' || it in 'a'..'f' }

        fun generate(random: SecureRandom = SecureRandom()): String =
            ByteArray(BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
