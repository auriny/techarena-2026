package techarena2026

import std.env.*
import std.collection.*
import std.convert.*

class PipeInfo {
    public let pid: Int64
    public let start: Int64
    public let len: Int64

    public init(pid: Int64, start: Int64, len: Int64) {
        this.pid = pid
        this.start = start
        this.len = len
    }
}

class FreeBlock {
    public var start: Int64
    public var len: Int64
    public var binIdx: Int64
    public var addressIdx: Int64

    public init(start: Int64, len: Int64) {
        this.start = start
        this.len = len
        this.binIdx = -1
        this.addressIdx = -1
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
    // A second heap is used only by the first-fit path.  Scanning largeBin
    // for its leftmost block made the short-length phase quadratic.
    private let largeAddressBin = ArrayList<FreeBlock>()

    private let activePipes = HashMap<Int64, PipeInfo>()
    private let pipesByStart = HashMap<Int64, PipeInfo>()
    private let pipesByEnd = HashMap<Int64, PipeInfo>()
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

    // Small bins are heaps ordered by address. The large bin is ordered by
    // (length, address), so a large free interval is no longer picked at random.
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

    private func swapAddress(a: Int64, b: Int64) {
        let block = this.largeAddressBin[a]
        this.largeAddressBin[a] = this.largeAddressBin[b]
        this.largeAddressBin[b] = block
        this.largeAddressBin[a].addressIdx = a
        this.largeAddressBin[b].addressIdx = b
    }

    private func restoreAddress(index: Int64) {
        var at = index
        while (at > 0) {
            let parent = (at - 1) / 2
            if (this.largeAddressBin[parent].start <= this.largeAddressBin[at].start) { break }
            this.swapAddress(at, parent)
            at = parent
        }
        while (at * 2 + 1 < this.largeAddressBin.size) {
            var child = at * 2 + 1
            if (child + 1 < this.largeAddressBin.size &&
                this.largeAddressBin[child + 1].start < this.largeAddressBin[child].start) {
                child += 1
            }
            if (this.largeAddressBin[at].start <= this.largeAddressBin[child].start) { break }
            this.swapAddress(at, child)
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
        if (block.len > 40) {
            block.addressIdx = this.largeAddressBin.size
            this.largeAddressBin.add(block)
            this.restoreAddress(block.addressIdx)
        }
    }

    private func unregisterBlock(block: FreeBlock) {
        this.startToBlock.remove(block.start)
        this.endToBlock.remove(block.end)

        let targetBin = this.getBin(block.len)
        if (block.len > 40) {
            let idx = block.addressIdx
            let lastIdx = this.largeAddressBin.size - 1
            if (idx != lastIdx) {
                let lastBlock = this.largeAddressBin[lastIdx]
                this.largeAddressBin[idx] = lastBlock
                lastBlock.addressIdx = idx
            }
            this.largeAddressBin.remove(at: lastIdx)
            block.addressIdx = -1
            if (idx < this.largeAddressBin.size) {
                this.restoreAddress(idx)
            }
        }
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
        let batch = this.equalLengthRun > 8 || this.previousRun > 0
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
            if (this.largeAddressBin.size > 0) {
                let candidate = this.largeAddressBin[0]
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
            // Repeated equal lengths benefit from blocks containing several
            // pipes: later removals can coalesce instead of leaving tiny gaps.
            if (batch) {
                var count: Int64 = 32
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

        var leftDistance: Int64 = 100
        var rightDistance: Int64 = 100
        var leftPid: Int64 = 0
        var rightPid: Int64 = 0
        if (let Some(neighbor) <- this.pipesByEnd.get(blockStart)) {
            leftPid = neighbor.pid
            let diff = neighbor.len - reqLen
            leftDistance = if (diff < 0) { -diff } else { diff }
        }
        if (let Some(neighbor) <- this.pipesByStart.get(blockStart + blockLength)) {
            rightPid = neighbor.pid
            let diff = neighbor.len - reqLen
            rightDistance = if (diff < 0) { -diff } else { diff }
        }
        var useRight = rightDistance < leftDistance
        // A surviving older neighbor is less likely to disappear immediately.
        // Placing against it leaves the remainder next to the younger pipe.
        if (pid > 32 && !batch) {
            useRight = rightPid > 0 && (leftPid == 0 || rightPid < leftPid)
        }
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

        let pipe = PipeInfo(pid, pipeStart, reqLen)
        this.activePipes.add(pid, pipe)
        this.pipesByStart.add(pipeStart, pipe)
        this.pipesByEnd.add(pipeStart + reqLen, pipe)

        return (n, pipeStart)
    }

    public func free(pid: Int64): Int64 {
        let optPipe = this.activePipes.remove(pid)
        if (optPipe.isNone()) {
            return 0
        }
        let pipe = optPipe.getOrThrow()
        this.removalStarted = true
        this.removedInRun += 1
        if (pid < this.runFirstPid) { this.combRun = false }
        let parity = (pid - this.runFirstPid) % 2
        if (this.firstRemovedParity < 0) { this.firstRemovedParity = parity }
        if (parity != this.firstRemovedParity) { this.combRun = false }
        this.pipesByStart.remove(pipe.start)
        this.pipesByEnd.remove(pipe.start + pipe.len)

        var newStart = pipe.start
        var newLen = pipe.len

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

        return pipe.start
    }
}

main(): Int64 {
    let manager = TrenchManager()
    var pidCounter: Int64 = 0
    let input = getStdIn()
    let output = getStdOut()

    while (true) {
        let optLine = input.readln()
        if (optLine.isNone()) {
            break
        }
        let line = optLine.getOrThrow().trimAscii()
        if (line.size == 0) {
            continue
        }

        let cmd = Int64.parse(line)
        if (cmd == 0) {
            break
        }

        if (cmd > 0) {
            pidCounter += 1
            let (n, d) = manager.allocate(pidCounter, cmd)
            output.write("${n} ${d}\n".toArray())
            output.flush()
        } else {
            let pid = -cmd
            let d = manager.free(pid)
            output.write("${d}\n".toArray())
            output.flush()
        }
    }

    return 0
}
