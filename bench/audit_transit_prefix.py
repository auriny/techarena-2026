"""Measure actual transit-prefix mobility in the full fixed Test 15 plan."""
import argparse
import json
from pathlib import Path

from inspect_test11_exact import load_test, validate
from solve_test11_peak import domains_for

ROOT=Path(__file__).resolve().parents[1]


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--base11',default='target/test11-freed4.txt')
    ap.add_argument('--base15',default='target/candidate-best15.txt')
    ap.add_argument('--output',default='target/transit-prefix-audit.json')
    args=ap.parse_args()
    p,c=load_test(ROOT/'bench/tests/11.in')
    q,d=load_test(ROOT/'bench/tests/15.in')
    a=list(map(int,(ROOT/args.base11).read_text().split()))
    b=list(map(int,(ROOT/args.base15).read_text().split()))
    assert a[:4006]==b[:4006]
    assert validate(p,c,a)[0]and validate(q,d,b)[0]
    live=[i for i in range(4006)if p[i][0]<9838 and p[i][1]>9810]
    transit=[i for i in live if p[i][1]<len(c)]
    group=domains_for(q,b,set(transit),418780)
    rows=[]
    for i in transit:
        domain=domains_for(q,b,{i},418780)[i]
        component=next((lo,hi)for lo,hi in domain if lo<=b[i]<=hi)
        rows.append(dict(i=i,y=b[i],length=p[i][2],life11=p[i][:2],life15=q[i][:2],
            continuous_corridor15=component,individual_full_domain15=domain,
            all_transit_free_domain15=group[i]))
    result=dict(prefix_peak9818=sum(p[i][0]<=9818<p[i][1]for i in range(4006)),
        prefix_window9810_9838=len(live),permanent11=sum(p[i][1]==len(c)for i in live),
        transit11=len(transit),individually_continuously_movable15=sum(
            row['continuous_corridor15'][0]<row['continuous_corridor15'][1]for row in rows),rows=rows)
    path=ROOT/args.output
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,indent=2))
    print(json.dumps({key:value for key,value in result.items()if key!='rows'},indent=2))
    print('Full exact domains:',path)


if __name__=='__main__':
    main()
