package com.altnautica.gcs.feature.node.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSchemaTest {

    /** Trimmed from the agent's config schema shape: $ref models, anyOf-null, enums, bounds, secrets. */
    private val schemaJson = """
        {
          "type": "object",
          "properties": {
            "agent": {"${'$'}ref": "#/${'$'}defs/AgentConfig"},
            "video": {"${'$'}ref": "#/${'$'}defs/VideoConfig"},
            "network": {"${'$'}ref": "#/${'$'}defs/NetworkConfig"}
          },
          "${'$'}defs": {
            "AgentConfig": {"title": "AgentConfig", "type": "object", "properties": {
              "name": {"title": "Name", "type": "string"}
            }},
            "VideoConfig": {"title": "VideoConfig", "type": "object", "properties": {
              "camera": {"${'$'}ref": "#/${'$'}defs/CameraConfig"},
              "lcd_fps_cap": {"type": "integer", "minimum": 1, "maximum": 60},
              "cameras": {"type": "array", "items": {"type": "object"}}
            }},
            "CameraConfig": {"title": "CameraConfig", "type": "object", "properties": {
              "encoder": {"type": "string", "enum": ["auto", "omx", "v4l2m2m", "software"]},
              "fps": {"type": "integer", "minimum": 0, "maximum": 4294967295},
              "hflip": {"type": "boolean"},
              "source": {"anyOf": [{"type": "string"}, {"type": "null"}]}
            }},
            "NetworkConfig": {"title": "NetworkConfig", "type": "object", "properties": {
              "hotspot": {"type": "object", "properties": {
                "password": {"type": "string", "x-secret": true},
                "threshold": {"type": "number", "minimum": 0.5}
              }}
            }}
          }
        }
    """.trimIndent()

    private val schema = ConfigSchema.parse(Json.parseToJsonElement(schemaJson).jsonObject)

    @Test
    fun resolvesRefsAndNamesGroupsByProperty() {
        val camera = schema.group("video.camera")
        assertNotNull(camera)
        assertEquals("Camera", camera!!.title)
        assertEquals("Name", schema.leaf("agent.name")!!.title)
        assertEquals(listOf("agent", "video", "network"), schema.root.children.map { it.key })
    }

    @Test
    fun typesBoundsEnumsSecretsAndNullability() {
        assertEquals(FieldType.Choice(listOf("auto", "omx", "v4l2m2m", "software")), schema.leaf("video.camera.encoder")!!.type)
        // A u32 maximum is no bound for an operator.
        assertEquals(FieldType.Integer(0, null), schema.leaf("video.camera.fps")!!.type)
        assertEquals(FieldType.Integer(1, 60), schema.leaf("video.lcd_fps_cap")!!.type)
        assertEquals(FieldType.Bool, schema.leaf("video.camera.hflip")!!.type)
        assertEquals(FieldType.Json, schema.leaf("video.cameras")!!.type)
        assertTrue(schema.leaf("video.camera.source")!!.nullable)
        assertTrue(schema.leaf("network.hotspot.password")!!.secret)
        assertEquals(FieldType.Decimal(0.5, null), schema.leaf("network.hotspot.threshold")!!.type)
    }

    @Test
    fun codecValidatesAgainstTheSchema() {
        val fps = schema.leaf("video.lcd_fps_cap")!!
        assertEquals(ConfigValueCodec.Parsed.Ok(JsonPrimitive(30L)), ConfigValueCodec.encode(fps, "30"))
        assertEquals(ConfigValueCodec.Parsed.Invalid(ConfigValueCodec.Reason.AboveMaximum), ConfigValueCodec.encode(fps, "61"))
        assertEquals(ConfigValueCodec.Parsed.Invalid(ConfigValueCodec.Reason.NotANumber), ConfigValueCodec.encode(fps, "fast"))

        val encoder = schema.leaf("video.camera.encoder")!!
        assertEquals(ConfigValueCodec.Parsed.Invalid(ConfigValueCodec.Reason.NotAnOption), ConfigValueCodec.encode(encoder, "nvenc"))

        val source = schema.leaf("video.camera.source")!!
        assertEquals(ConfigValueCodec.Parsed.Ok(JsonNull), ConfigValueCodec.encode(source, ""))

        val cameras = schema.leaf("video.cameras")!!
        assertTrue(ConfigValueCodec.encode(cameras, "[{\"id\":\"cam0\"}]") is ConfigValueCodec.Parsed.Ok)
        assertEquals(ConfigValueCodec.Parsed.Invalid(ConfigValueCodec.Reason.NotJson), ConfigValueCodec.encode(cameras, "[{"))
    }

    @Test
    fun secretsNeverEchoAndReadAsSet() {
        val pass = schema.leaf("network.hotspot.password")!!
        assertEquals("", ConfigValueCodec.display(pass, JsonPrimitive("***")))
        assertTrue(ConfigValueCodec.secretIsSet(JsonPrimitive("***")))
    }

    @Test
    fun valueAtReadsDottedPaths() {
        val config = Json.parseToJsonElement("""{"video":{"camera":{"fps":30}}}""") as JsonObject
        assertEquals(JsonPrimitive(30), AgentPageViewModel.valueAt(config, "video.camera.fps"))
        assertEquals(null, AgentPageViewModel.valueAt(config, "video.camera.width"))
    }
}
