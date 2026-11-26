// The plugin's log: bridge.log in the data folder (rotated at kLogMaxBytes), a copy on a USB stick that has a file
// named drivelog-bridge.log, and slog on QNX. Safe from any thread. Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_LOG_HPP
#define DRIVELOG_BRIDGE_LOG_HPP

#include <stdarg.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>
#ifdef __QNX__
#include <sys/slog.h>
#include <sys/slogcodes.h>
#endif
#include "bridge_platform.hpp"

namespace {

const char* const kLogFile = BRIDGE_DATA_DIR "/bridge.log";
const char* const kLogOld  = BRIDGE_DATA_DIR "/bridge.old.log";
const char* const kUsbLogName = "drivelog-bridge.log";   // a file of this name on a USB stick gets a copy of the log
const long kLogMaxBytes = 512L * 1024L;
const long kUsbLogMaxBytes = 4L * 1024L * 1024L;
const long kUsbLogCheckMs = 5000;                         // a stick may come and go

pthread_mutex_t g_logMutex = PTHREAD_MUTEX_INITIALIZER;
char g_usbLog[320] = "";
long g_usbLogCheckedMs = -100000;

void appendTo(const char* path, const char* text, size_t n, int flags) {
    int fd = open(path, O_WRONLY | O_APPEND | flags, 0644);
    if (fd < 0) return;
    ssize_t w = write(fd, text, n);
    (void)w;
    close(fd);
}

// Under g_logMutex: the USB copy's path, looked up again now and then.
void refreshUsbLog(long now) {
    if (now - g_usbLogCheckedMs < kUsbLogCheckMs) return;
    g_usbLogCheckedMs = now;
    char p[320];
    struct stat st;
    g_usbLog[0] = 0;
    if (findUsbFile(kUsbLogName, p, sizeof(p)) && stat(p, &st) == 0 && (long)st.st_size < kUsbLogMaxBytes)
        snprintf(g_usbLog, sizeof(g_usbLog), "%s", p);
}

void blog(const char* fmt, ...) {
    char msg[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    long now = monoMs();
    char line[480];
    int n = snprintf(line, sizeof(line), "[%6ld.%03ld] [DrivelogBridge] %s\n", now / 1000, now % 1000, msg);
    if (n < 0) return;
    if (n >= (int)sizeof(line)) n = (int)sizeof(line) - 1;
    {
        ScopedLock lock(g_logMutex);
        refreshUsbLog(now);
        if (g_usbLog[0]) appendTo(g_usbLog, line, (size_t)n, 0);
        struct stat st;
        if (stat(kLogFile, &st) == 0 && (long)st.st_size > kLogMaxBytes) rename(kLogFile, kLogOld);
        appendTo(kLogFile, line, (size_t)n, O_CREAT);
    }
#ifdef __QNX__
    slogf(_SLOG_SETCODE(0x4e48, 2), _SLOG_INFO, "[DrivelogBridge] %s", msg);
#endif
}

} // namespace

#endif
