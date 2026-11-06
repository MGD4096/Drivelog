// prova sul PC della logica dei plugin (scaricamento, caselle, titoli) con un finto framework bmw
#include "../ponte_plugin.cpp"
extern "C" int printf(const char*, ...);
extern "C" int fflush(void*);
extern "C" int* __errno_location(void);
extern "C" int* __get_errno_ptr(void) { return __errno_location(); }
extern "C" void bsCrea(void* self, const char* s) __asm__("_ZN3bmw11BasicStringIcEC1EPKc");
extern "C" void bsCrea(void* self, const char* s) { *(const char**)self = s; }
extern "C" void bsDistruggi(void* self) __asm__("_ZN3bmw11BasicStringIcED1Ev");
extern "C" void bsDistruggi(void*) {}
namespace bmw {
BufferStream::~BufferStream() {}
Image::Image(const unsigned char* d, u32 n) { memcpy(m_storage, &n, 4); memcpy(m_storage + 4, d + 1, 3); }
PluginApp::PluginApp(IDispatcher&, BasicString<char>, u32, u32, RHMIInterfaceVersion, const Bundle&, const Bundle&) {}
void PluginApp::post(Runnable*, u32) {}
void PluginApp::updateModel(u32 id, const char* t) { printf("  updateModel(%u, testo \"%s\")\n", id, t); }
void PluginApp::updateModel(u32 id, const Image& im) { u32 n; memcpy(&n, &im, 4); printf("  updateModel(%u, immagine %u byte, %.3s)\n", id, n, (const char*)&im + 4); }
bool PluginApp::onInit() { return true; }
void PluginApp::addHandler(u32 id, const DelegatoAzione& d) { printf("  addHandler(%u, oggetto %lu)\n", id, (unsigned long)d.oggetto); }
void PluginApp::onFinalize() {}
#if DATI_AUTO
// finto CDS: si tengono i delegati per chiamarli come farebbe l'iDrive
DelegatoCds g_delegati[32]; const char* g_nomiCds[32]; u32 g_nDelegati = 0;
void PluginApp::cdsAddPropertyChangedEventHandler(BasicString<char>& p, u32, const DelegatoCds& d) {
    g_nomiCds[g_nDelegati] = p.raw(); g_delegati[g_nDelegati++] = d;
}
#endif
}
#if DATI_AUTO
// valore CDS come lo manda l'iDrive: testo JSON in UTF-16
void cdsDaIdrive(const char* nome, const char* json) {
    static u16 buf[512]; u32 n = 0;
    while (json[n] && n < 511) { buf[n] = (unsigned char)json[n]; ++n; }
    buf[n] = 0;
    u16* p = buf;
    for (u32 i = 0; i < bmw::g_nDelegati; ++i)
        if (!strncmp(bmw::g_nomiCds[i], nome, 64)) bmw::g_delegati[i].funzione(bmw::g_delegati[i].oggetto, *(const bmw::BasicString<u16>*)&p);
}
#endif
int main() {
    static unsigned char mem[0x200];
    g_app = (bmw::PluginApp*)mem;
    preparaCaselle();
#if defined(PLUGIN_NOTIZIE) && SCHEDA_LATERALE
    printf("== appOnInit (registrazione delle righe)\n");
    for (u32 i = 0; i < kMaxNotizie; ++i) { bmw::DelegatoAzione d; d.oggetto = (void*)(unsigned long)i; d.funzione = &rigaScelta; g_app->addHandler(kAzioneScelta0 + i, d); }
#endif
    printf("== schermata di attesa\n"); schermataAttesa(); svuotaCaselle();
    g_telefono = ip4(127, 0, 0, 1);
    u32 t = trovaTelefono();
    printf("== trovaTelefono -> %s\n", t ? "127.0.0.1" : "NIENTE");
    int codice = 0;
    void* ponte = scarica(t, "/ponte", &codice);
    printf("== /ponte codice %d: %s", codice, ponte ? testo(ponte) : "(nulla)\n");
    bool ok = aggiornaDati(t, testo(ponte));
    printf("== aggiornaDati -> %d, epoch fatto %u\n", ok, g_epochFatto);
    svuotaCaselle();
#if defined(PLUGIN_NOTIZIE) && SCHEDA_LATERALE
    printf("== riga 3 selezionata\n");
    static char finto[64];
    rigaScelta((void*)2, *(const bmw::Parcel*)finto);
    printf("== di nuovo la riga 3 (niente)\n");
    rigaScelta((void*)2, *(const bmw::Parcel*)finto);
#endif
#if DATI_AUTO
    printf("== dati dell'auto\n");
    registraCds();
    printf("  proprieta' registrate: %u\n", bmw::g_nDelegati);
    cdsDaIdrive("driving.odometer", "{\"odometer\":12345}");
    cdsDaIdrive("sensors.fuel", "{\"fuel\":{\"tanklevel\":30,\"range\":420,\"reserve\":0}}");
    cdsDaIdrive("sensors.doors", "{\"doors\":{\"driverFront\":1,\"passengerFront\":0}}");
    cdsDaIdrive("driving.odometer", "{\"odometer\":12346}");      // il piu' nuovo sostituisce quello non ancora mandato
    mandaDatiAuto(t);
    cdsDaIdrive("sensors.fuel", "{\"fuel\":{\"tanklevel\":30,\"range\":420,\"reserve\":0}}");   // uguale: non si rimanda
    cdsDaIdrive("controls.sunroof", "{\"sunroof\":{\"status\":0,\"tiltPosition\":3}}");
    mandaDatiAuto(t);
    printf("  inviate al telefono:\n"); fflush(0);
    { void* f = fopen("/tmp/finto_telefono_auto.txt", "r"); char r[600]; while (f && fgets(r, sizeof(r), f)) printf("    %s", r); if (f) fclose(f); }
    // v9: il telefono riparte (avvio= cambia): gli ultimi valori tornano in coda e si rimandano tutti
    { void* f = fopen("/tmp/finto_telefono_auto.txt", "w"); if (f) fclose(f); }
    rimandaDatiAuto("IDRIVEPONTE x avvio=1000");
    rimandaDatiAuto("IDRIVEPONTE x avvio=2000");
    mandaDatiAuto(t);
    printf("  rimandati dopo la ripartenza del telefono:\n"); fflush(0);
    { void* f = fopen("/tmp/finto_telefono_auto.txt", "r"); char r[600]; while (f && fgets(r, sizeof(r), f)) printf("    %s", r); if (f) fclose(f); }
#endif
#if defined(PLUGIN_AUTO)
    printf("== Stato vettura: epoch nuovo ma righe con la stessa versione (non deve riscaricare le pagine)\n");
    g_epochFatto = 0;
    printf("  versioni gia' portate: %u %u\n", g_versioneRiga[0], g_versioneRiga[1]);
    aggiornaDati(t, "IDRIVEPONTE x vettura=778"); svuotaCaselle();
#endif
    printf("== secondo giro con lo stesso epoch (non deve scaricare niente)\n");
    aggiornaDati(t, testo(ponte)); svuotaCaselle();
    void* no = scarica(t, "/notizia/9.png", &codice);
    printf("== /notizia/9.png -> %p codice %d\n", no, codice);
    return 0;
}
