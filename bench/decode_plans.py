import re

with open('solution.cj', encoding='utf-8') as f:
    text = f.read()

start = text.find('func makePlans()')
plans_text = text[start:]

plan_chunks = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', plans_text)
print('Total plans found:', len(plan_chunks))

for idx, p in enumerate(plan_chunks):
    strs = re.findall(r'"([^"]*)"', p)
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
    
    adds = [x for x in ops if x > 0]
    max_pos = max([p for p in pos if p >= 0]) if pos else 0
    print(f'Plan {idx+1:2d}: {len(ops):6d} ops (adds: {len(adds):5d}), max pos: {max_pos}')
