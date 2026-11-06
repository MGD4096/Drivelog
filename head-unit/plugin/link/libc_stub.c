/* solo per il collegamento: in auto queste funzioni arrivano dalla libc.so.3 vera della testata */
void* malloc(unsigned n){return 0;} void free(void* p){} void* memset(void* d,int c,unsigned n){return d;}
void* memcpy(void* d,const void* s,unsigned n){return d;} unsigned strlen(const char* s){return 0;}
int strncmp(const char* a,const char* b,unsigned n){return 0;} int snprintf(char* b,unsigned n,const char* f,...){return 0;}
int vsnprintf(char* b,unsigned n,const char* f,__builtin_va_list a){return 0;} void* fopen(const char* p,const char* m){return 0;}
int fclose(void* f){return 0;} int fprintf(void* f,const char* x,...){return 0;} int fflush(void* f){return 0;}
int open(const char* p,int o,...){return 0;} int read(int fd,void* b,unsigned n){return 0;} int close(int fd){return 0;}
int access(const char* p,int m){return 0;} void* opendir(const char* p){return 0;} void* readdir(void* d){return 0;}
int closedir(void* d){return 0;} long time(long* t){return 0;} void* localtime_r(const long* t,void* r){return 0;}
void* dlsym(void* h,const char* s){return 0;} int getpid(void){return 0;}
unsigned sleep(unsigned s){return 0;} int* __get_errno_ptr(void){return 0;}
int pthread_create(int* t,const void* a,void* (*f)(void*),void* x){return 0;}
int usleep(unsigned u){return 0;} int fcntl(int f,int c,...){return 0;} void* popen(const char* c,const char* m){return 0;}
int pclose(void* f){return 0;} char* fgets(char* b,int n,void* f){return 0;} char* strstr(const char* a,const char* b){return 0;}
void* realloc(void* p,unsigned n){return 0;}
