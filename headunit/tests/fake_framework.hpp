// Test stand-in for the head unit's libAppFramework.A.so: the bmw:: symbols the plugin calls, with a PluginApp whose
// vtable has the car's layout (34 slots; onError 21, onInit 22, onFinalize 23). It records what the plugin does.
// Not BMW's code: only the names and the slot numbers match.
#ifndef DRIVELOG_FAKE_FRAMEWORK_HPP
#define DRIVELOG_FAKE_FRAMEWORK_HPP
#include <map>
#include <string>
#include <vector>

namespace bmw {
class IDispatcher;
class Bundle;
class Parcel { public: int unused; };
enum RHMIInterfaceVersion { RHMI_INTERFACE_VERSION_1 = 1 };

template <typename T> class BasicString {
public:
    BasicString(const T* s);
    BasicString(const BasicString& other);
    ~BasicString();
    T* m_data;
};

class TextID {
public:
    explicit TextID(unsigned id);
    unsigned m_id, m_type;
};

class Runnable {
public:
    virtual ~Runnable();
    virtual void run() = 0;
};

template <typename R, typename A1, typename A2, typename A3, typename A4> struct Delegate {
    void* object;
    R (*stub)(void* object, A1 a1);
};
typedef Delegate<bool, const BasicString<unsigned short>&, void, void, void> CdsDelegate;
typedef Delegate<bool, const Parcel&, void, void, void> ActionDelegate;

#define FAKE_SLOT(n) virtual void slot##n();
class PluginApp {
public:
    PluginApp(IDispatcher&, BasicString<char> name, unsigned major, unsigned minor, RHMIInterfaceVersion,
              const Bundle&, const Bundle&);
    virtual ~PluginApp();                                                      // slots 0, 1
    FAKE_SLOT(2) FAKE_SLOT(3) FAKE_SLOT(4) FAKE_SLOT(5) FAKE_SLOT(6) FAKE_SLOT(7) FAKE_SLOT(8) FAKE_SLOT(9)
    FAKE_SLOT(10) FAKE_SLOT(11) FAKE_SLOT(12) FAKE_SLOT(13) FAKE_SLOT(14) FAKE_SLOT(15) FAKE_SLOT(16)
    FAKE_SLOT(17) FAKE_SLOT(18) FAKE_SLOT(19) FAKE_SLOT(20)
    virtual void onError(unsigned code, const BasicString<char>& text);       // 21
    virtual bool onInit();                                                     // 22
    virtual void onFinalize();                                                 // 23
    FAKE_SLOT(24) FAKE_SLOT(25) FAKE_SLOT(26) FAKE_SLOT(27) FAKE_SLOT(28) FAKE_SLOT(29) FAKE_SLOT(30)
    FAKE_SLOT(31) FAKE_SLOT(32) FAKE_SLOT(33)
    void post(Runnable* runnable, unsigned delayMs);
    void updateModel(unsigned modelId, const TextID& text);
    void addHandler(unsigned actionId, const ActionDelegate& handler);
    void cdsAddPropertyChangedEventHandler(BasicString<char>& property, unsigned id, const CdsDelegate& handler);
    std::string name;
};
#undef FAKE_SLOT
} // namespace bmw

struct FakeState {
    std::vector<bmw::Runnable*> posted;                 // not run yet
    std::map<std::string, bmw::CdsDelegate> cds;        // property -> handler
    std::vector<std::string> cdsOrder;
    std::map<unsigned, bmw::ActionDelegate> actions;
    std::map<unsigned, unsigned> modelText;             // textIdModel -> text id
    int baseOnInit, baseOnFinalize, destroyed;
    FakeState() : baseOnInit(0), baseOnFinalize(0), destroyed(0) {}
};
extern FakeState g_fake;
#endif
