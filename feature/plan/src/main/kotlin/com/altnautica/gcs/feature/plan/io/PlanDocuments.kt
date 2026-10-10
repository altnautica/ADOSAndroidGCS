package com.altnautica.gcs.feature.plan.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** A text document picked through the Storage Access Framework. */
data class PlanDocument(val name: String, val text: String)

/** Reads and writes plan files behind SAF document URIs. */
interface PlanDocuments {
    suspend fun read(uri: Uri): PlanDocument

    suspend fun write(uri: Uri, text: String)
}

class ContentResolverPlanDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlanDocuments {

    override suspend fun read(uri: Uri): PlanDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("unreadable document")
        PlanDocument(name, text)
    }

    override suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("unwritable document")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanDocumentsModule {
    @Binds
    abstract fun bindPlanDocuments(impl: ContentResolverPlanDocuments): PlanDocuments
}
