import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

holes = [307, 363, 783, 797, 810, 908, 1369, 1928]

# Find which pipe was at each hole and when it was deleted
p_at = {}
pid = 0
for idx, op in enumerate(ops):
    if op > 0:
        pid += 1
        p_at[pid] = (idx, pos[idx], op)

for pid, (idx, s, l) in p_at.items():
    if s in holes:
        # find when deleted
        del_op = None
        for oi in range(idx + 1, len(ops)):
            if ops[oi] == -pid:
                del_op = oi
                break
        print(f'Hole at {s} (len {l}): originally PID {pid} (added at op {idx}), deleted at op {del_op}')
