import sys
from ortools.sat.python import cp_model
from analyze_p15 import ops, pos

# 1. Simulate occupancy up to op 199950
# Track which pipes from 1..199950 are alive at 199950
# For those alive, find their deletion op (if in 199950..200000) or end
TAIL_START = 199950
TOTAL_OPS = len(ops)

pipe_spans = {} # pid -> (s, o, add_op, del_op)
del_map = {}
for i, o in enumerate(ops):
    if o < 0:
        del_map[-o] = i

pid = 0
for i in range(TAIL_START):
    o = ops[i]
    if o > 0:
        pid += 1
        s = pos[i]
        d_op = del_map.get(pid, TOTAL_OPS)
        if d_op >= TAIL_START:
            pipe_spans[pid] = (s, o, i, d_op)

print(f"Pre-existing pipes alive at {TAIL_START}: {len(pipe_spans)}")

# Now collect tail additions
tail_adds = [] # (op_idx, pid, length, del_op)
for i in range(TAIL_START, TOTAL_OPS):
    o = ops[i]
    if o > 0:
        pid += 1
        d_op = del_map.get(pid, TOTAL_OPS)
        tail_adds.append((i, pid, o, d_op))

print(f"Tail additions to place: {len(tail_adds)}")
for a in tail_adds:
    print(f"  Op {a[0]} (pid={a[1]}): len={a[2]}, alive {a[0]}..{a[3]}")

# Build spatial intervals:
# The obstacles are the pre-existing pipes. But note that 99.9% of the trench is static!
# Let's find which cells in 0..418780 are free at some point during TAIL_START..TOTAL_OPS.
cells_ever_free = [True] * 418780

# If a pre-existing pipe is never deleted (del_op == TOTAL_OPS), its cells are NEVER free
for pid, (s, l, add_op, del_op) in pipe_spans.items():
    if del_op >= TOTAL_OPS:
        for c in range(s, min(s + l, 418780)):
            cells_ever_free[c] = False

# What cells are EVER free?
free_intervals = []
cur = None
for c in range(418780):
    if cells_ever_free[c]:
        if cur is None: cur = c
    else:
        if cur is not None:
            free_intervals.append((cur, c - cur))
            cur = None
if cur is not None:
    free_intervals.append((cur, 418780 - cur))

print(f"\nCandidate free intervals in 0..418780 during tail: {len(free_intervals)}")
for s, l in free_intervals:
    if l >= 5:
        print(f"  [{s} .. {s+l}], len {l}")
