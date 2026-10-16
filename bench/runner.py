#!/usr/bin/env python3
import argparse
import os
import subprocess

with_proc_stats = True

try:
    import psutil
except:
    try:
        from pip._internal import main as pip
        print("Try to install psutil to measure CPU time and memory usage")
        pip(['install',  'psutil'])
        import psutil
    except Exception as e:
        print("Failed to install psutil. Only result will shown")
        with_proc_stats = False

class ProcInfo:
    def __init__(self, score, cpu_time, memory):
        self.score = score
        self.cpu_time = cpu_time
        self.memory = memory


def single_test_run(solution, interactor, test_file):
    score_file = 'score.txt'
    output_file = 'answer.txt'
    with open(output_file, 'w'):
        pass

    try:
        interactor_opts = [interactor, test_file, score_file, output_file, 'report.txt']
        with subprocess.Popen(interactor_opts, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL) as interactor_proc:
            with subprocess.Popen(solution, stdin=interactor_proc.stdout, stdout=interactor_proc.stdin):
                pass
    except:
        print("Can't run {}. Check solution path and options are valid.".format(solution))
        exit(-1)

    try:
        with open(score_file, 'r') as score_file:
            return ProcInfo(float(score_file.readline()), None, None)
    except:
        print("score.txt doesn't exists or empty.")
        return ProcInfo(0.0, None, None)


def stats_test_run(solution, interactor, test_file):
    score_file = 'score.txt'
    output_file = 'answer.txt'
    with open(output_file, 'w'):
        pass

    time = 0.0
    memory = 0.0
    try:
        interactor_opts = [interactor, test_file, score_file, output_file, 'report.txt']
        with psutil.Popen(interactor_opts, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as interactor_proc:
            with psutil.Popen(solution, stdin=interactor_proc.stdout, stdout=interactor_proc.stdin) as solution_proc:
                while True:
                    gone, alive = psutil.wait_procs([solution_proc], timeout=0.01)
                    if not alive:
                        break
                    try:
                        time = max(alive[0].cpu_times().user, time)
                        memory = max(alive[0].memory_info().rss / (2 ** 20), memory)
                    except psutil.NoSuchProcess:
                        break
    except Exception:
        print("Can't run {}. Check solution path and options are valid.".format(solution))
        exit(-1)

    try:
        with open(score_file, 'r') as score_file:
            return ProcInfo(float(score_file.readline()), time, memory)
    except:
        print("score.txt doesn't exists or empty.")
        return ProcInfo(0.0, time, memory)


def main(args):
    def check_path(action, path, error_message):
        if not action(path):
            print(error_message)
            exit(-1)

    check_path(os.path.exists, args.interactor, 'Incorrect interactor path')

    if args.test_folder is not None:
        check_path(os.path.isdir, args.test_folder, 'Incorrect path for test folder')
        tests = list(map(lambda f: os.path.join(args.test_folder, f), os.listdir(args.test_folder)))
    else:
        check_path(os.path.exists, args.test_file, 'Incorrect test path')
        tests = [args.test_file]

    total = 0
    max_pad = len(os.path.basename(max(tests, key=lambda t: len(os.path.basename(t)))))
    for test_file in tests:
        base_name = os.path.basename(test_file)
        if not with_proc_stats:
            cur = single_test_run(args.solution, args.interactor, test_file)
        else:
            cur = stats_test_run(args.solution, args.interactor, test_file)
        total += cur.score
        if with_proc_stats and cur.memory is not None:
            print('Test {: >{}}  Time: {:.3f}s, Memory: {:.1f}mb, '.format(base_name, max_pad, cur.cpu_time, cur.memory), end='')
        print("Result: ", cur.score)

    print("Total: ", total)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Runs solution on tests.")
    parser.add_argument('solution',
                        help='Name of solution to test ("java mysol" for Java or "python mysol" for Python); '
                             'dont forget dot-slash on Linux)',
                        metavar="SOLUTION")

    parser.add_argument('--interactor',
                        help='Path to interactor',
                        default='./interactor.exe' if os.name == 'nt' else './interactor',
                        nargs='?'
                        )

    group = parser.add_mutually_exclusive_group()

    group.add_argument('-d',
                       dest='test_folder',
                       type=str,
                       nargs='?',
                       help='Run solution on all tests in folder. Assume that all files in folder in test format')

    group.add_argument('-i',
                       dest='test_file',
                       type=str,
                       nargs='?',
                       default='1.in',
                       help='Run solution on single test')
    args = parser.parse_args()
    main(args)
