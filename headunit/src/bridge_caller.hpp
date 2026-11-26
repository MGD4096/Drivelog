// Calling the phones. While no phone is connected the plugin itself connects to port phonePort of the phones on its
// Wi-Fi (see bridge_core.hpp, "finding phones"): a few at a time, non-blocking, each given kProbeTimeoutMs; a full
// round at most every kCallEveryMs. The first one that answers goes to the ConnectionSink like one that called in.
// Needed because wireless Android Auto keeps the car's Wi-Fi to itself (AGENTS.md). Network thread only.
// Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_CALLER_HPP
#define DRIVELOG_BRIDGE_CALLER_HPP

#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "bridge_listener.hpp"

namespace {

enum { kMaxProbes = 24, kMaxCandidates = 254 };
const long kProbeTimeoutMs = 1500;
const long kCallEveryMs = 5000;

class PhoneCaller : public PollSource {
public:
    PhoneCaller(const bridge::Config& conf, ConnectionSink& sink)
        : m_conf(conf), m_sink(sink), m_candCount(0), m_candNext(0), m_roundDoneMs(0), m_lastCandCount(-1),
          m_lastCandSource(""), m_fromSubnet(false), m_resultCount(0) {
        for (int i = 0; i < kMaxProbes; ++i) m_probes[i].fd = -1;
    }

    // A new start of the network thread: the first round may begin at once.
    void reset(long now) {
        stop();
        m_roundDoneMs = now - kCallEveryMs;
        m_lastCandCount = -1;
    }

    // Closes every call in progress and forgets the round.
    void stop() {
        for (int i = 0; i < kMaxProbes; ++i) if (m_probes[i].fd >= 0) { ::close(m_probes[i].fd); m_probes[i].fd = -1; }
        m_candCount = m_candNext = 0;
    }

    // Each turn of the network loop: start or carry on with a round while the plugin listens and no phone is
    // connected; nothing otherwise.
    void tick(long now, const Listener& listener, int phonesConnected) {
        if (!listener.open() || m_conf.phonePort == 0 || phonesConnected > 0) {
            if (probeCount() || m_candCount) stop();
            return;
        }
        for (int i = 0; i < kMaxProbes; ++i) {
            if (m_probes[i].fd >= 0 && now - m_probes[i].startMs >= kProbeTimeoutMs) {
                noteResult(m_probes[i].ip, -1);
                ::close(m_probes[i].fd);
                m_probes[i].fd = -1;
            }
        }
        if (m_candCount == 0 || (m_candNext >= m_candCount && probeCount() == 0)) {
            if (m_candCount && m_candNext >= m_candCount) { m_candCount = m_candNext = 0; m_roundDoneMs = now; }
            if (now - m_roundDoneMs < kCallEveryMs) return;
            startRound(now, listener.info());
        }
        startProbes(now);
    }

    virtual int fill(struct pollfd* fds, int max) {
        int n = 0;
        for (int i = 0; i < kMaxProbes && n < max; ++i) {
            if (m_probes[i].fd < 0) continue;
            fds[n].fd = m_probes[i].fd;
            fds[n].events = POLLOUT;
            fds[n].revents = 0;
            ++n;
        }
        return n;
    }

    // A call's socket became writable or failed: connected (handed on) or not (closed).
    virtual void ready(const struct pollfd* fds, int count, long now) {
        for (int k = 0; k < count; ++k) {
            if (!fds[k].revents) continue;
            for (int i = 0; i < kMaxProbes; ++i) {
                if (m_probes[i].fd >= 0 && m_probes[i].fd == fds[k].fd) { probeDone(i, now); break; }
            }
        }
    }

private:
    struct Probe { int fd; unsigned long ip; long startMs; };

    // The last outcome of calling an address, so the log says once (not every 5 s) why a phone did not answer.
    // err: -1 = no answer within kProbeTimeoutMs, otherwise an errno. Forgotten when a phone connects.
    struct CallResult { unsigned long ip; int err; };

    int probeCount() const {
        int n = 0;
        for (int i = 0; i < kMaxProbes; ++i) if (m_probes[i].fd >= 0) ++n;
        return n;
    }

    void noteResult(unsigned long ip, int err) {
        if (m_fromSubnet && err == -1) return;    // most of a subnet never answers: not worth a line each
        int i = 0;
        while (i < m_resultCount && m_results[i].ip != ip) ++i;
        if (i == m_resultCount) {
            if (m_resultCount == kMaxCandidates) return;
            m_results[i].ip = ip;
            m_results[i].err = 0;
            ++m_resultCount;
        }
        if (m_results[i].err == err) return;
        m_results[i].err = err;
        char name[24];
        ipText(ip, name, sizeof(name));
        if (err == -1) blog("calling %s:%u: no answer within %ld ms (dropped on the way, or the phone ignores it)",
                            name, (unsigned)m_conf.phonePort, kProbeTimeoutMs);
        else blog("calling %s:%u: %s", name, (unsigned)m_conf.phonePort, strerror(err));
    }

    // Who to call this round: phoneAddress=, else the phones in the ARP table of the Wi-Fi interface (Linux), else
    // every address of its /24. Nothing when the plugin listens on an address from bridge.conf (no interface to look at).
    void startRound(long now, const ListenInfo& where) {
        m_candCount = m_candNext = 0;
        unsigned long own = bridge::parseIPv4(where.addr);
        const char* source = "";
        m_fromSubnet = false;
        if (m_conf.phoneAddress[0]) {
            unsigned long a = bridge::parseIPv4(m_conf.phoneAddress);
            if (a) { m_cand[m_candCount++] = a; source = "bridge.conf phoneAddress"; }
        } else if (where.iface[0] && own) {
            static char arp[16384];
            if (readProcFile("/proc/net/arp", arp, sizeof(arp))) {
                m_candCount = bridge::parseArpTable(arp, where.iface, m_cand, kMaxCandidates);
                source = "ARP table";
            }
            if (m_candCount == 0) {
                m_candCount = bridge::subnetHosts(own, where.mask, m_cand, kMaxCandidates);
                source = "the whole subnet";
                m_fromSubnet = true;
            }
        }
        if (m_candCount != m_lastCandCount || strcmp(source, m_lastCandSource) != 0) {
            logRound(source);
            m_lastCandCount = m_candCount;
            m_lastCandSource = source;
        }
        if (m_candCount == 0) m_roundDoneMs = now;
    }

    void logRound(const char* source) {
        if (!m_candCount) return;
        char list[96] = "";                // the addresses themselves, when there are only a few
        if (m_candCount <= 4) {
            for (int i = 0; i < m_candCount; ++i) {
                char one[24];
                ipText(m_cand[i], one, sizeof(one));
                size_t used = strlen(list);
                snprintf(list + used, sizeof(list) - used, "%s%s", i ? ", " : " (", one);
            }
            size_t used = strlen(list);
            snprintf(list + used, sizeof(list) - used, ")");
        }
        blog("no phone connected: calling %d address(es)%s from %s on port %u, every %ld s",
             m_candCount, list, source, (unsigned)m_conf.phonePort, kCallEveryMs / 1000);
    }

    void startProbes(long now) {
        while (m_candNext < m_candCount && probeCount() < kMaxProbes) {
            unsigned long ip = m_cand[m_candNext++];
            int fd = socket(AF_INET, SOCK_STREAM, 0);
            if (fd < 0) return;
            setNonBlocking(fd);
            struct sockaddr_in sin;
            memset(&sin, 0, sizeof(sin));
            sin.sin_family = AF_INET;
            sin.sin_port = htons(m_conf.phonePort);
            sin.sin_addr.s_addr = htonl(ip);
            if (connect(fd, reinterpret_cast<struct sockaddr*>(&sin), sizeof(sin)) == 0) {
                found(fd, ip, now);
                return;
            }
            if (errno != EINPROGRESS && errno != EWOULDBLOCK) { noteResult(ip, errno); ::close(fd); continue; }
            for (int i = 0; i < kMaxProbes; ++i) {
                if (m_probes[i].fd < 0) { m_probes[i].fd = fd; m_probes[i].ip = ip; m_probes[i].startMs = now; break; }
            }
        }
    }

    void probeDone(int i, long now) {
        Probe& p = m_probes[i];
        int err = 0;
        socklen_t len = sizeof(err);
        if (getsockopt(p.fd, SOL_SOCKET, SO_ERROR, &err, &len) != 0) err = errno;
        if (err != 0) { noteResult(p.ip, err); ::close(p.fd); p.fd = -1; return; }
        int fd = p.fd;
        p.fd = -1;
        found(fd, p.ip, now);
    }

    // One phone answered: this round is over, and after it leaves, failures are worth logging again.
    void found(int fd, unsigned long ip, long now) {
        char name[24];
        ipText(ip, name, sizeof(name));
        stop();
        m_resultCount = 0;
        m_sink.connected(fd, name, "the plugin called it", now);
    }

    const bridge::Config& m_conf;
    ConnectionSink& m_sink;
    Probe m_probes[kMaxProbes];
    unsigned long m_cand[kMaxCandidates];
    int m_candCount, m_candNext;
    long m_roundDoneMs;
    int m_lastCandCount;
    const char* m_lastCandSource;
    bool m_fromSubnet;
    CallResult m_results[kMaxCandidates];
    int m_resultCount;
    PhoneCaller(const PhoneCaller&);
    PhoneCaller& operator=(const PhoneCaller&);
};

} // namespace

#endif
