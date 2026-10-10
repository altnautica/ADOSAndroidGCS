# SPDX-License-Identifier: GPL-3.0-only
#
# Phone-radio receive library: libsodium + the vendored WFB receive core,
# wrapped by the JNI bridge in wfb_receiver_jni.cpp.

cmake_minimum_required(VERSION 3.22.1)
project(ados_wfbrx C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)

# Unreferenced code (the vendored command-line capture class) is dropped at
# link time; see compat/pcap.h.
add_compile_options(-ffunction-sections -fdata-sections)

# ------------------------------------------------------------------ libsodium
set(SODIUM_ROOT ${VENDOR}/libsodium/src/libsodium)
set(SODIUM_GEN ${CMAKE_CURRENT_BINARY_DIR}/sodium-gen)
set(VERSION 1.0.22)
set(SODIUM_LIBRARY_VERSION_MAJOR 26)
set(SODIUM_LIBRARY_VERSION_MINOR 4)
set(SODIUM_LIBRARY_MINIMAL_DEF "")
configure_file(${SODIUM_ROOT}/include/sodium/version.h.in ${SODIUM_GEN}/sodium/version.h @ONLY)

file(GLOB_RECURSE SODIUM_SOURCES CONFIGURE_DEPENDS ${SODIUM_ROOT}/*.c)
add_library(sodium STATIC ${SODIUM_SOURCES})
# The generated version.h includes "export.h" from include/sodium, so that
# directory is public too.
target_include_directories(sodium
    PUBLIC ${SODIUM_GEN} ${SODIUM_ROOT}/include ${SODIUM_ROOT}/include/sodium
    PRIVATE ${SODIUM_GEN}/sodium)
target_compile_definitions(sodium
    PUBLIC SODIUM_STATIC=1
    PRIVATE
        _GNU_SOURCE=1 CONFIGURED=1
        NATIVE_LITTLE_ENDIAN=1
        HAVE_ATOMIC_OPS=1 HAVE_C11_MEMORY_FENCES=1 HAVE_GCC_MEMORY_FENCES=1
        HAVE_INLINE_ASM=1 HAVE_INTTYPES_H=1 HAVE_STDINT_H=1
        HAVE_CLOCK_GETTIME=1 HAVE_GETPID=1 HAVE_MADVISE=1 HAVE_MLOCK=1 HAVE_MMAP=1
        HAVE_MPROTECT=1 HAVE_NANOSLEEP=1 HAVE_POSIX_MEMALIGN=1 HAVE_PTHREAD=1
        HAVE_PTHREAD_PRIO_INHERIT=1 HAVE_RAISE=1 HAVE_SYSCONF=1 HAVE_SYS_AUXV_H=1
        HAVE_SYS_MMAN_H=1 HAVE_SYS_PARAM_H=1 HAVE_SYS_RANDOM_H=1 HAVE_WEAK_SYMBOLS=1
        TLS=_Thread_local)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(sodium PRIVATE HAVE_TI_MODE=1 HAVE_ARMCRYPTO=1)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(sodium PRIVATE
        HAVE_TI_MODE=1 HAVE_CPUID=1 HAVE_MMINTRIN_H=1 HAVE_EMMINTRIN_H=1 HAVE_PMMINTRIN_H=1
        HAVE_TMMINTRIN_H=1 HAVE_SMMINTRIN_H=1 HAVE_AVXINTRIN_H=1 HAVE_AVX2INTRIN_H=1
        HAVE_AVX512FINTRIN_H=1 HAVE_WMMINTRIN_H=1 HAVE_RDRAND=1)
endif()
target_compile_options(sodium PRIVATE
    -fvisibility=hidden -fno-strict-aliasing -fno-strict-overflow -fwrapv
    -Wno-unused-function -Wno-unknown-pragmas)

# ------------------------------------------------------------------ WFB receive core
set(WFB_SRC ${VENDOR}/wfb-ng/src)
add_library(wfbrx_core STATIC
    ${WFB_SRC}/rx.cpp
    ${WFB_SRC}/wifibroadcast.cpp
    ${WFB_SRC}/zfex.c
    ${WFB_SRC}/radiotap.c)
target_include_directories(wfbrx_core
    PUBLIC ${WFB_SRC} ${CMAKE_CURRENT_SOURCE_DIR}/compat)
target_compile_definitions(wfbrx_core
    PUBLIC
        __WFB_RX_SHARED_LIBRARY__
        PREINCLUDE_FILE=<${CMAKE_CURRENT_SOURCE_DIR}/wfb_log.h>
    PRIVATE
        ZFEX_UNROLL_ADDMUL_SIMD=8
        ZFEX_USE_INTEL_SSSE3
        ZFEX_USE_ARM_NEON
        ZFEX_INLINE_ADDMUL
        ZFEX_INLINE_ADDMUL_SIMD)
target_link_libraries(wfbrx_core PUBLIC sodium log)

# ------------------------------------------------------------------ JNI library
add_library(ados_wfbrx SHARED wfb_receiver_jni.cpp)
target_link_libraries(ados_wfbrx PRIVATE wfbrx_core sodium android log)
target_link_options(ados_wfbrx PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
