// SPDX-License-Identifier: GPL-3.0-only
//
// JNI bridge for com.altnautica.gcs.radio.rx.WfbTransmitter.
//
// One WFB transmitter (one radio port of one link): the vendored
// wfb-ng Transmitter does FEC, the session-key exchange, encryption and
// block numbering unchanged; this subclass's only job is where a finished
// packet goes. Where the command-line transmitter writes to a raw socket, it
// writes one record to the USB radio's transmit pipe:
//
//   u32 le length | radiotap header | 802.11 header | packet
//
// with exactly the radiotap and 802.11 headers RawSocketTransmitter puts in
// front of the same packet (tx.cpp, RawSocketTransmitter::inject_packet). The
// pipe is non-blocking and every record fits in PIPE_BUF, so a record is
// written whole or not at all; a full pipe drops the packet and counts it,
// never stalls the caller.

#include <jni.h>

#include <android/log.h>
#include <assert.h>
#include <endian.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <net/if.h>
#include <sodium.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <linux/if_packet.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "zfex.h"

// tx.hpp is written to be included by tx.cpp after these headers and this
// directive; it names std types unqualified.
using namespace std;

#include "wifibroadcast.hpp"
#include "tx.hpp"

namespace {

constexpr const char *kTag = "AdosWfbTx";
constexpr const char *kTransmitterClass = "com/altnautica/gcs/radio/rx/WfbTransmitter";
constexpr size_t kKeyBytes = crypto_box_SECRETKEYBYTES + crypto_box_PUBLICKEYBYTES;

class PipeTransmitter final : public Transmitter {
public:
    PipeTransmitter(const std::string &key_path, int k, int n, uint32_t channel_id,
                    const radiotap_header_t &radiotap, int fd, std::vector<tags_item_t> &tags)
        : Transmitter(k, n, key_path, /*epoch=*/0, channel_id, /*fec_delay=*/0, tags),
          channel_id_(channel_id), radiotap_(radiotap), fd_(fd) {}

    ~PipeTransmitter() override {
        if (fd_ >= 0) close(fd_);
    }

    void select_output(int) override {}

    void dump_stats(uint64_t, uint32_t &injected_packets, uint32_t &dropped_packets,
                    uint32_t &injected_bytes) override {
        injected_packets = static_cast<uint32_t>(injected);
        dropped_packets = static_cast<uint32_t>(dropped);
        injected_bytes = static_cast<uint32_t>(injected_bytes_);
    }

    void update_radiotap_header(radiotap_header_t &radiotap) override { radiotap_ = radiotap; }

    radiotap_header_t get_radiotap_header() override { return radiotap_; }

    int64_t injected = 0;
    int64_t injected_bytes_ = 0;
    int64_t dropped = 0;

protected:
    void inject_packet(const uint8_t *buf, size_t size) override {
        uint8_t ieee_hdr[sizeof(ieee80211_header)];
        memcpy(ieee_hdr, ieee80211_header, sizeof(ieee80211_header));
        ieee_hdr[0] = FRAME_TYPE_DATA;
        const uint32_t channel_id_be = htobe32(channel_id_);
        memcpy(ieee_hdr + SRC_MAC_THIRD_BYTE, &channel_id_be, sizeof(uint32_t));
        memcpy(ieee_hdr + DST_MAC_THIRD_BYTE, &channel_id_be, sizeof(uint32_t));
        ieee_hdr[FRAME_SEQ_LB] = ieee80211_seq_ & 0xff;
        ieee_hdr[FRAME_SEQ_HB] = (ieee80211_seq_ >> 8) & 0xff;
        ieee80211_seq_ += 16;

        const std::vector<uint8_t> &rt = radiotap_.header;
        const size_t body = rt.size() + sizeof(ieee_hdr) + size;
        uint8_t record[PIPE_BUF];
        if (4 + body > sizeof(record)) {
            dropped += 1;
            return;
        }
        record[0] = static_cast<uint8_t>(body & 0xff);
        record[1] = static_cast<uint8_t>((body >> 8) & 0xff);
        record[2] = static_cast<uint8_t>((body >> 16) & 0xff);
        record[3] = static_cast<uint8_t>((body >> 24) & 0xff);
        memcpy(record + 4, rt.data(), rt.size());
        memcpy(record + 4 + rt.size(), ieee_hdr, sizeof(ieee_hdr));
        memcpy(record + 4 + rt.size() + sizeof(ieee_hdr), buf, size);
        const ssize_t n = write(fd_, record, 4 + body);
        if (n == static_cast<ssize_t>(4 + body)) {
            injected += 1;
            injected_bytes_ += static_cast<int64_t>(body);
        } else {
            dropped += 1;
        }
    }

    void set_mark(uint32_t) override {}

private:
    const uint32_t channel_id_;
    radiotap_header_t radiotap_;
    const int fd_;
    uint16_t ieee80211_seq_ = 0;
};

struct TransmitterHandle {
    std::mutex lock;
    std::unique_ptr<PipeTransmitter> tx;
    uint64_t session_key_announce_ms = 0;
    int64_t packets_in = 0;
    int64_t bytes_in = 0;
    int64_t oversize = 0;
};

void throw_java(JNIEnv *env, const char *klass, const char *message) {
    jclass cls = env->FindClass(klass);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

// The Transmitter reads its key pair from a path. As for the receiver, hand
// it one through a pipe so the key never touches storage.
std::unique_ptr<PipeTransmitter> make_transmitter(const uint8_t *key, int k, int n, uint32_t channel_id,
                                                  const radiotap_header_t &radiotap, int fd) {
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) throw std::runtime_error("pipe failed");
    const ssize_t written = write(fds[1], key, kKeyBytes);
    close(fds[1]);
    if (written != static_cast<ssize_t>(kKeyBytes)) {
        close(fds[0]);
        throw std::runtime_error("key hand-off failed");
    }
    const std::string path = "/proc/self/fd/" + std::to_string(fds[0]);
    std::vector<tags_item_t> tags;
    try {
        auto tx = std::make_unique<PipeTransmitter>(path, k, n, channel_id, radiotap, fd, tags);
        close(fds[0]);
        return tx;
    } catch (...) {
        close(fds[0]);
        throw;
    }
}

// Takes ownership of pipe_fd (closed with the transmitter, or here on failure).
jlong native_open(JNIEnv *env, jclass, jint pipe_fd, jbyteArray key_bytes, jint link_id, jint radio_port,
                  jint k, jint n, jint mcs_index, jint bandwidth_mhz) {
    auto fail = [&](const char *klass, const char *message) -> jlong {
        close(pipe_fd);
        throw_java(env, klass, message);
        return 0;
    };
    if (env->GetArrayLength(key_bytes) != static_cast<jsize>(kKeyBytes)) {
        return fail("java/lang/IllegalArgumentException", "key must be 64 bytes");
    }
    if (k < 1 || n < k || n > 255 || radio_port < 0 || radio_port > 255 || link_id < 0 || link_id > 0xffffff) {
        return fail("java/lang/IllegalArgumentException", "transmitter parameters out of range");
    }
    const int flags = fcntl(pipe_fd, F_GETFL);
    if (flags < 0 || fcntl(pipe_fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        return fail("java/lang/IllegalStateException", "transmit pipe unusable");
    }
    uint8_t key[kKeyBytes];
    env->GetByteArrayRegion(key_bytes, 0, kKeyBytes, reinterpret_cast<jbyte *>(key));
    const uint32_t channel_id = (static_cast<uint32_t>(link_id) << 8) + static_cast<uint32_t>(radio_port);
    auto handle = std::make_unique<TransmitterHandle>();
    try {
        // The box's transmitters pass no -S, -L, -G or -V: STBC, LDPC and the
        // short guard interval off, HT (not VHT) with one stream.
        const radiotap_header_t radiotap = init_radiotap_header(
            /*stbc=*/0, /*ldpc=*/false, /*short_gi=*/false, static_cast<uint8_t>(bandwidth_mhz),
            static_cast<uint8_t>(mcs_index), /*vht_mode=*/false, /*vht_nss=*/1);
        handle->tx = make_transmitter(key, k, n, channel_id, radiotap, pipe_fd);
    } catch (const std::exception &e) {
        sodium_memzero(key, sizeof(key));
        return fail("java/lang/IllegalStateException", e.what());
    }
    sodium_memzero(key, sizeof(key));
    handle->session_key_announce_ms = get_time_ms();
    __android_log_print(ANDROID_LOG_INFO, kTag, "transmitter open: channel id 0x%08x, k=%d n=%d, MCS %d",
                        channel_id, k, n, mcs_index);
    return reinterpret_cast<jlong>(handle.release());
}

// Sends one datagram; false when it is larger than one WFB packet carries or
// the transmitter is closed.
jboolean native_send(JNIEnv *env, jclass, jlong ptr, jbyteArray payload) {
    auto *h = reinterpret_cast<TransmitterHandle *>(ptr);
    if (h == nullptr) return JNI_FALSE;
    const jsize size = env->GetArrayLength(payload);
    std::lock_guard<std::mutex> guard(h->lock);
    if (!h->tx) return JNI_FALSE;
    h->packets_in += 1;
    h->bytes_in += size;
    if (static_cast<size_t>(size) > MAX_PAYLOAD_SIZE) {
        h->oversize += 1;
        return JNI_FALSE;
    }
    std::vector<uint8_t> buf(static_cast<size_t>(size));
    env->GetByteArrayRegion(payload, 0, size, reinterpret_cast<jbyte *>(buf.data()));
    try {
        // As the command-line transmitter does: the session key goes out
        // ahead of data, at most once a second, only while there is data.
        const uint64_t now = get_time_ms();
        if (now >= h->session_key_announce_ms) {
            h->tx->send_session_key();
            h->session_key_announce_ms = now + SESSION_KEY_ANNOUNCE_MSEC;
        }
        h->tx->send_packet(buf.data(), buf.size(), 0);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "send failed: %s", e.what());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// [packetsIn, bytesIn, framesInjected, bytesInjected, framesDropped, packetsOversize], cumulative.
jlongArray native_stats(JNIEnv *env, jclass, jlong ptr) {
    auto *h = reinterpret_cast<TransmitterHandle *>(ptr);
    jlong values[6] = {0, 0, 0, 0, 0, 0};
    if (h != nullptr) {
        std::lock_guard<std::mutex> guard(h->lock);
        if (h->tx) {
            values[0] = h->packets_in;
            values[1] = h->bytes_in;
            values[2] = h->tx->injected;
            values[3] = h->tx->injected_bytes_;
            values[4] = h->tx->dropped;
            values[5] = h->oversize;
        }
    }
    jlongArray out = env->NewLongArray(6);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 6, values);
    return out;
}

// The transmitter, and with it the pipe's descriptor, is destroyed under the
// handle's lock, so a send still inside never writes to a closed or reused
// descriptor. That a call cannot start on the handle once close began, and so
// that the handle itself may go after, is WfbTransmitter's lock.
void native_close(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<TransmitterHandle *>(ptr);
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(h->lock);
        h->tx.reset();
    }
    delete h;
}

const JNINativeMethod kMethods[] = {
    {const_cast<char *>("nativeOpen"), const_cast<char *>("(I[BIIIIII)J"), reinterpret_cast<void *>(native_open)},
    {const_cast<char *>("nativeSend"), const_cast<char *>("(J[B)Z"), reinterpret_cast<void *>(native_send)},
    {const_cast<char *>("nativeStats"), const_cast<char *>("(J)[J"), reinterpret_cast<void *>(native_stats)},
    {const_cast<char *>("nativeClose"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_close)},
};

}  // namespace

// Called from the library's JNI_OnLoad (wfb_receiver_jni.cpp).
bool ados_register_wfb_transmitter(JNIEnv *env) {
    jclass cls = env->FindClass(kTransmitterClass);
    if (cls == nullptr) return false;
    const jint count = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
    return env->RegisterNatives(cls, kMethods, count) == JNI_OK;
}
