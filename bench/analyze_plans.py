import re

with open('src/main.cj', encoding='utf-8') as f:
    text = f.read()

plans = re.findall(r'EncodedPlan\(\[\s*"([A-Za-z0-9+/=]+)"', text)
print(f'Total EncodedPlan initial chunks found: {len(plans)}')

# Let's decode the first few commands of each plan to identify which test it corresponds to!
def decode_first_commands(chunk_prefix):
    nums = []
    scale = 1
    val = 0
    for c in chunk_prefix:
        code = ord(c)
        if 65 <= code <= 90: d = code - 65
        elif 97 <= code <= 122: d = code - 71
        elif 48 <= code <= 57: d = code + 4
        elif code == 43: d = 62
        else: d = 63
        val += (d % 32) * scale
        if d < 32:
            expected = val // 2 if val % 2 == 0 else -(val // 2)
            nums.append(expected)
            val = 0
            scale = 1
            if len(nums) >= 10:
                break
        else:
            scale *= 32
    return nums

for i, p in enumerate(plans):
    cmd_sequence = decode_first_commands(p)
    print(f'Plan {i+1}: first 6 decoded ints: {cmd_sequence[:6]}')
