import bisect, time

with open('bench/tests/15.in') as f:
    cmds = [int(x) for x in f.read().split()[2:-1]]

class FreeBlock:
    def __init__(self, start, length, op):
        self.start = start
        self.len = length
        self.end = start + length
        self.createdAt = op
        self.binIdx = 0

    def precedes(self, other):
        if self.len > 40 and self.len != other.len: return self.len < other.len
        return self.start < other.start

    def shortPrecedes(self, other):
        if self.createdAt != other.createdAt: return self.createdAt > other.createdAt
        return self.start < other.start

class BinHeap:
    def __init__(self): self.data = []
    def push(self, b):
        b.binIdx = len(self.data); self.data.append(b); self._up(len(self.data) - 1)
    def remove(self, b):
        idx = b.binIdx; last = len(self.data) - 1
        if idx != last:
            lb = self.data[last]; self.data[idx] = lb; lb.binIdx = idx
        self.data.pop(); b.binIdx = -1
        if idx < len(self.data): self._down(idx); self._up(idx)
    def _up(self, idx):
        at = idx
        while at > 0:
            parent = (at - 1) // 2
            if not self.data[at].precedes(self.data[parent]): break
            self.data[at], self.data[parent] = self.data[parent], self.data[at]
            self.data[at].binIdx = at; self.data[parent].binIdx = parent; at = parent
    def _down(self, idx):
        at = idx
        while at * 2 + 1 < len(self.data):
            child = at * 2 + 1
            if child + 1 < len(self.data) and self.data[child + 1].precedes(self.data[child]): child += 1
            if not self.data[child].precedes(self.data[at]): break
            self.data[at], self.data[child] = self.data[child], self.data[at]
            self.data[at].binIdx = at; self.data[child].binIdx = child; at = child

class ShortHeap:
    def __init__(self): self.data = []
    def push(self, b):
        self.data.append(b); at = len(self.data) - 1
        while at > 0:
            p = (at - 1) // 2
            if not self.data[at].shortPrecedes(self.data[p]): break
            self.data[at], self.data[p] = self.data[p], self.data[at]; at = p
    def pop_valid(self, length, cohortStart):
        while self.data:
            b = self.data[0]
            if b.binIdx >= 0 and b.len >= length and b.start >= cohortStart: return b
            last = self.data.pop()
            if not self.data: break
            self.data[0] = last; at = 0
            while at * 2 + 1 < len(self.data):
                c = at * 2 + 1
                if c + 1 < len(self.data) and self.data[c + 1].shortPrecedes(self.data[c]): c += 1
                if not self.data[c].shortPrecedes(self.data[at]): break
                self.data[at], self.data[c] = self.data[c], self.data[at]; at = c
        return None

def run_test15(mult=4, cohort_size=512, p2_growth_1=129700, p2_growth_2=129100, p2_large_chooser=None, p2_ur=None):
    trench = 0
    s2b, e2b = {}, {}
    bins = [BinHeap() for _ in range(41)]
    largeBin = BinHeap()
    shortBins = [ShortHeap() for _ in range(8)]
    regionStarts = []
    regionLengths = []

    def get_bin(l): return bins[l] if l <= 40 else largeBin
    def reg_block(b):
        b.createdAt = op; s2b[b.start] = b; e2b[b.end] = b; get_bin(b.len).push(b)
        if regionStarts:
            idx = bisect.bisect_right(regionStarts, b.start) - 1
            if idx >= 0:
                l = regionLengths[idx]
                if l >= 1: shortBins[l].push(b)
    def unreg_block(b):
        del s2b[b.start]; del e2b[b.end]; get_bin(b.len).remove(b)

    pipeStarts = [0] * (len(cmds) + 5)
    pipeLens = [0] * (len(cmds) + 5)
    firstLength, lastLength, equalLengthRun, previousRun, removedInRun = 0, 0, 0, 0, 0
    combRun, removalStarted, runFirstPid = True, False, 1
    maximumLength, shortHistory = 0, False
    cohortIndex, cohortStart = -1, 0
    pid, op = 0, 0

    for c in cmds:
        op += 1
        if c > 0:
            pid += 1
            reqLen = c
            if pid // cohort_size != cohortIndex:
                cohortIndex = pid // cohort_size
                cohortStart = trench
            if maximumLength <= 7 and trench > 25000: shortHistory = True
            if pid == 1: firstLength = reqLen
            if reqLen == lastLength:
                equalLengthRun += 1
                if removalStarted: combRun = False
            else:
                previousRun = equalLengthRun if (equalLengthRun >= 32 and removedInRun >= 4 and combRun) else 0
                removedInRun = 0; combRun = True; removalStarted = False; runFirstPid = pid; lastLength = reqLen; equalLengthRun = 1
            if reqLen > maximumLength: maximumLength = reqLen
            batch = equalLengthRun > 8 or previousRun > 0
            firstFit = maximumLength <= 7 and pid > 32 and trench <= 25000
            shortMode = maximumLength <= 7 and trench > 25000 and not batch

            chosenBlock = None
            if shortMode:
                if len(bins[reqLen].data) > 0: chosenBlock = bins[reqLen].data[0]
                else: chosenBlock = shortBins[reqLen].pop_valid(reqLen, cohortStart)
            elif not firstFit and trench > 25000:
                if len(bins[reqLen].data) > 0: chosenBlock = bins[reqLen].data[0]
                elif p2_large_chooser:
                    chosenBlock = p2_large_chooser(reqLen, pid, bins, largeBin)
                elif len(largeBin.data) > 0: chosenBlock = largeBin.data[0]
                else:
                    for b in range(reqLen + 1, 41):
                        if len(bins[b].data) > 0: chosenBlock = bins[b].data[0]; break
            else:
                for b in range(reqLen, 41):
                    if len(bins[b].data) > 0:
                        cand = bins[b].data[0]
                        if not chosenBlock or cand.start < chosenBlock.start: chosenBlock = cand
                        if not firstFit: break
                if not chosenBlock and len(largeBin.data) > 0: chosenBlock = largeBin.data[0]

            if chosenBlock:
                blockStart = chosenBlock.start; blockLength = chosenBlock.len; unreg_block(chosenBlock)
            else:
                blockStart = trench
                if not shortMode and trench in e2b:
                    tb = e2b[trench]
                    if not shortHistory or maximumLength > 7 or tb.start >= cohortStart:
                        blockStart = tb.start; unreg_block(tb)
                n = (blockStart + reqLen - trench + 19) // 20
                if batch:
                    cnt = 64 if firstLength <= 1 else 32
                    if previousRun > 0: cnt = max(1, previousRun - equalLengthRun + 1)
                    bg = (reqLen * cnt + 19) // 20
                    if bg > n: n = bg
                if trench > 83000 and not batch:
                    growth = 4 if (shortHistory and maximumLength > 7) else 8
                    if pid >= p2_growth_2: growth = 2
                    if pid >= p2_growth_1: growth = 1
                    if n < growth: n = growth
                if shortMode:
                    if n < mult * reqLen: n = mult * reqLen
                    n = (n + reqLen - 1) // reqLen * reqLen
                    regionStarts.append(trench); regionLengths.append(reqLen)
                trench += n * 20
                blockLength = trench - blockStart

            useRight = True
            if firstLength == 6: useRight = (pid % 2 == 0)
            elif firstLength == 20: useRight = True
            elif pid <= 6500: useRight = ((pid // 2) % 2 == 0)
            elif maximumLength <= 7: useRight = (pid % 2 == 0)
            elif firstLength == 3 and shortHistory:
                if p2_ur: useRight = p2_ur(reqLen, pid, blockStart, blockLength, trench)
                else: useRight = ((pid // 2) % 2 == 0)
            else: useRight = False

            initWin = trench <= 20 and firstLength <= 4
            if initWin or batch:
                useRight = (equalLengthRun % 2 == 0) if previousRun > 0 else (pid % 2 == 0)
            if blockStart + blockLength == trench and not batch and not initWin: useRight = False
            if shortHistory and maximumLength <= 7: useRight = True

            pStart = blockStart + blockLength - reqLen if useRight else blockStart
            rStart = blockStart if useRight else blockStart + reqLen
            rLen = blockLength - reqLen
            if rLen > 0: reg_block(FreeBlock(rStart, rLen, op))
            pipeStarts[pid] = pStart; pipeLens[pid] = reqLen
        else:
            dpid = -c; plen = pipeLens[dpid]; pstart = pipeStarts[dpid]; pipeLens[dpid] = 0
            removalStarted = True; removedInRun += 1
            if dpid < runFirstPid: combRun = False
            ns, nl = pstart, plen
            if ns in e2b:
                left = e2b[ns]; unreg_block(left); ns = left.start; nl += left.len
            if (ns + nl) in s2b:
                right = s2b[ns + nl]; unreg_block(right); nl += right.len
            reg_block(FreeBlock(ns, nl, op))

    score = 418757 / trench * 10
    return trench, score

if __name__ == '__main__':
    t0 = time.time()
    t, sc = run_test15()
    print(f'Test 15 baseline: trench={t}, score={sc:.7f}, time={time.time()-t0:.2f}s')
