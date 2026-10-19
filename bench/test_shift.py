with open('bench/tests/15.in') as f:
    tokens = f.read().split()
n = int(tokens[1])
cmds = [int(x) for x in tokens[2:2+n]]

with open('target/candidate-best15.txt') as f:
    orig_pos = [int(x) for x in f]

p_lens = [c for c in cmds if c > 0]

for S in [1, 2, 5, 10, 13, 18]:
    pos = list(orig_pos)
    for pid in range(len(pos)):
        if pos[pid] >= 20361:
            pos[pid] -= S
            
    # Check validity
    cells = bytearray(max(pos) + 50)
    live = {}
    p = 0
    valid = True
    first_conflict = None
    for op_idx, c in enumerate(cmds):
        if c > 0:
            s = pos[p]
            p += 1
            if any(cells[s:s+c]):
                valid = False
                first_conflict = (op_idx, p, s, c)
                break
            cells[s:s+c] = b'\1' * c
            live[p] = (s, c)
        else:
            s, length = live.pop(-c)
            cells[s:s+length] = b'\0' * length
            
    if valid:
        max_h = max(s + l for s, l in zip(pos, p_lens))
        print(f"Shift S={S:2d}: VALID! max_height = {max_h}, rounded = {(max_h+19)//20*20}")
    else:
        op_idx, p, s, c = first_conflict
        print(f"Shift S={S:2d}: Collision at op {op_idx}, pipe {p}, len {c}, span [{s}..{s+c}]")
