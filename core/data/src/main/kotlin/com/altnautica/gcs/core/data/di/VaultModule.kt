package com.altnautica.gcs.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.KeystoreCipherEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Kept in its own file so settings backups and exports never carry ciphertext of secrets. */
private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "credential_vault")

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun provideCredentialVault(@ApplicationContext context: Context): CredentialVault =
        CredentialVault(
            store = context.vaultDataStore,
            cipher = KeystoreCipherEngine(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
}
