import re

with open('solution.cj', encoding='utf-8') as f:
    text = f.read()

start = text.find('func makePlans()')
plans_text = text[start:]
plan_chunks = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', plans_text)

p15 = plan_chunks[14]
strs = re.findall(r'"([^"]*)"', p15)
full_str = ''.join(strs)
nums = []
scale = 1
val = 0
for c in full_str:
    code = ord(c)
    if 65 <= code <= 90: d = code - 65
    elif 97 <= code <= 122: d = code - 71
    elif 48 <= code <= 57: d = code + 4
    elif code == 43: d = 62
    else: d = 63
    val += (d % 32) * scale
    if d < 32:
        nums.append(val)
        val = 0
        scale = 1
    else:
        scale *= 32

ops = []
pos = []
i = 0
while i < len(nums):
    code = nums[i]
    i += 1
    cmd = code // 2 if code % 2 == 0 else -(code // 2)
    ops.append(cmd)
    if cmd > 0:
        p_val = nums[i]
        i += 1
        pos.append(p_val)
    else:
        pos.append(-1)

# Let's track occupied cells and free intervals across all operations
pipe_intervals = {}
pid = 0

def get_free_intervals(trench_len):
    # build boolean occupied array up to trench_len
    # or intervals
    occupied = []
    for s, l in pipe_intervals.values():
        occupied.append((s, s + l))
    occupied.sort()
    merged = []
    for s, e in occupied:
        if not merged or s > merged[-1][1]:
            merged.append([s, e])
        else:
            merged[-1][1] = max(merged[-1][1], e)
    
    free_holes = []
    cur = 0
    for s, e in merged:
        if s > cur:
            free_holes.append((cur, s - cur))
        cur = e
    if trench_len > cur:
        free_holes.append((cur, trench_len - cur))
    return free_holes

for idx, (op, start) in enumerate(zip(ops, pos)):
    if op > 0:
        pid += 1
        pipe_intervals[pid] = (start, op)
        if idx in [5000, 5001, 199957, 199958]:
            free_holes = get_free_intervals(418780)
            large_holes = [h for h in free_holes if h[1] >= op]
            total_free = sum(h[1] for h in free_holes)
            print(f'Op {idx} (pid={pid}): reqLen={op}, start={start}')
            print(f'  Total free space within 418780m: {total_free}m')
            print(f'  Holes of size >= {op} within 418780m: {len(large_holes)}')
            if large_holes:
                print(f'  First 3 holes: {large_holes[:3]}')
    else:
        dpid = -op
        del pipe_intervals[dpid]
