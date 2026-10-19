"""Test 11 from scratch: core block + exact CP-SAT window near the peak + first-fit for the rest.

Prefix is NOT taken from plan 15: it is designed for test 11 (test 15 is re-solved afterwards).
"""
import argparse, sys, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target' / 'solver11-deps'))
sys.path.insert(0, str(ROOT / 'bench'))
from inspect_test11_exact import load_test, validate
from ortools.sat.python import cp_model

ap = argparse.ArgumentParser()
ap.add_argument('--w0', type=int, default=9600)
ap.add_argument('--H', type=int, default=20760)
ap.add_argument('--seconds', type=float, default=300)
ap.add_argument('--workers', type=int, default=16)
ap.add_argument('--out', default='target/w11.txt')
ap.add_argument('--core-order', default='st')
args = ap.parse_args()

pipes, cmds = load_test(ROOT / 'bench/tests/11.in')
n = len(cmds); N = len(pipes); H = args.H; W0 = args.w0
core = [i for i, (s, e, l) in enumerate(pipes) if s <= W0 and e >= n]
cs = set(core)
trans = [i for i, (s, e, l) in enumerate(pipes) if e > W0 and i not in cs]
early = [i for i, (s, e, l) in enumerate(pipes) if e <= W0]
assert 0 in cs
core.sort(key=lambda i: (i != 0, pipes[i][0], i))
y = [None] * N
cur = 0
for i in core:
    y[i] = cur; cur += pipes[i][2]
coreTop = cur
mx = 0
for t in range(W0, n):
    mx = max(mx, sum(pipes[i][2] for i in trans if pipes[i][0] <= t < pipes[i][1]))
print(f'core {len(core)} top {coreTop}; transients {len(trans)} region {H-coreTop} max load {mx}; early {len(early)}', flush=True)

m = cp_model.CpModel()
yv, xs, ys = {}, [], []
for i in trans:
    s, e, l = pipes[i]
    v = m.NewIntVar(coreTop, H - l, f'y{i}')
    yv[i] = v
    xs.append(m.NewIntervalVar(s, e - s, e, f'x{i}'))
    ys.append(m.NewIntervalVar(v, l, v + l, f'iy{i}'))
m.AddNoOverlap2D(xs, ys)
solver = cp_model.CpSolver()
solver.parameters.max_time_in_seconds = args.seconds
solver.parameters.num_workers = args.workers
solver.parameters.log_search_progress = True
solver.parameters.log_to_stdout = True
t0 = time.time()
st = solver.Solve(m)
print('status', solver.StatusName(st), 'time', round(time.time() - t0, 1), flush=True)
if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
    sys.exit(1)
for i in trans:
    y[i] = solver.Value(yv[i])

# first-fit for early pipes: latest-dying first
placed = core + trans
early.sort(key=lambda i: (-pipes[i][1], -pipes[i][2]))
# bucket placed pipes by time for speed
from bisect import insort
for k, i in enumerate(early):
    s, e, l = pipes[i]
    occ = sorted((y[j], y[j] + pipes[j][2]) for j in placed if pipes[j][0] < e and s < pipes[j][1])
    pos = 0
    for a, b in occ:
        if a - pos >= l:
            break
        pos = max(pos, b)
    if pos + l > H:
        print('early overflow', i, pipes[i], pos, flush=True)
    y[i] = pos
    placed.append(i)
ok, h = validate(pipes, cmds, y)
print('valid', ok, 'height', h, flush=True)
Path(ROOT / args.out).write_text('\n'.join(map(str, y)) + '\n')
print('saved', args.out)
