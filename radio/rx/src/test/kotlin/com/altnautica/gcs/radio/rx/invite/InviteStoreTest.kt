package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `an unreadable vault is reported, not loaded as no keys, and a later load reads it`() = runTest {
        val prefs = MemoryPreferences()
        val invite = sampleInvite()
        InviteStore(CredentialVault(prefs, FlakyCipher(), scope)).save(invite)

        val cipher = FlakyCipher().apply { failing = true }
        val store = InviteStore(CredentialVault(prefs, cipher, scope))
        assertTrue(runCatching { store.load() }.exceptionOrNull() is VaultUnreadableException)
        assertTrue(runCatching { store.reload() }.exceptionOrNull() is VaultUnreadableException)
        assertNull(store.invite.value)

        cipher.failing = false
        assertEquals(invite, store.load())
        assertEquals(invite, store.invite.value)
    }

    @Test
    fun `reload reads the vault again after a passing fault`() = runTest {
        val prefs = MemoryPreferences()
        val invite = sampleInvite()
        InviteStore(CredentialVault(prefs, FlakyCipher(), scope)).save(invite)
        val cipher = FlakyCipher().apply { failing = true }
        val store = InviteStore(CredentialVault(prefs, cipher, scope))
        assertTrue(runCatching { store.load() }.exceptionOrNull() is VaultUnreadableException)

        cipher.failing = false
        assertEquals(invite, store.reload())
    }

    @Test
    fun `an empty vault loads as no keys`() = runTest {
        assertNull(InviteStore(CredentialVault(MemoryPreferences(), FlakyCipher(), scope)).load())
    }

    @Test
    fun `a forget the vault refuses keeps the keys`() = runTest {
        val cipher = FlakyCipher()
        val store = InviteStore(CredentialVault(MemoryPreferences(), cipher, scope))
        val invite = sampleInvite()
        store.save(invite)

        cipher.failing = true
        assertTrue(runCatching { store.forget() }.exceptionOrNull() is VaultUnreadableException)
        assertEquals(invite, store.invite.value)
        assertEquals(invite, store.load())

        cipher.failing = false
        store.forget()
        assertNull(store.invite.value)
        assertNull(store.load())
    }
}
