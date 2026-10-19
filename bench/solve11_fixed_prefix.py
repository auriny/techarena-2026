"""Solve test 11 with FIXED prefix from test 15 plan.

The first 4006 pipe positions are taken from candidate-best15.txt.
The remaining 2487 suffix pipes are optimized via CP-SAT.
"""
import argparse, sys, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target' / 'solver11-deps'))
sys.path.insert(0, str(ROOT / 'bench'))
from inspect_test11_exact import load_test, validate
from ortools.sat.python import cp_model

ap = argparse.ArgumentParser()
ap.add_argument('--prefix', default='target/candidate-best15.txt')
ap.add_argument('--H', type=int, default=20760)
ap.add_argument('--seconds', type=float, default=300)
ap.add_argument('--workers', type=int, default=16)
ap.add_argument('--out', default='target/w11-fp.txt')
args = ap.parse_args()

pipes, cmds = load_test(ROOT / 'bench/tests/11.in')
n = len(cmds); N = len(pipes); H = args.H; P = 4006
prefix_y = list(map(int, (ROOT / args.prefix).read_text().split()))[:P]

y = [None] * N
for i in range(P):
    y[i] = prefix_y[i]

suffix = list(range(P, N))
print(f'N={N} P={P} suffix={len(suffix)} H={H}', flush=True)

# Compute domains for each suffix pipe: which y ranges are free from prefix overlap
# For pipe i with [st_i, en_i), find all prefix pipes j with overlapping time,
# then subtract their occupied intervals from [0, H-len_i]
def compute_domains(i):
    si, ei, li = pipes[i]
    # collect occupied intervals from prefix pipes alive during [si, ei)
    occ = []
    for j in range(P):
        sj, ej, lj = pipes[j]
        if sj < ei and si < ej:  # time overlap
            occ.append((y[j], y[j] + lj))
    # merge
    occ.sort()
    merged = []
    for a, b in occ:
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    # gaps
    dom = []
    lo = 0
    for a, b in merged:
        if a > lo and a - lo >= li:
            dom.append((lo, a - li))
        lo = max(lo, b)
    if H - lo >= li:
        dom.append((lo, H - li))
    return dom

print('Computing domains...', flush=True)
t0 = time.time()
domains = {}
for i in suffix:
    d = compute_domains(i)
    if not d:
        print(f'EMPTY domain for pipe {i} {pipes[i]}!', flush=True)
        sys.exit(1)
    domains[i] = d
print(f'Domains computed in {time.time()-t0:.1f}s', flush=True)

# Build CP-SAT model
m = cp_model.CpModel()
yv = {}
xs, ys = [], []
for i in suffix:
    d = domains[i]
    intervals = [(lo, hi) for lo, hi in d]
    v = m.NewIntVarFromDomain(cp_model.Domain.FromIntervals(intervals), f'y{i}')
    yv[i] = v
    si, ei, li = pipes[i]
    xs.append(m.NewIntervalVar(si, ei - si, ei, f'x{i}'))
    ys.append(m.NewIntervalVar(v, li, v + li, f'iy{i}'))

m.AddNoOverlap2D(xs, ys)

# Hints from existing plans
for hf in ['target/test11-resume.txt', 'target/test11-tabu.txt']:
    hp = ROOT / hf
    if hp.exists():
        hy = list(map(int, hp.read_text().split()))
        can_hint = all(hy[i] == prefix_y[i] for i in range(P))
        if can_hint:
            print(f'Using hints from {hf}', flush=True)
            for i in suffix:
                m.AddHint(yv[i], hy[i])
            break
        else:
            print(f'Hints from {hf}: prefix mismatch, skipping', flush=True)

solver = cp_model.CpSolver()
solver.parameters.max_time_in_seconds = args.seconds
solver.parameters.num_workers = args.workers
solver.parameters.log_search_progress = True
solver.parameters.log_to_stdout = True

class BestCb(cp_model.CpSolverSolutionCallback):
    def __init__(self):
        super().__init__()
        self.best = None; self.bh = 10**9; self.cnt = 0
    def on_solution_callback(self):
        self.cnt += 1
        sol = {i: self.Value(yv[i]) for i in suffix}
        h = max(sol[i] + pipes[i][2] for i in suffix)
        ph = max(y[j] + pipes[j][2] for j in range(P))
        h = max(h, ph)
        if h < self.bh:
            self.bh = h
            self.best = sol
            print(f'  CB#{self.cnt} H={h}', flush=True)

cb = BestCb()
t0 = time.time()
st = solver.Solve(m, cb)
print(f'Status: {solver.StatusName(st)} time={time.time()-t0:.1f}s', flush=True)

if cb.best:
    for i in suffix:
        y[i] = cb.best[i]
    ok, h = validate(pipes, cmds, y)
    print(f'Valid: {ok}, height: {h}', flush=True)
    if ok:
        # Check prefix match
        pdiff = sum(1 for i in range(P) if y[i] != prefix_y[i])
        print(f'Prefix diffs: {pdiff}', flush=True)
        (ROOT / args.out).write_text('\n'.join(map(str, y)) + '\n')
        print(f'Saved {args.out}')
elif st in (cp_model.OPTIMAL, cp_model.FEASIBLE):
    for i in suffix:
        y[i] = solver.Value(yv[i])
    ok, h = validate(pipes, cmds, y)
    print(f'Valid: {ok}, height: {h}', flush=True)
    if ok:
        (ROOT / args.out).write_text('\n'.join(map(str, y)) + '\n')
        print(f'Saved {args.out}')
else:
    print('No solution found!', flush=True)
