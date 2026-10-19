"""Test 11: movable suffix, adaptive order cores, lazy collision constraints.

All suffix coordinates are variables. Only prefix coordinates are fixed.
Original order is a retractable search restriction, not a geometric boundary.
No candidate is a solution until the independent cell validator accepts it.

Example (PowerShell, from repository root):
  python bench/solve_test11_lazy.py --seconds 7200 --workers 16 --anchor --peak-capacity

There is no proved two-hour success bound for this instance. The budget and
child-process watchdog bound computation; disabling presolve avoids the old
quadratic presolve bottleneck. Exit 0 means a fully verified target plan.
"""
import argparse
import hashlib
import json
import multiprocessing as mp
import random
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target/solver11-deps'))
from ortools.sat.python import cp_model
from inspect_test11_exact import load_test, validate

PREFIX = 4006
PEAK_TIME = 9818


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value))
    temporary.replace(path)


def prefix_gaps(pipes, base, prefix_size, height, at_time):
    fixed = sorted((base[i], base[i]+pipes[i][2]) for i in range(prefix_size)
                   if pipes[i][0] <= at_time < pipes[i][1])
    gaps, cursor = [], 0
    for lo, hi in fixed:
        if lo > cursor:
            gaps.append((cursor, min(lo, height)))
        cursor = max(cursor, hi)
    if cursor < height:
        gaps.append((cursor, height))
    return [(lo, hi) for lo, hi in gaps if lo < hi]


def prefix_domains(pipes, base, prefix_size, height):
    """Exact integer y domains avoiding the prefix throughout each lifespan."""
    fixed = sorted((base[j], base[j]+pipes[j][2], pipes[j][0], pipes[j][1])
                   for j in range(prefix_size))
    result = []
    for st, en, length in pipes[prefix_size:]:
        cursor, intervals = 0, []
        for lo, hi, a, b in fixed:
            if st >= b or a >= en:
                continue
            if lo-cursor >= length:
                intervals.append((cursor, min(lo-length, height-length)))
            cursor = max(cursor, hi)
            if cursor+length > height:
                break
        if cursor+length <= height:
            intervals.append((cursor, height-length))
        result.append([(a, b) for a, b in intervals if a <= b])
    return result


def order_cover(pipes, positions, horizon):
    """At each time connect consecutive active pipes in vertical order.

    For every simultaneous pair, the resulting DAG contains an order path.
    These cover edges imply all baseline orders. Building the cover costs
    O(sum(en_i-st_i)) and does not enumerate all overlapping pairs.
    """
    last, edges = [-1]*horizon, set()
    for i in sorted(range(len(pipes)), key=lambda j: (positions[j], j)):
        st, en, _ = pipes[i]
        parents = set(last[st:en])
        parents.discard(-1)
        edges.update((j, i) for j in parents)
        last[st:en] = [i]*(en-st)
    return edges


def collisions(pipes, positions):
    """Find actual simultaneous 2D collisions without building all pairs."""
    active = []
    found = []
    for i in sorted(range(len(pipes)), key=lambda j: (positions[j], j)):
        yi = positions[i]
        active = [j for j in active if positions[j]+pipes[j][2] > yi]
        si, ei, _ = pipes[i]
        for j in active:
            sj, ej, _ = pipes[j]
            if si < ej and sj < ei:
                found.append((min(i, j), max(i, j)))
        active.append(i)
    return found


def nearest(domain, value):
    return min((min(b, max(a, value)) for a, b in domain),
               key=lambda candidate: abs(candidate-value))


def build_model(data, state, mode):
    pipes, domains = data['pipes'], data['domains']
    model = cp_model.CpModel()
    variables = [model.new_int_var_from_domain(cp_model.Domain.from_intervals(d), f'y{i}')
                 for i, d in enumerate(domains)]
    for i, var in enumerate(variables):
        model.add_hint(var, nearest(domains[i], state['hint'][i]))
    assumptions, reverse = [], {}
    # Optional coordinate assumptions are used by the joint-prefix driver.
    # Every pipe already has a variable: cores release blocking coordinates
    # without selecting arbitrary spatial or temporal subproblem boundaries.
    for i in state.get('fixed', []):
        if mode == 'core':
            keep = model.new_bool_var(f'coordinate{i}')
            model.add(variables[i] == data['base'][i]).only_enforce_if(keep)
            assumptions.append(keep)
            reverse[keep.index] = ('coordinate', i)
        else:
            # Assumption cores use a single worker. Ordinary search uses hard
            # equalities for this round so CP-SAT can use all requested workers.
            model.add(variables[i] == data['base'][i])
    for a, b in state['pinned']:
        if mode == 'core':
            keep = model.new_bool_var(f'keep{a}_{b}')
            model.add(variables[a]+pipes[a][2] <= variables[b]).only_enforce_if(keep)
            assumptions.append(keep)
            reverse[keep.index] = (a, b)
        else:
            model.add(variables[a]+pipes[a][2] <= variables[b])
    if assumptions:
        model.add_assumptions(assumptions)
    if data.get('other_geometry'):
        tx, hy = [], []
        for i, st, en, length in data['other_geometry']:
            tx.append(model.new_fixed_size_interval_var(st, en-st, f'othert{i}'))
            hy.append(model.new_fixed_size_interval_var(variables[i], length, f'otherh{i}'))
        model.add_no_overlap_2d(tx, hy)
    # Each free edge has BOTH orientations. No pipe disappears from the model.
    order_vars = {}
    for a, b in state['free']:
        before = model.new_bool_var(f'order{a}_{b}')
        model.add(variables[a]+pipes[a][2] <= variables[b]).only_enforce_if(before)
        model.add(variables[b]+pipes[b][2] <= variables[a]).only_enforce_if(before.Not())
        model.add_hint(before, int(state['hint'][a] <= state['hint'][b]))
        order_vars[a, b] = before
    if data['clique_times']:
        heights = [model.new_fixed_size_interval_var(variables[i], p[2], f'h{i}')
                   for i, p in enumerate(pipes)]
        seen = set()
        for t in data['clique_times']:
            members = tuple(i for i, (st, en, _) in enumerate(pipes) if st <= t < en)
            if len(members) < 2 or members in seen:
                continue
            seen.add(members)
            model.add_no_overlap(heights[i] for i in members)
    if state.get('local_cliques'):
        local_heights = {}
        for members in state['local_cliques']:
            for i in members:
                if i not in local_heights:
                    local_heights[i] = model.new_fixed_size_interval_var(
                        variables[i], pipes[i][2], f'localh{i}')
            model.add_no_overlap(local_heights[i] for i in members)
    # Exact necessary load conservation at several times, including nonpeak
    # times. This prevents a displaced pipe's length from disappearing into an
    # overlap in another slot of a relaxed candidate. Coordinates are shared
    # by every row; these are not independent per-time packings.
    memberships = {}
    for row in data.get('capacity_rows', []):
        assignment = {}
        for i, (st, en, length) in enumerate(pipes):
            if not st <= row['time'] < en:
                continue
            choices = []
            for k, (a, b) in enumerate(row['bins']):
                if not any(max(a, lo) <= min(b-length, hi) for lo, hi in domains[i]):
                    continue
                key = (i, a, b)
                if key not in memberships:
                    present = model.new_bool_var(f'bin{i}_{a}_{b}')
                    model.add(variables[i] >= a).only_enforce_if(present)
                    model.add(variables[i]+length <= b).only_enforce_if(present)
                    model.add_hint(present, int(a <= state['hint'][i] and
                                               state['hint'][i]+length <= b))
                    memberships[key] = present
                present = memberships[key]
                choices.append(present)
                assignment[i, k] = present
            model.add_exactly_one(choices)
        for k, (a, b) in enumerate(row['bins']):
            load = sum(pipes[i][2]*present for (i, j), present in assignment.items() if j == k)
            model.add(load <= b-a)
            # Every slot's slack is at most the total slack at this time.
            model.add(load >= b-a-row['slack'])
    if data.get('anchor') and mode != 'core':
        penalties = []
        for (a, b), before in order_vars.items():
            base_before = int(data['base'][a] <= data['base'][b])
            penalties.append(before.Not() if base_before else before)
        for (i, a, b), present in memberships.items():
            base_y = data['base'][i]
            in_base_slot = int(a <= base_y and base_y + pipes[i][2] <= b)
            if not in_base_slot:
                penalties.append(present * 10)
        if penalties:
            model.minimize(sum(penalties))
    return model, variables, reverse


class SearchEarlyExit(cp_model.CpSolverSolutionCallback):
    def __init__(self, variables, pipes, extension=2.0):
        super().__init__()
        self.variables = variables
        self.pipes = pipes
        self.extension = extension
        self.first_time = None
        self.best_positions = None
        self.timer = None

    def on_solution_callback(self):
        now = time.monotonic()
        pos = [self.value(v) for v in self.variables]
        self.best_positions = pos
        bad = collisions(self.pipes, pos)
        if not bad:
            if self.timer:
                self.timer.cancel()
            self.stop_search()
            return
        if self.first_time is None:
            self.first_time = now
            self.timer = threading.Timer(self.extension, self.stop_search)
            self.timer.daemon = True
            self.timer.start()
        elif now - self.first_time >= self.extension:
            self.stop_search()


def solve_child(data, state, mode, seconds, workers, seed, verbose, connection):
    """Runs in an expendable process: no unbounded native-solver wait."""
    cb = None
    try:
        started = time.monotonic()
        model, variables, reverse = build_model(data, state, mode)
        solver = cp_model.CpSolver()
        solver.parameters.max_time_in_seconds = seconds
        solver.parameters.num_search_workers = 1 if mode == 'core' else workers
        solver.parameters.random_seed = seed
        solver.parameters.log_search_progress = verbose
        solver.parameters.cp_model_presolve = False
        solver.parameters.linearization_level = 0
        solver.parameters.core_minimization_level = 1
        solver.parameters.hint_conflict_limit = 300
        solver.parameters.randomize_search = True
        cb = SearchEarlyExit(variables, data['pipes'], extension=2.0) if mode == 'search' else None
        result = solver.solve(model, cb) if cb else solver.solve(model)
        if cb and cb.timer:
            cb.timer.cancel()
        report = {'status': solver.status_name(result),
                  'wall': round(time.monotonic()-started, 3),
                  'conflicts': solver.num_conflicts, 'branches': solver.num_branches}
        if result in (cp_model.FEASIBLE, cp_model.OPTIMAL):
            report['positions'] = cb.best_positions if (cb and cb.best_positions) else [solver.value(v) for v in variables]
        elif result == cp_model.INFEASIBLE and mode == 'core':
            report['core'] = [reverse[int(lit)]
                              for lit in solver.sufficient_assumptions_for_infeasibility()]
        connection.send(report)
    except BaseException as error:
        if cb and cb.timer:
            cb.timer.cancel()
        connection.send({'status': 'ERROR', 'message': repr(error)})
    finally:
        if cb and cb.timer:
            cb.timer.cancel()
        connection.close()


def bounded_solve(data, state, mode, seconds, workers, seed, verbose, stop_path):
    if stop_path.exists():
        return {'status': 'STOPPED'}
    context = mp.get_context('spawn')
    receive, send = context.Pipe(duplex=False)
    process = context.Process(target=solve_child,
                              args=(data, state, mode, seconds, workers, seed, verbose, send))
    deadline = time.monotonic()+seconds+12
    heartbeat = time.monotonic()+30
    try:
        process.start()
        send.close()
        while time.monotonic() < deadline:
            if stop_path.exists():
                return {'status': 'STOPPED'}
            if receive.poll(.2):
                try:
                    report = receive.recv()
                except EOFError:
                    return {'status': 'ERROR', 'message': 'solver pipe closed without a report'}
                process.join(timeout=2)
                return report
            if not process.is_alive():
                return {'status': 'ERROR', 'message': f'child exit {process.exitcode}'}
            if data.get('heartbeat') and time.monotonic() >= heartbeat:
                print('SEARCH RUNNING',mode,'child',process.pid,
                      'seconds_remaining',round(max(0,deadline-time.monotonic()-12)),flush=True)
                heartbeat=time.monotonic()+30
        return {'status': 'WATCHDOG', 'wall': seconds+12}
    except KeyboardInterrupt:
        return {'status': 'STOPPED'}
    finally:
        if process.pid is not None:
            if process.is_alive():
                process.terminate()
            process.join(timeout=3)
        send.close()
        receive.close()


def parse_times(text):
    return sorted(set(int(s) for s in text.split(',') if s.strip()))


def validated_checkpoint(path, fingerprint, pipes, prefix, height):
    """A checkpoint is untrusted search state, never an output-plan shortcut."""
    old = json.loads(path.read_text())
    if (old.get('fingerprint', fingerprint) != fingerprint
            or old['height'] != height or old['prefix'] != prefix):
        raise ValueError('Checkpoint belongs to different input, prefix, or height')
    if len(old['hint']) != len(pipes) or any(type(y) is not int for y in old['hint']):
        raise ValueError('Invalid checkpoint coordinates')
    return old


def save_checked(path, full_positions, data, commands, prefix, ceiling):
    if full_positions[:len(prefix)] != prefix:
        raise ValueError('Attempt to save a changed immutable prefix')
    ok, height = validate(data, commands, full_positions)
    if not ok or height > ceiling:
        raise ValueError(('Refusing to save an invalid target plan', ok, height, ceiling))
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(''.join(f'{y}\n' for y in full_positions))
    temporary.replace(path)
    return height


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='target/test11-freed4.txt')
    parser.add_argument('--output', default='target/test11-candidate-20760.txt')
    parser.add_argument('--seconds', type=float, default=7200)
    parser.add_argument('--slice', type=float, default=20)
    parser.add_argument('--core-seconds', type=float, default=8)
    parser.add_argument('--workers', type=int, default=16)
    parser.add_argument('--seed', type=int, default=13)
    parser.add_argument('--height', type=int, default=20760)
    parser.add_argument('--prefix-size', type=int, default=PREFIX,
                        help='Fixed prefix size. Values below 4006 require diagnostic mode.')
    parser.add_argument('--diagnostic-relax-prefix', action='store_true',
                        help='TEST 11 ONLY: allow changes incompatible with Test 15; never a joint candidate.')
    parser.add_argument('--max-disjunctions', type=int, default=36000)
    parser.add_argument('--release', type=int, default=16,
                        help='Release at most this many edges per proven core; 0 means all.')
    parser.add_argument('--clique-times', default='',
                        help='Exact 1D active cliques at these times; empty disables.')
    parser.add_argument('--auto-cliques', action='store_true',
                        help='Add global active-time cliques after collisions (can be slow).')
    parser.add_argument('--local-cliques', type=int, default=0,
                        help='After collisions add exact cliques within this vertical radius.')
    parser.add_argument('--anchor', action=argparse.BooleanOptionalAction, default=True,
                        help='Minimize total displacement from the complete base in search solves.')
    parser.add_argument('--presolve', action='store_true',
                        help='Enable a single limited presolve iteration (default: disabled).')
    parser.add_argument('--peak-capacity', action=argparse.BooleanOptionalAction, default=True,
                        help='Add capacity rows at --capacity-times, including all active suffix pipes.')
    parser.add_argument('--capacity-times', default='9810,9818,9831,9837',
                        help='Necessary exact slot load bounds, not independent layouts.')
    parser.add_argument('--resume', help='Resume an order state JSON, not an ejection partial.')
    parser.add_argument('--state', default='target/test11-lazy-state.json')
    parser.add_argument('--stop-file', default='target/test11-lazy.stop',
                        help='Creating this file stops the parent and its solver child.')
    parser.add_argument('--log', action='store_true')
    parser.add_argument('--max-rounds', type=int, default=100000)
    parser.add_argument('--initial-free', choices=['none', 'cross', 'top', 'bottom'],
                        default='top', help='Initial order relations to make reversible.')
    parser.add_argument('--batch-limit', type=int, default=300,
                        help='Max new collision disjunctions to add per round.')
    args = parser.parse_args()
    prefix_size = args.prefix_size
    if not 1 <= prefix_size <= PREFIX:
        parser.error('prefix-size must be between 1 and 4006')
    if prefix_size < PREFIX:
        if not args.diagnostic_relax_prefix:
            parser.error('A relaxed shared prefix requires --diagnostic-relax-prefix')
        if args.output == 'target/test11-candidate-20760.txt':
            args.output = f'target/diagnostic-prefix{prefix_size}-test11-only.txt'
        if args.state == 'target/test11-lazy-state.json':
            args.state = f'target/diagnostic-prefix{prefix_size}-state.json'
        if args.stop_file == 'target/test11-lazy.stop':
            args.stop_file = f'target/diagnostic-prefix{prefix_size}.stop'
        print('DIAGNOSTIC TEST 11 ONLY: Test 15 compatibility is NOT modeled.', flush=True)
    if (args.workers < 1 or args.seconds <= 0 or args.slice <= 0 or args.core_seconds <= 0
            or args.max_disjunctions < 1 or args.release < 0 or args.height <= 0
            or args.max_rounds < 1 or args.local_cliques < 0 or args.batch_limit < 1):
        parser.error('Workers, time limits and disjunction cap must be positive; release >= 0.')
    stop_path = ROOT/args.stop_file
    if stop_path.exists():
        print('STOP FILE EXISTS: no solver started:', stop_path, flush=True)
        return 130
    started = time.monotonic()
    pipes, commands = load_test(ROOT/'bench/tests/11.in')
    base_path = ROOT/args.base
    if not base_path.exists() and args.base == 'target/test11-freed4.txt':
        base_path = ROOT/'target/test11-resume.txt'
    base = list(map(int, base_path.read_text().split()))
    reference = list(map(int, (ROOT/'target/candidate-best15.txt').read_text().split()))
    if len(base) != len(pipes) or len(reference) < prefix_size:
        raise ValueError('Wrong number of plan coordinates')
    prefix = reference[:prefix_size]
    if base[:prefix_size] != prefix or not validate(pipes, commands, base)[0]:
        raise ValueError('Base must be a complete valid plan with the immutable prefix')
    if max(base[i]+pipes[i][2] for i in range(prefix_size)) > args.height:
        print('PROVED INFEASIBLE: immutable prefix exceeds requested height', flush=True)
        return 2
    fingerprint = hashlib.sha256(json.dumps([pipes, prefix, args.height]).encode()).hexdigest()
    if max(base[i]+p[2] for i, p in enumerate(pipes)) <= args.height:
        save_checked(ROOT/args.output, base, pipes, commands, prefix, args.height)
        return 0
    domains = prefix_domains(pipes, base, prefix_size, args.height)
    if any(not domain for domain in domains):
        print('PROVED PREFIX-ONLY EMPTY DOMAIN', flush=True)
        return 2
    suffix = pipes[prefix_size:]
    cover = order_cover(suffix, base[prefix_size:], len(commands))
    data = {'pipes': suffix, 'domains': domains, 'base': base[prefix_size:],
            'anchor': args.anchor, 'presolve': args.presolve,
            'clique_times': parse_times(args.clique_times)}
    if any(not 0 <= t < len(commands) for t in data['clique_times']):
        parser.error('Clique times must be within the command horizon')
    pinned, free = set(cover), set()
    local_cliques = set()
    hint = base[prefix_size:]
    # Slots are used ONLY to choose initial reversible edges, never to bound y.
    gaps = prefix_gaps(pipes, base, prefix_size, args.height, PEAK_TIME)
    if args.peak_capacity:
        data['capacity_rows'] = []
        for at_time in parse_times(args.capacity_times):
            if not 0 <= at_time < len(commands):
                parser.error('Capacity times must be within the command horizon')
            load = sum(length for st, en, length in pipes if st <= at_time < en)
            if load > args.height:
                print('PROVED INFEASIBLE: total live length exceeds height at', at_time, flush=True)
                return 2
            data['capacity_rows'].append({
                'time': at_time, 'slack': args.height-load,
                'bins': prefix_gaps(pipes, base, prefix_size, args.height, at_time)})
    def old_slot(i):
        z = base[i+prefix_size]
        return next((k for k, (a, b) in enumerate(gaps) if a <= z < b), -1)
    for a, b in cover:
        both_peak = (suffix[a][0] <= PEAK_TIME < suffix[a][1] and
                     suffix[b][0] <= PEAK_TIME < suffix[b][1])
        make_free = ((args.initial_free == 'cross' and both_peak and old_slot(a) != old_slot(b))
                     or (args.initial_free == 'top' and
                         max(base[a+prefix_size], base[b+prefix_size]) >= 20361)
                     or (args.initial_free == 'bottom' and
                         (min(base[a+prefix_size], base[b+prefix_size]) < 1800
                          or max(base[a+prefix_size], base[b+prefix_size]) >= 20361)))
        if make_free:
            pinned.remove((a, b))
            free.add((min(a, b), max(a, b)))
    if args.resume:
        old = validated_checkpoint(ROOT/args.resume, fingerprint, suffix, prefix, args.height)
        pinned = {tuple(edge) for edge in old['pinned']}
        free = {tuple(edge) for edge in old['free']}
        hint = old['hint']
        local_cliques = {tuple(members) for members in old.get('local_cliques', [])}
        data['clique_times'] = sorted(set(data['clique_times']) | set(old.get('clique_times', [])))
        if any(not 0 <= t < len(commands) for t in data['clique_times']):
            raise ValueError('Invalid checkpoint clique time')
    if len(hint) != len(suffix):
        raise ValueError('Wrong checkpoint hint length')
    for a, b in pinned | free:
        if (not 0 <= a < len(suffix) or not 0 <= b < len(suffix) or a == b
                or suffix[a][0] >= suffix[b][1] or suffix[b][0] >= suffix[a][1]):
            raise ValueError('Invalid checkpoint order edge')
    if any((min(a, b), max(a, b)) in free for a, b in pinned):
        raise ValueError('Checkpoint pins a reversible edge')
    for members in local_cliques:
        if (len(set(members)) != len(members)
                or any(not 0 <= i < len(suffix) for i in members)
                or (members and max(suffix[i][0] for i in members)
                    >= min(suffix[i][1] for i in members))):
            raise ValueError('Checkpoint contains a clique without a common active time')
    rng = random.Random(args.seed)
    hot_pipes = set()
    def edge_priority(edge):
        a, b = edge
        hot = (100.0 if (a in hot_pipes or b in hot_pipes) else 0.0)
        h_max = max(base[a+prefix_size], base[b+prefix_size])
        height_score = h_max / 20760.0
        t_mid = 0.5 * (min(suffix[a][1], suffix[b][1]) + max(suffix[a][0], suffix[b][0]))
        time_score = 1.0 / (1.0 + abs(t_mid - PEAK_TIME) / 100.0)
        return hot + height_score * 10.0 + time_score * 5.0 + rng.random() * 2.0
    print('START', 'H', args.height, 'suffix', len(suffix), 'cover', len(cover),
          'pinned', len(pinned), 'free', len(free),
          'presolve', 'ON' if args.presolve else 'OFF',
          'cliques', data['clique_times'], flush=True)
    print('All suffix y coordinates are movable; prefix alone is fixed.', flush=True)
    unknown = 0
    core_budget = args.core_seconds
    mode = 'core'
    stopped = False
    for iteration in range(args.max_rounds):
        remaining = args.seconds-(time.monotonic()-started)
        if remaining < 2 or stop_path.exists():
            stopped = stop_path.exists()
            break
        if len(free) >= args.max_disjunctions:
            print('DISJUNCTION CAP reached; checkpoint will be saved.', flush=True)
            break
        state = {'pinned': sorted(pinned), 'free': sorted(free), 'hint': hint,
                 'local_cliques': sorted(local_cliques)}
        # Save only search state here, never a purported complete target plan.
        disk = dict(state, height=args.height, prefix=prefix, fingerprint=fingerprint,
                    kind='SEARCH_STATE_NOT_A_VALIDATED_PLAN', clique_times=data['clique_times'])
        state_path = ROOT/args.state
        atomic_json(state_path, disk)
        seconds = min(remaining-1, core_budget if mode == 'core' else args.slice)
        last_report = bounded_solve(data, state, mode, seconds, args.workers,
                                    args.seed+iteration, args.log, stop_path)
        status = last_report['status']
        print('ROUND', iteration, mode, status, 'pinned', len(pinned), 'free', len(free),
              'wall', last_report.get('wall'),
              'branches', last_report.get('branches'),
              'elapsed', round(time.monotonic()-started, 1), flush=True)
        if status == 'ERROR' or status == 'MODEL_INVALID':
            print(last_report, flush=True)
            return 3
        if status == 'STOPPED':
            stopped = True
            break
        if 'positions' in last_report:
            pos = last_report['positions']
            bad = collisions(suffix, pos)
            if not bad:
                full = base[:prefix_size]+pos
                height = save_checked(ROOT/args.output, full, pipes, commands, prefix, args.height)
                print('VERIFIED TEST11-ONLY DIAGNOSTIC' if prefix_size < PREFIX else 'VERIFIED TARGET',
                      height, 'output', ROOT/args.output, flush=True)
                return 0
            if mode == 'core':
                # Core mode is unanchored; positions are arbitrary and cause thousands of fake collisions!
                # Do NOT run SEPARATE on unanchored core solutions. Switch to search mode instead!
                mode, unknown = 'search', 0
                continue
            hint = pos
            additions = set(bad)-free
            if not additions:
                raise RuntimeError('Collision separator found a violation of an existing exact constraint')
            # A pinned edge cannot collide in a solution of this model.
            assert not any((a, b) in pinned or (b, a) in pinned for a, b in additions)
            available = args.max_disjunctions-len(free)
            def collision_severity(pair):
                a, b = pair
                overlap = min(hint[a]+suffix[a][2], hint[b]+suffix[b][2]) - max(hint[a], hint[b])
                at_peak = int(suffix[a][0] <= PEAK_TIME < suffix[a][1] and suffix[b][0] <= PEAK_TIME < suffix[b][1])
                top = int(base[a+prefix_size] >= 20361 or base[b+prefix_size] >= 20361)
                return top * 10000 + at_peak * 1000 + overlap
            sorted_additions = sorted(additions, key=collision_severity, reverse=True)
            batch_cap = min(args.batch_limit, available)
            additions = set(sorted_additions[:batch_cap])
            free.update(additions)
            print('SEPARATE', len(bad), 'collisions;', len(additions),
                  f'new exact pair constraints (batch cap {batch_cap})', flush=True)
            hot_pipes = {i for pair in bad for i in pair}
            incident = [e for e in pinned if e[0] in hot_pipes or e[1] in hot_pipes]
            incident.sort(key=edge_priority, reverse=True)
            for a, b in incident[:16]:
                pinned.remove((a, b))
                free.add((min(a, b), max(a, b)))
            # Add the worst violating time as another exact clique, lazily.
            times = {}
            for a, b in bad:
                t = max(suffix[a][0], suffix[b][0])
                times[t] = times.get(t, 0)+1
            if args.auto_cliques and times and len(data['clique_times']) < 6:
                t = max(times, key=times.get)
                if t not in data['clique_times']:
                    data['clique_times'].append(t)
            if args.local_cliques:
                # Sort bad by proximity to upper corridor so cliques protect the peak and top corridor!
                bad_top = sorted(bad, key=lambda pair: max(hint[pair[0]], hint[pair[1]]), reverse=True)
                for a, b in bad_top[:24]:
                    t = max(suffix[a][0], suffix[b][0])
                    center = min(hint[a], hint[b])
                    members = tuple(i for i, (st, en, _) in enumerate(suffix)
                                    if st <= t < en and
                                    abs(hint[i]-center) <= args.local_cliques)
                    if len(members) >= 3:
                        local_cliques.add(members)
            mode, unknown = 'search', 0
        elif status == 'INFEASIBLE':
            if mode != 'core':
                mode = 'core'
                unknown += 1
                if unknown >= 2 and pinned:
                    edges = list(pinned)
                    edges.sort(key=edge_priority, reverse=True)
                    release = edges[:min(24, len(edges))]
                    for a, b in release:
                        pinned.remove((a, b))
                        free.add((min(a, b), max(a, b)))
                    print('DIVERSIFY (search UNSAT, core timeout): release', len(release), 'orders', flush=True)
                    unknown = 0
                continue
            unknown = 0
            core = [tuple(edge) for edge in last_report['core']]
            if not core:
                print('PROVED INFEASIBLE: no order assumptions remain in the core.', flush=True)
                return 2
            # Prefer relations near observed conflicts and corridor exchanges.
            # This changes search priority only; every core member remains a
            # retractable order restriction, never an immutable coordinate.
            core.sort(key=edge_priority, reverse=True)
            if args.release:
                core = core[:args.release]
            for a, b in core:
                pinned.remove((a, b))
                free.add((min(a, b), max(a, b)))
            print('RELEASE CORE', len(core),
                  [(a+prefix_size, b+prefix_size) for a, b in core[:24]], flush=True)
            mode = 'core'
        else:
            unknown += 1
            mode = 'search'
            if unknown >= 2 and pinned:
                # UNKNOWN proves nothing. Releasing order assumptions enlarges
                # the search space soundly; no exclusion/no-good is inferred.
                edges = list(pinned)
                edges.sort(key=edge_priority, reverse=True)
                release = edges[:min(24, len(edges))]
                for a, b in release:
                    pinned.remove((a, b))
                    free.add((min(a, b), max(a, b)))
                print('DIVERSIFY: release', len(release), 'orders; UNKNOWN is not UNSAT', flush=True)
                unknown = 0
                core_budget = min(30, core_budget*1.25)
    disk = {'height': args.height, 'prefix': prefix, 'pinned': sorted(pinned),
            'free': sorted(free), 'hint': hint, 'local_cliques': sorted(local_cliques),
            'kind': 'SEARCH_STATE_NOT_A_VALIDATED_PLAN', 'fingerprint': fingerprint,
            'clique_times': data['clique_times']}
    atomic_json(ROOT/args.state, disk)
    print('STOPPED' if stopped else 'BUDGET/CAP END',
          ': target not found; complete baseline preserved.', flush=True)
    return 130 if stopped else 1


if __name__ == '__main__':
    mp.freeze_support()
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('STOPPED: last checkpoint preserved.', flush=True)
        raise SystemExit(130)
