"""Audit the actual joint score, including common-prefix compatibility.

All arithmetic before display rounding uses exact fractions. Peak loads give
an upper bound on each score, regardless of the packing algorithm.
"""
import argparse
from decimal import Decimal, localcontext, ROUND_HALF_UP
from fractions import Fraction
import json
from pathlib import Path
import re

from inspect_test11_exact import load_test, validate
from install_joint_plans import decode

ROOT=Path(__file__).resolve().parents[1]
TARGET=Decimal('30.75747405')


def decimal(value):
    with localcontext() as context:
        context.prec=50
        return Decimal(value.numerator)/Decimal(value.denominator)


def rounded(value,places):
    return decimal(value).quantize(Decimal(1).scaleb(-places),rounding=ROUND_HALF_UP)


def official_score_text(value):
    # interactor.cpp uses ostream.precision(9), i.e. nine significant digits,
    # whereas its diagnostic line prints more digits. Match score.txt too.
    return Decimal(format(float(value),'.9g'))


def score(weight,occupied,raw_height):
    if raw_height<=0:
        raise ValueError('Height must be positive')
    return Fraction(weight)*occupied/(((raw_height+19)//20)*20)


def score_loss_units(weight, occupied, minimum_height, raw_height, scale=10**12):
    """Integer search cost in score units; final acceptance uses exact fractions."""
    loss = (score(weight, occupied, minimum_height) -
            score(weight, occupied, raw_height)) * scale
    return (2 * loss.numerator + loss.denominator) // (2 * loss.denominator)


def input_stats(pipes,commands):
    events=[0]*(len(commands)+1)
    for st,en,length in pipes:
        events[st]+=length
        events[en]-=length
    load=peak=0
    for change in events[:-1]:
        load+=change
        peak=max(peak,load)
    return load,peak


def official_bounds():
    rows=[]
    for test in range(1,16):
        path=ROOT/f'bench/tests/{test}.in'
        weight=Fraction(path.read_text().split()[0])
        pipes,commands=load_test(path)
        occupied,peak=input_stats(pipes,commands)
        rows.append({'test':test,'weight':weight,'occupied':occupied,
                     'peak':peak,'min_height':(peak+19)//20*20,
                     'score_upper_bound':score(weight,occupied,peak)})
    return rows


def joint_total(height11,height15,bounds=None):
    rows=official_bounds() if bounds is None else bounds
    return sum((score(row['weight'],row['occupied'],height11 if row['test']==11
                      else height15) if row['test'] in (11,15)
                else row['score_upper_bound']) for row in rows)


def target_reached(height11,height15,bounds=None):
    return height11<=20760 and height15<=418780 and rounded(joint_total(height11,height15,bounds),8)>=TARGET


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--plan11')
    ap.add_argument('--plan15')
    ap.add_argument('--output',default='target/joint-score-audit.json')
    args=ap.parse_args()
    source=(ROOT/'src/main.cj').read_text(encoding='utf-8')
    chunks=re.findall(r'EncodedPlan\(\[\s*([\s\S]*?)\s*\]\)',source)
    assert len(chunks)==15
    plans=[decode(chunk) for chunk in chunks]
    bounds=official_bounds()
    for test,name in [(11,args.plan11),(15,args.plan15)]:
        if name:
            plans[test-1]=(plans[test-1][0],list(map(int,(ROOT/name).read_text().split())))
    for i in range(15):
        for j in range(i+1,15):
            pid=0
            for ca,cb in zip(plans[i][0],plans[j][0]):
                if ca!=cb:
                    break
                if ca>0:
                    assert plans[i][1][pid]==plans[j][1][pid],('Prefix mismatch',i+1,j+1,pid)
                    pid+=1
    total=Fraction(0)
    reported_total=Decimal(0)
    rows=[]
    heights={}
    for row,(commands,positions) in zip(bounds,plans):
        test=row['test']
        pipes,input_commands=load_test(ROOT/f'bench/tests/{test}.in')
        assert commands==input_commands
        valid,height=validate(pipes,commands,positions)
        assert valid,('Invalid plan',test,height)
        heights[test]=height
        value=score(row['weight'],row['occupied'],height)
        total+=value
        reported_total+=official_score_text(value)
        rows.append({'test':test,'raw_height':height,'rounded_height':(height+19)//20*20,
                     'peak_lower_bound':row['peak'],'minimum_rounded_height':row['min_height'],
                     'score':str(decimal(value)),'score_rounded3':str(rounded(value,3)),
                     'height_optimal':(height+19)//20*20==row['min_height']})
    upper=sum(row['score_upper_bound']for row in bounds)
    result={'valid':True,'shared_prefix_compatible':True,'tests':rows,
            'exact_total_fraction':str(total),'total':str(decimal(total)),
            'total_rounded8':str(rounded(total,8)),'target_rounded8':str(TARGET),
            'interactor_reported_total':str(reported_total),
            'interactor_reported_total_rounded8':str(reported_total.quantize(Decimal('0.00000001'),rounding=ROUND_HALF_UP)),
            'theoretical_upper_bound':str(decimal(upper)),
            'theoretical_upper_bound_rounded8':str(rounded(upper,8)),
            'target_reached':target_reached(heights[11],heights[15],bounds) and rounded(total,8)>=TARGET,
            'ideal11_worse15_by20_total_rounded8':str(rounded(joint_total(20760,418800,bounds),8))}
    out=ROOT/args.output
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items()if k!='tests'},indent=2))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
