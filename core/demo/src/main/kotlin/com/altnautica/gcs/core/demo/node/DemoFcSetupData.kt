package com.altnautica.gcs.core.demo.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Stateless payloads of the demo setup engine ([DemoFcSetup]): step inputs, results and fixed tables.

/** A quad motor: its number, spin direction (clockwise or not) and test sequence. */
internal class DemoMotor(val number: Int, val cw: Boolean, val seq: Int)

/** Quad-X in test order: motor number, spin direction, test sequence. */
internal val QUAD_X = listOf(DemoMotor(1, false, 1), DemoMotor(4, true, 2), DemoMotor(2, false, 3), DemoMotor(3, true, 4))

/** The frame step's input names, kept as its result on Apply. */
internal val FRAME_INPUTS = setOf("frame_class", "frame_type", "prop_in", "motor_kv", "cells")

/** Frame class (quad, hexa, octa) as its X layout picture, as the engine sends it. */
private val CLASS_IMAGES = mapOf(1 to "motor_order_quad_x", 2 to "motor_order_hexa_x", 3 to "motor_order_octa_x")

/** Frame type (plus, X, H) as the quad drawn in that layout. */
private val TYPE_IMAGES = mapOf(0 to "motor_order_quad_plus", 1 to "motor_order_quad_x", 3 to "motor_order_quad_h")

/** Profile parameters the FC reads only at boot: changing one restarts it. */
private val REBOOT_PARAMS = setOf("PRX1_TYPE")

/** The frame step's inputs as the engine sends them for an ArduCopter quad X. */
internal fun frameInputs() = JsonObject(
    mapOf(
        "frame_class" to enumInput(1, listOf(1, 2, 3), "setup.input.frame_class", CLASS_IMAGES),
        "frame_type" to enumInput(1, listOf(0, 1, 3), "setup.input.frame_type", TYPE_IMAGES),
        "prop_in" to numberInput(5.0, 2.0, 40.0, 0.5, "in", "setup.input.prop_in"),
        "motor_kv" to numberInput(2300.0, 50.0, 5000.0, 10.0, "rpm/V", "setup.input.motor_kv"),
        "cells" to numberInput(4.0, 1.0, 14.0, 1.0, "S", "setup.input.cells"),
    ),
)

private fun enumInput(value: Int, options: List<Int>, label: String, images: Map<Int, String>) = JsonObject(
    mapOf(
        "kind" to JsonPrimitive("enum"),
        "value" to JsonPrimitive(value),
        "options" to JsonArray(options.map { JsonPrimitive(it) }),
        "label_key" to JsonPrimitive(label),
        "images" to JsonObject(images.map { (k, v) -> k.toString() to JsonPrimitive(v) }.toMap()),
    ),
)

private fun numberInput(value: Double, min: Double, max: Double, step: Double, unit: String, label: String) = JsonObject(
    mapOf(
        "kind" to JsonPrimitive("number"),
        "value" to JsonPrimitive(value),
        "min" to JsonPrimitive(min),
        "max" to JsonPrimitive(max),
        "step" to JsonPrimitive(step),
        "unit" to JsonPrimitive(unit),
        "label_key" to JsonPrimitive(label),
    ),
)

/** The copter failsafe profile as `{name, value, have}` rows against [fcValues], as the vehicle's pilot lists it. */
internal fun profileRows(fcValues: Map<String, Double>) = JsonArray(
    DemoFcSetup.COPTER_PROFILE.map { (name, value) ->
        JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value), "have" to JsonPrimitive(fcValues[name])))
    },
)

/** True when applying the profile changes a parameter the FC reads only at boot. */
internal fun profileReboots(fcValues: Map<String, Double>) =
    DemoFcSetup.COPTER_PROFILE.any { (name, value) -> name in REBOOT_PARAMS && fcValues[name] != value }

private val GAINS_BEFORE = mapOf(
    "ATC_RAT_RLL_P" to 0.135, "ATC_RAT_RLL_I" to 0.135, "ATC_RAT_RLL_D" to 0.0036,
    "ATC_RAT_PIT_P" to 0.135, "ATC_RAT_PIT_I" to 0.135, "ATC_RAT_PIT_D" to 0.0036,
    "ATC_ANG_RLL_P" to 4.5, "ATC_ANG_PIT_P" to 4.5,
)
private val GAINS_AFTER = mapOf(
    "ATC_RAT_RLL_P" to 0.112, "ATC_RAT_RLL_I" to 0.112, "ATC_RAT_RLL_D" to 0.0031,
    "ATC_RAT_PIT_P" to 0.124, "ATC_RAT_PIT_I" to 0.124, "ATC_RAT_PIT_D" to 0.0034,
    "ATC_ANG_RLL_P" to 7.2, "ATC_ANG_PIT_P" to 6.8,
)

/** The autotune step's result: a roll/pitch tune with its saved gains. */
internal fun autotuneResult(): Map<String, JsonElement> = mapOf(
    "gains_before" to JsonObject(GAINS_BEFORE.mapValues { JsonPrimitive(it.value) }),
    "gains_after" to JsonObject(GAINS_AFTER.mapValues { JsonPrimitive(it.value) }),
    "changed" to JsonArray(GAINS_AFTER.keys.map { JsonPrimitive(it) }),
    "copter_axes_saved" to JsonArray(listOf(JsonPrimitive("roll"), JsonPrimitive("pitch"))),
    "plane_axes_done" to JsonArray(emptyList()),
    "yaw_pending" to JsonPrimitive(false),
    "notes" to JsonArray(emptyList()),
)

/** The results of the ground steps the demo aircraft finished earlier. */
internal val DEMO_GROUND_RESULTS: Map<String, Map<String, JsonElement>> = mapOf(
    "detect" to mapOf(
        "firmware" to JsonPrimitive("ardupilot"),
        "version" to JsonPrimitive(DemoFcSetup.FIRMWARE_VERSION),
        "vehicle_class" to JsonPrimitive("multirotor"),
    ),
    "accel" to mapOf("poses" to JsonPrimitive(DemoFcSetup.POSES.size)),
    "compass" to mapOf("compass_fitness" to JsonPrimitive(6.4)),
    "outputs" to mapOf(
        "tested" to JsonPrimitive(4),
        "reversed_dshot" to JsonArray(emptyList()),
        "reversed_servo" to JsonArray(emptyList()),
        "swap_wires" to JsonArray(emptyList()),
        "wrong_position" to JsonArray(emptyList()),
    ),
    "battery" to mapOf("batt_volt_mult" to JsonPrimitive(10.1)),
)
