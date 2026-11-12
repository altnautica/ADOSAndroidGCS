package com.altnautica.gcs.feature.plan.io

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

/** The open plan with unsaved edits, kept so a process death does not lose it. */
data class PlanDraft(val name: String, val savedId: String?, val planJson: String)

/** Holds at most one draft; `write(null)` clears it. */
interface PlanDraftStore {
    suspend fun read(): PlanDraft?

    suspend fun write(draft: PlanDraft?)
}

/** One JSON file in app-private storage, replaced atomically on each write. */
class FilePlanDraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlanDraftStore {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    override suspend fun read(): PlanDraft? = withContext(Dispatchers.IO) {
        runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            PlanDraft(
                name = o.getValue("name").jsonPrimitive.content,
                savedId = o["savedId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                planJson = o.getValue("plan").jsonPrimitive.content,
            )
        }.getOrNull()
    }

    override suspend fun write(draft: PlanDraft?) = withContext(Dispatchers.IO) {
        if (draft == null) {
            file.delete()
            return@withContext
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        val o: JsonObject = buildJsonObject {
            put("name", draft.name)
            put("savedId", draft.savedId)
            put("plan", draft.planJson)
        }
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private companion object {
        const val FILE_NAME = "planner-draft.json"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanDraftStoreModule {
    @Binds
    abstract fun bindPlanDraftStore(impl: FilePlanDraftStore): PlanDraftStore
}
