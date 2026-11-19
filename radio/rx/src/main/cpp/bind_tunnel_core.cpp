// SPDX-License-Identifier: GPL-3.0-only

#include "bind_tunnel_core.h"

#include <sys/random.h>
#include <time.h>

#include <cstring>

extern "C" {
#include "lwip/init.h"
#include "lwip/ip4_addr.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"
}

namespace ados::bindtun {
namespace {

struct Endpoint {
    bool open = false;
    struct netif netif {};
    Aggregator aggregator;
    std::vector<Datagram> pending;
    struct tcp_pcb *pcb = nullptr;
    TcpState state = TcpState::Idle;
    std::vector<uint8_t> received;
};

Endpoint g;
bool g_lwip_ready = false;

err_t netif_output(struct netif *, struct pbuf *p, const ip4_addr_t *) {
    if (p->tot_len == 0 || p->tot_len > kMaxPacket) return ERR_VAL;
    uint8_t buf[kMaxPacket];
    const u16_t n = pbuf_copy_partial(p, buf, p->tot_len, 0);
    g.aggregator.push(buf, n, monotonic_ms(), g.pending);
    return ERR_OK;
}

err_t netif_setup(struct netif *n) {
    n->name[0] = 'b';
    n->name[1] = 't';
    n->mtu = static_cast<u16_t>(kMaxPacket);
    n->output = netif_output;
    n->flags = 0;
    return ERR_OK;
}

err_t on_connected(void *, struct tcp_pcb *, err_t err) {
    g.state = err == ERR_OK ? TcpState::Connected : TcpState::Failed;
    return ERR_OK;
}

err_t on_recv(void *, struct tcp_pcb *pcb, struct pbuf *p, err_t err) {
    if (p == nullptr) {
        // The peer closed its side.
        g.state = TcpState::Closed;
        return ERR_OK;
    }
    if (err == ERR_OK) {
        const size_t at = g.received.size();
        g.received.resize(at + p->tot_len);
        pbuf_copy_partial(p, g.received.data() + at, p->tot_len, 0);
        tcp_recved(pcb, p->tot_len);
    }
    pbuf_free(p);
    return ERR_OK;
}

void on_error(void *, err_t) {
    // lwIP has already freed the pcb.
    g.pcb = nullptr;
    g.state = TcpState::Failed;
}

}  // namespace

uint64_t monotonic_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000u + static_cast<uint64_t>(ts.tv_nsec) / 1000000u;
}

bool stack_open(uint32_t local_ip) {
    if (g.open) return false;
    if (!g_lwip_ready) {
        // Once per process: lwip_init re-registers its cyclic timers.
        lwip_init();
        g_lwip_ready = true;
    }
    g.aggregator = Aggregator();
    g.pending.clear();
    g.received.clear();
    g.state = TcpState::Idle;
    g.pcb = nullptr;
    ip4_addr_t addr, mask, gw;
    addr.addr = lwip_htonl(local_ip);
    mask.addr = lwip_htonl(0xffffff00u);
    gw.addr = lwip_htonl((local_ip & 0xffffff00u) | 2u);
    if (netif_add(&g.netif, &addr, &mask, &gw, nullptr, netif_setup, ip_input) == nullptr) return false;
    netif_set_default(&g.netif);
    netif_set_up(&g.netif);
    netif_set_link_up(&g.netif);
    g.open = true;
    return true;
}

void stack_close() {
    if (!g.open) return;
    if (g.pcb != nullptr) {
        tcp_arg(g.pcb, nullptr);
        tcp_recv(g.pcb, nullptr);
        tcp_err(g.pcb, nullptr);
        tcp_abort(g.pcb);
        g.pcb = nullptr;
    }
    netif_set_down(&g.netif);
    netif_remove(&g.netif);
    g.pending.clear();
    g.received.clear();
    g.state = TcpState::Idle;
    g.open = false;
}

void stack_input(const uint8_t *datagram, size_t len) {
    if (!g.open) return;
    deaggregate(datagram, len, [](const uint8_t *pkt, size_t n) {
        struct pbuf *p = pbuf_alloc(PBUF_RAW, static_cast<u16_t>(n), PBUF_RAM);
        if (p == nullptr) return;
        pbuf_take(p, pkt, static_cast<u16_t>(n));
        if (g.netif.input(p, &g.netif) != ERR_OK) pbuf_free(p);
    });
}

void stack_poll(std::vector<Datagram> &out) {
    if (!g.open) return;
    sys_check_timeouts();
    g.aggregator.poll(monotonic_ms(), g.pending);
    for (auto &d : g.pending) out.push_back(std::move(d));
    g.pending.clear();
}

bool tcp_open(uint32_t ip, uint16_t port) {
    if (!g.open || g.pcb != nullptr) return false;
    struct tcp_pcb *pcb = tcp_new_ip_type(IPADDR_TYPE_V4);
    if (pcb == nullptr) return false;
    tcp_recv(pcb, on_recv);
    tcp_err(pcb, on_error);
    ip_addr_t dst;
    IP_ADDR4(&dst, (ip >> 24) & 0xff, (ip >> 16) & 0xff, (ip >> 8) & 0xff, ip & 0xff);
    g.pcb = pcb;
    g.state = TcpState::Connecting;
    if (tcp_connect(pcb, &dst, port, on_connected) != ERR_OK) {
        tcp_abort(pcb);
        g.pcb = nullptr;
        g.state = TcpState::Failed;
        return false;
    }
    return true;
}

size_t tcp_send(const uint8_t *data, size_t len) {
    if (g.pcb == nullptr || g.state != TcpState::Connected) return 0;
    size_t n = len;
    const size_t room = tcp_sndbuf(g.pcb);
    if (n > room) n = room;
    if (n > 0xffff) n = 0xffff;
    if (n == 0) return 0;
    if (tcp_write(g.pcb, data, static_cast<u16_t>(n), TCP_WRITE_FLAG_COPY) != ERR_OK) return 0;
    tcp_output(g.pcb);
    return n;
}

std::vector<uint8_t> tcp_take() {
    std::vector<uint8_t> out;
    out.swap(g.received);
    return out;
}

TcpState tcp_state() { return g.state; }

}  // namespace ados::bindtun

extern "C" u32_t sys_now(void) { return static_cast<u32_t>(ados::bindtun::monotonic_ms()); }

extern "C" unsigned int ados_lwip_rand(void) {
    unsigned int r = 0;
    if (getrandom(&r, sizeof(r), 0) != static_cast<ssize_t>(sizeof(r))) r = static_cast<unsigned int>(ados::bindtun::monotonic_ms());
    return r;
}
