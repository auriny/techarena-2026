from bench.analyze_p15 import ops, pos

adds = [(idx, op, s) for idx, (op, s) in enumerate(zip(ops, pos)) if op > 0]
print(f'Total additions: {len(adds)}')

# Check positions distribution:
buckets = [0] * 22
for idx, op, s in adds:
    b = s // 20000
    if b < len(buckets):
        buckets[b] += 1
    else:
        print(f's >= 440000: {s}')

for b, count in enumerate(buckets):
    print(f'Range {b*20000:7d}..{(b+1)*20000:7d}: {count:6d} pipes')
