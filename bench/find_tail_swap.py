from pathlib import Path

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

# We want max coordinate <= 418780
MAX_TRENCH = 418780

# The only problematic pipe in plan-15-418781.txt is PID 130149 (pid 130148), which has pos 418760, len 21 -> 418781 (exceeds 418780 by 1).
# All other pipes have s + L <= 418760.
target_pid = 130148
target_pipe = pipes[target_pid]

# Can target_pipe be placed at some s <= 418759?
# If target_pipe is at 418759, its span is [418759..418780], len 21.
# What does [418759..418780] collide with?
# Let's check what collides with [418759..418780] during target_pipe lifetime [199796..199982]:
conflicts_at_top = []
for p in pipes:
    if p['pid'] == target_pid: continue
    if max(p['start'], target_pipe['start']) < min(p['end'], target_pipe['end']):
        s = plan[p['pid']]
        e = s + p['len']
        if max(s, 418759) < min(e, 418780):
            conflicts_at_top.append(p)

print(f"Conflicts at [418759..418780]: {len(conflicts_at_top)}")
for c in conflicts_at_top:
    print(f"  PID {c['pid']+1}: len={c['len']}, pos=[{plan[c['pid']]}..{plan[c['pid']]+c['len']}], span=[{c['start']}..{c['end']}]")
