// SPDX-License-Identifier: GPL-2.0-or-later
//
// JNI bridge for com.altnautica.gcs.radio.usb.NativeUsbRadio.
//
// Runs only inside the radio service process. Opens the Realtek USB radio
// through the file descriptor Android granted (libusb_wrap_sys_device), brings
// it up in monitor mode on one channel and writes every received frame that
// carries the broadcast data-plane address prefix to a pipe as one record:
//
//   u32 le length | u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame
//
// `length` counts everything after itself. The frame excludes the FCS. The
// pipe's write end is non-blocking and every record fits in PIPE_BUF, so a
// record is written whole or not at all; when the reader falls behind,
// records are dropped and counted rather than stalling the USB receive loop.

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

#include <libusb.h>

#include "IRtlDevice.h"
#include "RxPacket.h"
#include "SelectedChannel.h"
#include "UsbDeviceLock.h"
#include "UsbOpen.h"
#include "WiFiDriver.h"
#include "logger.h"

namespace {

constexpr const char *kTag = "AdosUsbRadio";
constexpr const char *kClass = "com/altnautica/gcs/radio/usb/NativeUsbRadio";
constexpr size_t kFcsBytes = 4;
constexpr size_t kMinFrame = 24;
constexpr int kMaxAntennas = 4;
// Realtek reports path power as a 0..110 scale and SNR in half-dB steps.
constexpr int kRssiOffset = 110;

struct Radio {
    Logger_t logger;
    libusb_context *ctx = nullptr;
    libusb_device_handle *handle = nullptr;
    int iface = 0;
    std::shared_ptr<devourer::UsbDeviceLock> lock;
    std::unique_ptr<IRtlDevice> device;

    std::mutex control;  // serialises channel changes against bring-up
    std::thread rx_thread;
    std::atomic<bool> running{false};
    int out_fd = -1;
    uint8_t channel = 0;
    ChannelWidth_t width = CHANNEL_WIDTH_20;

    std::atomic<int64_t> frames_seen{0};
    std::atomic<int64_t> frames_forwarded{0};
    std::atomic<int64_t> frames_dropped{0};
    std::atomic<int64_t> frames_corrupt{0};

    void on_packet(const Packet &packet) {
        frames_seen.fetch_add(1, std::memory_order_relaxed);
        if (packet.RxAtrib.crc_err || packet.RxAtrib.icv_err) {
            frames_corrupt.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        if (packet.Data.size() < kMinFrame + kFcsBytes) return;
        const uint8_t *frame = packet.Data.data();
        const size_t frame_len = packet.Data.size() - kFcsBytes;
        // Only frames whose transmitter address starts with the data-plane
        // marker cross the process boundary.
        if (frame[10] != 0x57 || frame[11] != 0x42) return;

        int antennas = 0;
        for (int i = 0; i < kMaxAntennas; i++) {
            if (packet.RxAtrib.rssi[i] != 0) antennas = i + 1;
        }
        if (antennas == 0) antennas = 1;

        const size_t body = 1 + 2 * static_cast<size_t>(antennas) + frame_len;
        uint8_t record[PIPE_BUF];
        if (4 + body > sizeof(record)) {
            frames_dropped.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        record[0] = static_cast<uint8_t>(body & 0xff);
        record[1] = static_cast<uint8_t>((body >> 8) & 0xff);
        record[2] = static_cast<uint8_t>((body >> 16) & 0xff);
        record[3] = static_cast<uint8_t>((body >> 24) & 0xff);
        record[4] = static_cast<uint8_t>(antennas);
        for (int i = 0; i < antennas; i++) {
            record[5 + i] = static_cast<uint8_t>(static_cast<int8_t>(packet.RxAtrib.rssi[i] - kRssiOffset));
            record[5 + antennas + i] = static_cast<uint8_t>(static_cast<int8_t>(packet.RxAtrib.snr[i] / 2));
        }
        memcpy(record + 5 + 2 * antennas, frame, frame_len);
        const ssize_t n = write(out_fd, record, 4 + body);
        if (n == static_cast<ssize_t>(4 + body)) {
            frames_forwarded.fetch_add(1, std::memory_order_relaxed);
        } else {
            frames_dropped.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void close_all() {
        if (device) device->StopRxLoop();
        if (rx_thread.joinable()) rx_thread.join();
        device.reset();
        if (handle != nullptr) {
            libusb_release_interface(handle, iface);
            libusb_close(handle);
            handle = nullptr;
        }
        if (ctx != nullptr) {
            libusb_exit(ctx);
            ctx = nullptr;
        }
        lock.reset();
        if (out_fd >= 0) {
            close(out_fd);
            out_fd = -1;
        }
    }
};

ChannelWidth_t width_for(int bandwidth_mhz) {
    switch (bandwidth_mhz) {
        case 5: return CHANNEL_WIDTH_5;
        case 10: return CHANNEL_WIDTH_10;
        case 40: return CHANNEL_WIDTH_40;
        case 80: return CHANNEL_WIDTH_80;
        default: return CHANNEL_WIDTH_20;
    }
}

void throw_state(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

jlong native_open(JNIEnv *env, jclass, jint fd, jstring lock_dir) {
    auto radio = std::make_unique<Radio>();
    radio->logger = std::make_shared<Logger>();
    radio->logger->set_level(Logger::Level::Info);
    radio->logger->events().disable();

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);
    int rc = libusb_init(&radio->ctx);
    if (rc < 0) {
        throw_state(env, std::string("USB init failed: ") + libusb_error_name(rc));
        return 0;
    }
    rc = libusb_wrap_sys_device(radio->ctx, static_cast<intptr_t>(fd), &radio->handle);
    if (rc < 0 || radio->handle == nullptr) {
        radio->close_all();
        throw_state(env, std::string("Radio not accessible: ") + libusb_error_name(rc));
        return 0;
    }
    const char *dir = env->GetStringUTFChars(lock_dir, nullptr);
    const std::string lock_path = dir != nullptr ? dir : "";
    if (dir != nullptr) env->ReleaseStringUTFChars(lock_dir, dir);

    radio->iface = devourer::find_wifi_interface(radio->handle);
    // A wrapped, app-owned descriptor cannot survive a USB reset (the device
    // would re-enumerate under a new descriptor), so the reset is skipped.
    rc = devourer::claim_interface_then_reset(radio->handle, radio->iface, radio->logger, false, radio->lock,
                                              lock_path);
    if (rc != 0) {
        libusb_close(radio->handle);
        radio->handle = nullptr;
        radio->close_all();
        throw_state(env, rc == LIBUSB_ERROR_BUSY ? "Radio is in use by another app"
                                                 : std::string("Radio claim failed: ") + libusb_error_name(rc));
        return 0;
    }
    try {
        WiFiDriver driver(radio->logger);
        radio->device = driver.CreateRtlDevice(radio->handle, radio->ctx, radio->lock);
    } catch (const std::exception &e) {
        radio->close_all();
        throw_state(env, std::string("Radio driver failed: ") + e.what());
        return 0;
    }
    if (!radio->device) {
        radio->close_all();
        throw_state(env, "This radio chip is not supported");
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "radio opened on interface %d", radio->iface);
    return reinterpret_cast<jlong>(radio.release());
}

jboolean native_start(JNIEnv *env, jclass, jlong ptr, jint channel, jint bandwidth_mhz, jint out_fd) {
    auto *radio = reinterpret_cast<Radio *>(ptr);
    if (radio == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(radio->control);
    if (radio->running.load()) return JNI_TRUE;
    if (radio->rx_thread.joinable()) radio->rx_thread.join();
    // The descriptor is owned from here and closed by nativeClose.
    if (radio->out_fd >= 0 && radio->out_fd != out_fd) close(radio->out_fd);
    radio->out_fd = out_fd;
    const int flags = fcntl(out_fd, F_GETFL);
    if (flags < 0 || fcntl(out_fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        throw_state(env, std::string("Frame pipe unusable: ") + strerror(errno));
        return JNI_FALSE;
    }
    radio->channel = static_cast<uint8_t>(channel);
    radio->width = width_for(bandwidth_mhz);
    try {
        // Bring-up and first tune on the calling thread (about two seconds),
        // so every later channel change happens on a chip that is up.
        radio->device->InitWrite(SelectedChannel{radio->channel, 0, radio->width});
    } catch (const std::exception &e) {
        throw_state(env, std::string("Radio bring-up failed: ") + e.what());
        return JNI_FALSE;
    }
    radio->running = true;
    radio->rx_thread = std::thread([radio]() {
        try {
            radio->device->StartRxLoop([radio](const Packet &packet) { radio->on_packet(packet); });
        } catch (const std::exception &e) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "receive loop ended: %s", e.what());
        }
        radio->running = false;
    });
    return JNI_TRUE;
}

jboolean native_set_channel(JNIEnv *, jclass, jlong ptr, jint channel, jint bandwidth_mhz) {
    auto *radio = reinterpret_cast<Radio *>(ptr);
    if (radio == nullptr || !radio->running.load()) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(radio->control);
    const ChannelWidth_t width = width_for(bandwidth_mhz);
    try {
        if (width == radio->width) {
            radio->device->FastRetune(static_cast<uint8_t>(channel));
        } else {
            radio->device->SetMonitorChannel(SelectedChannel{static_cast<uint8_t>(channel), 0, width});
            radio->width = width;
        }
        radio->channel = static_cast<uint8_t>(channel);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "retune to %d failed: %s", channel, e.what());
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "tuned to channel %d (%d MHz)", channel, bandwidth_mhz);
    return JNI_TRUE;
}

// [running, framesSeen, framesForwarded, framesDropped, framesCorrupt, channel]
jlongArray native_stats(JNIEnv *env, jclass, jlong ptr) {
    auto *radio = reinterpret_cast<Radio *>(ptr);
    jlong values[6] = {0, 0, 0, 0, 0, 0};
    if (radio != nullptr) {
        values[0] = radio->running.load() ? 1 : 0;
        values[1] = radio->frames_seen.load();
        values[2] = radio->frames_forwarded.load();
        values[3] = radio->frames_dropped.load();
        values[4] = radio->frames_corrupt.load();
        values[5] = radio->channel;
    }
    jlongArray out = env->NewLongArray(6);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 6, values);
    return out;
}

void native_close(JNIEnv *, jclass, jlong ptr) {
    auto *radio = reinterpret_cast<Radio *>(ptr);
    if (radio == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(radio->control);
        radio->close_all();
    }
    delete radio;
}

const JNINativeMethod kMethods[] = {
    {const_cast<char *>("nativeOpen"), const_cast<char *>("(ILjava/lang/String;)J"), reinterpret_cast<void *>(native_open)},
    {const_cast<char *>("nativeStart"), const_cast<char *>("(JIII)Z"), reinterpret_cast<void *>(native_start)},
    {const_cast<char *>("nativeSetChannel"), const_cast<char *>("(JII)Z"), reinterpret_cast<void *>(native_set_channel)},
    {const_cast<char *>("nativeStats"), const_cast<char *>("(J)[J"), reinterpret_cast<void *>(native_stats)},
    {const_cast<char *>("nativeClose"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_close)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass(kClass);
    if (cls == nullptr) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
