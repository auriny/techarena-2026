"""Exact Test 11 repair with explicit peak-slot capacity constraints."""
import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target' / 'solver11-deps'))
from ortools.sat.python import cp_model
from inspect_test11_exact import load_test, validate


def domains_for(pipes, positions, selected, height):
    fixed = sorted((positions[i], positions[i] + p[2], p[0], p[1])
                   for i, p in enumerate(pipes) if i not in selected)
    domains = {}
    for i in sorted(selected):
        st, en, length = pipes[i]
        cur = 0
        out = []
        for a, b, s, e in fixed:
            if s >= en or e <= st:
                continue
            if a - cur >= length:
                out.append((cur, min(a - length, height - length)))
            cur = max(cur, b)
            if cur + length > height:
                break
        if cur + length <= height:
            out.append((cur, height - length))
        domains[i] = [(a, b) for a,b in out if a<=b]
    return domains


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cut', type=int, default=9650)
    ap.add_argument('--band', type=int, default=1000)
    ap.add_argument('--seconds', type=float, default=60)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--ordered', action='store_true')
    ap.add_argument('--pairwise', action='store_true')
    ap.add_argument('--log', action='store_true')
    ap.add_argument('--optimize', action='store_true')
    ap.add_argument('--output', default='target/test11-20760.txt')
    ap.add_argument('--base', default='target/candidate-best11.txt')
    ap.add_argument('--move-limit', type=int, default=0)
    ap.add_argument('--cumulative', action='store_true')
    ap.add_argument('--assignment')
    ap.add_argument('--workers',type=int,default=16)
    ap.add_argument('--peak-positions')
    ap.add_argument('--repair-hints', action='store_true')
    ap.add_argument('--near-base',type=int,default=0)
    ap.add_argument('--order-unmoved',action='store_true')
    ap.add_argument('--assume-order',action='store_true')
    ap.add_argument('--tight',action='store_true')
    ap.add_argument('--flow-small',action='store_true')
    ap.add_argument('--optional-slots',action='store_true')
    ap.add_argument('--lns-only',action='store_true')
    ap.add_argument('--lns-difficulty',type=float,default=0.5)
    ap.add_argument('--extra-moves',default='')
    ap.add_argument('--preserve-bins',action='store_true')
    ap.add_argument('--load-objective',action='store_true')
    ap.add_argument('--select-file')
    ap.add_argument('--ceiling',type=int,default=0)
    ap.add_argument('--radius',type=int,default=0)
    ap.add_argument('--fixed-order',action='store_true')
    ap.add_argument('--order-nonpeak',action='store_true')
    ap.add_argument('--top-cumulative',action='store_true')
    ap.add_argument('--presolve-iterations',type=int,default=3)
    ap.add_argument('--ordered-chains',action='store_true')
    args=ap.parse_args()
    pipes,commands=load_test(ROOT/'bench/tests/11.in')
    base=list(map(int,(ROOT/args.base).read_text().split()))
    prefix=list(map(int,(ROOT/'target/candidate-best15.txt').read_text().split()))[:4006]
    assert base[:4006]==prefix
    assert validate(pipes,commands,base)[0]
    H=max(20760,max(base[i]+p[2] for i,p in enumerate(pipes))) if args.optimize else 20760; peak_t=9818
    if args.ceiling:H=args.ceiling
    fixed_at_peak=sorted((base[i],base[i]+pipes[i][2]) for i in range(4006) if pipes[i][0]<=peak_t<pipes[i][1])
    bins=[]; end=0
    for a,b in fixed_at_peak:
        if a>end:bins.append((end,a))
        end=max(end,b)
    if end<H:bins.append((end,H))
    selected={i for i in range(4006,len(pipes)) if pipes[i][1]>args.cut and (base[i]<args.band or base[i]>=20361)}
    if args.select_file:selected=set(json.loads((ROOT/args.select_file).read_text()))
    assert min(selected)>=4006
    domains=domains_for(pipes,base,selected,H)
    print('selected',len(selected),'domain positions',sum(sum(b-a+1 for a,b in d) for d in domains.values()),flush=True)
    for i,d in domains.items():
        if args.radius:
            domains[i]=[(max(a,base[i]-args.radius),min(b,base[i]+args.radius)) for a,b in d if max(a,base[i]-args.radius)<=min(b,base[i]+args.radius)]
        if not d:raise ValueError(('Empty domain',i,pipes[i]))
    model=cp_model.CpModel()
    ceiling=model.new_int_var(20759,H,'ceiling')
    if args.optimize:
        model.minimize(ceiling)
        model.add_hint(ceiling,H)
    else:model.add(ceiling==H)
    y={i:model.new_int_var_from_domain(cp_model.Domain.from_intervals(domains[i]),f'y{i}') for i in sorted(selected)}
    moved={}
    if args.near_base or args.order_unmoved:
        for i in sorted(selected):
            moved[i]=model.new_bool_var(f'moved{i}')
            model.add(y[i]==base[i]).only_enforce_if(moved[i].Not())
            model.add(y[i]!=base[i]).only_enforce_if(moved[i])
            model.add_hint(moved[i],0)
        if args.near_base:model.add(sum(moved.values())<=args.near_base)
    if args.peak_positions:
        for i,z in json.loads((ROOT/args.peak_positions).read_text()).items():
            model.add(y[int(i)]==z)
    tx=[];hy=[]
    for i in sorted(selected):
        s,e,l=pipes[i]
        model.add(y[i]+l<=ceiling)
        tx.append(model.new_fixed_size_interval_var(s,e-s,f't{i}'))
        hy.append(model.new_fixed_size_interval_var(y[i],l,f'h{i}'))
        if any(a<=base[i]<=b for a,b in domains[i]):model.add_hint(y[i],base[i])
    if not args.pairwise:model.add_no_overlap_2d(tx,hy)
    choices={}; oldbin={}
    for i in sorted(selected):
        s,e,l=pipes[i]
        if not s<=peak_t<e:continue
        oldbin[i]=next((k for k,(a,b) in enumerate(bins) if a<=base[i]<b),-1)
        opts=[]
        for k,(a,b) in enumerate(bins):
            if not any(max(a,d)<=min(b-l,u) for d,u in domains[i]):continue
            bv=model.new_bool_var(f'bin{i}_{k}')
            choices[i,k]=bv;opts.append(bv)
            model.add(y[i]>=a).only_enforce_if(bv)
            model.add(y[i]+l<=b).only_enforce_if(bv)
            model.add_hint(bv,int(k==oldbin[i]))
        model.add_exactly_one(opts)
    if args.assignment:
        assignment=json.loads((ROOT/args.assignment).read_text())
        for i,k in assignment.items():
            i=int(i)
            if (i,k) not in choices:raise ValueError(('assignment unavailable',i,k))
            model.add(choices[i,k]==1)
    if args.extra_moves:
        moves={int(i):int(k) for i,k in (part.split(':') for part in args.extra_moves.split(','))}
        for i,k in moves.items():model.add(choices[i,k]==1)
    else:moves={}
    if args.preserve_bins:
        for i,k in oldbin.items():
            if i not in moves:model.add(choices[i,k]==1)
    if args.load_objective:
        model.minimize(1000*sum(pipes[i][2]*v for (i,k),v in choices.items() if k==len(bins)-1)+ceiling)
    for k,(a,b) in enumerate(bins):
        fixed_load=sum(p[2] for i,p in enumerate(pipes) if i not in selected and p[0]<=peak_t<p[1] and a<=base[i]<b)
        capacity=ceiling-a-fixed_load if b==H else b-a-fixed_load
        model.add(sum(pipes[i][2]*v for (i,j),v in choices.items() if j==k)<=capacity)
        if args.optional_slots:
            intervals=[]
            for (i,j),v in choices.items():
                if j==k:
                    intervals.append(model.new_optional_fixed_size_interval_var(y[i],pipes[i][2],v,f'peak{i}_{k}'))
            for i,p in enumerate(pipes):
                if i not in selected and p[0]<=peak_t<p[1] and a<=base[i]<b:
                    intervals.append(model.new_fixed_size_interval_var(base[i],p[2],f'peakfixed{i}'))
            model.add_no_overlap(intervals)
        if args.tight and k!=5:
            model.add(sum(pipes[i][2]*v for (i,j),v in choices.items() if j==k)==capacity)
        if args.flow_small and (b-a<500):
            incoming={};outgoing={}
            for (i,j),v in choices.items():
                if j!=k:continue
                arcs=[]
                for lo,hi in domains[i]:
                    for z in range(max(a,lo),min(b-pipes[i][2],hi)+1):
                        arc=model.new_bool_var(f'arc{i}_{z}')
                        arcs.append(arc)
                        outgoing.setdefault(z,[]).append(arc)
                        incoming.setdefault(z+pipes[i][2],[]).append(arc)
                        model.add(y[i]==z).only_enforce_if(arc)
                model.add(sum(arcs)==v)
            for i,p in enumerate(pipes):
                if i not in selected and p[0]<=peak_t<p[1] and a<=base[i]<b:
                    outgoing.setdefault(base[i],[]).append(1)
                    incoming.setdefault(base[i]+p[2],[]).append(1)
            skips=[]
            for z in range(a,b):
                skip=model.new_bool_var(f'skip{z}');skips.append(skip)
                outgoing.setdefault(z,[]).append(skip);incoming.setdefault(z+1,[]).append(skip)
            model.add(sum(skips)<=1)
            for z in range(a,b+1):
                model.add(sum(outgoing.get(z,[]))-sum(incoming.get(z,[]))==(1 if z==a else -1 if z==b else 0))
        if args.cumulative:
            times=[];demands=[]
            for (i,j),v in choices.items():
                if j!=k:continue
                s,e,l=pipes[i]
                times.append(model.new_optional_fixed_size_interval_var(s,e-s,v,f'optional{i}_{k}'))
                demands.append(l)
            for i,p in enumerate(pipes):
                if i in selected:continue
                s,e,l=p
                overlap=min(b,base[i]+l)-max(a,base[i])
                if overlap>0:
                    times.append(model.new_fixed_size_interval_var(s,e-s,f'fixed{i}_{k}'))
                    demands.append(overlap)
            model.add_cumulative(times,demands,b-a)
    if args.move_limit:
        model.add(sum(1-choices[i,k] for i,k in oldbin.items() if (i,k) in choices)<=args.move_limit)
    if args.top_cumulative:
        tis=[];dem=[]
        for i in sorted(selected):
            s,e,l=pipes[i]
            if not any(b>=20361 for a,b in domains[i]):continue
            v=choices.get((i,len(bins)-1))
            if v is None:
                v=model.new_bool_var(f'top{i}')
                model.add(y[i]>=20361).only_enforce_if(v)
                model.add(y[i]<20361).only_enforce_if(v.Not())
                model.add_hint(v,int(base[i]>=20361))
            tis.append(model.new_optional_fixed_size_interval_var(s,e-s,v,f'topt{i}'));dem.append(l)
        for i,(s,e,l) in enumerate(pipes):
            if i not in selected and base[i]>=20361:
                tis.append(model.new_fixed_size_interval_var(s,e-s,f'topfixed{i}'));dem.append(l)
        model.add_cumulative(tis,dem,ceiling-20361)
    ids=sorted(selected)
    if args.ordered_chains:
        for k,(a,b) in enumerate(bins):
            ordered=sorted((i for i in oldbin if oldbin[i]==k),key=lambda i:base[i])
            last=a
            for i in ordered:
                nxt=model.new_int_var(a,b,f'chain{k}_{i}')
                if (i,k) in choices:
                    v=choices[i,k]
                    model.add(y[i]>=last).only_enforce_if(v)
                    model.add(nxt==y[i]+pipes[i][2]).only_enforce_if(v)
                    model.add(nxt==last).only_enforce_if(v.Not())
                else:model.add(nxt==last)
                model.add_hint(nxt,min(b,max(a,base[i]+pipes[i][2])))
                last=nxt
    edges=0
    for n,i in enumerate(ids):
        for j in ids[n+1:]:
            si,ei,li=pipes[i];sj,ej,lj=pipes[j]
            if si>=ej or sj>=ei:continue
            if max(b for a,b in domains[i])+li<=min(a for a,b in domains[j]):continue
            if max(b for a,b in domains[j])+lj<=min(a for a,b in domains[i]):continue
            if args.pairwise:
                before=model.new_bool_var(f'b{i}_{j}')
                model.add(y[i]+li<=y[j]).only_enforce_if(before)
                model.add(y[j]+lj<=y[i]).only_enforce_if(before.Not())
                model.add_hint(before,int(base[i]<base[j]))
                edges+=1
            if args.fixed_order:
                first,second=(i,j) if base[i]<base[j] else (j,i)
                model.add(y[first]+pipes[first][2]<=y[second])
            if args.order_nonpeak and i not in oldbin and j not in oldbin:
                first,second=(i,j) if base[i]<base[j] else (j,i)
                model.add(y[first]+pipes[first][2]<=y[second])
            if args.ordered and i in oldbin and j in oldbin and oldbin[i]==oldbin[j]:
                for k in range(len(bins)):
                    if (i,k) in choices and (j,k) in choices:
                        first,second=(i,j) if base[i]<base[j] else (j,i)
                        model.add(y[first]+pipes[first][2]<=y[second]).only_enforce_if([choices[i,k],choices[j,k]])
            if args.assume_order and i in oldbin and j in oldbin and oldbin[i]==oldbin[j]:
                k=oldbin[i]
                if (i,k) in choices and (j,k) in choices:
                    first,second=(i,j) if base[i]<base[j] else (j,i)
                    model.add(y[first]+pipes[first][2]<=y[second]).only_enforce_if([choices[i,k],choices[j,k]])
    print('built',len(y),'variables',len(choices),'bin options',edges,'edges',flush=True)
    solver=cp_model.CpSolver()
    solver.parameters.num_search_workers=args.workers
    solver.parameters.max_time_in_seconds=args.seconds
    solver.parameters.random_seed=args.seed
    solver.parameters.log_search_progress=args.log
    solver.parameters.max_presolve_iterations=args.presolve_iterations
    solver.parameters.use_lns_only=args.lns_only
    solver.parameters.lns_initial_difficulty=args.lns_difficulty
    if args.repair_hints:
        solver.parameters.repair_hint=True
        solver.parameters.hint_conflict_limit=100000
    class Progress(cp_model.CpSolverSolutionCallback):
        def on_solution_callback(self):
            out=base[:]
            for i in selected:out[i]=self.value(y[i])
            ok,h=validate(pipes,commands,out)
            assert ok and out[:4006]==prefix,(ok,h)
            print('INCUMBENT',h,'time',self.wall_time,flush=True)
            path=ROOT/args.output
            path.write_text(''.join(f'{v}\n' for v in out))
            if h<=20760:self.stop_search()
    # At an almost fully occupied slice these propagators detect overloads early.
    solver.parameters.use_timetabling_in_no_overlap_2d=True
    solver.parameters.use_energetic_reasoning_in_no_overlap_2d=True
    solver.parameters.use_area_energetic_reasoning_in_no_overlap_2d=True
    result=solver.solve(model,Progress())
    print(solver.status_name(result),'wall',solver.wall_time,'branches',solver.num_branches,'conflicts',solver.num_conflicts,flush=True)
    if result not in (cp_model.FEASIBLE,cp_model.OPTIMAL):return 2
    out=base[:]
    for i in selected:out[i]=solver.value(y[i])
    assert out[:4006]==prefix
    ok,h=validate(pipes,commands,out)
    assert ok,(ok,h)
    assert h<=H,h
    path=ROOT/args.output
    path.write_text(''.join(f'{v}\n' for v in out))
    changes=[(i,base[i],out[i]) for i in sorted(selected) if base[i]!=out[i]]
    print('VERIFIED',h,'rounded',(h+19)//20*20,'changed',len(changes),'output',path,flush=True)
    path.with_suffix('.json').write_text(json.dumps({'height':h,'changes':changes,'args':vars(args)},indent=2))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
