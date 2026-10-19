"""Re-solve test 15 for a given shared prefix (first 4006 positions taken from a test-11 plan).

Layout:
  * zone [0, Z): prefix pipes (fixed). At the end only the 2947 permanent ones remain;
    gaps between them are tiled exactly by "core" pipes (alive from <=W0 to the end),
    respecting the time when non-permanent prefix pipes vacate the cells.
  * remaining core stacked above Z, sorted by creation time.
  * transients (alive in [W0, n) but not core): exact CP-SAT above the core.
  * everything else: reverse-time sweep, leftmost fit in the currently free cells (outside the zone).
"""
import argparse, sys, time
from array import array
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target' / 'solver11-deps'))
sys.path.insert(0, str(ROOT / 'bench'))
from inspect_test11_exact import load_test, validate
from ortools.sat.python import cp_model

ap = argparse.ArgumentParser()
ap.add_argument('--plan11', required=True)
ap.add_argument('--w0', type=int, default=199900)
ap.add_argument('--H', type=int, default=418780)
ap.add_argument('--seconds', type=float, default=300)
ap.add_argument('--workers', type=int, default=16)
ap.add_argument('--out', default='target/w15.txt')
args = ap.parse_args()

pipes, cmds = load_test(ROOT / 'bench/tests/15.in')
n = len(cmds); N = len(pipes); H = args.H; W0 = args.w0; P = 4006
y11 = list(map(int, (ROOT / args.plan11).read_text().split()))
y = [None] * N
for i in range(P):
    y[i] = y11[i]
Z = max(y[i] + pipes[i][2] for i in range(P))
print('zone top', Z, flush=True)

core = [i for i in range(P, N) if pipes[i][0] <= W0 and pipes[i][1] >= n]
trans = [i for i in range(P, N) if pipes[i][1] > W0 and not (pipes[i][0] <= W0 and pipes[i][1] >= n)]
early = [i for i in range(P, N) if pipes[i][1] <= W0]
assert all(pipes[i][1] <= W0 or pipes[i][1] == n for i in range(P)), 'prefix pipe alive in window but not permanent'

# --- zone tiling
occ_end = bytearray(Z)
freet = [0] * Z
for i in range(P):
    s, e, l = pipes[i]
    for c in range(y[i], y[i] + l):
        if e == n:
            occ_end[c] = 1
        else:
            freet[c] = max(freet[c], e)
pool = {l: [] for l in range(1, 41)}
for i in core:
    pool[pipes[i][2]].append(i)
for l in pool:
    pool[l].sort(key=lambda i: pipes[i][0])
pool_st = {l: [pipes[i][0] for i in pool[l]] for l in pool}
from bisect import bisect_left
used = set()
c = 0
zone_fill = 0
holes = []
while c < Z:
    if occ_end[c]:
        c += 1; continue
    r = c
    while r < Z and not occ_end[r]:
        r += 1
    p = c
    while p < r:
        done = False
        for l in range(min(40, r - p), 0, -1):
            if not pool[l]:
                continue
            thr = max(freet[p:p + l])
            k = bisect_left(pool_st[l], thr)
            if k < len(pool[l]):
                j = pool[l].pop(k); pool_st[l].pop(k)
                y[j] = p; used.add(j); p += l; zone_fill += 1; done = True
                break
        if not done:
            holes.append(p); p += 1
    c = r
print('zone filled with', zone_fill, 'core pipes; untiled cells', len(holes), flush=True)
# merge hole cells into segments of equal free time
segs = []
for p in holes:
    if segs and segs[-1][1] == p and segs[-1][2] == freet[p]:
        segs[-1][1] = p + 1
    else:
        segs.append([p, p + 1, freet[p]])
runs = []  # maximal contiguous hole runs (for domains)
for a, b, f in segs:
    if runs and runs[-1][1] == a:
        runs[-1][1] = b
    else:
        runs.append([a, b])
print('hole segments', segs[:20], flush=True)
stack = sorted((i for i in core if i not in used), key=lambda i: (pipes[i][0], i))
cur = Z
for i in stack:
    y[i] = cur; cur += pipes[i][2]
coreTop = cur
mx = 0
ev = {}
for i in trans:
    s, e, l = pipes[i]
    ev[max(s, W0)] = ev.get(max(s, W0), 0) + l
    ev[e] = ev.get(e, 0) - l
run = 0
for t in sorted(ev):
    run += ev[t]; mx = max(mx, run)
print(f'core {len(core)} top {coreTop}; transients {len(trans)} region {H - coreTop}+holes {len(holes)} max load {mx}; early {len(early)}', flush=True)

# --- CP-SAT window
m = cp_model.CpModel()
yv, xs, ys = {}, [], []
for i in trans:
    s, e, l = pipes[i]
    dom = [[coreTop, H - l]] + [[a, b - l] for a, b in runs if b - a >= l]
    v = m.NewIntVarFromDomain(cp_model.Domain.FromIntervals(sorted(dom)), f'y{i}')
    yv[i] = v
    xs.append(m.NewIntervalVar(s, e - s, e, f'x{i}'))
    ys.append(m.NewIntervalVar(v, l, v + l, f'iy{i}'))
for a, b, f in segs:  # cell busy (prefix pipe) until f
    xs.append(m.NewIntervalVar(0, f, f, f'hx{a}'))
    ys.append(m.NewIntervalVar(a, b - a, b, f'hy{a}'))
m.AddNoOverlap2D(xs, ys)
solver = cp_model.CpSolver()
solver.parameters.max_time_in_seconds = args.seconds
solver.parameters.num_workers = args.workers
t0 = time.time()
st = solver.Solve(m)
print('status', solver.StatusName(st), 'time', round(time.time() - t0, 1), flush=True)
if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
    sys.exit(1)
for i in trans:
    y[i] = solver.Value(yv[i])

# --- reverse sweep for early pipes (segment tree of free runs)
SZ = 1 << 19
assert H <= SZ
mxr = array('i', [0]) * (2 * SZ); pre = array('i', [0]) * (2 * SZ); suf = array('i', [0]) * (2 * SZ)
lz = array('b', [-1]) * (2 * SZ)
length = array('i', [0]) * (2 * SZ)
for x in range(SZ, 2 * SZ):
    length[x] = 1
for x in range(SZ - 1, 0, -1):
    length[x] = 2 * length[2 * x]

def setnode(x, free):
    v = length[x] if free else 0
    mxr[x] = pre[x] = suf[x] = v; lz[x] = free

def pull(x):
    a, b = 2 * x, 2 * x + 1
    pre[x] = pre[a] if pre[a] < length[a] else length[a] + pre[b]
    suf[x] = suf[b] if suf[b] < length[b] else length[b] + suf[a]
    mxr[x] = max(mxr[a], mxr[b], suf[a] + pre[b])

def push(x):
    if lz[x] >= 0:
        setnode(2 * x, lz[x]); setnode(2 * x + 1, lz[x]); lz[x] = -1

def update(x, l, r, a, b, free):
    if a <= l and r <= b:
        setnode(x, free); return
    push(x); m_ = (l + r) >> 1
    if a < m_: update(2 * x, l, m_, a, b, free)
    if b > m_: update(2 * x + 1, m_, r, a, b, free)
    pull(x)

def leftmost(need):
    if mxr[1] < need:
        return -1
    x, l, r = 1, 0, SZ
    while x < SZ:
        push(x)
        m_ = (l + r) >> 1
        a, b = 2 * x, 2 * x + 1
        if mxr[a] >= need:
            x, r = a, m_
        elif suf[a] + pre[b] >= need:
            return m_ - suf[a]
        else:
            x, l = b, m_
    return l

for x in range(SZ, 2 * SZ):
    setnode(x, 1 if (x - SZ) < H else 0)
for x in range(SZ - 1, 0, -1):
    pull(x)
update(1, 0, SZ, 0, Z, 0)  # zone is reserved
add_at = [[] for _ in range(n)]
rem_at = [[] for _ in range(n)]
for i in range(P, N):
    if y[i] is not None and y[i] < Z:
        continue  # zone pipes handled by reservation
    s, e, l = pipes[i]
    add_at[e - 1].append(i)
    rem_at[s].append(i)
earlyset = set(early)
t0 = time.time()
for t in range(n - 1, -1, -1):
    for i in add_at[t]:
        l = pipes[i][2]
        if i in earlyset:
            pos = leftmost(l)
            if pos < 0 or pos + l > H:
                raise SystemExit(f'no room for early pipe {i} {pipes[i]} at t={t}')
            y[i] = pos
        update(1, 0, SZ, y[i], y[i] + pipes[i][2], 0)
    for i in rem_at[t]:
        update(1, 0, SZ, y[i], y[i] + pipes[i][2], 1)
print('sweep done', round(time.time() - t0, 1), 's', flush=True)
ok, h = validate(pipes, cmds, y)
print('valid', ok, 'height', h, flush=True)
(ROOT / args.out).write_text('\n'.join(map(str, y)) + '\n')
print('saved', args.out)
