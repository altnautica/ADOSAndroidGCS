package com.altnautica.gcs.feature.configure.wizard

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Xml
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.Reader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A 2-D affine transform `[a c e; b d f]`, as in SVG `matrix(a b c d e f)`. */
data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    fun map(x: Float, y: Float) = Offset(a * x + c * y + e, b * x + d * y + f)

    /** Mean scale, for stroke widths and font sizes. */
    val scale: Float get() = ((hypot(a, b) + hypot(c, d)) / 2f)

    val isIdentity: Boolean get() = this == Affine()

    fun toMatrix() = Matrix().apply {
        values[Matrix.ScaleX] = a
        values[Matrix.SkewY] = b
        values[Matrix.SkewX] = c
        values[Matrix.ScaleY] = d
        values[Matrix.TranslateX] = e
        values[Matrix.TranslateY] = f
    }
}

/** One drawable shape of a setup illustration, in viewBox units. */
data class SvgShape(val path: Path, val fill: Color?, val stroke: Color?, val strokeWidth: Float, val roundCaps: Boolean)

/** One text label, positioned in viewBox units. */
data class SvgText(
    val text: String,
    val at: Offset,
    val size: Float,
    val color: Color,
    val anchor: Paint.Align,
    val bold: Boolean,
    val middleBaseline: Boolean,
)

/** A parsed setup illustration: its viewBox, shapes and labels in document order. */
data class SvgImage(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val shapes: List<SvgShape>,
    val texts: List<SvgText>,
)

/**
 * Reads the subset of SVG the setup illustrations use: `path`, `circle`, `ellipse`,
 * `rect`, `line`, `polyline`, `polygon` and `text`, with presentation attributes or
 * inline style inherited through `g`, and `translate`/`rotate`/`scale`/`matrix` transforms.
 * `currentColor` takes [current].
 */
object SvgReader {
    fun read(reader: Reader, current: Color): SvgImage {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)
        var box = floatArrayOf(0f, 0f, 100f, 100f)
        val shapes = mutableListOf<SvgShape>()
        val texts = mutableListOf<SvgText>()
        val stack = ArrayDeque<Style>()
        stack.addLast(Style())
        var openText: Pair<Style, Map<String, String>>? = null
        val textBuf = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    val style = stack.last().child(attrs, current)
                    stack.addLast(style)
                    when (parser.name) {
                        "svg" -> attrs["viewBox"]?.let { vb -> numbers(vb).takeIf { it.size == 4 }?.let { box = it.toFloatArray() } }
                            ?: run {
                                val w = attrs["width"]?.let(::number)
                                val h = attrs["height"]?.let(::number)
                                if (w != null && h != null) box = floatArrayOf(0f, 0f, w, h)
                            }
                        "text" -> {
                            openText = style to attrs
                            textBuf.clear()
                        }
                        else -> shape(parser.name, attrs)?.let { path ->
                            if (!style.transform.isIdentity) path.transform(style.transform.toMatrix())
                            shapes += SvgShape(path, style.fill, style.stroke, style.width * style.transform.scale, style.roundCaps)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (openText != null) textBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "text") {
                        openText?.let { (style, attrs) ->
                            val content = textBuf.toString().trim()
                            val color = style.fill ?: style.stroke
                            if (content.isNotEmpty() && color != null) {
                                texts += SvgText(
                                    text = content,
                                    at = style.transform.map(attrs["x"]?.let(::number) ?: 0f, attrs["y"]?.let(::number) ?: 0f),
                                    size = style.fontSize * style.transform.scale,
                                    color = color,
                                    anchor = when (style.anchor) {
                                        "middle" -> Paint.Align.CENTER
                                        "end" -> Paint.Align.RIGHT
                                        else -> Paint.Align.LEFT
                                    },
                                    bold = style.bold,
                                    middleBaseline = style.middleBaseline,
                                )
                            }
                        }
                        openText = null
                    }
                    stack.removeLast()
                }
            }
            event = parser.next()
        }
        return SvgImage(box[0], box[1], box[2], box[3], shapes, texts)
    }

    private data class Style(
        val fill: Color? = Color.Black,
        val stroke: Color? = null,
        val width: Float = 1f,
        val roundCaps: Boolean = false,
        val fontSize: Float = 16f,
        val bold: Boolean = false,
        val anchor: String = "start",
        val middleBaseline: Boolean = false,
        val transform: Affine = Affine(),
    ) {
        fun child(attrs: Map<String, String>, current: Color): Style {
            val inline = attrs["style"]?.split(';')?.mapNotNull { decl ->
                decl.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }?.toMap().orEmpty()
            val all = attrs + inline
            return Style(
                // A present attribute replaces the inherited paint; `none` clears it.
                fill = if ("fill" in all) color(all.getValue("fill"), current) else fill,
                stroke = if ("stroke" in all) color(all.getValue("stroke"), current) else stroke,
                width = all["stroke-width"]?.let(::number) ?: width,
                roundCaps = all["stroke-linecap"]?.let { it == "round" } ?: roundCaps,
                fontSize = all["font-size"]?.let(::number) ?: fontSize,
                bold = all["font-weight"]?.let { w -> w == "bold" || (w.toIntOrNull() ?: 400) >= 600 } ?: bold,
                anchor = all["text-anchor"] ?: anchor,
                middleBaseline = all["dominant-baseline"]?.let { it == "middle" || it == "central" } ?: middleBaseline,
                transform = all["transform"]?.let { transform * parseTransform(it) } ?: transform,
            )
        }
    }

    private fun shape(name: String, a: Map<String, String>): Path? {
        fun f(key: String) = a[key]?.let(::number) ?: 0f
        return when (name) {
            "path" -> a["d"]?.let { PathParser().parsePathString(it).toPath() }
            "circle" -> Path().apply {
                val r = f("r")
                addOval(Rect(f("cx") - r, f("cy") - r, f("cx") + r, f("cy") + r))
            }
            "ellipse" -> Path().apply { addOval(Rect(f("cx") - f("rx"), f("cy") - f("ry"), f("cx") + f("rx"), f("cy") + f("ry"))) }
            "rect" -> Path().apply {
                val rect = Rect(f("x"), f("y"), f("x") + f("width"), f("y") + f("height"))
                val rx = a["rx"]?.let(::number) ?: a["ry"]?.let(::number) ?: 0f
                if (rx > 0f) addRoundRect(RoundRect(rect, CornerRadius(rx, rx))) else addRect(rect)
            }
            "line" -> Path().apply {
                moveTo(f("x1"), f("y1"))
                lineTo(f("x2"), f("y2"))
            }
            "polyline", "polygon" -> {
                val pts = numbers(a["points"].orEmpty())
                if (pts.size < 4) {
                    null
                } else {
                    Path().apply {
                        moveTo(pts[0], pts[1])
                        var i = 2
                        while (i + 1 < pts.size) {
                            lineTo(pts[i], pts[i + 1])
                            i += 2
                        }
                        if (name == "polygon") close()
                    }
                }
            }
            else -> null
        }
    }

    private val NUMBER = Regex("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val TRANSFORM = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")

    private fun numbers(text: String): List<Float> = NUMBER.findAll(text).map { it.value.toFloat() }.toList()

    private fun number(text: String): Float? = NUMBER.find(text)?.value?.toFloatOrNull()

    internal fun parseTransform(text: String): Affine = TRANSFORM.findAll(text).fold(Affine()) { acc, m ->
        val n = numbers(m.groupValues[2])
        val next = when (m.groupValues[1]) {
            "matrix" -> if (n.size == 6) Affine(n[0], n[1], n[2], n[3], n[4], n[5]) else Affine()
            "translate" -> Affine(e = n.getOrElse(0) { 0f }, f = n.getOrElse(1) { 0f })
            "scale" -> {
                val sx = n.getOrElse(0) { 1f }
                Affine(a = sx, d = n.getOrElse(1) { sx })
            }
            "rotate" -> {
                val r = Math.toRadians(n.getOrElse(0) { 0f }.toDouble())
                val rot = Affine(cos(r).toFloat(), sin(r).toFloat(), -sin(r).toFloat(), cos(r).toFloat())
                if (n.size >= 3) {
                    Affine(e = n[1], f = n[2]) * rot * Affine(e = -n[1], f = -n[2])
                } else {
                    rot
                }
            }
            "skewX" -> Affine(c = kotlin.math.tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat())
            "skewY" -> Affine(b = kotlin.math.tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat())
            else -> Affine()
        }
        acc * next
    }

    internal fun color(text: String, current: Color): Color? {
        val t = text.trim()
        return when {
            t == "none" || t == "transparent" -> null
            t == "currentColor" -> current
            t.startsWith("#") && t.length == 4 -> Color(t[1].digitToInt(16) * 17, t[2].digitToInt(16) * 17, t[3].digitToInt(16) * 17)
            t.startsWith("#") && (t.length == 7 || t.length == 9) -> {
                val v = t.substring(1).toLong(16)
                if (t.length == 7) Color(0xFF000000 or v) else Color(((v and 0xFF) shl 24) or (v ushr 8))
            }
            t == "white" -> Color.White
            t == "black" -> Color.Black
            else -> current
        }
    }
}

/**
 * The setup illustration [key] from `cockpit-spec/setup-images/<key>.svg`, fitted to
 * the width. Draws nothing when the image is absent.
 */
@Composable
fun SetupImage(key: String, description: String, current: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image = produceState<SvgImage?>(null, key, current) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("$IMAGE_DIR/$key.svg").bufferedReader().use { SvgReader.read(it, current) }
            } catch (e: IOException) {
                null
            } catch (e: XmlPullParserException) {
                null
            }
        }
    }.value ?: return
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(image.width / image.height)
            .semantics { contentDescription = description },
    ) { drawSvg(image) }
}

private fun DrawScope.drawSvg(image: SvgImage) {
    val s = minOf(size.width / image.width, size.height / image.height)
    scale(s, s, pivot = Offset.Zero) {
        translate(-image.minX, -image.minY) {
            image.shapes.forEach { shape ->
                shape.fill?.let { drawPath(shape.path, it, style = Fill) }
                shape.stroke?.let {
                    val cap = if (shape.roundCaps) StrokeCap.Round else StrokeCap.Butt
                    drawPath(shape.path, it, style = Stroke(shape.strokeWidth, cap = cap))
                }
            }
            drawIntoCanvas { canvas ->
                image.texts.forEach { t ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = t.color.toArgb()
                        textSize = t.size
                        textAlign = t.anchor
                        typeface = if (t.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val y = if (t.middleBaseline) t.at.y - (paint.ascent() + paint.descent()) / 2f else t.at.y
                    canvas.nativeCanvas.drawText(t.text, t.at.x, y, paint)
                }
            }
        }
    }
}

const val IMAGE_DIR = "cockpit-spec/setup-images"
