package com.altnautica.gcs.buildlogic.mavlink

import java.io.File

/**
 * Writes Kotlin codecs for a parsed dialect.
 *
 * Messages go to `<basePackage>.messages` (one data class per message plus the
 * id registry and the CRC_EXTRA table); enums go to `<basePackage>.enums` as
 * objects of constants. The runtime helpers the generated code calls
 * (`MavPayload`, `MavMessageCodec`, `MavFieldMeta`) live hand-written in
 * `<basePackage>`.
 */
class MavlinkKotlinGenerator(private val basePackage: String) {

    private val messagesPackage = "$basePackage.messages"
    private val enumsPackage = "$basePackage.enums"

    /**
     * Message class names, unique ignoring case (class files share one
     * directory on case-insensitive file systems). A later id whose name
     * collides gets its id appended, e.g. `SIMSTATE` beside `SIM_STATE`.
     */
    private val messageClassNames = mutableMapOf<Int, String>()

    private fun messageClass(m: MavMessageDef): String = messageClassNames.getValue(m.id)

    fun generate(dialect: MavDialect, outputDir: File) {
        outputDir.deleteRecursively()
        val messagesDir = File(outputDir, messagesPackage.replace('.', '/')).apply { mkdirs() }
        val enumsDir = File(outputDir, enumsPackage.replace('.', '/')).apply { mkdirs() }

        messageClassNames.clear()
        val taken = mutableSetOf("mavmessage", "mavmessages", "mavcrcextra")
        dialect.messages.forEach { m ->
            var name = className(m.name)
            if (!taken.add(name.lowercase())) {
                name += m.id
                require(taken.add(name.lowercase())) { "Class name clash for ${m.name}" }
            }
            messageClassNames[m.id] = name
        }

        File(messagesDir, "MavMessage.kt").writeText(messageInterface())
        dialect.messages.forEach { File(messagesDir, "${messageClass(it)}.kt").writeText(message(it)) }
        File(messagesDir, "MavMessages.kt").writeText(registry(dialect.messages))
        File(messagesDir, "MavCrcExtra.kt").writeText(crcTable(dialect.messages))
        dialect.enums.forEach { File(enumsDir, "${className(it.name)}.kt").writeText(enumObject(it)) }
    }

    private val header = "// Generated from the MAVLink XML definitions by the ados.mavlink.codegen plugin. Do not edit.\n"

    private fun messageInterface() = buildString {
        append(header)
        append("package $messagesPackage\n\n")
        append("/** A decoded MAVLink message. Every message of the dialect implements this. */\n")
        append("sealed interface MavMessage {\n")
        append("    /** MAVLink message id. */\n")
        append("    val msgId: Int\n\n")
        append("    /** Payload in wire order with MAVLink 2 trailing-zero truncation applied. */\n")
        append("    fun encodePayload(): ByteArray\n")
        append("}\n")
    }

    private fun message(m: MavMessageDef): String = buildString {
        val cls = messageClass(m)
        val props = m.fields.map { it to propertyName(it.name) }
        props.forEach { (_, p) -> require(p != "msgId") { "Field name clash in ${m.name}" } }
        val hasArrays = m.fields.any { it.arrayLength > 0 && it.type != MavPrimitive.CHAR }

        append(header)
        append("package $messagesPackage\n\n")
        append("import $basePackage.MavFieldMeta\n")
        append("import $basePackage.MavMessageCodec\n")
        append("import $basePackage.MavPayload\n\n")
        append(kdoc(m.description, ""))
        append("data class $cls(\n")
        props.forEach { (f, p) ->
            append(kdoc(f.description, "    "))
            append("    val ${escape(p)}: ${kotlinType(f)} = ${defaultValue(f)},\n")
        }
        append(") : MavMessage {\n")
        append("    override val msgId: Int get() = ID\n\n")
        append("    override fun encodePayload(): ByteArray {\n")
        append("        val b = MavPayload.writer(LENGTH)\n")
        m.wireFields.forEach { f ->
            append("        ${encodeStatement(f, m.offsets.getValue(f), "this.${escape(propertyName(f.name))}")}\n")
        }
        append("        return MavPayload.truncate(b)\n")
        append("    }\n")

        if (hasArrays) {
            append("\n    override fun equals(other: Any?): Boolean {\n")
            append("        if (this === other) return true\n")
            append("        if (other !is $cls) return false\n")
            props.forEach { (f, p) ->
                val n = escape(p)
                val cmp = if (f.arrayLength > 0 && f.type != MavPrimitive.CHAR) "$n.contentEquals(other.$n)" else "$n == other.$n"
                append("        if (!($cmp)) return false\n")
            }
            append("        return true\n")
            append("    }\n\n")
            append("    override fun hashCode(): Int {\n")
            append("        var h = 0\n")
            props.forEach { (f, p) ->
                val n = escape(p)
                val hc = if (f.arrayLength > 0 && f.type != MavPrimitive.CHAR) "$n.contentHashCode()" else "$n.hashCode()"
                append("        h = 31 * h + $hc\n")
            }
            append("        return h\n")
            append("    }\n\n")
            append("    override fun toString(): String = \"$cls(\" +\n")
            props.forEachIndexed { i, (f, p) ->
                val n = escape(p)
                val v = if (f.arrayLength > 0 && f.type != MavPrimitive.CHAR) "$n.contentToString()" else n
                val sep = if (i == 0) "" else ", "
                append("        \"$sep$p=\" + $v +\n")
            }
            append("        \")\"\n")
        }

        append("\n    companion object : MavMessageCodec<$cls> {\n")
        append("        const val ID: Int = ${m.id}\n")
        append("        const val CRC_EXTRA: Int = ${m.crcExtra}\n")
        append("        const val LENGTH: Int = ${m.length}\n")
        append("        const val BASE_LENGTH: Int = ${m.baseLength}\n\n")
        append("        override val id: Int get() = ID\n")
        append("        override val messageName: String get() = \"${m.name}\"\n")
        append("        override val crcExtra: Int get() = CRC_EXTRA\n")
        append("        override val length: Int get() = LENGTH\n")
        append("        override val baseLength: Int get() = BASE_LENGTH\n\n")
        append("        override val fields: List<MavFieldMeta> by lazy {\n")
        append("            listOf(\n")
        m.wireFields.forEach { f ->
            append(
                "                MavFieldMeta(\"${f.name}\", \"${f.type.xmlName}\", ${m.offsets.getValue(f)}, " +
                    "${f.arrayLength}, ${f.extension}),\n",
            )
        }
        append("            )\n")
        append("        }\n\n")
        append("        override fun decode(payload: ByteArray): $cls {\n")
        append("            val b = MavPayload.reader(payload, LENGTH)\n")
        append("            return $cls(\n")
        props.forEach { (f, p) ->
            append("                ${escape(p)} = ${decodeExpression(f, m.offsets.getValue(f))},\n")
        }
        append("            )\n")
        append("        }\n")
        append("    }\n")
        append("}\n")
    }

    private fun registry(messages: List<MavMessageDef>) = buildString {
        append(header)
        append("package $messagesPackage\n\n")
        append("import $basePackage.MavMessageCodec\n\n")
        append("/** Registry of every message codec in the dialect. */\n")
        append("object MavMessages {\n")
        append("    /** Codec for [msgId], or null when the dialect does not define it. */\n")
        append("    fun codec(msgId: Int): MavMessageCodec<*>? = when (msgId) {\n")
        messages.forEach { append("        ${it.id} -> ${messageClass(it)}\n") }
        append("        else -> null\n")
        append("    }\n\n")
        append("    /** Decodes [payload] (zero-filled when truncated); null for an unknown id. */\n")
        append("    fun decode(msgId: Int, payload: ByteArray): MavMessage? = codec(msgId)?.decode(payload)\n\n")
        append("    /** Every message id in the dialect, ascending. */\n")
        append("    val ids: IntArray = intArrayOf(\n")
        messages.chunked(16).forEach { chunk -> append("        ${chunk.joinToString(", ") { it.id.toString() }},\n") }
        append("    )\n")
        append("}\n")
    }

    private fun crcTable(messages: List<MavMessageDef>) = buildString {
        append(header)
        append("package $messagesPackage\n\n")
        append("/** CRC_EXTRA seed and payload lengths by message id; framing uses this without decoding. */\n")
        append("object MavCrcExtra {\n")
        append("    /** CRC_EXTRA for [msgId], or -1 when the id is unknown. */\n")
        append("    fun crcExtra(msgId: Int): Int = when (msgId) {\n")
        messages.forEach { append("        ${it.id} -> ${it.crcExtra}\n") }
        append("        else -> -1\n")
        append("    }\n\n")
        append("    /** Full payload length including extensions, or -1 when the id is unknown. */\n")
        append("    fun length(msgId: Int): Int = when (msgId) {\n")
        messages.forEach { append("        ${it.id} -> ${it.length}\n") }
        append("        else -> -1\n")
        append("    }\n\n")
        append("    /** Payload length without extensions (the MAVLink 1 length), or -1 when unknown. */\n")
        append("    fun baseLength(msgId: Int): Int = when (msgId) {\n")
        messages.forEach { append("        ${it.id} -> ${it.baseLength}\n") }
        append("        else -> -1\n")
        append("    }\n")
        append("}\n")
    }

    private fun enumObject(e: MavEnumDef) = buildString {
        val wide = e.entries.any { it.value > Int.MAX_VALUE || it.value < Int.MIN_VALUE }
        append(header)
        append("package $enumsPackage\n\n")
        append(kdoc(e.description, ""))
        append("object ${className(e.name)} {\n")
        e.entries.forEach { entry ->
            append(kdoc(entry.description, "    "))
            if (wide) {
                append("    const val ${entry.name}: Long = ${entry.value}L\n")
            } else {
                append("    const val ${entry.name}: Int = ${entry.value}\n")
            }
        }
        append("}\n")
    }

    private fun kotlinType(f: MavFieldDef): String = if (f.arrayLength == 0) {
        when (f.type) {
            MavPrimitive.UINT8, MavPrimitive.INT8, MavPrimitive.CHAR,
            MavPrimitive.UINT16, MavPrimitive.INT16, MavPrimitive.INT32 -> "Int"
            MavPrimitive.UINT32, MavPrimitive.UINT64, MavPrimitive.INT64 -> "Long"
            MavPrimitive.FLOAT -> "Float"
            MavPrimitive.DOUBLE -> "Double"
        }
    } else {
        when (f.type) {
            MavPrimitive.CHAR -> "String"
            MavPrimitive.UINT8, MavPrimitive.INT8 -> "ByteArray"
            MavPrimitive.UINT16, MavPrimitive.INT16, MavPrimitive.INT32 -> "IntArray"
            MavPrimitive.UINT32, MavPrimitive.UINT64, MavPrimitive.INT64 -> "LongArray"
            MavPrimitive.FLOAT -> "FloatArray"
            MavPrimitive.DOUBLE -> "DoubleArray"
        }
    }

    private fun defaultValue(f: MavFieldDef): String = when (val t = kotlinType(f)) {
        "Int" -> "0"
        "Long" -> "0L"
        "Float" -> "0f"
        "Double" -> "0.0"
        "String" -> "\"\""
        else -> "$t(${f.arrayLength})"
    }

    private fun encodeStatement(f: MavFieldDef, offset: Int, v: String): String {
        if (f.arrayLength > 0) {
            val fn = when (f.type) {
                MavPrimitive.CHAR -> "putString"
                MavPrimitive.UINT8, MavPrimitive.INT8 -> "putBytes"
                MavPrimitive.UINT16, MavPrimitive.INT16 -> "putShorts"
                MavPrimitive.INT32 -> "putInts"
                MavPrimitive.UINT32 -> "putUInts"
                MavPrimitive.UINT64, MavPrimitive.INT64 -> "putLongs"
                MavPrimitive.FLOAT -> "putFloats"
                MavPrimitive.DOUBLE -> "putDoubles"
            }
            return "MavPayload.$fn(b, $offset, ${f.arrayLength}, $v)"
        }
        return when (f.type) {
            MavPrimitive.UINT8, MavPrimitive.INT8, MavPrimitive.CHAR -> "b.put($offset, $v.toByte())"
            MavPrimitive.UINT16, MavPrimitive.INT16 -> "b.putShort($offset, $v.toShort())"
            MavPrimitive.INT32 -> "b.putInt($offset, $v)"
            MavPrimitive.UINT32 -> "b.putInt($offset, $v.toInt())"
            MavPrimitive.UINT64, MavPrimitive.INT64 -> "b.putLong($offset, $v)"
            MavPrimitive.FLOAT -> "b.putFloat($offset, $v)"
            MavPrimitive.DOUBLE -> "b.putDouble($offset, $v)"
        }
    }

    private fun decodeExpression(f: MavFieldDef, offset: Int): String {
        if (f.arrayLength > 0) {
            val fn = when (f.type) {
                MavPrimitive.CHAR -> "getString"
                MavPrimitive.UINT8, MavPrimitive.INT8 -> "getBytes"
                MavPrimitive.UINT16 -> "getUShorts"
                MavPrimitive.INT16 -> "getShorts"
                MavPrimitive.INT32 -> "getInts"
                MavPrimitive.UINT32 -> "getUInts"
                MavPrimitive.UINT64, MavPrimitive.INT64 -> "getLongs"
                MavPrimitive.FLOAT -> "getFloats"
                MavPrimitive.DOUBLE -> "getDoubles"
            }
            return "MavPayload.$fn(b, $offset, ${f.arrayLength})"
        }
        return when (f.type) {
            MavPrimitive.UINT8, MavPrimitive.CHAR -> "b.get($offset).toInt() and 0xFF"
            MavPrimitive.INT8 -> "b.get($offset).toInt()"
            MavPrimitive.UINT16 -> "b.getShort($offset).toInt() and 0xFFFF"
            MavPrimitive.INT16 -> "b.getShort($offset).toInt()"
            MavPrimitive.INT32 -> "b.getInt($offset)"
            MavPrimitive.UINT32 -> "b.getInt($offset).toLong() and 0xFFFFFFFFL"
            MavPrimitive.UINT64, MavPrimitive.INT64 -> "b.getLong($offset)"
            MavPrimitive.FLOAT -> "b.getFloat($offset)"
            MavPrimitive.DOUBLE -> "b.getDouble($offset)"
        }
    }

    private fun kdoc(text: String, indent: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim().replace("*/", "* /").replace("/*", "/ *")
        return if (clean.isEmpty()) "" else "$indent/** $clean */\n"
    }

    companion object {
        private val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "when", "while",
        )

        /** `GPS_RAW_INT` becomes `GpsRawInt`. */
        fun className(name: String): String = name.split('_').filter { it.isNotEmpty() }
            .joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

        /** `custom_mode` becomes `customMode`; already mixed-case names keep their casing. */
        fun propertyName(name: String): String {
            val parts = name.split('_').filter { it.isNotEmpty() }
            return parts.first() + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
        }

        fun escape(name: String): String = if (name in KEYWORDS) "`$name`" else name
    }
}
