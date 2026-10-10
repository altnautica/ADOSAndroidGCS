package com.altnautica.gcs.buildlogic.mavlink

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One MAVLink primitive type with its wire size. */
enum class MavPrimitive(val xmlName: String, val size: Int) {
    UINT8("uint8_t", 1),
    INT8("int8_t", 1),
    CHAR("char", 1),
    UINT16("uint16_t", 2),
    INT16("int16_t", 2),
    UINT32("uint32_t", 4),
    INT32("int32_t", 4),
    FLOAT("float", 4),
    UINT64("uint64_t", 8),
    INT64("int64_t", 8),
    DOUBLE("double", 8);

    companion object {
        fun parse(name: String): MavPrimitive {
            // `uint8_t_mavlink_version` is a uint8_t on the wire and in CRC_EXTRA.
            val normalized = if (name == "uint8_t_mavlink_version") "uint8_t" else name
            return entries.firstOrNull { it.xmlName == normalized }
                ?: error("Unknown MAVLink field type '$name'")
        }
    }
}

data class MavFieldDef(
    val name: String,
    val type: MavPrimitive,
    /** 0 for a scalar. */
    val arrayLength: Int,
    val extension: Boolean,
    val description: String,
) {
    val wireSize: Int get() = type.size * maxOf(arrayLength, 1)
}

data class MavMessageDef(
    val id: Int,
    val name: String,
    val description: String,
    /** Declaration order. */
    val fields: List<MavFieldDef>,
) {
    /** Base fields sorted by type size (stable), then extensions in declaration order. */
    val wireFields: List<MavFieldDef> by lazy {
        fields.filterNot { it.extension }.sortedByDescending { it.type.size } + fields.filter { it.extension }
    }

    val offsets: Map<MavFieldDef, Int> by lazy {
        var offset = 0
        wireFields.associateWith { field -> offset.also { offset += field.wireSize } }
    }

    val length: Int get() = fields.sumOf { it.wireSize }
    val baseLength: Int get() = fields.filterNot { it.extension }.sumOf { it.wireSize }

    /** CRC_EXTRA seed: X.25 over the name and the base fields in wire order. */
    val crcExtra: Int by lazy {
        var crc = 0xFFFF
        fun acc(b: Int) {
            var tmp = (b xor (crc and 0xFF)) and 0xFF
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
        }
        fun accString(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { acc(it.toInt() and 0xFF) }
        accString("$name ")
        for (field in wireFields.filterNot { it.extension }) {
            accString("${field.type.xmlName} ")
            accString("${field.name} ")
            if (field.arrayLength > 0) acc(field.arrayLength)
        }
        (crc and 0xFF) xor (crc shr 8)
    }
}

data class MavEnumEntryDef(val name: String, val value: Long, val description: String)

data class MavEnumDef(val name: String, val description: String, val entries: List<MavEnumEntryDef>)

data class MavDialect(val messages: List<MavMessageDef>, val enums: List<MavEnumDef>)

/** Parses a dialect file and every file it includes (transitively, each once). */
object MavlinkDefinitionParser {

    fun parse(dialect: File): MavDialect {
        val messages = linkedMapOf<Int, MavMessageDef>()
        val enums = linkedMapOf<String, MavEnumDef>()
        val visited = mutableSetOf<String>()

        fun visit(file: File) {
            if (!visited.add(file.canonicalPath)) return
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val root = factory.newDocumentBuilder().parse(file).documentElement
            root.children("include").forEach { visit(File(file.parentFile, it.textContent.trim())) }
            root.children("enums").flatMap { it.children("enum") }.forEach { e ->
                val parsed = parseEnum(e)
                val existing = enums[parsed.name]
                enums[parsed.name] = if (existing == null) {
                    parsed
                } else {
                    val known = existing.entries.map { it.name }.toSet()
                    existing.copy(entries = existing.entries + parsed.entries.filterNot { it.name in known })
                }
            }
            root.children("messages").flatMap { it.children("message") }.forEach { m ->
                val parsed = parseMessage(m)
                val clash = messages[parsed.id]
                require(clash == null || clash.name == parsed.name) {
                    "Message id ${parsed.id} defined twice (${clash?.name}, ${parsed.name})"
                }
                messages[parsed.id] = parsed
            }
        }

        visit(dialect)
        return MavDialect(messages.values.sortedBy { it.id }, enums.values.sortedBy { it.name })
    }

    private fun parseEnum(e: Element): MavEnumDef {
        var next = 0L
        val entries = e.children("entry").map { entry ->
            val raw = entry.getAttribute("value")
            val value = if (raw.isBlank()) next else parseValue(raw)
            next = value + 1
            MavEnumEntryDef(entry.getAttribute("name"), value, entry.children("description").firstOrNull()?.textContent.orEmpty())
        }
        return MavEnumDef(e.getAttribute("name"), e.children("description").firstOrNull()?.textContent.orEmpty(), entries)
    }

    private fun parseValue(raw: String): Long {
        val v = raw.trim()
        return when {
            v.startsWith("0x") || v.startsWith("0X") -> v.substring(2).toLong(16)
            v.startsWith("0b") || v.startsWith("0B") -> v.substring(2).toLong(2)
            "**" in v -> v.split("**").let { (b, p) -> Math.pow(b.trim().toDouble(), p.trim().toDouble()).toLong() }
            else -> v.toLong()
        }
    }

    private fun parseMessage(m: Element): MavMessageDef {
        val fields = mutableListOf<MavFieldDef>()
        var inExtensions = false
        val nodes = m.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            when (node.tagName) {
                "extensions" -> inExtensions = true
                "field" -> {
                    val rawType = node.getAttribute("type")
                    val bracket = rawType.indexOf('[')
                    val (typeName, arrayLength) = if (bracket >= 0) {
                        rawType.substring(0, bracket) to rawType.substring(bracket + 1, rawType.indexOf(']')).toInt()
                    } else {
                        rawType to 0
                    }
                    fields += MavFieldDef(
                        name = node.getAttribute("name"),
                        type = MavPrimitive.parse(typeName),
                        arrayLength = arrayLength,
                        extension = inExtensions,
                        description = node.textContent.trim(),
                    )
                }
            }
        }
        return MavMessageDef(
            id = m.getAttribute("id").toInt(),
            name = m.getAttribute("name"),
            description = m.children("description").firstOrNull()?.textContent?.trim().orEmpty(),
            fields = fields,
        )
    }

    private fun Element.children(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) out += n
        }
        return out
    }
}
