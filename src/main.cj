package techarena2026

import std.env.*
import std.collection.*
import std.convert.*

class PipeInfo {
    public let start: Int64
    public let len: Int64

    public init(start: Int64, len: Int64) {
        this.start = start
        this.len = len
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

    private let activePipes = HashMap<Int64, PipeInfo>()

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
    }

    public func allocate(pid: Int64, reqLen: Int64): (Int64, Int64) {
        var chosenBlock: Option<FreeBlock> = None

        var b = reqLen
        while (b <= 40) {
            if (this.bins[b].size > 0) {
                chosenBlock = Some(this.bins[b][this.bins[b].size - 1])
                break
            }
            b += 1
        }

        if (chosenBlock.isNone() && this.largeBin.size > 0) {
            chosenBlock = Some(this.largeBin[this.largeBin.size - 1])
        }

        if (let Some(block) <- chosenBlock) {
            this.unregisterBlock(block)
            let start = block.start
            let leftover = block.len - reqLen

            if (leftover > 0) {
                let remainderBlock = FreeBlock(start + reqLen, leftover)
                this.registerBlock(remainderBlock)
            }

            this.activePipes.add(pid, PipeInfo(start, reqLen))
            return (0, start)
        }

        var tailStart = this.trenchLength
        if (let Some(tailBlock) <- this.endToBlock.get(this.trenchLength)) {
            tailStart = tailBlock.start
            this.unregisterBlock(tailBlock)
        }

        let targetEnd = tailStart + reqLen
        var n: Int64 = 0

        if (targetEnd > this.trenchLength) {
            let diff = targetEnd - this.trenchLength
            n = (diff + 19) / 20
            this.trenchLength += n * 20
        }

        let pipeStart = tailStart
        let leftoverTail = this.trenchLength - (pipeStart + reqLen)
        if (leftoverTail > 0) {
            let tailRemainder = FreeBlock(pipeStart + reqLen, leftoverTail)
            this.registerBlock(tailRemainder)
        }

        this.activePipes.add(pid, PipeInfo(pipeStart, reqLen))
        return (n, pipeStart)
    }

    public func free(pid: Int64): Int64 {
        let optPipe = this.activePipes.remove(pid)
        if (optPipe.isNone()) {
            return 0
        }
        let pipe = optPipe.getOrThrow()

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
