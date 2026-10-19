with open('src/main.cj', encoding='utf-8') as f:
    text = f.read()

# Make trenchLength public
text = text.replace('private var trenchLength: Int64 = 0', 'public var trenchLength: Int64 = 0', 1)

main_start = text.find('main(): Int64 {')
before_main = text[:main_start]

driver = """main(): Int64 {
    let manager = TrenchManager()
    let input = getStdIn()
    let printer = FastPrinter()
    var pidCounter: Int64 = 0
    while (true) {
        let optLine = input.readln()
        if (optLine.isNone()) { break }
        let line = optLine.getOrThrow()
        let cmd = parseIntFast(line)
        if (cmd == 0) { break }
        if (cmd > 0) {
            pidCounter += 1
            let (n, d) = manager.allocate(pidCounter, cmd)
            printer.write2(n, d)
        } else {
            let pid = -cmd
            let d = manager.free(pid)
            printer.write1(d)
        }
    }
    eprintln("Final trench: ${manager.trenchLength}")
    return 0
}
"""

with open('bench/test15_eval.cj', 'w', encoding='utf-8') as out:
    out.write(before_main + driver)
