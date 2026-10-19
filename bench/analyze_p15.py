import re

with open('solution.cj', encoding='utf-8') as f:
    text = f.read()

start = text.find('func makePlans()')
plans_text = text[start:]
plan_chunks = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', plans_text)

# Plan 15 is plan_chunks[14]
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

# Now simulate Plan 15 trench length!
trench = 0
max_end = 0
high_ends = []
for idx, (op, start) in enumerate(zip(ops, pos)):
    if op > 0:
        end = start + op
        if end > max_end:
            max_end = end
        if end > trench:
            n = (end - trench + 19) // 20
            trench += n * 20
            if trench > 418760:
                high_ends.append((idx, op, start, end, trench))

print(f'Final trench: {trench}')
print(f'Max pipe end coordinate: {max_end}')
print('Expansions beyond 418760:')
for h in high_ends:
    print(f'Op {h[0]}: len={h[1]}, start={h[2]}, end={h[3]} -> trench={h[4]}')
