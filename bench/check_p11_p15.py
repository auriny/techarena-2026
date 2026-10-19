import re

with open('solution.cj', encoding='utf-8') as f:
    text = f.read()

start = text.find('func makePlans()')
plans_text = text[start:]
plan_chunks = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', plans_text)

def decode(chunk_text):
    strs = re.findall(r'"([^"]*)"', chunk_text)
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
    ops, pos = [], []
    i = 0
    while i < len(nums):
        code = nums[i]
        i += 1
        cmd = code // 2 if code % 2 == 0 else -(code // 2)
        ops.append(cmd)
        if cmd > 0:
            pos.append(nums[i])
            i += 1
        else:
            pos.append(-1)
    return ops, pos

ops11, pos11 = decode(plan_chunks[10]) # Plan 11
ops15, pos15 = decode(plan_chunks[14]) # Plan 15

adds11 = [p for o, p in zip(ops11, pos11) if o > 0][:4006]
adds15 = [p for o, p in zip(ops15, pos15) if o > 0][:4006]

diffs = sum(1 for a, b in zip(adds11, adds15) if a != b)
print('Diffs between Plan 11 and Plan 15 in first 4006 adds:', diffs)
