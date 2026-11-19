// SPDX-License-Identifier: GPL-3.0-only
//
// lwIP configuration for the bind tunnel: one point-to-point IPv4 interface,
// TCP only, no OS (the bind thread drives every call and the timers), live
// only for one bind session.

#ifndef ADOS_LWIPOPTS_H
#define ADOS_LWIPOPTS_H

#define NO_SYS 1
#define SYS_LIGHTWEIGHT_PROT 0
#define LWIP_NETCONN 0
#define LWIP_SOCKET 0
#define LWIP_TCPIP_CORE_LOCKING 0

#define LWIP_IPV4 1
#define LWIP_IPV6 0
#define LWIP_TCP 1
#define LWIP_UDP 0
#define LWIP_RAW 0
#define LWIP_ICMP 1
#define LWIP_IGMP 0
#define LWIP_DNS 0
#define LWIP_DHCP 0
#define LWIP_AUTOIP 0
#define LWIP_ACD 0
#define LWIP_ARP 0
#define LWIP_ETHERNET 0
#define IP_FORWARD 0
#define IP_REASSEMBLY 0
#define IP_FRAG 0
#define LWIP_SINGLE_NETIF 1
#define LWIP_NETIF_STATUS_CALLBACK 0
#define LWIP_NETIF_LINK_CALLBACK 0
#define LWIP_HAVE_LOOPIF 0
#define LWIP_NETIF_LOOPBACK 0
#define LWIP_STATS 0
#define LWIP_ALTCP 0

#define MEM_ALIGNMENT 8
#define MEM_SIZE (64 * 1024)
#define MEMP_NUM_PBUF 32
#define MEMP_NUM_TCP_PCB 2
#define MEMP_NUM_TCP_PCB_LISTEN 1
#define MEMP_NUM_TCP_SEG 64
#define PBUF_POOL_SIZE 32

// The tunnel carries IP packets of at most 1443 bytes (wfb_tun's 1445-byte
// radio MTU less its 2-byte length prefix); 1400 leaves room for headers.
#define TCP_MSS 1400
#define TCP_WND (8 * TCP_MSS)
#define TCP_SND_BUF (8 * TCP_MSS)
#define TCP_SND_QUEUELEN (4 * TCP_SND_BUF / TCP_MSS)
#define LWIP_TCP_KEEPALIVE 0
#define LWIP_CHECKSUM_ON_COPY 0

#define LWIP_RAND() ((u32_t)ados_lwip_rand())

#ifdef __cplusplus
extern "C" {
#endif
unsigned int ados_lwip_rand(void);
#ifdef __cplusplus
}
#endif

#endif
