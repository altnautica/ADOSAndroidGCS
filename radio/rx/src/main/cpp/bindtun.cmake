# SPDX-License-Identifier: GPL-3.0-only
#
# The bind tunnel's IP endpoint: the vendored lwIP (IPv4 and TCP only, see
# lwip_port/lwipopts.h) and the wfb_tun framing around it. Shared by the
# Android build and the host-side unit test build.

set(BINDTUN_DIR ${CMAKE_CURRENT_LIST_DIR})
set(LWIP_ROOT ${BINDTUN_DIR}/vendor/lwip/src)

file(GLOB LWIP_SOURCES CONFIGURE_DEPENDS ${LWIP_ROOT}/core/*.c ${LWIP_ROOT}/core/ipv4/*.c)
add_library(lwip STATIC ${LWIP_SOURCES})
target_include_directories(lwip PUBLIC ${BINDTUN_DIR}/lwip_port ${LWIP_ROOT}/include)
target_compile_options(lwip PRIVATE -Wno-unused-parameter -Wno-address)

add_library(bindtun_core STATIC
    ${BINDTUN_DIR}/bind_tun_framing.cpp
    ${BINDTUN_DIR}/bind_tunnel_core.cpp)
target_include_directories(bindtun_core PUBLIC ${BINDTUN_DIR})
target_link_libraries(bindtun_core PUBLIC lwip)
