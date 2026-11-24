// Host test of the Drivelog bridge, built with the car's compiler and run under qemu-arm (toolchain/check.sh):
//  1. bridge_core.hpp on its own (UTF-16, JSON check, lines, bridge.conf, the Wi-Fi rule);
//  2. libDrivelogBridge.so loaded like the HMI loads it (dlsym "pluginAppInfo") against fake_framework.cpp, with
//     bridge.conf "address=127.0.0.1 port=17777": CDS values in, protocol lines out over a real TCP connection.
//   host_test <libDrivelogBridge.so> <its data folder>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <string>
#include "bridge_core.hpp"
#include "fake_framework.hpp"

static int g_fail = 0, g_checks = 0;
#define CHECK(c) do { ++g_checks; if (!(c)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); ++g_fail; } } while (0)

static bool contains(const std::string& s, const char* part) { return s.find(part) != std::string::npos; }

// ---- 1. bridge_core.hpp -------------------------------------------------------------------------------------------
static void testCore() {
    char out[64];
    const unsigned short ascii[] = { '{', '"', 'a', '"', ':', '1', '}', 0 };
    CHECK(bridge::utf16ToUtf8(ascii, out, sizeof(out)) == 7 && strcmp(out, "{\"a\":1}") == 0);
    const unsigned short pl[] = { 0x0141, 0x00F3, 0x20AC, 0xD83D, 0xDE97, 0 };       // Ł ó € and a car emoji
    bridge::utf16ToUtf8(pl, out, sizeof(out));
    CHECK(strcmp(out, "\xC5\x81\xC3\xB3\xE2\x82\xAC\xF0\x9F\x9A\x97") == 0);
    const unsigned short ctrl[] = { 'a', '\n', 'b', 0xD800, 'c', 0 };                // newline, lone surrogate
    bridge::utf16ToUtf8(ctrl, out, sizeof(out));
    CHECK(strcmp(out, "a b?c") == 0);
    char small[4];
    bridge::utf16ToUtf8(pl, small, sizeof(small));                                    // "Ł" fits, "ó" not
    CHECK(strcmp(small, "\xC5\x81") == 0);

    CHECK(bridge::looksLikeJsonObject("{\"fuel\":{\"tanklevel\":31}}"));
    CHECK(bridge::looksLikeJsonObject("  {\"s\":\"a}b{\"} "));
    CHECK(bridge::looksLikeJsonObject("{\"s\":\"q\\\"}\"}"));
    CHECK(!bridge::looksLikeJsonObject("{\"a\":1"));
    CHECK(!bridge::looksLikeJsonObject("[1]"));
    CHECK(!bridge::looksLikeJsonObject("{\"a\":\"open}"));
    CHECK(!bridge::looksLikeJsonObject("{}}"));
    CHECK(!bridge::looksLikeJsonObject(""));

    char line[512];
    bridge::cdsLine(line, sizeof(line), "sensors.fuel", "{\"tanklevel\":31}", 1234);
    CHECK(strcmp(line, "{\"type\":\"cds\",\"t\":1234,\"name\":\"sensors.fuel\",\"value\":{\"tanklevel\":31}}\n") == 0);
    const char* values[bridge::kPropertyCount] = { 0 };
    CHECK(bridge::snapshotLine(line, sizeof(line), values, 5) > 0);
    CHECK(strcmp(line, "{\"type\":\"snapshot\",\"t\":5,\"values\":{}}\n") == 0);
    values[0] = "{\"odometer\":1}";
    values[1] = "{\"x\":2}";
    bridge::snapshotLine(line, sizeof(line), values, 5);
    CHECK(strcmp(line, "{\"type\":\"snapshot\",\"t\":5,\"values\":{\"driving.odometer\":{\"odometer\":1},"
                       "\"sensors.fuel\":{\"x\":2}}}\n") == 0);
    CHECK(bridge::snapshotLine(line, 20, values, 5) == -1);

    bridge::Config c;
    CHECK(c.port == 7777 && c.maxClients == 2 && !c.iface[0] && !c.address[0]);
    bridge::parseConfig("# comment\r\nport=8000\r\ninterface= wlan1 \nclients=9\naddress=10.0.0.1", c);
    CHECK(c.port == 8000 && strcmp(c.iface, "wlan1") == 0 && strcmp(c.address, "10.0.0.1") == 0 && c.maxClients == 2);
    bridge::parseConfig("port=70000\nclients=1", c);
    CHECK(c.port == 8000 && c.maxClients == 1);

    CHECK(bridge::interfaceScore("wlan0", 0xC0A80001UL) == 4);
    CHECK(bridge::interfaceScore("uap0", 0xC0A80001UL) == 3);
    CHECK(bridge::interfaceScore("en5", 0xAC10E001UL) == 2);       // 172.16.224.1
    CHECK(bridge::interfaceScore("en5", 0xAC10E101UL) == 0);
    CHECK(bridge::interfaceScore("wlan0", 0x7F000001UL) == 0);     // loopback
    CHECK(bridge::interfaceScore("wlan0", 0xA9FE0100UL) == 0);     // 169.254.1.0, the service link
    CHECK(bridge::interfaceScore("lo", 0x7F000001UL) == 0);
}

// ---- 2. the plugin ------------------------------------------------------------------------------------------------
struct Info {
    uint16_t major, minor;
    const char* name;
    const char* vendor;
    uint32_t rhmi;
    bmw::PluginApp* (*create)(bmw::IDispatcher&, const char*, const bmw::Bundle&, const bmw::Bundle&);
    void (*destroy)(void*);
};

static void runPosted() {
    std::vector<bmw::Runnable*> now;
    now.swap(g_fake.posted);
    for (size_t i = 0; i < now.size(); ++i) {
        void** vptr = *reinterpret_cast<void***>(now[i]);
        reinterpret_cast<void (*)(void*)>(vptr[2])(now[i]);        // run(), slot 2 after the two destructors
    }
}

static void feed(const char* prop, const char* utf8) {
    std::map<std::string, bmw::CdsDelegate>::iterator it = g_fake.cds.find(prop);
    if (it == g_fake.cds.end()) { printf("FAIL: no CDS handler for %s\n", prop); ++g_fail; return; }
    unsigned short buf[512];
    size_t n = 0;
    for (const unsigned char* p = (const unsigned char*)utf8; *p && n < 511;) {    // 1-2 byte UTF-8 is enough here
        if (*p < 0x80) buf[n++] = *p++;
        else { buf[n++] = (unsigned short)(((p[0] & 0x1F) << 6) | (p[1] & 0x3F)); p += 2; }
    }
    buf[n] = 0;
    bmw::BasicString<unsigned short> s(buf);
    it->second.stub(it->second.object, s);
}

static long nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int connectTo(unsigned short port) {
    long until = nowMs() + 8000;
    while (nowMs() < until) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in a;
        memset(&a, 0, sizeof(a));
        a.sin_family = AF_INET;
        a.sin_port = htons(port);
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(fd, (struct sockaddr*)&a, sizeof(a)) == 0) return fd;
        close(fd);
        usleep(100000);
    }
    return -1;
}

struct Reader {
    int fd;
    std::string buf;
    explicit Reader(int f) : fd(f) {}
    // One line without '\n'; "" on timeout, "<closed>" when the plugin closed the connection.
    std::string line(long timeoutMs) {
        long until = nowMs() + timeoutMs;
        for (;;) {
            size_t nl = buf.find('\n');
            if (nl != std::string::npos) { std::string l = buf.substr(0, nl); buf.erase(0, nl + 1); return l; }
            long left = until - nowMs();
            if (left <= 0) return "";
            struct pollfd p = { fd, POLLIN, 0 };
            if (poll(&p, 1, (int)left) <= 0) continue;
            char b[4096];
            ssize_t n = recv(fd, b, sizeof(b), 0);
            if (n <= 0) return "<closed>";
            buf.append(b, (size_t)n);
        }
    }
    // The next line that is not a ping.
    std::string data(long timeoutMs) {
        for (;;) {
            std::string l = line(timeoutMs);
            if (!contains(l, "\"type\":\"ping\"")) return l;
        }
    }
};

static std::string readFile(const std::string& p) {
    std::string s;
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) return s;
    char b[4096];
    size_t n;
    while ((n = fread(b, 1, sizeof(b), f)) > 0) s.append(b, n);
    fclose(f);
    return s;
}

static unsigned text(unsigned model) { return g_fake.modelText[model]; }

static void testPlugin(const char* libPath, const std::string& dataDir) {
    void* lib = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { printf("FAIL: dlopen: %s\n", dlerror()); ++g_fail; return; }
    Info* info = static_cast<Info*>(dlsym(lib, "pluginAppInfo"));
    CHECK(info != NULL);
    if (!info) return;
    CHECK(info->major == 1 && info->rhmi == 1 && strcmp(info->name, "DRIVELOG") == 0);

    static char dummy[64];
    bmw::PluginApp* app = info->create(*(bmw::IDispatcher*)dummy, "DrivelogBridge", *(bmw::Bundle*)dummy,
                                       *(bmw::Bundle*)dummy);
    CHECK(app != NULL);
    if (!app) return;
    CHECK(info->create(*(bmw::IDispatcher*)dummy, "DrivelogBridge", *(bmw::Bundle*)dummy, *(bmw::Bundle*)dummy) == NULL);
    CHECK(readFile(dataDir + "/app-guard").size() > 0);                 // crash guard counts this start
    void** vptr = *reinterpret_cast<void***>(app);
    CHECK(reinterpret_cast<bool (*)(void*)>(vptr[22])(app));             // onInit
    CHECK(g_fake.baseOnInit == 1);
    CHECK(g_fake.actions.size() == 3 && g_fake.actions.count(31) && g_fake.actions.count(33));
    CHECK(text(41) == 1000 && text(42) == 2000 && text(43) == 3000);     // starting, 0 phones, 0 values

    runPosted();                                                         // the subscription step
    CHECK(g_fake.cds.size() == (size_t)bridge::kPropertyCount);
    CHECK(g_fake.cdsOrder.size() == (size_t)bridge::kPropertyCount && g_fake.cdsOrder[0] == "driving.odometer");
    CHECK(g_fake.posted.size() == 1);                                    // the 1 s tick

    feed("driving.odometer", "{\"odometer\":12345}");                   // before any phone: goes into the snapshot

    int fd = connectTo(17777);
    CHECK(fd >= 0);
    if (fd < 0) return;
    Reader r(fd);
    std::string hello = r.data(3000);
    CHECK(contains(hello, "{\"type\":\"hello\",\"proto\":1,\"app\":\"drivelog-bridge-0.1.0 EntryNav2\""));
    CHECK(contains(hello, "\"props\":15}"));
    std::string snap = r.data(3000);
    CHECK(contains(snap, "{\"type\":\"snapshot\",") && contains(snap, "\"values\":{\"driving.odometer\":{\"odometer\":12345}}}"));

    feed("sensors.fuel", "{\"fuel\":{\"tanklevel\":31,\"range\":420}}");
    std::string l = r.data(3000);
    CHECK(contains(l, "\"type\":\"cds\"") && contains(l, "\"name\":\"sensors.fuel\",\"value\":{\"fuel\":{\"tanklevel\":31,\"range\":420}}}"));

    feed("sensors.fuel", "{\"fuel\":{\"tanklevel\":31,\"range\":420}}");     // same again: not sent
    feed("sensors.lid", "{\"lid\":\"nie wiem");                                  // not JSON: not sent
    feed("sensors.temperatureExterior", "{\"temperatureExterior\":4.5,\"note\":\"\xC5\x82\"}");   // "ł"
    l = r.data(3000);
    CHECK(contains(l, "\"name\":\"sensors.temperatureExterior\"") && contains(l, "\"note\":\"\xC5\x82\"}"));

    for (int i = 0; i < 400; ++i) {                                      // more than the queue holds (256)
        char v[64];
        snprintf(v, sizeof(v), "{\"odometer\":%d}", 20000 + i);
        feed("driving.odometer", v);
    }
    std::string last;
    long until = nowMs() + 4000;
    while (nowMs() < until && !contains(last, "{\"odometer\":20399}")) last = r.data(1000);
    CHECK(contains(last, "{\"odometer\":20399}"));                       // the newest value always arrives

    runPosted();                                                         // tick: screen texts
    CHECK(text(41) == 1003 && text(42) == 2001 && text(43) == 3003);     // connected, 1 phone, 3 values

    std::string ping = r.line(7000);                                     // nothing new for 5 s: ping
    CHECK(contains(ping, "{\"type\":\"ping\",\"t\":"));

    int fd2 = connectTo(17777);                                          // second phone: allowed (default 2)
    Reader r2(fd2);
    CHECK(contains(r2.data(3000), "\"type\":\"hello\""));
    std::string snap2 = r2.data(3000);
    CHECK(contains(snap2, "\"sensors.fuel\":{\"fuel\"") && contains(snap2, "{\"odometer\":20399}"));
    int fd3 = connectTo(17777);                                          // third: refused
    Reader r3(fd3);
    CHECK(contains(r3.line(3000), "too many phones"));
    CHECK(r3.line(3000) == "<closed>");
    close(fd3);

    close(fd2);                                                          // a phone leaves: the other stays
    feed("controls.sunroof", "{\"sunroof\":{\"status\":0}}");
    CHECK(contains(r.data(3000), "\"name\":\"controls.sunroof\""));
    usleep(1200000);
    runPosted();
    CHECK(text(42) == 2001 && text(43) == 3004);

    reinterpret_cast<void (*)(void*)>(vptr[23])(app);                    // onFinalize: connections closed
    CHECK(g_fake.baseOnFinalize == 1);
    CHECK(r.line(3000) == "<closed>");
    runPosted();                                                         // a tick after finalize does nothing
    CHECK(g_fake.posted.empty());
    close(fd);
    info->destroy(app);
    CHECK(g_fake.destroyed == 1);

    std::string log = readFile(dataDir + "/bridge.log");
    CHECK(contains(log, "listening on 127.0.0.1:17777"));
    CHECK(contains(log, "subscribed 15 CDS properties"));
    CHECK(contains(log, "CDS sensors.lid: not a JSON object"));
    CHECK(contains(log, "phone 127.0.0.1 refused"));

    // app.disable: create() refuses
    std::string off = dataDir + "/app.disable";
    FILE* f = fopen(off.c_str(), "w");
    if (f) fclose(f);
    CHECK(info->create(*(bmw::IDispatcher*)dummy, "DrivelogBridge", *(bmw::Bundle*)dummy, *(bmw::Bundle*)dummy) == NULL);
    unlink(off.c_str());

    // crash guard: two more starts that never got stable make three lines -> the next create() refuses
    unlink((dataDir + "/app-guard").c_str());
    for (int i = 0; i < 3; ++i) {
        g_fake = FakeState();
        bmw::PluginApp* a = info->create(*(bmw::IDispatcher*)dummy, "DrivelogBridge", *(bmw::Bundle*)dummy,
                                         *(bmw::Bundle*)dummy);
        CHECK(a != NULL);
        if (a) info->destroy(a);
    }
    CHECK(info->create(*(bmw::IDispatcher*)dummy, "DrivelogBridge", *(bmw::Bundle*)dummy, *(bmw::Bundle*)dummy) == NULL);
    CHECK(contains(readFile(dataDir + "/bridge.log"), "crash guard: the HMI restarted 3 times"));
}

int main(int argc, char** argv) {
    if (argc != 3) { printf("usage: host_test <libDrivelogBridge.so> <data dir>\n"); return 2; }
    testCore();
    testPlugin(argv[1], argv[2]);
    printf("%s: %d checks, %d failed\n", g_fail ? "FAILED" : "OK", g_checks, g_fail);
    return g_fail ? 1 : 0;
}
