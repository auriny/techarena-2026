import sys
from pathlib import Path
from ortools.sat.python import cp_model

values = Path('bench/tests/15.in').read_text().split()
weight, n = float(values[0]), int(values[1])
cmds = list(map(int, values[2:2+n]))
plan = list(map(int, Path('target/plan-15-418781.txt').read_text().split()))

# Build pipes info
pipes = []
del_map = {}
for t, c in enumerate(cmds):
    if c > 0:
        pipes.append({'pid': len(pipes), 'len': c, 'start': t, 'end': n})
    else:
        pid = -c - 1
        pipes[pid]['end'] = t
        del_map[pid] = t

for TAIL_START in [199900, 199850, 199800, 199750, 199700]:
    print(f"\n================ Trying TAIL_START = {TAIL_START} ================")
    
    # Pre-existing pipes alive at or after TAIL_START
    pre_pipes = []
    for p, s in zip(pipes, plan):
        if p['start'] < TAIL_START:
            if p['end'] > TAIL_START:
                pre_pipes.append((p['pid'], s, p['len'], p['start'], p['end']))
                
    # Tail additions
    tail_adds = []
    for p, s in zip(pipes, plan):
        if p['start'] >= TAIL_START:
            tail_adds.append((p['pid'], s, p['len'], p['start'], p['end']))
            
    print(f"Pre-existing pipes alive: {len(pre_pipes)}, Tail additions: {len(tail_adds)}")
    
    # We want max trench <= 418780
    MAX_TRENCH = 418780
    
    def get_allowed_domain(length, add_op, del_op):
        forbidden = []
        for pid, s, l, a_k, d_k in pre_pipes:
            if max(add_op, a_k) < min(del_op, d_k):
                f_start = max(0, s - length + 1)
                f_end = min(MAX_TRENCH - length, s + l - 1)
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
        max_val = MAX_TRENCH - length
        for fs, fe in merged:
            if fs > cur:
                allowed.append((cur, fs - 1))
            cur = max(cur, fe + 1)
        if cur <= max_val:
            allowed.append((cur, max_val))
        return allowed

    model = cp_model.CpModel()
    Y = []
    possible = True
    for idx, (pid, orig_s, L, a_op, d_op) in enumerate(tail_adds):
        allowed = get_allowed_domain(L, a_op, d_op)
        if not allowed:
            print(f"  Pipe {pid+1} (len {L}) has NO allowed positions!")
            possible = False
            break
        dom = cp_model.Domain.FromIntervals(allowed)
        y_var = model.NewIntVarFromDomain(dom, f"Y_{pid}")
        Y.append(y_var)
        
    if not possible:
        continue
        
    print(f"All {len(tail_adds)} tail additions have non-empty domains in [0..{MAX_TRENCH}]!")
    
    # Time overlaps
    overlap_count = 0
    for i in range(len(tail_adds)):
        pid_i, _, L_i, a_i, d_i = tail_adds[i]
        for j in range(i + 1, len(tail_adds)):
            pid_j, _, L_j, a_j, d_j = tail_adds[j]
            if max(a_i, a_j) < min(d_i, d_j):
                overlap_count += 1
                b = model.NewBoolVar(f"b_{i}_{j}")
                model.Add(Y[i] + L_i <= Y[j]).OnlyEnforceIf(b)
                model.Add(Y[j] + L_j <= Y[i]).OnlyEnforceIf(b.Not())
                
    print(f"Adding {overlap_count} non-overlap constraints...")
    
    # Objective: prefer positions close to orig_s
    # Minimize max coordinate
    max_coord = model.NewIntVar(0, MAX_TRENCH, "max_coord")
    for idx, (pid, _, L, _, _) in enumerate(tail_adds):
        model.Add(max_coord >= Y[idx] + L)
    model.Minimize(max_coord)
    
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 20.0
    solver.parameters.num_workers = 8
    status = solver.Solve(model)
    
    print(f"CP-SAT Status: {solver.StatusName(status)}")
    if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
        res_max = solver.Value(max_coord)
        print(f"FOUND SOLUTION with max coordinate: {res_max} <= {MAX_TRENCH}!")
        # Create new plan
        new_plan = list(plan)
        for idx, (pid, _, L, _, _) in enumerate(tail_adds):
            new_plan[pid] = solver.Value(Y[idx])
            
        out_file = Path('target/plan-15-champion.txt')
        out_file.write_text('\n'.join(map(str, new_plan)) + '\n')
        print(f"Saved champion plan to {out_file}!")
        sys.exit(0)
