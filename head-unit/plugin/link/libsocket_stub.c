/* solo per il collegamento: in auto arrivano dalla libsocket.so.3 vera della testata */
int socket(int a,int b,int c){return 0;} int connect(int s,const void* a,unsigned n){return 0;}
int setsockopt(int s,int l,int o,const void* v,unsigned n){return 0;} int send(int s,const void* b,unsigned n,int f){return 0;}
int recv(int s,void* b,unsigned n,int f){return 0;} void* gethostbyname(const char* n){return 0;}
