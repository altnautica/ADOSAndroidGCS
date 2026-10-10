// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb;

/**
 * The USB radio service, running in its own process. The client opens the
 * dongle with the descriptor Android granted, picks a channel, then reads
 * frame records from the pipe frames() returns:
 *
 *   u32 le length | u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame
 */
interface IUsbRadio {
    void open(in ParcelFileDescriptor usbFd, int vendorId, int productId);
    void setChannel(int channel, int bandwidthMhz);
    ParcelFileDescriptor frames();
    void close();
    /** [running, framesSeen, framesForwarded, framesDropped, framesCorrupt, channel] */
    long[] stats();
}
