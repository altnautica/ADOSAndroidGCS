// SPDX-License-Identifier: GPL-3.0-only
//
// The bind tunnel's framing, byte for byte as wfb-ng's wfb_tun: each IPv4
// packet is prefixed by its length (2 bytes, big-endian) and packets are
// aggregated into one radio datagram of at most kTunMtu bytes, sent when the
// next packet would not fit, when it is exactly full, or kAggTimeoutMs after
// the first packet of the batch. A zero-length datagram is the keepalive
// ping, sent when nothing else went out for a whole ping interval.

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace ados::bindtun {

constexpr size_t kTunMtu = 1445;
constexpr size_t kHeaderBytes = 2;
constexpr size_t kMaxPacket = kTunMtu - kHeaderBytes;
constexpr uint64_t kAggTimeoutMs = 5;
constexpr uint64_t kPingIntervalMs = 500;

using Datagram = std::vector<uint8_t>;

class Aggregator {
public:
    // Appends one packet at [now_ms]; any datagram that became ready goes to
    // [out]. False (and nothing queued) for an empty or oversize packet.
    bool push(const uint8_t *packet, size_t len, uint64_t now_ms, std::vector<Datagram> &out);

    // Sends what the batch holds once its aggregation time is up, and the
    // keepalive ping when nothing went out for a ping interval.
    void poll(uint64_t now_ms, std::vector<Datagram> &out);

    size_t buffered() const { return data_.size(); }

private:
    void send_batch(uint64_t now_ms, std::vector<Datagram> &out);

    std::vector<uint8_t> data_;  // whole buffer, may exceed one MTU by the overflowing packet
    size_t batch_size_ = 0;      // the ready prefix, at most kTunMtu
    uint64_t batch_started_ms_ = 0;
    // As wfb_tun's ping semaphore: a ping goes only at a tick with no send since the previous tick.
    int ping_sem_ = 0;
    uint64_t next_ping_ms_ = 0;
    bool ping_armed_ = false;
};

// Splits one received datagram into its packets, calling [on_packet] for
// each. An empty datagram is a ping and yields none. False when the datagram
// is malformed (a length running past its end); the packets before it were
// delivered.
bool deaggregate(const uint8_t *data, size_t len,
                 const std::function<void(const uint8_t *, size_t)> &on_packet);

}  // namespace ados::bindtun
