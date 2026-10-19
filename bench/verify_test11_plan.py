"""Validate a complete Test 11 plan, including its immutable Test 15 prefix."""
import argparse
import json
from pathlib import Path

from inspect_test11_exact import load_test, validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plan', nargs='?', default='target/test11-resume.txt')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    path = Path(args.plan)
    if not path.is_absolute():
        path = root / path
    pipes, commands = load_test(root / 'bench/tests/11.in')
    positions = list(map(int, path.read_text().split()))
    reference = list(map(int, (root / 'target/candidate-best15.txt').read_text().split()))
    original = list(map(int, (root / 'target/candidate-best11.txt').read_text().split()))
    assert len(positions) == len(pipes), (len(positions), len(pipes))
    assert len(reference) >= 4006
    prefix_differences = sum(a != b for a, b in zip(positions[:4006], reference[:4006]))
    valid, result = validate(pipes, commands, positions)
    raw_height = max(y + pipe[2] for y, pipe in zip(positions, pipes))
    report = {
        'plan': str(path),
        'pipes': len(pipes),
        'commands': len(commands),
        'collision_free': valid,
        'validation_result': result,
        'prefix_differences': prefix_differences,
        'raw_height': raw_height,
        'rounded_height': ((raw_height + 19) // 20) * 20,
        'changes_from_original': sum(a != b for a, b in zip(positions, original)),
        'above_20760': [
            {'idx': i, 'st': pipe[0], 'en': pipe[1], 'len': pipe[2], 'y': y}
            for i, (pipe, y) in enumerate(zip(pipes, positions))
            if y + pipe[2] > 20760
        ],
        'target_reached': valid and prefix_differences == 0 and raw_height <= 20760,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not valid or prefix_differences:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
