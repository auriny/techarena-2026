import glob, math
from pathlib import Path

tests_dir = Path('bench/tests')
total_max_score = 0.0

for test_num in range(1, 16):
    file_path = tests_dir / f'{test_num}.in'
    with open(file_path) as f:
        tokens = f.read().split()
    
    weight = float(tokens[0])
    n = int(tokens[1])
    ops = [int(x) for x in tokens[2:2+n]]
    
    pipe_lens = {}
    next_pid = 1
    current_alive_sum = 0
    max_alive_sum = 0
    
    for op in ops:
        if op > 0:
            pipe_lens[next_pid] = op
            current_alive_sum += op
            next_pid += 1
            if current_alive_sum > max_alive_sum:
                max_alive_sum = current_alive_sum
        elif op < 0:
            pid = -op
            current_alive_sum -= pipe_lens[pid]
            del pipe_lens[pid]
            
    final_occupied = current_alive_sum
    min_trench = math.ceil(max_alive_sum / 20) * 20
    if final_occupied > min_trench:
        min_trench = math.ceil(final_occupied / 20) * 20
    
    theoretical_max_kpi = final_occupied / min_trench
    theoretical_max_score = theoretical_max_kpi * weight
    total_max_score += theoretical_max_score
    print(f"Test {test_num:2d}: weight={weight:4.1f}, final_occ={final_occupied:7d}, max_alive={max_alive_sum:7d}, min_trench={min_trench:7d}, max_kpi={theoretical_max_kpi:.6f}, max_score={theoretical_max_score:.9f}")

print(f"\nTotal Absolute Theoretical Max Score: {total_max_score:.9f} / 32.1")
