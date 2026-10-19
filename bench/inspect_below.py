import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

pipes_below = []
pid = 0
for idx, (op, s) in enumerate(zip(ops, pos)):
    if op > 0:
        pid += 1
        if 418600 <= s < 418704:
            pipes_below.append((idx, pid, op, s, s + op))

pipes_below.sort(key=lambda x: x[3])
print(f'Pipes near 418600..418704:')
for p in pipes_below:
    print(f'Op {p[0]:6d} (pid={p[1]:6d}): len={p[2]:2d}, [{p[3]} .. {p[4]}]')
