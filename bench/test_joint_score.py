import unittest
from fractions import Fraction
from decimal import Decimal
from audit_joint_score import official_bounds,joint_total,rounded,target_reached,score,official_score_text,score_loss_units


class JointScoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bounds=official_bounds()

    def test_goal_matches_peak_upper_bounds(self):
        theoretical=sum(row['score_upper_bound']for row in self.bounds)
        self.assertEqual(rounded(theoretical,8),Decimal('30.75747405'))
        self.assertEqual(joint_total(20760,418780,self.bounds),theoretical)

    def test_three_decimal_test15_score_does_not_certify_total(self):
        self.assertEqual(rounded(score(10,418757,418780),3),Decimal('9.999'))
        self.assertEqual(rounded(score(10,418757,418800),3),Decimal('9.999'))
        self.assertEqual(rounded(joint_total(20760,418800,self.bounds),8),Decimal('30.75699652'))
        self.assertFalse(target_reached(20760,418800,self.bounds))

    def test_remaining_seven_meters_change_rounded_score(self):
        self.assertFalse(target_reached(20767,418780,self.bounds))
        self.assertTrue(target_reached(20760,418780,self.bounds))
        self.assertEqual(rounded(score(1,20361,20760),3),Decimal('0.981'))
        self.assertEqual(rounded(score(1,20361,20767),3),Decimal('0.980'))

    def test_height_rounding_is_not_round_to_nearest(self):
        self.assertEqual(score(1,100,100),Fraction(1))
        self.assertEqual(score(1,100,101),Fraction(5,6))

    def test_official_interactor_uses_significant_digits(self):
        self.assertEqual(official_score_text(score(10,418757,418780)),Decimal('9.99945079'))
        ideal_report=sum(official_score_text(row['score_upper_bound'])for row in self.bounds)
        self.assertEqual(ideal_report.quantize(Decimal('0.00000001')),Decimal('30.75747405'))

    def test_search_cost_prefers_better_joint_score_over_meter_potential(self):
        baseline=score_loss_units(1,20361,20760,20767)
        intermediate=score_loss_units(10,418757,418780,418800)
        self.assertGreater(baseline,intermediate)
        self.assertEqual(score_loss_units(1,20361,20760,20761),baseline)
        self.assertEqual(score_loss_units(1,20361,20760,20760),0)


if __name__=='__main__':
    unittest.main()
