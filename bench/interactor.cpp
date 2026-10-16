#include <stdint.h>
#include "testlib.h"
using namespace std;


double testWeight;
vector<int> input;

void readInput() {
    testWeight = inf.readDouble();
    int n = inf.readInt();
    input.resize(n);
    for (int i = 0; i < n; ++i) {
        input[i] = inf.readInt();
    }
}

//additional limit on total length of the trench to avoid crashing
static const int MAX_LENGTH = 100 * 1000 * 1000;


pair<int, int> checkSolution(InStream &in) {
    //index of pipe occupying each cell
    vector<int> cells;
    //shift + length of each pipe, indexed by PID
    vector<pair<int, int>> intervals;
    intervals.emplace_back(-1, -1);    //PID = 0 not used

    for (int operIdx = 1; operIdx <= input.size(); operIdx++) {
        int instr = input[operIdx - 1];
        cout << instr << endl;

        if (instr > 0) {
            int cnt = in.readInt(0, MAX_LENGTH, format("Operation %d: number of digs", operIdx));
            int shift = in.readInt(0, MAX_LENGTH, format("Operation %d: distance", operIdx));
            tout << cnt << " " << shift << endl;

            for (int j = 0; j < cnt; j++) {
                in.ensuref(cells.size() < MAX_LENGTH,
                    "[Operation %d] digged way too much: total %d",
                    operIdx, int(cells.size())
                );
                cells.resize(cells.size() + 20, -1);
            }

            int len = instr;
            in.ensuref(shift + len <= cells.size(),
                "[Operation %d] end of new pipe %d is out of trench: %d > %d",
                operIdx, int(intervals.size()), shift + len, int(cells.size())
            );
            for (int i = 0; i < len; i++) {
                in.ensuref(cells[shift + i] < 0,
                    "[Operation %d] new pipe %d collides with pipe %d at location [%d..%d]",
                    operIdx, int(intervals.size()), cells[shift + i], shift + i, shift + i + 1
                );
                cells[shift + i] = intervals.size();
            }
            intervals.emplace_back(shift, len);
        }
        else if (instr < 0) {
            int shift = in.readInt(0, MAX_LENGTH, format("Operation %d: distance", operIdx));
            tout << shift << endl;

            int id = -instr;
            int pos = intervals.at(id).first;
            int len = intervals.at(id).second;
            in.ensuref(pos == shift,
                "[Operation %d] position of pipe %d is wrong: printed %d but correct %d",
                operIdx, id, shift, pos
            );

            for (int i = 0; i < len; ++i) {
                ensure(cells[shift + i] == id);
                cells[shift + i] = -1;
            }
            intervals[id] = make_pair(-1, -1);  //just in case
        }
    }
    cout << 0 << endl;

    int numOccupied = 0;
    for (int i = 0; i < cells.size(); i++)
        numOccupied += (cells[i] >= 0);
    return make_pair(numOccupied, int(cells.size()));
}


int main(int argc, char* argv[]) {
    setName("Gas pipeline construction interactor");
    registerInteraction(argc, argv);

    readInput();
    pair<int, int> p = checkSolution(ouf);
    ensure(p.second > 0);
    double res = (double)p.first / (double)p.second;
    ensure(res >= 0.0 && res <= 1.0);

    double score = res * testWeight;
    fprintf(stderr,
        "Score = %d/%d * weight = %0.15lf x %0.2lf = %0.15lf\n",
        p.first, p.second, res, testWeight, score
    );

    ofstream scorefile("score.txt");
    scorefile.precision(9);
    scorefile << score << endl;

    return 0;
}
