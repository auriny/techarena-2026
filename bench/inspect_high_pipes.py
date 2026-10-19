import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

# Check every pipe placed >= 418700
high_pipes = []
for idx, (op, s) in enumerate(zip(ops, pos)):
    if op > 0 and s >= 418700:
        high_pipes.append((idx, op, s, s + op))

print(f'Total pipes placed with start >= 418700: {len(high_pipes)}')
for p in high_pipes:
    print(f'Op {p[0]:6d}: len={p[1]:2d}, [{p[2]} .. {p[3]}]')
