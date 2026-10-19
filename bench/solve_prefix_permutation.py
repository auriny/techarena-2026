"""Joint repair using permutations of permanent Test 15 prefix blocks.

Test 15's entire geometry is preserved: a selected prefix pipe is assigned
one existing, equally sized permanent block, after its last earlier occupant
has left. AllDifferent makes each block used exactly once. This replaces the
130255-pipe second model with a small matching problem, without dropping any
rectangle or assuming access to future commands at runtime.
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
from research_shared_prefix import permanent_slot_release
from solve_test11_peak import domains_for


def intersect(a, b):
    out = []
    for x, y in a:
        for z, w in b:
            if max(x, z) <= min(y, w):
                out.append((max(x, z), min(y, w)))
    return sorted(out)


def potential_overlap(a, la, b, lb):
    i = j = 0
    while i < len(a) and j < len(b):
        if a[i][0] < b[j][1] + lb and b[j][0] < a[i][1] + la:
            return True
        if a[i][1] + la <= b[j][0]:
            i += 1
        else:
            j += 1
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--seconds', type=float, default=180)
    ap.add_argument('--workers', type=int, default=16)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--band', type=int, default=1600)
    ap.add_argument('--wall', type=int, default=10405)
    ap.add_argument('--donors', type=int, default=5)
    ap.add_argument('--radius', type=int, default=80)
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--log', action='store_true')
    ap.add_argument('--prefix-file')
    ap.add_argument('--free-suffix', default='')
    ap.add_argument('--output', default='target/prefix-permutation')
    ap.add_argument('--base11',default='target/test11-freed4.txt')
    ap.add_argument('--base15',default='target/candidate-best15.txt')
    ap.add_argument('--suffix-file')
    ap.add_argument('--repair-hint',action='store_true',help='Allow collisions in selected hint rectangles; fixed rectangles are still checked.')
    args = ap.parse_args()
    start = time.monotonic()
    p, c = load_test(ROOT / 'bench/tests/11.in')
    q, d = load_test(ROOT / 'bench/tests/15.in')
    a = list(map(int, (ROOT/args.base11).read_text().split()))
    b = list(map(int, (ROOT/args.base15).read_text().split()))
    assert a[:4006] == b[:4006]
    assert (args.repair_hint or validate(p,c,a)[0]) and validate(q,d,b)[0]
    release = permanent_slot_release(q, d, b, 4999, 4006)
    shared = {i for i in release if 10298 <= a[i] < args.wall}
    for length in range(1, 8):
        candidates = [i for i in release if 0 < i < 4006 and a[i] < args.band
                      and p[i][2] == length and p[i][1] < 9818]
        candidates.sort(key=lambda i: (min(abs(a[i]-x) for x in (113, 141, 286, 348, 682, 686, 765, 771, 1523)),
                                       -p[i][1], i))
        shared.update(candidates[:args.donors])
    if args.prefix_file:
        shared = set(json.loads((ROOT/args.prefix_file).read_text()))
    assert 0 not in shared and all(i in release for i in shared)
    suffix = {i for i in range(4006, len(p)) if a[i] < args.band or a[i] >= 20354
              or 10230 <= a[i] < args.wall + 50}
    if args.suffix_file:
        suffix=set(json.loads((ROOT/args.suffix_file).read_text()))
    selected = shared | suffix
    if args.repair_hint:
        cells=bytearray(max(z+l for z,(_,_,l) in zip(a,p)))
        pid=0
        for command in c:
            if command>0:
                if pid not in selected:
                    z=a[pid]
                    assert not any(cells[z:z+command]),('Fixed hint collision',pid)
                    cells[z:z+command]=b'\1'*command
                pid+=1
            else:
                i=-command-1
                if i not in selected:
                    z,l=a[i],p[i][2]
                    cells[z:z+l]=b'\0'*l
    height = 20760 if args.hard else 20767
    dom = domains_for(p, a, selected, height)
    for i in shared:
        allowed = sorted(a[j] for j in shared if p[j][2] == p[i][2] and p[i][0] >= release[j])
        dom[i] = intersect(dom[i], [(v, v) for v in allowed])
    free = set(map(int, filter(None, args.free_suffix.split(','))))
    for i in suffix:
        if i in free or a[i] >= 20354:
            continue
        windows = [(max(0, a[i]-args.radius), a[i]+args.radius)]
        # Every selected suffix pipe may enter the new cavity in the wall.
        windows.append((10230, args.wall+50))
        dom[i] = intersect(dom[i], windows)
    empty = [i for i in selected if not dom[i]]
    if empty:
        print('EMPTY', empty, flush=True)
        return 2
    model = cp_model.CpModel()
    y = {i:model.new_int_var_from_domain(cp_model.Domain.from_intervals(dom[i]), f'y{i}')
         for i in sorted(selected)}
    ceiling = model.new_int_var(20760, height, 'height11')
    for i, var in y.items():
        model.add(var+p[i][2] <= ceiling)
        if any(lo <= a[i] <= hi for lo,hi in dom[i]):
            model.add_hint(var, a[i])
    model.add_hint(ceiling, height)
    if not args.hard:
        model.minimize(ceiling)
    for length in range(1,8):
        model.add_all_different(y[i] for i in shared if p[i][2] == length)
    ids = sorted(selected)
    pairs = 0
    for k,i in enumerate(ids):
        for j in ids[k+1:]:
            if i in shared and j in shared:
                continue  # Matching already guarantees disjoint permanent blocks.
            if max(p[i][0],p[j][0]) >= min(p[i][1],p[j][1]):
                continue
            if not potential_overlap(dom[i],p[i][2],dom[j],p[j][2]):
                continue
            bv = model.new_bool_var(f'b{i}_{j}')
            model.add(y[i]+p[i][2] <= y[j]).only_enforce_if(bv)
            model.add(y[j]+p[j][2] <= y[i]).only_enforce_if(~bv)
            model.add_hint(bv, int(a[i]+p[i][2] <= a[j]))
            pairs += 1
    # Redundant peak cliques propagate long chains without quadratic presolve.
    for t in (9810,9818,9831,9837):
        intervals = [model.new_fixed_size_interval_var(y[i], p[i][2], f'c{t}_{i}')
                     for i in ids if p[i][0] <= t < p[i][1]]
        model.add_no_overlap(intervals)
        fixed = sorted((a[i],a[i]+v[2]) for i,v in enumerate(p)
                       if i not in selected and v[0] <= t < v[1])
        bins, end = [], 0
        for lo,hi in fixed:
            if lo > end:
                bins.append((end,lo))
            end = max(end,hi)
        if end < height:
            bins.append((end,height))
        loads = [[] for _ in bins]
        for i in ids:
            if not p[i][0] <= t < p[i][1]:
                continue
            choices = [(k,lo,hi) for k,(lo,hi) in enumerate(bins)
                       if any(max(lo,x) <= min(hi-p[i][2],z) for x,z in dom[i])]
            if len(choices) == 1:
                loads[choices[0][0]].append(p[i][2])
                continue
            bs = []
            for k,lo,hi in choices:
                v = model.new_bool_var(f'cap{t}_{i}_{k}')
                model.add(y[i] >= lo).only_enforce_if(v)
                model.add(y[i]+p[i][2] <= hi).only_enforce_if(v)
                model.add_hint(v, int(lo <= a[i] and a[i]+p[i][2] <= hi))
                loads[k].append(p[i][2]*v)
                bs.append(v)
            model.add_exactly_one(bs)
        total = sum(l for st,en,l in p if st <= t < en)
        for k,(lo,hi) in enumerate(bins):
            cap = ceiling-lo if hi == height else hi-lo
            model.add(sum(loads[k]) <= cap)
            model.add(sum(loads[k]) >= cap-(ceiling-total))
    print('MODEL shared',len(shared),'suffix',len(suffix),'pairs',pairs,
          'build',round(time.monotonic()-start,2),flush=True)
    solver = cp_model.CpSolver()
    solver.parameters.num_search_workers = args.workers
    solver.parameters.max_time_in_seconds = args.seconds
    solver.parameters.random_seed = args.seed
    solver.parameters.max_presolve_iterations = 1
    solver.parameters.cp_model_probing_level = 0
    solver.parameters.log_search_progress = args.log
    def save(callback):
        aa,bb = a[:],b[:]
        for i in y:
            aa[i] = callback.value(y[i])
        for i in shared:
            bb[i] = aa[i]
        assert aa[:4006] == bb[:4006]
        valid11,h11 = validate(p,c,aa)
        valid15,h15 = validate(q,d,bb)
        assert valid11 and valid15 and h11 <= height and h15 <= 418780
        root = ROOT / args.output
        root.parent.mkdir(parents=True,exist_ok=True)
        for name,plan in [('11',aa),('15',bb)]:
            root.with_name(root.name+'-'+name+'.txt').write_text(''.join(f'{v}\n' for v in plan))
        print('VERIFIED',h11,h15,'prefix changes',sum(aa[i]!=a[i] for i in shared),
              'elapsed',round(time.monotonic()-start,2),flush=True)
        return h11
    class Progress(cp_model.CpSolverSolutionCallback):
        def on_solution_callback(self):
            if save(self) <= 20760:
                self.stop_search()
    status = solver.solve(model,Progress())
    print('STATUS',solver.status_name(status),'bound',solver.best_objective_bound,
          'wall',solver.wall_time,'branches',solver.num_branches,flush=True)
    if status in (cp_model.FEASIBLE,cp_model.OPTIMAL):
        save(solver)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
