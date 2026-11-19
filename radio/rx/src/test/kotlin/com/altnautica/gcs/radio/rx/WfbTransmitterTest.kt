package com.altnautica.gcs.radio.rx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WfbTransmitterTest {

    /**
     * The native transmitter as a stand-in: counts calls made while it is
     * being freed or after it was freed, and can hold a call inside until
     * [proceed] opens.
     */
    private class FakeNative(gated: Boolean) : WfbTxNative {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(if (gated) 1 else 0)
        val sends = AtomicInteger()
        val closes = AtomicInteger()
        val misuse = AtomicInteger()
        private val inside = AtomicInteger()

        @Volatile private var freed = false

        override fun send(handle: Long, payload: ByteArray): Boolean = call {
            sends.incrementAndGet()
            true
        }

        override fun stats(handle: Long): LongArray = call { LongArray(STATS) { 1 } }

        override fun close(handle: Long) {
            if (freed || inside.get() != 0) misuse.incrementAndGet()
            freed = true
            closes.incrementAndGet()
        }

        private fun <T> call(body: () -> T): T {
            if (freed) misuse.incrementAndGet()
            inside.incrementAndGet()
            try {
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                repeat(SPIN) { Thread.yield() }
                return body()
            } finally {
                inside.decrementAndGet()
            }
        }
    }

    @Test
    fun closeWaitsForASendInsideTheTransmitter() {
        val native = FakeNative(gated = true)
        val tx = WfbTransmitter(native, HANDLE, linkId = 1, radioPort = 1)
        val sender = thread { tx.send(byteArrayOf(1)) }
        assertTrue(native.entered.await(5, TimeUnit.SECONDS))
        val closer = thread { tx.close() }
        closer.join(300)
        assertEquals("close freed the transmitter under a send", 0, native.closes.get())
        native.proceed.countDown()
        sender.join(5_000)
        closer.join(5_000)
        assertEquals(1, native.closes.get())
        assertEquals(0, native.misuse.get())

        // Once closed, nothing reaches the native side.
        assertFalse(tx.send(byteArrayOf(2)))
        assertEquals(WfbTxStats(0, 0, 0, 0, 0, 0), tx.stats())
        tx.close()
        assertEquals(1, native.sends.get())
        assertEquals(1, native.closes.get())
        assertEquals(0, native.misuse.get())
    }

    @Test
    fun closeWaitsForAStatsReadInsideTheTransmitter() {
        val native = FakeNative(gated = true)
        val tx = WfbTransmitter(native, HANDLE, linkId = 1, radioPort = 3)
        var read: WfbTxStats? = null
        val reader = thread { read = tx.stats() }
        assertTrue(native.entered.await(5, TimeUnit.SECONDS))
        val closer = thread { tx.close() }
        closer.join(300)
        assertEquals("close freed the transmitter under a stats read", 0, native.closes.get())
        native.proceed.countDown()
        reader.join(5_000)
        closer.join(5_000)
        assertEquals(WfbTxStats(1, 1, 1, 1, 1, 1), read)
        assertEquals(1, native.closes.get())
        assertEquals(0, native.misuse.get())
    }

    @Test
    fun manySendersAndAReaderNeverTouchAFreedTransmitter() {
        val native = FakeNative(gated = false)
        val tx = WfbTransmitter(native, HANDLE, linkId = 1, radioPort = 1)
        val start = CountDownLatch(1)
        val workers = List(SENDERS) {
            thread {
                start.await()
                while (tx.send(byteArrayOf(1))) Unit
            }
        } + thread {
            start.await()
            while (tx.stats().packetsIn != 0L) Unit
        }
        start.countDown()
        Thread.sleep(50)
        tx.close()
        workers.forEach { it.join(5_000) }
        assertTrue(native.sends.get() > 0)
        assertEquals(1, native.closes.get())
        assertEquals(0, native.misuse.get())
    }

    private companion object {
        const val HANDLE = 0x1000L
        const val STATS = 6
        const val SPIN = 20
        const val SENDERS = 4
    }
}
