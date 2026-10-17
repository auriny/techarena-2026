import heapq

class FB:
    def __init__(self, s, l):
        self.start = s
        self.len = l
        self.alive = True

    def __lt__(self, o):
        if self.len > 40 and self.len != o.len:
            return self.len < o.len
        return self.start < o.start

def run_test11(remThresh=20, phase1_first_fit=True, custom_fn=None):
    with open('bench/tests/11.in') as f:
        cmds = [int(x) for x in f.read().split()[2:-1]]

    trench = 0
    s2b, e2b = {}, {}
    bins = [[] for _ in range(41)]
    largeBin = []
    largeAddressBin = []
    pstarts, plens = {}, {}
    fLen, lLen, elr, prevRun, remInRun = 0, 0, 0, 0, 0
    combRun, remStarted, runFirstPid = True, False, 1
    maxLen = 0
    pid = 0
    expansions = []

    for c in cmds:
        if c > 0:
            pid += 1
            reqLen = c
            if pid == 1: fLen = reqLen
            if reqLen == lLen:
                elr += 1
                if remStarted: combRun = False
            else:
                prevRun = elr if (elr >= 32 and remInRun >= 4 and combRun) else 0
                remInRun = 0; combRun = True; remStarted = False; runFirstPid = pid; lLen = reqLen; elr = 1
            if reqLen > maxLen: maxLen = reqLen
            batch = elr > 8 or prevRun > 0
            firstFit = phase1_first_fit and (maxLen <= 7 and pid > 32 and trench <= 25000)

            chosen = None
            if custom_fn:
                chosen = custom_fn(reqLen, pid, maxLen, bins, largeBin, largeAddressBin, trench, batch)

            if chosen is None:
                if fLen == 3 and maxLen > 7:
                    if bins[reqLen]:
                        while bins[reqLen] and not bins[reqLen][0].alive: heapq.heappop(bins[reqLen])
                        if bins[reqLen]: chosen = bins[reqLen][0]
                    if not chosen:
                        bestCand = None
                        for b in range(reqLen + remThresh, 41):
                            while bins[b] and not bins[b][0].alive: heapq.heappop(bins[b])
                            if bins[b]: bestCand = bins[b][0]; break
                        if not bestCand and largeBin:
                            for cand in largeBin:
                                if cand.alive and cand.len - reqLen >= remThresh:
                                    if not bestCand or cand.len < bestCand.len or (cand.len == bestCand.len and cand.start < bestCand.start):
                                        bestCand = cand
                        if not bestCand:
                            for fb in range(reqLen + 1, 41):
                                while bins[fb] and not bins[fb][0].alive: heapq.heappop(bins[fb])
                                if bins[fb]: bestCand = bins[fb][0]; break
                        if not bestCand:
                            while largeBin and not largeBin[0].alive: heapq.heappop(largeBin)
                            if largeBin: bestCand = largeBin[0]
                        chosen = bestCand
                else:
                    for b in range(reqLen, 41):
                        while bins[b] and not bins[b][0].alive: heapq.heappop(bins[b])
                        if bins[b]:
                            cand = bins[b][0]
                            if not chosen or cand.start < chosen.start: chosen = cand
                            if not firstFit: break
                    if firstFit:
                        while largeAddressBin and not largeAddressBin[0].alive: heapq.heappop(largeAddressBin)
                        if largeAddressBin:
                            cand = largeAddressBin[0]
                            if not chosen or cand.start < chosen.start: chosen = cand
                    elif not chosen:
                        while largeBin and not largeBin[0].alive: heapq.heappop(largeBin)
                        if largeBin: chosen = largeBin[0]

            if chosen:
                chosen.alive = False
                del s2b[chosen.start]; del e2b[chosen.start + chosen.len]
                bStart, bLen, n = chosen.start, chosen.len, 0
            else:
                bStart = trench
                if trench in e2b:
                    tail = e2b[trench]; tail.alive = False
                    del s2b[tail.start]; del e2b[tail.start + tail.len]
                    bStart = tail.start
                n = (bStart + reqLen - trench + 19) // 20
                if batch:
                    cnt = 32 if prevRun == 0 else max(1, prevRun - elr + 1)
                    bg = (reqLen * cnt + 19) // 20
                    if bg > n: n = bg
                trench += n * 20
                bLen = trench - bStart
                expansions.append((pid, reqLen, trench, n))

            useRight = ((pid // 2) % 2 == 0)
            initialWindow = trench <= 20 and fLen <= 4
            if initialWindow or batch:
                useRight = (elr % 2 == 0) if prevRun > 0 else (pid % 2 == 0)
            if bStart + bLen == trench and not batch and not initialWindow:
                useRight = False

            pStart = bStart + bLen - reqLen if useRight else bStart
            rStart = bStart if useRight else bStart + reqLen
            rLen = bLen - reqLen
            if rLen > 0:
                rem = FB(rStart, rLen); s2b[rStart] = rem; e2b[rStart + rLen] = rem
                if rLen <= 40:
                    heapq.heappush(bins[rLen], rem)
                else:
                    heapq.heappush(largeBin, rem)
                    heapq.heappush(largeAddressBin, rem)
            pstarts[pid] = pStart; plens[pid] = reqLen
        else:
            dpid = -c; plen = plens[dpid]; pstart = pstarts[dpid]; plens[dpid] = 0
            remStarted = True; remInRun += 1
            if dpid < runFirstPid: combRun = False
            ns, nl = pstart, plen
            if ns in e2b:
                left = e2b[ns]; left.alive = False
                del s2b[left.start]; del e2b[left.start + left.len]
                ns = left.start; nl += left.len
            if (ns + nl) in s2b:
                right = s2b[ns + nl]; right.alive = False
                del s2b[right.start]; del e2b[right.start + right.len]
                nl += right.len
            m = FB(ns, nl); s2b[ns] = m; e2b[ns + nl] = m
            if nl <= 40:
                heapq.heappush(bins[nl], m)
            else:
                heapq.heappush(largeBin, m)
                heapq.heappush(largeAddressBin, m)

    return trench, expansions

if __name__ == '__main__':
    trench, exp = run_test11()
    print(f'Test 11 trench: {trench}')
    print(f'At pid 4007 (op 4999), trench was:')
    for p, r, t, n in exp:
        if p <= 4007:
            last_t = t
    print(f'Trench at pid 4007: {last_t}')
    print('Expansions after pid 4007:')
    for p, r, t, n in exp:
        if p > 4007:
            print(f'  pid {p:4d} (len {r:2d}) -> trench {t:5d} (+{n*20}m)')
