import sys, os, time
from ortools.sat.python import cp_model
sys.path.insert(0, os.path.abspath('bench'))
from analyze_p15 import ops, pos

def solve(tail_start=199800, time_limit=30.0):
    TOTAL_OPS = len(ops)
    del_map = { -o: i for i, o in enumerate(ops) if o < 0 }

    pre_pipes = []
    pid = 0
    for i in range(tail_start):
        o = ops[i]
        if o > 0:
            pid += 1
            s = pos[i]
            d_op = del_map.get(pid, TOTAL_OPS)
            if d_op >= tail_start:
                pre_pipes.append((pid, s, o, i, d_op))

    tail_adds = []
    for i in range(tail_start, TOTAL_OPS):
        o = ops[i]
        if o > 0:
            pid += 1
            d_op = del_map.get(pid, TOTAL_OPS)
            tail_adds.append((i, pid, o, i, d_op))

    print(f"Testing tail_start={tail_start}: {len(tail_adds)} tail additions, {len(pre_pipes)} pre_pipes")

    def get_allowed_domain(length, add_op, del_op, max_trench=418780):
        forbidden = []
        for pid, s, l, a_k, d_k in pre_pipes:
            if max(add_op, a_k) < min(del_op, d_k):
                f_start = max(0, s - length + 1)
                f_end = min(max_trench - length, s + l - 1)
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
        max_val = max_trench - length
        for fs, fe in merged:
            if fs > cur:
                allowed.append((cur, fs - 1))
            cur = max(cur, fe + 1)
        if cur <= max_val:
            allowed.append((cur, max_val))
            
        return allowed

    model = cp_model.CpModel()
    Y = []
    for idx, (op_i, pid_i, L_i, a_i, d_i) in enumerate(tail_adds):
        allowed = get_allowed_domain(L_i, a_i, d_i, 418780)
        dom = cp_model.Domain.FromIntervals(allowed)
        y_var = model.NewIntVarFromDomain(dom, f"Y_{idx}")
        Y.append(y_var)

    overlap_count = 0
    for i in range(len(tail_adds)):
        op_i, pid_i, L_i, a_i, d_i = tail_adds[i]
        for j in range(i + 1, len(tail_adds)):
            op_j, pid_j, L_j, a_j, d_j = tail_adds[j]
            if max(a_i, a_j) < min(d_i, d_j):
                overlap_count += 1
                b = model.NewBoolVar(f"b_{i}_{j}")
                model.Add(Y[i] + L_i <= Y[j]).OnlyEnforceIf(b)
                model.Add(Y[j] + L_j <= Y[i]).OnlyEnforceIf(b.Not())

    print(f"Tail-tail overlaps: {overlap_count}")
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit
    solver.parameters.num_search_workers = 8
    t0 = time.time()
    status = solver.Solve(model)
    t1 = time.time()
    print(f"Solver status: {solver.StatusName(status)} in {t1-t0:.2f}s")
    if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
        print("FEASIBLE SOLUTION FOUND!")
        return True, solver, Y, tail_adds
    return False, None, None, None

if __name__ == '__main__':
    for ts in [199800, 199700, 199600, 199500]:
        ok, solver, Y, tail_adds = solve(ts, time_limit=20.0)
        if ok:
            break
