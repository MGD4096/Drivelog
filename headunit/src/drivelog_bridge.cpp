// libDrivelogBridge.so - iDrive PluginApp that hands the car values Drivelog needs to the phone.
//
// It subscribes to the CDS properties of bridge_core.hpp and serves them on a TCP port of the car's Wi-Fi: one JSON
// object per line (hello, snapshot, cds, ping; headunit/README.md). A phone may call in (port 7777), and while none
// is connected the plugin calls the phones (port 7778). Read only: nothing is ever written to CDS.
//
//   NBT Evo (default)                 QNX 6.5,  /opt/hmi/ID5/data/ro/Plugins/DrivelogBridge, data /var/opt/drivelogbridge
//   EntryNav2 (-DBRIDGE_ENTRYNAV2)    Linux ARMv7 hard-float, /opt/hmi/data/ro/Plugins/DrivelogBridge,
//                                     data /var/local/data/DrivelogBridge
//
// One translation unit, built from parts with one job each (all in src/, included below):
//   bridge_core.hpp       pure logic: property list, JSON lines, config, address maths (host-tested)
//   bridge_framework.hpp  the iDrive framework declarations and the Runnable stand-in
//   bridge_platform.hpp   paths, time, files, USB sticks, ScopedLock
//   bridge_log.hpp        the log
//   bridge_guard.hpp      the crash guard
//   bridge_values.hpp     ValueStore and NetworkStatus: what the HMI and network threads share
//   bridge_clients.hpp    the connected phones; the PollSource and ConnectionSink interfaces
//   bridge_listener.hpp   the listening socket on the Wi-Fi address
//   bridge_caller.hpp     calling the phones while none is connected
//   bridge_network.hpp    the network thread that runs the parts above
// This file: the CDS callbacks, the screen, and the app object the HMI creates.
//
// Threads: CDS callbacks and the screen run in the HMI's app thread (Runnables posted to the PluginApp); the
// network has its own thread. They share only ValueStore and NetworkStatus, each behind its own lock.
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <new>
#include <sys/stat.h>
#include "bridge_core.hpp"
#include "bridge_framework.hpp"
#include "bridge_platform.hpp"
#include "bridge_log.hpp"
#include "bridge_guard.hpp"
#include "bridge_values.hpp"
#include "bridge_network.hpp"

namespace {

const unsigned kSubscribeDelayMs = 1500;    // after onInit, as the plugins that work on the car do
const unsigned kTickMs = 1000;              // screen texts, crash guard

// The screen (DrivelogBridge.xml): three lines, each a textIdModel switched between texts of the text database.
enum { kModelState = 41, kModelPhones = 42, kModelValues = 43, kActionFirst = 31, kLines = 3 };
enum { kTextState = 1000, kTextPhones = 2000, kTextValues = 3000 };

bridge::Config g_conf;
ValueStore g_values;
NetworkStatus g_status;
Network g_network(g_conf, g_values, g_status);
CrashGuard g_guard;
bmw::PluginApp* g_app = NULL;

// ---- CDS ----------------------------------------------------------------------------------------------------------
int g_logged[kPropertyCount];          // values written to the log per property (the first few only)

void handleCds(int prop, const bmw::BasicString<unsigned short>& value) {
    char text[bridge::kValueBytes];
    bridge::utf16ToUtf8(value.raw(), text, sizeof(text));
    if (!bridge::looksLikeJsonObject(text)) {
        if (g_logged[prop]++ < 3) blog("CDS %s: not a JSON object, not sent: '%.200s'", kProperties[prop], text);
        return;
    }
    if (!g_values.put(prop, text, monoMs())) return;
    if (g_logged[prop]++ < 3) blog("CDS %s = %.300s", kProperties[prop], text);
    g_network.wake();
}

// The delegate does not say which property changed: one stub per property.
template <int P> bool onCds(void*, const bmw::BasicString<unsigned short>& v) {
    handleCds(P, v);
    return true;
}
typedef bool (*CdsStub)(void*, const bmw::BasicString<unsigned short>&);
const CdsStub kStubs[] = { &onCds<0>, &onCds<1>, &onCds<2>, &onCds<3>, &onCds<4>, &onCds<5>, &onCds<6>,
                           &onCds<7>, &onCds<8>, &onCds<9>, &onCds<10>, &onCds<11>, &onCds<12>, &onCds<13>,
                           &onCds<14>, &onCds<15>, &onCds<16>, &onCds<17> };
typedef char kOneStubPerProperty[sizeof(kStubs) / sizeof(kStubs[0]) == (size_t)kPropertyCount ? 1 : -1];

// ---- the screen ----------------------------------------------------------------------------------------------------
StandInRunnable g_subscribe, g_tick;
unsigned g_shown[kLines];

void showScreen(bmw::PluginApp* app, bool force) {
    int state, phones;
    g_status.get(&state, &phones);
    unsigned t[kLines];
    t[0] = kTextState + (unsigned)state;
    t[1] = kTextPhones + (unsigned)phones;
    t[2] = kTextValues + (unsigned)g_values.count();
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
    g_guard.tick(monoMs());
    app->post(g_tick.runnable(), kTickMs);
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
    g_tick.init(&tickRun);
    app->post(g_tick.runnable(), kTickMs);
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
    g_guard.started(monoMs());
    g_values.reset();
    for (int i = 0; i < kPropertyCount; ++i) g_logged[i] = 0;
    g_status.set(kStateStarting, 0);
    loadConfig();
    showScreen(self, true);
    g_network.start();
    g_subscribe.init(&subscribeRun);
    self->post(g_subscribe.runnable(), kSubscribeDelayMs);
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
    g_network.stop();
    self->onFinalize();
}

bmw::PluginApp* createApp(bmw::IDispatcher& dispatcher, const char* name, const bmw::Bundle& params,
                          const bmw::Bundle& config) {
    mkdir(kDataDir, 0755);
    blog("create(%s) %s", name ? name : "(null)", BRIDGE_VERSION);
    if (fileExists(kDisableA) || fileExists(kDisableB)) { blog("turned off by app.disable"); return NULL; }
    if (g_app) { blog("create: already running"); return NULL; }
    if (!g_guard.allowsStart()) return NULL;
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
    g_network.stop();                    // in case onFinalize was not called
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
