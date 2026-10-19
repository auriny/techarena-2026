"""Grow a Test 11 repair by extracting the fixed pipes blocking it.

The only permanent boundary is the immutable 4006-pipe prefix.  All other
fixed coordinates are assumptions, so an UNSAT core can expand the repair.
"""
import argparse
import json
import sys
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'target/solver11-deps'))
from ortools.sat.python import cp_model
from inspect_test11_exact import load_test,validate
from solve_test11_peak import domains_for


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--base',default='target/test11-resume.txt')
    ap.add_argument('--output',default='target/test11-adaptive.txt')
    ap.add_argument('--height',type=int,default=20760)
    ap.add_argument('--seconds',type=float,default=30)
    ap.add_argument('--iterations',type=int,default=100)
    ap.add_argument('--initial',default='')
    ap.add_argument('--release',type=int,default=0)
    ap.add_argument('--log',action='store_true')
    ap.add_argument('--eject',action='store_true')
    ap.add_argument('--workers',type=int,default=8)
    ap.add_argument('--initial-file')
    ap.add_argument('--seed',type=int,default=1)
    ap.add_argument('--eject-cost',choices=['count','length','area','late'],default='count')
    ap.add_argument('--resume-partial')
    ap.add_argument('--peak-relaxation',action='store_true')
    ap.add_argument('--geometry-relaxation',action='store_true')
    ap.add_argument('--close-lengths',action='store_true')
    ap.add_argument('--close-ejected-lengths',type=int,default=-1)
    ap.add_argument('--augment-neighbors',type=int,default=0)
    ap.add_argument('--trace-cost',action='store_true')
    ap.add_argument('--auto-close-length',action='store_true')
    ap.add_argument('--auto-neighbors',type=int,default=0)
    ap.add_argument('--max-presolve',type=float,default=2)
    ap.add_argument('--anchor-cost',action='store_true')
    ap.add_argument('--capacity-relaxation',action='store_true')
    ap.add_argument('--relax-hint')
    args=ap.parse_args()
    pipes,commands=load_test(ROOT/'bench/tests/11.in')
    base=list(map(int,(ROOT/args.base).read_text().split()))
    prefix=list(map(int,(ROOT/'target/candidate-best15.txt').read_text().split()))[:4006]
    assert base[:4006]==prefix and validate(pipes,commands,base)[0]
    n=len(pipes);H=args.height;started=time.time()
    # No suffix coordinate is baked into these domains.
    dom=domains_for(pipes,base,set(range(4006,n)),H)
    selected={i for i in range(4006,n) if base[i]+pipes[i][2]>H}
    selected.update(map(int,filter(None,args.initial.split(','))))
    if args.initial_file:selected.update(json.loads((ROOT/args.initial_file).read_text()))
    selected.update(i for i in range(4006,n) if base[i]>=20361)
    hits={}
    hint=base[:]
    if args.resume_partial:
        resume=json.loads((ROOT/args.resume_partial).read_text())
        selected.update(resume['selected']);selected.update(resume['ejected'])
        hint=resume['positions']
    if args.relax_hint:
        for i,v in json.loads((ROOT/args.relax_hint).read_text()).items():hint[int(i)]=v
    if args.close_lengths:
        lengths={pipes[i][2] for i in selected if pipes[i][0]<=9818<pipes[i][1]}
        selected.update(i for i in range(4006,n) if pipes[i][0]<=9818<pipes[i][1] and pipes[i][2] in lengths)
    if args.close_ejected_lengths>=0 and args.resume_partial:
        lengths={pipes[i][2] for i in resume['ejected']}
        selected.update(i for i in range(4006,n) if pipes[i][0]<=9818<pipes[i][1] and any(abs(pipes[i][2]-l)<=args.close_ejected_lengths for l in lengths))
    if args.augment_neighbors and args.resume_partial:
        seeds=list(resume['ejected'])
        for i in seeds:
            neighbors=[j for j in range(4006,n) if j not in selected and pipes[i][0]<pipes[j][1] and pipes[j][0]<pipes[i][1]]
            neighbors.sort(key=lambda j:min(abs(base[j]-base[i]),abs(base[j]+pipes[j][2]-base[i]),abs(base[j]-base[i]-pipes[i][2])))
            selected.update(neighbors[:args.augment_neighbors])
    for iteration in range(args.iterations):
        model=cp_model.CpModel()
        ys={i:model.new_int_var_from_domain(cp_model.Domain.from_intervals(dom[i]),f'y{i}') for i in sorted(selected)}
        tx=[];hy=[]
        for i in sorted(selected):
            s,e,l=pipes[i]
            tx.append(model.new_fixed_size_interval_var(s,e-s,f't{i}'))
            hy.append(model.new_fixed_size_interval_var(ys[i],l,f'h{i}'))
            if any(a<=hint[i]<=b for a,b in dom[i]):model.add_hint(ys[i],hint[i])
        model.add_no_overlap_2d(tx,hy)
        pins={};constraints=0
        for j in range(4006,n):
            if j in selected:continue
            sj,ej,lj=pipes[j];a=base[j];b=a+lj
            pin=None
            for i in sorted(selected):
                si,ei,li=pipes[i]
                if si>=ej or sj>=ei:continue
                lo=max(0,a-li+1);hi=min(H-li,b-1)
                if lo>hi or not any(max(lo,d)<=min(hi,u) for d,u in dom[i]):continue
                if pin is None:
                    pin=model.new_bool_var(f'fixed{j}');pins[j]=pin
                allowed=[]
                if lo>0:allowed.append((0,lo-1))
                if hi<H-li:allowed.append((hi+1,H-li))
                model.add_linear_expression_in_domain(ys[i],cp_model.Domain.from_intervals(allowed)).only_enforce_if(pin)
                constraints+=1
        if args.eject:
            if args.capacity_relaxation:
                peak=9818;bins=[];last=0
                for a,b in sorted((base[i],base[i]+pipes[i][2]) for i in range(4006) if pipes[i][0]<=peak<pipes[i][1]):
                    if a>last:bins.append((last,a))
                    last=max(last,b)
                bins.append((last,H));bin_vars={}
                for i in range(4006,n):
                    s,e,l=pipes[i]
                    if not s<=peak<e:continue
                    old=next(k for k,(a,b) in enumerate(bins) if a<=base[i]<b or k==len(bins)-1)
                    choices=[]
                    for k,(a,b) in enumerate(bins):
                        if not any(max(a,lo)<=min(b-l,hi) for lo,hi in dom[i]):continue
                        v=model.new_bool_var(f'cap{i}_{k}');bin_vars[i,k]=v;choices.append(v)
                        model.add_hint(v,int(a<=hint[i]<b))
                        if i in selected:
                            model.add(ys[i]>=a).only_enforce_if(v);model.add(ys[i]+l<=b).only_enforce_if(v)
                        elif i in pins and k!=old:model.add(v==0).only_enforce_if(pins[i])
                        elif i not in pins and k!=old:model.add(v==0)
                    model.add_exactly_one(choices)
                for k,(a,b) in enumerate(bins):
                    model.add(sum(pipes[i][2]*v for (i,j),v in bin_vars.items() if j==k)<=b-a)
            if args.peak_relaxation or args.geometry_relaxation:
                peak_intervals=[]
                relax_times=[];relax_heights=[]
                for i in sorted(selected):
                    if pipes[i][0]<=9818<pipes[i][1]:
                        peak_intervals.append(model.new_fixed_size_interval_var(ys[i],pipes[i][2],f'peakfree{i}'))
                    if args.geometry_relaxation:
                        s,e,l=pipes[i]
                        relax_times.append(model.new_fixed_size_interval_var(s,e-s,f'fulltime{i}'))
                        relax_heights.append(model.new_fixed_size_interval_var(ys[i],l,f'fullheight{i}'))
                for j in range(4006,n):
                    if j in selected:continue
                    if not args.geometry_relaxation and not pipes[j][0]<=9818<pipes[j][1]:continue
                    l=pipes[j][2]
                    z=model.new_int_var_from_domain(cp_model.Domain.from_intervals(dom[j]),f'peakrelax{j}')
                    if j in pins:model.add(z==base[j]).only_enforce_if(pins[j])
                    else:model.add(z==base[j])
                    model.add_hint(z,base[j])
                    if pipes[j][0]<=9818<pipes[j][1]:peak_intervals.append(model.new_fixed_size_interval_var(z,l,f'peakrelaxed{j}'))
                    if args.geometry_relaxation:
                        s,e,l=pipes[j]
                        relax_times.append(model.new_fixed_size_interval_var(s,e-s,f'fulltime{j}'))
                        relax_heights.append(model.new_fixed_size_interval_var(z,l,f'fullheight{j}'))
                model.add_no_overlap(peak_intervals)
                if args.geometry_relaxation:model.add_no_overlap_2d(relax_times,relax_heights)
            def weight(j):
                s,e,l=pipes[j]
                if args.anchor_cost:
                    candidates=0
                    for lo,hi in dom[j]:candidates+=hi-lo+1
                    return max(1,100000//max(1,candidates))
                if args.eject_cost=='length':return l
                if args.eject_cost=='area':return l*(e-s)
                if args.eject_cost=='late':return 1+(e-s)//100
                return 1
            model.minimize(sum(weight(j)*(1-v) for j,v in pins.items()))
            for j,v in pins.items():model.add_hint(v,1)
        else:model.add_assumptions(list(pins.values()))
        if args.trace_cost:
            ordered=sorted(i for i in selected if pipes[i][0]<=9818<pipes[i][1])
            for n,i in enumerate(ordered):
                for j in ordered[n+1:]:
                    if (base[i]<20361)==(base[j]<20361):
                        first,second=(i,j) if base[i]<base[j] else (j,i)
                        model.add(ys[first]+pipes[first][2]<=ys[second])
        reverse={v.index:j for j,v in pins.items()}
        solver=cp_model.CpSolver()
        solver.parameters.num_search_workers=args.workers if args.eject else 1
        solver.parameters.max_time_in_seconds=args.seconds
        solver.parameters.log_search_progress=args.log
        solver.parameters.random_seed=args.seed
        solver.parameters.use_timetabling_in_no_overlap_2d=True
        solver.parameters.use_energetic_reasoning_in_no_overlap_2d=True
        solver.parameters.core_minimization_level=2
        solver.parameters.find_multiple_cores=True
        solver.parameters.max_presolve_iterations=int(args.max_presolve)
        print('BUILD',iteration,'movable',len(selected),'fixed assumptions',len(pins),'constraints',constraints,'elapsed',round(time.time()-started,1),flush=True)
        result=solver.solve(model)
        print('RESULT',solver.status_name(result),'wall',solver.wall_time,'elapsed',round(time.time()-started,1),flush=True)
        if result in (cp_model.FEASIBLE,cp_model.OPTIMAL):
            if args.eject:
                release=[j for j,v in pins.items() if not solver.value(v)]
                for i in selected:hint[i]=solver.value(ys[i])
                (ROOT/args.output).with_suffix('.partial.json').write_text(json.dumps({'height':H,'selected':sorted(selected),'ejected':release,'positions':hint}))
                if release:
                    print('EJECT',len(release),release,'objective bound',solver.best_objective_bound,flush=True)
                    selected.update(release)
                    if args.auto_close_length:
                        lengths={pipes[i][2] for i in release}
                        selected.update(i for i in range(4006,n) if pipes[i][0]<=9818<pipes[i][1] and pipes[i][2] in lengths)
                    if args.auto_neighbors:
                        for i in release:
                            neighbors=[j for j in range(4006,n) if j not in selected and pipes[i][0]<pipes[j][1] and pipes[j][0]<pipes[i][1]]
                            neighbors.sort(key=lambda j:min(abs(base[j]-base[i]),abs(base[j]+pipes[j][2]-base[i]),abs(base[j]-base[i]-pipes[i][2])))
                            selected.update(neighbors[:args.auto_neighbors])
                    (ROOT/'target/test11-adaptive-selection.json').write_text(json.dumps(sorted(selected)))
                    (ROOT/args.output).with_suffix('.selection.json').write_text(json.dumps(sorted(selected)))
                    continue
            out=base[:]
            for i in selected:out[i]=solver.value(ys[i])
            ok,h=validate(pipes,commands,out)
            assert ok and h<=H and out[:4006]==prefix,(ok,h)
            path=ROOT/args.output
            path.write_text(''.join(f'{v}\n' for v in out))
            print('VERIFIED',h,'changed',sum(a!=b for a,b in zip(out,base)),'output',path,flush=True)
            return 0
        if result==cp_model.INFEASIBLE:
            core=[reverse[v] for v in solver.sufficient_assumptions_for_infeasibility()]
            if not core:
                print('INFEASIBLE even with the entire remaining suffix removed',flush=True)
                return 2
            for j in core:hits[j]=hits.get(j,0)+1
            core.sort(key=lambda j:(-hits[j],-int(pipes[j][0]<=9818<pipes[j][1]),-pipes[j][1],pipes[j][0]))
            release=core[:args.release] if args.release else core
            print('CORE',len(core),'RELEASE',release,flush=True)
            selected.update(release)
            (ROOT/'target/test11-adaptive-selection.json').write_text(json.dumps(sorted(selected)))
        else:
            # UNKNOWN is not an infeasibility proof. Increase the solve budget.
            args.seconds=min(600,args.seconds*1.5)
    return 3


if __name__=='__main__':raise SystemExit(main())
