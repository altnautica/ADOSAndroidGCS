package com.altnautica.gcs.feature.connect

import com.altnautica.gcs.core.agent.discovery.PairingCode

/** Field-level problems the connect forms report before anything is sent. */
enum class InputError { Required, InvalidPort, InvalidHost, InvalidCode, KeyTooShort }

/** Pure validation for the connect forms. */
object EndpointValidation {

    private val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private const val MIN_API_KEY = 16

    fun port(text: String): InputError? {
        val t = text.trim()
        if (t.isEmpty()) return InputError.Required
        val p = t.toIntOrNull() ?: return InputError.InvalidPort
        return if (p in 1..65535) null else InputError.InvalidPort
    }

    /**
     * A host as the operator types it: IPv4, IPv6 (bare or bracketed), or a DNS
     * / mDNS name, optionally followed by `:port` (not for bare IPv6).
     */
    fun host(text: String, allowPort: Boolean = true): InputError? {
        val t = text.trim()
        if (t.isEmpty()) return InputError.Required
        if (t.contains("://") || t.contains('/') || t.any { it.isWhitespace() }) return InputError.InvalidHost
        val (h, port) = splitHostPort(t) ?: return InputError.InvalidHost
        if (port != null && (!allowPort || port(port) != null)) return InputError.InvalidHost
        return when {
            h.contains(':') -> if (isIpv6(h)) null else InputError.InvalidHost
            h.all { it.isDigit() || it == '.' } -> if (IPV4.matches(h)) null else InputError.InvalidHost
            HOSTNAME.matches(h) -> null
            else -> InputError.InvalidHost
        }
    }

    fun code(text: String): InputError? {
        val n = PairingCode.normalize(text)
        if (n.isEmpty()) return InputError.Required
        return if (PairingCode.isValid(n)) null else InputError.InvalidCode
    }

    /** An API key is optional; when present it must look like one. */
    fun apiKey(text: String): InputError? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (t.length >= MIN_API_KEY && t.none { it.isWhitespace() }) null else InputError.KeyTooShort
    }

    /** `host` → (host, null); `host:port` → (host, port); `[v6]:port` → (v6, port); bare v6 → (v6, null). */
    internal fun splitHostPort(t: String): Pair<String, String?>? {
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) return null
            val h = t.substring(1, end)
            val rest = t.substring(end + 1)
            return when {
                rest.isEmpty() -> h to null
                rest.startsWith(":") -> h to rest.drop(1)
                else -> null
            }
        }
        val colons = t.count { it == ':' }
        return when (colons) {
            0 -> t to null
            1 -> t.substringBefore(':') to t.substringAfter(':')
            else -> t to null
        }
    }

    private fun isIpv6(h: String): Boolean = runCatching {
        h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' } &&
            java.net.InetAddress.getByName(h) is java.net.Inet6Address
    }.getOrDefault(false)
}
