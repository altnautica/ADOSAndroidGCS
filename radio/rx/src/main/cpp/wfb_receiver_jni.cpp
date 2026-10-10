// SPDX-License-Identifier: GPL-3.0-only
//
// JNI bridge for com.altnautica.gcs.radio.rx.WfbReceiver.
//
// The USB radio service hands the app raw 802.11 frames. This bridge runs
// them through the vendored aggregator (session key exchange, decrypt,
// Reed-Solomon recovery) for each radio port of one link and calls back
// WfbReceiver.onPacket(port, payload) for every recovered datagram, on the
// thread that called nativeFeed.
//
// It also opens the sealed phone invite a ground station issues: X25519
// with the phone's secret, a two-step HMAC-SHA256 session key, then
// ChaCha20-Poly1305 (IETF nonce, empty associated data).

#include <jni.h>

#include <android/log.h>
#include <fcntl.h>
#include <sodium.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "rx.hpp"
#include "wifibroadcast.hpp"

namespace {

constexpr const char *kTag = "AdosWfbRx";
constexpr const char *kReceiverClass = "com/altnautica/gcs/radio/rx/WfbReceiver";
constexpr size_t kRxKeyBytes = crypto_box_SECRETKEYBYTES + crypto_box_PUBLICKEYBYTES;
constexpr size_t kHeaderBytes = sizeof(ieee80211_header);
constexpr uint64_t kStatsWindowMs = 1000;

// Counters of one port over the last closed stats window.
constexpr int kStatFields = 10;

struct ReceiverHandle;

class PortAggregator final : public Aggregator {
public:
    PortAggregator(const std::string &key_path, uint32_t channel_id, int port, ReceiverHandle *owner)
        : Aggregator(key_path, 0, channel_id), channel_id_(channel_id), port_(port), owner_(owner) {}

    uint32_t channel_id() const { return channel_id_; }
    int port() const { return port_; }

protected:
    void send_to_socket(const uint8_t *payload, uint16_t packet_size) override;

private:
    const uint32_t channel_id_;
    const int port_;
    ReceiverHandle *const owner_;
};

struct ReceiverHandle {
    jobject receiver = nullptr;  // global reference to the Kotlin WfbReceiver
    jmethodID on_packet = nullptr;
    JNIEnv *env = nullptr;       // valid only inside nativeFeed
    std::vector<std::unique_ptr<PortAggregator>> ports;

    uint64_t window_start_ms = 0;
    std::mutex stats_lock;
    std::vector<int64_t> last_window;  // kStatFields per port
    int64_t foreign_frames = 0;        // frames for another link or not ours
    int64_t malformed_records = 0;

    void deliver(int port, const uint8_t *payload, uint16_t size) {
        if (env == nullptr) return;
        jbyteArray array = env->NewByteArray(size);
        if (array == nullptr) {
            env->ExceptionClear();
            return;
        }
        env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte *>(payload));
        env->CallVoidMethod(receiver, on_packet, static_cast<jint>(port), array);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(array);
    }

    // Closes the stats window on the feed thread, the only thread that
    // touches aggregator state; readers see the last closed window.
    void roll_window() {
        const uint64_t now = get_time_ms();
        if (window_start_ms == 0) {
            window_start_ms = now;
            return;
        }
        if (now - window_start_ms < kStatsWindowMs) return;
        std::vector<int64_t> snapshot;
        snapshot.reserve(ports.size() * kStatFields);
        for (auto &agg : ports) {
            snapshot.push_back(agg->port());
            snapshot.push_back(agg->count_p_all);
            snapshot.push_back(agg->count_p_dec_err);
            snapshot.push_back(agg->count_p_session);
            snapshot.push_back(agg->count_p_data);
            snapshot.push_back(static_cast<int64_t>(agg->count_p_uniq.size()));
            snapshot.push_back(agg->count_p_fec_recovered);
            snapshot.push_back(agg->count_p_lost);
            snapshot.push_back(agg->count_p_bad);
            snapshot.push_back(agg->count_p_outgoing);
            agg->clear_stats();
        }
        {
            std::lock_guard<std::mutex> guard(stats_lock);
            last_window.swap(snapshot);
        }
        window_start_ms = now;
    }
};

void PortAggregator::send_to_socket(const uint8_t *payload, uint16_t packet_size) {
    owner_->deliver(port_, payload, packet_size);
}

// The aggregator reads its key pair from a path. Hand it one through a pipe
// so the key never touches storage: write it, close the write end, and let
// fopen("/proc/self/fd/N") read it back once.
std::unique_ptr<PortAggregator> make_aggregator(const uint8_t *key, uint32_t channel_id, int port,
                                                ReceiverHandle *owner) {
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) {
        throw std::runtime_error("pipe failed");
    }
    const ssize_t written = write(fds[1], key, kRxKeyBytes);
    close(fds[1]);
    if (written != static_cast<ssize_t>(kRxKeyBytes)) {
        close(fds[0]);
        throw std::runtime_error("key hand-off failed");
    }
    const std::string path = "/proc/self/fd/" + std::to_string(fds[0]);
    try {
        auto agg = std::make_unique<PortAggregator>(path, channel_id, port, owner);
        close(fds[0]);
        return agg;
    } catch (...) {
        close(fds[0]);
        throw;
    }
}

void throw_java(JNIEnv *env, const char *klass, const char *message) {
    jclass cls = env->FindClass(klass);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

jlong native_create(JNIEnv *env, jobject thiz, jbyteArray rx_key, jint link_id, jintArray ports) {
    if (env->GetArrayLength(rx_key) != static_cast<jsize>(kRxKeyBytes)) {
        throw_java(env, "java/lang/IllegalArgumentException", "rx key must be 64 bytes");
        return 0;
    }
    const jsize port_count = env->GetArrayLength(ports);
    if (port_count <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "no ports");
        return 0;
    }
    uint8_t key[kRxKeyBytes];
    env->GetByteArrayRegion(rx_key, 0, kRxKeyBytes, reinterpret_cast<jbyte *>(key));
    std::vector<jint> port_values(port_count);
    env->GetIntArrayRegion(ports, 0, port_count, port_values.data());

    auto handle = std::make_unique<ReceiverHandle>();
    jclass cls = env->GetObjectClass(thiz);
    handle->on_packet = env->GetMethodID(cls, "onPacket", "(I[B)V");
    if (handle->on_packet == nullptr) {
        sodium_memzero(key, sizeof(key));
        return 0;
    }
    try {
        for (jint port : port_values) {
            if (port < 0 || port > 255) throw std::invalid_argument("port out of range");
            const uint32_t channel_id = (static_cast<uint32_t>(link_id) << 8) + static_cast<uint32_t>(port);
            handle->ports.push_back(make_aggregator(key, channel_id, port, handle.get()));
        }
    } catch (const std::exception &e) {
        sodium_memzero(key, sizeof(key));
        throw_java(env, "java/lang/IllegalStateException", e.what());
        return 0;
    }
    sodium_memzero(key, sizeof(key));
    handle->receiver = env->NewGlobalRef(thiz);
    __android_log_print(ANDROID_LOG_INFO, kTag, "receiver ready: link 0x%06x, %d port(s)",
                        static_cast<unsigned>(link_id), static_cast<int>(port_count));
    return reinterpret_cast<jlong>(handle.release());
}

// Record body layout (after the 4-byte length the reader strips):
//   u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame (no FCS)
void native_feed(JNIEnv *env, jobject, jlong ptr, jobject buffer, jint length) {
    auto *handle = reinterpret_cast<ReceiverHandle *>(ptr);
    if (handle == nullptr) return;
    auto *data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || length <= 0 || length > capacity) {
        handle->malformed_records++;
        return;
    }
    handle->roll_window();

    const size_t len = static_cast<size_t>(length);
    const size_t antennas = data[0];
    if (antennas > RX_ANT_MAX || len < 1 + 2 * antennas) {
        handle->malformed_records++;
        return;
    }
    const uint8_t *frame = data + 1 + 2 * antennas;
    const size_t frame_len = len - 1 - 2 * antennas;
    if (frame_len <= kHeaderBytes || frame[10] != 0x57 || frame[11] != 0x42) {
        handle->foreign_frames++;
        return;
    }
    const uint32_t channel_id = (static_cast<uint32_t>(frame[12]) << 24) | (static_cast<uint32_t>(frame[13]) << 16) |
                                (static_cast<uint32_t>(frame[14]) << 8) | static_cast<uint32_t>(frame[15]);
    PortAggregator *target = nullptr;
    for (auto &agg : handle->ports) {
        if (agg->channel_id() == channel_id) {
            target = agg.get();
            break;
        }
    }
    if (target == nullptr) {
        handle->foreign_frames++;
        return;
    }

    uint8_t antenna[RX_ANT_MAX];
    int8_t rssi[RX_ANT_MAX];
    int8_t noise[RX_ANT_MAX];
    memset(antenna, 0xff, sizeof(antenna));
    memset(rssi, SCHAR_MIN, sizeof(rssi));
    memset(noise, SCHAR_MAX, sizeof(noise));
    for (size_t i = 0; i < antennas; i++) {
        antenna[i] = static_cast<uint8_t>(i);
        rssi[i] = static_cast<int8_t>(data[1 + i]);
        noise[i] = static_cast<int8_t>(rssi[i] - static_cast<int8_t>(data[1 + antennas + i]));
    }

    handle->env = env;
    target->process_packet(frame + kHeaderBytes, frame_len - kHeaderBytes, 0, antenna, rssi, noise,
                           0, 0, 20, nullptr);
    handle->env = nullptr;
}

jlongArray native_stats(JNIEnv *env, jobject, jlong ptr) {
    auto *handle = reinterpret_cast<ReceiverHandle *>(ptr);
    if (handle == nullptr) return env->NewLongArray(0);
    std::vector<int64_t> copy;
    {
        std::lock_guard<std::mutex> guard(handle->stats_lock);
        copy = handle->last_window;
    }
    copy.push_back(handle->foreign_frames);
    copy.push_back(handle->malformed_records);
    jlongArray out = env->NewLongArray(static_cast<jsize>(copy.size()));
    if (out != nullptr) {
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(copy.size()), reinterpret_cast<const jlong *>(copy.data()));
    }
    return out;
}

void native_destroy(JNIEnv *env, jobject, jlong ptr) {
    auto *handle = reinterpret_cast<ReceiverHandle *>(ptr);
    if (handle == nullptr) return;
    if (handle->receiver != nullptr) env->DeleteGlobalRef(handle->receiver);
    delete handle;
}

jbyteArray to_java(JNIEnv *env, const uint8_t *bytes, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(bytes));
    }
    return out;
}

// 64 bytes: X25519 secret (32) followed by its public key (32).
jbyteArray native_generate_key_pair(JNIEnv *env, jclass) {
    uint8_t pair[crypto_scalarmult_SCALARBYTES + crypto_scalarmult_BYTES];
    randombytes_buf(pair, crypto_scalarmult_SCALARBYTES);
    if (crypto_scalarmult_base(pair + crypto_scalarmult_SCALARBYTES, pair) != 0) {
        sodium_memzero(pair, sizeof(pair));
        return nullptr;
    }
    jbyteArray out = to_java(env, pair, sizeof(pair));
    sodium_memzero(pair, sizeof(pair));
    return out;
}

// Public key for a 32-byte X25519 secret.
jbyteArray native_public_key(JNIEnv *env, jclass, jbyteArray secret) {
    if (env->GetArrayLength(secret) != crypto_scalarmult_SCALARBYTES) return nullptr;
    uint8_t sk[crypto_scalarmult_SCALARBYTES];
    uint8_t pk[crypto_scalarmult_BYTES];
    env->GetByteArrayRegion(secret, 0, sizeof(sk), reinterpret_cast<jbyte *>(sk));
    const int rc = crypto_scalarmult_base(pk, sk);
    sodium_memzero(sk, sizeof(sk));
    return rc == 0 ? to_java(env, pk, sizeof(pk)) : nullptr;
}

std::vector<uint8_t> bytes_of(JNIEnv *env, jbyteArray array) {
    std::vector<uint8_t> out(static_cast<size_t>(env->GetArrayLength(array)));
    if (!out.empty()) env->GetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray native_open_invite(JNIEnv *env, jclass, jbyteArray phone_secret, jbyteArray gs_eph_pub, jbyteArray nonce,
                              jbyteArray ciphertext, jbyteArray context) {
    std::vector<uint8_t> sk = bytes_of(env, phone_secret);
    const std::vector<uint8_t> pk = bytes_of(env, gs_eph_pub);
    const std::vector<uint8_t> iv = bytes_of(env, nonce);
    const std::vector<uint8_t> ct = bytes_of(env, ciphertext);
    const std::vector<uint8_t> ctx = bytes_of(env, context);
    if (sk.size() != crypto_scalarmult_SCALARBYTES || pk.size() != crypto_scalarmult_BYTES ||
        iv.size() != crypto_aead_chacha20poly1305_ietf_NPUBBYTES || ct.size() < crypto_aead_chacha20poly1305_ietf_ABYTES) {
        sodium_memzero(sk.data(), sk.size());
        return nullptr;
    }

    uint8_t shared[crypto_scalarmult_BYTES];
    const int dh = crypto_scalarmult(shared, sk.data(), pk.data());
    sodium_memzero(sk.data(), sk.size());
    if (dh != 0) return nullptr;

    // prk = HMAC-SHA256(key = 0x00 * 32, msg = shared)
    uint8_t zero_salt[crypto_auth_hmacsha256_KEYBYTES] = {0};
    uint8_t prk[crypto_auth_hmacsha256_BYTES];
    crypto_auth_hmacsha256_state st;
    crypto_auth_hmacsha256_init(&st, zero_salt, sizeof(zero_salt));
    crypto_auth_hmacsha256_update(&st, shared, sizeof(shared));
    crypto_auth_hmacsha256_final(&st, prk);
    sodium_memzero(shared, sizeof(shared));

    // key = HMAC-SHA256(key = prk, msg = context || 0x01)
    const uint8_t counter = 0x01;
    uint8_t key[crypto_aead_chacha20poly1305_ietf_KEYBYTES];
    crypto_auth_hmacsha256_init(&st, prk, sizeof(prk));
    crypto_auth_hmacsha256_update(&st, ctx.data(), ctx.size());
    crypto_auth_hmacsha256_update(&st, &counter, 1);
    crypto_auth_hmacsha256_final(&st, key);
    sodium_memzero(prk, sizeof(prk));
    sodium_memzero(&st, sizeof(st));

    std::vector<uint8_t> plain(ct.size() - crypto_aead_chacha20poly1305_ietf_ABYTES);
    unsigned long long plain_len = 0;
    const int rc = crypto_aead_chacha20poly1305_ietf_decrypt(plain.data(), &plain_len, nullptr, ct.data(), ct.size(),
                                                             nullptr, 0, iv.data(), key);
    sodium_memzero(key, sizeof(key));
    if (rc != 0) return nullptr;
    jbyteArray out = to_java(env, plain.data(), static_cast<size_t>(plain_len));
    sodium_memzero(plain.data(), plain.size());
    return out;
}

const JNINativeMethod kInstanceMethods[] = {
    {const_cast<char *>("nativeCreate"), const_cast<char *>("([BI[I)J"), reinterpret_cast<void *>(native_create)},
    {const_cast<char *>("nativeFeed"), const_cast<char *>("(JLjava/nio/ByteBuffer;I)V"), reinterpret_cast<void *>(native_feed)},
    {const_cast<char *>("nativeStats"), const_cast<char *>("(J)[J"), reinterpret_cast<void *>(native_stats)},
    {const_cast<char *>("nativeDestroy"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_destroy)},
    {const_cast<char *>("nativeGenerateKeyPair"), const_cast<char *>("()[B"), reinterpret_cast<void *>(native_generate_key_pair)},
    {const_cast<char *>("nativePublicKey"), const_cast<char *>("([B)[B"), reinterpret_cast<void *>(native_public_key)},
    {const_cast<char *>("nativeOpenInvite"), const_cast<char *>("([B[B[B[B[B)[B"), reinterpret_cast<void *>(native_open_invite)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    if (sodium_init() < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "libsodium failed to initialise");
        return JNI_ERR;
    }
    jclass cls = env->FindClass(kReceiverClass);
    if (cls == nullptr) return JNI_ERR;
    const jint count = static_cast<jint>(sizeof(kInstanceMethods) / sizeof(kInstanceMethods[0]));
    if (env->RegisterNatives(cls, kInstanceMethods, count) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
