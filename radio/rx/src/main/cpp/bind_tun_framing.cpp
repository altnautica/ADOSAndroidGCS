// SPDX-License-Identifier: GPL-3.0-only

#include "bind_tun_framing.h"

namespace ados::bindtun {

bool Aggregator::push(const uint8_t *packet, size_t len, uint64_t now_ms, std::vector<Datagram> &out) {
    if (len == 0 || len > kMaxPacket) return false;
    if (data_.empty()) batch_started_ms_ = now_ms;
    data_.push_back(static_cast<uint8_t>(len >> 8));
    data_.push_back(static_cast<uint8_t>(len & 0xff));
    data_.insert(data_.end(), packet, packet + len);
    if (data_.size() <= kTunMtu) batch_size_ = data_.size();
    if (data_.size() >= kTunMtu) send_batch(now_ms, out);
    return true;
}

void Aggregator::send_batch(uint64_t now_ms, std::vector<Datagram> &out) {
    while (batch_size_ > 0) {
        out.emplace_back(data_.begin(), data_.begin() + static_cast<std::ptrdiff_t>(batch_size_));
        ping_sem_ = 1;
        data_.erase(data_.begin(), data_.begin() + static_cast<std::ptrdiff_t>(batch_size_));
        batch_size_ = data_.size();
        // A remainder exactly one MTU long goes at once; a shorter one waits
        // its own aggregation time from now.
        if (data_.size() != kTunMtu) break;
    }
    if (!data_.empty()) batch_started_ms_ = now_ms;
}

void Aggregator::poll(uint64_t now_ms, std::vector<Datagram> &out) {
    if (!data_.empty() && now_ms - batch_started_ms_ >= kAggTimeoutMs) send_batch(now_ms, out);
    if (!ping_armed_) {
        ping_armed_ = true;
        next_ping_ms_ = now_ms + kPingIntervalMs;
        return;
    }
    if (now_ms < next_ping_ms_) return;
    next_ping_ms_ = now_ms + kPingIntervalMs;
    if (ping_sem_ == 0) out.emplace_back();
    if (ping_sem_ > 0) ping_sem_--;
}

bool deaggregate(const uint8_t *data, size_t len,
                 const std::function<void(const uint8_t *, size_t)> &on_packet) {
    size_t off = 0;
    while (off < len) {
        if (off + kHeaderBytes > len) return false;
        const size_t n = (static_cast<size_t>(data[off]) << 8) | data[off + 1];
        off += kHeaderBytes;
        if (n == 0 || off + n > len) return false;
        on_packet(data + off, n);
        off += n;
    }
    return true;
}

}  // namespace ados::bindtun
