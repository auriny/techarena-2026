from pathlib import Path
from collections import Counter


def load_test(path):
    tok = Path(path).read_text().split()
    n = int(tok[1])
    commands = list(map(int, tok[2:2 + n]))
    pipes = []
    alive = set()
    for t, c in enumerate(commands):
        if c > 0:
            pipes.append([t, n, c])
            alive.add(len(pipes) - 1)
        elif c < 0:
            idx = -c - 1
            assert idx in alive, (t, c)
            pipes[idx][1] = t
            alive.remove(idx)
        else:
            raise ValueError((t, c))
    return pipes, commands


def validate(pipes, commands, y):
    assert len(y) == len(pipes), (len(y), len(pipes))
    assert min(y) >= 0
    height = max(p[2] + z for p, z in zip(pipes, y))
    cells = bytearray(height)
    idx = 0
    for t, c in enumerate(commands):
        if c > 0:
            z = y[idx]
            if any(cells[z:z+c]):
                return False, (t, idx, z, c)
            cells[z:z+c] = b'\1' * c
            idx += 1
        else:
            j = -c-1
            z, length = y[j], pipes[j][2]
            assert all(cells[z:z+length])
            cells[z:z+length] = b'\0' * length
    return True, height


if __name__ == '__main__':
    p, commands = load_test('bench/tests/11.in')
    y = list(map(int, Path('target/candidate-best11.txt').read_text().split()))
    y15 = list(map(int, Path('target/candidate-best15.txt').read_text().split()))
    print('pipes', len(p), 'commands', len(commands), 'prefix diffs', sum(a != b for a,b in zip(y[:4006],y15[:4006])), flush=True)
    print('base valid', validate(p,commands,y), flush=True)
    exact = Path('target/exact-11-20760.txt')
    if exact.exists():
        z = list(map(int,exact.read_text().split()))
        print('old exact', validate(p,commands,z), 'prefix diffs',sum(a != b for a,b in zip(z[:4006],y15[:4006])),flush=True)
    events = [0] * (len(commands)+1)
    for s,e,l in p:
        events[s] += l
        events[e] -= l
    loads=[]
    current=0
    for d in events[:-1]:
        current += d
        loads.append(current)
    peak=max(loads); t=loads.index(peak)
    print('peak',peak,'at',t,'violators', [(i,p[i],y[i]) for i in range(len(p)) if y[i]+p[i][2]>20760],flush=True)
    fixed=sorted((y[i],y[i]+p[i][2]) for i in range(4006) if p[i][0]<=t<p[i][1])
    gaps=[]; end=0
    for a,b in fixed:
        if a>end: gaps.append((end,a))
        end=max(end,b)
    if end<20760:gaps.append((end,20760))
    for k,(a,b) in enumerate(gaps):
        ids=[i for i in range(4006,len(p)) if p[i][0]<=t<p[i][1] and a<=y[i]<b]
        print(k,(a,b),'load',sum(p[i][2] for i in ids),'slack',b-a-sum(p[i][2] for i in ids),flush=True)
        if b-a<500:
            print([(i, p[i],y[i]) for i in ids],flush=True)
