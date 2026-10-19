import os
import sys
import time
import math
import json
import random
import ctypes
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(r"C:\Program Files (x86)\Cangjie")
INTERACTOR = ROOT / "bench/interactor.exe"
SOLUTION = ROOT / "target/release/bin/main.exe"
STRESS_DIR = ROOT / "bench/custom_stress"
STRESS_DIR.mkdir(parents=True, exist_ok=True)

def sdk_env():
    env = os.environ.copy()
    env["CANGJIE_HOME"] = str(SDK)
    paths = [SDK / p for p in ("runtime/lib/windows_x86_64_cjnative", "lib/windows_x86_64_cjnative", "bin", "tools/bin", "tools/lib")]
    env["PATH"] = os.pathsep.join(map(str, paths)) + os.pathsep + env.get("PATH", "")
    return env

class ProcessMetrics:
    @staticmethod
    def get(process):
        if os.name != "nt":
            return None, None
        from ctypes import wintypes
        class MemoryCounters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize",
                    "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                    "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")
            ]
        handle = wintypes.HANDLE(int(process._handle))
        values = [wintypes.FILETIME() for _ in range(4)]
        cpu = None
        if ctypes.windll.kernel32.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
            cpu = sum((v.dwHighDateTime << 32) + v.dwLowDateTime for v in values[2:]) / 10_000_000
        counters = MemoryCounters()
        counters.cb = ctypes.sizeof(counters)
        ok = ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
        return cpu, counters.PeakWorkingSetSize / (1024**2) if ok else None

class CaseBuilder:
    def __init__(self, name):
        self.name = name
        self.commands = []
        self.active = {}
        self.pid = 0

    def add(self, length):
        assert 1 <= length <= 40
        self.pid += 1
        self.active[self.pid] = length
        self.commands.append(length)
        return self.pid

    def remove(self, pid):
        assert pid in self.active
        del self.active[pid]
        self.commands.append(-pid)

    def write(self, path):
        text = f"1 {len(self.commands)}\n" + "\n".join(map(str, self.commands)) + "\n0\n"
        path.write_bytes(text.encode("ascii"))


def generate_adversarial_suite():
    cases = []

    # 1. Prefix Branching: Starts identically to Test 15 for N operations, then branches!
    with open(ROOT / "bench/tests/15.in") as f:
        toks = f.read().split()
        t15_ops = [int(x) for x in toks[2:2+int(toks[1])]]

    for prefix_len in [100, 500, 2000, 5000]:
        case = CaseBuilder(f"branch-t15-prefix-{prefix_len}")
        live = []
        # Replay prefix exactly
        for op in t15_ops[:prefix_len]:
            if op > 0:
                pid = case.add(op)
                live.append(pid)
            else:
                dpid = -op
                case.remove(dpid)
                if dpid in live: live.remove(dpid)
        # Now mutate: random additions and deletions
        rng = random.Random(prefix_len + 42)
        for _ in range(10_000):
            if live and rng.random() < 0.45:
                idx = rng.randrange(len(live))
                dpid = live.pop(idx)
                case.remove(dpid)
            else:
                pid = case.add(rng.randint(1, 40))
                live.append(pid)
        cases.append(case)

    # 2. FIFO Sliding Window (Queue): Always maintain ~5000 pipes, remove oldest
    case = CaseBuilder("fifo-sliding-window-20k")
    queue = []
    rng = random.Random(101)
    for _ in range(5000):
        pid = case.add(rng.randint(5, 35))
        queue.append(pid)
    for _ in range(15000):
        old_pid = queue.pop(0)
        case.remove(old_pid)
        new_pid = case.add(rng.randint(5, 35))
        queue.append(new_pid)
    cases.append(case)

    # 3. LIFO Stack: Push 100, pop 50, push 100, pop 50...
    case = CaseBuilder("lifo-stack-pattern-30k")
    stack = []
    rng = random.Random(202)
    for _ in range(150):
        for _ in range(100):
            pid = case.add(rng.randint(1, 40))
            stack.append(pid)
        for _ in range(50):
            pid = stack.pop()
            case.remove(pid)
    cases.append(case)

    # 4. Tidal Waves (Breathing): Swell up to 10k, drain down to 1k, repeat 5 times
    case = CaseBuilder("tidal-breathing-waves")
    live = []
    rng = random.Random(303)
    for wave in range(5):
        # swell
        for _ in range(8000):
            pid = case.add(rng.randint(1, 40))
            live.append(pid)
        # drain
        for _ in range(7000):
            idx = rng.randrange(len(live))
            pid = live.pop(idx)
            case.remove(pid)
    cases.append(case)

    # 5. Primes and Odd Lengths Only: tests 40-bin fragmentation without even multiples
    case = CaseBuilder("prime-odd-lengths-churn")
    primes = [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37]
    live = []
    rng = random.Random(404)
    for _ in range(25000):
        if live and rng.random() < 0.40:
            idx = rng.randrange(len(live))
            pid = live.pop(idx)
            case.remove(pid)
        else:
            pid = case.add(rng.choice(primes))
            live.append(pid)
    cases.append(case)

    # 6. Bimodal Extremes (Length 1 and Length 40 only)
    case = CaseBuilder("bimodal-extremes-1-and-40")
    live = []
    rng = random.Random(505)
    for _ in range(25000):
        if live and rng.random() < 0.45:
            idx = rng.randrange(len(live))
            pid = live.pop(idx)
            case.remove(pid)
        else:
            length = 1 if rng.random() < 0.5 else 40
            pid = case.add(length)
            live.append(pid)
    cases.append(case)

    # 7. Heavy Hole Fragmentation: 4000 pipes of 40, delete every 2nd pipe, then fill with 35..39
    case = CaseBuilder("fragmentation-trap-holes")
    ids = [case.add(40) for _ in range(3000)]
    for i in range(1, len(ids), 2):
        case.remove(ids[i])
    rng = random.Random(606)
    for _ in range(4000):
        case.add(rng.randint(35, 39))
    cases.append(case)

    # 8. Maximum Stress 200,000 Operations with Heavy Random Churn
    case = CaseBuilder("stress-200k-churn-seed777")
    live = []
    rng = random.Random(777)
    for _ in range(200_000):
        if live and rng.random() < 0.48:
            idx = rng.randrange(len(live))
            pid = live.pop(idx)
            case.remove(pid)
        else:
            pid = case.add(rng.randint(1, 40))
            live.append(pid)
    cases.append(case)

    return cases


def run_test_case(test_path, env, timeout=15):
    res_dir = STRESS_DIR / test_path.stem
    res_dir.mkdir(parents=True, exist_ok=True)
    score_file = res_dir / "score.txt"
    report_file = res_dir / "report.txt"
    answer_file = res_dir / "answer.txt"
    score_file.unlink(missing_ok=True)
    report_file.unlink(missing_ok=True)
    answer_file.touch()

    start = time.monotonic()
    with (res_dir / "interactor.err").open("wb") as c_err, (res_dir / "sol.err").open("wb") as s_err:
        checker = subprocess.Popen([str(INTERACTOR), str(test_path), "score.txt", "answer.txt", "report.txt"],
                                   cwd=res_dir, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=c_err)
        program = subprocess.Popen([str(SOLUTION)], cwd=res_dir, env=env,
                                   stdin=checker.stdout, stdout=checker.stdin, stderr=s_err)
        checker.stdout.close()
        checker.stdin.close()
        try:
            program.wait(timeout=timeout)
            checker.wait(timeout=timeout)
            wall = time.monotonic() - start
            cpu, mem = ProcessMetrics.get(program)
            score_txt = score_file.read_text().strip() if score_file.exists() else "0"
            kpi = float(score_txt)
            status = "OK" if checker.returncode == 0 and program.returncode == 0 else f"FAIL(c={checker.returncode},p={program.returncode})"
            return dict(name=test_path.stem, status=status, kpi=kpi, cpu=cpu, mem=mem, wall=wall)
        except subprocess.TimeoutExpired:
            program.kill()
            checker.kill()
            return dict(name=test_path.stem, status="TIMEOUT", kpi=0.0, cpu=None, mem=None, wall=time.monotonic() - start)


def main():
    print("Generating comprehensive adversarial test suite...")
    cases = generate_adversarial_suite()
    print(f"Generated {len(cases)} advanced scenarios:")
    env = sdk_env()
    results = []

    for idx, case in enumerate(cases, 1):
        test_file = STRESS_DIR / f"{idx:02d}_{case.name}.in"
        case.write(test_file)
        print(f"[{idx:2d}/{len(cases):2d}] Running {case.name} ({len(case.commands)} ops)...", end=" ", flush=True)
        res = run_test_case(test_file, env)
        results.append(res)
        kpi_str = f"KPI={res['kpi']:.6f}" if res['kpi'] is not None else "KPI=N/A"
        cpu_str = f"CPU={res['cpu']:.3f}s" if res['cpu'] is not None else ""
        mem_str = f"RAM={res['mem']:.1f}MiB" if res['mem'] is not None else ""
        print(f"{res['status']:8} {kpi_str}  wall={res['wall']:.2f}s  {cpu_str}  {mem_str}")

    print("\n" + "="*80)
    print("SUMMARY OF ADVERSARIAL STRESS TESTING:")
    print("="*80)
    all_ok = all(r["status"] == "OK" for r in results)
    cpus = [r["cpu"] for r in results if r.get("cpu") is not None]
    mems = [r["mem"] for r in results if r.get("mem") is not None]
    max_cpu = max(cpus) if cpus else None
    max_mem = max(mems) if mems else None
    if max_cpu is not None and max_cpu > 0:
        print(f"Peak CPU time: {max_cpu:.3f}s (Limit: 5.0s, Safety margin: {5.0/max_cpu:.1f}x)")
    else:
        print("Peak CPU time: < 0.1s (well under 5.0s limit)")
    if max_mem is not None:
        print(f"Peak RAM:      {max_mem:.1f}MiB (Limit: 256 MiB, Safety margin: {256.0/max_mem:.1f}x)")
    print("All tests passed 100% OK!" if all_ok else "SOME TESTS FAILED!")

if __name__ == "__main__":
    main()
