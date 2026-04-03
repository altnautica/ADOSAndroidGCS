
cmake_minimum_required(VERSION 3.22.1)

project("ados_wfb")

# ==============================================================================
# Include directories
# ==============================================================================

# libusb, sodium, pcap headers (from wfbngrtl8812)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# opus headers (from videonative)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/libs/include)

# ==============================================================================
# Static library: wfb-ng (WFB-ng FEC + radiotap + RX protocol)
# ==============================================================================

add_library(wfb-ng STATIC
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/zfex.c
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/zfex.h
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/ieee80211_radiotap.h
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/radiotap.c
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/rx.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/rx.hpp
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/wifibroadcast.hpp
        ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng/src/wifibroadcast.cpp)

set_target_properties(wfb-ng PROPERTIES INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng)
target_include_directories(wfb-ng PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/wfb-ng)
target_compile_definitions(wfb-ng PRIVATE
        __WFB_RX_SHARED_LIBRARY__
        PREINCLUDE_FILE=<${CMAKE_CURRENT_SOURCE_DIR}/wfb_log.h>
        ZFEX_UNROLL_ADDMUL_SIMD=8
        ZFEX_USE_INTEL_SSSE3
        ZFEX_USE_ARM_NEON
        ZFEX_INLINE_ADDMUL
        ZFEX_INLINE_ADDMUL_SIMD
)

# ==============================================================================
# Static library: devourer (RTL8812AU userspace USB driver)
# ==============================================================================

add_library(devourer STATIC
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/Hal8812PhyReg.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/Hal8812PwrSeq.c
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/Hal8812PwrSeq.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/basic_types.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/hal8812a_fw.c
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/hal8812a_fw.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/hal_com_reg.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/rtl8812a_hal.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/rtl8812a_recv.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal/rtl8812a_spec.h

        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/Radiotap.c
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/registry_priv.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/ieee80211_radiotap.h

        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/EepromManager.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/EepromManager.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/Firmware.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/FirmwareManager.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/FirmwareManager.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/FrameParser.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/FrameParser.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/HalModule.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/HalModule.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/ParsedRadioPacket.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/RadioManagementModule.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/RadioManagementModule.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/Rtl8812aDevice.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/Rtl8812aDevice.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/RtlUsbAdapter.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/RtlUsbAdapter.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/SelectedChannel.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/WiFiDriver.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/WiFiDriver.h
        ${CMAKE_CURRENT_SOURCE_DIR}/devourer/src/registry_priv.h)

set_target_properties(devourer PROPERTIES INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/devourer)
target_include_directories(devourer PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/devourer)
target_include_directories(devourer PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/devourer/hal)
set_property(TARGET devourer PROPERTY CXX_STANDARD 20)

# ==============================================================================
# Shared library: ados_wfb (WFB link layer + video decoder, single JNI library)
# ==============================================================================

add_library(${CMAKE_PROJECT_NAME} SHARED
        # WFB link layer (from wfbngrtl8812)
        RxFrame.cpp
        RxFrame.h
        WfbngLink.cpp
        WfbngLink.hpp
        TxFrame.cpp
        TxFrame.h
        SignalQualityCalculator.cpp
        SignalQualityCalculator.h
        FecChangeController.h

        # Video decoder (from videonative)
        parser/H26XParser.cpp
        parser/ParseRTP.cpp
        AudioDecoder.cpp
        UdpReceiver.cpp
        UdsReceiver.cpp
        VideoDecoder.cpp
        VideoPlayer.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # Static libs
        devourer
        wfb-ng

        # Android system libs
        android
        log
        mediandk
        aaudio

        # Prebuilt shared/static libs
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb1.0.so
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libsodium.so
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libpcap.a
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libopus.so
)

set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)
