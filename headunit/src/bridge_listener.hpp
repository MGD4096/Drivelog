// The listening socket on the car's Wi-Fi: which address to use (bridge.conf, or the best Wi-Fi-looking interface),
// opening and closing it, and taking the phones that call in. Network thread only.
// Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_LISTENER_HPP
#define DRIVELOG_BRIDGE_LISTENER_HPP

#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <ifaddrs.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "bridge_clients.hpp"

namespace {

// Where the plugin listens, as the calls to the phones need it.
struct ListenInfo {
    char addr[48];          // empty: not listening
    char iface[32];         // interface of addr (empty: bridge.conf address=)
    unsigned long mask;     // its netmask, host order
};

class Listener : public PollSource {
public:
    Listener(const bridge::Config& conf, ConnectionSink& sink) : m_conf(conf), m_sink(sink), m_fd(-1) {
        m_info.addr[0] = 0;
        m_info.iface[0] = 0;
        m_info.mask = 0;
    }

    bool open() const { return m_fd >= 0; }
    const ListenInfo& info() const { return m_info; }

    // The address to listen on: bridge.conf address=, else the IPv4 address of interface= or of the best
    // Wi-Fi-looking interface (bridge::interfaceScore). Empty: none (the car's Wi-Fi is off).
    void findAddress(ListenInfo& out) const {
        out.addr[0] = 0;
        out.iface[0] = 0;
        out.mask = 0;
        if (m_conf.address[0]) { snprintf(out.addr, sizeof(out.addr), "%s", m_conf.address); return; }
        struct ifaddrs* list = NULL;
        if (getifaddrs(&list) != 0) return;
        int best = 0;
        for (struct ifaddrs* a = list; a; a = a->ifa_next) {
            if (!a->ifa_addr || a->ifa_addr->sa_family != AF_INET || !a->ifa_name) continue;
            const struct sockaddr_in* sin = reinterpret_cast<const struct sockaddr_in*>(a->ifa_addr);
            unsigned long host = ntohl(sin->sin_addr.s_addr);
            int score;
            if (m_conf.iface[0]) score = strcmp(a->ifa_name, m_conf.iface) == 0 && host != 0 ? 10 : 0;
            else score = bridge::interfaceScore(a->ifa_name, host);
            if (score > best) {
                best = score;
                inet_ntop(AF_INET, &sin->sin_addr, out.addr, (socklen_t)sizeof(out.addr));
                snprintf(out.iface, sizeof(out.iface), "%s", a->ifa_name);
                out.mask = a->ifa_netmask ? ntohl(reinterpret_cast<const struct sockaddr_in*>(a->ifa_netmask)->sin_addr.s_addr) : 0;
            }
        }
        freeifaddrs(list);
    }

    // Starts listening on [where]; returns the new state (listening, no Wi-Fi for a bad address, or port busy).
    int start(const ListenInfo& where) {
        struct sockaddr_in sin;
        memset(&sin, 0, sizeof(sin));
        sin.sin_family = AF_INET;
        sin.sin_port = htons(m_conf.port);
        if (inet_pton(AF_INET, where.addr, &sin.sin_addr) != 1) { blog("bad address '%s' in bridge.conf", where.addr); return kStateNoWifi; }
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) { blog("socket: %s", strerror(errno)); return kStatePortBusy; }
        int one = 1;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        if (bind(fd, reinterpret_cast<struct sockaddr*>(&sin), sizeof(sin)) != 0 || listen(fd, 4) != 0) {
            blog("cannot listen on %s:%u: %s", where.addr, (unsigned)m_conf.port, strerror(errno));
            ::close(fd);
            return kStatePortBusy;
        }
        setNonBlocking(fd);
        m_fd = fd;
        m_info = where;
        blog("listening on %s:%u", where.addr, (unsigned)m_conf.port);
        return kStateListening;
    }

    void stop(const char* why) {
        if (m_fd >= 0) {
            ::close(m_fd);
            blog("stopped listening on %s:%u (%s)", m_info.addr, (unsigned)m_conf.port, why);
        }
        m_fd = -1;
        m_info.addr[0] = 0;
    }

    virtual int fill(struct pollfd* fds, int max) {
        if (m_fd < 0 || max < 1) return 0;
        fds[0].fd = m_fd;
        fds[0].events = POLLIN;
        fds[0].revents = 0;
        return 1;
    }

    virtual void ready(const struct pollfd* fds, int count, long now) {
        if (count < 1 || !fds[0].revents || m_fd < 0) return;
        struct sockaddr_in peer;
        socklen_t plen = sizeof(peer);
        int fd = accept(m_fd, reinterpret_cast<struct sockaddr*>(&peer), &plen);
        if (fd < 0) return;
        char name[48] = "?";
        inet_ntop(AF_INET, &peer.sin_addr, name, sizeof(name));
        m_sink.connected(fd, name, "it called", now);
    }

private:
    const bridge::Config& m_conf;
    ConnectionSink& m_sink;
    int m_fd;
    ListenInfo m_info;
    Listener(const Listener&);
    Listener& operator=(const Listener&);
};

} // namespace

#endif
