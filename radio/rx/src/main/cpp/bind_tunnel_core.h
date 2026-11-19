// SPDX-License-Identifier: GPL-3.0-only
//
// The bind session's IP endpoint: an lwIP interface whose packets travel in
// wfb_tun framing, and one TCP connection over it. lwIP is process-global
// state, so there is one endpoint at a time; every call belongs to one
// thread (the bind thread), which also calls poll() every few milliseconds.

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "bind_tun_framing.h"

namespace ados::bindtun {

enum class TcpState : int { Idle = 0, Connecting = 1, Connected = 2, Closed = 3, Failed = 4 };

// Brings the interface up at [local_ip] (host order) with a /24; false when an endpoint is already open.
bool stack_open(uint32_t local_ip);

// Aborts the connection and takes the interface down.
void stack_close();

// One datagram the tunnel's receive plane decoded: its packets go into the stack.
void stack_input(const uint8_t *datagram, size_t len);

// Runs lwIP's timers and the aggregation clock; every datagram due on the
// tunnel's transmit plane since the last call goes to [out].
void stack_poll(std::vector<Datagram> &out);

// Starts a TCP connection to [ip]:[port] (host order); false when one exists or it could not start.
bool tcp_open(uint32_t ip, uint16_t port);

// Queues bytes on the connection; the count accepted (fewer while the send buffer is full).
size_t tcp_send(const uint8_t *data, size_t len);

// Received bytes the endpoint holds at most: the TCP window (lwipopts.h
// TCP_WND). The window reopens only as tcp_take hands bytes on.
constexpr size_t kReceiveCap = 8 * 1400;

// Takes every byte received since the last call and reopens the window by that much.
std::vector<uint8_t> tcp_take();

TcpState tcp_state();

// How far an orderly close has come: our FIN sent, acknowledged, and the
// connection finished (TIME_WAIT after the peer's FIN, or closed after it).
enum class CloseProgress : int { NotClosing = 0, FinSent = 1, FinAcked = 2, Complete = 3, Failed = 4 };

// Closes the send side with a FIN, leaving receive open for the peer's FIN.
// False when there is no open connection to close.
bool tcp_finish();

CloseProgress tcp_close_progress();

uint64_t monotonic_ms();

}  // namespace ados::bindtun
