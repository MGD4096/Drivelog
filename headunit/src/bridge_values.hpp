// What the HMI thread and the network thread share, each behind its own lock held for a few instructions:
//  - ValueStore: the latest value of every CDS property and the changes not sent yet (written by the CDS callbacks,
//    read by the network thread);
//  - NetworkStatus: what the screen shows (written by the network thread, read by the HMI thread).
// Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_VALUES_HPP
#define DRIVELOG_BRIDGE_VALUES_HPP

#include <string.h>
#include <pthread.h>
#include "bridge_core.hpp"
#include "bridge_platform.hpp"

namespace {

using bridge::kPropertyCount;
using bridge::kProperties;

enum { kQueueSize = 256 };

// One change of a property, as queued for the phones.
struct Event { int prop; long t; char value[bridge::kValueBytes]; };

class ValueStore {
public:
    ValueStore() { pthread_mutex_init(&m_mutex, NULL); reset(); }

    // Forgets everything (a new start of the plugin).
    void reset() {
        ScopedLock lock(m_mutex);
        for (int i = 0; i < kPropertyCount; ++i) m_has[i] = false;
        m_count = 0;
        m_head = m_len = 0;
        m_overflow = false;
    }

    // Stores [text] as the value of [prop]; true when it differs from the last one (then it is queued too). When the
    // queue is full the oldest change is dropped and the phones get a new snapshot instead.
    bool put(int prop, const char* text, long now) {
        ScopedLock lock(m_mutex);
        if (m_has[prop] && strcmp(m_last[prop], text) == 0) return false;
        if (!m_has[prop]) ++m_count;
        m_has[prop] = true;
        copyValue(m_last[prop], text);
        if (m_len == kQueueSize) { m_head = (m_head + 1) % kQueueSize; --m_len; m_overflow = true; }
        Event& e = m_queue[(m_head + m_len) % kQueueSize];
        e.prop = prop;
        e.t = now;
        copyValue(e.value, text);
        ++m_len;
        return true;
    }

    // How many properties have a value.
    int count() {
        ScopedLock lock(m_mutex);
        return m_count;
    }

    // Copies the latest values into [copy]; values[i] points into it, or is NULL when property i has none yet.
    void snapshot(char (*copy)[bridge::kValueBytes], const char** values) {
        ScopedLock lock(m_mutex);
        for (int i = 0; i < kPropertyCount; ++i) {
            if (m_has[i]) { memcpy(copy[i], m_last[i], bridge::kValueBytes); values[i] = copy[i]; }
            else values[i] = NULL;
        }
    }

    // Moves the queued changes (at most kQueueSize) into [batch]; [overflow] says some were lost.
    int take(Event* batch, bool* overflow) {
        ScopedLock lock(m_mutex);
        int count = m_len;
        for (int i = 0; i < count; ++i) batch[i] = m_queue[(m_head + i) % kQueueSize];
        m_head = (m_head + count) % kQueueSize;
        m_len = 0;
        *overflow = m_overflow;
        m_overflow = false;
        return count;
    }

private:
    static void copyValue(char* to, const char* from) { snprintf(to, bridge::kValueBytes, "%s", from); }

    pthread_mutex_t m_mutex;
    char  m_last[kPropertyCount][bridge::kValueBytes];
    bool  m_has[kPropertyCount];
    int   m_count;
    Event m_queue[kQueueSize];
    int   m_head, m_len;
    bool  m_overflow;
    ValueStore(const ValueStore&);
    ValueStore& operator=(const ValueStore&);
};

// The network's state as the screen shows it (line 1: text kTextState + state).
enum State { kStateStarting = 0, kStateNoWifi = 1, kStateListening = 2, kStateConnected = 3, kStatePortBusy = 4,
             kStateStopped = 5 };

class NetworkStatus {
public:
    NetworkStatus() : m_state(kStateStarting), m_phones(0) { pthread_mutex_init(&m_mutex, NULL); }

    void set(int state, int phones) {
        ScopedLock lock(m_mutex);
        m_state = state;
        m_phones = phones;
    }

    void get(int* state, int* phones) {
        ScopedLock lock(m_mutex);
        *state = m_state;
        *phones = m_phones;
    }

private:
    pthread_mutex_t m_mutex;
    int m_state;
    int m_phones;
    NetworkStatus(const NetworkStatus&);
    NetworkStatus& operator=(const NetworkStatus&);
};

} // namespace

#endif
