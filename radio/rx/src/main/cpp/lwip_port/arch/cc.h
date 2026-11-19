// SPDX-License-Identifier: GPL-3.0-only
//
// lwIP compiler and platform glue for the bind tunnel (Android NDK and the
// host test build alike).

#ifndef ADOS_LWIP_ARCH_CC_H
#define ADOS_LWIP_ARCH_CC_H

#include <stdio.h>
#include <stdlib.h>

#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_NO_CTYPE_H 0

#define LWIP_PLATFORM_DIAG(x) do { printf x; } while (0)
#define LWIP_PLATFORM_ASSERT(x) do { fprintf(stderr, "lwip assert: %s\n", x); abort(); } while (0)

#endif
