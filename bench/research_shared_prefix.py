"""Audit safe shared-prefix swaps; never starts a packing solver.

Only emits research data. A swap preserving Test 15 may collide with suffix
pipes in Test 11; those blockers are reported, not silently removed.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from inspect_test11_exact import load_test, validate

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BOUNDARIES = (3242, 1426, 2903, 1122, 993, 752, 3997, 20)


def common_prefix(commands_a, commands_b):
    count = next((i for i, (a, b) in enumerate(zip(commands_a, commands_b))
                  if a != b), min(len(commands_a), len(commands_b)))
    return count, sum(c > 0 for c in commands_a[:count])


def permanent_slot_release(pipes, commands, positions, command_count, pipe_count):
    """Earliest safe start at each permanent prefix pipe's original block.

    The block is empty from its last preceding deletion until its original
    permanent owner starts. All permanent owners survive to the test end.
    """
    height = max(positions[i] + pipes[i][2] for i in range(pipe_count))
    last_free = [0] * height
    release = {}
    pid = 0
    for t, command in enumerate(commands[:command_count]):
        if command > 0:
            if pipes[pid][1] == len(commands):
                release[pid] = max(last_free[positions[pid]:positions[pid] + command])
            pid += 1
        else:
            i = -command - 1
            start, length = positions[i], pipes[i][2]
            last_free[start:start + length] = [t] * length
    assert pid == pipe_count
    return release


def safe_swap(pipes, release, i, j):
    return (i != j and i in release and j in release
            and pipes[i][2] == pipes[j][2]
            and pipes[i][0] >= release[j]
            and pipes[j][0] >= release[i])


def spatial_index(pipes, positions):
    height = max(y + p[2] for y, p in zip(positions, pipes))
    rows = [set() for _ in range(height)]
    for i, (y, (_, _, length)) in enumerate(zip(positions, pipes)):
        for x in range(y, y + length):
            rows[x].add(i)
    return rows


def swap_blockers(pipes, positions, rows, i, j):
    blockers = set()
    for moving, destination in ((i, j), (j, i)):
        st, en, length = pipes[moving]
        at = positions[destination]
        occupants = set().union(*rows[at:at + length])
        blockers.update(k for k in occupants if k not in (i, j)
                        and st < pipes[k][1] and pipes[k][0] < en)
    return sorted(blockers)


def analyze(pipes11, commands11, positions11, pipes15, commands15, positions15,
            boundary_ids, at_time):
    cut, count = common_prefix(commands11, commands15)
    if not count or positions11[:count] != positions15[:count]:
        raise ValueError('The reference plans must have identical common-prefix coordinates')
    if any(not 0 <= i < count for i in boundary_ids):
        raise ValueError('Boundary IDs must belong to the shared prefix')
    release = permanent_slot_release(pipes15, commands15, positions15, cut, count)
    groups = defaultdict(list)
    for i in release:
        groups[pipes15[i][2]].append(i)
    rows = spatial_index(pipes11, positions11)
    safe_count = peak_change_count = 0
    proposals = []
    for ids in groups.values():
        for index, i in enumerate(ids):
            for j in ids[index + 1:]:
                if not safe_swap(pipes15, release, i, j):
                    continue
                safe_count += 1
                live_i = pipes11[i][0] <= at_time < pipes11[i][1]
                live_j = pipes11[j][0] <= at_time < pipes11[j][1]
                if live_i == live_j:
                    continue
                peak_change_count += 1
                if i not in boundary_ids and j not in boundary_ids:
                    continue
                blockers = swap_blockers(pipes11, positions11, rows, i, j)
                if any(k < count for k in blockers):
                    raise AssertionError('A supposedly safe swap collides with the shared prefix')
                proposals.append({
                    'swap': [i, j], 'length': pipes11[i][2],
                    'old_y': [positions11[i], positions11[j]],
                    'lifetimes11': [pipes11[i][:2], pipes11[j][:2]],
                    'blockers11': blockers,
                    'blocker_count': len(blockers),
                    'test11_is_a_valid_plan': False,
                })
    proposals.sort(key=lambda v: (v['blocker_count'], v['swap']))
    return {
        'kind': 'RESEARCH_PROPOSALS_NOT_VALIDATED_TEST11_PLANS',
        'indices': 'zero based', 'common_commands': cut, 'common_pipes': count,
        'permanent_prefix_pipes15': len(release),
        'safe_equal_length_swaps15': safe_count,
        'swaps_changing_peak_geometry11': peak_change_count,
        'peak_time11': at_time, 'examined_boundaries': sorted(boundary_ids),
        'proposals': proposals,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base11', default='target/test11-freed4.txt')
    parser.add_argument('--base15', default='target/candidate-best15.txt')
    parser.add_argument('--peak', type=int, default=9818)
    parser.add_argument('--boundary-ids', default=','.join(map(str, DEFAULT_BOUNDARIES)),
                        help='Only filters reported proposals, not the all-swap counters.')
    parser.add_argument('--verify', type=int, default=3,
                        help='Strictly replay this many proposed swaps on complete Test 15.')
    parser.add_argument('--output', default='target/test11-shared-prefix-research.json')
    args = parser.parse_args()
    if args.verify < 0:
        parser.error('--verify must be nonnegative')
    pipes11, commands11 = load_test(ROOT / 'bench/tests/11.in')
    pipes15, commands15 = load_test(ROOT / 'bench/tests/15.in')
    positions11 = list(map(int, (ROOT / args.base11).read_text().split()))
    positions15 = list(map(int, (ROOT / args.base15).read_text().split()))
    for name, pipes, commands, positions in (
            ('11', pipes11, commands11, positions11),
            ('15', pipes15, commands15, positions15)):
        ok, result = validate(pipes, commands, positions)
        if not ok:
            raise ValueError(('Invalid reference plan', name, result))
        print('REFERENCE', name, 'valid', ok, 'raw_height', result, flush=True)
    boundaries = {int(i) for i in args.boundary_ids.split(',') if i.strip()}
    report = analyze(pipes11, commands11, positions11, pipes15, commands15,
                     positions15, boundaries, args.peak)
    reference_height15 = max(y + p[2] for y, p in zip(positions15, pipes15))
    for proposal in report['proposals'][:args.verify]:
        i, j = proposal['swap']
        trial = positions15[:]
        trial[i], trial[j] = trial[j], trial[i]
        ok, height = validate(pipes15, commands15, trial)
        if not ok or height != reference_height15:
            raise AssertionError(('The safe-swap claim failed', i, j, ok, height))
        proposal['full_test15_replay'] = {'valid': True, 'raw_height': height}
    report['verified_test15_proposals'] = min(args.verify, len(report['proposals']))
    path = ROOT / args.output
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'proposals'}, indent=2))
    print('PROPOSALS', len(report['proposals']), 'output', path)
    for proposal in report['proposals'][:12]:
        print(json.dumps(proposal))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
