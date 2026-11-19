package com.altnautica.gcs.radio.rx.bind

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

class BindExchangeTest {

    /** The drone's bind server as a script: each line the phone sends is answered by the next reply. */
    private class ScriptedServer(vararg replies: String?) : BindStream {
        private val pending = ArrayDeque(replies.toList())
        val sent = mutableListOf<String>()
        private var answer: String? = null

        override suspend fun write(bytes: ByteArray) {
            sent += bytes.decodeToString()
            answer = pending.removeFirstOrNull()
        }

        override suspend fun readLine(timeoutMs: Long): String? = answer.also { answer = null }
    }

    @Test
    fun `version then bind, both answered OK, is accepted`() = runTest {
        val server = ScriptedServer("OK\twfb-ng 25.1", "OK")
        val result = BindExchange().run(server, "QUJD")
        assertEquals(ExchangeResult.Accepted("wfb-ng 25.1"), result)
        assertEquals(listOf("VERSION\r\n", "BIND\tQUJD\r\n"), server.sent)
    }

    @Test
    fun `the drone's refusal carries its reason`() = runTest {
        assertEquals(ExchangeResult.Refused("Checksum failed"), BindExchange().run(ScriptedServer("OK\tv", "ERR\tChecksum failed"), "x"))
        assertEquals(ExchangeResult.Refused("Internal error"), BindExchange().run(ScriptedServer("ERR\tInternal error"), "x"))
    }

    @Test
    fun `silence or a foreign answer is no answer`() = runTest {
        assertEquals(ExchangeResult.NoAnswer("VERSION"), BindExchange().run(ScriptedServer(null), "x"))
        assertEquals(ExchangeResult.NoAnswer("BIND"), BindExchange().run(ScriptedServer("OK\tv", null), "x"))
        assertEquals(ExchangeResult.NoAnswer("VERSION"), BindExchange().run(ScriptedServer("HTTP/1.1 400"), "x"))
    }

    @Test
    fun `paired needs both decoded frames and the drone's OK`() {
        val ok = ExchangeResult.Accepted("v")
        assertTrue(PeerProof.paired(3, ok))
        assertFalse("a clean exchange alone is not proof", PeerProof.paired(0, ok))
        assertFalse(PeerProof.paired(3, ExchangeResult.Refused("no")))
    }

    @Test
    fun `lines split at LF with or without CR, across reads`() {
        val s = LineSplitter()
        assertEquals(emptyList<String>(), s.push("OK\twfb".toByteArray()))
        assertEquals(listOf("OK\twfb-ng 25.1", "OK"), s.push("-ng 25.1\r\nOK\nER".toByteArray()))
        assertEquals(listOf("ERR\tx"), s.push("R\tx\r\n".toByteArray()))
    }

    @Test
    fun `the bundle is a gzip tar of drone key and its sha1sum line`() {
        val droneKey = ByteArray(64) { (it * 3).toByte() }
        val tar = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(BindBundle.encode(droneKey, 1_700_000_000)))).readBytes()
        val members = untar(tar)
        assertEquals(listOf("./drone.key", "./checksum.txt"), members.map { it.first })
        assertTrue(members[0].second.contentEquals(droneKey))
        val sha1 = MessageDigest.getInstance("SHA-1").digest(droneKey).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("$sha1  drone.key\n", members[1].second.decodeToString())
        assertEquals(0, tar.size % 512)
    }

    /** Reads a ustar archive back, checking each header's checksum and the end marker as tar does. */
    private fun untar(tar: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var off = 0
        while (true) {
            val h = tar.copyOfRange(off, off + 512)
            if (h.all { it == 0.toByte() }) break
            val stored = String(h, 148, 6, Charsets.US_ASCII).toInt(8)
            val computed = h.mapIndexed { i, b -> if (i in 148 until 156) ' '.code else b.toInt() and 0xff }.sum()
            assertEquals("header checksum", computed, stored)
            assertEquals("ustar", String(h, 257, 5, Charsets.US_ASCII))
            assertEquals('0', h[156].toInt().toChar())
            val name = String(h, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
            val size = String(h, 124, 11, Charsets.US_ASCII).toInt(8)
            out += name to tar.copyOfRange(off + 512, off + 512 + size)
            off += 512 + (size + 511) / 512 * 512
        }
        assertTrue("two zero blocks end the archive", tar.copyOfRange(off, off + 1024).all { it == 0.toByte() })
        return out
    }
}
