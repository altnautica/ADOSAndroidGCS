package com.altnautica.gcs.radio.rx.bind

import com.altnautica.gcs.radio.rx.WfbReceiver

/** The TCP connection's state on the bind tunnel. */
enum class TunnelTcpState { Idle, Connecting, Connected, Closed, Failed }

/**
 * The bind session's IP endpoint: a userspace IPv4/TCP stack at
 * [BindProfile.GROUND_IP]/24 whose packets travel in wfb_tun framing. One
 * per process at a time; every call belongs to the one bind thread.
 */
class BindTunnel : AutoCloseable {
    private var open = false

    init {
        WfbReceiver.loadLibrary()
        check(BindTunnelNative.nativeOpen(BindProfile.GROUND_IP)) { "a bind tunnel is already open" }
        open = true
    }

    /** One datagram decoded on the bind receive plane. */
    fun input(datagram: ByteArray) {
        if (open) BindTunnelNative.nativeInput(datagram)
    }

    /** Runs the stack's timers; the datagrams now due on the bind transmit plane. */
    fun poll(): Array<ByteArray> = if (open) BindTunnelNative.nativePoll() ?: emptyArray() else emptyArray()

    fun connect(ip: Int, port: Int): Boolean = open && BindTunnelNative.nativeConnect(ip, port)

    /** Bytes accepted onto the connection (fewer than offered while its send buffer is full). */
    fun send(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int =
        if (open) BindTunnelNative.nativeSend(bytes, offset, length) else 0

    /** Every byte received since the last call. */
    fun take(): ByteArray = if (open) BindTunnelNative.nativeTake() ?: ByteArray(0) else ByteArray(0)

    val state: TunnelTcpState get() = if (open) TunnelTcpState.entries[BindTunnelNative.nativeState()] else TunnelTcpState.Idle

    override fun close() {
        if (open) {
            open = false
            BindTunnelNative.nativeClose()
        }
    }
}

/** The native half of [BindTunnel] (bind_tunnel.cpp). */
object BindTunnelNative {
    @JvmStatic external fun nativeOpen(localIp: Int): Boolean

    @JvmStatic external fun nativeClose()

    @JvmStatic external fun nativeInput(datagram: ByteArray)

    @JvmStatic external fun nativePoll(): Array<ByteArray>?

    @JvmStatic external fun nativeConnect(ip: Int, port: Int): Boolean

    @JvmStatic external fun nativeSend(data: ByteArray, offset: Int, length: Int): Int

    @JvmStatic external fun nativeTake(): ByteArray?

    @JvmStatic external fun nativeState(): Int
}
