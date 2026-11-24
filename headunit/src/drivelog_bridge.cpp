// libDrivelogBridge.so - iDrive PluginApp that hands the car values Drivelog needs to the phone.
//
// It subscribes to the 15 CDS properties of bridge_core.hpp and serves them on a TCP port of the car's Wi-Fi:
// one JSON object per line (hello, snapshot, cds, ping; headunit/README.md). The phone connects; the plugin never
// looks for it. Read only: nothing is ever written to CDS.
//
//   NBT Evo (default)                 QNX 6.5,  /opt/hmi/ID5/data/ro/Plugins/DrivelogBridge, data /var/opt/drivelogbridge
//   EntryNav2 (-DBRIDGE_ENTRYNAV2)    Linux ARMv7 hard-float, /opt/hmi/data/ro/Plugins/DrivelogBridge,
//                                     data /var/local/data/DrivelogBridge
//
// Threads: CDS callbacks and the screen run in the HMI's app thread (Runnables posted to the PluginApp); the
// network has its own thread. They share only g_last / the event queue, under g_mutex. A slow or dead phone can
// never block the HMI: sockets are non-blocking and a phone that does not read is dropped.
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <new>
#include <dirent.h>
#include <dlfcn.h>
#include <poll.h>
#include <pthread.h>
#include <ifaddrs.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#ifdef __QNX__
#include <sys/slog.h>
#include <sys/slogcodes.h>
#endif
#include "bridge_core.hpp"

#ifndef RTLD_DEFAULT
#define RTLD_DEFAULT ((void*)-2)   // QNX value
#endif

#ifdef BRIDGE_ENTRYNAV2
#define BRIDGE_TARGET "EntryNav2"
#else
#define BRIDGE_TARGET "NBT Evo"
#endif
#define BRIDGE_VERSION "drivelog-bridge-0.1.0 " BRIDGE_TARGET

// ---- the iDrive app framework (libAppFramework.A.so), declared by hand ----------------------------------------------
// The framework has no public headers. These declarations only have to produce the same mangled names as the
// exports of both head units' libAppFramework.A.so (so the namespace must keep the framework's own name); the
// layouts are the ones GaugeApp and CdsScan use on the car.
namespace bmw {
class IDispatcher;
class Bundle;
class Runnable;
class Parcel;
enum RHMIInterfaceVersion { RHMI_INTERFACE_VERSION_1 = 1 };

template <typename T> class BasicString {
public:
    BasicString(const T* s);
    BasicString(const BasicString& other);
    ~BasicString();
    const T* raw() const { return m_data; }
private:
    T* m_data;
};

class TextID {
public:
    explicit TextID(unsigned id);
private:
    unsigned m_id, m_type;
};

template <typename R, typename A1, typename A2, typename A3, typename A4> struct Delegate {
    void* object;
    R (*stub)(void* object, A1 a1);
};
typedef Delegate<bool, const BasicString<unsigned short>&, void, void, void> CdsDelegate;
typedef Delegate<bool, const Parcel&, void, void, void> ActionDelegate;

const size_t kPluginAppStorage = 0x200;      // the real object is smaller; EntryNav2 writes up to +0x174

class PluginApp {
public:
    PluginApp(IDispatcher& dispatcher, BasicString<char> name, unsigned major, unsigned minor,
              RHMIInterfaceVersion rhmi, const Bundle& params, const Bundle& config);
    void post(Runnable* runnable, unsigned delayMs);
    void updateModel(unsigned modelId, const TextID& text);
    void addHandler(unsigned actionId, const ActionDelegate& handler);
    void cdsAddPropertyChangedEventHandler(BasicString<char>& property, unsigned id, const CdsDelegate& handler);
    bool onInit();
    void onFinalize();
private:
    unsigned char m_storage[kPluginAppStorage];
};
} // namespace bmw

namespace {
using bridge::kPropertyCount;
using bridge::kProperties;

// ---- paths and constants ------------------------------------------------------------------------------------------
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
const char* const kDataDir   = BRIDGE_DATA_DIR;
const char* const kDisableA  = BRIDGE_DATA_DIR "/app.disable";
const char* const kDisableB  = BRIDGE_PLUGIN_DIR "/app.disable";
const char* const kConfFile  = BRIDGE_DATA_DIR "/bridge.conf";
const char* const kConfFile2 = BRIDGE_PLUGIN_DIR "/bridge.conf";
const char* const kLogFile   = BRIDGE_DATA_DIR "/bridge.log";
const char* const kLogOld    = BRIDGE_DATA_DIR "/bridge.old.log";
const char* const kGuardFile = BRIDGE_DATA_DIR "/app-guard";
const char* const kUsbLogName = "drivelog-bridge.log";   // a file of this name on a USB stick gets a copy of the log
const long kLogMaxBytes = 512L * 1024L;
const long kUsbLogMaxBytes = 4L * 1024L * 1024L;
const int  kCrashGuardMax = 3;          // HMI restarts right after loading this app before it turns itself off
const long kStableAfterMs = 120000L;    // ... "right after" = within this time

const unsigned kSubscribeDelayMs = 1500;    // after onInit, as the plugins that work on the car do
const unsigned kTickMs = 1000;              // screen texts, crash guard
const long kPingAfterMs = 5000;             // a phone that got nothing for this long gets a ping
const long kAddressCheckMs = 3000;          // how often the Wi-Fi address is looked up again
const size_t kClientBufMax = 256 * 1024;    // a phone with more than this waiting to be sent is dropped
enum { kMaxClients = 4, kQueueSize = 256 };

enum { kSlotOnError = 21, kSlotOnInit = 22, kSlotOnFinalize = 23, kVtblSlots = 34 };   // PluginApp vtable

// The screen (DrivelogBridge.xml): three lines, each a textIdModel switched between texts of the text database.
enum { kModelState = 41, kModelPhones = 42, kModelValues = 43, kActionFirst = 31, kLines = 3 };
enum { kTextState = 1000, kTextPhones = 2000, kTextValues = 3000 };
enum State { kStateStarting = 0, kStateNoWifi = 1, kStateListening = 2, kStateConnected = 3, kStatePortBusy = 4,
             kStateStopped = 5 };

// ---- USB sticks and the log --------------------------------------------------------------------------------------
struct UsbRoot { const char* dir; const char* prefix; };
#ifdef BRIDGE_ENTRYNAV2
const UsbRoot kUsbRoots[] = { { "/media", "" }, { "/mnt", "" }, { "/run/media", "" } };
#else
const UsbRoot kUsbRoots[] = { { "/fs", "usb" }, { "/mnt", "umass" } };
#endif

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
            struct stat st;
            if (stat(p, &st) == 0) {
                snprintf(out, n, "%s", p);
                closedir(d);
                return true;
            }
        }
        closedir(d);
    }
    return false;
}

long monoMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + (long)(ts.tv_nsec / 1000000);
}

bool fileExists(const char* p) {
    struct stat st;
    return stat(p, &st) == 0;
}

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
    pthread_mutex_lock(&g_logMutex);
    if (now - g_usbLogCheckedMs >= 5000) {          // a stick may come and go
        g_usbLogCheckedMs = now;
        char p[320];
        struct stat st;
        g_usbLog[0] = 0;
        if (findUsbFile(kUsbLogName, p, sizeof(p)) && stat(p, &st) == 0 && (long)st.st_size < kUsbLogMaxBytes)
            snprintf(g_usbLog, sizeof(g_usbLog), "%s", p);
    }
    if (g_usbLog[0]) appendTo(g_usbLog, line, (size_t)n, 0);
    struct stat st;
    if (stat(kLogFile, &st) == 0 && (long)st.st_size > kLogMaxBytes) rename(kLogFile, kLogOld);
    appendTo(kLogFile, line, (size_t)n, O_CREAT);
    pthread_mutex_unlock(&g_logMutex);
#ifdef __QNX__
    slogf(_SLOG_SETCODE(0x4e48, 2), _SLOG_INFO, "[DrivelogBridge] %s", msg);
#endif
}

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

// ---- shared state (g_mutex) ---------------------------------------------------------------------------------------
struct Event { int prop; long t; char value[bridge::kValueBytes]; };

pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
char  g_last[kPropertyCount][bridge::kValueBytes];   // latest value of each property
bool  g_has[kPropertyCount];
int   g_valueCount = 0;                               // properties with a value
Event g_queue[kQueueSize];                            // changes the network thread has not sent yet
int   g_qHead = 0, g_qLen = 0;
bool  g_qOverflow = false;                            // events were lost: every phone gets a new snapshot
int   g_state = kStateStarting;                       // written by the network thread, shown on the screen
int   g_phones = 0;

bridge::Config g_conf;
bmw::PluginApp* g_app = NULL;
long g_initMs = -1;

// ---- crash guard ----------------------------------------------------------------------------------------------------
// A crash inside the plugin takes the whole HMI down (screen, radio, navigation), and the HMI restarts and loads the
// plugin again. Every create() adds a line to app-guard; after kStableAfterMs of normal work the file is removed.
// kCrashGuardMax lines = that many HMI restarts in a row right after loading: the plugin stays off.
bool g_stableMarked = false;

bool crashGuardAllows() {
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

void markStableWhenDue() {
    if (g_stableMarked || g_initMs < 0 || monoMs() - g_initMs < kStableAfterMs) return;
    g_stableMarked = true;
    unlink(kGuardFile);
    blog("running for %ld s: crash guard reset", kStableAfterMs / 1000);
}

// ---- network thread -----------------------------------------------------------------------------------------------
struct Client {
    int fd;
    char* out;          // bytes waiting to be sent
    size_t len, cap;
    long lastSentMs;
    char peer[48];
};

Client g_clients[kMaxClients];
int g_listenFd = -1;
char g_listenAddr[48] = "";
char g_listenIface[32] = "";          // interface of g_listenAddr (empty: bridge.conf address=)
unsigned long g_listenMask = 0;       // its netmask, host order
int g_wake[2] = { -1, -1 };          // self-pipe: a CDS value or the stop request wakes poll()
volatile bool g_stop = false;
pthread_t g_thread;
bool g_threadRunning = false;

void setNonBlocking(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

void closeClient(Client& c, const char* why) {
    if (c.fd < 0) return;
    blog("phone %s disconnected: %s", c.peer, why);
    close(c.fd);
    c.fd = -1;
    free(c.out);
    c.out = NULL;
    c.len = c.cap = 0;
}

int clientCount() {
    int n = 0;
    for (int i = 0; i < kMaxClients; ++i) if (g_clients[i].fd >= 0) ++n;
    return n;
}

bool queueBytes(Client& c, const char* data, size_t n) {
    if (c.len + n > kClientBufMax) { closeClient(c, "not reading (send buffer full)"); return false; }
    if (c.len + n > c.cap) {
        size_t cap = c.cap ? c.cap : 4096;
        while (cap < c.len + n) cap *= 2;
        char* p = static_cast<char*>(realloc(c.out, cap));
        if (!p) { closeClient(c, "out of memory"); return false; }
        c.out = p;
        c.cap = cap;
    }
    memcpy(c.out + c.len, data, n);
    c.len += n;
    return true;
}

void flushClient(Client& c, long now) {
    while (c.fd >= 0 && c.len > 0) {
#ifdef MSG_NOSIGNAL
        ssize_t w = send(c.fd, c.out, c.len, MSG_NOSIGNAL);
#else
        ssize_t w = send(c.fd, c.out, c.len, 0);       // SIGPIPE is blocked in this thread (networkMain)
#endif
        if (w > 0) {
            memmove(c.out, c.out + w, c.len - (size_t)w);
            c.len -= (size_t)w;
            c.lastSentMs = now;
        } else if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            return;
        } else {
            closeClient(c, w == 0 ? "closed" : strerror(errno));
        }
    }
}

// A big enough buffer for a snapshot of every property.
char g_lineBuf[kPropertyCount * (bridge::kValueBytes + 48) + 256];

void sendSnapshot(Client& c, long now) {
    const char* values[kPropertyCount];
    static char copy[kPropertyCount][bridge::kValueBytes];
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < kPropertyCount; ++i) {
        if (g_has[i]) { memcpy(copy[i], g_last[i], sizeof(copy[i])); values[i] = copy[i]; }
        else values[i] = NULL;
    }
    pthread_mutex_unlock(&g_mutex);
    int n = bridge::snapshotLine(g_lineBuf, sizeof(g_lineBuf), values, now);
    if (n > 0) queueBytes(c, g_lineBuf, (size_t)n);
}

// The address to listen on: bridge.conf address=, else the IPv4 address of interface= or of the best Wi-Fi-looking
// interface (bridge::interfaceScore). Empty: none (the car's Wi-Fi is off).
void findListenAddress(char* out, size_t n, char* iface, size_t ifaceSize, unsigned long* mask) {
    out[0] = 0;
    iface[0] = 0;
    *mask = 0;
    if (g_conf.address[0]) { snprintf(out, n, "%s", g_conf.address); return; }
    struct ifaddrs* list = NULL;
    if (getifaddrs(&list) != 0) return;
    int best = 0;
    for (struct ifaddrs* a = list; a; a = a->ifa_next) {
        if (!a->ifa_addr || a->ifa_addr->sa_family != AF_INET || !a->ifa_name) continue;
        const struct sockaddr_in* sin = reinterpret_cast<const struct sockaddr_in*>(a->ifa_addr);
        unsigned long host = ntohl(sin->sin_addr.s_addr);
        int score;
        if (g_conf.iface[0]) score = strcmp(a->ifa_name, g_conf.iface) == 0 && host != 0 ? 10 : 0;
        else score = bridge::interfaceScore(a->ifa_name, host);
        if (score > best) {
            best = score;
            inet_ntop(AF_INET, &sin->sin_addr, out, (socklen_t)n);
            snprintf(iface, ifaceSize, "%s", a->ifa_name);
            *mask = a->ifa_netmask ? ntohl(reinterpret_cast<const struct sockaddr_in*>(a->ifa_netmask)->sin_addr.s_addr) : 0;
        }
    }
    freeifaddrs(list);
}

void closeProbes();

void closeListen(const char* why) {
    closeProbes();
    for (int i = 0; i < kMaxClients; ++i) closeClient(g_clients[i], why);
    if (g_listenFd >= 0) {
        close(g_listenFd);
        blog("stopped listening on %s:%u (%s)", g_listenAddr, (unsigned)g_conf.port, why);
    }
    g_listenFd = -1;
    g_listenAddr[0] = 0;
}

// Returns the new state.
int openListen(const char* addr) {
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons(g_conf.port);
    if (inet_pton(AF_INET, addr, &sin.sin_addr) != 1) { blog("bad address '%s' in bridge.conf", addr); return kStateNoWifi; }
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { blog("socket: %s", strerror(errno)); return kStatePortBusy; }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind(fd, reinterpret_cast<struct sockaddr*>(&sin), sizeof(sin)) != 0 || listen(fd, 4) != 0) {
        blog("cannot listen on %s:%u: %s", addr, (unsigned)g_conf.port, strerror(errno));
        close(fd);
        return kStatePortBusy;
    }
    setNonBlocking(fd);
    g_listenFd = fd;
    snprintf(g_listenAddr, sizeof(g_listenAddr), "%s", addr);
    blog("listening on %s:%u", addr, (unsigned)g_conf.port);
    return kStateListening;
}

// A connected phone, whichever side opened the connection ([how] for the log): hello and snapshot go out first.
void addClient(int fd, const char* name, const char* how, long now) {
    int slot = -1;
    for (int i = 0; i < g_conf.maxClients && i < kMaxClients; ++i) if (g_clients[i].fd < 0) { slot = i; break; }
    if (slot < 0) {
        const char busy[] = "{\"type\":\"error\",\"msg\":\"too many phones\"}\n";
        ssize_t w = send(fd, busy, sizeof(busy) - 1, 0);
        (void)w;
        close(fd);
        blog("phone %s refused: already %d connected", name, clientCount());
        return;
    }
    setNonBlocking(fd);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    Client& c = g_clients[slot];
    c.fd = fd;
    c.out = NULL;
    c.len = c.cap = 0;
    c.lastSentMs = now;
    snprintf(c.peer, sizeof(c.peer), "%s", name);
    blog("phone %s connected (%s)", name, how);
    char hello[256];
    int n = bridge::helloLine(hello, sizeof(hello), BRIDGE_VERSION, now);
    if (n > 0 && queueBytes(c, hello, (size_t)n)) sendSnapshot(c, now);
}

void acceptClient(long now) {
    struct sockaddr_in peer;
    socklen_t plen = sizeof(peer);
    int fd = accept(g_listenFd, reinterpret_cast<struct sockaddr*>(&peer), &plen);
    if (fd < 0) return;
    char name[48] = "?";
    inet_ntop(AF_INET, &peer.sin_addr, name, sizeof(name));
    addClient(fd, name, "it called", now);
}

// ---- calling the phones -------------------------------------------------------------------------------------------
// While no phone is connected the plugin itself connects to port phonePort of the phones on its Wi-Fi (see
// bridge_core.hpp, "finding phones"): a few at a time, non-blocking, each given kProbeTimeoutMs; a full round at most
// every kCallEveryMs. The first one that answers becomes a client like one that called in.
enum { kMaxProbes = 24, kMaxCandidates = 254 };
const long kProbeTimeoutMs = 1500;
const long kCallEveryMs = 5000;

struct Probe { int fd; unsigned long ip; long startMs; };
Probe g_probes[kMaxProbes];
unsigned long g_cand[kMaxCandidates];
int g_candCount = 0, g_candNext = 0;
long g_roundDoneMs = -kCallEveryMs;
int g_lastCandCount = -1;
const char* g_lastCandSource = "";
bool g_roundFromSubnet = false;

// The last outcome of calling each address, so the log says once (not every 5 s) why a phone did not answer.
// err: -1 = no answer within kProbeTimeoutMs, otherwise an errno. Forgotten when a phone connects.
struct CallResult { unsigned long ip; int err; };
CallResult g_results[kMaxCandidates];
int g_resultCount = 0;

void ipText(unsigned long ip, char* out, size_t n) {
    snprintf(out, n, "%lu.%lu.%lu.%lu", (ip >> 24) & 255, (ip >> 16) & 255, (ip >> 8) & 255, ip & 255);
}

void noteCallResult(unsigned long ip, int err) {
    if (g_roundFromSubnet && err == -1) return;    // most of a subnet never answers: not worth a line each
    int i = 0;
    while (i < g_resultCount && g_results[i].ip != ip) ++i;
    if (i == g_resultCount) {
        if (g_resultCount == kMaxCandidates) return;
        g_results[i].ip = ip;
        g_results[i].err = 0;
        ++g_resultCount;
    }
    if (g_results[i].err == err) return;
    g_results[i].err = err;
    char name[24];
    ipText(ip, name, sizeof(name));
    if (err == -1) blog("calling %s:%u: no answer within %ld ms (dropped on the way, or the phone ignores it)",
                        name, (unsigned)g_conf.phonePort, kProbeTimeoutMs);
    else blog("calling %s:%u: %s", name, (unsigned)g_conf.phonePort, strerror(err));
}

int probeCount() {
    int n = 0;
    for (int i = 0; i < kMaxProbes; ++i) if (g_probes[i].fd >= 0) ++n;
    return n;
}

void closeProbes() {
    for (int i = 0; i < kMaxProbes; ++i) if (g_probes[i].fd >= 0) { close(g_probes[i].fd); g_probes[i].fd = -1; }
    g_candCount = g_candNext = 0;
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

// Who to call this round: phoneAddress=, else the phones in the ARP table of the Wi-Fi interface (Linux), else every
// address of its /24. Nothing when the plugin listens on an address from bridge.conf (no interface to look at).
void startCallRound(long now) {
    g_candCount = g_candNext = 0;
    unsigned long own = bridge::parseIPv4(g_listenAddr);
    const char* source = "";
    g_roundFromSubnet = false;
    if (g_conf.phoneAddress[0]) {
        unsigned long a = bridge::parseIPv4(g_conf.phoneAddress);
        if (a) { g_cand[g_candCount++] = a; source = "bridge.conf phoneAddress"; }
    } else if (g_listenIface[0] && own) {
        static char arp[16384];
        if (readProcFile("/proc/net/arp", arp, sizeof(arp))) {
            g_candCount = bridge::parseArpTable(arp, g_listenIface, g_cand, kMaxCandidates);
            source = "ARP table";
        }
        if (g_candCount == 0) {
            g_candCount = bridge::subnetHosts(own, g_listenMask, g_cand, kMaxCandidates);
            source = "the whole subnet";
            g_roundFromSubnet = true;
        }
    }
    if (g_candCount != g_lastCandCount || strcmp(source, g_lastCandSource) != 0) {
        char list[96] = "";                // the addresses themselves, when there are only a few
        if (g_candCount > 0 && g_candCount <= 4) {
            for (int i = 0; i < g_candCount; ++i) {
                char one[24];
                ipText(g_cand[i], one, sizeof(one));
                size_t used = strlen(list);
                snprintf(list + used, sizeof(list) - used, "%s%s", i ? ", " : " (", one);
            }
            size_t used = strlen(list);
            snprintf(list + used, sizeof(list) - used, ")");
        }
        if (g_candCount) blog("no phone connected: calling %d address(es)%s from %s on port %u, every %ld s",
                              g_candCount, list, source, (unsigned)g_conf.phonePort, kCallEveryMs / 1000);
        g_lastCandCount = g_candCount;
        g_lastCandSource = source;
    }
    if (g_candCount == 0) g_roundDoneMs = now;
}

void startProbes(long now) {
    while (g_candNext < g_candCount && probeCount() < kMaxProbes) {
        unsigned long ip = g_cand[g_candNext++];
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return;
        setNonBlocking(fd);
        struct sockaddr_in sin;
        memset(&sin, 0, sizeof(sin));
        sin.sin_family = AF_INET;
        sin.sin_port = htons(g_conf.phonePort);
        sin.sin_addr.s_addr = htonl(ip);
        if (connect(fd, reinterpret_cast<struct sockaddr*>(&sin), sizeof(sin)) == 0) {
            char name[24];
            ipText(ip, name, sizeof(name));
            g_resultCount = 0;
            addClient(fd, name, "the plugin called it", now);
            closeProbes();
            return;
        }
        if (errno != EINPROGRESS && errno != EWOULDBLOCK) { noteCallResult(ip, errno); close(fd); continue; }
        for (int i = 0; i < kMaxProbes; ++i) {
            if (g_probes[i].fd < 0) { g_probes[i].fd = fd; g_probes[i].ip = ip; g_probes[i].startMs = now; break; }
        }
    }
}

// One probe's socket became writable or failed: connected (a client now) or not (closed).
void probeDone(int i, long now) {
    Probe& p = g_probes[i];
    int err = 0;
    socklen_t len = sizeof(err);
    if (getsockopt(p.fd, SOL_SOCKET, SO_ERROR, &err, &len) != 0) err = errno;
    if (err != 0) { noteCallResult(p.ip, err); close(p.fd); p.fd = -1; return; }
    char name[24];
    ipText(p.ip, name, sizeof(name));
    int fd = p.fd;
    p.fd = -1;
    closeProbes();                         // one phone found: this round is over
    g_resultCount = 0;                     // after it leaves, failures are worth logging again
    addClient(fd, name, "the plugin called it", now);
}

// Each turn of the network loop: start or carry on with a round while no phone is connected; nothing otherwise.
void callPhones(long now) {
    if (g_listenFd < 0 || g_conf.phonePort == 0 || clientCount() > 0) {
        if (probeCount() || g_candCount) closeProbes();
        return;
    }
    for (int i = 0; i < kMaxProbes; ++i) {
        if (g_probes[i].fd >= 0 && now - g_probes[i].startMs >= kProbeTimeoutMs) {
            noteCallResult(g_probes[i].ip, -1);
            close(g_probes[i].fd); g_probes[i].fd = -1; }
    }
    if (g_candCount == 0 || (g_candNext >= g_candCount && probeCount() == 0)) {
        if (g_candCount && g_candNext >= g_candCount) { g_candCount = g_candNext = 0; g_roundDoneMs = now; }
        if (now - g_roundDoneMs < kCallEveryMs) return;
        startCallRound(now);
    }
    startProbes(now);
}

// Moves the queued CDS changes to every phone's buffer.
void distributeEvents(long now) {
    static Event batch[kQueueSize];
    int count;
    bool overflow;
    pthread_mutex_lock(&g_mutex);
    count = g_qLen;
    for (int i = 0; i < count; ++i) batch[i] = g_queue[(g_qHead + i) % kQueueSize];
    g_qHead = (g_qHead + count) % kQueueSize;
    g_qLen = 0;
    overflow = g_qOverflow;
    g_qOverflow = false;
    pthread_mutex_unlock(&g_mutex);
    for (int k = 0; k < kMaxClients; ++k) {
        Client& c = g_clients[k];
        if (c.fd < 0) continue;
        if (overflow) { sendSnapshot(c, now); continue; }      // the snapshot holds the newest of everything
        for (int i = 0; i < count && c.fd >= 0; ++i) {
            int n = bridge::cdsLine(g_lineBuf, sizeof(g_lineBuf), kProperties[batch[i].prop], batch[i].value,
                                    batch[i].t);
            if (n > 0 && (size_t)n < sizeof(g_lineBuf)) queueBytes(c, g_lineBuf, (size_t)n);
        }
    }
}

void setState(int s) {
    pthread_mutex_lock(&g_mutex);
    g_state = s;
    g_phones = clientCount();
    pthread_mutex_unlock(&g_mutex);
}

void* networkMain(void*) {
    sigset_t block;                       // a write to a closed connection must not kill the HMI
    sigemptyset(&block);
    sigaddset(&block, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &block, NULL);
    for (int i = 0; i < kMaxClients; ++i) { g_clients[i].fd = -1; g_clients[i].out = NULL; g_clients[i].len = g_clients[i].cap = 0; }
    for (int i = 0; i < kMaxProbes; ++i) g_probes[i].fd = -1;
    g_candCount = g_candNext = 0;
    g_roundDoneMs = monoMs() - kCallEveryMs;
    g_lastCandCount = -1;
    long lastCheck = -kAddressCheckMs;
    int state = kStateNoWifi;
    bool loggedNoWifi = false;
    while (!g_stop) {
        long now = monoMs();
        if (now - lastCheck >= kAddressCheckMs) {
            lastCheck = now;
            char addr[48], iface[32];
            unsigned long mask;
            findListenAddress(addr, sizeof(addr), iface, sizeof(iface), &mask);
            if (strcmp(addr, g_listenAddr) != 0 || (g_listenFd < 0 && addr[0])) {
                if (g_listenFd >= 0) closeListen(addr[0] ? "Wi-Fi address changed" : "Wi-Fi gone");
                if (addr[0]) {
                    state = openListen(addr);
                    snprintf(g_listenIface, sizeof(g_listenIface), "%s", iface);
                    g_listenMask = mask;
                    loggedNoWifi = false;
                }
                else {
                    state = kStateNoWifi;
                    if (!loggedNoWifi) blog("no Wi-Fi address yet (car Wi-Fi off?): checking every %ld s", kAddressCheckMs / 1000);
                    loggedNoWifi = true;
                }
            }
        }
        callPhones(now);
        struct pollfd fds[2 + kMaxClients + kMaxProbes];
        int idx[2 + kMaxClients + kMaxProbes];
        int nf = 0;
        fds[nf].fd = g_wake[0]; fds[nf].events = POLLIN; fds[nf].revents = 0; idx[nf++] = -2;
        if (g_listenFd >= 0) { fds[nf].fd = g_listenFd; fds[nf].events = POLLIN; fds[nf].revents = 0; idx[nf++] = -1; }
        for (int i = 0; i < kMaxClients; ++i) {
            if (g_clients[i].fd < 0) continue;
            fds[nf].fd = g_clients[i].fd;
            fds[nf].events = (short)(POLLIN | (g_clients[i].len ? POLLOUT : 0));
            fds[nf].revents = 0;
            idx[nf++] = i;
        }
        for (int i = 0; i < kMaxProbes; ++i) {
            if (g_probes[i].fd < 0) continue;
            fds[nf].fd = g_probes[i].fd;
            fds[nf].events = POLLOUT;
            fds[nf].revents = 0;
            idx[nf++] = 1000 + i;
        }
        int r = poll(fds, (nfds_t)nf, 500);
        now = monoMs();
        if (r < 0 && errno != EINTR) { blog("poll: %s", strerror(errno)); usleep(200000); }
        for (int k = 0; r > 0 && k < nf; ++k) {
            if (!fds[k].revents) continue;
            if (idx[k] == -2) {
                char drain[64];
                while (read(g_wake[0], drain, sizeof(drain)) > 0) {}
            } else if (idx[k] == -1) {
                acceptClient(now);
            } else if (idx[k] >= 1000) {
                if (g_probes[idx[k] - 1000].fd == fds[k].fd) probeDone(idx[k] - 1000, now);
            } else {
                Client& c = g_clients[idx[k]];
                if (fds[k].revents & (POLLIN | POLLHUP | POLLERR)) {
                    char in[512];              // what the phone sends is not used (protocol: optional)
                    ssize_t got = recv(c.fd, in, sizeof(in), 0);
                    if (got == 0) closeClient(c, "closed by the phone");
                    else if (got < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
                        closeClient(c, strerror(errno));
                }
            }
        }
        distributeEvents(now);
        for (int i = 0; i < kMaxClients; ++i) {
            Client& c = g_clients[i];
            if (c.fd < 0) continue;
            if (c.len == 0 && now - c.lastSentMs >= kPingAfterMs) {
                char ping[64];
                int n = bridge::pingLine(ping, sizeof(ping), now);
                if (n > 0) queueBytes(c, ping, (size_t)n);
            }
            flushClient(c, now);
        }
        if (state == kStateListening || state == kStateConnected)
            state = clientCount() > 0 ? kStateConnected : kStateListening;
        setState(state);
    }
    closeListen("plugin stopped");
    setState(kStateStopped);
    return NULL;
}

void wakeNetwork() {
    if (g_wake[1] < 0) return;
    char b = 1;
    ssize_t w = write(g_wake[1], &b, 1);     // non-blocking: a full pipe already means "wake up"
    (void)w;
}

void startNetwork() {
    if (g_threadRunning) return;
    if (pipe(g_wake) != 0) { blog("pipe: %s", strerror(errno)); return; }
    setNonBlocking(g_wake[0]);
    setNonBlocking(g_wake[1]);
    g_stop = false;
    if (pthread_create(&g_thread, NULL, &networkMain, NULL) != 0) {
        blog("cannot start the network thread");
        close(g_wake[0]); close(g_wake[1]);
        g_wake[0] = g_wake[1] = -1;
        return;
    }
    g_threadRunning = true;
}

void stopNetwork() {
    if (!g_threadRunning) return;
    g_stop = true;
    wakeNetwork();
    pthread_join(g_thread, NULL);           // poll() waits at most 500 ms
    g_threadRunning = false;
    close(g_wake[0]); close(g_wake[1]);
    g_wake[0] = g_wake[1] = -1;
}

// ---- CDS ----------------------------------------------------------------------------------------------------------
int g_logged[kPropertyCount];          // values written to the log per property (the first few only)

void handleCds(int prop, const bmw::BasicString<unsigned short>& value) {
    char text[bridge::kValueBytes];
    bridge::utf16ToUtf8(value.raw(), text, sizeof(text));
    if (!bridge::looksLikeJsonObject(text)) {
        if (g_logged[prop]++ < 3) blog("CDS %s: not a JSON object, not sent: '%.200s'", kProperties[prop], text);
        return;
    }
    long now = monoMs();
    bool changed;
    pthread_mutex_lock(&g_mutex);
    changed = !g_has[prop] || strcmp(g_last[prop], text) != 0;
    if (changed) {
        if (!g_has[prop]) ++g_valueCount;
        g_has[prop] = true;
        memcpy(g_last[prop], text, sizeof(text));
        if (g_qLen == kQueueSize) { g_qHead = (g_qHead + 1) % kQueueSize; --g_qLen; g_qOverflow = true; }
        Event& e = g_queue[(g_qHead + g_qLen) % kQueueSize];
        e.prop = prop;
        e.t = now;
        memcpy(e.value, text, sizeof(text));
        ++g_qLen;
    }
    pthread_mutex_unlock(&g_mutex);
    if (changed) {
        if (g_logged[prop]++ < 3) blog("CDS %s = %.300s", kProperties[prop], text);
        wakeNetwork();
    }
}

// The delegate does not say which property changed: one stub per property.
template <int P> bool onCds(void*, const bmw::BasicString<unsigned short>& v) {
    handleCds(P, v);
    return true;
}
typedef bool (*CdsStub)(void*, const bmw::BasicString<unsigned short>&);
const CdsStub kStubs[] = { &onCds<0>, &onCds<1>, &onCds<2>, &onCds<3>, &onCds<4>, &onCds<5>, &onCds<6>,
                           &onCds<7>, &onCds<8>, &onCds<9>, &onCds<10>, &onCds<11>, &onCds<12>, &onCds<13>,
                           &onCds<14> };
typedef char kOneStubPerProperty[sizeof(kStubs) / sizeof(kStubs[0]) == (size_t)kPropertyCount ? 1 : -1];

// ---- bmw::Runnable stand-in: vtable copied from the framework's, run() is ours -----------------------------------
struct StandInRunnable { void** vptr; };
void runnableNoop(void*) {}

void initRunnable(StandInRunnable& r, void** vtbl, void (*run)(void*)) {
    void** base = reinterpret_cast<void**>(dlsym(RTLD_DEFAULT, "_ZTVN3bmw8RunnableE"));
    if (base) memcpy(vtbl, base, 5 * sizeof(void*));
    else { vtbl[0] = NULL; vtbl[1] = NULL; }
    vtbl[2] = reinterpret_cast<void*>(&runnableNoop);     // destructors: the objects are static
    vtbl[3] = reinterpret_cast<void*>(&runnableNoop);
    vtbl[4] = reinterpret_cast<void*>(run);
    r.vptr = vtbl + 2;
}

StandInRunnable g_subRunnable, g_tickRunnable;
void* g_subVtbl[5];
void* g_tickVtbl[5];
unsigned g_shown[kLines];

void showScreen(bmw::PluginApp* app, bool force) {
    unsigned t[kLines];
    pthread_mutex_lock(&g_mutex);
    t[0] = kTextState + (unsigned)g_state;
    t[1] = kTextPhones + (unsigned)g_phones;
    t[2] = kTextValues + (unsigned)g_valueCount;
    pthread_mutex_unlock(&g_mutex);
    for (int i = 0; i < kLines; ++i) {
        if (!force && g_shown[i] == t[i]) continue;
        bmw::TextID text(t[i]);
        app->updateModel(kModelState + (unsigned)i, text);
        g_shown[i] = t[i];
    }
}

void tickRun(void*) {
    bmw::PluginApp* app = g_app;
    if (!app) return;
    showScreen(app, false);
    markStableWhenDue();
    app->post(reinterpret_cast<bmw::Runnable*>(&g_tickRunnable), kTickMs);
}

void subscribeRun(void*) {
    bmw::PluginApp* app = g_app;
    if (!app) return;
    for (int i = 0; i < kPropertyCount; ++i) {
        bmw::BasicString<char> name(kProperties[i]);
        bmw::CdsDelegate d;
        d.object = app;
        d.stub = kStubs[i];
        app->cdsAddPropertyChangedEventHandler(name, 0, d);
    }
    blog("subscribed %d CDS properties", (int)kPropertyCount);
    initRunnable(g_tickRunnable, g_tickVtbl, &tickRun);
    app->post(reinterpret_cast<bmw::Runnable*>(&g_tickRunnable), kTickMs);
}

bool onLinePressed(void* app, const bmw::Parcel&) {     // the lines only show status; a press refreshes them
    showScreen(static_cast<bmw::PluginApp*>(app), true);
    return true;
}

// ---- the app object -----------------------------------------------------------------------------------------------
void* g_vtbl[2 + kVtblSlots];

void loadConfig() {
    g_conf = bridge::Config();
    const char* used = "defaults";
    char* text = readSmallFile(kConfFile, 8192);
    if (text) used = kConfFile;
    else if ((text = readSmallFile(kConfFile2, 8192)) != NULL) used = kConfFile2;
    if (text) { bridge::parseConfig(text, g_conf); free(text); }
    blog("config (%s): port %u, interface '%s', address '%s', %d phone(s)", used, (unsigned)g_conf.port,
         g_conf.iface[0] ? g_conf.iface : "auto", g_conf.address, g_conf.maxClients);
}

bool appOnInit(bmw::PluginApp* self) {
    for (int i = 0; i < kLines; ++i) {              // handlers before the base onInit()
        bmw::ActionDelegate d;
        d.object = self;
        d.stub = &onLinePressed;
        self->addHandler(kActionFirst + (unsigned)i, d);
    }
    bool ok = self->onInit();
    g_initMs = monoMs();
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < kPropertyCount; ++i) { g_has[i] = false; g_logged[i] = 0; }
    g_valueCount = 0;
    g_qHead = g_qLen = 0;
    g_qOverflow = false;
    g_state = kStateStarting;
    g_phones = 0;
    pthread_mutex_unlock(&g_mutex);
    loadConfig();
    showScreen(self, true);
    startNetwork();
    initRunnable(g_subRunnable, g_subVtbl, &subscribeRun);
    self->post(reinterpret_cast<bmw::Runnable*>(&g_subRunnable), kSubscribeDelayMs);
    blog("onInit: base -> %d", (int)ok);
    return ok;
}

void appOnError(bmw::PluginApp*, unsigned code, const bmw::BasicString<char>& text) {
    const char* s = text.raw();
    blog("onError %u: %s", code, s ? s : "(null)");
}

void appOnFinalize(bmw::PluginApp* self) {
    blog("onFinalize");
    g_app = NULL;                        // no more Runnables
    stopNetwork();
    self->onFinalize();
}

bmw::PluginApp* createApp(bmw::IDispatcher& dispatcher, const char* name, const bmw::Bundle& params,
                          const bmw::Bundle& config) {
    mkdir(kDataDir, 0755);
    blog("create(%s) %s", name ? name : "(null)", BRIDGE_VERSION);
    if (fileExists(kDisableA) || fileExists(kDisableB)) { blog("turned off by app.disable"); return NULL; }
    if (g_app) { blog("create: already running"); return NULL; }
    if (!crashGuardAllows()) return NULL;
    g_stableMarked = false;
    void* mem = ::operator new(bmw::kPluginAppStorage);
    memset(mem, 0, bmw::kPluginAppStorage);
    bmw::PluginApp* app = new (mem) bmw::PluginApp(dispatcher, bmw::BasicString<char>(name ? name : "DrivelogBridge"),
                                                   1, 0, bmw::RHMI_INTERFACE_VERSION_1, params, config);
    void** base = *reinterpret_cast<void***>(app);
    memcpy(g_vtbl, base - 2, sizeof(g_vtbl));
    g_vtbl[2 + kSlotOnInit]     = reinterpret_cast<void*>(&appOnInit);
    g_vtbl[2 + kSlotOnError]    = reinterpret_cast<void*>(&appOnError);
    g_vtbl[2 + kSlotOnFinalize] = reinterpret_cast<void*>(&appOnFinalize);
    *reinterpret_cast<void***>(app) = g_vtbl + 2;
    g_app = app;
    return app;
}

void destroyApp(void* app) {
    blog("destroy");
    if (!app) return;
    if (app == g_app) g_app = NULL;
    stopNetwork();                       // in case onFinalize was not called
    void** vt = *reinterpret_cast<void***>(app);
    reinterpret_cast<void (*)(void*)>(vt[1])(app);     // deleting destructor
}

} // namespace

extern "C" {
struct BridgePluginAppInfo {
    uint16_t versionMajor;
    uint16_t versionMinor;
    const char* name;
    const char* vendor;
    uint32_t rhmiInterfaceVersion;
    bmw::PluginApp* (*create)(bmw::IDispatcher&, const char*, const bmw::Bundle&, const bmw::Bundle&);
    void (*destroy)(void*);
};
__attribute__((visibility("default"))) BridgePluginAppInfo pluginAppInfo = {
    1, 0, "DRIVELOG", "DRIVELOG", 1, &createApp, &destroyApp
};
}
