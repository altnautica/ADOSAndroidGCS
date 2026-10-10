package com.altnautica.gcs.core.cloud.convex

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The cloud database's function surface, reduced to raw JSON so typed
 * wrappers decode with one nullable-safe [CloudJson] and tests can stand in a
 * fake. Argument values must be Convex values: String, Double (every Convex
 * `number`), Boolean, List, Map or null.
 */
interface ConvexGateway {
    fun subscribe(name: String, args: Map<String, Any?> = emptyMap()): Flow<Result<JsonElement>>
    suspend fun action(name: String, args: Map<String, Any?> = emptyMap()): JsonElement
    suspend fun mutation(name: String, args: Map<String, Any?> = emptyMap()): JsonElement
}

/** The unauthenticated calls the sign-in flow makes (Convex Auth's own actions). */
interface AuthBackend {
    suspend fun signIn(args: Map<String, Any?>): JsonElement
}

/** Thrown when the cloud is unavailable in this build or session. */
class CloudUnavailableException(message: String) : IllegalStateException(message)

val CloudJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
