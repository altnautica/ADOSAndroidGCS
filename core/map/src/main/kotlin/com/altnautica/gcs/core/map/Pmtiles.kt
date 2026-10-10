package com.altnautica.gcs.core.map

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tile encoding declared in a PMTiles v3 header. */
enum class PmtilesTileType(val code: Int, val raster: Boolean) {
    Unknown(0, false),
    Mvt(1, false),
    Png(2, true),
    Jpeg(3, true),
    Webp(4, true),
    Avif(5, true),
    ;

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** The fields of a PMTiles v3 header the app uses. */
data class PmtilesHeader(
    val tileType: PmtilesTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: TileBounds,
    val center: GeoPoint,
    val centerZoom: Int,
) {
    companion object {
        /** Fixed size of the v3 header at the start of the archive. */
        const val SIZE = 127

        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        /**
         * Parses the first [SIZE] bytes of a PMTiles archive.
         *
         * @throws IllegalArgumentException when the bytes are not a PMTiles v3 header.
         */
        fun parse(bytes: ByteArray): PmtilesHeader {
            require(bytes.size >= SIZE) { "PMTiles header is truncated" }
            require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a PMTiles archive" }
            val version = bytes[7].toInt() and 0xFF
            require(version == 3) { "Unsupported PMTiles version $version" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(offset: Int) = buf.getInt(offset) / 10_000_000.0
            return PmtilesHeader(
                tileType = PmtilesTileType.fromCode(bytes[99].toInt() and 0xFF),
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
                bounds = TileBounds(minLat = e7(106), maxLat = e7(114), minLon = e7(102), maxLon = e7(110)),
                centerZoom = bytes[118].toInt() and 0xFF,
                center = GeoPoint(latDeg = e7(123), lonDeg = e7(119)),
            )
        }
    }
}

/** A raster PMTiles archive imported into app storage and usable as an offline basemap. */
data class PmtilesPack(
    /** File name inside the app's pack directory; stable id. */
    val id: String,
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val header: PmtilesHeader,
) {
    /** Source URL MapLibre resolves through its PMTiles file source. */
    val sourceUrl: String get() = "pmtiles://file://$absolutePath"
}
