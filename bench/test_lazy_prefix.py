import tempfile
import unittest
from pathlib import Path

from solve_test11_lazy import save_checked


class RelaxedPrefixValidationTests(unittest.TestCase):
    pipes = [[0, 2, 2], [1, 4, 1], [3, 4, 2]]
    commands = [2, 1, -1, 2]

    def test_shorter_prefix_keeps_full_collision_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'plan.txt'
            self.assertEqual(save_checked(path, [0, 2, 0], self.pipes,
                                          self.commands, [0], 3), 3)
            self.assertEqual(path.read_text().split(), ['0', '2', '0'])
            with self.assertRaises(ValueError):
                save_checked(path, [0, 1, 0], self.pipes,
                             self.commands, [0], 3)
            self.assertEqual(path.read_text().split(), ['0', '2', '0'])

    def test_changed_fixed_coordinate_and_excess_height_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'plan.txt'
            for plan, height in [([1, 3, 1], 4), ([0, 3, 0], 3)]:
                with self.assertRaises(ValueError):
                    save_checked(path, plan, self.pipes, self.commands, [0], height)
            self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()
