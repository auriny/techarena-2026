"""Small real-interactor regressions for plan selection and online handoff.

Compiles the production allocator/dispatcher with four tiny fixture plans into
an isolated target directory. Does not run or stop any optimization search.
"""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('handoff_harness', ROOT / 'run-tests.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


def encode_number(value):
    alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    result = ''
    while True:
        digit, value = value % 32, value // 32
        result += alphabet[digit + (32 if value else 0)]
        if not value:
            return result


def encode_plan(commands, positions):
    coordinates = iter(positions)
    encoded = ''
    for command in commands:
        encoded += encode_number(2 * abs(command) + int(command < 0))
        if command > 0:
            encoded += encode_number(next(coordinates))
    return '    EncodedPlan(["' + encoded + '"])'


class PlanHandoffTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = ROOT / 'target/plan-handoff-check'
        cls.directory.mkdir(parents=True, exist_ok=True)
        cls.env, _ = harness.sdk_environment(Path(r'C:\Program Files (x86)\Cangjie'))
        source = (ROOT / 'src/main.cj').read_text(encoding='utf-8')
        # The first pair disagrees on coordinates before its commands diverge.
        # The second pair shares coordinates and permits a valid later branch.
        fixtures = [
            ((10, 10, -1, 10), (0, 10, 0)),
            ((10, 10, -2, 10), (10, 0, 0)),
            ((20, 10, -1, 20), (0, 20, 0)),
            ((20, 10, -2, 10), (0, 20, 20)),
        ]
        source = source[:source.index('func makePlans(): Array<EncodedPlan> {')]
        source += 'func makePlans(): Array<EncodedPlan> {\n    return [\n'
        source += ',\n'.join(encode_plan(*fixture) for fixture in fixtures)
        source += '\n    ]\n}\n'
        fixture_source = cls.directory / 'fixture.cj'
        fixture_source.write_text(source, encoding='utf-8')
        cls.binary = cls.directory / 'fixture.exe'
        compiler = Path(r'C:\Program Files (x86)\Cangjie\bin\cjc.exe')
        compiled = subprocess.run(
            [str(compiler), str(fixture_source), '-O2', '-o', str(cls.binary)],
            cwd=cls.directory, env=cls.env, capture_output=True, text=True, timeout=60)
        if compiled.returncode:
            raise RuntimeError(compiled.stdout + compiled.stderr)

    def run_sequence(self, commands):
        # This interactor build does not persist its tout stream. Capture the
        # deterministic dispatcher separately to assert its exact answers.
        playback = subprocess.run(
            [str(self.binary)], input='\n'.join(map(str, (*commands, 0))) + '\n',
            cwd=self.directory, env=self.env, capture_output=True, text=True,
            check=True, timeout=10)
        with tempfile.TemporaryDirectory(dir=self.directory) as temporary:
            path = Path(temporary) / (self.id().split('.')[-1] + '.in')
            path.write_text('1 ' + str(len(commands)) + '\n'
                            + '\n'.join(map(str, commands)) + '\n0\n', encoding='ascii')
            result = harness.run_case(path, 'plan-handoff', path.stem, self.binary,
                                      ROOT / 'bench/interactor.exe', self.env, 10)
        self.assertEqual(result['status'], 'OK', result)
        return [tuple(map(int, line.split())) for line in playback.stdout.splitlines()]

    def test_conflicting_plan_cannot_rewrite_past_placements(self):
        answers = self.run_sequence((10, 10, -1, 10))
        self.assertEqual(answers[:2], [(1, 10), (0, 0)])
        self.assertEqual(answers[2], (10,))
        self.assertEqual(answers[3], (0, 10))

    def test_chosen_plan_remains_usable(self):
        self.assertEqual(self.run_sequence((10, 10, -2, 10)),
                         [(1, 10), (0, 0), (0,), (0, 0)])

    def test_compatible_plan_can_branch_after_common_prefix(self):
        self.assertEqual(self.run_sequence((20, 10, -1, 20)),
                         [(1, 0), (1, 20), (0,), (0, 0)])

    def test_other_compatible_branch(self):
        self.assertEqual(self.run_sequence((20, 10, -2, 10)),
                         [(1, 0), (1, 20), (20,), (0, 20)])

    def test_unrecognized_first_command(self):
        self.run_sequence((7, 7, -1, 16, -2, 40))

    def test_handoff_on_length_mismatch_preserves_live_pipes(self):
        self.run_sequence((10, 10, 15, -1, 40, -2, 7))

    def test_handoff_after_known_plan_ends(self):
        self.run_sequence((10, 10, -2, 10, -1, 40, -3, 7))


if __name__ == '__main__':
    unittest.main(verbosity=2)
