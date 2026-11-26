// The iDrive app framework (libAppFramework.A.so), declared by hand, and the Runnable stand-in.
//
// The framework has no public headers. These declarations only have to produce the same mangled names as the
// exports of both head units' libAppFramework.A.so (so the namespace must keep the framework's own name); the
// layouts are the ones GaugeApp and CdsScan use on the car. Part of drivelog_bridge.cpp's translation unit.
#ifndef DRIVELOG_BRIDGE_FRAMEWORK_HPP
#define DRIVELOG_BRIDGE_FRAMEWORK_HPP

#include <stddef.h>
#include <string.h>
#include <dlfcn.h>

#ifndef RTLD_DEFAULT
#define RTLD_DEFAULT ((void*)-2)   // QNX value
#endif

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

enum { kSlotOnError = 21, kSlotOnInit = 22, kSlotOnFinalize = 23, kVtblSlots = 34 };   // PluginApp vtable

// A bmw::Runnable built by hand: the framework's vtable copied, run() is ours. Posted to the PluginApp, it runs in
// the HMI's app thread. The objects are static, so the destructors do nothing.
class StandInRunnable {
public:
    StandInRunnable() : m_vptr(NULL) { memset(m_vtbl, 0, sizeof(m_vtbl)); }

    void init(void (*run)(void*)) {
        void** base = reinterpret_cast<void**>(dlsym(RTLD_DEFAULT, "_ZTVN3bmw8RunnableE"));
        if (base) memcpy(m_vtbl, base, sizeof(m_vtbl));
        else { m_vtbl[0] = NULL; m_vtbl[1] = NULL; }
        m_vtbl[2] = reinterpret_cast<void*>(&noop);
        m_vtbl[3] = reinterpret_cast<void*>(&noop);
        m_vtbl[4] = reinterpret_cast<void*>(run);
        m_vptr = m_vtbl + 2;
    }

    bmw::Runnable* runnable() { return reinterpret_cast<bmw::Runnable*>(this); }

private:
    static void noop(void*) {}
    void** m_vptr;              // must stay the first member: the framework reads the object as a Runnable
    void* m_vtbl[5];
    StandInRunnable(const StandInRunnable&);
    StandInRunnable& operator=(const StandInRunnable&);
};

} // namespace

#endif
