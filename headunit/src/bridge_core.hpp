// Drivelog bridge - the parts of the head-unit plugin that do not touch the iDrive framework or the network:
// the property list, UTF-16 -> UTF-8, the JSON lines of the protocol, bridge.conf and the address rule.
// C++98 (QNX SDP 6.5 GCC 4.4, EntryNav2 GCC 4.6). Header only, so the host tests can include it directly.
#ifndef DRIVELOG_BRIDGE_CORE_HPP
#define DRIVELOG_BRIDGE_CORE_HPP
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace bridge {

// The car values Drivelog uses (core/CarData.kt, CdsProperty.ALL). A test on the app side reads this list from the
// source and fails when the two differ. Nothing personal (no GPS, contacts, VIN): nothing has to be masked.
static const char* const kProperties[] = {
    "driving.odometer",
    "sensors.fuel",
    "driving.averageConsumption",
    "sensors.temperatureExterior",
    "sensors.temperatureInterior",
    "sensors.doors",
    "sensors.trunk",
    "sensors.lid",
    "controls.sunroof",
    "controls.windowDriverFront",
    "controls.windowPassengerFront",
    "controls.windowDriverRear",
    "controls.windowPassengerRear",
    "sensors.battery",                 // 12 V battery charge, % (85..97 seen; 255 = none)
    "sensors.tirePressureWarning",     // status 0 = no warning
};
enum { kPropertyCount = sizeof(kProperties) / sizeof(kProperties[0]) };

const int kProtocolVersion = 1;
const unsigned short kDefaultPort = 7777;
const size_t kValueBytes = 1024;            // one value as UTF-8; the car's are well under 400 characters

// UTF-16 (zero-terminated) -> UTF-8, also zero-terminated; at most out_size - 1 bytes, never a cut sequence.
// Control characters become spaces, so a value can never break a line of the protocol. Lone surrogates -> '?'.
// Returns the number of bytes written.
inline size_t utf16ToUtf8(const unsigned short* in, char* out, size_t out_size) {
    size_t n = 0;
    if (!out || out_size == 0) return 0;
    if (!in) { out[0] = 0; return 0; }
    for (size_t i = 0; in[i]; ++i) {
        unsigned long c = in[i];
        if (c >= 0xD800 && c <= 0xDBFF && in[i + 1] >= 0xDC00 && in[i + 1] <= 0xDFFF) {
            c = 0x10000UL + ((c - 0xD800UL) << 10) + (in[i + 1] - 0xDC00UL);
            ++i;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = '?';
        }
        if (c < 0x20 || c == 0x7F) c = ' ';
        char b[4];
        size_t len;
        if (c < 0x80) { b[0] = (char)c; len = 1; }
        else if (c < 0x800) { b[0] = (char)(0xC0 | (c >> 6)); b[1] = (char)(0x80 | (c & 0x3F)); len = 2; }
        else if (c < 0x10000) {
            b[0] = (char)(0xE0 | (c >> 12)); b[1] = (char)(0x80 | ((c >> 6) & 0x3F));
            b[2] = (char)(0x80 | (c & 0x3F)); len = 3;
        } else {
            b[0] = (char)(0xF0 | (c >> 18)); b[1] = (char)(0x80 | ((c >> 12) & 0x3F));
            b[2] = (char)(0x80 | ((c >> 6) & 0x3F)); b[3] = (char)(0x80 | (c & 0x3F)); len = 4;
        }
        if (n + len >= out_size) break;
        memcpy(out + n, b, len);
        n += len;
    }
    out[n] = 0;
    return n;
}

// A JSON object as far as the bridge cares: '{' ... '}' after trimming spaces, balanced braces outside strings.
// The app parses it properly; this only keeps a line of the protocol well formed.
inline bool looksLikeJsonObject(const char* s) {
    if (!s) return false;
    while (*s == ' ') ++s;
    if (*s != '{') return false;
    int depth = 0;
    bool inString = false;
    const char* last = s;
    for (const char* p = s; *p; ++p) {
        char c = *p;
        if (inString) {
            if (c == '\\') { if (!p[1]) return false; ++p; }
            else if (c == '"') inString = false;
            continue;
        }
        if (c == '"') inString = true;
        else if (c == '{' || c == '[') ++depth;
        else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        if (c != ' ') last = p;
    }
    return depth == 0 && !inString && *last == '}';
}

// The lines of the protocol (docs: headunit/README.md). Each returns the length written (snprintf), the line
// ends with '\n'. t = the head unit's monotonic clock in ms.
inline int helloLine(char* out, size_t n, const char* app, long t) {
    return snprintf(out, n, "{\"type\":\"hello\",\"proto\":%d,\"app\":\"%s\",\"t\":%ld,\"props\":%d}\n",
                    kProtocolVersion, app, t, (int)kPropertyCount);
}

inline int cdsLine(char* out, size_t n, const char* name, const char* value, long t) {
    return snprintf(out, n, "{\"type\":\"cds\",\"t\":%ld,\"name\":\"%s\",\"value\":%s}\n", t, name, value);
}

inline int pingLine(char* out, size_t n, long t) {
    return snprintf(out, n, "{\"type\":\"ping\",\"t\":%ld}\n", t);
}

// values[i] = latest value of kProperties[i] or NULL. Returns the length, or -1 when it does not fit.
inline int snapshotLine(char* out, size_t n, const char* const* values, long t) {
    int len = snprintf(out, n, "{\"type\":\"snapshot\",\"t\":%ld,\"values\":{", t);
    if (len < 0 || (size_t)len >= n) return -1;
    bool first = true;
    for (int i = 0; i < kPropertyCount; ++i) {
        if (!values[i]) continue;
        int w = snprintf(out + len, n - (size_t)len, "%s\"%s\":%s", first ? "" : ",", kProperties[i], values[i]);
        if (w < 0 || (size_t)(len + w) >= n) return -1;
        len += w;
        first = false;
    }
    int w = snprintf(out + len, n - (size_t)len, "}}\n");
    if (w < 0 || (size_t)(len + w) >= n) return -1;
    return len + w;
}

// ---- bridge.conf (in the data folder; every line optional) ----------------------------------------------------------
//   port=7777          TCP port
//   interface=wlan0    listen only on this interface's IPv4 address
//   address=a.b.c.d    listen only on this address (wins over interface; the only way to allow 127.0.0.1)
//   clients=2          phones at the same time (1..4)
// Without interface/address: the car's Wi-Fi, found by the rule in pickInterface().
struct Config {
    unsigned short port;
    char iface[32];
    char address[48];
    int maxClients;
    Config() : port(kDefaultPort), maxClients(2) { iface[0] = 0; address[0] = 0; }
};

inline void parseConfig(const char* text, Config& c) {
    while (text && *text) {
        const char* end = strchr(text, '\n');
        size_t len = end ? (size_t)(end - text) : strlen(text);
        char line[128];
        if (len >= sizeof(line)) len = sizeof(line) - 1;
        memcpy(line, text, len);
        line[len] = 0;
        while (len && (line[len - 1] == '\r' || line[len - 1] == ' ' || line[len - 1] == '\t')) line[--len] = 0;
        char* eq = strchr(line, '=');
        if (line[0] != '#' && eq) {
            *eq = 0;
            const char* key = line;
            const char* val = eq + 1;
            while (*val == ' ') ++val;
            if (strcmp(key, "port") == 0) {
                long p = strtol(val, NULL, 10);
                if (p > 0 && p < 65536) c.port = (unsigned short)p;
            } else if (strcmp(key, "interface") == 0) {
                snprintf(c.iface, sizeof(c.iface), "%s", val);
            } else if (strcmp(key, "address") == 0) {
                snprintf(c.address, sizeof(c.address), "%s", val);
            } else if (strcmp(key, "clients") == 0) {
                long k = strtol(val, NULL, 10);
                if (k >= 1 && k <= 4) c.maxClients = (int)k;
            }
        }
        if (!end) break;
        text = end + 1;
    }
}

// How well an interface fits as the car's Wi-Fi when bridge.conf names none: 0 = never. addr is IPv4 in host order.
// wlan0 (EntryNav2, checked in entry-aad), then other Wi-Fi names, then the 172.16.224.0/24 network the iDrive
// Plus plugin found phones in on NBT Evo. Loopback and link-local (169.254/16: the service link) are never used.
inline int interfaceScore(const char* name, unsigned long addr) {
    if ((addr >> 24) == 127 || (addr >> 16) == 0xA9FE || addr == 0) return 0;
    if (strcmp(name, "wlan0") == 0) return 4;
    if (strncmp(name, "uap", 3) == 0 || strncmp(name, "wl", 2) == 0) return 3;
    if ((addr >> 8) == 0xAC10E0UL) return 2;                 // 172.16.224.x
    return 0;
}

} // namespace bridge
#endif
