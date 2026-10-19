import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

high_pipes = []
pid = 0
for idx, (op, s) in enumerate(zip(ops, pos)):
    if op > 0:
        pid += 1
        if s >= 418700:
            high_pipes.append((idx, pid, op, s, s + op))

high_pids = {p[1]: p for p in high_pipes}
del_times = {}
for idx, op in enumerate(ops):
    if op < 0 and (-op) in high_pids:
        del_times[-op] = idx

print('All 28 high pipes and their deletion times:')
for p in high_pipes:
    idx, pid, op, s, e = p
    dt = del_times.get(pid, 'NEVER (ALIVE AT END)')
    print(f'Op {idx:6d} (pid={pid:6d}): len={op:2d}, [{s} .. {e}], deleted at op: {dt}')
