// The crash guard. A crash inside the plugin takes the whole HMI down (screen, radio, navigation), and the HMI
// restarts and loads the plugin again. Every create() adds a line to app-guard; after kStableAfterMs of normal work
// the file is removed. kCrashGuardMax lines = that many HMI restarts in a row right after loading: the plugin stays
// off. Used from the HMI thread only. Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_GUARD_HPP
#define DRIVELOG_BRIDGE_GUARD_HPP

#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include "bridge_log.hpp"

namespace {

const char* const kGuardFile = BRIDGE_DATA_DIR "/app-guard";
const int  kCrashGuardMax = 3;          // HMI restarts right after loading this app before it turns itself off
const long kStableAfterMs = 120000L;    // ... "right after" = within this time

class CrashGuard {
public:
    CrashGuard() : m_startMs(-1), m_stable(false) {}

    // At create(): false (and logged) when the plugin must stay off; otherwise this start is counted.
    bool allowsStart() {
        m_startMs = -1;
        m_stable = false;
        int lines = 0;
        FILE* f = fopen(kGuardFile, "r");
        if (f) {
            char b[64];
            while (fgets(b, sizeof(b), f)) ++lines;
            fclose(f);
        }
        if (lines >= kCrashGuardMax) {
            blog("crash guard: the HMI restarted %d times right after loading this plugin, so it stays off. "
                 "Read %s, then delete %s and restart the head unit.", lines, kLogFile, kGuardFile);
            return false;
        }
        f = fopen(kGuardFile, "a");
        if (f) {
            fprintf(f, "%ld\n", (long)time(NULL));
            fflush(f);
            fsync(fileno(f));
            fclose(f);
        }
        return true;
    }

    // The plugin's onInit has run: the time it must keep working from.
    void started(long nowMs) { m_startMs = nowMs; }

    // Every tick: once it has worked long enough, the start no longer counts as a crash.
    void tick(long nowMs) {
        if (m_stable || m_startMs < 0 || nowMs - m_startMs < kStableAfterMs) return;
        m_stable = true;
        unlink(kGuardFile);
        blog("running for %ld s: crash guard reset", kStableAfterMs / 1000);
    }

private:
    long m_startMs;
    bool m_stable;
};

} // namespace

#endif
