import glob
from pathlib import Path

with open('bench/tests/15.in') as f:
    tokens = f.read().split()
n = int(tokens[1])
cmds = [int(x) for x in tokens[2:2+n]]

del_map = {}
for i, c in enumerate(cmds):
    if c < 0:
        del_map[-c] = i

p_lens = [c for c in cmds if c > 0]
perm_pids = [pid for pid in range(1, len(p_lens) + 1) if del_map.get(pid, n) == n]

files = glob.glob('target/reverse-15-*.txt')
print(f'Checking {len(files)} files for permanent pipe height...')

best_perm_h = 10**9
best_fn = None
below_780 = []

for fn in files:
    with open(fn) as f:
        pos = [int(x) for x in f]
    perm_h = max(pos[pid - 1] + p_lens[pid - 1] for pid in perm_pids)
    if perm_h < best_perm_h:
        best_perm_h = perm_h
        best_fn = fn
    if perm_h <= 418780:
        below_780.append((perm_h, fn))

print(f'Best permanent pipe height: {best_perm_h} in {best_fn}')
print(f'Total files with permanent height <= 418780: {len(below_780)}')
for h, fn in sorted(below_780)[:10]:
    print(f'  {fn}: perm_height = {h}')
