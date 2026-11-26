// Paths and small helpers over the operating system (QNX 6.5 or Linux): time, files, sockets, USB sticks, locking.
// No state of the plugin here. Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_PLATFORM_HPP
#define DRIVELOG_BRIDGE_PLATFORM_HPP

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef BRIDGE_ENTRYNAV2
#define BRIDGE_TARGET "EntryNav2"
#else
#define BRIDGE_TARGET "NBT Evo"
#endif
#define BRIDGE_VERSION "drivelog-bridge-0.2.0 " BRIDGE_TARGET   // raise with every change the phone may need (plugins/id6 BridgeVersion.MIN)

#ifndef BRIDGE_DATA_DIR
#ifdef BRIDGE_ENTRYNAV2
#define BRIDGE_DATA_DIR "/var/local/data/DrivelogBridge"
#else
#define BRIDGE_DATA_DIR "/var/opt/drivelogbridge"
#endif
#endif
#ifndef BRIDGE_PLUGIN_DIR
#ifdef BRIDGE_ENTRYNAV2
#define BRIDGE_PLUGIN_DIR "/opt/hmi/data/ro/Plugins/DrivelogBridge"
#else
#define BRIDGE_PLUGIN_DIR "/opt/hmi/ID5/data/ro/Plugins/DrivelogBridge"
#endif
#endif

namespace {

const char* const kDataDir   = BRIDGE_DATA_DIR;
const char* const kDisableA  = BRIDGE_DATA_DIR "/app.disable";
const char* const kDisableB  = BRIDGE_PLUGIN_DIR "/app.disable";
const char* const kConfFile  = BRIDGE_DATA_DIR "/bridge.conf";
const char* const kConfFile2 = BRIDGE_PLUGIN_DIR "/bridge.conf";

// Holds a mutex for the life of the object, so an early return cannot leave it locked.
class ScopedLock {
public:
    explicit ScopedLock(pthread_mutex_t& m) : m_mutex(m) { pthread_mutex_lock(&m_mutex); }
    ~ScopedLock() { pthread_mutex_unlock(&m_mutex); }
private:
    pthread_mutex_t& m_mutex;
    ScopedLock(const ScopedLock&);
    ScopedLock& operator=(const ScopedLock&);
};

long monoMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + (long)(ts.tv_nsec / 1000000);
}

bool fileExists(const char* p) {
    struct stat st;
    return stat(p, &st) == 0;
}

void setNonBlocking(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

void ipText(unsigned long ip, char* out, size_t n) {
    snprintf(out, n, "%lu.%lu.%lu.%lu", (ip >> 24) & 255, (ip >> 16) & 255, (ip >> 8) & 255, ip & 255);
}

// The whole file in a new buffer (free() it), or NULL when it is missing, empty or bigger than maxSize.
char* readSmallFile(const char* path, long maxSize) {
    struct stat st;
    if (stat(path, &st) != 0 || (long)st.st_size <= 0 || (long)st.st_size > maxSize) return NULL;
    char* buf = static_cast<char*>(malloc((size_t)st.st_size + 1));
    if (!buf) return NULL;
    FILE* f = fopen(path, "rb");
    size_t n = f ? fread(buf, 1, (size_t)st.st_size, f) : 0;
    if (f) fclose(f);
    buf[n] = 0;
    return buf;
}

// Reads a /proc file (its size shows as 0, so stat() does not tell how much there is).
bool readProcFile(const char* path, char* buf, size_t n) {
    FILE* f = fopen(path, "r");
    if (!f) return false;
    size_t got = fread(buf, 1, n - 1, f);
    fclose(f);
    buf[got] = 0;
    return got > 0;
}

// Where USB sticks appear: folders under [dir] whose name starts with [prefix].
struct UsbRoot { const char* dir; const char* prefix; };
#ifdef BRIDGE_ENTRYNAV2
const UsbRoot kUsbRoots[] = { { "/media", "" }, { "/mnt", "" }, { "/run/media", "" } };
#else
const UsbRoot kUsbRoots[] = { { "/fs", "usb" }, { "/mnt", "umass" } };
#endif

// The path of a file called [name] in the top folder of a USB stick, if one has it.
bool findUsbFile(const char* name, char* out, size_t n) {
    for (size_t r = 0; r < sizeof(kUsbRoots) / sizeof(kUsbRoots[0]); ++r) {
        DIR* d = opendir(kUsbRoots[r].dir);
        if (!d) continue;
        size_t plen = strlen(kUsbRoots[r].prefix);
        struct dirent* e;
        while ((e = readdir(d)) != NULL) {
            if (e->d_name[0] == '.' || strncmp(e->d_name, kUsbRoots[r].prefix, plen) != 0) continue;
            char p[320];
            snprintf(p, sizeof(p), "%s/%.200s/%s", kUsbRoots[r].dir, e->d_name, name);
            if (fileExists(p)) {
                snprintf(out, n, "%s", p);
                closedir(d);
                return true;
            }
        }
        closedir(d);
    }
    return false;
}

} // namespace

#endif
