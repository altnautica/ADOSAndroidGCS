package com.altnautica.gcs.feature.settings.licences

/** The app's own licence, shown first. */
const val APP_LICENCE_ASSET = "licenses/ados-gpl-3.0.txt"

/**
 * Full licence texts bundled as assets (`licenses/…`), keyed by a word that names the
 * component in the first column of a `THIRD_PARTY_NOTICES.md` table row. The font licences
 * come from the design system's assets, which merge into the same asset tree.
 */
private val BY_COMPONENT: List<Pair<String, List<String>>> = listOf(
    "Inter" to listOf("licenses/inter-OFL.txt"),
    "JetBrains Mono" to listOf("licenses/jetbrains-mono-OFL.txt"),
    "Space Grotesk" to listOf("licenses/space-grotesk-OFL.txt"),
    "MAVLink" to listOf("licenses/mavlink.txt"),
    "WebRTC" to listOf("licenses/webrtc-bsd-3.txt", "licenses/webrtc-sdk-mit.txt"),
    "MapLibre" to listOf("licenses/maplibre-bsd-2.txt"),
    "usb-serial" to listOf("licenses/usb-serial-mit.txt"),
    "devourer" to listOf("licenses/devourer-gpl-2.0.txt"),
    "libusb" to listOf("licenses/libusb-lgpl-2.1.txt"),
    "wfb-ng" to listOf("licenses/wfb-ng-gpl-3.0.txt"),
    "libsodium" to listOf("licenses/libsodium-isc.txt"),
)

private const val APACHE_ASSET = "licenses/apache-2.0.txt"

/** The licence texts for one notices table row; Apache-2.0 rows share the one Apache text. */
fun licenceAssetsFor(row: List<String>): List<String> {
    val name = row.firstOrNull().orEmpty()
    BY_COMPONENT.firstOrNull { (key, _) -> name.contains(key) }?.let { return it.second }
    return if (row.any { it.contains("Apache-2.0") }) listOf(APACHE_ASSET) else emptyList()
}

/** A readable title for a licence asset path, e.g. "apache-2.0.txt" → "apache-2.0". */
fun licenceTitle(asset: String): String = asset.substringAfterLast('/').removeSuffix(".txt")
