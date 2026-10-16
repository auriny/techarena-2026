"""Integration checks for the Python harness using the real official interactor."""

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('harness', ROOT / 'run-tests.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.env, _ = harness.sdk_environment(Path(r'C:\Program Files (x86)\Cangjie'))
        cls.interactor = ROOT / 'bench/interactor.exe'
        cls.manifest = harness.generate()

    def run_stub(self, code, commands=(1,), timeout=3):
        with tempfile.TemporaryDirectory(dir=ROOT / 'target', prefix='harness-check-') as directory:
            path = Path(directory) / 'case.in'
            path.write_text(f'1 {len(commands)}\n' + '\n'.join(map(str, commands)) + '\n0\n')
            result = harness.run_case(path, 'selftest', 'stub', [sys.executable, '-u', '-c', code],
                                      self.interactor, self.env, timeout)
            self.assertTrue((Path(result['logs']) / 'solution.stderr.txt').exists())
            return result

    def test_valid_zero_kpi_is_not_failure(self):
        code = "input(); print('1 0', flush=True); input(); print('0', flush=True); input()"
        result = self.run_stub(code, (1, -1))
        self.assertEqual(result['status'], 'OK')
        self.assertEqual(result['kpi'], 0)

    def test_interactor_rejects_overlap(self):
        result = self.run_stub("input(); print('1 0', flush=True); input(); print('0 0', flush=True); input()",
                               (1, 1))
        self.assertEqual(result['status'], 'INVALID')
        self.assertIn('collides', result['diagnostic'])

    def test_runtime_error(self):
        result = self.run_stub("import sys; print('intentional failure', file=sys.stderr); sys.exit(7)")
        self.assertEqual(result['status'], 'RUNTIME_ERROR')
        self.assertIn('intentional failure', result['solution_stderr'])

    def test_no_flush_or_answer_times_out(self):
        result = self.run_stub('import time; input(); time.sleep(20)', timeout=0.5)
        self.assertEqual(result['status'], 'TIMEOUT')
        self.assertLess(result['wall_s'], 4)

    def test_output_without_newline_times_out(self):
        result = self.run_stub("import sys; input(); sys.stdout.write('1 0'); sys.stdout.flush(); input()",
                               timeout=0.5)
        self.assertEqual(result['status'], 'TIMEOUT')

    def test_old_score_is_not_reused(self):
        self.assertEqual(self.run_stub("input(); print('1 0', flush=True); input()")['status'], 'OK')
        result = self.run_stub("input(); print('0 0', flush=True); input()")
        self.assertEqual(result['status'], 'INVALID')
        self.assertIsNone(result['score'])

    def test_generated_scenarios_are_valid_and_reproducible(self):
        originals = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (ROOT / 'bench/tests').glob('*.in')}
        regenerated = harness.generate()
        self.assertEqual(regenerated, self.manifest)
        self.assertEqual(len(regenerated), 50)
        self.assertEqual([m['number'] for m in regenerated], list(range(16, 66)))
        for entry in regenerated:
            path = harness.EXTRA_DIR / f'{entry["number"]}.in'
            weight, count = harness.validate(path)
            self.assertEqual((weight, count), (1, entry['operations']))
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry['sha256'])
        self.assertEqual(originals, {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in (ROOT / 'bench/tests').glob('*.in')})


if __name__ == '__main__':
    unittest.main(verbosity=2)
