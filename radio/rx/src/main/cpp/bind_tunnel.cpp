// SPDX-License-Identifier: GPL-3.0-only
//
// JNI bridge for com.altnautica.gcs.radio.rx.bind.BindTunnelNative: the bind
// session's IP endpoint (bind_tunnel_core.h). Every call comes from the one
// bind thread.

#include <jni.h>

#include "bind_tunnel_core.h"

namespace bt = ados::bindtun;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeOpen(JNIEnv *, jclass, jint local_ip) {
    return bt::stack_open(static_cast<uint32_t>(local_ip)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeClose(JNIEnv *, jclass) {
    bt::stack_close();
}

JNIEXPORT void JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeInput(JNIEnv *env, jclass, jbyteArray datagram) {
    const jsize n = env->GetArrayLength(datagram);
    std::vector<uint8_t> buf(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(datagram, 0, n, reinterpret_cast<jbyte *>(buf.data()));
    bt::stack_input(buf.data(), buf.size());
}

JNIEXPORT jobjectArray JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativePoll(JNIEnv *env, jclass) {
    std::vector<bt::Datagram> out;
    bt::stack_poll(out);
    jclass byte_array = env->FindClass("[B");
    if (byte_array == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(out.size()), byte_array, nullptr);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < out.size(); i++) {
        const auto &d = out[i];
        jbyteArray a = env->NewByteArray(static_cast<jsize>(d.size()));
        if (a == nullptr) return nullptr;
        if (!d.empty()) env->SetByteArrayRegion(a, 0, static_cast<jsize>(d.size()), reinterpret_cast<const jbyte *>(d.data()));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), a);
        env->DeleteLocalRef(a);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeConnect(JNIEnv *, jclass, jint ip, jint port) {
    if (port < 1 || port > 0xffff) return JNI_FALSE;
    return bt::tcp_open(static_cast<uint32_t>(ip), static_cast<uint16_t>(port)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeSend(JNIEnv *env, jclass, jbyteArray data, jint offset, jint length) {
    if (offset < 0 || length < 0 || offset + length > env->GetArrayLength(data)) return 0;
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    if (length > 0) env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(buf.data()));
    return static_cast<jint>(bt::tcp_send(buf.data(), buf.size()));
}

JNIEXPORT jbyteArray JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeTake(JNIEnv *env, jclass) {
    const std::vector<uint8_t> bytes = bt::tcp_take();
    jbyteArray a = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (a != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(a, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
    }
    return a;
}

JNIEXPORT jint JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeState(JNIEnv *, jclass) {
    return static_cast<jint>(bt::tcp_state());
}

JNIEXPORT jboolean JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeFinish(JNIEnv *, jclass) {
    return bt::tcp_finish() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_altnautica_gcs_radio_rx_bind_BindTunnelNative_nativeCloseProgress(JNIEnv *, jclass) {
    return static_cast<jint>(bt::tcp_close_progress());
}

}  // extern "C"
