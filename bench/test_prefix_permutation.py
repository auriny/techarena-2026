import unittest
import itertools
import random

from solve_prefix_permutation import potential_overlap


class PrefixPermutationTests(unittest.TestCase):
    def test_touching_rectangles_do_not_overlap(self):
        self.assertFalse(potential_overlap([(0,0)],3,[(3,3)],2))
        self.assertTrue(potential_overlap([(0,0)],3,[(2,2)],2))

    def test_discontinuous_domains_do_not_use_their_convex_hull(self):
        self.assertFalse(potential_overlap([(0,0),(100,100)],2,[(10,20)],3))

    def test_sparse_pair_filter_against_exhaustive_integer_enumeration(self):
        rng=random.Random(1321)
        for _ in range(1000):
            a=sorted(set(rng.randrange(0,18)for _ in range(6)))
            b=sorted(set(rng.randrange(0,18)for _ in range(6)))
            la,lb=rng.randrange(1,8),rng.randrange(1,8)
            want=any(x<y+lb and y<x+la for x,y in itertools.product(a,b))
            self.assertEqual(potential_overlap([(x,x)for x in a],la,[(y,y)for y in b],lb),want)


if __name__=='__main__':
    unittest.main()
