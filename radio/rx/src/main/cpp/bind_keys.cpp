// SPDX-License-Identifier: GPL-3.0-only
//
// JNI bridge for com.altnautica.gcs.radio.rx.bind.BindKeys: a fresh fleet
// keypair exactly as wfb-ng's wfb_keygen makes one, two crypto_box key pairs
// from libsodium's random source:
//
//   drone.key = drone secret (32) || ground public (32)
//   gs.key    = ground secret (32) || drone public (32)
//
// returned as one 128-byte array, drone.key then gs.key.

#include <jni.h>
#include <sodium.h>

#include <cstring>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindKeys_nativeGenerate(JNIEnv *env, jclass) {
    unsigned char drone_pk[crypto_box_PUBLICKEYBYTES], drone_sk[crypto_box_SECRETKEYBYTES];
    unsigned char gs_pk[crypto_box_PUBLICKEYBYTES], gs_sk[crypto_box_SECRETKEYBYTES];
    if (sodium_init() < 0 || crypto_box_keypair(drone_pk, drone_sk) != 0 || crypto_box_keypair(gs_pk, gs_sk) != 0) {
        return nullptr;
    }
    unsigned char out[2 * (crypto_box_SECRETKEYBYTES + crypto_box_PUBLICKEYBYTES)];
    unsigned char *p = out;
    memcpy(p, drone_sk, sizeof(drone_sk));
    p += sizeof(drone_sk);
    memcpy(p, gs_pk, sizeof(gs_pk));
    p += sizeof(gs_pk);
    memcpy(p, gs_sk, sizeof(gs_sk));
    p += sizeof(gs_sk);
    memcpy(p, drone_pk, sizeof(drone_pk));
    jbyteArray result = env->NewByteArray(sizeof(out));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, sizeof(out), reinterpret_cast<const jbyte *>(out));
    sodium_memzero(drone_sk, sizeof(drone_sk));
    sodium_memzero(gs_sk, sizeof(gs_sk));
    sodium_memzero(out, sizeof(out));
    return result;
}
