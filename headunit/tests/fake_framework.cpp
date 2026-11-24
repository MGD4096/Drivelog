#include "fake_framework.hpp"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

FakeState g_fake;

namespace bmw {
template <> BasicString<char>::BasicString(const char* s) : m_data(strdup(s ? s : "")) {}
template <> BasicString<char>::BasicString(const BasicString& o) : m_data(strdup(o.m_data)) {}
template <> BasicString<char>::~BasicString() { free(m_data); }

static unsigned short* copy16(const unsigned short* s) {
    size_t n = 0;
    while (s[n]) ++n;
    unsigned short* p = static_cast<unsigned short*>(malloc((n + 1) * sizeof(unsigned short)));
    memcpy(p, s, (n + 1) * sizeof(unsigned short));
    return p;
}
template <> BasicString<unsigned short>::BasicString(const unsigned short* s) : m_data(copy16(s)) {}
template <> BasicString<unsigned short>::BasicString(const BasicString& o) : m_data(copy16(o.m_data)) {}
template <> BasicString<unsigned short>::~BasicString() { free(m_data); }

TextID::TextID(unsigned id) : m_id(id), m_type(0) {}
Runnable::~Runnable() {}

PluginApp::PluginApp(IDispatcher&, BasicString<char> n, unsigned, unsigned, RHMIInterfaceVersion, const Bundle&,
                     const Bundle&) : name(n.m_data) {}
PluginApp::~PluginApp() { g_fake.destroyed++; }
#define FAKE_SLOT_DEF(n) void PluginApp::slot##n() { printf("FAKE: unexpected call of vtable slot %d\n", n); abort(); }
FAKE_SLOT_DEF(2) FAKE_SLOT_DEF(3) FAKE_SLOT_DEF(4) FAKE_SLOT_DEF(5) FAKE_SLOT_DEF(6) FAKE_SLOT_DEF(7)
FAKE_SLOT_DEF(8) FAKE_SLOT_DEF(9) FAKE_SLOT_DEF(10) FAKE_SLOT_DEF(11) FAKE_SLOT_DEF(12) FAKE_SLOT_DEF(13)
FAKE_SLOT_DEF(14) FAKE_SLOT_DEF(15) FAKE_SLOT_DEF(16) FAKE_SLOT_DEF(17) FAKE_SLOT_DEF(18) FAKE_SLOT_DEF(19)
FAKE_SLOT_DEF(20) FAKE_SLOT_DEF(24) FAKE_SLOT_DEF(25) FAKE_SLOT_DEF(26) FAKE_SLOT_DEF(27) FAKE_SLOT_DEF(28)
FAKE_SLOT_DEF(29) FAKE_SLOT_DEF(30) FAKE_SLOT_DEF(31) FAKE_SLOT_DEF(32) FAKE_SLOT_DEF(33)
void PluginApp::onError(unsigned, const BasicString<char>&) {}
bool PluginApp::onInit() { g_fake.baseOnInit++; return true; }
void PluginApp::onFinalize() { g_fake.baseOnFinalize++; }

void PluginApp::post(Runnable* r, unsigned) { g_fake.posted.push_back(r); }
void PluginApp::updateModel(unsigned id, const TextID& t) { g_fake.modelText[id] = t.m_id; }
void PluginApp::addHandler(unsigned id, const ActionDelegate& d) { g_fake.actions[id] = d; }
void PluginApp::cdsAddPropertyChangedEventHandler(BasicString<char>& p, unsigned, const CdsDelegate& d) {
    g_fake.cds[p.m_data] = d;
    g_fake.cdsOrder.push_back(p.m_data);
}
} // namespace bmw
