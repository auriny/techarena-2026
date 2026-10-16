import argparse
import ctypes
import json
import math
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from bench.generate_tests import EXTRA_DIR, generate, validate

ROOT = Path(__file__).resolve().parent


def sdk_environment(sdk):
    sdk = sdk.resolve()
    compiler = sdk / 'bin/cjc.exe'
    manager = sdk / 'tools/bin/cjpm.exe'
    for executable in (compiler, manager):
        if not executable.is_file():
            raise ValueError(f'SDK executable not found: {executable}')
    env = os.environ.copy()
    env['CANGJIE_HOME'] = str(sdk)
    paths = [sdk / p for p in ('runtime/lib/windows_x86_64_cjnative',
                              'lib/windows_x86_64_cjnative', 'bin', 'tools/bin', 'tools/lib')]
    env['PATH'] = os.pathsep.join(map(str, paths)) + os.pathsep + env.get('PATH', '')
    version = subprocess.check_output([str(compiler), '--version'], env=env, text=True)
    print(version.strip(), flush=True)
    if not re.search(r'Compiler:\s+1\.0\.5\b', version):
        raise ValueError('Expected Cangjie 1.0.5; check --sdk.')
    return env, manager


def process_metrics(process):
    """Windows process CPU seconds and peak working set, including exited processes."""
    if os.name != 'nt':
        return None, None
    from ctypes import wintypes

    class MemoryCounters(ctypes.Structure):
        _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ('PeakWorkingSetSize', 'WorkingSetSize',
                'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
                'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage')]

    handle = wintypes.HANDLE(int(process._handle))
    values = [wintypes.FILETIME() for _ in range(4)]
    cpu = None
    if ctypes.windll.kernel32.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
        cpu = sum((v.dwHighDateTime << 32) + v.dwLowDateTime for v in values[2:]) / 10_000_000
    counters = MemoryCounters()
    counters.cb = ctypes.sizeof(counters)
    ok = ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
    return cpu, counters.PeakWorkingSetSize / 1024**2 if ok else None


def run_case(path, group, name, solution, interactor, env, timeout):
    weight, operations = validate(path)
    directory = ROOT / 'target/test-results' / group / path.stem
    directory.mkdir(parents=True, exist_ok=True)
    # Never reuse scores or diagnostic files from an earlier run.
    for filename in ('score.txt', 'answer.txt', 'report.txt'):
        (directory / filename).unlink(missing_ok=True)
    # testlib requires the answer file to exist even though this interactor writes it.
    (directory / 'answer.txt').touch()
    result = dict(test=int(path.stem) if path.stem.isdigit() else path.stem,
                  group=group, name=name, operations=operations, weight=weight,
                  status='ERROR', kpi=None, score=None, logs=str(directory))
    processes = []
    start = time.monotonic()
    timed_out = False
    try:
        with (directory / 'interactor.stderr.txt').open('wb') as checker_err, \
             (directory / 'solution.stderr.txt').open('wb') as solution_err:
            checker = subprocess.Popen([str(interactor), str(path.resolve()), 'score.txt',
                                         'answer.txt', 'report.txt'], cwd=directory, env=env,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=checker_err)
            processes.append(checker)
            try:
                command = solution if isinstance(solution, list) else [str(solution)]
                program = subprocess.Popen(command, cwd=directory, env=env,
                                            stdin=checker.stdout, stdout=checker.stdin, stderr=solution_err)
                processes.append(program)
            finally:
                # EOF must propagate if either child exits, even on malformed output.
                checker.stdout.close()
                checker.stdin.close()
            deadline = start + timeout
            for process in (program, checker):
                process.wait(timeout=max(0.001, deadline - time.monotonic()))
            result['solution_exit'] = program.returncode
            result['interactor_exit'] = checker.returncode
            result['cpu_s'], result['peak_memory_mb'] = process_metrics(program)
    except subprocess.TimeoutExpired:
        timed_out = True
        result['status'] = 'TIMEOUT'
        result['error'] = f'Wall-time watchdog exceeded {timeout:g}s (both processes).'
    except OSError as error:
        result['error'] = str(error)
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill()
        for process in processes:
            process.wait()
        result['wall_s'] = time.monotonic() - start

    diagnostic = (directory / 'interactor.stderr.txt').read_text(encoding='utf-8', errors='replace')
    report = directory / 'report.txt'
    if report.exists():
        diagnostic += '\n' + report.read_text(encoding='utf-8', errors='replace')
    if not timed_out and len(processes) == 2:
        program, checker = processes[1], processes[0]
        if checker.returncode == 1:
            # A rejected placement closes the pipe; the solution may then fail on EOF.
            result['status'] = 'INVALID'
        elif program.returncode != 0:
            result['status'] = 'RUNTIME_ERROR'
        elif checker.returncode != 0:
            result['status'] = 'INVALID' if checker.returncode == 2 else 'INTERACTOR_ERROR'
        else:
            try:
                score = float((directory / 'score.txt').read_text().strip())
                match = re.search(r'Score = (\d+)/(\d+)', diagnostic)
                if not match:
                    raise ValueError('Interactor did not report occupied/trench lengths.')
                occupied, trench = map(int, match.groups())
                kpi = occupied / trench
                if not math.isfinite(score) or not 0 <= kpi <= 1 or not math.isclose(
                        score, kpi * weight, rel_tol=1e-7, abs_tol=1e-9):
                    raise ValueError('Inconsistent score from interactor.')
                result.update(status='OK', kpi=kpi, score=score,
                              occupied_m=occupied, trench_m=trench)
                if result['cpu_s'] is not None and result['cpu_s'] > 5:
                    result.update(status='CPU_LIMIT', error='Solution exceeded 5 CPU seconds.')
                if result['peak_memory_mb'] is not None and result['peak_memory_mb'] > 256:
                    result.update(status='MEMORY_LIMIT', error='Solution exceeded 256 MiB peak working set.')
            except (OSError, ValueError, ZeroDivisionError) as error:
                result['error'] = str(error)
    if result['status'] != 'OK':
        result['diagnostic'] = diagnostic[-4000:]
        result['solution_stderr'] = (directory / 'solution.stderr.txt').read_text(
            encoding='utf-8', errors='replace')[-4000:]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(r'C:\Program Files (x86)\Cangjie'))
    parser.add_argument('--suite', choices=['all', 'official', 'extra'], default='all')
    parser.add_argument('--test', type=int, nargs='+', help='Test numbers 1..65; overrides --suite.')
    parser.add_argument('--file', type=Path, help='Run a custom test file instead.')
    parser.add_argument('--timeout', type=float, default=30, help='Wall-time watchdog per test, seconds.')
    parser.add_argument('--no-build', action='store_true', help='Use the existing executable.')
    parser.add_argument('--generate-only', action='store_true', help='Regenerate the 50 extra tests and exit.')
    args = parser.parse_args()
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error('--timeout must be a finite positive number')
    if args.file and args.test:
        parser.error('--file and --test cannot be combined')
    if args.test and any(not 1 <= n <= 65 for n in args.test):
        parser.error('--test numbers must be between 1 and 65')

    manifest = generate()
    if args.generate_only:
        print(f'Generated {len(manifest)} tests in {EXTRA_DIR}; originals unchanged.')
        return 0
    extra_names = {entry['number']: entry['name'] for entry in manifest}
    if args.file:
        cases = [(args.file.resolve(), 'custom', args.file.stem)]
    else:
        numbers = args.test if args.test else (range(1, 66) if args.suite == 'all' else
                  range(1, 16) if args.suite == 'official' else range(16, 66))
        cases = [(ROOT / 'bench/tests' / f'{n}.in', 'official', f'official-{n}') if n <= 15 else
                 (EXTRA_DIR / f'{n}.in', 'extra', extra_names[n]) for n in sorted(set(numbers))]
    for path, _, _ in cases:
        validate(path)
    env, manager = sdk_environment(args.sdk)
    if not args.no_build:
        subprocess.run([str(manager), 'build'], cwd=ROOT, env=env, check=True)
    solution = ROOT / 'target/release/bin/main.exe'
    interactor = ROOT / 'bench/interactor.exe'
    for path in (solution, interactor):
        if not path.is_file():
            raise ValueError(f'Executable not found: {path}')
    print(f'Running {len(cases)} tests. CPU limit: 5s; peak working set: 256 MiB; '
          f'wall watchdog: {args.timeout:g}s.', flush=True)
    results = []
    summary_path = ROOT / 'target/test-results/summary.json'
    for path, group, name in cases:
        result = run_case(path, group, name, solution, interactor, env, args.timeout)
        results.append(result)
        kpi = f'{result["kpi"]:.6f}' if result['kpi'] is not None else '-'
        score = f'{result["score"]:.9f}' if result['score'] is not None else '-'
        cpu = result.get('cpu_s')
        memory = result.get('peak_memory_mb')
        metrics = f'CPU={cpu:.3f}s RAM={memory:.1f}MiB' if cpu is not None and memory is not None else ''
        print(f'{group:8} {path.stem:>2} {name:27} {result["status"]:12} '
              f'ops={result["operations"]:6} KPI={kpi} score={score} '
              f'wall={result["wall_s"]:.2f}s {metrics}', flush=True)
        if result['status'] != 'OK':
            print(result.get('error') or result.get('solution_stderr') or result.get('diagnostic'), flush=True)
            print(f'  Logs: {result["logs"]}', flush=True)
        summary_path.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    official = [r for r in results if r['group'] == 'official']
    if official:
        score = sum(r['score'] for r in official if r['status'] == 'OK')
        print(f'Official score ({len(official)} selected tests): {score:.9f} / '
              f'{sum(r["weight"] for r in official):g}')
    extra = [r for r in results if r['group'] != 'official']
    if extra:
        good = [r for r in extra if r['status'] == 'OK']
        print(f'Additional/custom tests: {len(good)}/{len(extra)} OK (not contest points).')
        if good:
            print(f'Additional KPI: mean={sum(r["kpi"] for r in good)/len(good):.6f}, '
                  f'min={min(r["kpi"] for r in good):.6f}')
    failed = [r for r in results if r['status'] != 'OK']
    print(f'Passed {len(results)-len(failed)}/{len(results)}; JSON report: {summary_path}')
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('\nTest run interrupted.', file=sys.stderr)
        sys.exit(130)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Error: {error}', file=sys.stderr)
        sys.exit(1)
