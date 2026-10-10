// SPDX-License-Identifier: GPL-3.0-only
//
// Pre-included ahead of the vendored receive sources: routes their
// diagnostics to logcat and silences the stdout IPC stream the
// command-line receiver uses.

#ifndef ADOS_WFB_LOG_H
#define ADOS_WFB_LOG_H

#include <android/log.h>

#define ADOS_WFB_TAG "AdosWfbRx"

#define WFB_ERR(...) __android_log_print(ANDROID_LOG_WARN, ADOS_WFB_TAG, __VA_ARGS__)
#define WFB_INFO(...) __android_log_print(ANDROID_LOG_INFO, ADOS_WFB_TAG, __VA_ARGS__)
#define WFB_DBG(...) ((void)0)
#define IPC_MSG(...) ((void)0)
#define IPC_MSG_SEND() ((void)0)

#endif // ADOS_WFB_LOG_H
