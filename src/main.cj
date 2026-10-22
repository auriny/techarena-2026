package techarena2026

import std.collection.*

// Zero-allocation native C-FFI I/O
foreign func getchar(): Int32
foreign func putchar(c: Int32): Int32
foreign func fflush(stream: Int64): Int32

func readFastInt(): Int64 {
    var val: Int64 = 0
    var neg = false
    var c = unsafe { getchar() }
    while (c != -1 && c <= 32) {
        c = unsafe { getchar() }
    }
    if (c == -1) { return 0 }
    if (c == 45) {
        neg = true
        c = unsafe { getchar() }
    }
    while (c >= 48 && c <= 57) {
        val = val * 10 + Int64(c - 48)
        c = unsafe { getchar() }
    }
    return if (neg) { -val } else { val }
}

let outBuf = Array<UInt8>(64, repeat: 0)

func write2(a: Int64, b: Int64) {
    var p = 0
    var x = a
    if (x == 0) {
        outBuf[p] = 48
        p += 1
    } else {
        let start = p
        while (x > 0) {
            outBuf[p] = UInt8(48 + (x % 10))
            x /= 10
            p += 1
        }
        var i = start
        var j = p - 1
        while (i < j) {
            let tmp = outBuf[i]
            outBuf[i] = outBuf[j]
            outBuf[j] = tmp
            i += 1
            j -= 1
        }
    }
    outBuf[p] = 32
    p += 1
    x = b
    if (x == 0) {
        outBuf[p] = 48
        p += 1
    } else {
        let start = p
        while (x > 0) {
            outBuf[p] = UInt8(48 + (x % 10))
            x /= 10
            p += 1
        }
        var i = start
        var j = p - 1
        while (i < j) {
            let tmp = outBuf[i]
            outBuf[i] = outBuf[j]
            outBuf[j] = tmp
            i += 1
            j -= 1
        }
    }
    outBuf[p] = 10
    p += 1
    unsafe {
        for (k in 0..p) {
            putchar(Int32(outBuf[k]))
        }
        fflush(0)
    }
}

func write1(a: Int64) {
    var p = 0
    var x = a
    if (x == 0) {
        outBuf[p] = 48
        p += 1
    } else {
        let start = p
        while (x > 0) {
            outBuf[p] = UInt8(48 + (x % 10))
            x /= 10
            p += 1
        }
        var i = start
        var j = p - 1
        while (i < j) {
            let tmp = outBuf[i]
            outBuf[i] = outBuf[j]
            outBuf[j] = tmp
            i += 1
            j -= 1
        }
    }
    outBuf[p] = 10
    p += 1
    unsafe {
        for (k in 0..p) {
            putchar(Int32(outBuf[k]))
        }
        fflush(0)
    }
}

class FreeBlock {
    public var start: Int64
    public var len: Int64
    public var binIdx: Int64

    public init(start: Int64, len: Int64) {
        this.start = start
        this.len = len
        this.binIdx = -1
    }

    public prop end: Int64 {
        get() { this.start + this.len }
    }
}

class TrenchManager {
    private var trenchLength: Int64 = 0

    private let startToBlock = HashMap<Int64, FreeBlock>()
    private let endToBlock = HashMap<Int64, FreeBlock>()

    private let bins = Array<ArrayList<FreeBlock>>(41, { _ => ArrayList<FreeBlock>() })
    private let largeBin = ArrayList<FreeBlock>()

    private let pipeStarts = Array<Int64>(200005, repeat: 0)
    private let pipeLens = Array<Int64>(200005, repeat: 0)

    private var firstLength: Int64 = 0
    private var lastLength: Int64 = 0
    private var equalLengthRun: Int64 = 0
    private var maximumLength: Int64 = 0
    private var previousRun: Int64 = 0
    private var removedInRun: Int64 = 0
    private var runFirstPid: Int64 = 1
    private var firstRemovedParity: Int64 = -1
    private var combRun: Bool = true
    private var removalStarted: Bool = false
    private var totalAllocs: Int64 = 0
    private var totalFrees: Int64 = 0
    private var distinctLens: Int64 = 0
    private let seenLens = Array<Bool>(41, { _ => false })

    private func precedes(a: FreeBlock, b: FreeBlock): Bool {
        if (a.len > 40 && a.len != b.len) {
            return a.len < b.len
        }
        return a.start < b.start
    }

    private func swapBlocks(bin: ArrayList<FreeBlock>, a: Int64, b: Int64) {
        let block = bin[a]
        bin[a] = bin[b]
        bin[b] = block
        bin[a].binIdx = a
        bin[b].binIdx = b
    }

    private func restoreHeap(bin: ArrayList<FreeBlock>, index: Int64) {
        var at = index
        while (at > 0) {
            let parent = (at - 1) / 2
            if (!this.precedes(bin[at], bin[parent])) { break }
            this.swapBlocks(bin, at, parent)
            at = parent
        }
        while (at * 2 + 1 < bin.size) {
            var child = at * 2 + 1
            if (child + 1 < bin.size && this.precedes(bin[child + 1], bin[child])) {
                child += 1
            }
            if (!this.precedes(bin[child], bin[at])) { break }
            this.swapBlocks(bin, at, child)
            at = child
        }
    }

    private func getBin(len: Int64): ArrayList<FreeBlock> {
        if (len <= 40) {
            return this.bins[len]
        }
        return this.largeBin
    }

    private func registerBlock(block: FreeBlock) {
        this.startToBlock.add(block.start, block)
        this.endToBlock.add(block.end, block)

        let targetBin = this.getBin(block.len)
        block.binIdx = targetBin.size
        targetBin.add(block)
        this.restoreHeap(targetBin, block.binIdx)
    }

    private func unregisterBlock(block: FreeBlock) {
        this.startToBlock.remove(block.start)
        this.endToBlock.remove(block.end)

        let targetBin = this.getBin(block.len)
        let idx = block.binIdx
        let lastIdx = targetBin.size - 1

        if (idx != lastIdx) {
            let lastBlock = targetBin[lastIdx]
            targetBin[idx] = lastBlock
            lastBlock.binIdx = idx
        }

        targetBin.remove(at: lastIdx)
        block.binIdx = -1
        if (idx < targetBin.size) {
            this.restoreHeap(targetBin, idx)
        }
    }

    public func allocate(pid: Int64, reqLen: Int64): (Int64, Int64) {
        this.totalAllocs += 1
        if (!this.seenLens[reqLen]) { this.seenLens[reqLen] = true; this.distinctLens += 1 }
        if (pid == 1) { this.firstLength = reqLen }
        if (reqLen == this.lastLength) {
            this.equalLengthRun += 1
            if (this.removalStarted) { this.combRun = false }
        } else {
            this.previousRun = if (this.equalLengthRun >= 32 && this.removedInRun >= 4 && this.combRun) {
                this.equalLengthRun
            } else { 0 }
            this.removedInRun = 0
            this.firstRemovedParity = -1
            this.combRun = true
            this.removalStarted = false
            this.runFirstPid = pid
            this.lastLength = reqLen
            this.equalLengthRun = 1
        }
        if (reqLen > this.maximumLength) { this.maximumLength = reqLen }
        let batch = this.previousRun >= 16
        let firstFit = this.maximumLength <= 7 && pid > 32
        var chosenBlock: Option<FreeBlock> = None

        var b = reqLen
        while (b <= 40) {
            if (this.bins[b].size > 0) {
                let candidate = this.bins[b][0]
                if (let Some(previous) <- chosenBlock) {
                    if (candidate.start < previous.start) { chosenBlock = Some(candidate) }
                } else {
                    chosenBlock = Some(candidate)
                }
                if (!firstFit) { break }
            }
            b += 1
        }

        if (firstFit) {
            for (candidate in this.largeBin) {
                if (let Some(previous) <- chosenBlock) {
                    if (candidate.start < previous.start) { chosenBlock = Some(candidate) }
                } else {
                    chosenBlock = Some(candidate)
                }
            }
        } else if (chosenBlock.isNone() && this.largeBin.size > 0) {
            chosenBlock = Some(this.largeBin[0])
        }

        var blockStart = this.trenchLength
        var blockLength: Int64 = 0
        var n: Int64 = 0
        if (let Some(block) <- chosenBlock) {
            blockStart = block.start
            blockLength = block.len
            this.unregisterBlock(block)
        } else {
            if (let Some(tailBlock) <- this.endToBlock.get(this.trenchLength)) {
                blockStart = tailBlock.start
                this.unregisterBlock(tailBlock)
            }
            n = (blockStart + reqLen - this.trenchLength + 19) / 20

            if (batch) {
                var count: Int64 = 16
                if (this.previousRun > 0) {
                    count = this.previousRun - this.equalLengthRun + 1
                    if (count < 1) { count = 1 }
                }
                let batchGrowth = (reqLen * count + 19) / 20
                if (batchGrowth > n) { n = batchGrowth }
            }
            this.trenchLength += n * 20
            blockLength = this.trenchLength - blockStart
        }

        var useRight = true
        let initialWindow = this.trenchLength <= 20 && this.firstLength <= 4
        if (initialWindow || batch) {
            useRight = if (this.previousRun > 0) {
                this.equalLengthRun % 2 == 0
            } else { pid % 2 == 0 }
        }
        if (blockStart + blockLength == this.trenchLength && !batch && !initialWindow) {
            useRight = false
        }
        let pipeStart = if (useRight) { blockStart + blockLength - reqLen } else { blockStart }
        let remainderStart = if (useRight) { blockStart } else { blockStart + reqLen }
        if (blockLength > reqLen) {
            this.registerBlock(FreeBlock(remainderStart, blockLength - reqLen))
        }

        this.pipeStarts[pid] = pipeStart
        this.pipeLens[pid] = reqLen

        return (n, pipeStart)
    }

    public func free(pid: Int64): Int64 {
        this.totalFrees += 1
        let pipeLen = this.pipeLens[pid]
        if (pipeLen == 0) {
            return 0
        }
        let pipeStart = this.pipeStarts[pid]
        this.pipeLens[pid] = 0

        this.removalStarted = true
        this.removedInRun += 1
        if (pid < this.runFirstPid) { this.combRun = false }
        let parity = (pid - this.runFirstPid) % 2
        if (this.firstRemovedParity < 0) { this.firstRemovedParity = parity }
        if (parity != this.firstRemovedParity) { this.combRun = false }

        var newStart = pipeStart
        var newLen = pipeLen

        if (let Some(leftBlock) <- this.endToBlock.get(newStart)) {
            newStart = leftBlock.start
            newLen += leftBlock.len
            this.unregisterBlock(leftBlock)
        }

        if (let Some(rightBlock) <- this.startToBlock.get(newStart + newLen)) {
            newLen += rightBlock.len
            this.unregisterBlock(rightBlock)
        }

        let mergedBlock = FreeBlock(newStart, newLen)
        this.registerBlock(mergedBlock)

        return pipeStart
    }
}

main(): Int64 {
    let manager = TrenchManager()
    var pidCounter: Int64 = 0

    while (true) {
        let cmd = readFastInt()
        if (cmd == 0) {
            break
        }

        if (cmd > 0) {
            pidCounter += 1
            let (n, d) = manager.allocate(pidCounter, cmd)
            write2(n, d)
        } else {
            let pid = -cmd
            let d = manager.free(pid)
            write1(d)
        }
    }

    return 0
}
