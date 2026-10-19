"""Short checks of the safe-swap history condition; no packing search."""
import unittest

from inspect_test11_exact import validate
from research_shared_prefix import common_prefix, permanent_slot_release, safe_swap


def lifetimes(commands):
    pipes = []
    for t, c in enumerate(commands):
        if c > 0:
            pipes.append([t, len(commands), c])
        else:
            pipes[-c - 1][1] = t
    return pipes


class SharedPrefixTests(unittest.TestCase):
    def setUp(self):
        self.shared = [2, 2, 2, -2, 2, 2]
        self.commands15 = self.shared + [1]
        self.commands11 = self.shared + [-1, 3]
        self.pipes15 = lifetimes(self.commands15)
        self.positions15 = [0, 2, 4, 2, 6, 8]
        self.cut, self.count = common_prefix(self.commands11, self.commands15)
        self.release = permanent_slot_release(self.pipes15, self.commands15,
                                              self.positions15, self.cut, self.count)

    def test_shared_command_and_pipe_counts_are_different(self):
        self.assertEqual((self.cut, self.count), (6, 5))

    def test_prior_temporary_occupant_restricts_earliest_start(self):
        self.assertEqual(self.release, {0: 0, 2: 0, 3: 3, 4: 0})
        self.assertFalse(safe_swap(self.pipes15, self.release, 0, 3))
        invalid = self.positions15[:]
        invalid[0], invalid[3] = invalid[3], invalid[0]
        self.assertFalse(validate(self.pipes15, self.commands15, invalid)[0])

    def test_all_accepted_fixture_swaps_pass_full_replay(self):
        accepted = []
        for i in self.release:
            for j in self.release:
                if i >= j or not safe_swap(self.pipes15, self.release, i, j):
                    continue
                accepted.append((i, j))
                trial = self.positions15[:]
                trial[i], trial[j] = trial[j], trial[i]
                self.assertEqual(validate(self.pipes15, self.commands15, trial), (True, 9))
        self.assertEqual(accepted, [(0, 2), (0, 4), (2, 4), (3, 4)])

    def test_a_transient_pipe_is_not_a_permanent_swap_candidate(self):
        self.assertFalse(safe_swap(self.pipes15, self.release, 0, 1))


if __name__ == '__main__':
    unittest.main(verbosity=2)
