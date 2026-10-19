import subprocess, re, sys, time
from pathlib import Path

root = Path('.')
prefixfile = 'target/plan-11-0.txt'
exe = root / 'target/reversepack.exe'

best_h = 10**9
best_info = None

# Modes 10 to 60 (bucket = mode - 9 -> bucket from 1 to 51)
# And mode 1 with different tie-breakers if possible
print("Starting search across modes and seeds...")
t0 = time.time()
tested = 0

modes_to_test = [35, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 45, 50]

for mode in modes_to_test:
    for seed in range(1, 15):
        tested += 1
        cmd = [str(exe), '15', str(mode), str(seed), prefixfile, '4006']
        try:
            res = subprocess.check_output(cmd, text=True, timeout=2.0)
            m = re.search(r'HEIGHT (\d+)', res)
            if m:
                h = int(m.group(1))
                if h < best_h:
                    best_h = h
                    best_info = (mode, seed)
                    print(f"NEW BEST: height={best_h}, rounded={(best_h+19)//20*20} (mode={mode}, seed={seed}) in {time.time()-t0:.1f}s", flush=True)
                if best_h <= 418780:
                    print("GOAL ACHIEVED! best_h <= 418780!", flush=True)
                    sys.exit(0)
        except Exception as e:
            pass

print(f"Tested {tested} configurations in {time.time()-t0:.1f}s. Best height={best_h} with {best_info}")
