import heapq

class FreeBlock:
    def __init__(self, start, length):
        self.start = start
        self.len = length
        self.alive = True

    def __lt__(self, other):
        if self.len > 40 and self.len != other.len:
            return self.len < other.len
        return self.start < other.start

def simulate(cmds, count_default=32, batch_use_right_tail=True):
    trench = 0
    startToBlock = {}
    endToBlock = {}
    bins = [[] for _ in range(41)]
    largeBin = []
    
    pipeStarts = {}
    pipeLens = {}
    
    firstLength = 0
    lastLength = 0
    equalLengthRun = 0
    previousRun = 0
    removedInRun = 0
    combRun = True
    removalStarted = False
    runFirstPid = 1
    
    pid = 0
    expansions = []
    
    for c in cmds:
        if c > 0:
            pid += 1
            reqLen = c
            if pid == 1:
                firstLength = reqLen
                
            if reqLen == lastLength:
                equalLengthRun += 1
                if removalStarted:
                    combRun = False
            else:
                previousRun = equalLengthRun if (equalLengthRun >= 32 and removedInRun >= 4 and combRun) else 0
                removedInRun = 0
                combRun = True
                removalStarted = False
                runFirstPid = pid
                lastLength = reqLen
                equalLengthRun = 1
                
            batch = equalLengthRun > 8 or previousRun > 0
            
            # Choose block
            chosenBlock = None
            for b in range(reqLen, 41):
                while bins[b] and not bins[b][0].alive:
                    heapq.heappop(bins[b])
                if bins[b]:
                    chosenBlock = bins[b][0]
                    break
            if not chosenBlock:
                while largeBin and not largeBin[0].alive:
                    heapq.heappop(largeBin)
                if largeBin:
                    chosenBlock = largeBin[0]
                    
            if chosenBlock:
                chosenBlock.alive = False
                del startToBlock[chosenBlock.start]
                del endToBlock[chosenBlock.start + chosenBlock.len]
                blockStart = chosenBlock.start
                blockLength = chosenBlock.len
                n = 0
            else:
                blockStart = trench
                if trench in endToBlock:
                    tail = endToBlock[trench]
                    tail.alive = False
                    del startToBlock[tail.start]
                    del endToBlock[tail.start + tail.len]
                    blockStart = tail.start
                    
                n = (blockStart + reqLen - trench + 19) // 20
                if batch:
                    cnt = count_default
                    if previousRun > 0:
                        cnt = max(1, previousRun - equalLengthRun + 1)
                    batchGrowth = (reqLen * cnt + 19) // 20
                    if batchGrowth > n:
                        n = batchGrowth
                        
                trench += n * 20
                blockLength = trench - blockStart
                expansions.append((pid, reqLen, trench, n))
                
            useRight = (pid % 2 == 0)
            if batch:
                useRight = (equalLengthRun % 2 == 0) if previousRun > 0 else (pid % 2 == 0)
            if blockStart + blockLength == trench and not batch:
                useRight = False
                
            pipeStart = blockStart + blockLength - reqLen if useRight else blockStart
            remStart = blockStart if useRight else blockStart + reqLen
            remLen = blockLength - reqLen
            
            if remLen > 0:
                rem = FreeBlock(remStart, remLen)
                startToBlock[remStart] = rem
                endToBlock[remStart + remLen] = rem
                if remLen <= 40:
                    heapq.heappush(bins[remLen], rem)
                else:
                    heapq.heappush(largeBin, rem)
                    
            pipeStarts[pid] = pipeStart
            pipeLens[pid] = reqLen
            
        else:
            del_pid = -c
            pipeLen = pipeLens[del_pid]
            pipeStart = pipeStarts[del_pid]
            pipeLens[del_pid] = 0
            
            removalStarted = True
            removedInRun += 1
            if del_pid < runFirstPid:
                combRun = False
            parity = (del_pid - runFirstPid) % 2
            
            newStart = pipeStart
            newLen = pipeLen
            
            if newStart in endToBlock:
                left = endToBlock[newStart]
                left.alive = False
                del startToBlock[left.start]
                del endToBlock[left.start + left.len]
                newStart = left.start
                newLen += left.len
                
            if (newStart + newLen) in startToBlock:
                right = startToBlock[newStart + newLen]
                right.alive = False
                del startToBlock[right.start]
                del endToBlock[right.start + right.len]
                newLen += right.len
                
            merged = FreeBlock(newStart, newLen)
            startToBlock[newStart] = merged
            endToBlock[newStart + newLen] = merged
            if newLen <= 40:
                heapq.heappush(bins[newLen], merged)
            else:
                heapq.heappush(largeBin, merged)
                
    active_len = sum(l for l in pipeLens.values())
    return trench, active_len, expansions

if __name__ == '__main__':
    with open('bench/tests/13.in') as f:
        cmds = [int(x) for x in f.read().split()[2:-1]]
    trench, active, expansions = simulate(cmds)
    print(f'Simulated trench: {trench}, active: {active}, expansions: {len(expansions)}')
    for e in expansions[-10:]:
        print('  expansion:', e)
