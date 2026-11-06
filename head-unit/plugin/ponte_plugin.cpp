// MINI Plus / BMW Plus / Supra Plus per iDrive - v9 - plugin compilati (clang) per NBT EVO ID6
// (MINI F5x/F60, BMW con NBT EVO, Toyota GR Supra J29).
// v6: il testo "in attesa" della lista si legge da attesa.txt nella cartella del plugin (scritto dalla chiavetta
//     secondo la marca scelta), cosi' le stesse librerie valgono per tutte e tre le app.
// v8: sesto plugin, Stato auto (libAuto.so): la pagina con autonomia, km, viaggio, temperature e aperture che l'app
//     disegna coi dati letti dal plugin Meteo; si aggiorna ogni 10 secondi invece che ogni 30.
// v9: Stato vettura: il plugin Auto diventa un elenco di due voci (Riepilogo, Porte finestrini e tetto) dalle
//     pagine /vettura/...; dopo il titolo di ogni riga un TAB e la versione: pagina e miniatura si riscaricano solo
//     quando la versione cambia. Meteo: se il telefono riparte da zero (avvio= in /ponte cambia) rimanda tutti gli
//     ultimi dati dell'auto, anche quelli fermi (porte chiuse, km a motore spento).
// Lo stesso sorgente produce sei plugin:
//   pagina singola : -DPLUGIN_METEO (libMeteo.so), -DPLUGIN_CARBURANTI (libCarburanti.so), -DPLUGIN_TRAFFICO (libTraffico.so)
//   elenco         : -DPLUGIN_NOTIZIE (libNotizie.so), -DPLUGIN_LOCALI (libLocali.so, percorsi /locali/...),
//                    -DPLUGIN_AUTO (libAuto.so, Stato vettura, percorsi /vettura/..., due righe)
//
// Cosa fanno: trovano il telefono sul Wi-Fi della macchina (tabella ARP, altrimenti scansione 172.16.224.x),
// chiedono all'app del telefono (MINI Plus, BMW Plus o Supra Plus, porta 8765) le pagine gia' pronte e aggiornano la schermata da soli:
//   Meteo   : GET /meteo.png                     -> raImageModel 12
//   Notizie : GET /notizie.txt                   -> titoli dei pulsanti (raDataModel 41..48)
//             GET /mini/<i>.png                  -> miniature dei pulsanti (raImageModel 61..68)
//             GET /notizia/<i>.png               -> pagina della notizia aperta (raImageModel 51..58)
//             (v5: niente piu' scheda accanto alla lista: la barra di selezione dell'iDrive le passava sopra;
//              resta nel codice dietro SCHEDA_LATERALE 0)
//             GET /anteprima/<i>.png             -> scheda accanto alla lista (raImageModel 71), cambiata
//                                                   quando una riga viene selezionata (selectAction -> raAction 20..27)
//   Auto    : GET /vettura/notizie.txt, /vettura/mini/<i>.png, /vettura/notizia/<i>.png (v9, come le Notizie, 2 righe)
//   Meteo   : anche i dati dell'auto (v7): chilometri, carburante, consumo, temperature, porte, finestrini e tetto
//             letti dal CDS dell'iDrive (lo stesso meccanismo di NBT-AAD per driving.gear) e mandati al telefono
//             con GET /auto?n=<proprieta'>&v=<valore JSON>: l'app ne fa il viaggio e lo stato al parcheggio.
//             /opt/aa/auto.disable presente -> il plugin non legge i dati dell'auto (meteo invariato).
// Il telefono scarica i dati con la sua connessione (open-meteo, ANSA): la testata non ha internet suo.
// Senza telefono resta la pagina "in attesa" (attesa.png nella cartella del plugin).
//
// Thread: il thread di rete scarica e lascia i dati in "caselle"; il ciclo del plugin (Runnable ogni secondo,
// sul thread dell'HMI) li prende e chiama updateModel. updateModel non viene mai chiamato dal thread di rete.
//
// ABI del framework RHMI (PluginApp, Runnable, vtable, pluginAppInfo) ricavata da NBT-AAD:
//   Required Notice: Copyright (c) 2026 nanaisu - NBT-AAD (https://github.com/Sq00ky/Supra-iDrive6-Android-Auto)
//   (PolyForm-Noncommercial-1.0.0) - uso personale, non commerciale.
// Layout di bmw::Image ricavato da libCoreFramework.A.so: { BasicString<char> path @0; BufferStream @4 (0x18); bool @0x1c }.
//
// Sicurezze (il plugin gira dentro HmiMain3D):
//   - /opt/aa/ponte.disable presente          -> Meteo e Notizie compilati non partono
//   - /dev/shmem/<nome>.avvii >= 3            -> non parte (3 avvii dell'HMI di fila senza arrivare a 30 s)
//   - log: /dev/shmem/<nome>.log e, se c'e' una chiavetta, anche <chiavetta>/<nome>.log

#if defined(PLUGIN_METEO)
#define PLUGIN_PAGINA 1
#define DATI_AUTO 1
#define NOME_PLUGIN "Meteo"
#define PAGINA_PNG "/meteo.png"
#define CHIAVE_EPOCH "meteo="
#elif defined(PLUGIN_CARBURANTI)
#define PLUGIN_PAGINA 1
#define NOME_PLUGIN "Carburanti"
#define PAGINA_PNG "/carburanti.png"
#define CHIAVE_EPOCH "carburanti="
#elif defined(PLUGIN_TRAFFICO)
#define PLUGIN_PAGINA 1
#define NOME_PLUGIN "Traffico"
#define PAGINA_PNG "/traffico.png"
#define CHIAVE_EPOCH "traffico="
#elif defined(PLUGIN_AUTO)
#define PLUGIN_ELENCO 1
#define NOME_PLUGIN "Auto"
#define PREFISSO "/vettura"
#define CHIAVE_EPOCH "vettura="
#define MAX_RIGHE 2
#define GIRO_SECONDI 10
#elif defined(PLUGIN_NOTIZIE)
#define PLUGIN_ELENCO 1
#define NOME_PLUGIN "Notizie"
#define PREFISSO ""
#define CHIAVE_EPOCH "notizie="
#elif defined(PLUGIN_LOCALI)
#define PLUGIN_ELENCO 1
#define NOME_PLUGIN "Locali"
#define PREFISSO "/locali"
#define CHIAVE_EPOCH "locali="
#else
#error "compilare con -DPLUGIN_METEO / _CARBURANTI / _TRAFFICO (pagina singola) oppure -DPLUGIN_NOTIZIE / _LOCALI / _AUTO (elenco)"
#endif
#ifndef GIRO_SECONDI
#define GIRO_SECONDI 30                    // attesa fra un controllo del telefono e il successivo
#endif
#define VERSIONE_PLUGIN "v9"

typedef unsigned int  u32;
typedef unsigned short u16;
typedef int ssize_t_q;
typedef long time_t_q;

extern "C" {
void* malloc(u32);
void* realloc(void*, u32);
void  free(void*);
void* memset(void*, int, u32);
void* memcpy(void*, const void*, u32);
u32   strlen(const char*);
int   strncmp(const char*, const char*, u32);
char* strstr(const char*, const char*);
int   snprintf(char*, u32, const char*, ...);
int   vsnprintf(char*, u32, const char*, __builtin_va_list);
void* fopen(const char*, const char*);
int   fclose(void*);
int   fprintf(void*, const char*, ...);
int   open(const char*, int, ...);
ssize_t_q read(int, void*, u32);
int   close(int);
int   access(const char*, int);
void* opendir(const char*);
void* readdir(void*);
int   closedir(void*);
time_t_q time(time_t_q*);
void* localtime_r(const time_t_q*, void*);
void* dlsym(void*, const char*);
int   getpid(void);
unsigned sleep(unsigned);
int   usleep(unsigned);
int*  __get_errno_ptr(void);
int   pthread_create(int*, const void*, void* (*)(void*), void*);
int   fcntl(int, int, ...);
void* popen(const char*, const char*);
int   pclose(void*);
char* fgets(char*, int, void*);
// libsocket.so.3 (gia' caricata nell'HMI: libAppFramework.A.so ne dipende)
int   socket(int, int, int);
int   connect(int, const void*, u32);
int   setsockopt(int, int, int, const void*, u32);
int   send(int, const void*, u32, int);
int   recv(int, void*, u32, int);
}

inline void* operator new(__SIZE_TYPE__, void* p) { return p; }   // placement new, senza <new>

namespace bmw {
class IDispatcher;
class Bundle;
class Parcel;
// Delegate<bool, const Parcel&>: oggetto + funzione (come NBT-AAD per i raAction)
struct DelegatoAzione { void* oggetto; bool (*funzione)(void* oggetto, const Parcel& argomenti); };
class Runnable;
enum RHMIInterfaceVersion { RHMI_INTERFACE_VERSION_1 = 1 };

template <typename T> class BasicString {
public:
    BasicString(const T* s);                       // _ZN3bmw11BasicStringIcEC1EPKc
    BasicString(const BasicString& other);         // _ZN3bmw11BasicStringIcEC1ERKS1_
    ~BasicString();                                // _ZN3bmw11BasicStringIcED1Ev
    const T* raw() const { return m_data; }
private:
    T* m_data;
};

// Delegate<bool, const BasicString<unsigned short>&>: valore di una proprieta' CDS come testo JSON UTF-16 (NBT-AAD)
struct DelegatoCds { void* oggetto; bool (*funzione)(void* oggetto, const BasicString<u16>& valore); };

class BufferStream {
public:
    ~BufferStream();                               // _ZN3bmw12BufferStreamD1Ev
private:
    unsigned char m_storage[0x18];
};

class Image {
public:
    Image(const unsigned char* data, u32 size);    // _ZN3bmw5ImageC1EPKhj (copia i byte nel BufferStream)
private:
    unsigned char m_storage[0x20];
};

const u32 kPluginAppStorage = 0x200;               // come NBT-AAD

class PluginApp {
public:
    PluginApp(IDispatcher& dispatcher, BasicString<char> name, u32 major, u32 minor,
              RHMIInterfaceVersion rhmi, const Bundle& params, const Bundle& config);
    void post(Runnable* runnable, u32 delayMs);                  // run() sul thread del plugin dopo delayMs
    void updateModel(u32 modelId, const char* text);             // _ZN3bmw9PluginApp11updateModelEjPKc
    void updateModel(u32 modelId, const Image& image);           // _ZN3bmw9PluginApp11updateModelEjRKNS_5ImageE
    void addHandler(u32 actionId, const DelegatoAzione& handler) __asm__("_ZN3bmw9PluginApp10addHandlerEjRKNS_8DelegateIbRKNS_6ParcelEvvvEE");
    // dati dell'auto (la stessa chiamata di NBT-AAD per driving.gear, di SecurityMenu per specialvehicle.menucontrol)
    void cdsAddPropertyChangedEventHandler(BasicString<char>& proprieta, u32 id, const DelegatoCds& handler)
        __asm__("_ZN3bmw9PluginApp33cdsAddPropertyChangedEventHandlerERNS_11BasicStringIcEEjRKNS_8DelegateIbRKNS1_ItEEvvvEE");
    bool onInit();
    void onFinalize();
private:
    unsigned char m_storage[kPluginAppStorage];
};
} // namespace bmw

namespace {

// ---- costanti ---------------------------------------------------------------------------------------------
const char* const kDisable    = "/opt/aa/ponte.disable";
const char* const kAvvii      = "/dev/shmem/" NOME_PLUGIN ".avvii";
const char* const kLogShmem   = "/dev/shmem/" NOME_PLUGIN ".log";
const char* const kDirPlugin  = "/opt/hmi/ID5/data/ro/Plugins/" NOME_PLUGIN;
const u32   kPortaPonte       = 8765;
const u32   kTickMs           = 500;      // v5: mezzo secondo, le immagini passano a turni
const u32   kSecondiSano      = 60;      // giri da 500 ms = 30 s
const u32   kImmaginiPerGiro  = 2;       // l'HMI perdeva aggiornamenti quando arrivavano 24 immagini tutte insieme
const u32   kBytePerGiro      = 300u * 1024u;
#define SCHEDA_LATERALE 0
const u32   kMaxAvvii         = 3;
const u32   kMaxRisposta      = 1536u * 1024u;      // una pagina PNG 1280x480 sta sotto i 300 KB
enum { kSlotOnError = 21, kSlotOnInit = 22, kSlotOnFinalize = 23, kVtblSlots = 34 };
enum { O_RDONLY_Q = 0, F_OK_Q = 0 };

#if defined(PLUGIN_PAGINA)
const u32 kModelloMeteo = 12;
#else
#ifndef MAX_RIGHE
#define MAX_RIGHE 8
#endif
const u32 kMaxNotizie     = MAX_RIGHE;   // righe della lista (8 notizie; 2 per lo Stato vettura)
const u32 kModelloTitolo0 = 41;    // 41..48 raDataModel  (testo dei pulsanti)
const u32 kModelloPagina0 = 51;    // 51..58 raImageModel (notizia aperta)
const u32 kModelloMini0   = 61;    // 61..68 raImageModel (miniatura del pulsante)
const u32 kModelloAnteprima = 71;  // raImageModel della scheda accanto alla lista
#if SCHEDA_LATERALE
const u32 kAzioneScelta0  = 20;    // 20..27 raAction: selectAction delle righe (riga selezionata)
#endif
#endif

bmw::PluginApp* g_app = 0;
void* g_vtbl[2 + kVtblSlots];
volatile bool g_finito = false;
u32 g_giri = 0;

// ---- log ---------------------------------------------------------------------------------------------------
const char* nomeDirent(void* ent) {
    const char* p = (const char*)ent;
    short len20 = *(const short*)(p + 18);
    if (len20 > 0 && len20 < 64 && strlen(p + 20) == (u32)len20) return p + 20;
    short len12 = *(const short*)(p + 10);
    if (len12 > 0 && len12 < 64 && strlen(p + 12) == (u32)len12) return p + 12;
    return 0;
}

char g_logChiavetta[160] = "";
time_t_q g_ultimaRicerca = 0;

void cercaChiavetta() {
    time_t_q ora = time(0);
    if (g_logChiavetta[0] || (g_ultimaRicerca && ora - g_ultimaRicerca < 20)) return;
    g_ultimaRicerca = ora;
    void* d = opendir("/mnt");
    if (!d) return;
    void* e;
    while ((e = readdir(d)) != 0) {
        const char* n = nomeDirent(e);
        if (n && strncmp(n, "umass", 5) == 0) { snprintf(g_logChiavetta, sizeof(g_logChiavetta), "/mnt/%s/%s.log", n, NOME_PLUGIN); break; }
    }
    closedir(d);
}

void scriviRiga(const char* path, const char* riga) {
    void* f = fopen(path, "a");
    if (!f) return;
    fprintf(f, "%s\n", riga);
    fclose(f);
}

void scriviLog(const char* fmt, ...) {
    char msg[400];
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    __builtin_va_end(ap);
    int tmv[16];
    memset(tmv, 0, sizeof(tmv));
    time_t_q t = time(0);
    localtime_r(&t, tmv);
    char riga[480];
    snprintf(riga, sizeof(riga), "%02d:%02d:%02d UTC [%d] %s", tmv[2], tmv[1], tmv[0], getpid(), msg);
    scriviRiga(kLogShmem, riga);
    cercaChiavetta();
    if (g_logChiavetta[0]) scriviRiga(g_logChiavetta, riga);
}

// ---- contatore avvii ---------------------------------------------------------------------------------------
u32 leggiAvvii() {
    int fd = open(kAvvii, O_RDONLY_Q);
    if (fd < 0) return 0;
    char b[16]; memset(b, 0, sizeof(b));
    read(fd, b, sizeof(b) - 1);
    close(fd);
    u32 n = 0;
    for (int i = 0; b[i] >= '0' && b[i] <= '9'; ++i) n = n * 10 + (u32)(b[i] - '0');
    return n;
}

void scriviAvvii(u32 n) {
    void* f = fopen(kAvvii, "w");
    if (!f) return;
    fprintf(f, "%u\n", n);
    fclose(f);
}

// ---- caselle: il thread di rete deposita, il ciclo del plugin preleva ------------------------------------
// blocco = [u32 lunghezza][byte...]; il testo ha anche lo 0 finale
struct Casella { void* volatile blocco; u32 modello; bool immagine; };

#if defined(PLUGIN_PAGINA)
const u32 kCaselle = 1;
#else
const u32 kCaselle = 4 * kMaxNotizie;     // titoli, pagine, miniature, schede
const u32 kPrimaScheda = 3 * kMaxNotizie;
void* g_schede[kMaxNotizie];              // schede gia' arrivate (le usa solo il thread del plugin)
u32   g_fuoco = 0;                        // riga selezionata nella lista
#endif
Casella g_caselle[kCaselle];

void* nuovoBlocco(const void* dati, u32 n, bool zero) {
    unsigned char* b = (unsigned char*)malloc(4 + n + (zero ? 1 : 0));
    if (!b) return 0;
    memcpy(b, &n, 4);
    if (n) memcpy(b + 4, dati, n);
    if (zero) b[4 + n] = 0;
    return b;
}

void deposita(u32 indice, void* blocco) {
    if (!blocco || indice >= kCaselle) return;
    void* vecchio = __atomic_exchange_n(&g_caselle[indice].blocco, blocco, __ATOMIC_ACQ_REL);
    if (vecchio) free(vecchio);                        // non ancora mostrato: sostituito dal piu' nuovo
}

void mostraImmagine(u32 modello, const unsigned char* dati, u32 n) {
    unsigned char spazio[64] __attribute__((aligned(8)));
    memset(spazio, 0, sizeof(spazio));
    bmw::Image* img = new (spazio) bmw::Image(dati, n);
    g_app->updateModel(modello, *img);
    // bmw::Image non esporta un distruttore: si distruggono a mano i suoi due membri
    reinterpret_cast<bmw::BufferStream*>(spazio + 4)->~BufferStream();
    reinterpret_cast<bmw::BasicString<char>*>(spazio)->~BasicString();
}

#if defined(PLUGIN_ELENCO)
void mostraScheda(u32 i) {
    if (i >= kMaxNotizie || !g_schede[i]) return;
    u32 n; memcpy(&n, g_schede[i], 4);
    mostraImmagine(kModelloAnteprima, (const unsigned char*)g_schede[i] + 4, n);
}
#endif

/** porta sulla schermata cio' che il thread di rete ha lasciato: tutti i testi subito, le immagini a turni
 *  (al massimo kImmaginiPerGiro e kBytePerGiro per giro); ordine: titoli, miniature, pagine, schede */
void svuotaCaselle() {
    u32 immagini = 0, byte = 0;
    for (u32 passo = 0; passo < kCaselle; ++passo) {
#if defined(PLUGIN_ELENCO)
        // ordine di servizio: 0..7 titoli, 16..23 miniature, 8..15 pagine, 24..31 schede
        u32 gruppo = passo / kMaxNotizie, k = passo % kMaxNotizie;
        u32 i = (gruppo == 1 ? 2 : gruppo == 2 ? 1 : gruppo) * kMaxNotizie + k;
#else
        u32 i = passo;
#endif
        if (g_caselle[i].immagine && (immagini >= kImmaginiPerGiro || byte >= kBytePerGiro)) continue;   // al prossimo giro
        void* b = __atomic_exchange_n(&g_caselle[i].blocco, (void*)0, __ATOMIC_ACQ_REL);
        if (!b) continue;
        u32 n; memcpy(&n, b, 4);
#if defined(PLUGIN_ELENCO)
        if (i >= kPrimaScheda) {                       // scheda: si tiene da parte, si mostra se e' la riga selezionata
            u32 s = i - kPrimaScheda;
            if (g_schede[s]) free(g_schede[s]);
            g_schede[s] = b;
            if (s == g_fuoco) { mostraScheda(s); ++immagini; byte += n; }
            continue;
        }
#endif
        const unsigned char* dati = (const unsigned char*)b + 4;
        if (g_caselle[i].immagine) { mostraImmagine(g_caselle[i].modello, dati, n); ++immagini; byte += n; }
        else g_app->updateModel(g_caselle[i].modello, (const char*)dati);
        free(b);
    }
}

// ---- file locali (pagina "in attesa") ------------------------------------------------------------------------
void* bloccoDaFile(const char* nome) {
    char path[192];
    snprintf(path, sizeof(path), "%s/%s", kDirPlugin, nome);
    int fd = open(path, O_RDONLY_Q);
    if (fd < 0) return 0;
    u32 cap = 256 * 1024, tot = 0;
    unsigned char* b = (unsigned char*)malloc(4 + cap);
    if (!b) { close(fd); return 0; }
    for (;;) {
        if (tot == cap) { tot = 0; break; }                         // troppo grande: rifiuta
        ssize_t_q r = read(fd, b + 4 + tot, cap - tot);
        if (r <= 0) break;
        tot += (u32)r;
    }
    close(fd);
    if (!tot) { free(b); return 0; }
    memcpy(b, &tot, 4);
    return b;
}

// testo da file (ISO-8859-1, una riga); se manca o e' vuoto vale il testo di riserva
void* testoDaFile(const char* nome, const char* riserva) {
    unsigned char* f = (unsigned char*)bloccoDaFile(nome);
    if (f) {
        u32 n;
        memcpy(&n, f, 4);
        if (n > 200) n = 200;
        for (u32 i = 0; i < n; ++i) if (f[4 + i] == '\n' || f[4 + i] == '\r') { n = i; break; }
        void* t = n ? nuovoBlocco(f + 4, n, true) : 0;
        free(f);
        if (t) return t;
    }
    return nuovoBlocco(riserva, strlen(riserva), true);
}

// ---- rete --------------------------------------------------------------------------------------------------
#if !defined(PROVA_HOST)
// QNX io-pkt (derivato da NetBSD): sockaddr_in con sin_len, errno e costanti QNX
enum { AF_INET_Q = 2, SOCK_STREAM_Q = 1, SOL_SOCKET_Q = 0xffff, SO_SNDTIMEO_Q = 0x1005, SO_RCVTIMEO_Q = 0x1006 };
enum { F_GETFL_Q = 3, F_SETFL_Q = 4, O_NONBLOCK_Q = 0x80 };
enum { EINPROGRESS_Q = 236, EALREADY_Q = 237, EISCONN_Q = 256, ETIMEDOUT_Q = 260, ECONNREFUSED_Q = 261 };
// v2: in auto un connect ripetuto mentre il collegamento e' ancora in corso risponde EBUSY (16), non EALREADY
enum { EBUSY_Q = 16 };
struct IndirizzoIn { unsigned char len, family; u16 port; u32 addr; unsigned char zero[8]; };
#else
// solo per la prova sul PC (Linux): stessa logica, costanti Linux
enum { AF_INET_Q = 2, SOCK_STREAM_Q = 1, SOL_SOCKET_Q = 1, SO_SNDTIMEO_Q = 21, SO_RCVTIMEO_Q = 20 };
enum { F_GETFL_Q = 3, F_SETFL_Q = 4, O_NONBLOCK_Q = 04000 };
enum { EINPROGRESS_Q = 115, EALREADY_Q = 114, EISCONN_Q = 106, ETIMEDOUT_Q = 110, ECONNREFUSED_Q = 111 };
enum { EBUSY_Q = 16 };
struct IndirizzoIn { unsigned short family_len; u16 port; u32 addr; unsigned char zero[8]; };
#endif
struct TempoQ { long sec, usec; };

u32 ip4(u32 a, u32 b, u32 c, u32 d) { return a | (b << 8) | (c << 16) | (d << 24); }
u16 porta(u32 p) { return (u16)(((p & 0xff) << 8) | ((p >> 8) & 0xff)); }
void scriviIp(char* out, u32 n, u32 ip) {
    snprintf(out, n, "%u.%u.%u.%u", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
}
int erroreAttuale() { return *__get_errno_ptr(); }

void impostaBloccante(int s, bool bloccante) {
    int fl = fcntl(s, F_GETFL_Q);
    if (fl < 0) fl = 0;
    fcntl(s, F_SETFL_Q, bloccante ? (fl & ~O_NONBLOCK_Q) : (fl | O_NONBLOCK_Q));
}

int nuovoSocket(u32 ip, u32 port, IndirizzoIn* a) {
    int s = socket(AF_INET_Q, SOCK_STREAM_Q, 0);
    if (s < 0) return -1;
    memset(a, 0, sizeof(*a));
#if !defined(PROVA_HOST)
    a->len = sizeof(*a); a->family = AF_INET_Q;
#else
    a->family_len = AF_INET_Q;
#endif
    a->port = porta(port); a->addr = ip;
    return s;
}

int esitoConnect(int s, IndirizzoIn* a, int* err) {
    if (connect(s, a, sizeof(*a)) == 0) { *err = 0; return 1; }
    int e = erroreAttuale();
    if (e == EISCONN_Q) { *err = 0; return 1; }
    if (e == EALREADY_Q || e == EINPROGRESS_Q || e == EBUSY_Q) { *err = e; return 0; }
    *err = e; return -1;
}

int collegaTcp(u32 ip, u32 port, u32 maxMs, int* err) {
    *err = 0;
    IndirizzoIn a;
    int s = nuovoSocket(ip, port, &a);
    if (s < 0) { *err = erroreAttuale(); return -1; }
    impostaBloccante(s, false);
    int r;
    if (connect(s, &a, sizeof(a)) == 0) r = 1;
    else {
        int e = erroreAttuale();
        if (e != EINPROGRESS_Q && e != EALREADY_Q && e != EBUSY_Q) { *err = e; close(s); return -1; }
        r = 0;
        for (u32 t = 0; t < maxMs && r == 0; t += 100) { usleep(100000); r = esitoConnect(s, &a, err); }
        if (r == 0) *err = ETIMEDOUT_Q;
    }
    if (r != 1) { close(s); return -1; }
    impostaBloccante(s, true);
    TempoQ t = { 8, 0 };
    setsockopt(s, SOL_SOCKET_Q, SO_SNDTIMEO_Q, &t, sizeof(t));
    setsockopt(s, SOL_SOCKET_Q, SO_RCVTIMEO_Q, &t, sizeof(t));
    return s;
}

// GET al telefono: ritorna il corpo (blocco [u32 len][byte][0]) se HTTP 200, altrimenti 0; in *codice lo stato HTTP
void* scarica(u32 ip, const char* percorso, int* codice) {
    *codice = 0;
    int err = 0;
    int s = collegaTcp(ip, kPortaPonte, 3000, &err);
    if (s < 0) { *codice = -err; return 0; }
    // la richiesta sta nella memoria del processo, non sulla pila del thread (i percorsi /auto sono lunghi)
    u32 capReq = strlen(percorso) + 96;
    char* req = (char*)malloc(capReq);
    if (!req) { close(s); return 0; }
    snprintf(req, capReq, "GET %s HTTP/1.0\r\nHost: telefono\r\nUser-Agent: iDrive-%s/1\r\n\r\n", percorso, NOME_PLUGIN);
    int inviati = send(s, req, strlen(req), 0);
    free(req);
    if (inviati < 0) { close(s); *codice = -erroreAttuale(); return 0; }
    u32 cap = 64 * 1024, tot = 0;
    unsigned char* b = (unsigned char*)malloc(cap + 1);
    while (b) {
        if (tot == cap) {
            if (cap >= kMaxRisposta) break;
            u32 nuovo = cap * 2 > kMaxRisposta ? kMaxRisposta : cap * 2;
            unsigned char* nb = (unsigned char*)realloc(b, nuovo + 1);
            if (!nb) break;
            b = nb; cap = nuovo;
        }
        int k = recv(s, b + tot, cap - tot, 0);
        if (k <= 0) break;
        tot += (u32)k;
    }
    close(s);
    if (!b) return 0;
    b[tot] = 0;
    // "HTTP/1.0 200 OK\r\n...\r\n\r\n<corpo>"
    if (tot < 12 || strncmp((const char*)b, "HTTP/1.", 7) != 0) { free(b); return 0; }
    *codice = (b[9] - '0') * 100 + (b[10] - '0') * 10 + (b[11] - '0');
    const char* fine = strstr((const char*)b, "\r\n\r\n");
    if (*codice != 200 || !fine) { free(b); return 0; }
    u32 inizio = (u32)(fine + 4 - (const char*)b);
    u32 n = tot - inizio;
    void* blocco = nuovoBlocco(b + inizio, n, true);
    free(b);
    return blocco;
}

__attribute__((unused)) u32 lunghezza(void* blocco) { u32 n; memcpy(&n, blocco, 4); return n; }
const char* testo(void* blocco) { return (const char*)blocco + 4; }

// ---- telefono ----------------------------------------------------------------------------------------------
u32 g_candidati[8];
u32 g_nCandidati = 0;
u32 g_telefono = 0;

bool ipWifi(u32 ip) {
    u32 a = ip & 0xff, b = (ip >> 8) & 0xff, c = (ip >> 16) & 0xff, d = (ip >> 24) & 0xff;
    return a == 172 && b == 16 && (c == 224 || c == 222) && d != 1 && d != 0 && d != 255;
}

u32 leggiIp(const char* p) {
    u32 v[4] = {0, 0, 0, 0};
    for (int i = 0; i < 4; ++i) {
        if (*p < '0' || *p > '9') return 0;
        while (*p >= '0' && *p <= '9') { v[i] = v[i] * 10 + (u32)(*p - '0'); ++p; }
        if (v[i] > 255) return 0;
        if (i < 3) { if (*p != '.') return 0; ++p; }
    }
    return ip4(v[0], v[1], v[2], v[3]);
}

void aggiungiCandidato(u32 ip) {
    for (u32 i = 0; i < g_nCandidati; ++i) if (g_candidati[i] == ip) return;
    if (g_nCandidati < 8) g_candidati[g_nCandidati++] = ip;
}

void candidatiDaArp() {
    void* f = popen("PATH=/sbin:/usr/sbin:/bin:/usr/bin:/proc/boot:$PATH arp -an 2>/dev/null", "r");
    if (!f) { scriviLog("arp: popen non riuscito (errno %d)", erroreAttuale()); return; }
    char riga[160];
    while (fgets(riga, sizeof(riga), f)) {
        if (strstr(riga, "incomplete")) continue;
        const char* p = strstr(riga, "(");
        if (!p) continue;
        u32 ip = leggiIp(p + 1);
        if (ip && ipWifi(ip)) aggiungiCandidato(ip);
    }
    pclose(f);
}

// scansione di 172.16.224.2-254 sulla porta dell'app, 32 indirizzi per volta, connect non bloccanti
void scansione() {
    for (u32 base = 2; base <= 254 && !g_finito && g_nCandidati == 0; base += 32) {
        int s[32]; IndirizzoIn a[32]; int stato[32];
        u32 n = 0;
        for (u32 d = base; d < base + 32 && d <= 254; ++d, ++n) {
            stato[n] = -1;
            s[n] = nuovoSocket(ip4(172, 16, 224, d), kPortaPonte, &a[n]);
            if (s[n] < 0) continue;
            impostaBloccante(s[n], false);
            if (connect(s[n], &a[n], sizeof(a[n])) == 0) stato[n] = 1;
            else { int e = erroreAttuale(); stato[n] = (e == EINPROGRESS_Q || e == EALREADY_Q || e == EBUSY_Q) ? 0 : -1; }
        }
        for (int attesa = 0; attesa < 15; ++attesa) {
            usleep(100000);
            for (u32 i = 0; i < n; ++i) {
                if (stato[i] != 0) continue;
                int e = 0;
                int r = esitoConnect(s[i], &a[i], &e);
                if (r != 0) stato[i] = r;
            }
        }
        for (u32 i = 0; i < n; ++i) {
            if (stato[i] == 1) aggiungiCandidato(a[i].addr);
            if (s[i] >= 0) close(s[i]);
        }
    }
}

// cerca il telefono con l'app accesa: ritorna l'IP oppure 0
u32 trovaTelefono() {
    g_nCandidati = 0;
    if (g_telefono) aggiungiCandidato(g_telefono);
    candidatiDaArp();
    if (g_nCandidati == 0) scansione();
    for (u32 i = 0; i < g_nCandidati; ++i) {
        int codice = 0;
        void* b = scarica(g_candidati[i], "/ponte", &codice);
        if (b && strncmp(testo(b), "IDRIVEPONTE", 11) == 0) {
            char ip[24]; scriviIp(ip, sizeof(ip), g_candidati[i]);
            scriviLog("telefono trovato: %s - %s", ip, testo(b));
            free(b);
            return g_candidati[i];
        }
        if (b) free(b);
    }
    return 0;
}

// numero dopo "chiave=" nella risposta di /ponte
u32 valore(const char* s, const char* chiave) {
    const char* p = strstr(s, chiave);
    if (!p) return 0;
    p += strlen(chiave);
    u32 v = 0;
    while (*p >= '0' && *p <= '9') { v = v * 10 + (u32)(*p - '0'); ++p; }
    return v;
}

// ---- dati dell'auto (solo Meteo) ----------------------------------------------------------------------------
#if DATI_AUTO
const char* const kDisableAuto = "/opt/aa/auto.disable";
// nomi da IDriveConnectKit (CDSProperty.kt); gli stessi della tabella di libhmiremoting.so dell'iDrive
const char* const kProprieta[] = {
    "driving.odometer", "sensors.fuel", "driving.averageConsumption",
    "sensors.temperatureExterior", "sensors.temperatureInterior",
    "sensors.doors", "sensors.trunk", "sensors.lid", "controls.sunroof",
    "controls.windowDriverFront", "controls.windowPassengerFront",
    "controls.windowDriverRear", "controls.windowPassengerRear",
};
const u32 kNumProprieta = sizeof(kProprieta) / sizeof(kProprieta[0]);
const u32 kMaxValore = 400;                // i valori CDS sono JSON brevi (il piu' lungo, un finestrino, ~70 caratteri)
const u32 kGiroCds = 3;                    // registrazione 1,5 s dopo onInit, come NBT-AAD
const u32 kLogPerProprieta = 2;            // i primi valori di ogni proprieta' finiscono nel log (per vederne il formato)

void* volatile g_valori[kNumProprieta];    // ultimo valore arrivato e non ancora mandato (thread dell'HMI -> rete)
char g_mandato[kNumProprieta][kMaxValore]; // ultimo valore mandato al telefono (solo thread di rete)
u32 g_loggati[kNumProprieta];
bool g_cdsRegistrati = false;
bool g_autoSenzaApp = false;               // il telefono ha un'app che non conosce /auto: si smette di mandare

// sul thread dell'HMI: niente rete, niente attese; si lascia il valore (ASCII) al thread di rete
bool cambioCds(void* oggetto, const bmw::BasicString<u16>& valore) {
    u32 i = (u32)(unsigned long)oggetto;   // l'indice della proprieta' viaggia al posto dell'oggetto
    if (i >= kNumProprieta) return true;
    const u16* p = valore.raw();
    u32 n = 0;
    while (p && p[n] && n < kMaxValore - 1) ++n;
    unsigned char* b = (unsigned char*)malloc(4 + n + 1);    // blocco [u32 lunghezza][testo][0], come nuovoBlocco
    if (!b) return true;
    memcpy(b, &n, 4);
    for (u32 k = 0; k < n; ++k) b[4 + k] = p[k] < 0x80 ? (unsigned char)p[k] : '?';
    b[4 + n] = 0;
    void* vecchio = __atomic_exchange_n(&g_valori[i], (void*)b, __ATOMIC_ACQ_REL);
    if (vecchio) free(vecchio);
    return true;
}

void registraCds() {
    if (g_cdsRegistrati || !g_app) return;
    g_cdsRegistrati = true;
    if (access(kDisableAuto, F_OK_Q) == 0) { scriviLog("dati dell'auto disattivati da %s", kDisableAuto); return; }
    for (u32 i = 0; i < kNumProprieta; ++i) {
        bmw::BasicString<char> nome(kProprieta[i]);
        bmw::DelegatoCds d;
        d.oggetto = (void*)(unsigned long)i;
        d.funzione = &cambioCds;
        g_app->cdsAddPropertyChangedEventHandler(nome, 0, d);
    }
    scriviLog("dati dell'auto: %u proprieta' CDS registrate", kNumProprieta);
}

// "%XX" per tutto cio' che non e' lettera, cifra o - _ . ~ (il JSON ha { } " : ,)
void codifica(char* out, u32 cap, const char* in) {
    static const char esa[] = "0123456789ABCDEF";
    u32 k = 0;
    for (const unsigned char* p = (const unsigned char*)in; *p && k + 4 < cap; ++p) {
        unsigned char c = *p;
        bool libero = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
        if (libero) out[k++] = (char)c;
        else { out[k++] = '%'; out[k++] = esa[c >> 4]; out[k++] = esa[c & 15]; }
    }
    out[k] = 0;
}

// sul thread di rete: il telefono e' ripartito da zero (avvio= cambiato): ogni ultimo valore gia' mandato torna in
// coda, cosi' arrivano anche quelli fermi; un valore piu' nuovo gia' in coda resta quello
u32 g_avvioTelefono = 0;
void rimandaDatiAuto(const char* ponte) {
    u32 avvio = valore(ponte, "avvio=");
    if (!avvio || avvio == g_avvioTelefono) return;
    bool prima = g_avvioTelefono != 0;
    g_avvioTelefono = avvio;
    g_autoSenzaApp = false;                      // app nuova: si riprova /auto anche dopo un 404
    u32 rimessi = 0;
    for (u32 i = 0; i < kNumProprieta; ++i) {
        if (!g_mandato[i][0]) continue;
        void* b = nuovoBlocco(g_mandato[i], strlen(g_mandato[i]), true);
        void* atteso = 0;
        if (b && !__atomic_compare_exchange_n(&g_valori[i], &atteso, b, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) free(b);
        g_mandato[i][0] = 0;
        ++rimessi;
    }
    if (prima) scriviLog("auto: il telefono e' ripartito, rimando %u valori", rimessi);
}

// sul thread di rete: manda al telefono i valori cambiati; se il telefono non risponde il valore resta per il giro dopo
void mandaDatiAuto(u32 ip) {
    if (g_autoSenzaApp) return;
    for (u32 i = 0; i < kNumProprieta && !g_finito; ++i) {
        void* b = __atomic_exchange_n(&g_valori[i], (void*)0, __ATOMIC_ACQ_REL);
        if (!b) continue;
        const char* v = testo(b);
        if (strncmp(v, g_mandato[i], kMaxValore) == 0) { free(b); continue; }
        // solo il thread di rete passa di qui: memoria statica, niente di grosso sulla pila del thread
        static char valore[3 * kMaxValore + 1];
        static char percorso[3 * kMaxValore + 80];
        codifica(valore, sizeof(valore), v);
        snprintf(percorso, sizeof(percorso), "/auto?n=%s&v=%s", kProprieta[i], valore);
        int codice = 0;
        void* r = scarica(ip, percorso, &codice);
        if (r) free(r);
        if (codice == 200) {
            if (g_loggati[i] < kLogPerProprieta) { ++g_loggati[i]; scriviLog("auto: %s = %s", kProprieta[i], v); }
            u32 n = strlen(v);
            if (n >= kMaxValore) n = kMaxValore - 1;
            memcpy(g_mandato[i], v, n);
            g_mandato[i][n] = 0;
            free(b);
            continue;
        }
        if (codice == 404) {
            scriviLog("auto: l'app del telefono non riceve i dati dell'auto (404): aggiornala");
            g_autoSenzaApp = true;
            free(b);
            return;
        }
        // non arrivato: si rimette, a meno che nel frattempo ne sia arrivato uno piu' nuovo
        void* atteso = 0;
        if (!__atomic_compare_exchange_n(&g_valori[i], &atteso, b, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) free(b);
        return;
    }
}
#endif

// attesa tra un giro di rete e l'altro; intanto (Meteo) i dati dell'auto partono ogni 2 secondi
void attendi(u32 secondi, u32 ip) {
    for (u32 i = 0; i < secondi && !g_finito; ++i) {
#if DATI_AUTO
        if (ip && (i % 2) == 0) mandaDatiAuto(ip);
#else
        (void)ip;
#endif
        sleep(1);
    }
}

// ---- aggiornamento dei dati --------------------------------------------------------------------------------
u32 g_epochFatto = 0;     // ultimo epoch del telefono gia' portato sulla schermata

#if defined(PLUGIN_PAGINA)
bool aggiornaDati(u32 ip, const char* ponte) {
    u32 e = valore(ponte, CHIAVE_EPOCH);
    if (!e || e == g_epochFatto) return true;
    int codice = 0;
    void* png = scarica(ip, PAGINA_PNG, &codice);
    if (!png) { scriviLog("%s: errore %d", PAGINA_PNG, codice); return false; }
    scriviLog("%s: %u byte (epoch %u)", PAGINA_PNG, lunghezza(png), e);
    // il blocco ha lo 0 finale in piu': per l'immagine conta solo la lunghezza scritta in testa
    deposita(0, png);
    g_epochFatto = e;
    return true;
}
#else
u32 g_versioneRiga[kMaxNotizie];      // versione di ogni riga gia' portata sulla schermata (0 = nessuna)

bool aggiornaDati(u32 ip, const char* ponte) {
    u32 e = valore(ponte, CHIAVE_EPOCH);
    if (!e || e == g_epochFatto) return true;
    int codice = 0;
    void* lista = scarica(ip, PREFISSO "/notizie.txt", &codice);
    if (!lista) { scriviLog("notizie.txt: errore %d", codice); return false; }
    // riga 1 "NOTIZIE <epoch> <n>", poi un titolo per riga
    char* p = (char*)lista + 4;
    char* a = p;
    while (*a && *a != '\n') ++a;
    u32 n = 0;
    { const char* q = p; int spazi = 0; while (q < a) { if (*q == ' ') ++spazi; else if (spazi == 2 && *q >= '0' && *q <= '9') n = n * 10 + (u32)(*q - '0'); ++q; } }
    if (n > kMaxNotizie) n = kMaxNotizie;
    char* riga = (*a) ? a + 1 : a;
    bool tutto = true;
    u32 versione[kMaxNotizie];
    for (u32 i = 0; i < kMaxNotizie; ++i) {
        char* fine = riga;
        while (*fine && *fine != '\n') ++fine;
        u32 len = (u32)(fine - riga);
        // v9: "titolo<TAB>versione": la versione dice se pagina e miniatura sono cambiate (0 = senza, si riscaricano)
        versione[i] = 0;
        for (u32 k = 0; k < len; ++k) if (riga[k] == '\t') {
            for (u32 j = k + 1; j < len && riga[j] >= '0' && riga[j] <= '9'; ++j) versione[i] = versione[i] * 10 + (u32)(riga[j] - '0');
            len = k;
            break;
        }
        if (i < n) deposita(i, nuovoBlocco(riga, len, true));
        else deposita(i, nuovoBlocco("", 0, true));
        riga = (*fine) ? fine + 1 : fine;
    }
    free(lista);
    for (u32 i = 0; i < n && !g_finito; ++i) {
        if (versione[i] && versione[i] == g_versioneRiga[i]) continue;      // riga uguale: niente da riscaricare
        bool rigaOk = true;
        char percorso[40];
        snprintf(percorso, sizeof(percorso), PREFISSO "/mini/%u.png", i);
        void* mini = scarica(ip, percorso, &codice);
        if (mini) deposita(kMaxNotizie * 2 + i, mini); else { tutto = false; rigaOk = false; }
        snprintf(percorso, sizeof(percorso), PREFISSO "/notizia/%u.png", i);
        void* pag = scarica(ip, percorso, &codice);
        if (pag) deposita(kMaxNotizie + i, pag); else { tutto = false; rigaOk = false; scriviLog("%s: errore %d", percorso, codice); }
        if (rigaOk) g_versioneRiga[i] = versione[i];
#if SCHEDA_LATERALE
        snprintf(percorso, sizeof(percorso), PREFISSO "/anteprima/%u.png", i);
        void* sch = scarica(ip, percorso, &codice);
        if (sch) deposita(kPrimaScheda + i, sch);
        else if (codice != 404) { tutto = false; scriviLog("%s: errore %d", percorso, codice); }   // 404: app 1.0, senza schede
#endif
    }
    if (kMaxNotizie > 2) scriviLog("notizie: %u titoli, pagine %s (epoch %u)", n, tutto ? "tutte" : "NON tutte", e);
    else if (!tutto) scriviLog("righe: %u, pagine NON tutte (epoch %u)", n, e);
    if (tutto) g_epochFatto = e;
    return tutto;
}
#endif

void* threadRete(void*) {
    sleep(10);
    u32 errori = 0;
    while (!g_finito) {
        if (!g_telefono) {
            g_telefono = trovaTelefono();
            if (!g_telefono) { attendi(20, 0); continue; }
        }
        int codice = 0;
        void* ponte = scarica(g_telefono, "/ponte", &codice);
        if (!ponte || strncmp(testo(ponte), "IDRIVEPONTE", 11) != 0) {
            if (ponte) free(ponte);
            if (++errori >= 2) { scriviLog("telefono non risponde (errore %d): lo cerco di nuovo", codice); g_telefono = 0; errori = 0; }
            attendi(10, 0);
            continue;
        }
        errori = 0;
#if DATI_AUTO
        rimandaDatiAuto(testo(ponte));
#endif
        bool ok = aggiornaDati(g_telefono, testo(ponte));
        free(ponte);
        attendi(ok ? GIRO_SECONDI : 10, g_telefono);
    }
    return 0;
}

// ---- ciclo periodico sul thread del plugin -----------------------------------------------------------------
struct Giro { void** vptr; };
Giro  g_giro;
void* g_giroVtbl[5];

void giroNoop(void*) {}

void giroRun(void*) {
    if (!g_app || g_finito) return;
    ++g_giri;
    svuotaCaselle();
#if DATI_AUTO
    if (g_giri == kGiroCds) registraCds();
#endif
    if (g_giri == kSecondiSano) { scriviAvvii(0); scriviLog("%u s di funzionamento: avvio considerato riuscito", kSecondiSano / 2); }
    g_app->post(reinterpret_cast<bmw::Runnable*>(&g_giro), kTickMs);
}

void avviaGiro(bmw::PluginApp* app) {
    void** base = reinterpret_cast<void**>(dlsym((void*)-2 /* RTLD_DEFAULT su QNX */, "_ZTVN3bmw8RunnableE"));
    if (base) memcpy(g_giroVtbl, base, sizeof(g_giroVtbl));
    else { g_giroVtbl[0] = 0; g_giroVtbl[1] = 0; }
    g_giroVtbl[2] = reinterpret_cast<void*>(&giroNoop);
    g_giroVtbl[3] = reinterpret_cast<void*>(&giroNoop);
    g_giroVtbl[4] = reinterpret_cast<void*>(&giroRun);
    g_giro.vptr = g_giroVtbl + 2;
    app->post(reinterpret_cast<bmw::Runnable*>(&g_giro), kTickMs);
}

void preparaCaselle() {
#if defined(PLUGIN_PAGINA)
    g_caselle[0].modello = kModelloMeteo; g_caselle[0].immagine = true;
#else
    for (u32 i = 0; i < kMaxNotizie; ++i) {
        g_caselle[i].modello = kModelloTitolo0 + i;                    g_caselle[i].immagine = false;
        g_caselle[kMaxNotizie + i].modello = kModelloPagina0 + i;      g_caselle[kMaxNotizie + i].immagine = true;
        g_caselle[kMaxNotizie * 2 + i].modello = kModelloMini0 + i;    g_caselle[kMaxNotizie * 2 + i].immagine = true;
    }
#endif
}

void schermataAttesa() {
#if defined(PLUGIN_PAGINA)
    deposita(0, bloccoDaFile("attesa.png"));
#else
    deposita(0, testoDaFile("attesa.txt", "In attesa del telefono"));
    for (u32 i = 1; i < kMaxNotizie; ++i) deposita(i, nuovoBlocco("", 0, true));
    for (u32 i = 0; i < kMaxNotizie; ++i) deposita(kMaxNotizie + i, bloccoDaFile("attesa.png"));
#endif
}

// ---- riga selezionata nella lista (selectAction -> raAction 20..27) ----------------------------------------
#if defined(PLUGIN_ELENCO)
bool rigaScelta(void* oggetto, const bmw::Parcel&) {
    u32 i = (u32)(unsigned long)oggetto;          // l'indice della riga viaggia al posto dell'oggetto
    if (i < kMaxNotizie && i != g_fuoco) { g_fuoco = i; mostraScheda(i); }
    return true;
}
#endif

// ---- ciclo di vita -----------------------------------------------------------------------------------------
bool appOnInit(bmw::PluginApp* self) {
#if defined(PLUGIN_ELENCO) && SCHEDA_LATERALE
    for (u32 i = 0; i < kMaxNotizie; ++i) {
        bmw::DelegatoAzione d;
        d.oggetto = (void*)(unsigned long)i;
        d.funzione = &rigaScelta;
        self->addHandler(kAzioneScelta0 + i, d);
    }
#endif
    bool ok = self->onInit();
    scriviLog("onInit base -> %d", (int)ok);
    preparaCaselle();
    schermataAttesa();
    svuotaCaselle();
    avviaGiro(self);
    int tid = 0;
    int r = pthread_create(&tid, 0, &threadRete, 0);
    scriviLog("thread di rete: %s (tid %d)", r == 0 ? "avviato" : "NON avviato", tid);
    return ok;
}

void appOnError(bmw::PluginApp*, u32 code, const bmw::BasicString<char>& text) {
    const char* s = text.raw();
    scriviLog("onError %u: %s", code, s ? s : "(null)");
}

void appOnFinalize(bmw::PluginApp* self) {
    scriviLog("onFinalize");
    g_finito = true;
    self->onFinalize();
}

bmw::PluginApp* creaApp(bmw::IDispatcher& dispatcher, const char* name, const bmw::Bundle& params, const bmw::Bundle& config) {
    scriviLog("create(%s) %s %s", name ? name : "(null)", NOME_PLUGIN, VERSIONE_PLUGIN);
    if (access(kDisable, F_OK_Q) == 0) { scriviLog("disattivato da %s", kDisable); return 0; }
    u32 avvii = leggiAvvii();
    if (avvii >= kMaxAvvii) { scriviLog("disattivato: %u avvii senza arrivare a %u s (cancella %s per riprovare)", avvii, kSecondiSano / 2, kAvvii); return 0; }
    scriviAvvii(avvii + 1);
    if (g_app) { scriviLog("create: gia' creato"); return 0; }

    void* mem = malloc(bmw::kPluginAppStorage);
    if (!mem) return 0;
    memset(mem, 0, bmw::kPluginAppStorage);
    bmw::PluginApp* app = new (mem) bmw::PluginApp(dispatcher, bmw::BasicString<char>(name ? name : NOME_PLUGIN), 1, 0,
                                                   bmw::RHMI_INTERFACE_VERSION_1, params, config);
    void** base = *reinterpret_cast<void***>(app);
    memcpy(g_vtbl, base - 2, sizeof(g_vtbl));
    g_vtbl[2 + kSlotOnInit]     = reinterpret_cast<void*>(&appOnInit);
    g_vtbl[2 + kSlotOnError]    = reinterpret_cast<void*>(&appOnError);
    g_vtbl[2 + kSlotOnFinalize] = reinterpret_cast<void*>(&appOnFinalize);
    *reinterpret_cast<void***>(app) = g_vtbl + 2;
    g_app = app;
    scriviLog("create: app %p, avvio n. %u", (void*)app, avvii + 1);
    return app;
}

void distruggiApp(void* app) {
    scriviLog("destroy(%p)", app);
    if (!app) return;
    g_finito = true;
    void** vt = *reinterpret_cast<void***>(app);
    reinterpret_cast<void (*)(void*)>(vt[1])(app);
    if (app == g_app) g_app = 0;
}

} // namespace

extern "C" {
struct InfoPlugin {
    u16 versionMajor;
    u16 versionMinor;
    const char* name;
    const char* vendor;
    u32 rhmiInterfaceVersion;
    bmw::PluginApp* (*create)(bmw::IDispatcher&, const char*, const bmw::Bundle&, const bmw::Bundle&);
    void (*destroy)(void*);
};
__attribute__((visibility("default"))) InfoPlugin pluginAppInfo = {
    1, 0, NOME_PLUGIN, "DeathWalkeR", 1, &creaApp, &distruggiApp
};
}
