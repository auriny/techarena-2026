import unittest

from solve_test11_lazy import build_model, cp_model
from solve_joint_transit import fixed_gaps, expansion_candidates, result_action


class JointTransitModelTests(unittest.TestCase):
    def test_core_identifies_fixed_blocker_and_releasing_it_repairs(self):
        data={'pipes':[[0,5,2],[0,5,2]],'domains':[[(0,0)],[(0,2)]],
              'base':[0,0],'anchor':False,'clique_times':[],
              'other_geometry':[]}
        state={'pinned':[],'free':[(0,1)],'hint':[0,0],'fixed':[1]}
        model,variables,reverse=build_model(data,state,'core')
        solver=cp_model.CpSolver()
        self.assertEqual(solver.solve(model),cp_model.INFEASIBLE)
        core=[reverse[int(lit)]for lit in solver.sufficient_assumptions_for_infeasibility()]
        self.assertIn(('coordinate',1),core)
        state['fixed']=[]
        model,variables,_=build_model(data,state,'core')
        self.assertEqual(solver.solve(model),cp_model.OPTIMAL)
        self.assertEqual([solver.value(v)for v in variables],[0,2])
        state['fixed']=[1]
        search_model,_,reverse=build_model(data,state,'search')
        self.assertEqual(len(search_model.proto.assumptions),0)
        self.assertFalse(reverse)
        self.assertEqual(solver.solve(search_model),cp_model.INFEASIBLE)

    def test_shared_pipes_must_also_be_disjoint_in_test15(self):
        data={'pipes':[[0,2,2],[2,4,2]],'domains':[[(0,0)],[(0,0)]],
              'base':[0,0],'anchor':False,'clique_times':[],
              'other_geometry':[(0,0,4,2),(1,2,4,2)]}
        state={'pinned':[],'free':[],'hint':[0,0],'fixed':[]}
        model,_,_=build_model(data,state,'core')
        self.assertEqual(cp_model.CpSolver().solve(model),cp_model.INFEASIBLE)
        data['other_geometry']=[(0,0,2,2),(1,2,4,2)]
        model,_,_=build_model(data,state,'core')
        self.assertEqual(cp_model.CpSolver().solve(model),cp_model.OPTIMAL)

    def test_fixed_gaps_use_half_open_time_and_keep_moving_load_elsewhere(self):
        pipes=[[0,2,2],[2,4,2],[0,4,3]]
        self.assertEqual(fixed_gaps(pipes,[0,2,4],{2},8,2),[(0,2),(4,8)])

    def test_proven_infeasible_scope_expands_after_core_timeout(self):
        data={'pipes':[[0,5,2],[0,5,2]],'domains':[[(0,0)],[(0,2)]],
              'base':[0,0],'anchor':False,'clique_times':[]}
        state={'pinned':[],'free':[(0,1)],'hint':[0,0],'fixed':[1]}
        solver=cp_model.CpSolver()
        model,_,_=build_model(data,state,'search')
        status=solver.status_name(solver.solve(model))
        self.assertEqual(result_action('search',status,False),'extract_core')
        self.assertEqual(result_action('core','UNKNOWN',True),'expand_proved_scope')
        released=expansion_candidates(data['pipes'],data['base'],data['domains'],
                                      state['free'],set(state['fixed']),8)
        self.assertEqual(released,{1})
        state['fixed']=list(set(state['fixed'])-released)
        model,variables,_=build_model(data,state,'search')
        self.assertEqual(solver.solve(model),cp_model.OPTIMAL)
        self.assertEqual([solver.value(v)for v in variables],[0,2])

    def test_unknown_without_infeasibility_is_not_a_proof(self):
        self.assertEqual(result_action('core','UNKNOWN',False),'ordinary')
        self.assertEqual(result_action('search','UNKNOWN',True),'ordinary')

    def test_expansion_skips_redundant_singleton_fixations(self):
        pipes=[[0,5,2],[0,5,2],[0,5,2]]
        self.assertEqual(expansion_candidates(pipes,[0,0,5],
            [[(0,0)],[(0,2)],[(5,5)]],[(0,1),(0,2)],{1,2},8),{1})


if __name__=='__main__':
    unittest.main()
