// Test 11 solver: A-block (prefix pipes alive at peak) packed at the bottom,
// everything else ordered and compacted by skyline gravity; SA over the order.
// usage: sa11 <seconds> <threads> <seed> <init_file_or_-> <out_file> [target]
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

int N, T;                 // pipes, commands
vector<int> st, en, ln;
const int SZ = 16384;
int TARGET = 20760;
const int TP = 9818;

struct Seg {
    int mx[2 * SZ], lz[2 * SZ];  // lz = -1 none
    void clear() { memset(mx, 0, sizeof mx); memset(lz, -1, sizeof lz); }
    inline void app(int x, int v) { mx[x] = v; lz[x] = v; }
    inline void push(int x) { if (lz[x] >= 0) { app(2*x, lz[x]); app(2*x+1, lz[x]); lz[x] = -1; } }
    int query(int x, int l, int r, int a, int b) {
        if (a <= l && r <= b) return mx[x];
        push(x); int m = (l + r) / 2, res = 0;
        if (a < m) res = max(res, query(2*x, l, m, a, b));
        if (b > m) res = max(res, query(2*x+1, m, r, a, b));
        return res;
    }
    void assign(int x, int l, int r, int a, int b, int v) {
        if (a <= l && r <= b) { app(x, v); return; }
        push(x); int m = (l + r) / 2;
        if (a < m) assign(2*x, l, m, a, b, v);
        if (b > m) assign(2*x+1, m, r, a, b, v);
        mx[x] = max(mx[2*x], mx[2*x+1]);
    }
    inline int place(int i) {  // returns y
        int y = query(1, 0, SZ, st[i], en[i]);
        assign(1, 0, SZ, st[i], en[i], y + ln[i]);
        return y;
    }
};

vector<int> Aorder, U;      // fixed A order, U pipes
Seg baseSeg;                // skyline after A
vector<int> baseY;

static inline long long fcost(int top) {
    long long o = top - TARGET;
    long long c = 0;
    if (o > 0) c += 1000LL * o;
    int m = top - (TARGET - 8);
    if (m > 0) c += m;   // mild pressure near the top
    return c;
}

const int K = 128;
struct State {
    vector<int> ord;          // order of U
    vector<int> y;            // y per pipe id
    vector<Seg*> snap;        // snap[k] = skyline before position k*K
    vector<long long> pc;     // cost before checkpoint
    vector<int> ph;           // height before checkpoint
    long long cost; int H;
    Seg work;
    void init(const vector<int>& o) {
        ord = o; y = baseY; int nc = (ord.size() + K - 1) / K + 1;
        snap.resize(nc); for (auto& s : snap) if (!s) s = new Seg();
        pc.assign(nc, 0); ph.assign(nc, 0);
        full(0);
    }
    // recompute from checkpoint c, updating snapshots and y
    void full(int c) {
        memcpy(&work, c == 0 ? &baseSeg : snap[c], sizeof(Seg));
        if (c == 0) { pc[0] = 0; ph[0] = 0; for (int i : Aorder) ph[0] = max(ph[0], baseY[i] + ln[i]); memcpy(snap[0], &baseSeg, sizeof(Seg)); }
        long long cs = pc[c]; int h = ph[c];
        for (int p = c * K; p < (int)ord.size(); p++) {
            if (p % K == 0 && p / K != c) { memcpy(snap[p / K], &work, sizeof(Seg)); pc[p / K] = cs; ph[p / K] = h; }
            int i = ord[p]; int yy = work.place(i); y[i] = yy; int top = yy + ln[i];
            cs += fcost(top); h = max(h, top);
        }
        cost = cs; H = h;
    }
    // evaluate candidate order (ord already modified) from position a, without committing
    long long eval(int a, int& Hout) {
        int c = a / K;
        memcpy(&work, snap[c], sizeof(Seg));
        long long cs = pc[c]; int h = ph[c];
        for (int p = c * K; p < (int)ord.size(); p++) {
            int i = ord[p]; int yy = work.place(i); int top = yy + ln[i];
            cs += fcost(top); h = max(h, top);
        }
        Hout = h; return cs;
    }
};

int main(int argc, char** argv) {
    double secs = argc > 1 ? atof(argv[1]) : 60;
    int threads = argc > 2 ? atoi(argv[2]) : 16;
    unsigned seed0 = argc > 3 ? atoi(argv[3]) : 1;
    string initf = argc > 4 ? argv[4] : "-";
    string outf = argc > 5 ? argv[5] : "target/sa11-best.txt";
    if (argc > 6) TARGET = atoi(argv[6]);
    ifstream f("bench/tests/11.in"); double w; f >> w >> T;
    vector<int> alive;
    for (int t = 0; t < T; t++) {
        int c; f >> c;
        if (c > 0) { st.push_back(t); en.push_back(T); ln.push_back(c); }
        else en[-c - 1] = t;
    }
    N = st.size();
    vector<int> A, others;
    for (int i = 0; i < N; i++) {
        if (i < 4006 && st[i] <= TP && TP < en[i]) A.push_back(i); else others.push_back(i);
    }
    // A order: pipe 0 first, permanent by st asc, then non-permanent by end desc
    vector<int> perm, nonp;
    for (int i : A) if (i != 0) (en[i] == T ? perm : nonp).push_back(i);
    sort(perm.begin(), perm.end(), [](int a, int b) { return st[a] < st[b]; });
    sort(nonp.begin(), nonp.end(), [](int a, int b) { return en[a] > en[b] || (en[a] == en[b] && a < b); });
    Aorder.push_back(0); for (int i : perm) Aorder.push_back(i); for (int i : nonp) Aorder.push_back(i);
    baseSeg.clear(); baseY.assign(N, 0);
    for (int i : Aorder) baseY[i] = baseSeg.place(i);
    int topA = 0; for (int i : Aorder) topA = max(topA, baseY[i] + ln[i]);
    fprintf(stderr, "N=%d A=%zu topA=%d U=%zu\n", N, A.size(), topA, others.size());

    // initial order of U
    vector<int> init = others;
    if (initf != "-") {
        ifstream g(initf); vector<int> yy(N); for (int i = 0; i < N; i++) g >> yy[i];
        stable_sort(init.begin(), init.end(), [&](int a, int b) { return yy[a] < yy[b]; });
    } else {
        // heuristic: by start time
        stable_sort(init.begin(), init.end(), [&](int a, int b) { return st[a] < st[b]; });
    }

    vector<long long> bestCost(threads, LLONG_MAX); vector<int> bestH(threads, INT_MAX);
    vector<vector<int>> bestY(threads);
    long long globalBest = LLONG_MAX; int globalH = INT_MAX; vector<int> globalY;
    auto t0 = chrono::steady_clock::now();
    auto elapsed = [&]() { return chrono::duration<double>(chrono::steady_clock::now() - t0).count(); };
    volatile bool done = false;

    #pragma omp parallel num_threads(threads)
    {
        int tid = omp_get_thread_num();
        mt19937_64 rng(seed0 * 1000003ULL + tid * 7919ULL);
        State* S = new State();
        S->init(init);
        int n = S->ord.size();
        vector<int> posOf(N, -1);
        for (int p = 0; p < n; p++) posOf[S->ord[p]] = p;
        double Tstart = 3000.0, Tend = 0.5;
        long long it = 0, acc = 0;
        double lastReport = 0;
        uniform_real_distribution<double> U01(0, 1);
        while (true) {
            if ((it & 63) == 0) {
                double e = elapsed();
                if (e > secs || done) break;
            }
            it++;
            double frac = min(1.0, elapsed() / secs);
            double temp = Tstart * pow(Tend / Tstart, frac);
            // pick a "hot" pipe: one with top above TARGET-40, else random
            int a, b;
            int mv = rng() % 100;
            // gather candidate: random position among top-heavy
            int i;
            {
                int tries = 0;
                do { i = S->ord[rng() % n]; tries++; } while (S->y[i] + ln[i] < S->H - 60 && tries < 40);
            }
            int pi = posOf[i];
            if (mv < 45) {
                // move pipe i earlier/later by random offset (insertion)
                int span = 1 + (rng() % 3 == 0 ? rng() % n : rng() % 200);
                int pj = (rng() & 1) ? max(0, pi - span) : min(n - 1, pi + span);
                if (pj == pi) continue;
                a = min(pi, pj);
                vector<int> saved(S->ord.begin() + a, S->ord.begin() + max(pi, pj) + 1);
                int x = S->ord[pi];
                if (pj < pi) { for (int p = pi; p > pj; p--) S->ord[p] = S->ord[p - 1]; S->ord[pj] = x; }
                else { for (int p = pi; p < pj; p++) S->ord[p] = S->ord[p + 1]; S->ord[pj] = x; }
                int H2; long long c2 = S->eval(a, H2);
                long long d = c2 - S->cost;
                if (d <= 0 || U01(rng) < exp(-d / temp)) {
                    S->full(a / K); acc++;
                    for (int p = a; p <= max(pi, pj); p++) posOf[S->ord[p]] = p;
                } else {
                    copy(saved.begin(), saved.end(), S->ord.begin() + a);
                }
            } else {
                // swap with another pipe overlapping in time (prefer near positions)
                int j;
                int tries = 0;
                do {
                    int span = 1 + (rng() % 4 == 0 ? rng() % n : rng() % 300);
                    int pj = (rng() & 1) ? max(0, pi - span) : min(n - 1, pi + span);
                    j = S->ord[pj]; tries++;
                } while ((j == i || st[j] >= en[i] || st[i] >= en[j]) && tries < 20);
                if (j == i) continue;
                int pj = posOf[j];
                swap(S->ord[pi], S->ord[pj]);
                a = min(pi, pj);
                int H2; long long c2 = S->eval(a, H2);
                long long d = c2 - S->cost;
                if (d <= 0 || U01(rng) < exp(-d / temp)) {
                    S->full(a / K); acc++;
                    posOf[i] = pj; posOf[j] = pi;
                } else swap(S->ord[pi], S->ord[pj]);
            }
            if (S->cost < bestCost[tid]) {
                bestCost[tid] = S->cost; bestH[tid] = S->H; bestY[tid] = S->y;
                #pragma omp critical
                {
                    if (S->cost < globalBest) {
                        globalBest = S->cost; globalH = S->H; globalY = S->y;
                        ofstream o(outf);
                        for (int k = 0; k < N; k++) o << globalY[k] << "\n";
                        fprintf(stderr, "[%.1fs] t%d best cost=%lld H=%d\n", elapsed(), tid, globalBest, globalH);
                        if (globalH <= TARGET) done = true;
                    }
                }
            }
            if (tid == 0 && elapsed() - lastReport > 10) {
                lastReport = elapsed();
                fprintf(stderr, "  [%.0fs] t0 it=%lld acc=%lld cost=%lld H=%d temp=%.2f\n", lastReport, it, acc, S->cost, S->H, temp);
            }
        }
    }
    printf("FINAL best cost=%lld H=%d\n", globalBest, globalH);
    return 0;
}
