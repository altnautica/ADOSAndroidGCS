package com.altnautica.gcs.radio.rx.session

import com.altnautica.gcs.core.link.fleet.BoundFleet
import com.altnautica.gcs.radio.rx.invite.InviteBundle
import com.altnautica.gcs.radio.rx.invite.InvitePorts
import com.altnautica.gcs.radio.rx.invite.InviteSlot
import com.altnautica.gcs.radio.rx.invite.StoredInvite
import com.altnautica.gcs.radio.rx.link.LinkParams
import com.altnautica.gcs.radio.rx.tx.GroundTxParams
import java.util.Base64

/**
 * What one radio run listens with: the fleet's keys, channels and ports as
 * an invite lays them out, the drone slots to receive, and whether the phone
 * is the fleet's own ground (bound over the air) rather than a guest of a
 * box's fleet.
 */
internal class RunPlan(
    val bundle: InviteBundle,
    /** The drone slots a receiver listens on. */
    val slots: List<Int>,
    /** The drone heard on each slot when the run starts; others are learnt from their beacons. */
    val knownDevices: Map<Int, String>,
    /** True when the phone is the fleet's ground: it transmits from the start and enrols drones it hears. */
    val ownsFleet: Boolean,
    /** Whether the fleet's key has been seen to decode a drone's traffic. */
    val keyProven: Boolean,
) {
    companion object {
        fun of(invite: StoredInvite): RunPlan {
            val bundle = invite.bundle
            return RunPlan(
                bundle = bundle,
                slots = bundle.slots.map { it.slot },
                knownDevices = bundle.slots.associate { it.slot to it.deviceId },
                ownsFleet = false,
                keyProven = true,
            )
        }

        /**
         * The fleet this phone bound over the air: the ground key both
         * receives and transmits, the pair key authenticates hops and
         * beacons, one channel serves as home and rendezvous, and every slot
         * is heard (a just-bound drone's slot is known only once it beacons).
         */
        fun of(fleet: BoundFleet): RunPlan {
            val b64 = Base64.getEncoder()
            val bundle = InviteBundle(
                v = InviteBundle.VERSION,
                rxKeyB64 = b64.encodeToString(fleet.gsKey),
                hopKeyB64 = b64.encodeToString(fleet.pairKey),
                fleetId = fleet.fleetId,
                slots = fleet.slots.map { InviteSlot(it.slot, it.deviceId) },
                channel = fleet.channel,
                rendezvousChannel = fleet.channel,
                bandwidthMhz = BOUND_BANDWIDTH_MHZ,
                mcs = GroundTxParams.DEFAULT_MCS,
                ports = InvitePorts(LinkParams.PORT_VIDEO, LinkParams.PORT_CONTROL, LinkParams.PORT_AUX_DOWN),
            )
            return RunPlan(
                bundle = bundle,
                slots = (1..LinkParams.FLEET_MAX_SLOTS).toList(),
                knownDevices = fleet.slots.associate { it.slot to it.deviceId },
                ownsFleet = true,
                keyProven = fleet.keyProven,
            )
        }

        private const val BOUND_BANDWIDTH_MHZ = 20
    }
}
