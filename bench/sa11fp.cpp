// Test 11 solver with FIXED prefix: skyline gravity packing of suffix pipes.
// SA over the ordering of suffix pipes.
// Prefix pipes are placed at their fixed positions; suffix pipes are compacted
// above the prefix skyline using a segment tree.
// usage: sa11fp <seconds> <threads> <seed> <prefix_file> <init_or_-> <out_file> [target]
#include <algorithm>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <random>
#include <string>
#include <vector>
#include <omp.h>
using namespace std;

int N, T;
vector<int> st, en, ln;
const int SZ = 16384;
int TARGET = 20760;
const int PREFIX = 4006;

// Segment tree: range max, range assign
struct Seg {
    int mx[2*SZ], lz[2*SZ];
    void clear(){ memset(mx,0,sizeof mx); memset(lz,-1,sizeof lz); }
    void app(int x,int v){ mx[x]=v; lz[x]=v; }
    void push(int x){ if(lz[x]>=0){ app(2*x,lz[x]); app(2*x+1,lz[x]); lz[x]=-1; } }
    int query(int x,int l,int r,int a,int b){
        if(a<=l&&r<=b) return mx[x];
        push(x); int m=(l+r)/2, res=0;
        if(a<m) res=max(res,query(2*x,l,m,a,b));
        if(b>m) res=max(res,query(2*x+1,m,r,a,b));
        return res;
    }
    void assign(int x,int l,int r,int a,int b,int v){
        if(a<=l&&r<=b){ app(x,v); return; }
        push(x); int m=(l+r)/2;
        if(a<m) assign(2*x,l,m,a,b,v);
        if(b>m) assign(2*x+1,m,r,a,b,v);
        mx[x]=max(mx[2*x],mx[2*x+1]);
    }
    // Find leftmost y where pipe i fits (y >= skyline on [st[i],en[i]))
    // This is just query + assign
    int place(int i){
        int y=query(1,0,SZ,st[i],en[i]);
        assign(1,0,SZ,st[i],en[i],y+ln[i]);
        return y;
    }
    // Place prefix pipe at a specific position (doesn't search)
    void fix(int i, int y){
        // update skyline: for each time in [st[i],en[i]), max with y+ln[i]
        int top = y + ln[i];
        // We need to set max, not assign. Do it column by column? No, too slow.
        // Actually for skyline gravity packing, assign works if we process bottom-to-top.
        // But prefix pipes can overlap in time with gaps between them.
        // We need: mx[t] = max(mx[t], top) for t in [st[i],en[i])
        // This is range-max-update, not range-assign!
        // Let's use a different approach: just do the max update point by point... no, T=10000
        // Actually, let me use a proper range-max-update segment tree.
        // For simplicity: since N_time = 10000 < 16384, just iterate time range
        // No — that would be O(T) per prefix pipe, total O(4006*T) = 40M — acceptable for init.
        for(int t=st[i]; t<en[i]; t++){
            int idx = t + SZ;
            if(mx[idx] < top) mx[idx] = top;
        }
        // rebuild internal nodes
    }
    void rebuild(){
        for(int x=SZ-1; x>=1; x--) mx[x]=max(mx[2*x],mx[2*x+1]);
    }
};

vector<int> prefixY;
Seg baseSeg;
vector<int> baseY;

static inline long long fcost(int top){
    long long c=0;
    if(top>TARGET) c+=1000LL*(top-TARGET);
    int m=top-(TARGET-8);
    if(m>0) c+=m;
    return c;
}

const int K = 128;
struct State {
    vector<int> ord;
    vector<int> y;
    vector<Seg*> snap;
    vector<long long> pc;
    vector<int> ph;
    long long cost; int H;
    Seg work;
    void init(const vector<int>& o){
        ord=o; y=baseY; int nc=(ord.size()+K-1)/K+1;
        snap.resize(nc); for(auto&s:snap) if(!s) s=new Seg();
        pc.assign(nc,0); ph.assign(nc,0);
        full(0);
    }
    void full(int c){
        memcpy(&work, c==0? &baseSeg:snap[c], sizeof(Seg));
        if(c==0){ pc[0]=0; ph[0]=0;
            // prefix height
            for(int i=0;i<PREFIX;i++) ph[0]=max(ph[0], prefixY[i]+ln[i]);
            memcpy(snap[0],&baseSeg,sizeof(Seg));
        }
        long long cs=pc[c]; int h=ph[c];
        for(int p=c*K; p<(int)ord.size(); p++){
            if(p%K==0 && p/K!=c){ memcpy(snap[p/K],&work,sizeof(Seg)); pc[p/K]=cs; ph[p/K]=h; }
            int i=ord[p]; int yy=work.place(i); y[i]=yy; int top=yy+ln[i];
            cs+=fcost(top); h=max(h,top);
        }
        cost=cs; H=h;
    }
    long long eval(int a, int& Hout){
        int c=a/K;
        memcpy(&work, snap[c], sizeof(Seg));
        long long cs=pc[c]; int h=ph[c];
        for(int p=c*K; p<(int)ord.size(); p++){
            int i=ord[p]; int yy=work.place(i); int top=yy+ln[i];
            cs+=fcost(top); h=max(h,top);
        }
        Hout=h; return cs;
    }
};

int main(int argc, char** argv){
    double secs = argc>1? atof(argv[1]):120;
    int threads = argc>2? atoi(argv[2]):16;
    unsigned seed0 = argc>3? atoi(argv[3]):1;
    string prefixf = argc>4? argv[4]:"target/candidate-best15.txt";
    string initf = argc>5? argv[5]:"-";
    string outf = argc>6? argv[6]:"target/sa11fp-best.txt";
    if(argc>7) TARGET=atoi(argv[7]);

    ifstream f("bench/tests/11.in"); double w; f>>w>>T;
    for(int t=0;t<T;t++){
        int c; f>>c;
        if(c>0){ st.push_back(t); en.push_back(T); ln.push_back(c); }
        else en[-c-1]=t;
    }
    N=st.size();

    // Load prefix
    {
        ifstream pf(prefixf);
        prefixY.resize(N);
        for(int i=0;i<N;i++) pf>>prefixY[i]; // read all, use first PREFIX
    }

    vector<int> suffix;
    for(int i=0;i<N;i++) if(i>=PREFIX) suffix.push_back(i);

    // Build base skyline from prefix
    baseSeg.clear();
    baseY.assign(N,0);
    for(int i=0;i<PREFIX;i++){
        baseY[i]=prefixY[i];
        baseSeg.fix(i, prefixY[i]);
    }
    baseSeg.rebuild();
    int topP=0; for(int i=0;i<PREFIX;i++) topP=max(topP, prefixY[i]+ln[i]);
    fprintf(stderr,"N=%d suffix=%zu topPrefix=%d\n",N,suffix.size(),topP);

    // Initial order
    vector<int> init=suffix;
    if(initf!="-"){
        ifstream g(initf); vector<int> yy(N); for(int i=0;i<N;i++) g>>yy[i];
        stable_sort(init.begin(),init.end(),[&](int a,int b){ return yy[a]<yy[b]; });
    } else {
        stable_sort(init.begin(),init.end(),[&](int a,int b){ return st[a]<st[b]; });
    }

    long long globalBest=LLONG_MAX; int globalH=INT_MAX; vector<int> globalY;
    auto t0=chrono::steady_clock::now();
    auto elapsed=[&](){ return chrono::duration<double>(chrono::steady_clock::now()-t0).count(); };
    volatile bool done=false;

    #pragma omp parallel num_threads(threads)
    {
        int tid=omp_get_thread_num();
        mt19937_64 rng(seed0*1000003ULL+tid*7919ULL);
        State* S=new State(); S->init(init);
        int n=S->ord.size();
        vector<int> posOf(N,-1);
        for(int p=0;p<n;p++) posOf[S->ord[p]]=p;
        double Tstart=5000.0, Tend=0.2;
        long long it=0, acc=0;
        double lastReport=0;
        uniform_real_distribution<double> U01(0,1);

        while(true){
            if((it&63)==0){ if(elapsed()>secs||done) break; }
            it++;
            double frac=min(1.0,elapsed()/secs);
            double temp=Tstart*pow(Tend/Tstart,frac);

            int i,pi;
            { int tries=0; do{ i=S->ord[rng()%n]; tries++; } while(S->y[i]+ln[i]<S->H-80&&tries<50); }
            pi=posOf[i];

            int mv=rng()%100;
            int a;
            if(mv<45){
                int span=1+(rng()%3==0? rng()%n : rng()%300);
                int pj=(rng()&1)? max(0,pi-span):min(n-1,pi+span);
                if(pj==pi) continue;
                a=min(pi,pj);
                vector<int> saved(S->ord.begin()+a, S->ord.begin()+max(pi,pj)+1);
                int x=S->ord[pi];
                if(pj<pi){ for(int p=pi;p>pj;p--) S->ord[p]=S->ord[p-1]; S->ord[pj]=x; }
                else{ for(int p=pi;p<pj;p++) S->ord[p]=S->ord[p+1]; S->ord[pj]=x; }
                int H2; long long c2=S->eval(a,H2);
                long long d=c2-S->cost;
                if(d<=0||U01(rng)<exp(-d/temp)){
                    S->full(a/K); acc++;
                    for(int p=a;p<=max(pi,pj);p++) posOf[S->ord[p]]=p;
                } else copy(saved.begin(),saved.end(),S->ord.begin()+a);
            } else {
                int j; int tries=0;
                do{
                    int span=1+(rng()%4==0? rng()%n : rng()%400);
                    int pj=(rng()&1)? max(0,pi-span):min(n-1,pi+span);
                    j=S->ord[pj]; tries++;
                } while((j==i||st[j]>=en[i]||st[i]>=en[j])&&tries<20);
                if(j==i) continue;
                int pj=posOf[j];
                swap(S->ord[pi],S->ord[pj]);
                a=min(pi,pj);
                int H2; long long c2=S->eval(a,H2);
                long long d=c2-S->cost;
                if(d<=0||U01(rng)<exp(-d/temp)){
                    S->full(a/K); acc++;
                    posOf[i]=pj; posOf[j]=pi;
                } else swap(S->ord[pi],S->ord[pj]);
            }

            if(S->cost<globalBest){
                #pragma omp critical
                {
                    if(S->cost<globalBest){
                        globalBest=S->cost; globalH=S->H; globalY=S->y;
                        ofstream o(outf);
                        for(int k=0;k<N;k++) o<<globalY[k]<<"\n";
                        fprintf(stderr,"[%.1fs] t%d best cost=%lld H=%d\n",elapsed(),tid,globalBest,globalH);
                        if(globalH<=TARGET) done=true;
                    }
                }
            }
            if(tid==0&&elapsed()-lastReport>10){
                lastReport=elapsed();
                fprintf(stderr,"  [%.0fs] t0 it=%lld acc=%lld cost=%lld H=%d temp=%.2f\n",
                    lastReport,it,acc,S->cost,S->H,temp);
            }
        }
    }
    printf("FINAL best cost=%lld H=%d\n",globalBest,globalH);
    return 0;
}
