// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb;

/**
 * The USB radio service, running in its own process. The client opens the
 * dongle with the descriptor Android granted, picks a channel, then reads
 * frame records from the pipe frames() returns:
 *
 *   u32 le length | u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame
 *
 * To transmit, it writes records to the pipe txFrames() returns, each in one
 * write of at most PIPE_BUF bytes (4096); the radio transmits them once
 * frames() has brought it up:
 *
 *   u32 le length | radiotap header | 802.11 frame
 */
interface IUsbRadio {
    void open(in ParcelFileDescriptor usbFd, int vendorId, int productId);
    void setChannel(int channel, int bandwidthMhz);
    ParcelFileDescriptor frames();
    void close();
    /** [running, framesSeen, framesForwarded, framesDropped, framesCorrupt, channel] */
    long[] stats();
    /** The write end of the transmit record pipe; one per open radio. */
    ParcelFileDescriptor txFrames();
    /** [framesOffered, framesSent, framesRefused, bytesOffered, bytesSent], cumulative since open */
    long[] txStats();
}
