// The network thread: follows the Wi-Fi address, listens, calls the phones while none is connected, and sends them
// the CDS changes. A slow or dead phone can never block the HMI: sockets are non-blocking and a phone that does not
// read is dropped. The HMI thread only calls start(), wake() and stop(). Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_NETWORK_HPP
#define DRIVELOG_BRIDGE_NETWORK_HPP

#include <errno.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include "bridge_caller.hpp"

namespace {

const long kAddressCheckMs = 3000;          // how often the Wi-Fi address is looked up again
const int  kPollMs = 500;                   // the longest the thread sleeps (and stop() waits)

// A self-pipe: a CDS value or the stop request wakes poll() at once.
class WakePipe : public PollSource {
public:
    WakePipe() { m_fd[0] = m_fd[1] = -1; }

    bool open() {
        if (pipe(m_fd) != 0) { blog("pipe: %s", strerror(errno)); m_fd[0] = m_fd[1] = -1; return false; }
        setNonBlocking(m_fd[0]);
        setNonBlocking(m_fd[1]);
        return true;
    }

    void close() {
        if (m_fd[0] >= 0) ::close(m_fd[0]);
        if (m_fd[1] >= 0) ::close(m_fd[1]);
        m_fd[0] = m_fd[1] = -1;
    }

    void wake() {
        if (m_fd[1] < 0) return;
        char b = 1;
        ssize_t w = write(m_fd[1], &b, 1);     // non-blocking: a full pipe already means "wake up"
        (void)w;
    }

    virtual int fill(struct pollfd* fds, int max) {
        if (m_fd[0] < 0 || max < 1) return 0;
        fds[0].fd = m_fd[0];
        fds[0].events = POLLIN;
        fds[0].revents = 0;
        return 1;
    }

    virtual void ready(const struct pollfd* fds, int count, long) {
        if (count < 1 || !fds[0].revents) return;
        char drain[64];
        while (read(m_fd[0], drain, sizeof(drain)) > 0) {}
    }

private:
    int m_fd[2];
    WakePipe(const WakePipe&);
    WakePipe& operator=(const WakePipe&);
};

class Network {
public:
    Network(const bridge::Config& conf, ValueStore& values, NetworkStatus& status)
        : m_status(status), m_clients(conf, values), m_listener(conf, m_clients), m_caller(conf, m_clients),
          m_stop(false), m_running(false), m_loggedNoWifi(false) { pthread_mutex_init(&m_stopMutex, NULL); }

    void start() {
        if (m_running) return;
        if (!m_wake.open()) return;
        setStop(false);
        if (pthread_create(&m_thread, NULL, &Network::main, this) != 0) {
            blog("cannot start the network thread");
            m_wake.close();
            return;
        }
        m_running = true;
    }

    void stop() {
        if (!m_running) return;
        setStop(true);
        m_wake.wake();
        pthread_join(m_thread, NULL);           // poll() waits at most kPollMs
        m_running = false;
        m_wake.close();
    }

    // A new CDS value is waiting.
    void wake() { m_wake.wake(); }

private:
    static void* main(void* self) {
        static_cast<Network*>(self)->run();
        return NULL;
    }

    void setStop(bool on) { ScopedLock lock(m_stopMutex); m_stop = on; }
    bool stopRequested() { ScopedLock lock(m_stopMutex); return m_stop; }

    // Closes the calls, the phones and the listening socket, in that order.
    void closeAll(const char* why) {
        m_caller.stop();
        m_clients.closeAll(why);
        m_listener.stop(why);
    }

    // Every kAddressCheckMs: listen on the current Wi-Fi address, moving when it changes. Returns the state.
    int followAddress(int state) {
        ListenInfo where;
        m_listener.findAddress(where);
        if (strcmp(where.addr, m_listener.info().addr) == 0 && (m_listener.open() || !where.addr[0])) return state;
        if (m_listener.open()) closeAll(where.addr[0] ? "Wi-Fi address changed" : "Wi-Fi gone");
        if (where.addr[0]) {
            m_loggedNoWifi = false;
            return m_listener.start(where);
        }
        if (!m_loggedNoWifi) blog("no Wi-Fi address yet (car Wi-Fi off?): checking every %ld s", kAddressCheckMs / 1000);
        m_loggedNoWifi = true;
        return kStateNoWifi;
    }

    void run() {
        sigset_t block;                       // a write to a closed connection must not kill the HMI
        sigemptyset(&block);
        sigaddset(&block, SIGPIPE);
        pthread_sigmask(SIG_BLOCK, &block, NULL);
        m_caller.reset(monoMs());
        m_loggedNoWifi = false;
        long lastCheck = -kAddressCheckMs;
        int state = kStateNoWifi;
        // In the order their sockets are handled each turn.
        PollSource* const sources[] = { &m_wake, &m_listener, &m_clients, &m_caller };
        const int nSources = (int)(sizeof(sources) / sizeof(sources[0]));
        while (!stopRequested()) {
            long now = monoMs();
            if (now - lastCheck >= kAddressCheckMs) {
                lastCheck = now;
                state = followAddress(state);
            }
            m_caller.tick(now, m_listener, m_clients.count());
            struct pollfd fds[2 + kMaxClients + kMaxProbes];
            int first[sizeof(sources) / sizeof(sources[0])], count[sizeof(sources) / sizeof(sources[0])];
            int nf = 0;
            for (int s = 0; s < nSources; ++s) {
                first[s] = nf;
                count[s] = sources[s]->fill(fds + nf, (int)(sizeof(fds) / sizeof(fds[0])) - nf);
                nf += count[s];
            }
            int r = poll(fds, (nfds_t)nf, kPollMs);
            now = monoMs();
            if (r < 0 && errno != EINTR) { blog("poll: %s", strerror(errno)); usleep(200000); }
            if (r > 0) for (int s = 0; s < nSources; ++s) sources[s]->ready(fds + first[s], count[s], now);
            m_clients.sendAll(now);
            if (state == kStateListening || state == kStateConnected)
                state = m_clients.count() > 0 ? kStateConnected : kStateListening;
            m_status.set(state, m_clients.count());
        }
        closeAll("plugin stopped");
        m_status.set(kStateStopped, m_clients.count());
    }

    NetworkStatus& m_status;
    WakePipe m_wake;
    Clients m_clients;
    Listener m_listener;
    PhoneCaller m_caller;
    pthread_mutex_t m_stopMutex;
    bool m_stop;                // set by the HMI thread, read by the network thread (under m_stopMutex)
    bool m_running;             // HMI thread only
    bool m_loggedNoWifi;        // network thread only
    pthread_t m_thread;
    Network(const Network&);
    Network& operator=(const Network&);
};

} // namespace

#endif
