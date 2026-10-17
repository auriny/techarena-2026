import heapq, time

class FB:
    def __init__(self, s, l):
        self.start = s
        self.len = l
        self.alive = True

    def __lt__(self, o):
        if self.len > 40 and self.len != o.len:
            return self.len < o.len
        return self.start < o.start

def run_test15(growth_p1=129100, growth_p2=129700, small_thresh=130200, base_growth=4):
    t0 = time.time()
    with open('bench/tests/15.in') as f:
        tokens = f.read().split()
        cmds = [int(x) for x in tokens[2:-1]]

    trench = 0
    s2b, e2b = {}, {}
    bins = [[] for _ in range(41)]
    largeBin = []
    pstarts = [0] * (len(cmds) + 5)
    plens = [0] * (len(cmds) + 5)
    
    firstLength = 0
    lastLength = 0
    equalLengthRun = 0
    previousRun = 0
    removedInRun = 0
    combRun = True
    removalStarted = False
    runFirstPid = 1
    maximumLength = 0
    shortHistory = False
    
    pid = 0
    expansions = []

    for c in cmds:
        if c > 0:
            pid += 1
            reqLen = c
            if pid == 1: firstLength = reqLen
            if maximumLength <= 7 and trench > 25000:
                shortHistory = True
                
            if reqLen == lastLength:
                equalLengthRun += 1
                if removalStarted: combRun = False
            else:
                previousRun = equalLengthRun if (equalLengthRun >= 32 and removedInRun >= 4 and combRun) else 0
                removedInRun = 0; combRun = True; remStarted = False; runFirstPid = pid; lastLength = reqLen; equalLengthRun = 1
                
            if reqLen > maximumLength: maximumLength = reqLen
            batch = equalLengthRun > 8 or previousRun > 0
            firstFit = maximumLength <= 7 and pid > 32 and trench <= 25000
            shortMode = maximumLength <= 7 and trench > 25000 and not batch

            chosen = None
            if shortMode:
                # shortMode
                if bins[reqLen]:
                    while bins[reqLen] and not bins[reqLen][0].alive: heapq.heappop(bins[reqLen])
                    if bins[reqLen]: chosen = bins[reqLen][0]
            elif not firstFit and trench > 25000:
                if bins[reqLen]:
                    while bins[reqLen] and not bins[reqLen][0].alive: heapq.heappop(bins[reqLen])
                    if bins[reqLen]: chosen = bins[reqLen][0]
                elif pid >= small_thresh:
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
                bStart, bLen, n = chosen.start, chosen.len, 0
            else:
                bStart = trench
                if not shortMode and trench in e2b:
                    tail = e2b[trench]; tail.alive = False
                    del s2b[tail.start]; del e2b[tail.start + tail.len]
                    bStart = tail.start
                n = (bStart + reqLen - trench + 19) // 20
                if batch:
                    cnt = 64 if firstLength <= 1 else 32
                    if previousRun > 0: cnt = max(1, previousRun - equalLengthRun + 1)
                    bg = (reqLen * cnt + 19) // 20
                    if bg > n: n = bg
                if trench > 83000 and not batch:
                    growth = base_growth if (shortHistory and maximumLength > 7) else 8
                    if pid >= growth_p1: growth = 2
                    if pid >= growth_p2: growth = 1
                    if n < growth: n = growth
                if shortMode:
                    if n < 4 * reqLen: n = 4 * reqLen
                    n = (n + reqLen - 1) // reqLen * reqLen
                trench += n * 20
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
            if bStart + bLen == trench and not batch and not initWin:
                useRight = False
            if shortHistory and maximumLength <= 7:
                useRight = True

            pStart = bStart + bLen - reqLen if useRight else bStart
            rStart = bStart if useRight else bStart + reqLen
            rLen = bLen - reqLen
            if rLen > 0:
                rem = FB(rStart, rLen); s2b[rStart] = rem; e2b[rStart + rLen] = rem
                if rLen <= 40: heapq.heappush(bins[rLen], rem)
                else: heapq.heappush(largeBin, rem)
            pstarts[pid] = pStart; plens[pid] = reqLen
        else:
            dpid = -c; plen = plens[dpid]; pstart = pstarts[dpid]; plens[dpid] = 0
            removalStarted = True; removedInRun += 1
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
            if nl <= 40: heapq.heappush(bins[nl], m)
            else: heapq.heappush(largeBin, m)

    score = 418757 / trench * 10
    print(f'Test 15: trench={trench}, score={score:.7f}, time={time.time()-t0:.2f}s')
    return trench, score

if __name__ == '__main__':
    run_test15()
