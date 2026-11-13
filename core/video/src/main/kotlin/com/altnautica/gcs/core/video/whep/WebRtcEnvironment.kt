package com.altnautica.gcs.core.video.whep

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.altnautica.gcs.core.video.DebugProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/** How the hardware decoder hands its pictures to the renderers. */
enum class DecoderOutput {
    /** Straight into a texture shared with the renderers: no copy per frame. */
    Texture,

    /** Into memory the renderer uploads: one copy per frame, but no texture handoff. */
    ByteBuffer,
}

/**
 * Picks the [DecoderOutput]. In texture mode libwebrtc's decoder drops, without counting it,
 * every picture that comes out while the renderer still holds the previous texture, and it
 * holds it for the whole draw and buffer swap. Where that swap is slow (the emulator's
 * host-GPU pipe, 16 to 37 ms a frame) most frames are lost there, so emulators decode to
 * byte buffers. Real phones keep textures, and move to byte buffers only once a session
 * measures that loss (`HandoffLossMonitor`).
 */
object DecoderOutputPolicy {
    private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")
    private val EMULATOR_DECODERS = listOf("c2.goldfish.", "OMX.android.goldfish.")

    fun isEmulatorHardware(hardware: String): Boolean = hardware in EMULATOR_HARDWARE

    fun isEmulator(hardware: String, avcDecoders: List<String>): Boolean =
        isEmulatorHardware(hardware) || avcDecoders.any { name -> EMULATOR_DECODERS.any(name::startsWith) }

    /**
     * [override] is a debuggable build's `debug.ados.video.bytebuffer` property: "1" forces
     * byte buffers, "0" forces textures, anything else leaves the choice to the device.
     */
    fun initial(emulator: Boolean, override: String?): DecoderOutput = when (override) {
        "1" -> DecoderOutput.ByteBuffer
        "0" -> DecoderOutput.Texture
        else -> if (emulator) DecoderOutput.ByteBuffer else DecoderOutput.Texture
    }
}

/** True on an Android emulator, judged by its board name alone (cheap enough for the UI thread). */
val isEmulatorDevice: Boolean by lazy { DecoderOutputPolicy.isEmulatorHardware(Build.HARDWARE) }

/**
 * The process-wide WebRTC objects: one EGL context shared by every renderer and a
 * peer-connection factory per [DecoderOutput], with the hardware-first decoder factory.
 * Created on first use so screens without video never load the native library.
 */
@Singleton
class WebRtcEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val eglBase: EglBase by lazy { EglBase.create() }

    private val initialized: Unit by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
    }

    private val factories = EnumMap<DecoderOutput, PeerConnectionFactory>(DecoderOutput::class.java)
    private var output: DecoderOutput? = null

    /** The output new peers decode to. Read off the main thread: the first read queries the codecs. */
    val decoderOutput: DecoderOutput
        get() = synchronized(this) { output ?: chooseOutput().also { output = it } }

    /** The factory for new peers, decoding to [decoderOutput]. */
    val factory: PeerConnectionFactory
        get() = synchronized(this) {
            val o = decoderOutput
            factories.getOrPut(o) {
                initialized
                PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(
                        DefaultVideoDecoderFactory(if (o == DecoderOutput.Texture) eglBase.eglBaseContext else null),
                    )
                    .createPeerConnectionFactory()
            }
        }

    /**
     * Moves new peers to byte-buffer output once a session measured the texture handoff
     * losing frames; peers already open keep theirs. True when the output changed.
     */
    fun fallBackToByteBuffer(): Boolean = synchronized(this) {
        if (decoderOutput == DecoderOutput.ByteBuffer) return@synchronized false
        output = DecoderOutput.ByteBuffer
        Log.i(TAG, "decoder output=bytebuffer reason=texture-handoff-loss")
        true
    }

    private fun chooseOutput(): DecoderOutput {
        val decoders = runCatching { avcDecoderNames() }.getOrDefault(emptyList())
        val emulator = DecoderOutputPolicy.isEmulator(Build.HARDWARE, decoders)
        val override = DebugProperties.read(context, DebugProperties.BYTE_BUFFER)
        return DecoderOutputPolicy.initial(emulator, override).also {
            Log.i(TAG, "decoder output=${it.name.lowercase()} emulator=$emulator decoders=$decoders override=$override")
        }
    }

    private fun avcDecoderNames(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .map { it.name }

    private companion object {
        const val TAG = "VideoStats"
    }
}
