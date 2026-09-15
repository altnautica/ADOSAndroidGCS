package com.altnautica.gcs.data.wifi

/**
 * A ground-station AP credential the operator supplied.
 *
 * [ssid] is null when the operator typed only a passphrase, in which case the
 * SSID prefix match does the selecting.
 */
data class GroundStationCredential(
    val passphrase: String,
    val ssid: String? = null,
) {
    /**
     * The SSID suffix to narrow the join to.
     *
     * The join uses a prefix match on `ADOS-GS-`, so a scanned QR that carried
     * this unit's full SSID narrows it to that unit, while a typed passphrase
     * alone leaves it empty (match any ADOS ground station in range).
     */
    val ssidSuffix: String
        get() = ssid
            ?.removePrefix(WifiConnectionManager.GS_SSID_PREFIX)
            ?.takeIf { it.isNotBlank() && it != ssid }
            ?: ""
}

/**
 * Parser for the standard WPA join string the node's on-box panel renders as a
 * QR code: `WIFI:T:WPA;S:<ssid>;P:<pass>;;`.
 *
 * Scanning that code (or pasting the string) is the intended way to get a
 * per-unit passphrase into this app without reading it off a screen. The
 * node escapes the format's own separators inside a value with a backslash, so
 * a passphrase containing `;` or `:` survives the round trip; unescaping is
 * therefore not optional — skipping it yields a credential that looks right and
 * joins nothing.
 */
object WifiJoinString {

    private const val PREFIX = "WIFI:"

    /**
     * Parse [raw] into a credential, or return null when it is not a WPA join
     * string carrying a passphrase.
     *
     * `T:nopass` is rejected: an open network has no passphrase to store, and
     * treating it as an empty one would produce exactly the blank-passphrase
     * join [WifiConnectionManager] refuses.
     */
    fun parse(raw: String): GroundStationCredential? {
        val body = raw.trim().takeIf { it.startsWith(PREFIX, ignoreCase = true) }
            ?.substring(PREFIX.length)
            ?: return null

        val fields = splitUnescaped(body)
        var type: String? = null
        var ssid: String? = null
        var pass: String? = null
        for (field in fields) {
            val sep = indexOfUnescaped(field, ':') ?: continue
            val key = field.substring(0, sep)
            val value = unescape(field.substring(sep + 1))
            when (key.uppercase()) {
                "T" -> type = value
                "S" -> ssid = value
                "P" -> pass = value
            }
        }

        if (type != null && type.equals("nopass", ignoreCase = true)) return null
        val passphrase = pass?.takeIf { it.isNotEmpty() } ?: return null
        return GroundStationCredential(
            passphrase = passphrase,
            ssid = ssid?.takeIf { it.isNotEmpty() },
        )
    }

    /** Split on `;` separators that are not backslash-escaped. */
    private fun splitUnescaped(body: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in body) {
            when {
                escaped -> {
                    current.append('\\').append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == ';' -> {
                    out.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.filter { it.isNotEmpty() }
    }

    private fun indexOfUnescaped(field: String, target: Char): Int? {
        var escaped = false
        for ((i, c) in field.withIndex()) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == target -> return i
            }
        }
        return null
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var escaped = false
        for (c in value) {
            if (escaped) {
                out.append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }
}
