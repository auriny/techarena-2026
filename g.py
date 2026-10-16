import os, glob, math

WEIGHTS = {1:0.1, 2:1, 3:1, 4:1, 5:1, 6:1, 7:1, 8:1, 9:1, 10:1, 11:1, 12:4, 13:4, 14:4, 15:10}

for i in range(1, 16):
    path = f"bench/tests/{i}.in"
    if not os.path.exists(path):
        path = f"tests/{i}.in"
    with open(path) as f:
        tokens = f.read().split()
        weight, count = float(tokens[0]), int(tokens[1])
        cmds = [int(x) for x in tokens[2:2 + count]]
        assert len(tokens) == count + 3 and tokens[-1] == '0'

    active = {}
    pid = 0
    cur_sum = 0
    peak_sum = 0
    adds, dels = 0, 0
    del_runs = []
    cur_del_run = 0
    lengths = []

    for c in cmds:
        if float(c) > 0:
            if cur_del_run > 0:
                del_runs.append(cur_del_run)
                cur_del_run = 0
            pid += 1
            active[pid] = c
            cur_sum += c
            if cur_sum > peak_sum:
                peak_sum = cur_sum
            adds += 1
            lengths.append(c)
        else:
            cur_del_run += 1
            dels += 1
            cur_sum -= active.pop(-c)
    if cur_del_run > 0:
        del_runs.append(cur_del_run)

    min_trench = max(20, math.ceil(peak_sum / 20) * 20)
    max_kpi = cur_sum / min_trench
    max_score = max_kpi * weight
    uniq_l = sorted(list(set(lengths)))
    print(f"Test #{i:2d} (w={WEIGHTS[i]:4.1f}): cmds={len(cmds):6d} (+{adds}/-{dels}) | "
          f"L=[{min(lengths)}..{max(lengths)}] (uniq={len(uniq_l)}) | "
          f"peak_sum={peak_sum} -> min_trench={min_trench} | end_sum={cur_sum} | "
          f"MAX_KPI={max_kpi:.5f} (MAX_SCORE={max_score:.4f}) | "
          f"del_batches={len(del_runs)} (avg_batch={sum(del_runs)/max(1,len(del_runs)):.1f}) | "
          f"first15={cmds[:15]}")
