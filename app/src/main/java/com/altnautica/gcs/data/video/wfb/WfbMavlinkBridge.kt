package com.altnautica.gcs.data.video.wfb

import android.util.Log
import com.altnautica.gcs.data.mavlink.MavLinkParser
import com.altnautica.gcs.data.flightlog.TlogRecorder
import io.dronefleet.mavlink.MavlinkConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges MAVLink telemetry from the WFB-ng native layer to the app's MAVLink parser.
 *
 * The WFB-ng native code (WfbngLink.cpp) aggregates MAVLink frames from the drone
 * and forwards them to UDP localhost:14550. This bridge listens on that port and feeds
 * the received bytes into the existing MavLinkParser for telemetry processing.
 */
@Singleton
class WfbMavlinkBridge @Inject constructor(
    private val parser: MavLinkParser,
    private val tlogRecorder: TlogRecorder,
) {

    companion object {
        private const val TAG = "WfbMavlinkBridge"
        private const val MAVLINK_UDP_PORT = 14550
        private const val BUFFER_SIZE = 2048
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    /**
     * Start listening for MAVLink UDP packets on localhost:14550.
     */
    fun start() {
        if (listenJob?.isActive == true) {
            Log.w(TAG, "Bridge already running")
            return
        }

        listenJob = scope.launch {
            try {
                val udpSocket = DatagramSocket(MAVLINK_UDP_PORT, InetAddress.getByName("127.0.0.1"))
                socket = udpSocket
                udpSocket.soTimeout = 0 // Block indefinitely

                Log.i(TAG, "Listening for MAVLink on UDP :$MAVLINK_UDP_PORT")

                val buffer = ByteArray(BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        udpSocket.receive(packet)
                        if (packet.length > 0) {
                            val data = packet.data.copyOf(packet.length)
                            processFrame(data)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected if timeout is set, continue
                    } catch (e: java.net.SocketException) {
                        if (isActive) {
                            Log.w(TAG, "Socket error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MAVLink bridge: ${e.message}", e)
            }
        }
    }

    /**
     * Stop listening and release the socket.
     */
    fun stop() {
        listenJob?.cancel()
        listenJob = null
        socket?.close()
        socket = null
        Log.i(TAG, "MAVLink bridge stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun processFrame(bytes: ByteArray) {
        // Record raw bytes to tlog
        tlogRecorder.recordFrame(bytes)

        try {
            val inputStream = ByteArrayInputStream(bytes)
            val sendStream = ByteArrayOutputStream()
            val connection = MavlinkConnection.create(inputStream, sendStream)

            var message = connection.next()
            while (message != null) {
                parser.handleMessage(message)
                message = try {
                    connection.next()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MAVLink parse error: ${e.message}")
        }
    }
}
