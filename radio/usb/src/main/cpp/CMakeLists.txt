# SPDX-License-Identifier: GPL-2.0-or-later
#
# USB radio library for the isolated radio service process: libusb plus the
# vendored Realtek userspace driver (RTL8812AU family and RTL8812EU),
# wrapped by the JNI bridge in usbradio_shim.cpp.

cmake_minimum_required(VERSION 3.22.1)
project(ados_usbradio C CXX)

set(CMAKE_C_STANDARD 11)
set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)

add_compile_options(-ffunction-sections -fdata-sections)

# ------------------------------------------------------------------ libusb
set(LIBUSB_ROOT ${VENDOR}/libusb)
add_library(usb1 STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)
target_include_directories(usb1
    PUBLIC ${LIBUSB_ROOT}/libusb
    PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb/os)
target_compile_options(usb1 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb1 PUBLIC log)

# ------------------------------------------------------------------ driver
# Chip families built: Jaguar1 (RTL8812AU / 8811AU / 8821AU) and Jaguar3
# RTL8822E (RTL8812EU). Other families in the vendored tree are not built.
set(DVR ${VENDOR}/devourer)
add_library(devourer STATIC
    # generation-agnostic core
    ${DVR}/src/HalmacCfgParam.cpp
    ${DVR}/src/ParsedRadioPacket.cpp
    ${DVR}/src/Radiotap.c
    ${DVR}/src/RadiotapBuilder.cpp
    ${DVR}/src/RtlAdapter.cpp
    ${DVR}/src/UsbTransport.cpp
    ${DVR}/src/UsbDeviceLock.cpp
    ${DVR}/src/UsbOpen.cpp
    ${DVR}/src/TxMode.cpp
    ${DVR}/src/TxPower.cpp
    ${DVR}/src/LinkHealth.cpp
    ${DVR}/src/chanmig/ChannelScore.cpp
    ${DVR}/src/chanmig/MigGate.cpp
    ${DVR}/src/SignalStop.cpp
    ${DVR}/src/WiFiDriver.cpp
    ${DVR}/src/LaCapture.cpp
    ${DVR}/src/PhyTableLoader.cpp
    # Jaguar1
    ${DVR}/hal/hal8812a_fw.c
    ${DVR}/hal/hal8821a_fw.c
    ${DVR}/hal/Hal8812PwrSeq.c
    ${DVR}/hal/Hal8821APwrSeq.c
    ${DVR}/hal/Hal8812a_TxPwrTrack.cpp
    ${DVR}/src/jaguar1/RtlJaguarDevice.cpp
    ${DVR}/src/jaguar1/HalModule.cpp
    ${DVR}/src/jaguar1/RadioManagementModule.cpp
    ${DVR}/src/jaguar1/EepromManager.cpp
    ${DVR}/src/jaguar1/FirmwareManager.cpp
    ${DVR}/src/jaguar1/Iqk8812a.cpp
    ${DVR}/src/jaguar1/PhydmWatchdog.cpp
    ${DVR}/src/jaguar1/PowerTracking8812a.cpp
    ${DVR}/src/jaguar1/FrameParser.cpp
    ${DVR}/src/jaguar1/BbDbgportReader.cpp
    # Jaguar3 shared port (carries the per-variant dispatchers)
    ${DVR}/src/jaguar3/RtlJaguar3Device.cpp
    ${DVR}/src/jaguar3/HalJaguar3.cpp
    ${DVR}/src/jaguar3/HalmacJaguar3Fw.cpp
    ${DVR}/src/jaguar3/HalmacJaguar3MacInit.cpp
    ${DVR}/src/jaguar3/RadioManagementJaguar3.cpp
    ${DVR}/src/jaguar3/PhydmRuntimeJaguar3.cpp
    ${DVR}/src/jaguar3/PhyTableLoaderJaguar3.cpp
    ${DVR}/src/jaguar3/Phy8822cTables.cpp
    ${DVR}/src/jaguar3/Halrf8822c.cpp
    # Jaguar3 RTL8822E variant
    ${DVR}/hal/hal8822e_fw.c
    ${DVR}/hal/phydm/rtl8822e/Hal8822e_PhyTables.c
    ${DVR}/src/jaguar3/Phy8822eTables.cpp
    ${DVR}/src/jaguar3/Halrf8822e.cpp)
target_compile_features(devourer PUBLIC cxx_std_20)
target_compile_definitions(devourer PUBLIC
    DEVOURER_HAVE_JAGUAR1=1
    DEVOURER_HAVE_JAGUAR3=1
    DEVOURER_HAVE_JAGUAR3_8822E=1
    DEVOURER_LOG_MAX_LEVEL=DEVOURER_LL_INFO)
target_include_directories(devourer PUBLIC
    ${DVR}/hal
    ${DVR}/hal/phydm/rtl8822e
    ${DVR}/src)
target_compile_options(devourer PRIVATE -Wno-unused-function -Wno-unused-variable -Wno-unused-private-field)
target_link_libraries(devourer PUBLIC usb1 log)

# ------------------------------------------------------------------ JNI library
add_library(ados_usbradio SHARED usbradio_shim.cpp)
target_compile_features(ados_usbradio PRIVATE cxx_std_20)
target_link_libraries(ados_usbradio PRIVATE devourer usb1 android log)
target_link_options(ados_usbradio PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
