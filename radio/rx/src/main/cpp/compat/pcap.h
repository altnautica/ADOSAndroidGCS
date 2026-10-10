// SPDX-License-Identifier: GPL-3.0-only
//
// Declarations-only stand-in for <pcap.h>.
//
// The vendored receive sources include <pcap.h> because their command-line
// receiver captures from a kernel monitor-mode interface. On the phone the
// 802.11 frames arrive from the USB radio service instead, and only the
// aggregator (FEC + decrypt) is used. The capture class still has to
// compile, so this header declares the handful of types and functions it
// names. The library links with --gc-sections and never references that
// class, so none of these functions are called or linked.

#ifndef ADOS_COMPAT_PCAP_H
#define ADOS_COMPAT_PCAP_H

#include <stdint.h>
#include <sys/time.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PCAP_ERRBUF_SIZE 256
#define DLT_IEEE802_11_RADIO 127

typedef struct pcap pcap_t;

struct pcap_pkthdr {
    struct timeval ts;
    uint32_t caplen;
    uint32_t len;
};

struct bpf_insn;

struct bpf_program {
    unsigned int bf_len;
    struct bpf_insn *bf_insns;
};

pcap_t *pcap_create(const char *source, char *errbuf);
int pcap_set_buffer_size(pcap_t *p, int size);
int pcap_set_snaplen(pcap_t *p, int snaplen);
int pcap_set_promisc(pcap_t *p, int promisc);
int pcap_set_timeout(pcap_t *p, int ms);
int pcap_set_immediate_mode(pcap_t *p, int mode);
int pcap_activate(pcap_t *p);
int pcap_setnonblock(pcap_t *p, int nonblock, char *errbuf);
int pcap_datalink(pcap_t *p);
int pcap_compile(pcap_t *p, struct bpf_program *fp, const char *str, int optimize, uint32_t netmask);
int pcap_setfilter(pcap_t *p, struct bpf_program *fp);
void pcap_freecode(struct bpf_program *fp);
int pcap_get_selectable_fd(pcap_t *p);
char *pcap_geterr(pcap_t *p);
void pcap_close(pcap_t *p);
const uint8_t *pcap_next(pcap_t *p, struct pcap_pkthdr *h);

#ifdef __cplusplus
}
#endif

#endif // ADOS_COMPAT_PCAP_H
