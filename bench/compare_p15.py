import subprocess, re

def decode(p_str):
    strs = re.findall(r'"([^"]*)"', p_str)
    full_str = ''.join(strs)
    nums, scale, val = [], 1, 0
    for c in full_str:
        code = ord(c)
        if 65 <= code <= 90: d = code - 65
        elif 97 <= code <= 122: d = code - 71
        elif 48 <= code <= 57: d = code + 4
        elif code == 43: d = 62
        else: d = 63
        val += (d % 32) * scale
        if d < 32: nums.append(val); val, scale = 0, 1
        else: scale *= 32
    ops, pos, i = [], [], 0
    while i < len(nums):
        c = nums[i]; i += 1
        cmd = c // 2 if c % 2 == 0 else -(c // 2)
        ops.append(cmd)
        if cmd > 0: pos.append(nums[i]); i += 1
        else: pos.append(-1)
    return ops, pos

old_cj = subprocess.check_output(['git', 'show', '02620f4:solution.cj'], text=True)
cur_cj = subprocess.check_output(['git', 'show', '1e53dc7:solution.cj'], text=True)
p_old = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', old_cj[old_cj.find('func makePlans()'):])[14]
p_cur = re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', cur_cj[cur_cj.find('func makePlans()'):])[14]
o_old, pos_old = decode(p_old)
o_cur, pos_cur = decode(p_cur)

diffs = [(i, o_old[i], pos_old[i], pos_cur[i]) for i in range(len(o_old)) if pos_old[i] != pos_cur[i]]
print(f'Total position diffs in Plan 15: {len(diffs)}')
for d in diffs[:25]:
    print(f'Op {d[0]}: cmd={d[1]}, old_pos={d[2]}, cur_pos={d[3]}')

# Trench in old vs cur:
def get_trench(ops, pos):
    t = 0
    for o, s in zip(ops, pos):
        if o > 0:
            end = s + o
            if end > t:
                n = (end - t + 19) // 20
                t += n * 20
    return t

print(f'Old trench: {get_trench(o_old, pos_old)}')
print(f'Cur trench: {get_trench(o_cur, pos_cur)}')
