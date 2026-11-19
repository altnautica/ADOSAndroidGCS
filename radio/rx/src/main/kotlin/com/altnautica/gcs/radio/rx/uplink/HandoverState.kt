package com.altnautica.gcs.radio.rx.uplink

/** The phone's side of the uplink handover. */
enum class HandoverPhase(val wire: String) {
    /** No transmitters: the default with an invite. */
    ReceiveOnly("receive_only"),

    /** A take request is in flight to the box over the LAN; no transmitters yet. */
    Taking("taking"),

    /** The phone holds the uplink and runs both ground transmitters. */
    Transmitting("transmitting"),

    /** The phone gave the uplink up; [HandoverState.note] says why. */
    Yielded("yielded"),
}

/** How the phone came to hold the uplink. */
enum class HoldSource {
    /** Taken from the box this phone's invite came from. */
    Taken,

    /** The phone bound its fleet over the air and is that fleet's own ground. */
    OwnFleet,

    /** A debug build's bench relay holds the uplink for the fleet bench; no box is involved. */
    Bench,
}

/** One sentence the operator sees: why the phone yielded, or why a take or give-back was refused. */
sealed interface HandoverNote {
    /** The log text: English, one sentence. */
    val english: String

    data object NoRadio : HandoverNote {
        override val english = "Start the phone radio before taking the uplink."
    }

    data object NoHandoverKey : HandoverNote {
        override val english = "This phone holds no handover key from a ground station; request the keys from the box again."
    }

    data class NoLanReach(val box: String) : HandoverNote {
        override val english = "The phone cannot reach $box over the LAN, so it will not transmit."
    }

    data class ListenerFailed(val port: Int) : HandoverNote {
        override val english = "The phone could not open its take-back listener on port $port."
    }

    data class OtherGround(val deviceId: String) : HandoverNote {
        override val english = "Another ground is transmitting: $deviceId"
    }

    data class BoxRefused(val detail: String) : HandoverNote {
        override val english = detail
    }

    data class TxDidNotOpen(val box: String) : HandoverNote {
        override val english = "The phone's transmitters did not open, so the uplink went back to $box."
    }

    data class GaveBack(val box: String) : HandoverNote {
        override val english = "Gave the uplink back to $box."
    }

    data class GiveBackUnconfirmed(val box: String, val detail: String?) : HandoverNote {
        override val english = "$box did not confirm the give-back${detail?.let { " ($it)" } ?: ""}; it resumes 25 s after the phone's last beacon."
    }

    data class TxNotQuiet(val box: String) : HandoverNote {
        override val english = "The phone's transmitters did not go quiet, so $box was not told; it resumes 25 s after the phone's last beacon."
    }

    data class TakenBack(val box: String) : HandoverNote {
        override val english = "$box took the uplink back."
    }

    data object RadioStopped : HandoverNote {
        override val english = "The phone radio stopped, so the phone stopped transmitting."
    }
}

/** The handover as the screen and the bench read it. */
data class HandoverState(
    val phase: HandoverPhase = HandoverPhase.ReceiveOnly,
    /** How the uplink is held; set while [HandoverPhase.Transmitting]. */
    val source: HoldSource? = null,
    /** Why the phone yielded, or why the last take or give-back was refused. */
    val note: HandoverNote? = null,
    /** A take or give-back is in flight. */
    val busy: Boolean = false,
)
