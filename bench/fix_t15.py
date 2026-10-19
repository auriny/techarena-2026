import sys
sys.path.insert(0, 'bench')
from analyze_p15 import ops, pos

print(f"Total ops: {len(ops)}, Total pos: {len(pos)}")

# Check validity of original plan first:
class InteractorValidator:
    def __init__(self):
        self.cells = []
        self.intervals = [(-1, -1)]

    def step(self, op_idx, instr, shift):
        if instr > 0:
            req_len = instr
            end = shift + req_len
            if end > len(self.cells):
                n = (end - len(self.cells) + 19) // 20
                self.cells.extend([-1] * (n * 20))
            for i in range(req_len):
                if self.cells[shift + i] >= 0:
                    raise ValueError(f"Op {op_idx}: collision at {shift+i} with PID {self.cells[shift+i]}")
                self.cells[shift + i] = len(self.intervals)
            self.intervals.append((shift, req_len))
        else:
            pid = -instr
            pos, plen = self.intervals[pid]
            if pos != shift:
                raise ValueError(f"Op {op_idx}: wrong free pos {shift} vs {pos}")
            for i in range(plen):
                if self.cells[pos + i] != pid:
                    raise ValueError(f"Op {op_idx}: mismatch in cell at {pos+i}")
                self.cells[pos + i] = -1
            self.intervals[pid] = (-1, -1)

# Let's test the validator on original pos
print("Validating original positions...")
val = InteractorValidator()
for idx, (op, s) in enumerate(zip(ops, pos)):
    val.step(idx + 1, op, s if op > 0 else val.intervals[-op][0])
print(f"Original validation success! Trench: {len(val.cells)}")
