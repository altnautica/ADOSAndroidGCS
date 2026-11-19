# SPDX-License-Identifier: GPL-3.0-only
#
# Host-side tests of the bind tunnel: the wfb_tun framing against vectors
# captured from wfb-ng's own wfb_tun, and a TCP exchange through lwIP over
# that framing. Built and run by the :radio:rx hostNativeTest Gradle task.

cmake_minimum_required(VERSION 3.22.1)
project(ados_bindtun_host_test C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/bindtun.cmake)

add_executable(bind_tunnel_test bind_tunnel_test.cpp)
target_link_libraries(bind_tunnel_test PRIVATE bindtun_core)
target_compile_definitions(bind_tunnel_test PRIVATE
    GOLDEN_PATH="${CMAKE_CURRENT_SOURCE_DIR}/wfb_tun_golden.txt")

enable_testing()
add_test(NAME bind_tunnel_test COMMAND bind_tunnel_test)
