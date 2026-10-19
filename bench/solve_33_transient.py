import sys
from pathlib import Path
from ortools.sat.python import cp_model

values = Path('bench/tests/15.in').read_text().split()
weight, n = float(values[0]), int(values[1])
cmds = list(map(int, values[2:2+n]))
plan = list(map(int, Path('target/plan-15-418781.txt').read_text().split()))

pipes = []
for t, c in enumerate(cmds):
    if c > 0:
        pipes.append({'pid': len(pipes), 'len': c, 'start': t, 'end': n})
    else:
        pipes[-c - 1]['end'] = t

# Movable set:
# 1. PID 130149 (pid 130148)
# 2. PID 130233 (pid 130232)
# 3. All transient pipes with start >= 199750 and end < n
movable_pids = set()
movable_pids.add(130148)
movable_pids.add(130232)

for p in pipes:
    if p['start'] >= 199750 and p['end'] < n:
        movable_pids.add(p['pid'])

print(f"Total movable transient pipes: {len(movable_pids)}")

fixed_pipes = [p for p in pipes if p['pid'] not in movable_pids]
movable_list = [p for p in pipes if p['pid'] in movable_pids]

MAX_TRENCH = 418780

def get_allowed_domain(p):
    L = p['len']
    a_op, d_op = p['start'], p['end']
    forbidden = []
    for f in fixed_pipes:
        if max(a_op, f['start']) < min(d_op, f['end']):
            s = plan[f['pid']]
            l = f['len']
            f_start = max(0, s - L + 1)
            f_end = min(MAX_TRENCH - L, s + l - 1)
            if f_start <= f_end:
                forbidden.append((f_start, f_end))
    forbidden.sort()
    merged = []
    for s, e in forbidden:
        if not merged or s > merged[-1][1] + 1:
            merged.append([s, e])
        else:
            merged[-1][1] = max(merged[-1][1], e)
    allowed = []
    cur = 0
    max_val = MAX_TRENCH - L
    for fs, fe in merged:
        if fs > cur:
            allowed.append((cur, fs - 1))
        cur = max(cur, fe + 1)
    if cur <= max_val:
        allowed.append((cur, max_val))
    return allowed

model = cp_model.CpModel()
Y = {}

for p in movable_list:
    pid = p['pid']
    allowed = get_allowed_domain(p)
    if not allowed:
        print(f"Pipe {pid+1} (len {p['len']}) has NO allowed positions in [0..{MAX_TRENCH}]!")
        sys.exit(1)
    dom = cp_model.Domain.FromIntervals(allowed)
    y_var = model.NewIntVarFromDomain(dom, f"Y_{pid}")
    Y[pid] = y_var
    # Hint from current plan if within MAX_TRENCH
    orig_s = plan[pid]
    if orig_s + p['len'] <= MAX_TRENCH:
        model.AddHint(y_var, orig_s)

print("All movable transient pipes have valid domains!")

# Pairwise non-overlap
overlap_count = 0
for i in range(len(movable_list)):
    p_i = movable_list[i]
    for j in range(i + 1, len(movable_list)):
        p_j = movable_list[j]
        if max(p_i['start'], p_j['start']) < min(p_i['end'], p_j['end']):
            overlap_count += 1
            b = model.NewBoolVar(f"b_{p_i['pid']}_{p_j['pid']}")
            model.Add(Y[p_i['pid']] + p_i['len'] <= Y[p_j['pid']]).OnlyEnforceIf(b)
            model.Add(Y[p_j['pid']] + p_j['len'] <= Y[p_i['pid']]).OnlyEnforceIf(b.Not())

print(f"Added {overlap_count} non-overlap constraints among movable pipes.")

# Minimize max coordinate
max_coord = model.NewIntVar(0, MAX_TRENCH, "max_coord")
for p in movable_list:
    model.Add(max_coord >= Y[p['pid']] + p['len'])
model.Minimize(max_coord)

solver = cp_model.CpSolver()
solver.parameters.max_time_in_seconds = 60.0
solver.parameters.num_workers = 8
status = solver.Solve(model)

print(f"CP-SAT Status: {solver.StatusName(status)}")
if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
    res_max = solver.Value(max_coord)
    print(f"SUCCESS! Solved with max coordinate: {res_max} <= {MAX_TRENCH}!")
    new_plan = list(plan)
    for p in movable_list:
        new_plan[p['pid']] = solver.Value(Y[p['pid']])
    
    out_file = Path('target/plan-15-champion.txt')
    out_file.write_text('\n'.join(map(str, new_plan)) + '\n')
    print(f"Saved champion plan to {out_file}!")
