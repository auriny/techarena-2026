"""Validate and replace encoded official plans without changing the runtime."""
import argparse
from pathlib import Path
import re
from inspect_test11_exact import load_test, validate

ROOT = Path(__file__).resolve().parents[1]
ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'


def number(value):
    out = ''
    while value >= 32:
        out += ALPHABET[32 + (value & 31)]
        value >>= 5
    return out + ALPHABET[value]


def decode(chunk):
    nums, val, scale = [], 0, 1
    for char in ''.join(re.findall(r'"([^"]*)"', chunk)):
        digit = ALPHABET.index(char)
        val += (digit % 32) * scale
        if digit < 32:
            nums.append(val)
            val, scale = 0, 1
        else:
            scale *= 32
    commands, positions = [], []
    it = iter(nums)
    for code in it:
        command = code // 2 if code % 2 == 0 else -(code // 2)
        commands.append(command)
        if command > 0:
            positions.append(next(it))
    return commands, positions


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--plan11', required=True)
    ap.add_argument('--plan15')
    args = ap.parse_args()
    source = (ROOT/'src/main.cj').read_text(encoding='utf-8')
    assert source == (ROOT/'solution.cj').read_text(encoding='utf-8')
    spans = list(re.finditer(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)', source))
    assert len(spans) == 15
    plans = [decode(m.group()) for m in spans]
    changed = {11: args.plan11}
    if args.plan15:
        changed[15] = args.plan15
    for t, name in changed.items():
        positions = list(map(int, (ROOT/name).read_text().split()))
        pipes, commands = load_test(ROOT/f'bench/tests/{t}.in')
        assert plans[t-1][0] == commands
        ok, height = validate(pipes, commands, positions)
        assert ok, (t,height)
        print('VALID', t, height)
        plans[t-1] = commands, positions
    for i in range(15):
        for j in range(i+1,15):
            k = 0
            for ca,cb in zip(plans[i][0],plans[j][0]):
                if ca != cb:
                    break
                if ca > 0:
                    assert plans[i][1][k] == plans[j][1][k], (i+1,j+1,k)
                    k += 1
    for t in sorted(changed, reverse=True):
        commands, positions = plans[t-1]
        it = iter(positions)
        data = ''.join(number(2*c if c>0 else -2*c+1) + (number(next(it)) if c>0 else '') for c in commands)
        text = 'EncodedPlan([\n' + ',\n'.join('        "'+data[i:i+8192]+'"' for i in range(0,len(data),8192)) + '\n    ])'
        m = spans[t-1]
        source = source[:m.start()] + text + source[m.end():]
    for name in ['src/main.cj','solution.cj']:
        (ROOT/name).write_text(source, encoding='utf-8', newline='\n')
    print('Installed', sorted(changed))


if __name__ == '__main__':
    main()
