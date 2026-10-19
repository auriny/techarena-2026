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

target_pid = 130232 # PID 130233
target_pipe = pipes[target_pid]

conflicts = []
for p in pipes:
    if p['pid'] == target_pid: continue
    if max(p['start'], target_pipe['start']) < min(p['end'], target_pipe['end']):
        s = plan[p['pid']]
        e = s + p['len']
        if max(s, 418719) < min(e, 418759):
            conflicts.append(p)

print(f"Conflicts for PID 130233 at [418719..418759]: {len(conflicts)}")
for c in conflicts:
    print(f"  PID {c['pid']+1}: len={c['len']}, pos=[{plan[c['pid']]}..{plan[c['pid']]+c['len']}], span=[{c['start']}..{c['end']}]")
