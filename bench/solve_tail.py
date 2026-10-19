import sys
from ortools.sat.python import cp_model
from analyze_p15 import ops, pos

TAIL_START = 199950
TOTAL_OPS = len(ops)

# 1. Map deletions
del_map = {}
for i, o in enumerate(ops):
    if o < 0:
        del_map[-o] = i

# 2. Pre-existing pipes alive at TAIL_START
pre_pipes = [] # (pid, start, len, add_op, del_op)
pid = 0
for i in range(TAIL_START):
    o = ops[i]
    if o > 0:
        pid += 1
        s = pos[i]
        d_op = del_map.get(pid, TOTAL_OPS)
        if d_op >= TAIL_START:
            pre_pipes.append((pid, s, o, i, d_op))

print(f"Pre-existing pipes alive at {TAIL_START}: {len(pre_pipes)}")

# 3. Tail additions to place
tail_adds = [] # (op_idx, pid, length, add_op, del_op)
for i in range(TAIL_START, TOTAL_OPS):
    o = ops[i]
    if o > 0:
        pid += 1
        d_op = del_map.get(pid, TOTAL_OPS)
        tail_adds.append((i, pid, o, i, d_op))

print(f"Tail additions: {len(tail_adds)}")

# 4. Build allowed domains for each tail addition
# For each tail addition i, an obstacle k conflicts if [add_i, del_i] overlaps [add_k, del_k]
# Two intervals [a1, d1] and [a2, d2] overlap if max(a1, a2) <= min(d1, d2)
# If they overlap, start Y_i cannot be in [s_k - L_i + 1, s_k + L_k - 1]

def get_allowed_domain(length, add_op, del_op, max_trench=418780):
    forbidden = []
    for pid, s, l, a_k, d_k in pre_pipes:
        # Check time overlap
        if max(add_op, a_k) < min(del_op, d_k):
            # Overlaps in time!
            f_start = max(0, s - length + 1)
            f_end = min(max_trench - length, s + l - 1)
            if f_start <= f_end:
                forbidden.append((f_start, f_end))
                
    # Merge forbidden
    forbidden.sort()
    merged_forbidden = []
    for s, e in forbidden:
        if not merged_forbidden or s > merged_forbidden[-1][1] + 1:
            merged_forbidden.append([s, e])
        else:
            merged_forbidden[-1][1] = max(merged_forbidden[-1][1], e)
            
    # Complement with [0, max_trench - length]
    allowed = []
    cur = 0
    max_val = max_trench - length
    for fs, fe in merged_forbidden:
        if fs > cur:
            allowed.append((cur, fs - 1))
        cur = max(cur, fe + 1)
    if cur <= max_val:
        allowed.append((cur, max_val))
        
    return allowed

# 5. Build CP-SAT Model
model = cp_model.CpModel()
Y = []
allowed_domains_info = []

for idx, (op_i, pid_i, L_i, a_i, d_i) in enumerate(tail_adds):
    allowed = get_allowed_domain(L_i, a_i, d_i, 418780)
    flat_intervals = []
    total_slots = sum(e - s + 1 for s, e in allowed)
    for s, e in allowed:
        flat_intervals.extend([s, e])
    dom = cp_model.Domain.FromIntervals(allowed)
    y_var = model.NewIntVarFromDomain(dom, f"Y_{idx}_pid_{pid_i}")
    Y.append(y_var)
    allowed_domains_info.append((len(allowed), total_slots))
    print(f"Tail {idx} (Op {op_i}, pid {pid_i}, len {L_i}): {len(allowed)} allowed intervals, {total_slots} valid start positions")

# 6. Add non-overlap constraints between tail additions that overlap in time
overlap_count = 0
for i in range(len(tail_adds)):
    op_i, pid_i, L_i, a_i, d_i = tail_adds[i]
    for j in range(i + 1, len(tail_adds)):
        op_j, pid_j, L_j, a_j, d_j = tail_adds[j]
        # Do they overlap in time?
        if max(a_i, a_j) < min(d_i, d_j):
            overlap_count += 1
            # Either Y[i] + L_i <= Y[j] or Y[j] + L_j <= Y[i]
            b = model.NewBoolVar(f"b_{i}_{j}")
            model.Add(Y[i] + L_i <= Y[j]).OnlyEnforceIf(b)
            model.Add(Y[j] + L_j <= Y[i]).OnlyEnforceIf(b.Not())

print(f"Total tail-tail time overlaps to enforce: {overlap_count}")

# 7. Solve!
solver = cp_model.CpSolver()
solver.parameters.max_time_in_seconds = 10.0
status = solver.Solve(model)

if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
    print("\nCP-SAT FOUND A FEASIBLE PLACEMENT FOR ALL 27 TAIL ADDITIONS!")
    for idx, (op_i, pid_i, L_i, a_i, d_i) in enumerate(tail_adds):
        val = solver.Value(Y[idx])
        print(f"  Op {op_i}: len={L_i:2d}, pos={val}, span=[{val}..{val+L_i}]")
else:
    print(f"CP-SAT Status: {solver.StatusName(status)}")
