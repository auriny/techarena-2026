import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sim import TrenchSim, FreeBlock

with open('bench/tests/15.in') as f:
    toks = f.read().split()
cmds = [int(x) for x in toks[2:2+int(toks[1])]]

def run(sim_cls):
    sim = sim_cls()
    p = 0
    for c in cmds:
        if c > 0:
            p += 1
            sim.allocate(p, c)
        else:
            sim.free(-c)
    return sim.trench_length, 418757 / sim.trench_length * 10

# Test 1: What if in Phase 2, largeBin is ordered by address vs length?
# Test 2: What if largeBin blocks are only split if remainder >= 8 or 0?
# Test 3: What if trench tail expansion in Phase 2 has different count?

print("Baseline:", run(TrenchSim))
