// SPDX-License-Identifier: GPL-3.0-only
//
// Host-side tests of the bind tunnel. The framing must produce exactly the
// datagrams wfb-ng's own wfb_tun sends for the same packets (vectors in
// wfb_tun_golden.txt), and lwIP must carry a TCP exchange over it.

#include <time.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "bind_tun_framing.h"
#include "bind_tunnel_core.h"

using namespace ados::bindtun;

namespace {

int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                          \
        }                                                                        \
    } while (0)

using Bytes = std::vector<uint8_t>;

Bytes pattern(int idx, int len) {
    Bytes p(static_cast<size_t>(len));
    for (int j = 0; j < len; j++) p[static_cast<size_t>(j)] = static_cast<uint8_t>((idx * 31 + j * 7 + 1) & 0xff);
    return p;
}

Bytes from_hex(const std::string &s) {
    Bytes out;
    for (size_t i = 0; i + 1 < s.size(); i += 2) out.push_back(static_cast<uint8_t>(std::stoi(s.substr(i, 2), nullptr, 16)));
    return out;
}

struct Scenario {
    std::string name;
    std::vector<int> lens;
    std::vector<Bytes> out;
};

std::vector<Scenario> load_golden() {
    std::vector<Scenario> all;
    std::ifstream in(GOLDEN_PATH);
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream ls(line);
        std::string tag;
        ls >> tag;
        if (tag == "scenario") {
            all.emplace_back();
            ls >> all.back().name;
        } else if (tag == "in") {
            int n;
            while (ls >> n) all.back().lens.push_back(n);
        } else if (tag == "out") {
            std::string hex;
            ls >> hex;
            all.back().out.push_back(from_hex(hex));
        }
    }
    return all;
}

void test_aggregation_matches_wfb_tun() {
    const auto scenarios = load_golden();
    CHECK(scenarios.size() == 5);
    for (const auto &s : scenarios) {
        Aggregator agg;
        std::vector<Datagram> sent;
        agg.poll(0, sent);  // arms the ping clock
        for (size_t i = 0; i < s.lens.size(); i++) {
            const Bytes p = pattern(static_cast<int>(i), s.lens[i]);
            CHECK(agg.push(p.data(), p.size(), 1, sent));
        }
        agg.poll(1 + kAggTimeoutMs, sent);
        if (sent != s.out) std::fprintf(stderr, "scenario %s differs from wfb_tun\n", s.name.c_str());
        CHECK(sent == s.out);
        CHECK(agg.buffered() == 0);
    }
}

void test_deaggregation_recovers_every_packet() {
    for (const auto &s : load_golden()) {
        std::vector<Bytes> got;
        for (const auto &d : s.out) {
            CHECK(deaggregate(d.data(), d.size(), [&](const uint8_t *p, size_t n) { got.emplace_back(p, p + n); }));
        }
        CHECK(got.size() == s.lens.size());
        for (size_t i = 0; i < got.size() && i < s.lens.size(); i++) CHECK(got[i] == pattern(static_cast<int>(i), s.lens[i]));
    }
}

void test_a_batch_waits_for_its_aggregation_time() {
    Aggregator agg;
    std::vector<Datagram> sent;
    const Bytes p = pattern(0, 100);
    agg.push(p.data(), p.size(), 100, sent);
    agg.poll(104, sent);
    CHECK(sent.empty());
    agg.poll(105, sent);
    CHECK(sent.size() == 1 && sent[0].size() == 102);
}

void test_oversize_and_empty_packets_are_refused() {
    Aggregator agg;
    std::vector<Datagram> sent;
    const Bytes big(kMaxPacket + 1, 0x45);
    CHECK(!agg.push(big.data(), big.size(), 0, sent));
    CHECK(!agg.push(big.data(), 0, 0, sent));
    CHECK(agg.buffered() == 0);
}

void test_ping_only_after_a_silent_interval() {
    Aggregator agg;
    std::vector<Datagram> sent;
    agg.poll(0, sent);
    agg.poll(kPingIntervalMs, sent);
    CHECK(sent.size() == 1 && sent[0].empty());
    sent.clear();
    // Data went out during the interval: the next tick sends no ping.
    const Bytes p = pattern(0, 20);
    agg.push(p.data(), p.size(), kPingIntervalMs + 10, sent);
    agg.poll(kPingIntervalMs + 20, sent);
    CHECK(sent.size() == 1 && !sent[0].empty());
    sent.clear();
    agg.poll(2 * kPingIntervalMs + 20, sent);
    CHECK(sent.empty());
    agg.poll(3 * kPingIntervalMs + 20, sent);
    CHECK(sent.size() == 1 && sent[0].empty());
}

void test_malformed_datagrams_stop_cleanly() {
    int seen = 0;
    const uint8_t truncated_header[] = {0x00};
    CHECK(!deaggregate(truncated_header, 1, [&](const uint8_t *, size_t) { seen++; }));
    const uint8_t overlong[] = {0x00, 0x02, 0xaa, 0x00, 0x09, 0x01};
    CHECK(!deaggregate(overlong, sizeof(overlong), [&](const uint8_t *, size_t) { seen++; }));
    CHECK(seen == 1);
    CHECK(deaggregate(nullptr, 0, [&](const uint8_t *, size_t) { seen++; }));
    CHECK(seen == 1);
}

// ---------------------------------------------------------------- TCP through lwIP

uint16_t checksum(const uint8_t *data, size_t len, uint32_t sum = 0) {
    for (size_t i = 0; i + 1 < len; i += 2) sum += static_cast<uint32_t>(data[i] << 8 | data[i + 1]);
    if (len & 1) sum += static_cast<uint32_t>(data[len - 1] << 8);
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return static_cast<uint16_t>(~sum);
}

void put16(uint8_t *p, uint32_t v) { p[0] = static_cast<uint8_t>(v >> 8); p[1] = static_cast<uint8_t>(v); }
void put32(uint8_t *p, uint32_t v) { put16(p, v >> 16); put16(p + 2, v); }
uint32_t get32(const uint8_t *p) { return static_cast<uint32_t>(p[0]) << 24 | p[1] << 16 | p[2] << 8 | p[3]; }

constexpr uint32_t kPhone = 0x0a056301;  // 10.5.99.1
constexpr uint32_t kDrone = 0x0a056302;  // 10.5.99.2

// One IPv4/TCP segment from the drone to the phone.
Bytes segment(uint16_t sport, uint16_t dport, uint32_t seq, uint32_t ack, uint8_t flags, const std::string &payload) {
    Bytes p(40 + payload.size());
    p[0] = 0x45;
    put16(&p[2], static_cast<uint32_t>(p.size()));
    p[8] = 64;
    p[9] = 6;
    put32(&p[12], kDrone);
    put32(&p[16], kPhone);
    put16(&p[10], checksum(p.data(), 20));
    uint8_t *t = &p[20];
    put16(t, sport);
    put16(t + 2, dport);
    put32(t + 4, seq);
    put32(t + 8, ack);
    t[12] = 5 << 4;
    t[13] = flags;
    put16(t + 14, 8192);
    std::memcpy(t + 20, payload.data(), payload.size());
    const size_t tcp_len = 20 + payload.size();
    uint32_t pseudo = (kDrone >> 16) + (kDrone & 0xffff) + (kPhone >> 16) + (kPhone & 0xffff) + 6 + static_cast<uint32_t>(tcp_len);
    put16(t + 16, checksum(t, tcp_len, pseudo));
    return p;
}

Bytes frame(const Bytes &packet) {
    Aggregator agg;
    std::vector<Datagram> out;
    agg.push(packet.data(), packet.size(), 0, out);
    agg.poll(kAggTimeoutMs, out);
    return out.at(0);
}

void sleep_ms(long ms) {
    timespec ts{ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, nullptr);
}

// Every packet the phone sent since the last call.
std::vector<Bytes> sent_packets() {
    sleep_ms(static_cast<long>(kAggTimeoutMs) + 5);
    std::vector<Datagram> out;
    stack_poll(out);
    std::vector<Bytes> packets;
    for (const auto &d : out) deaggregate(d.data(), d.size(), [&](const uint8_t *p, size_t n) { packets.emplace_back(p, p + n); });
    return packets;
}

// Whether [p] is a FIN from the phone with sequence number [seq].
bool is_fin(const Bytes &p, uint32_t seq) {
    const size_t ihl = (p[0] & 0xf) * 4u;
    return get32(&p[12]) == kPhone && (p[ihl + 13] & 0x01) != 0 && get32(&p[ihl + 4]) == seq;
}

void test_tcp_exchange_over_the_framing() {
    CHECK(stack_open(kPhone));
    CHECK(!stack_open(kPhone));
    CHECK(tcp_open(kDrone, 5555));
    CHECK(tcp_state() == TcpState::Connecting);

    auto out = sent_packets();
    CHECK(out.size() == 1);
    if (out.size() != 1) return;
    const Bytes &syn = out[0];
    CHECK(syn[0] >> 4 == 4 && syn[9] == 6);
    CHECK(get32(&syn[12]) == kPhone && get32(&syn[16]) == kDrone);
    const uint8_t *t = &syn[(syn[0] & 0xf) * 4];
    const uint16_t phone_port = static_cast<uint16_t>(t[0] << 8 | t[1]);
    CHECK((t[2] << 8 | t[3]) == 5555);
    CHECK((t[13] & 0x3f) == 0x02);
    const uint32_t isn = get32(t + 4);

    const Bytes synack = frame(segment(5555, phone_port, 1000, isn + 1, 0x12, ""));
    stack_input(synack.data(), synack.size());
    CHECK(tcp_state() == TcpState::Connected);
    sent_packets();  // the handshake's ACK

    const std::string version = "VERSION\r\n";
    CHECK(tcp_send(reinterpret_cast<const uint8_t *>(version.data()), version.size()) == version.size());
    out = sent_packets();
    CHECK(out.size() == 1);
    if (out.size() != 1) return;
    const Bytes &data = out[0];
    const size_t ihl = (data[0] & 0xf) * 4u;
    const size_t doff = (data[ihl + 12] >> 4) * 4u;
    CHECK(std::string(data.begin() + static_cast<long>(ihl + doff), data.end()) == version);
    CHECK(get32(&data[ihl + 4]) == isn + 1);

    const Bytes reply = frame(segment(5555, phone_port, 1001, isn + 1 + static_cast<uint32_t>(version.size()), 0x18, "OK\t2.0\r\n"));
    stack_input(reply.data(), reply.size());
    const auto got = tcp_take();
    CHECK(std::string(got.begin(), got.end()) == "OK\t2.0\r\n");
    CHECK(tcp_take().empty());

    // The drone closes first; the phone's FIN then follows and is acknowledged.
    const uint32_t phone_next = isn + 1 + static_cast<uint32_t>(version.size());
    const Bytes fin = frame(segment(5555, phone_port, 1009, phone_next, 0x11, ""));
    stack_input(fin.data(), fin.size());
    CHECK(tcp_state() == TcpState::Closed);
    sent_packets();  // the ACK of the drone's FIN
    CHECK(tcp_close_progress() == CloseProgress::NotClosing);
    CHECK(tcp_finish());
    out = sent_packets();
    CHECK(out.size() == 1 && is_fin(out[0], phone_next));
    CHECK(tcp_close_progress() == CloseProgress::FinSent);
    const Bytes ack = frame(segment(5555, phone_port, 1010, phone_next + 1, 0x10, ""));
    stack_input(ack.data(), ack.size());
    CHECK(tcp_close_progress() == CloseProgress::Complete);

    stack_close();
    CHECK(tcp_state() == TcpState::Idle);
    CHECK(tcp_close_progress() == CloseProgress::NotClosing);
}

// The phone closes first, as it does after the drone's OK to BIND: its FIN
// goes out, is acknowledged, and the drone's own FIN is acknowledged in turn.
void test_the_phone_closes_first_and_waits_for_the_ack() {
    CHECK(stack_open(kPhone));
    CHECK(tcp_open(kDrone, 5555));
    auto out = sent_packets();
    CHECK(out.size() == 1);
    if (out.size() != 1) return;
    const uint8_t *t = &out[0][(out[0][0] & 0xf) * 4];
    const uint16_t phone_port = static_cast<uint16_t>(t[0] << 8 | t[1]);
    const uint32_t isn = get32(t + 4);
    const Bytes synack = frame(segment(5555, phone_port, 5000, isn + 1, 0x12, ""));
    stack_input(synack.data(), synack.size());
    CHECK(tcp_state() == TcpState::Connected);
    sent_packets();

    CHECK(tcp_finish());
    CHECK(tcp_finish());  // idempotent
    out = sent_packets();
    CHECK(out.size() == 1 && is_fin(out[0], isn + 1));
    CHECK(tcp_close_progress() == CloseProgress::FinSent);

    // Unacknowledged, lwIP retransmits the FIN rather than dropping it.
    bool retransmitted = false;
    for (int i = 0; i < 120 && !retransmitted; i++) {
        for (const auto &p : sent_packets()) retransmitted = retransmitted || is_fin(p, isn + 1);
        sleep_ms(40);
    }
    CHECK(retransmitted);
    CHECK(tcp_close_progress() == CloseProgress::FinSent);

    const Bytes ack = frame(segment(5555, phone_port, 5001, isn + 2, 0x10, ""));
    stack_input(ack.data(), ack.size());
    CHECK(tcp_close_progress() == CloseProgress::FinAcked);

    const Bytes fin = frame(segment(5555, phone_port, 5001, isn + 2, 0x11, ""));
    stack_input(fin.data(), fin.size());
    CHECK(tcp_close_progress() == CloseProgress::Complete);
    out = sent_packets();
    CHECK(out.size() == 1);
    if (out.size() == 1) {
        const uint8_t *a = &out[0][(out[0][0] & 0xf) * 4];
        CHECK((a[13] & 0x10) != 0 && get32(a + 8) == 5002);  // the drone's FIN acknowledged
    }
    stack_close();
}

void test_no_connection_has_nothing_to_finish() {
    CHECK(stack_open(kPhone));
    CHECK(!tcp_finish());
    CHECK(tcp_close_progress() == CloseProgress::NotClosing);
    stack_close();
}

// A peer that keeps sending while nothing is taken: the window closes at the
// cap, what it sends past the window never reaches the buffer, and taking
// the bytes reopens the window.
void test_a_peer_cannot_grow_the_receive_buffer_past_the_cap() {
    CHECK(stack_open(kPhone));
    CHECK(tcp_open(kDrone, 5555));
    auto out = sent_packets();
    CHECK(out.size() == 1);
    if (out.size() != 1) return;
    const uint8_t *t = &out[0][(out[0][0] & 0xf) * 4];
    const uint16_t phone_port = static_cast<uint16_t>(t[0] << 8 | t[1]);
    const uint32_t isn = get32(t + 4);
    const Bytes synack = frame(segment(5555, phone_port, 7000, isn + 1, 0x12, ""));
    stack_input(synack.data(), synack.size());
    CHECK(tcp_state() == TcpState::Connected);
    sent_packets();

    const std::string chunk(1400, 'x');
    uint32_t seq = 7001;
    for (int i = 0; i < 3 * static_cast<int>(kReceiveCap / chunk.size()); i++) {
        const Bytes d = frame(segment(5555, phone_port, seq, isn + 1, 0x18, chunk));
        stack_input(d.data(), d.size());
        seq += static_cast<uint32_t>(chunk.size());
    }
    // The phone's last word before anything is taken advertises a closed window.
    out = sent_packets();
    CHECK(!out.empty());
    if (!out.empty()) {
        const Bytes &a = out.back();
        const size_t ihl = (a[0] & 0xf) * 4u;
        CHECK((a[ihl + 14] << 8 | a[ihl + 15]) == 0);
    }
    const auto got = tcp_take();
    CHECK(got.size() == kReceiveCap);
    // Taking the bytes reopens the window in full.
    out = sent_packets();
    CHECK(!out.empty());
    if (!out.empty()) {
        const Bytes &a = out.back();
        const size_t ihl = (a[0] & 0xf) * 4u;
        CHECK(static_cast<size_t>(a[ihl + 14] << 8 | a[ihl + 15]) == kReceiveCap);
    }
    CHECK(tcp_take().empty());
    stack_close();
}

}  // namespace

int main() {
    test_aggregation_matches_wfb_tun();
    test_deaggregation_recovers_every_packet();
    test_a_batch_waits_for_its_aggregation_time();
    test_oversize_and_empty_packets_are_refused();
    test_ping_only_after_a_silent_interval();
    test_malformed_datagrams_stop_cleanly();
    test_tcp_exchange_over_the_framing();
    test_the_phone_closes_first_and_waits_for_the_ack();
    test_no_connection_has_nothing_to_finish();
    test_a_peer_cannot_grow_the_receive_buffer_past_the_cap();
    if (failures == 0) std::printf("bind tunnel tests passed\n");
    return failures == 0 ? 0 : 1;
}
