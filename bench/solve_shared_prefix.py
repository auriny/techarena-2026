"""Joint exact repair of Tests 11 and 15, using shared prefix variables.

Every pipe remains represented, either by a variable or by an exact domain
excluding all fixed rectangles for its entire lifetime. No order is frozen.
"""
import argparse
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target/solver11-deps'))
from ortools.sat.python import cp_model
from inspect_test11_exact import load_test, validate
from solve_test11_peak import domains_for
from audit_joint_score import official_bounds, joint_total, rounded, target_reached, score_loss_units


def atomic_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(text, encoding='utf-8')
    temporary.replace(path)


def intersect(a, b):
    out = []
    i = j = 0
    while i < len(a) and j < len(b):
        lo, hi = max(a[i][0], b[j][0]), min(a[i][1], b[j][1])
        if lo <= hi:
            out.append((lo, hi))
        if a[i][1] < b[j][1]:
            i += 1
        else:
            j += 1
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--band', type=int, default=771)
    ap.add_argument('--seconds', type=float, default=180)
    ap.add_argument('--workers', type=int, default=16)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--base11', default='target/test11-freed4.txt')
    ap.add_argument('--base15', default='target/candidate-best15.txt')
    ap.add_argument('--output', default='target/joint20760')
    ap.add_argument('--move-boundary', action='store_true')
    ap.add_argument('--log', action='store_true')
    ap.add_argument('--presolve', type=int, default=1)
    ap.add_argument('--optimize', action='store_true')
    ap.add_argument('--radius', type=int, default=0)
    ap.add_argument('--tail-band', type=int, default=0)
    ap.add_argument('--tail-time', type=int, default=198978)
    ap.add_argument('--shared-band', type=int, default=0)
    ap.add_argument('--cut', type=int, default=0)
    ap.add_argument('--capacity', action='store_true')
    ap.add_argument('--force20', type=int, default=-1)
    ap.add_argument('--swap', default='')
    ap.add_argument('--suffix-band', type=int, default=-1)
    ap.add_argument('--shared-ids', default='')
    ap.add_argument('--top-min', type=int, default=20354)
    ap.add_argument('--height15', type=int, default=418780)
    ap.add_argument('--shared-file')
    ap.add_argument('--suffix-file')
    ap.add_argument('--tail-file')
    ap.add_argument('--shared-radius', type=int, default=0)
    ap.add_argument('--suffix-radius', type=int, default=0)
    ap.add_argument('--top-floor', type=int, default=-1)
    ap.add_argument('--free-suffix', default='')
    ap.add_argument('--free-shared', default='')
    ap.add_argument('--require-change',action='store_true',help='Exclude the unchanged shared prefix; all pipes remain modeled.')
    ap.add_argument('--focus-boundary',action='store_true',help='Use boundary 20 position as a secondary search objective.')
    ap.add_argument('--accept-raw-hint',action='store_true',help='Allow selected rectangles in an otherwise fixed valid plan to overlap initially.')
    args = ap.parse_args()
    if args.height15 < 418780:
        ap.error('Test 15 peak load requires rounded height at least 418780')
    if args.seconds <= 0 or args.workers < 1:
        ap.error('seconds and workers must be positive')
    started = time.monotonic()
    report_path = (ROOT / args.output).with_suffix('.report.json')
    def report(status, **extra):
        atomic_write(report_path, json.dumps(dict(status=status, args=vars(args),
                     elapsed=time.monotonic()-started, **extra), indent=2))
    bounds = official_bounds()
    p, c = load_test(ROOT / 'bench/tests/11.in')
    q, d = load_test(ROOT / 'bench/tests/15.in')
    a = list(map(int, (ROOT / args.base11).read_text().split()))
    b = list(map(int, (ROOT / args.base15).read_text().split()))
    assert a[:4006] == b[:4006]
    assert args.accept_raw_hint or (validate(p,c,a)[0] and validate(q,d,b)[0])
    # Permanent prefix blocks of Test 15 can be rearranged while considering
    # all preceding occupants through the exact fixed-obstacle domains.
    shared = {i for i in range(1, 4006) if b[i] < (args.shared_band or args.band) and q[i][1] == len(d)}
    if args.shared_ids:
        shared = set(map(int,args.shared_ids.split(',')))
    if args.shared_file:
        shared = set(json.loads((ROOT/args.shared_file).read_text()))
    suffix = {i for i in range(4006, len(p)) if (a[i] < (args.band if args.suffix_band < 0 else args.suffix_band) or a[i] >= args.top_min) and p[i][1] > args.cut}
    tail = set()
    if args.suffix_file:
        suffix = set(json.loads((ROOT/args.suffix_file).read_text()))
    if args.tail_file:
        tail = set(json.loads((ROOT/args.tail_file).read_text()))
    swaps = [tuple(map(int, s.split(':'))) for s in args.swap.split(',') if s]
    for i,j in swaps:
        shared.update((i,j))
    if args.move_boundary:
        shared.add(20)
        # The only suffix pipe using boundary 20's block after its deletion.
        tail.add(129778)
        if args.tail_band:
            tail.update(i for i in range(4006,len(q)) if q[i][1] > args.tail_time and
                        (b[i] < args.tail_band or 20340 <= b[i] < 20370 or b[i] >= 418500))
    s11, s15 = shared | suffix, shared | tail
    if args.accept_raw_hint:
        def verify_fixed(pipes,commands,positions,moving):
            cells=bytearray(max(z+l for z,(_,_,l) in zip(positions,pipes)))
            pid=0
            for command in commands:
                if command>0:
                    if pid not in moving:
                        at=positions[pid]
                        assert not any(cells[at:at+command]),('Fixed rectangle collision',pid)
                        cells[at:at+command]=b'\1'*command
                    pid+=1
                else:
                    i=-command-1
                    if i not in moving:
                        at,length=positions[i],pipes[i][2]
                        cells[at:at+length]=b'\0'*length
        verify_fixed(p,c,a,s11)
        verify_fixed(q,d,b,s15)
    h11 = max(20760, max(y + z[2] for y, z in zip(a, p))) if args.optimize else 20760
    h15 = args.height15
    print('SELECTED shared', len(shared), 'suffix11', len(suffix), 'suffix15', len(tail), flush=True)
    da = domains_for(p, a, s11, h11)
    db = domains_for(q, b, s15, h15)
    domains = {i: intersect(da[i], db[i]) for i in shared}
    domains.update({i: da[i] for i in suffix})
    if args.radius:
        for i in shared:
            domains[i] = intersect(domains[i], [(max(0, a[i]-args.radius), a[i]+args.radius)])
    if args.shared_radius:
        for i in shared:
            if i != 20 and all(i not in pair for pair in swaps) and i not in set(map(int,filter(None,args.free_shared.split(',')))):
                domains[i] = intersect(domains[i],[(max(0,a[i]-args.shared_radius),a[i]+args.shared_radius)])
    if args.suffix_radius:
        for i in suffix:
            if a[i] < 20354 and i not in set(map(int,filter(None,args.free_suffix.split(',')))):
                domains[i] = intersect(domains[i],[(max(0,a[i]-args.suffix_radius),a[i]+args.suffix_radius)])
    if args.top_floor >= 0:
        for i in suffix:
            if a[i] >= 20354:
                domains[i] = intersect(domains[i],[(args.top_floor,h11-p[i][2])])
    empty = [i for i in domains if not domains[i]]
    if empty:
        print('EMPTY', empty, flush=True)
        report('EMPTY_DOMAIN', empty=empty)
        return 2
    model = cp_model.CpModel()
    y = {i: model.new_int_var_from_domain(cp_model.Domain.from_intervals(v), f'y{i}')
         for i, v in sorted(domains.items())}
    z = {i: model.new_int_var_from_domain(cp_model.Domain.from_intervals(db[i]), f'z{i}') for i in tail}
    for i, var in y.items():
        hint = min((min(hi, max(lo, a[i])) for lo, hi in domains[i]), key=lambda v: abs(v-a[i]))
        model.add_hint(var, hint)
    for i, var in z.items():
        if any(lo <= b[i] <= hi for lo, hi in db[i]):
            model.add_hint(var, b[i])
    for i,j in swaps:
        model.add(y[i] == a[j])
        model.add(y[j] == a[i])
    if args.force20 >= 0:
        model.add(y[20] == args.force20)
    if args.require_change:
        changed=[]
        for i in sorted(shared):
            bv=model.new_bool_var(f'changed{i}')
            model.add(y[i]!=a[i]).only_enforce_if(bv)
            model.add(y[i]==a[i]).only_enforce_if(~bv)
            model.add_hint(bv,0)
            changed.append(bv)
        model.add_bool_or(changed)
    ceiling = model.new_int_var(20760, h11, 'ceiling')
    ceiling15 = model.new_int_var(418780,h15,'ceiling15')
    model.add(ceiling >= max((a[i]+v[2] for i,v in enumerate(p) if i not in s11),default=0))
    model.add(ceiling15 >= max((b[i]+v[2] for i,v in enumerate(q) if i not in s15),default=0))
    for i in y:
        model.add(y[i] + p[i][2] <= ceiling)
    for i in shared:
        model.add(y[i]+q[i][2] <= ceiling15)
    for i in tail:
        model.add(z[i]+q[i][2] <= ceiling15)
    if args.optimize or h15>418780:
        # Compare OFFICIAL rounded-height scores before raw-meter tie breaks.
        # 20760/418800 improves the total over 20767/418780, even though
        # 2*excess11+excess15 incorrectly preferred the latter pair.
        losses=[]
        for test, variable, minimum, maximum in (
                (11,ceiling,20760,h11),(15,ceiling15,418780,h15)):
            row=bounds[test-1]
            costs=[score_loss_units(row['weight'],row['occupied'],minimum,k*20)
                   for k in range(minimum//20,(maximum+19)//20+1)]
            bucket=model.new_int_var(0,len(costs)-1,f'score_bucket{test}')
            model.add_division_equality(bucket,variable+19-minimum,20)
            loss=model.new_int_var(0,max(costs),f'score_loss{test}')
            model.add_element(bucket,costs,loss)
            losses.append(loss)
        raw_excess=(ceiling-20760)+(ceiling15-418780)
        tie_range=(h11-20760)+(h15-418780)+1
        objective=sum(losses)*tie_range+raw_excess
        if args.focus_boundary and 20 in y:
            objective=objective*(h11+1)+y[20]
        model.minimize(objective)
        model.add_hint(ceiling, h11)
        model.add_hint(ceiling15, min(h15,max(v+pipe[2]for v,pipe in zip(b,q))))

    def add_geometry(pipes, variables, label, times):
        tx, hy = [], {}
        for i, var in variables.items():
            st, en, length = pipes[i]
            tx.append(model.new_fixed_size_interval_var(st, en-st, f'{label}t{i}'))
            hy[i] = model.new_fixed_size_interval_var(var, length, f'{label}h{i}')
        model.add_no_overlap_2d(tx, list(hy.values()))
        for t in times:
            model.add_no_overlap(hy[i] for i in variables if pipes[i][0] <= t < pipes[i][1])
        return hy

    add_geometry(p, y, 'a', [9810, 9818, 9831, 9837])
    add_geometry(q, {**{i:y[i] for i in shared}, **z}, 'b', [4999, 199999])
    if args.capacity:
        for t in [9810,9818,9831,9837]:
            fixed = sorted((a[i],a[i]+v[2]) for i,v in enumerate(p)
                           if i not in y and v[0] <= t < v[1])
            bins, end = [], 0
            for lo,hi in fixed:
                if lo > end:
                    bins.append((end,lo))
                end = max(end,hi)
            if end < h11:
                bins.append((end,h11))
            loads = [[] for _ in bins]
            for i,var in y.items():
                if not p[i][0] <= t < p[i][1]:
                    continue
                length = p[i][2]
                choices = [(k,lo,hi) for k,(lo,hi) in enumerate(bins)
                           if any(max(lo,x) <= min(hi-length,z) for x,z in domains[i])]
                if len(choices) == 1:
                    loads[choices[0][0]].append(length)
                else:
                    bs = []
                    for k,lo,hi in choices:
                        bv = model.new_bool_var(f'cap{t}_{i}_{k}')
                        model.add(var >= lo).only_enforce_if(bv)
                        model.add(var+length <= hi).only_enforce_if(bv)
                        model.add_hint(bv,int(lo <= a[i] and a[i]+length <= hi))
                        bs.append(bv)
                        loads[k].append(length*bv)
                    model.add_exactly_one(bs)
            total_load = sum(v[2] for v in p if v[0] <= t < v[1])
            for k,(lo,hi) in enumerate(bins):
                cap = ceiling-lo if hi == h11 else hi-lo
                model.add(sum(loads[k]) <= cap)
                model.add(sum(loads[k]) >= cap-(ceiling-total_load))
        print('CAPACITY constraints added',flush=True)
    print('MODEL', len(y)+len(z), 'domain pieces', sum(map(len, domains.values())),
          'build_s', round(time.monotonic()-started, 3), flush=True)
    solver = cp_model.CpSolver()
    solver.parameters.num_search_workers = args.workers
    solver.parameters.max_time_in_seconds = args.seconds
    solver.parameters.random_seed = args.seed
    solver.parameters.max_presolve_iterations = args.presolve
    solver.parameters.cp_model_presolve = bool(args.presolve)
    solver.parameters.cp_model_probing_level = 0
    solver.parameters.log_search_progress = args.log
    solver.parameters.use_timetabling_in_no_overlap_2d = True
    solver.parameters.use_energetic_reasoning_in_no_overlap_2d = True
    solver.parameters.use_area_energetic_reasoning_in_no_overlap_2d = True

    def save(values11, values15):
        aa, bb = a[:], b[:]
        for i, v in values11.items():
            aa[i] = v
        for i in shared:
            bb[i] = aa[i]
        for i, v in values15.items():
            bb[i] = v
        assert aa[:4006] == bb[:4006]
        ok11, height11 = validate(p, c, aa)
        ok15, height15 = validate(q, d, bb)
        assert ok11 and ok15, (height11, height15)
        assert height11 <= h11 and height15 <= h15
        root = ROOT / args.output
        root.parent.mkdir(parents=True, exist_ok=True)
        for name, values in [('11', aa), ('15', bb)]:
            atomic_write(root.with_name(root.name + '-' + name + '.txt'), ''.join(f'{v}\n' for v in values))
        print('VERIFIED heights', height11, height15, 'changed shared',
              sum(aa[i] != a[i] for i in shared), 'elapsed', round(time.monotonic()-started, 2), flush=True)
        achieved=target_reached(height11,height15,bounds)
        total=rounded(joint_total(height11,height15,bounds),8)
        print('JOINT total_rounded8',str(total),'target_reached',achieved,flush=True)
        atomic_write(root.with_suffix('.json'), json.dumps({'height11':height11, 'height15':height15,
             'joint_total_rounded8':str(total),'joint_target_reached':achieved,
             'args':vars(args), 'shared_changes':[[i,a[i],aa[i]] for i in sorted(shared) if aa[i] != a[i]]}, indent=2))
        if achieved:
            for name,values in [('11',aa),('15',bb)]:
                path=ROOT/f'target/test{name}-joint-candidate-optimal.txt'
                temporary=path.with_suffix('.tmp')
                temporary.write_text(''.join(f'{v}\n'for v in values))
                temporary.replace(path)
        return achieved

    class Progress(cp_model.CpSolverSolutionCallback):
        def on_solution_callback(self):
            achieved = save({i:self.value(v) for i,v in y.items()}, {i:self.value(v) for i,v in z.items()})
            if achieved:
                self.stop_search()
    result = solver.solve(model, Progress())
    print('STATUS', solver.status_name(result), 'wall', solver.wall_time, 'branches', solver.num_branches, flush=True)
    report(solver.status_name(result),wall=solver.wall_time,branches=solver.num_branches,
           conflicts=solver.num_conflicts,best_bound=solver.best_objective_bound)
    if result in (cp_model.FEASIBLE, cp_model.OPTIMAL):
        save({i:solver.value(v) for i,v in y.items()}, {i:solver.value(v) for i,v in z.items()})
        return 0
    return 2


if __name__ == '__main__':
    raise SystemExit(main())
