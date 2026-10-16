"""Reproducible valid interactive scenarios; never writes to bench/tests."""

import hashlib
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXTRA_DIR = ROOT / 'extra-tests'


class Scenario:
    def __init__(self):
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
        del self.active[pid]
        self.commands.append(-pid)


def validate(path):
    """Validate the test itself independently of any placement strategy."""
    tokens = path.read_text(encoding='ascii').split()
    weight, count = float(tokens[0]), int(tokens[1])
    commands = [int(token) for token in tokens[2:]]
    if weight <= 0 or not commands or commands[-1] != 0:
        raise ValueError(f'{path}: invalid weight or missing terminating zero')
    if len(commands) != count + 1 or not 1 <= count <= 200_000:
        raise ValueError(f'{path}: invalid operation count')
    active = {}
    pid = 0
    for index, command in enumerate(commands[:-1], 1):
        if 1 <= command <= 40:
            pid += 1
            active[pid] = command
        elif command < 0 and -command in active:
            del active[-command]
        else:
            raise ValueError(f'{path}: invalid operation {index}: {command}')
    return weight, count


def random_scenario(seed, count, remove_probability, lengths, policy='random'):
    rng = random.Random(seed)
    case = Scenario()
    # Dense list plus reverse indices keeps random deletion O(1) at 200k commands.
    live = []
    positions = {}
    for _ in range(count):
        if live and rng.random() < remove_probability:
            index = -1 if policy == 'lifo' else rng.randrange(len(live))
            pid = live[index]
            index = positions.pop(pid)
            last = live.pop()
            if index < len(live):
                live[index] = last
                positions[last] = index
            case.remove(pid)
        else:
            pid = case.add(rng.choice(lengths))
            positions[pid] = len(live)
            live.append(pid)
    return case


def generate():
    cases = []

    def save(name, case, description, seed=None):
        cases.append((name, case, description, seed))

    for length in [1, 2, 19, 20, 21, 38, 39, 40]:
        case = Scenario()
        case.add(length)
        save(f'single-{length}', case, f'One pipe of length {length}; dig rounding boundaries.')

    for length in [1, 2, 19, 20, 21, 38, 39, 40]:
        case = Scenario()
        for _ in range(1000):
            case.add(length)
        save(f'repeated-{length}', case, f'1000 equal pipes of length {length}, no removals.')

    for name, lengths in [
        ('ascending', list(range(1, 41))),
        ('descending', list(range(40, 0, -1))),
        ('alternating-extremes', [1, 40, 2, 39, 19, 21, 20]),
        ('exact-twenties', [1, 19, 2, 18, 3, 17, 9, 11, 20, 40]),
    ]:
        case = Scenario()
        for length in lengths * 100:
            case.add(length)
        save(name, case, 'Deterministic allocation-only length pattern.')

    for name, order in [('merge-right', [1, 2]), ('merge-left', [2, 1]),
                        ('merge-both', [1, 3, 2])]:
        case = Scenario()
        for _ in range(300):
            ids = [case.add(10) for _ in range(4)]
            for index in order:
                case.remove(ids[index - 1])
            case.add(25 if len(order) == 3 else 20)
        save(name, case, 'Adjacent holes freed in different orders, then reused.')

    for name, order in [('free-forward', list(range(200))),
                        ('free-backward', list(reversed(range(200)))),
                        ('free-shuffled', random.Random(20261016).sample(range(200), 200))]:
        case = Scenario()
        for _ in range(10):
            ids = [case.add(1 + i % 40) for i in range(200)]
            for index in order:
                case.remove(ids[index])
            # Keep an empty-trench state mid-test and continue with fresh PIDs.
        save(name, case, 'Repeatedly remove every active pipe and refill; finishes empty.')

    for name, removed, refill in [
        ('checkerboard-small', range(0, 400, 2), [1, 2, 3, 4, 5]),
        ('checkerboard-large', range(1, 400, 2), [39, 40, 21]),
        ('middle-hole', range(100, 300), list(range(40, 0, -1))),
        ('prefix-hole', range(200), [40, 1, 39]),
        ('suffix-hole', range(200, 400), [21, 40, 19]),
    ]:
        case = Scenario()
        ids = [case.add(20) for _ in range(400)]
        for index in removed:
            case.remove(ids[index])
        for length in refill * 200:
            case.add(length)
        save(name, case, 'Fragmentation, hole reuse, and growth after partial removal.')

    case = Scenario()
    for _ in range(500):
        pid = case.add(40)
        case.remove(pid)
        case.add(1)
        case.add(39)
    save('split-large-remainders', case, 'Repeated split into 1 and 39; exact remainder reuse.')

    case = Scenario()
    for _ in range(500):
        ids = [case.add(40) for _ in range(4)]
        for pid in ids[:3]:
            case.remove(pid)
        for length in [1, 39, 40, 20, 20]:
            case.add(length)
    save('large-merged-holes', case, 'Merge holes beyond size 40, then split across the bin boundary.')

    case = Scenario()
    for _ in range(1000):
        ids = [case.add(5) for _ in range(8)]
        for index in [0, 2, 4, 6, 3, 1, 5]:
            case.remove(ids[index])
        for length in [5, 10, 20]:
            case.add(length)
    save('bin-swap-removal', case, 'Many equal holes; removal and merging of non-last bin elements.')

    profiles = [
        (0.10, list(range(1, 41)), 'random'),
        (0.35, list(range(1, 41)), 'random'),
        (0.49, list(range(1, 41)), 'random'),
        (0.70, list(range(1, 41)), 'random'),
        (0.95, list(range(1, 41)), 'random'),
        (0.45, list(range(1, 6)), 'random'),
        (0.45, list(range(35, 41)), 'random'),
        (0.40, [19, 20, 21, 39, 40], 'random'),
        (0.50, [1, 40], 'random'),
        (0.49, list(range(1, 41)), 'lifo'),
        (0.25, [7, 13, 17, 23, 29, 31, 37], 'random'),
        (0.65, [1, 2, 20, 40], 'lifo'),
    ]
    for index, (probability, lengths, policy) in enumerate(profiles):
        seed = 2026101600 + index
        save(f'random-{index + 1:02d}',
             random_scenario(seed, 10_000, probability, lengths, policy),
             f'Seeded random: deletion probability {probability}, lengths {lengths}, {policy}.', seed)

    case = Scenario()
    for _ in range(200_000):
        case.add(40)
    save('stress-max-growth', case, '200000 allocations of length 40; 8 million occupied meters.')
    case = Scenario()
    for _ in range(100_000):
        case.remove(case.add(1))
    save('stress-pid-churn', case, '200000 operations; 100000 unique PIDs, ends empty.')
    for index, probability in enumerate([0.35, 0.50]):
        seed = 2026101690 + index
        save(f'stress-random-{index + 1}',
             random_scenario(seed, 200_000, probability, list(range(1, 41))),
             f'200000 commands with random removal probability {probability}.', seed)

    assert len(cases) == 50, len(cases)
    EXTRA_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    for number, (name, case, description, seed) in enumerate(cases, 16):
        path = EXTRA_DIR / f'{number}.in'
        text = f'1 {len(case.commands)}\n' + '\n'.join(map(str, case.commands)) + '\n0\n'
        path.write_bytes(text.encode('ascii'))
        validate(path)
        manifest.append(dict(number=number, name=name, description=description, seed=seed,
                             operations=len(case.commands), weight=1,
                             sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (EXTRA_DIR / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


if __name__ == '__main__':
    generated = generate()
    print(f'Generated {len(generated)} tests in {EXTRA_DIR}; original tests unchanged.')
