// The connected phones and the two small interfaces the network parts talk through:
//  - PollSource: a part of the network loop with sockets to watch (wake pipe, listener, phones, calls to phones);
//  - ConnectionSink: where a new connection goes, whichever side opened it.
// Network thread only. Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_CLIENTS_HPP
#define DRIVELOG_BRIDGE_CLIENTS_HPP

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include "bridge_core.hpp"
#include "bridge_log.hpp"
#include "bridge_values.hpp"

namespace {

// A part of the network loop that has sockets to watch. Each turn the loop asks every source to [fill] its entries
// (at most [max]), polls them all, then hands each source back its own entries with their revents.
class PollSource {
public:
    virtual int fill(struct pollfd* fds, int max) = 0;
    virtual void ready(const struct pollfd* fds, int count, long now) = 0;
protected:
    ~PollSource() {}
};

// Takes a connected socket: a phone that called in, or one the plugin called. [how] is for the log.
class ConnectionSink {
public:
    virtual void connected(int fd, const char* name, const char* how, long now) = 0;
protected:
    ~ConnectionSink() {}
};

enum { kMaxClients = 4 };
const long kPingAfterMs = 5000;             // a phone that got nothing for this long gets a ping
const size_t kClientBufMax = 256 * 1024;    // a phone with more than this waiting to be sent is dropped

// The phones connected now: what is waiting to be sent to each, written without ever blocking. A phone that does not
// read is dropped rather than waited for.
class Clients : public PollSource, public ConnectionSink {
public:
    Clients(const bridge::Config& conf, ValueStore& values) : m_conf(conf), m_values(values) {
        for (int i = 0; i < kMaxClients; ++i) { m_c[i].fd = -1; m_c[i].out = NULL; m_c[i].len = m_c[i].cap = 0; }
    }

    int count() const {
        int n = 0;
        for (int i = 0; i < kMaxClients; ++i) if (m_c[i].fd >= 0) ++n;
        return n;
    }

    // A connected phone: hello and snapshot go out first. Refused (and told so) when all places are taken.
    virtual void connected(int fd, const char* name, const char* how, long now) {
        int slot = -1;
        for (int i = 0; i < m_conf.maxClients && i < kMaxClients; ++i) if (m_c[i].fd < 0) { slot = i; break; }
        if (slot < 0) {
            const char busy[] = "{\"type\":\"error\",\"msg\":\"too many phones\"}\n";
            ssize_t w = send(fd, busy, sizeof(busy) - 1, 0);
            (void)w;
            close(fd);
            blog("phone %s refused: already %d connected", name, count());
            return;
        }
        setNonBlocking(fd);
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        Client& c = m_c[slot];
        c.fd = fd;
        c.out = NULL;
        c.len = c.cap = 0;
        c.lastSentMs = now;
        snprintf(c.peer, sizeof(c.peer), "%s", name);
        blog("phone %s connected (%s)", name, how);
        char hello[256];
        int n = bridge::helloLine(hello, sizeof(hello), BRIDGE_VERSION, now);
        if (n > 0 && queue(c, hello, (size_t)n)) sendSnapshot(c, now);
    }

    void closeAll(const char* why) {
        for (int i = 0; i < kMaxClients; ++i) closeOne(m_c[i], why);
    }

    // Moves the queued CDS changes to every phone (a new snapshot when some were lost), pings the quiet ones and
    // sends what it can without waiting.
    void sendAll(long now) {
        static Event batch[kQueueSize];
        bool overflow;
        int count = m_values.take(batch, &overflow);
        for (int k = 0; k < kMaxClients; ++k) {
            Client& c = m_c[k];
            if (c.fd < 0) continue;
            if (overflow) { sendSnapshot(c, now); continue; }      // the snapshot holds the newest of everything
            for (int i = 0; i < count && c.fd >= 0; ++i) {
                int n = bridge::cdsLine(m_line, sizeof(m_line), kProperties[batch[i].prop], batch[i].value, batch[i].t);
                if (n > 0 && (size_t)n < sizeof(m_line)) queue(c, m_line, (size_t)n);
            }
        }
        for (int i = 0; i < kMaxClients; ++i) {
            Client& c = m_c[i];
            if (c.fd < 0) continue;
            if (c.len == 0 && now - c.lastSentMs >= kPingAfterMs) {
                char ping[64];
                int n = bridge::pingLine(ping, sizeof(ping), now);
                if (n > 0) queue(c, ping, (size_t)n);
            }
            flush(c, now);
        }
    }

    virtual int fill(struct pollfd* fds, int max) {
        int n = 0;
        for (int i = 0; i < kMaxClients && n < max; ++i) {
            if (m_c[i].fd < 0) continue;
            fds[n].fd = m_c[i].fd;
            fds[n].events = (short)(POLLIN | (m_c[i].len ? POLLOUT : 0));
            fds[n].revents = 0;
            ++n;
        }
        return n;
    }

    // What the phone sends is not used (protocol: optional); only a closed or broken connection matters.
    virtual void ready(const struct pollfd* fds, int count, long) {
        for (int k = 0; k < count; ++k) {
            if (!(fds[k].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            Client* c = find(fds[k].fd);
            if (!c) continue;
            char in[512];
            ssize_t got = recv(c->fd, in, sizeof(in), 0);
            if (got == 0) closeOne(*c, "closed by the phone");
            else if (got < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) closeOne(*c, strerror(errno));
        }
    }

private:
    struct Client {
        int fd;
        char* out;          // bytes waiting to be sent
        size_t len, cap;
        long lastSentMs;
        char peer[48];
    };

    Client* find(int fd) {
        for (int i = 0; i < kMaxClients; ++i) if (m_c[i].fd >= 0 && m_c[i].fd == fd) return &m_c[i];
        return NULL;
    }

    void closeOne(Client& c, const char* why) {
        if (c.fd < 0) return;
        blog("phone %s disconnected: %s", c.peer, why);
        close(c.fd);
        c.fd = -1;
        free(c.out);
        c.out = NULL;
        c.len = c.cap = 0;
    }

    bool queue(Client& c, const char* data, size_t n) {
        if (c.len + n > kClientBufMax) { closeOne(c, "not reading (send buffer full)"); return false; }
        if (c.len + n > c.cap) {
            size_t cap = c.cap ? c.cap : 4096;
            while (cap < c.len + n) cap *= 2;
            char* p = static_cast<char*>(realloc(c.out, cap));
            if (!p) { closeOne(c, "out of memory"); return false; }
            c.out = p;
            c.cap = cap;
        }
        memcpy(c.out + c.len, data, n);
        c.len += n;
        return true;
    }

    void flush(Client& c, long now) {
        while (c.fd >= 0 && c.len > 0) {
#ifdef MSG_NOSIGNAL
            ssize_t w = ::send(c.fd, c.out, c.len, MSG_NOSIGNAL);
#else
            ssize_t w = ::send(c.fd, c.out, c.len, 0);       // SIGPIPE is blocked in the network thread
#endif
            if (w > 0) {
                memmove(c.out, c.out + w, c.len - (size_t)w);
                c.len -= (size_t)w;
                c.lastSentMs = now;
            } else if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                return;
            } else {
                closeOne(c, w == 0 ? "closed" : strerror(errno));
            }
        }
    }

    void sendSnapshot(Client& c, long now) {
        const char* values[kPropertyCount];
        static char copy[kPropertyCount][bridge::kValueBytes];
        m_values.snapshot(copy, values);
        int n = bridge::snapshotLine(m_line, sizeof(m_line), values, now);
        if (n > 0) queue(c, m_line, (size_t)n);
    }

    const bridge::Config& m_conf;
    ValueStore& m_values;
    Client m_c[kMaxClients];
    char m_line[kPropertyCount * (bridge::kValueBytes + 48) + 256];   // big enough for a snapshot of every property
    Clients(const Clients&);
    Clients& operator=(const Clients&);
};

} // namespace

#endif
