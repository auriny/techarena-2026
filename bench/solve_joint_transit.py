"""Joint 11+15 repair with collision cuts and coordinate-core expansion.

All 2487 suffix pipes of Test 11 have variables from the start. Prefix pipes
surviving in the critical window but later deleted in Test 11 are shared
variables in BOTH tests. Exact full-lifetime domains represent all remaining
fixed rectangles. Coordinate assumptions, rather than spatial windows, define
the initial neighborhood; an UNSAT core releases its actual blockers.

Exit 0: both complete plans verified at the requested ceilings. Exit 1: budget
or stagnation limit, search state saved; no claim about global feasibility.
"""
import argparse
import hashlib
import json
import multiprocessing as mp
from pathlib import Path
import time

from inspect_test11_exact import load_test, validate
from solve_test11_peak import domains_for
from solve_shared_prefix import intersect
from solve_test11_lazy import (atomic_json, bounded_solve, collisions,
                               order_cover, nearest)
from audit_joint_score import official_bounds, joint_total, rounded, target_reached

ROOT = Path(__file__).resolve().parents[1]


def fixed_gaps(pipes, positions, moving, height, at):
    intervals = sorted((positions[i], positions[i]+length)
                       for i,(st,en,length) in enumerate(pipes)
                       if i not in moving and st <= at < en)
    gaps, end = [], 0
    for lo, hi in intervals:
        if lo > end:
            gaps.append((end, min(height,lo)))
        end = max(end, hi)
    if end < height:
        gaps.append((end,height))
    return [(lo,hi)for lo,hi in gaps if lo < hi]


def expansion_candidates(pipes, base, domains, free, fixed, count):
    """Choose fixations to release; all changes only enlarge the feasible set.

    The whole set of fixed coordinates is a sufficient UNSAT assumption set
    after the hard search proves infeasibility. A minimal core is helpful,
    but is not required for a sound expansion. Prefer the constraint frontier
    of already mobile pipes, and never release a redundant singleton domain.
    """
    mobile=set(range(len(pipes)))-fixed
    neighbors={}
    for i,j in free:
        if i in fixed and j in mobile:
            neighbors.setdefault(i,set()).add(j)
        if j in fixed and i in mobile:
            neighbors.setdefault(j,set()).add(i)
    candidates=[i for i in fixed
                if any(lo<base[i] or hi>base[i]for lo,hi in domains[i])]
    def priority(i):
        st,en,length=pipes[i]
        adjacent=neighbors.get(i,set())
        length_delta=min((abs(length-pipes[j][2])for j in adjacent),default=1000000)
        return (bool(adjacent),int(st<=9818<en),len(adjacent),-length_delta,en,-i)
    return set(sorted(candidates,key=priority,reverse=True)[:count])


def result_action(mode, status, pending_infeasible):
    """A timed-out core must not send a proven-infeasible scope back to search."""
    if status=='INFEASIBLE':
        return 'extract_core' if mode!='core' else 'core_infeasible'
    if status in ('UNKNOWN','WATCHDOG') and mode=='core' and pending_infeasible:
        return 'expand_proved_scope'
    return 'ordinary'


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--seconds',type=float,default=3600)
    ap.add_argument('--workers',type=int,default=16)
    ap.add_argument('--slice',type=float,default=120)
    ap.add_argument('--max-slice',type=float,default=600)
    ap.add_argument('--unknown-limit',type=int,default=4)
    ap.add_argument('--max-disjunctions',type=int,default=60000)
    ap.add_argument('--base11',default='target/test11-freed4.txt')
    ap.add_argument('--base15',default='target/candidate-best15.txt')
    ap.add_argument('--state',default='target/joint-transit-state.json')
    ap.add_argument('--output',default='target/joint-transit')
    ap.add_argument('--stop-file',default='target/joint-transit.stop')
    ap.add_argument('--resume',action='store_true')
    ap.add_argument('--log',action='store_true')
    ap.add_argument('--seed',type=int,default=391)
    ap.add_argument('--release',type=int,default=0,
                    help='Release this many core coordinates; 0 releases the entire proven core.')
    ap.add_argument('--core-seconds',type=float,default=15)
    ap.add_argument('--expand-on-resume',type=int,default=0,
                    help='Safely release this many additional coordinate fixations when resuming.')
    args=ap.parse_args()
    if not (args.seconds>0 and args.workers>0 and args.slice>0 and
            args.max_slice>=args.slice and args.unknown_limit>0 and args.release>=0 and
            args.core_seconds>0 and args.expand_on_resume>=0):
        ap.error('Invalid time, worker, or stagnation limit')
    if args.expand_on_resume and not args.resume:
        ap.error('--expand-on-resume requires --resume')
    started=time.monotonic()
    stop=ROOT/args.stop_file
    if stop.exists():
        print('STOP FILE EXISTS',stop,flush=True)
        return 130
    p,c=load_test(ROOT/'bench/tests/11.in')
    q,d=load_test(ROOT/'bench/tests/15.in')
    a=list(map(int,(ROOT/args.base11).read_text().split()))
    b=list(map(int,(ROOT/args.base15).read_text().split()))
    if a[:4006]!=b[:4006] or a[0]!=0:
        raise ValueError('Shared prefix mismatch or change to pipe 0')
    for pipes,commands,plan in [(p,c,a),(q,d,b)]:
        if not validate(pipes,commands,plan)[0]:
            raise ValueError('Invalid complete input plan')
    shared={i for i in range(1,4006)if p[i][0]<9838 and 9810<p[i][1]<len(c)}
    ids=sorted(shared|set(range(4006,len(p))))
    index={pid:i for i,pid in enumerate(ids)}
    moving=set(ids)
    dom11=domains_for(p,a,moving,20760)
    dom15=domains_for(q,b,shared,418780)
    domains=[intersect(dom11[i],dom15[i])if i in shared else dom11[i]for i in ids]
    if any(not domain for domain in domains):
        print('EMPTY_DOMAIN under selected shared prefix',
              [ids[k]for k,v in enumerate(domains)if not v],flush=True)
        return 2
    pipes=[p[i]for i in ids]
    base=[a[i]for i in ids]
    hint=[nearest(domain,y)for domain,y in zip(domains,base)]
    free={tuple(sorted(pair))for pair in order_cover(pipes,base,len(c))}
    mobile={index[i]for i in shared}|{k for k,i in enumerate(ids)if a[i]+p[i][2]>20760}
    fixed=set(range(len(ids)))-mobile
    fingerprint=hashlib.sha256(json.dumps([p,q,a,b,ids]).encode()).hexdigest()
    state_path=ROOT/args.state
    round_no=0
    pending_infeasible=False
    if args.resume:
        saved=json.loads(state_path.read_text())
        if saved['fingerprint']!=fingerprint:
            raise ValueError('Checkpoint belongs to another pair of input plans')
        fixed=set(saved['fixed'])
        free={tuple(v)for v in saved['free']}
        hint=saved['hint']
        round_no=saved['round']
        pending_infeasible=bool(saved.get('pending_infeasible',False))
        if (len(hint)!=len(ids) or not fixed<=set(range(len(ids))) or
                any(type(y)is not int for y in hint)):
            raise ValueError('Invalid checkpoint coordinates')
        for i,j in free:
            if not (0<=i<j<len(ids) and max(pipes[i][0],pipes[j][0])<min(pipes[i][1],pipes[j][1])):
                raise ValueError('Invalid checkpoint collision constraint')
    if args.expand_on_resume:
        release=expansion_candidates(pipes,base,domains,free,fixed,args.expand_on_resume)
        fixed-=release
        pending_infeasible=False
        print('RESUME EXPANSION released',len(release),'pipe IDs',
              [ids[i]for i in sorted(release)],flush=True)
    data={'pipes':pipes,'domains':domains,'base':base,'anchor':True,
          'presolve':False,'clique_times':[9818],
          'heartbeat':True,
          'other_geometry':[(index[i],*q[i])for i in sorted(shared)],
          'capacity_rows':[]}
    for at in [9810,9818,9831,9837]:
        load=sum(length for st,en,length in p if st<=at<en)
        data['capacity_rows'].append({'time':at,'slack':20760-load,
            'bins':fixed_gaps(p,a,moving,20760,at)})
    def checkpoint(kind):
        atomic_json(state_path,dict(kind='SEARCH_STATE_NOT_A_VALIDATED_PLAN',
            fingerprint=fingerprint,fixed=sorted(fixed),free=sorted(free),
            hint=hint,round=round_no,reason=kind,shared_ids=sorted(shared),
            pending_infeasible=pending_infeasible))
    print('JOINT START shared',len(shared),'suffix',len(p)-4006,
          'all_variables',len(ids),'mobile',len(ids)-len(fixed),
          'cuts',len(free),'workers',args.workers,flush=True)
    print('Ceilings 20760 / 418780. UNKNOWN is not progress or infeasibility.',flush=True)
    mode='core'
    unknown=0
    slice_seconds=args.slice
    bounds=None
    while time.monotonic()-started<args.seconds-2 and not stop.exists():
        checkpoint('RUNNING')
        remaining=args.seconds-(time.monotonic()-started)-1
        seconds=min(remaining,args.core_seconds if mode=='core'else slice_seconds)
        state={'pinned':[],'free':sorted(free),'hint':hint,'fixed':sorted(fixed)}
        result=bounded_solve(data,state,mode,seconds,args.workers,
                             args.seed+round_no,args.log,stop)
        print('ROUND',round_no,mode,result['status'],'mobile',len(ids)-len(fixed),
              'cuts',len(free),'wall',result.get('wall'),'branches',result.get('branches'),
              'elapsed',round(time.monotonic()-started,1),flush=True)
        round_no+=1
        if result['status']in ('ERROR','MODEL_INVALID'):
            print(result,flush=True)
            checkpoint('ERROR')
            return 3
        if result['status']=='STOPPED':
            break
        action=result_action(mode,result['status'],pending_infeasible)
        if action=='expand_proved_scope':
            release=expansion_candidates(pipes,base,domains,free,fixed,args.release or 16)
            if not release:
                checkpoint('INFEASIBLE_FOR_SELECTED_SHARED_PREFIX')
                print('PROVED INFEASIBLE: no nontrivial suffix coordinate fixations remain. '
                      'The selected shared-prefix set must be expanded.',flush=True)
                return 2
            fixed-=release
            pending_infeasible=False
            unknown=0
            slice_seconds=args.slice
            mode='core'
            print('EXPAND AFTER PROVED INFEASIBLE; core timed out; released',len(release),
                  'pipe IDs',[ids[i]for i in sorted(release)],flush=True)
            checkpoint('EXPANDED_PROVED_INFEASIBLE_SCOPE')
            continue
        if 'positions'in result:
            pending_infeasible=False
            hint=result['positions']
            bad=collisions(pipes,hint)
            if not bad:
                aa,bb=a[:],b[:]
                for i,y in zip(ids,hint):
                    aa[i]=y
                    if i in shared:
                        bb[i]=y
                ok11,h11=validate(p,c,aa)
                ok15,h15=validate(q,d,bb)
                assert ok11 and ok15 and aa[:4006]==bb[:4006]
                bounds=official_bounds()if bounds is None else bounds
                assert target_reached(h11,h15,bounds)
                root=ROOT/args.output
                for label,plan in [('11',aa),('15',bb)]:
                    path=root.with_name(root.name+'-'+label+'.txt')
                    temporary=path.with_suffix('.tmp')
                    temporary.write_text(''.join(f'{v}\n'for v in plan))
                    temporary.replace(path)
                atomic_json(root.with_suffix('.success.json'),dict(valid=True,
                    height11=h11,height15=h15,
                    total_rounded8=str(rounded(joint_total(h11,h15,bounds),8)),
                    shared_changes=sum(aa[i]!=a[i]for i in shared)))
                print('VERIFIED JOINT TARGET',h11,h15,'30.75747405',flush=True)
                checkpoint('VERIFIED_TARGET')
                return 0
            if mode=='core':
                mode='search'
                unknown=0
                continue
            additions=set(bad)-free
            if not additions:
                raise RuntimeError('Violation of an already modeled collision constraint')
            free.update(additions)
            print('COLLISION CUTS added',len(additions),'remaining collisions',len(bad),flush=True)
            if len(free)>args.max_disjunctions:
                checkpoint('DISJUNCTION_LIMIT')
                print('DISJUNCTION LIMIT; saved for review, no target found.',flush=True)
                return 1
            unknown=0
            slice_seconds=args.slice
            mode='search'
        elif result['status']=='INFEASIBLE':
            if action=='extract_core':
                pending_infeasible=True
                unknown=0
                checkpoint('PROVED_INFEASIBLE_NEEDS_EXPANSION')
                mode='core'
                continue
            core=result['core']
            release={int(v[1])for v in core if v[0]=='coordinate'}
            if not release:
                checkpoint('INFEASIBLE_FOR_SELECTED_SHARED_PREFIX')
                print('INFEASIBLE for these shared-prefix choices; not a global impossibility proof.',flush=True)
                return 2
            if not release<=fixed:
                raise RuntimeError('Invalid coordinate core')
            if args.release:
                # This weakens the assumptions in the sufficient core; it does
                # not assert that the expanded neighborhood is feasible.
                def priority(k):
                    st,en,length=pipes[k]
                    return (int(base[k]>=20300),int(st<=9818<en),en,base[k])
                release=set(sorted(release,key=priority,reverse=True)[:args.release])
            fixed-=release
            pending_infeasible=False
            print('RELEASE BLOCKERS',len(release),'pipe IDs',[ids[i]for i in sorted(release)][:70],flush=True)
            unknown=0
            slice_seconds=args.slice
            mode='core'
        else:
            unknown+=1
            mode='search'
            slice_seconds=min(args.max_slice,slice_seconds*2)
            if unknown>=args.unknown_limit:
                checkpoint('STAGNATION')
                print('STAGNATION LIMIT: state saved. Do not blindly repeat short restarts.',flush=True)
                return 1
    checkpoint('STOPPED'if stop.exists()else'BUDGET_END')
    print('STOPPED'if stop.exists()else'BUDGET END','no verified joint target',flush=True)
    return 130 if stop.exists()else 1


if __name__=='__main__':
    mp.freeze_support()
    raise SystemExit(main())
