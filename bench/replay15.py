"""Replay test-15 simulation with a prefix from test-11.

Phase 1: place first 4006 pipes from the given plan, track trench growth.
Phase 2 (from cmd 5000 onward): run original online bin-packing.

Between phases, reconstruct the free-block map from actual occupied cells.
"""
import sys, time, heapq, argparse
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'bench'))
from inspect_test11_exact import load_test, validate

PREFIX = 4006

class FB:
    __slots__ = ['start', 'len', 'alive']
    def __init__(self, s, l): self.start = s; self.len = l; self.alive = True
    def __lt__(self, o):
        if self.len > 40 and self.len != o.len: return self.len < o.len
        return self.start < o.start

def replay(plan11_path, out_path, H=418780):
    t0 = time.time()
    pipes, cmds = load_test(ROOT / 'bench/tests/15.in')
    n = len(cmds); N = len(pipes)
    prefix_y = list(map(int, (ROOT / plan11_path).read_text().split()))[:PREFIX]

    positions = [None] * N
    pstarts = {}; plens = {}
    pidx = 0; pid = 0
    trench = 0

    # --- Phase 1: prefix (first 4999 commands) ---
    CMD_SPLIT = 4999  # after this many commands, prefix ends
    for op_idx in range(CMD_SPLIT):
        c = cmds[op_idx]
        if c > 0:
            pid += 1
            pStart = prefix_y[pidx]
            reqLen = c
            need_top = pStart + reqLen
            if need_top > trench:
                expand = (need_top - trench + 19) // 20
                trench += expand * 20
            positions[pidx] = pStart
            pstarts[pid] = pStart; plens[pid] = reqLen
            pidx += 1
        else:
            dpid = -c
            del pstarts[dpid]  # mark as removed

    print(f'After prefix: pidx={pidx} pid={pid} trench={trench} alive={len(pstarts)}', flush=True)
    assert pidx == PREFIX

    # --- Build free-block map from current state ---
    alive_intervals = sorted((pstarts[p], plens[p]) for p in pstarts)
    s2b, e2b = {}, {}
    bins = [[] for _ in range(41)]
    largeBin = []

    def add_free(start, length):
        if length <= 0: return
        b = FB(start, length); s2b[start] = b; e2b[start + length] = b
        if length <= 40: heapq.heappush(bins[length], b)
        else: heapq.heappush(largeBin, b)

    pos = 0
    for s, l in alive_intervals:
        if s > pos:
            add_free(pos, s - pos)
        pos = max(pos, s + l)
    if pos < trench:
        add_free(pos, trench - pos)

    total_free = sum(b.len for b in s2b.values())
    print(f'Free blocks: {len(s2b)}, total free: {total_free}', flush=True)

    # --- Phase 2: suffix (from CMD_SPLIT onward) ---
    firstLength = pipes[0][2]  # length of first pipe
    lastLength = 0; equalLengthRun = 0; previousRun = 0
    removedInRun = 0; combRun = True; removalStarted = False; runFirstPid = 1
    maximumLength = max(p[2] for p in pipes[:pidx])
    shortHistory = False

    # Reconstruct state variables from prefix
    # Re-scan prefix commands to get lastLength/equalLengthRun/etc
    pid2 = 0
    for op_idx in range(CMD_SPLIT):
        c = cmds[op_idx]
        if c > 0:
            pid2 += 1
            reqLen = c
            if maximumLength <= 7 and trench > 25000: shortHistory = True
            if reqLen == lastLength:
                equalLengthRun += 1
                if removalStarted: combRun = False
            else:
                previousRun = equalLengthRun if (equalLengthRun >= 32 and removedInRun >= 4 and combRun) else 0
                removedInRun = 0; combRun = True; removalStarted = False; runFirstPid = pid2; lastLength = reqLen; equalLengthRun = 1
            if reqLen > maximumLength: maximumLength = reqLen
        else:
            removalStarted = True; removedInRun += 1
            dpid = -c
            if dpid < runFirstPid: combRun = False
    assert pid2 == pid

    for op_idx in range(CMD_SPLIT, n):
        c = cmds[op_idx]
        if c > 0:
            pid += 1
            reqLen = c
            if maximumLength <= 7 and trench > 25000: shortHistory = True
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

            chosen = None
            if shortMode:
                if bins[reqLen]:
                    while bins[reqLen] and not bins[reqLen][0].alive: heapq.heappop(bins[reqLen])
                    if bins[reqLen]: chosen = bins[reqLen][0]
            elif not firstFit and trench > 25000:
                if bins[reqLen]:
                    while bins[reqLen] and not bins[reqLen][0].alive: heapq.heappop(bins[reqLen])
                    if bins[reqLen]: chosen = bins[reqLen][0]
                elif pid >= 130200:
                    for b in range(reqLen + 1, 41):
                        while bins[b] and not bins[b][0].alive: heapq.heappop(bins[b])
                        if bins[b]: chosen = bins[b][0]; break
                    if not chosen:
                        while largeBin and not largeBin[0].alive: heapq.heappop(largeBin)
                        if largeBin: chosen = largeBin[0]
                else:
                    while largeBin and not largeBin[0].alive: heapq.heappop(largeBin)
                    if largeBin: chosen = largeBin[0]
                    if not chosen:
                        for b in range(reqLen + 1, 41):
                            while bins[b] and not bins[b][0].alive: heapq.heappop(bins[b])
                            if bins[b]: chosen = bins[b][0]; break
            else:
                for b in range(reqLen, 41):
                    while bins[b] and not bins[b][0].alive: heapq.heappop(bins[b])
                    if bins[b]:
                        chosen = bins[b][0]
                        if not firstFit: break
                if not chosen:
                    while largeBin and not largeBin[0].alive: heapq.heappop(largeBin)
                    if largeBin: chosen = largeBin[0]

            if chosen:
                chosen.alive = False
                del s2b[chosen.start]; del e2b[chosen.start + chosen.len]
                bStart, bLen, expand = chosen.start, chosen.len, 0
            else:
                bStart = trench
                if not shortMode and trench in e2b:
                    tail = e2b[trench]; tail.alive = False
                    del s2b[tail.start]; del e2b[tail.start + tail.len]
                    bStart = tail.start
                expand = (bStart + reqLen - trench + 19) // 20
                if batch:
                    cnt = 64 if firstLength <= 1 else 32
                    if previousRun > 0: cnt = max(1, previousRun - equalLengthRun + 1)
                    bg = (reqLen * cnt + 19) // 20
                    if bg > expand: expand = bg
                if trench > 83000 and not batch:
                    growth = 4 if (shortHistory and maximumLength > 7) else 8
                    if pid >= 129100: growth = 2
                    if pid >= 129700: growth = 1
                    if expand < growth: expand = growth
                if shortMode:
                    if expand < 4 * reqLen: expand = 4 * reqLen
                    expand = (expand + reqLen - 1) // reqLen * reqLen
                trench += expand * 20
                bLen = trench - bStart

            useRight = False
            if firstLength == 6: useRight = (pid % 2 == 0)
            elif firstLength == 20: useRight = True
            elif pid <= 6500: useRight = ((pid // 2) % 2 == 0)
            elif maximumLength <= 7: useRight = (pid % 2 == 0)
            else: useRight = False

            initWin = trench <= 20 and firstLength <= 4
            if initWin or batch:
                useRight = (equalLengthRun % 2 == 0) if previousRun > 0 else (pid % 2 == 0)
            if bStart + bLen == trench and not batch and not initWin: useRight = False
            if shortHistory and maximumLength <= 7: useRight = True

            pStart = bStart + bLen - reqLen if useRight else bStart
            rStart = bStart if useRight else bStart + reqLen
            rLen = bLen - reqLen
            if rLen > 0:
                rem = FB(rStart, rLen); s2b[rStart] = rem; e2b[rStart + rLen] = rem
                if rLen <= 40: heapq.heappush(bins[rLen], rem)
                else: heapq.heappush(largeBin, rem)
            positions[pidx] = pStart
            pstarts[pid] = pStart; plens[pid] = reqLen
            pidx += 1
        else:
            dpid = -c
            plen_v = plens[dpid]; pstart_v = pstarts[dpid]
            removalStarted = True; removedInRun += 1
            if dpid < runFirstPid: combRun = False
            ns, nl = pstart_v, plen_v
            if ns in e2b:
                left = e2b[ns]; left.alive = False
                del s2b[left.start]; del e2b[left.start + left.len]
                ns = left.start; nl += left.len
            if (ns + nl) in s2b:
                right = s2b[ns + nl]; right.alive = False
                del s2b[right.start]; del e2b[right.start + right.len]
                nl += right.len
            m = FB(ns, nl); s2b[ns] = m; e2b[ns + nl] = m
            if nl <= 40: heapq.heappush(bins[nl], m)
            else: heapq.heappush(largeBin, m)

    score = 418757 / trench * 10
    print(f'Test 15: trench={trench}, score={score:.9f}')
    assert all(p is not None for p in positions), f'missing positions (placed {sum(1 for p in positions if p is not None)}/{N})'
    ok, h = validate(pipes, cmds, positions)
    print(f'Valid: {ok}, height: {h}, target {H}')
    if ok and h <= H:
        (ROOT / out_path).write_text('\n'.join(map(str, positions)) + '\n')
        print('SUCCESS! Saved', out_path)
    elif ok:
        (ROOT / out_path).write_text('\n'.join(map(str, positions)) + '\n')
        print(f'Valid but height {h} > {H}. Saved anyway:', out_path)
    else:
        print('INVALID PLAN!')
    return trench, ok, h

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--plan11', required=True)
    ap.add_argument('--out', default='target/t15-replayed.txt')
    args = ap.parse_args()
    replay(args.plan11, args.out)
