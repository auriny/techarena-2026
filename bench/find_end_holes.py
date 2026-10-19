import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

# Find all pipes alive at the end (op 200000)
# And build an occupied map of the trench at the END of the contest
end_occ = {}
pid = 0
for idx, (op, s) in enumerate(zip(ops, pos)):
    if op > 0:
        pid += 1
        end_occ[pid] = (s, op)
    else:
        del end_occ[-op]

# Exclude the 7 permanent high pipes we want to move
target_pids = {4009, 4010, 4011, 4012, 4013, 4014, 4016}
for p in target_pids:
    del end_occ[p]

# Check which cells in 0..418780 are NEVER occupied by any pipe from op 5001 to op 200000
# For each cell, track if any pipe was alive there at any time >= op 5001
# Since trench is 418780, we can use an interval set or check the empty holes at the end!
end_occupied_intervals = sorted(end_occ.values())
end_merged = []
for s, l in end_occupied_intervals:
    e = s + l
    if not end_merged or s > end_merged[-1][1]:
        end_merged.append([s, e])
    else:
        end_merged[-1][1] = max(end_merged[-1][1], e)

end_holes = []
cur = 0
for s, e in end_merged:
    if s > cur:
        end_holes.append((cur, s - cur))
    cur = max(cur, e)
if 418780 > cur:
    end_holes.append((cur, 418780 - cur))

print(f"Empty holes at the end of Test 15 (up to 418780m):")
for h in end_holes:
    print(f"  Hole at {h[0]}: length {h[1]}")
