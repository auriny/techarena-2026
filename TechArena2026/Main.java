//package org.example.hackatones.TechArena2026;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Гибридный солвер v2 для TechArena 2026:
 *
 * 1. Точное аналитическое решение для одиночного офиса (k = 1) за O(N_S log N_S):
 *    - При одном открытом офисе все доступные специалисты назначаются ему,
 *      штраф баланса задач равен 0, ограничение maxDifference тривиально выполняется,
 *      поэтому глобальный оптимум достигается выбором топ maxSize допустимых объектов с netVal > 0.
 *
 * 2. Двухэтапное точное 2D-динамическое программирование в выпуклых контейнерах (solveExactPoolDP)
 *    + Итеративное сжатие контейнеров (Iterative Container Tightening):
 *    - Теорема: если контейнеры H_0, ..., H_{k-1} не пересекаются, то любые подмножества объектов
 *      S_t внутри H_t гарантированно порождают непересекающиеся выпуклые оболочки conv({o_t} U S_t).
 *    - Внутри каждого контейнера строится точная 2D-таблица рюкзака dp[t][n][tau] (максимальная
 *      прибыль при выборе ровно n объектов с суммарным числом задач tau) с 64-битной маской восстановления.
 *    - Поверх таблиц dp[t][n][tau] перебираются окна числа объектов [L, L + maxDifference] и коридоры
 *      задач [tauMin, tauMax], находя глобальный оптимум распределения объектов и задач за < 0.2 мс.
 *    - После выбора оптимальных подмножеств S_t* реальные оболочки conv({o_t} U S_t*) сжимаются,
 *      освобождая буферную зону между офисами, куда добираются новые объекты с повторным запуском DP.
 *
 * 3. Семейство адаптивных диаграмм Вороного в степенной метрике (3-Mode Laguerre / Power Diagram):
 *    - Режим 0: балансировка числа доходных объектов под maxSize и maxDifference;
 *    - Режим 1: взвешивание по чистой доходности (reward - roadDist) для захвата дорогих кластеров;
 *    - Режим 2: балансировка суммарной точной нагрузки по задачам (alpha * (maxTasks - minTasks)).
 *
 * 4. FastGeo — движок выпуклых оболочек на плоских примитивных массивах и 64-битных битовых масках:
 *    - O(1) проверка попадания кандидата внутрь текущей оболочки;
 *    - O(1) отсечение кандидатов, покрытых чужими оболочками.
 *
 * 5. WorkerSolver + 10-окрестностный локальный поиск + ALNS + SurrogateML с матрицей синергии офисов.
 */
public class Main {

    private static final long DEFAULT_TIME_LIMIT_MS = 200_000L;

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args.length > 0 ? args[0] : "instance.json");
        Path output = Path.of(args.length > 1 ? args[1] : "solution.json");
        long timeLimitMs = args.length > 2 ? Long.parseLong(args[2]) : DEFAULT_TIME_LIMIT_MS;

        String text = Files.readString(input, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) Json.parse(text);
        Instance instance = Instance.fromJson(raw);

        Solver solver = new Solver(instance, System.nanoTime() + timeLimitMs * 1_000_000L);
        Solution solution = Solution.empty();
        try {
            solution = solver.solve();
        } finally {
            Files.writeString(output, solution.toJson(), StandardCharsets.UTF_8);
        }
    }

    /* ============================ JSON PARSER ============================ */

    static final class Json {
        static Object parse(String text) {
            return new Parser(text).parseValue();
        }

        static final class Parser {
            final String s;
            int i;

            Parser(String s) {
                this.s = s;
            }

            void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            char peek() {
                skipWs();
                return i < s.length() ? s.charAt(i) : 0;
            }

            Object parseValue() {
                char c = peek();
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == '-' || Character.isDigit(c)) return parseNumber();
                throw new RuntimeException("bad json at " + i);
            }

            String parseString() {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') return out.toString();
                    if (c == '\\') out.append(s.charAt(i++));
                    else out.append(c);
                }
                throw new RuntimeException("unterminated string");
            }

            Number parseNumber() {
                int start = i;
                if (s.charAt(i) == '-') i++;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                        i++;
                    } else {
                        break;
                    }
                }
                String token = s.substring(start, i);
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                    return Double.parseDouble(token);
                }
                long v = Long.parseLong(token);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
                return v;
            }

            Map<String, Object> parseObject() {
                expect('{');
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek() == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    String key = parseString();
                    skipWs();
                    expect(':');
                    map.put(key, parseValue());
                    skipWs();
                    char c = s.charAt(i++);
                    if (c == '}') return map;
                    if (c != ',') throw new RuntimeException("expected , or }");
                }
            }

            List<Object> parseArray() {
                expect('[');
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek() == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    char c = s.charAt(i++);
                    if (c == ']') return list;
                    if (c != ',') throw new RuntimeException("expected , or ]");
                }
            }

            void expect(char ch) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != ch) {
                    throw new RuntimeException("expected " + ch);
                }
                i++;
            }
        }
    }

    /* ============================ DATA MODEL ============================ */

    static final class Vec {
        final double x;
        final double y;

        Vec(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class Site {
        final Vec pos;
        final double reward;
        final int[] tasks;
        final BitSet skillSet;

        Site(Vec pos, double reward, int[] tasks) {
            this.pos = pos;
            this.reward = reward;
            this.tasks = tasks;
            this.skillSet = new BitSet();
            for (int t : tasks) {
                this.skillSet.set(t);
            }
        }
    }

    static final class Office {
        final Vec pos;
        final double cost;
        final int maxSize;

        Office(Vec pos, double cost, int maxSize) {
            this.pos = pos;
            this.cost = cost;
            this.maxSize = maxSize;
        }
    }

    static final class Instance {
        final Site[] sites;
        final Office[] offices;
        final int[][] workers;
        final BitSet[] workerSkills;
        final int[] skillSupply;
        final double[][] distances;
        final double[][] netVal;       // reward[s] - distances[o][s]
        final double[][] euclidDist2;  // squared Euclidean distance [o][s]
        final double[][] officeEuclid; // Euclidean distance [o1][o2]
        final int maxDifference;
        final double balanceCoefficient;
        final int skillBound;
        final BitSet coveredGlobally;
        final boolean[] feasibleSite;
        final int[] siteTaskCount;
        final int maxSiteTasks;

        Instance(Site[] sites, Office[] offices, int[][] workers, double[][] distances,
                 int maxDifference, double balanceCoefficient) {
            this.sites = sites;
            this.offices = offices;
            this.workers = workers;
            this.distances = distances;
            this.maxDifference = maxDifference;
            this.balanceCoefficient = balanceCoefficient;

            int maxSkill = 0;
            this.workerSkills = new BitSet[workers.length];
            for (int i = 0; i < workers.length; i++) {
                workerSkills[i] = new BitSet();
                for (int s : workers[i]) {
                    workerSkills[i].set(s);
                    maxSkill = Math.max(maxSkill, s);
                }
            }
            for (Site site : sites) {
                for (int t : site.tasks) {
                    maxSkill = Math.max(maxSkill, t);
                }
            }
            this.skillBound = maxSkill + 1;
            this.skillSupply = new int[skillBound];
            this.coveredGlobally = new BitSet();
            for (BitSet skills : workerSkills) {
                for (int s = skills.nextSetBit(0); s >= 0; s = skills.nextSetBit(s + 1)) {
                    if (s < skillBound) {
                        skillSupply[s]++;
                        coveredGlobally.set(s);
                    }
                }
            }

            int nS = sites.length;
            int nO = offices.length;
            this.feasibleSite = new boolean[nS];
            this.siteTaskCount = new int[nS];
            int mTasks = 1;
            for (int s = 0; s < nS; s++) {
                siteTaskCount[s] = sites[s].tasks.length;
                mTasks = Math.max(mTasks, siteTaskCount[s]);
                boolean ok = true;
                BitSet need = sites[s].skillSet;
                for (int sk = need.nextSetBit(0); sk >= 0; sk = need.nextSetBit(sk + 1)) {
                    if (!coveredGlobally.get(sk)) {
                        ok = false;
                        break;
                    }
                }
                feasibleSite[s] = ok;
            }
            this.maxSiteTasks = mTasks;

            this.netVal = new double[nO][nS];
            this.euclidDist2 = new double[nO][nS];
            for (int o = 0; o < nO; o++) {
                for (int s = 0; s < nS; s++) {
                    netVal[o][s] = sites[s].reward - distances[o][s];
                    euclidDist2[o][s] = Geometry.euclid2(offices[o].pos, sites[s].pos);
                }
            }

            this.officeEuclid = new double[nO][nO];
            for (int i = 0; i < nO; i++) {
                for (int j = i + 1; j < nO; j++) {
                    double d = Math.sqrt(Geometry.euclid2(offices[i].pos, offices[j].pos));
                    officeEuclid[i][j] = d;
                    officeEuclid[j][i] = d;
                }
            }
        }

        @SuppressWarnings("unchecked")
        static Instance fromJson(Map<String, Object> raw) {
            List<Object> sitesRaw = (List<Object>) raw.get("sites");
            List<Object> officesRaw = (List<Object>) raw.get("offices");
            List<Object> workersRaw = (List<Object>) raw.get("workers");
            List<Object> distRaw = (List<Object>) raw.get("distances");

            Site[] sites = new Site[sitesRaw.size()];
            for (int i = 0; i < sitesRaw.size(); i++) {
                Map<String, Object> s = (Map<String, Object>) sitesRaw.get(i);
                sites[i] = new Site(
                        new Vec(num(s.get("x")), num(s.get("y"))),
                        num(s.get("reward")),
                        intArray(s.get("tasks")));
            }
            Office[] offices = new Office[officesRaw.size()];
            for (int i = 0; i < officesRaw.size(); i++) {
                Map<String, Object> o = (Map<String, Object>) officesRaw.get(i);
                offices[i] = new Office(
                        new Vec(num(o.get("x")), num(o.get("y"))),
                        num(o.get("cost")),
                        (int) num(o.get("maxSize")));
            }
            int[][] workers = new int[workersRaw.size()][];
            for (int i = 0; i < workersRaw.size(); i++) {
                workers[i] = intArray(workersRaw.get(i));
            }
            double[][] distances = new double[distRaw.size()][];
            for (int i = 0; i < distRaw.size(); i++) {
                List<Object> row = (List<Object>) distRaw.get(i);
                distances[i] = new double[row.size()];
                for (int j = 0; j < row.size(); j++) {
                    distances[i][j] = num(row.get(j));
                }
            }
            return new Instance(sites, offices, workers, distances,
                    (int) num(raw.get("maxDifference")),
                    num(raw.get("balanceCoefficient")));
        }

        static double num(Object v) {
            return ((Number) v).doubleValue();
        }

        static int[] intArray(Object value) {
            if (!(value instanceof List<?> list)) {
                return new int[0];
            }
            int[] out = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = ((Number) list.get(i)).intValue();
            }
            return out;
        }
    }

    static final class OpenedOffice {
        final int officeId;
        final List<Integer> sites;
        final List<Integer> workers;

        OpenedOffice(int officeId, List<Integer> sites, List<Integer> workers) {
            this.officeId = officeId;
            this.sites = sites;
            this.workers = workers;
        }
    }

    static final class Solution {
        final List<OpenedOffice> offices;
        final double objective;

        Solution(List<OpenedOffice> offices, double objective) {
            this.offices = offices;
            this.objective = objective;
        }

        static Solution empty() {
            return new Solution(List.of(), 0.0);
        }

        static double objectiveOf(Instance inst, List<OpenedOffice> opened) {
            if (opened.isEmpty()) {
                return 0.0;
            }
            double reward = 0;
            double distance = 0;
            double cost = 0;
            int minTasks = Integer.MAX_VALUE;
            int maxTasks = 0;
            for (OpenedOffice o : opened) {
                cost += inst.offices[o.officeId].cost;
                int tasks = 0;
                for (int s : o.sites) {
                    reward += inst.sites[s].reward;
                    distance += inst.distances[o.officeId][s];
                    tasks += inst.sites[s].tasks.length;
                }
                minTasks = Math.min(minTasks, tasks);
                maxTasks = Math.max(maxTasks, tasks);
            }
            return reward - distance - cost - inst.balanceCoefficient * (maxTasks - minTasks);
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < offices.size(); i++) {
                OpenedOffice o = offices.get(i);
                sb.append("  {\n");
                sb.append("    \"officeId\": ").append(o.officeId).append(",\n");
                sb.append("    \"sites\": ").append(ints(o.sites)).append(",\n");
                sb.append("    \"workers\": ").append(ints(o.workers)).append("\n");
                sb.append("  }");
                if (i + 1 < offices.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("]\n");
            return sb.toString();
        }

        private static String ints(List<Integer> values) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(values.get(i));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /* ============================ GEOMETRY & VALIDATOR ============================ */

    static final class Geometry {
        static final double EPS = 1e-12;

        static List<Vec> convexHull(List<Vec> points) {
            int nPoints = points.size();
            if (nPoints <= 2) {
                if (nPoints <= 1) return new ArrayList<>(points);
                if (same(points.get(0), points.get(1))) {
                    List<Vec> u = new ArrayList<>(1);
                    u.add(points.get(0));
                    return u;
                }
                return new ArrayList<>(points);
            }
            Vec[] sorted = points.toArray(new Vec[nPoints]);
            Arrays.sort(sorted, (a, b) -> {
                int cmp = Double.compare(a.x, b.x);
                return cmp != 0 ? cmp : Double.compare(a.y, b.y);
            });
            int uCount = 0;
            for (int i = 0; i < nPoints; i++) {
                if (uCount == 0 || !same(sorted[uCount - 1], sorted[i])) {
                    sorted[uCount++] = sorted[i];
                }
            }
            if (uCount <= 2) {
                List<Vec> unique = new ArrayList<>(uCount);
                for (int i = 0; i < uCount; i++) unique.add(sorted[i]);
                return unique;
            }
            Vec[] stack = new Vec[2 * uCount];
            int k = 0;
            for (int i = 0; i < uCount; i++) {
                Vec p = sorted[i];
                while (k >= 2 && cross(stack[k - 2], stack[k - 1], p) <= EPS) k--;
                stack[k++] = p;
            }
            for (int i = uCount - 2, t = k + 1; i >= 0; i--) {
                Vec p = sorted[i];
                while (k >= t && cross(stack[k - 2], stack[k - 1], p) <= EPS) k--;
                stack[k++] = p;
            }
            List<Vec> res = new ArrayList<>(k - 1);
            for (int i = 0; i < k - 1; i++) res.add(stack[i]);
            return res;
        }

        static boolean covers(List<Vec> hull, Vec p) {
            int n = hull.size();
            if (n == 0) return false;
            if (n == 1) return same(hull.get(0), p);
            if (n == 2) return onSegment(hull.get(0), hull.get(1), p);
            boolean hasPos = false;
            boolean hasNeg = false;
            for (int i = 0; i < n; i++) {
                int next = (i + 1 == n) ? 0 : (i + 1);
                double cr = cross(hull.get(i), hull.get(next), p);
                if (cr > EPS) hasPos = true;
                else if (cr < -EPS) hasNeg = true;
                if (hasPos && hasNeg) return false;
            }
            return true;
        }

        static boolean same(Vec a, Vec b) {
            return Math.abs(a.x - b.x) <= EPS && Math.abs(a.y - b.y) <= EPS;
        }

        static double cross(Vec a, Vec b, Vec c) {
            return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        }

        static boolean onSegment(Vec a, Vec b, Vec p) {
            if (Math.abs(cross(a, b, p)) > EPS) return false;
            return p.x + EPS >= Math.min(a.x, b.x) && p.x - EPS <= Math.max(a.x, b.x)
                    && p.y + EPS >= Math.min(a.y, b.y) && p.y - EPS <= Math.max(a.y, b.y);
        }

        static double euclid2(Vec a, Vec b) {
            double dx = a.x - b.x;
            double dy = a.y - b.y;
            return dx * dx + dy * dy;
        }
    }

    static final class Constraints {
        static boolean valid(Instance inst, List<OpenedOffice> opened) {
            int nO = inst.offices.length;
            int nS = inst.sites.length;
            int nW = inst.workers.length;
            boolean[] usedOffice = new boolean[nO];
            boolean[] usedSite = new boolean[nS];
            boolean[] usedWorker = new boolean[nW];
            for (OpenedOffice o : opened) {
                if (o.officeId < 0 || o.officeId >= nO || usedOffice[o.officeId]) return false;
                usedOffice[o.officeId] = true;
                if (o.sites.size() > inst.offices[o.officeId].maxSize) return false;
                for (int s : o.sites) {
                    if (s < 0 || s >= nS || usedSite[s]) return false;
                    usedSite[s] = true;
                }
                for (int w : o.workers) {
                    if (w < 0 || w >= nW || usedWorker[w]) return false;
                    usedWorker[w] = true;
                }
            }
            int minSites = Integer.MAX_VALUE;
            int maxSites = 0;
            for (OpenedOffice o : opened) {
                minSites = Math.min(minSites, o.sites.size());
                maxSites = Math.max(maxSites, o.sites.size());
                for (int s : o.sites) {
                    for (int task : inst.sites[s].tasks) {
                        boolean ok = false;
                        for (int w : o.workers) {
                            if (inst.workerSkills[w].get(task)) {
                                ok = true;
                                break;
                            }
                        }
                        if (!ok) return false;
                    }
                }
            }
            if (!opened.isEmpty() && maxSites - minSites > inst.maxDifference) return false;

            BitSet[] covered = new BitSet[opened.size()];
            double pad = 1e-9;
            for (int i = 0; i < opened.size(); i++) {
                OpenedOffice o = opened.get(i);
                List<Vec> raw = new ArrayList<>(o.sites.size() + 1);
                raw.add(inst.offices[o.officeId].pos);
                for (int s : o.sites) raw.add(inst.sites[s].pos);
                List<Vec> hull = Geometry.convexHull(raw);
                BitSet mask = new BitSet(nS + nO);
                if (!hull.isEmpty()) {
                    double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                    double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                    for (Vec p : hull) {
                        if (p.x < minX) minX = p.x;
                        if (p.x > maxX) maxX = p.x;
                        if (p.y < minY) minY = p.y;
                        if (p.y > maxY) maxY = p.y;
                    }
                    for (int s = 0; s < nS; s++) {
                        Vec p = inst.sites[s].pos;
                        if (p.x + pad < minX || p.x - pad > maxX || p.y + pad < minY || p.y - pad > maxY) continue;
                        if (Geometry.covers(hull, p)) mask.set(s);
                    }
                    for (int j = 0; j < nO; j++) {
                        Vec p = inst.offices[j].pos;
                        if (p.x + pad < minX || p.x - pad > maxX || p.y + pad < minY || p.y - pad > maxY) continue;
                        if (Geometry.covers(hull, p)) mask.set(nS + j);
                    }
                }
                for (int prev = 0; prev < i; prev++) {
                    if (mask.intersects(covered[prev])) return false;
                }
                covered[i] = mask;
            }
            return true;
        }
    }

    /* ============================ FAST INCREMENTAL GEOMETRY ENGINE ============================ */

    static final class FastGeo {
        private static final double EPS = 1e-12;
        private static final double PAD = 1e-9;

        final int nS;
        final int nO;
        final int nP;
        final int words;
        final double[] px;
        final double[] py;

        private final int[] scratchPts;
        private final int[] scratchHull;

        FastGeo(Instance inst) {
            this.nS = inst.sites.length;
            this.nO = inst.offices.length;
            this.nP = nS + nO;
            this.words = (nP + 63) >>> 6;
            this.px = new double[nP];
            this.py = new double[nP];
            for (int s = 0; s < nS; s++) {
                px[s] = inst.sites[s].pos.x;
                py[s] = inst.sites[s].pos.y;
            }
            for (int o = 0; o < nO; o++) {
                px[nS + o] = inst.offices[o].pos.x;
                py[nS + o] = inst.offices[o].pos.y;
            }
            this.scratchPts = new int[nP + 4];
            this.scratchHull = new int[2 * (nP + 4)];
        }

        int buildHull(int[] pts, int len, int[] outHull) {
            if (len <= 0) return 0;
            if (len == 1) {
                outHull[0] = pts[0];
                return 1;
            }
            for (int i = 0; i < len; i++) {
                scratchPts[i] = pts[i];
            }
            sortPointsByXY(scratchPts, len);

            int uCount = 0;
            for (int i = 0; i < len; i++) {
                int p = scratchPts[i];
                if (uCount == 0 || !samePt(scratchPts[uCount - 1], p)) {
                    scratchPts[uCount++] = p;
                }
            }
            if (uCount <= 2) {
                for (int i = 0; i < uCount; i++) outHull[i] = scratchPts[i];
                return uCount;
            }
            int k = 0;
            for (int i = 0; i < uCount; i++) {
                int p = scratchPts[i];
                while (k >= 2 && crossPt(scratchHull[k - 2], scratchHull[k - 1], p) <= EPS) {
                    k--;
                }
                scratchHull[k++] = p;
            }
            for (int i = uCount - 2, t = k + 1; i >= 0; i--) {
                int p = scratchPts[i];
                while (k >= t && crossPt(scratchHull[k - 2], scratchHull[k - 1], p) <= EPS) {
                    k--;
                }
                scratchHull[k++] = p;
            }
            int hLen = k - 1;
            for (int i = 0; i < hLen; i++) {
                outHull[i] = scratchHull[i];
            }
            return hLen;
        }

        private void sortPointsByXY(int[] a, int n) {
            for (int i = 1; i < n; i++) {
                int key = a[i];
                double kx = px[key], ky = py[key];
                int j = i - 1;
                while (j >= 0) {
                    int cur = a[j];
                    double cx = px[cur], cy = py[cur];
                    if (cx > kx || (cx == kx && cy > ky)) {
                        a[j + 1] = cur;
                        j--;
                    } else {
                        break;
                    }
                }
                a[j + 1] = key;
            }
        }

        boolean samePt(int a, int b) {
            return Math.abs(px[a] - px[b]) <= EPS && Math.abs(py[a] - py[b]) <= EPS;
        }

        double crossPt(int a, int b, int c) {
            return (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
        }

        boolean onSegPt(int a, int b, int p) {
            if (Math.abs(crossPt(a, b, p)) > EPS) return false;
            return px[p] + EPS >= Math.min(px[a], px[b]) && px[p] - EPS <= Math.max(px[a], px[b])
                    && py[p] + EPS >= Math.min(py[a], py[b]) && py[p] - EPS <= Math.max(py[a], py[b]);
        }

        boolean coversPt(int[] hull, int hLen, int p) {
            if (hLen == 0) return false;
            if (hLen == 1) return samePt(hull[0], p);
            if (hLen == 2) return onSegPt(hull[0], hull[1], p);
            boolean hasPos = false;
            boolean hasNeg = false;
            for (int i = 0; i < hLen; i++) {
                int next = (i + 1 == hLen) ? 0 : (i + 1);
                double cr = crossPt(hull[i], hull[next], p);
                if (cr > EPS) hasPos = true;
                else if (cr < -EPS) hasNeg = true;
                if (hasPos && hasNeg) return false;
            }
            return true;
        }

        void computeCoverMask(int[] hull, int hLen, long[] mask) {
            Arrays.fill(mask, 0L);
            if (hLen == 0) return;
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < hLen; i++) {
                int v = hull[i];
                double vx = px[v], vy = py[v];
                if (vx < minX) minX = vx;
                if (vx > maxX) maxX = vx;
                if (vy < minY) minY = vy;
                if (vy > maxY) maxY = vy;
            }
            minX -= PAD;
            maxX += PAD;
            minY -= PAD;
            maxY += PAD;
            for (int p = 0; p < nP; p++) {
                double x = px[p], y = py[p];
                if (x < minX || x > maxX || y < minY || y > maxY) continue;
                if (coversPt(hull, hLen, p)) {
                    mask[p >>> 6] |= (1L << (p & 63));
                }
            }
        }

        void buildUnionOther(long[][] covMasks, int k, int skipT, long[] outUnion) {
            Arrays.fill(outUnion, 0L);
            for (int u = 0; u < k; u++) {
                if (u == skipT || covMasks[u] == null) continue;
                long[] m = covMasks[u];
                for (int w = 0; w < words; w++) {
                    outUnion[w] |= m[w];
                }
            }
        }

        int testAddSiteToHull(int[] curHull, int curHLen, long[] curCovMask,
                              long[] unionOtherCov, int s, int[] candHull) {
            if ((unionOtherCov[s >>> 6] & (1L << (s & 63))) != 0L) {
                return -1;
            }
            if (curHLen > 0 && (curCovMask[s >>> 6] & (1L << (s & 63))) != 0L) {
                System.arraycopy(curHull, 0, candHull, 0, curHLen);
                return curHLen;
            }
            int[] tmp = new int[curHLen + 1];
            System.arraycopy(curHull, 0, tmp, 0, curHLen);
            tmp[curHLen] = s;
            int newHLen = buildHull(tmp, curHLen + 1, candHull);

            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < newHLen; i++) {
                int v = candHull[i];
                double vx = px[v], vy = py[v];
                if (vx < minX) minX = vx;
                if (vx > maxX) maxX = vx;
                if (vy < minY) minY = vy;
                if (vy > maxY) maxY = vy;
            }
            minX -= PAD;
            maxX += PAD;
            minY -= PAD;
            maxY += PAD;

            for (int w = 0; w < words; w++) {
                long word = unionOtherCov[w];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int p = (w << 6) + bit;
                    if (p < nP) {
                        double x = px[p], y = py[p];
                        if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                            if (coversPt(candHull, newHLen, p)) {
                                return -1;
                            }
                        }
                    }
                    word &= (word - 1L);
                }
            }
            return newHLen;
        }

        static boolean masksIntersect(long[] a, long[] b, int words) {
            for (int w = 0; w < words; w++) {
                if ((a[w] & b[w]) != 0L) return true;
            }
            return false;
        }
    }

    /* ============================ EXACT & HEURISTIC WORKER SOLVER ============================ */

    static final class WorkerSolver {
        final Instance inst;
        final int nW;
        final int nSk;

        WorkerSolver(Instance inst) {
            this.inst = inst;
            this.nW = inst.workers.length;
            this.nSk = inst.skillBound;
        }

        @SuppressWarnings("unchecked")
        List<Integer>[] allocate(BitSet[] needs) {
            int k = needs.length;
            if (k == 0) return new List[0];
            if (k > nW) return null;

            for (int sk = 0; sk < nSk; sk++) {
                int demand = 0;
                for (int t = 0; t < k; t++) {
                    if (needs[t].get(sk)) demand++;
                }
                if (demand > inst.skillSupply[sk]) {
                    return null;
                }
            }

            List<Integer>[] greedy = allocateScarcityGreedy(needs);
            if (greedy != null) {
                return greedy;
            }

            BitSet[] rem = new BitSet[k];
            List<Integer>[] assigned = new List[k];
            for (int t = 0; t < k; t++) {
                rem[t] = (BitSet) needs[t].clone();
                assigned[t] = new ArrayList<>(4);
            }
            boolean[] usedW = new boolean[nW];
            int[] budget = new int[]{4500};
            if (backtrackMRV(rem, assigned, usedW, budget)) {
                return assigned;
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private List<Integer>[] allocateScarcityGreedy(BitSet[] needs) {
            int k = needs.length;
            boolean[] used = new boolean[nW];
            List<Integer>[] perOffice = new List[k];
            BitSet[] rem = new BitSet[k];
            for (int t = 0; t < k; t++) {
                perOffice[t] = new ArrayList<>(4);
                rem[t] = (BitSet) needs[t].clone();
            }

            while (true) {
                int hardT = -1;
                int minAvail = Integer.MAX_VALUE;
                for (int t = 0; t < k; t++) {
                    if (rem[t].isEmpty()) continue;
                    for (int sk = rem[t].nextSetBit(0); sk >= 0; sk = rem[t].nextSetBit(sk + 1)) {
                        int avail = 0;
                        for (int w = 0; w < nW; w++) {
                            if (!used[w] && inst.workerSkills[w].get(sk)) avail++;
                        }
                        if (avail == 0) return null;
                        if (avail < minAvail) {
                            minAvail = avail;
                            hardT = t;
                        }
                    }
                }
                if (hardT < 0) break;

                int bestW = -1;
                double bestScore = -1e100;
                for (int w = 0; w < nW; w++) {
                    if (used[w]) continue;
                    int cov = 0;
                    double gain = 0.0;
                    for (int sk = rem[hardT].nextSetBit(0); sk >= 0; sk = rem[hardT].nextSetBit(sk + 1)) {
                        if (inst.workerSkills[w].get(sk)) {
                            cov++;
                            gain += 10.0 / Math.max(1, inst.skillSupply[sk]);
                        }
                    }
                    if (cov == 0) continue;
                    double penalty = 0.0;
                    BitSet ws = inst.workerSkills[w];
                    for (int sk = ws.nextSetBit(0); sk >= 0; sk = ws.nextSetBit(sk + 1)) {
                        if (rem[hardT].get(sk)) continue;
                        int otherNeed = 0;
                        for (int u = 0; u < k; u++) {
                            if (u != hardT && rem[u].get(sk)) otherNeed++;
                        }
                        if (otherNeed > 0) {
                            int avail = 0;
                            for (int w2 = 0; w2 < nW; w2++) {
                                if (!used[w2] && inst.workerSkills[w2].get(sk)) avail++;
                            }
                            if (avail <= otherNeed) {
                                penalty += 50.0;
                            } else {
                                penalty += 2.0 * otherNeed / avail;
                            }
                        }
                    }
                    double sc = gain - penalty;
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestW = w;
                    }
                }
                if (bestW < 0) return null;
                used[bestW] = true;
                perOffice[hardT].add(bestW);
                rem[hardT].andNot(inst.workerSkills[bestW]);
            }
            return perOffice;
        }

        private boolean backtrackMRV(BitSet[] rem, List<Integer>[] assigned, boolean[] usedW, int[] budget) {
            if (--budget[0] <= 0) return false;

            int bestT = -1;
            int bestSk = -1;
            int minCand = Integer.MAX_VALUE;

            int k = rem.length;
            for (int t = 0; t < k; t++) {
                if (rem[t].isEmpty()) continue;
                for (int sk = rem[t].nextSetBit(0); sk >= 0; sk = rem[t].nextSetBit(sk + 1)) {
                    int cands = 0;
                    for (int w = 0; w < nW; w++) {
                        if (!usedW[w] && inst.workerSkills[w].get(sk)) {
                            cands++;
                        }
                    }
                    if (cands == 0) return false;
                    if (cands < minCand) {
                        minCand = cands;
                        bestT = t;
                        bestSk = sk;
                        if (cands == 1) break;
                    }
                }
                if (minCand == 1) break;
            }

            if (bestT < 0) {
                return true;
            }

            int[] cands = new int[minCand];
            int[] scores = new int[minCand];
            int ptr = 0;
            for (int w = 0; w < nW; w++) {
                if (!usedW[w] && inst.workerSkills[w].get(bestSk)) {
                    cands[ptr] = w;
                    int cov = 0;
                    for (int sk = rem[bestT].nextSetBit(0); sk >= 0; sk = rem[bestT].nextSetBit(sk + 1)) {
                        if (inst.workerSkills[w].get(sk)) cov++;
                    }
                    scores[ptr] = cov * 100 - inst.workerSkills[w].cardinality();
                    ptr++;
                }
            }
            for (int i = 1; i < ptr; i++) {
                int cw = cands[i], cs = scores[i];
                int j = i - 1;
                while (j >= 0 && scores[j] < cs) {
                    cands[j + 1] = cands[j];
                    scores[j + 1] = scores[j];
                    j--;
                }
                cands[j + 1] = cw;
                scores[j + 1] = cs;
            }

            BitSet savedRem = (BitSet) rem[bestT].clone();
            for (int i = 0; i < ptr; i++) {
                int w = cands[i];
                usedW[w] = true;
                assigned[bestT].add(w);
                rem[bestT].andNot(inst.workerSkills[w]);

                if (backtrackMRV(rem, assigned, usedW, budget)) {
                    return true;
                }

                rem[bestT] = (BitSet) savedRem.clone();
                assigned[bestT].remove(assigned[bestT].size() - 1);
                usedW[w] = false;
                if (budget[0] <= 0) return false;
            }
            return false;
        }

        List<BitSet[]> generateWorkerPolicies(int[] oid, List<Integer>[] voronoiSites, boolean thorough) {
            int k = oid.length;
            List<BitSet[]> policies = new ArrayList<>();

            if (nW == 0) return policies;

            policies.add(buildMarginalWorkerPartition(oid, voronoiSites, 1.5, thorough ? 10 : 2));

            if (thorough) {
                BitSet[] full = new BitSet[k];
                for (int t = 0; t < k; t++) {
                    full[t] = (BitSet) inst.coveredGlobally.clone();
                }
                policies.add(full);
                policies.add(buildMarginalWorkerPartition(oid, voronoiSites, 5.0, 10));
                policies.add(buildScarcityWorkerPartition(oid, voronoiSites));
            }
            return deduplicatePolicies(policies);
        }

        private BitSet[] buildMarginalWorkerPartition(int[] oid, List<Integer>[] pools, double emptyBonusFactor, int maxSwapIters) {
            int k = oid.length;
            BitSet[] skills = new BitSet[k];
            int[] workerCount = new int[k];
            for (int t = 0; t < k; t++) skills[t] = new BitSet();
            boolean[] usedW = new boolean[nW];
            int[] workerOwner = new int[nW];
            Arrays.fill(workerOwner, -1);

            for (int step = 0; step < nW; step++) {
                int bestW = -1;
                int bestT = -1;
                double bestGain = -1e100;

                for (int t = 0; t < k; t++) {
                    double curVal = poolCoverableValue(oid[t], pools[t], skills[t]);
                    double bonus = (workerCount[t] == 0) ? (200.0 * emptyBonusFactor) : 0.0;
                    for (int w = 0; w < nW; w++) {
                        if (usedW[w]) continue;
                        BitSet next = (BitSet) skills[t].clone();
                        next.or(inst.workerSkills[w]);
                        double nextVal = poolCoverableValue(oid[t], pools[t], next);
                        double partial = partialSkillScore(oid[t], pools[t], next) - partialSkillScore(oid[t], pools[t], skills[t]);
                        double gain = (nextVal - curVal) + 0.15 * partial + bonus;
                        if (gain > bestGain) {
                            bestGain = gain;
                            bestW = w;
                            bestT = t;
                        }
                    }
                }
                if (bestW < 0) break;
                usedW[bestW] = true;
                workerOwner[bestW] = bestT;
                workerCount[bestT]++;
                skills[bestT].or(inst.workerSkills[bestW]);
            }

            boolean improved = true;
            int guard = 0;
            while (improved && guard++ < maxSwapIters) {
                improved = false;
                double curTotal = totalPoolValue(oid, pools, workerOwner);
                for (int w = 0; w < nW; w++) {
                    int oldT = workerOwner[w];
                    for (int newT = 0; newT < k; newT++) {
                        if (newT == oldT) continue;
                        workerOwner[w] = newT;
                        double v = totalPoolValue(oid, pools, workerOwner);
                        if (v > curTotal + 1e-6) {
                            curTotal = v;
                            oldT = newT;
                            improved = true;
                        } else {
                            workerOwner[w] = oldT;
                        }
                    }
                }
                for (int w1 = 0; w1 < nW; w1++) {
                    for (int w2 = w1 + 1; w2 < nW; w2++) {
                        if (workerOwner[w1] == workerOwner[w2]) continue;
                        int t1 = workerOwner[w1], t2 = workerOwner[w2];
                        workerOwner[w1] = t2;
                        workerOwner[w2] = t1;
                        double v = totalPoolValue(oid, pools, workerOwner);
                        if (v > curTotal + 1e-6) {
                            curTotal = v;
                            improved = true;
                        } else {
                            workerOwner[w1] = t1;
                            workerOwner[w2] = t2;
                        }
                    }
                }
            }

            for (int t = 0; t < k; t++) skills[t].clear();
            for (int w = 0; w < nW; w++) {
                if (workerOwner[w] >= 0) {
                    skills[workerOwner[w]].or(inst.workerSkills[w]);
                }
            }
            return skills;
        }

        private BitSet[] buildScarcityWorkerPartition(int[] oid, List<Integer>[] pools) {
            int k = oid.length;
            BitSet[] desired = new BitSet[k];
            for (int t = 0; t < k; t++) {
                desired[t] = new BitSet();
                int cap = inst.offices[oid[t]].maxSize;
                int cnt = 0;
                for (int s : pools[t]) {
                    if (inst.netVal[oid[t]][s] > 0 && cnt < cap) {
                        desired[t].or(inst.sites[s].skillSet);
                        cnt++;
                    }
                }
            }
            List<Integer>[] alloc = allocate(desired);
            if (alloc != null) {
                BitSet[] skills = new BitSet[k];
                boolean[] usedW = new boolean[nW];
                for (int t = 0; t < k; t++) {
                    skills[t] = new BitSet();
                    for (int w : alloc[t]) {
                        skills[t].or(inst.workerSkills[w]);
                        usedW[w] = true;
                    }
                }
                for (int w = 0; w < nW; w++) {
                    if (usedW[w]) continue;
                    int bestT = 0;
                    double bestG = -1e100;
                    for (int t = 0; t < k; t++) {
                        BitSet next = (BitSet) skills[t].clone();
                        next.or(inst.workerSkills[w]);
                        double g = poolCoverableValue(oid[t], pools[t], next) - poolCoverableValue(oid[t], pools[t], skills[t]);
                        if (g > bestG) {
                            bestG = g;
                            bestT = t;
                        }
                    }
                    skills[bestT].or(inst.workerSkills[w]);
                }
                return skills;
            }
            return buildMarginalWorkerPartition(oid, pools, 2.5, 6);
        }

        private double totalPoolValue(int[] oid, List<Integer>[] pools, int[] workerOwner) {
            int k = oid.length;
            BitSet[] sk = new BitSet[k];
            for (int t = 0; t < k; t++) sk[t] = new BitSet();
            for (int w = 0; w < nW; w++) {
                if (workerOwner[w] >= 0) sk[workerOwner[w]].or(inst.workerSkills[w]);
            }
            double sum = 0;
            int nonEmpty = 0;
            for (int t = 0; t < k; t++) {
                double v = poolCoverableValue(oid[t], pools[t], sk[t]);
                if (v > 0) nonEmpty++;
                sum += v;
            }
            return sum + nonEmpty * 50.0;
        }

        private double poolCoverableValue(int officeId, List<Integer> pool, BitSet skills) {
            double sum = 0;
            int cap = inst.offices[officeId].maxSize;
            int taken = 0;
            for (int s : pool) {
                double v = inst.netVal[officeId][s];
                if (v <= 0) break;
                if (coveredBy(s, skills)) {
                    sum += v;
                    if (++taken >= cap) break;
                }
            }
            return sum;
        }

        private double partialSkillScore(int officeId, List<Integer> pool, BitSet skills) {
            double sum = 0;
            int limit = Math.min(pool.size(), inst.offices[officeId].maxSize * 2);
            for (int i = 0; i < limit; i++) {
                int s = pool.get(i);
                double v = inst.netVal[officeId][s];
                if (v <= 0) break;
                BitSet need = inst.sites[s].skillSet;
                int total = need.cardinality();
                if (total == 0) continue;
                int have = 0;
                for (int sk = need.nextSetBit(0); sk >= 0; sk = need.nextSetBit(sk + 1)) {
                    if (skills.get(sk)) have++;
                }
                sum += v * ((double) have / total);
            }
            return sum;
        }

        private boolean coveredBy(int siteId, BitSet skills) {
            BitSet need = inst.sites[siteId].skillSet;
            for (int s = need.nextSetBit(0); s >= 0; s = need.nextSetBit(s + 1)) {
                if (!skills.get(s)) return false;
            }
            return true;
        }

        private List<BitSet[]> deduplicatePolicies(List<BitSet[]> in) {
            List<BitSet[]> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (BitSet[] p : in) {
                String key = Arrays.toString(p);
                if (seen.add(key)) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /* ============================ ONLINE SURROGATE ML MODEL ============================ */

    static final class SurrogateML {
        static final int DIM = 22;
        static final int HID = 12;
        private static final double LR_LIN = 0.025;
        private static final double LR_MLP = 0.018;
        private static final double L2 = 1e-4;
        private static final double CLIP = 4.0;

        private final Instance inst;
        private final double[] singleObj;
        private final double[] wLin;
        private final double[] precDiag;
        private final double[][] w1;
        private final double[] b1;
        private final double[] w2;
        private final double[][] siteOfficeAffinity;
        private final double[][] officeSynergy;
        private final Map<String, Double> fastCache = new HashMap<>();

        private final double totalReward;
        private final double diagOffices;
        private final double diagSites;
        private double scale;
        private int predictedK = 2;
        private int[] champIds;
        private double champObj = -1e100;

        SurrogateML(Instance inst, double[] singleObj) {
            this.inst = inst;
            this.singleObj = singleObj;
            this.wLin = new double[]{
                    0.05, 0.35, -0.40, 0.82, -0.10, -0.10, -0.75, 0.45,
                    0.22, -0.05, 0.15, -0.12, -0.15, 0.65, 0.10, 0.18,
                    -0.55, -0.18, 0.55, -0.25, 0.20, -0.20
            };
            this.precDiag = new double[DIM];
            Arrays.fill(this.precDiag, 1.0);
            this.w1 = new double[HID][DIM];
            this.b1 = new double[HID];
            this.w2 = new double[HID];
            long seed = 0x2026ACE1L;
            for (int u = 0; u < HID; u++) {
                w2[u] = ((u & 1) == 0 ? 0.14 : -0.14) / Math.sqrt(HID);
                for (int d = 0; d < DIM; d++) {
                    seed ^= seed << 13;
                    seed ^= seed >>> 7;
                    seed ^= seed << 17;
                    double r = ((seed & 0xFFFFL) / 32768.0) - 1.0;
                    w1[u][d] = r * 0.08;
                }
            }
            this.siteOfficeAffinity = new double[inst.offices.length][inst.sites.length];
            this.officeSynergy = new double[inst.offices.length][inst.offices.length];
            double sRew = 0;
            for (Site s : inst.sites) sRew += s.reward;
            this.totalReward = Math.max(1.0, sRew);
            this.diagOffices = Math.max(1e-6, bboxDiag(inst.offices, null));
            this.diagSites = Math.max(1e-6, bboxDiag(null, inst.sites));
            this.scale = Math.max(10.0, totalReward / Math.max(1, inst.offices.length));
        }

        void setPredictedK(int k) {
            this.predictedK = Math.max(1, k);
        }

        void observeFast(int[] ids, double obj) {
            if (ids == null || ids.length == 0 || !Double.isFinite(obj)) return;
            fastCache.put(key(ids), obj);
            updateSynergy(ids, obj, 0.12);
            trainStep(ids, obj, 0.35);
        }

        void observeThorough(int[] ids, Solution sol) {
            if (ids == null || ids.length == 0 || sol == null) return;
            double obj = sol.offices.isEmpty() ? 0.0 : sol.objective;
            if (!Double.isFinite(obj)) return;
            fastCache.put(key(ids), Math.max(fastCache.getOrDefault(key(ids), -1e100), obj));
            updateSynergy(ids, obj, 0.28);
            trainStep(ids, obj, 1.0);

            if (!sol.offices.isEmpty() && obj > 0) {
                double weight = (obj >= champObj - 1e-6) ? 0.25 : 0.08;
                for (OpenedOffice o : sol.offices) {
                    for (int s : o.sites) {
                        siteOfficeAffinity[o.officeId][s] =
                                (1.0 - weight) * siteOfficeAffinity[o.officeId][s] + weight * 1.0;
                    }
                }
            }
        }

        private void updateSynergy(int[] ids, double obj, double lr) {
            if (ids.length < 2 || obj <= 0) return;
            double norm = obj / Math.max(10.0, Math.abs(champObj > 0 ? champObj : scale));
            for (int i = 0; i < ids.length; i++) {
                for (int j = i + 1; j < ids.length; j++) {
                    int a = ids[i], b = ids[j];
                    double v = (1.0 - lr) * officeSynergy[a][b] + lr * norm;
                    officeSynergy[a][b] = v;
                    officeSynergy[b][a] = v;
                }
            }
        }

        double affinityBonus(int officeId, int siteId) {
            return siteOfficeAffinity[officeId][siteId];
        }

        private void trainStep(int[] ids, double obj, double weight) {
            double[] x = features(ids);
            for (int d = 0; d < DIM; d++) {
                precDiag[d] += weight * x[d] * x[d];
            }
            if (Math.abs(obj) > 1.0) {
                scale = 0.85 * scale + 0.15 * Math.max(10.0, Math.abs(obj));
            }
            double target = obj / scale;
            double[] h = new double[HID];
            double pred = forward(x, h);
            double rawErr = pred - target;
            double grad = Math.max(-1.5, Math.min(1.5, rawErr)) * weight;
            backward(x, h, grad);

            if (champIds != null && Math.abs(obj - champObj) > 1e-6) {
                if (obj > champObj) {
                    pairwiseStep(ids, champIds, 0.5 * weight);
                } else {
                    pairwiseStep(champIds, ids, 0.5 * weight);
                }
            }
            if (obj > champObj) {
                champObj = obj;
                champIds = ids.clone();
                Arrays.sort(champIds);
            }
        }

        private void pairwiseStep(int[] better, int[] worse, double weight) {
            double[] xb = features(better);
            double[] xw = features(worse);
            double[] hb = new double[HID];
            double[] hw = new double[HID];
            double sb = forward(xb, hb);
            double sw = forward(xw, hw);
            double diff = Math.max(-15.0, Math.min(15.0, sb - sw));
            double sigma = 1.0 / (1.0 + Math.exp(-diff));
            double g = (1.0 - sigma) * weight;
            backward(xb, hb, -g);
            backward(xw, hw, g);
        }

        private double forward(double[] x, double[] h) {
            double y = 0.0;
            for (int d = 0; d < DIM; d++) {
                y += wLin[d] * x[d];
            }
            for (int u = 0; u < HID; u++) {
                double z = b1[u];
                for (int d = 0; d < DIM; d++) {
                    z += w1[u][d] * x[d];
                }
                h[u] = z >= 0.0 ? z : 0.1 * z;
                y += w2[u] * h[u];
            }
            return y;
        }

        private void backward(double[] x, double[] h, double grad) {
            if (!Double.isFinite(grad)) return;
            for (int d = 0; d < DIM; d++) {
                wLin[d] = clip(wLin[d] - LR_LIN * (grad * x[d] + L2 * wLin[d]));
            }
            for (int u = 0; u < HID; u++) {
                double oldW2 = w2[u];
                w2[u] = clip(w2[u] - LR_MLP * (grad * h[u] + L2 * w2[u]));
                double dh = grad * oldW2 * (h[u] >= 0.0 ? 1.0 : 0.1);
                b1[u] = clip(b1[u] - LR_MLP * dh);
                for (int d = 0; d < DIM; d++) {
                    w1[u][d] = clip(w1[u][d] - LR_MLP * (dh * x[d] + L2 * w1[u][d]));
                }
            }
        }

        double predictUCB(int[] ids, double ucbBeta) {
            double[] x = features(ids);
            double[] h = new double[HID];
            double mean = forward(x, h) * scale;
            Double cached = fastCache.get(key(ids));
            if (cached != null) {
                mean = 0.65 * cached + 0.35 * mean;
            }
            if (ids.length >= 2) {
                double syn = 0.0;
                int pairs = 0;
                for (int i = 0; i < ids.length; i++) {
                    for (int j = i + 1; j < ids.length; j++) {
                        syn += officeSynergy[ids[i]][ids[j]];
                        pairs++;
                    }
                }
                mean += 0.08 * scale * (syn / Math.max(1, pairs));
            }
            if (ucbBeta <= 0.0) return mean;
            double var = 0.0;
            for (int d = 0; d < DIM; d++) {
                var += (x[d] * x[d]) / precDiag[d];
            }
            return mean + ucbBeta * scale * Math.sqrt(var / DIM);
        }

        double predict(int[] ids) {
            return predictUCB(ids, 0.0);
        }

        double[] features(int[] ids) {
            int k = ids.length;
            int nOff = inst.offices.length;
            int nSites = inst.sites.length;

            double sumS = 0.0, minS = Double.POSITIVE_INFINITY, maxS = Double.NEGATIVE_INFINITY;
            double sumCost = 0.0, sumCap = 0.0;
            int minCap = Integer.MAX_VALUE;

            for (int id : ids) {
                double s = Math.max(0.0, singleObj[id]);
                sumS += s;
                minS = Math.min(minS, s);
                maxS = Math.max(maxS, s);
                sumCost += inst.offices[id].cost;
                int cap = inst.offices[id].maxSize;
                sumCap += cap;
                minCap = Math.min(minCap, cap);
            }

            double minPair = 0.0, meanPair = 0.0;
            if (k >= 2) {
                minPair = Double.POSITIVE_INFINITY;
                int pairs = 0;
                for (int i = 0; i < k; i++) {
                    for (int j = i + 1; j < k; j++) {
                        double d = inst.officeEuclid[ids[i]][ids[j]];
                        minPair = Math.min(minPair, d);
                        meanPair += d;
                        pairs++;
                    }
                }
                meanPair /= Math.max(1, pairs);
            }

            int[] load = new int[k];
            int[] tasksLoad = new int[k];
            double voronoiSum = 0.0;
            int roadMismatch = 0;
            int positiveSites = 0;
            for (int s = 0; s < nSites; s++) {
                if (!inst.feasibleSite[s]) continue;
                int bestEuT = 0;
                double bestEu = Double.POSITIVE_INFINITY;
                int bestRdT = 0;
                double bestRd = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    int oid = ids[t];
                    double eu = inst.euclidDist2[oid][s];
                    if (eu < bestEu) {
                        bestEu = eu;
                        bestEuT = t;
                    }
                    double rd = inst.distances[oid][s];
                    if (rd < bestRd) {
                        bestRd = rd;
                        bestRdT = t;
                    }
                }
                if (bestEuT != bestRdT) roadMismatch++;
                double v = inst.netVal[ids[bestEuT]][s];
                if (v > 0 && load[bestEuT] < inst.offices[ids[bestEuT]].maxSize) {
                    voronoiSum += v;
                    load[bestEuT]++;
                    tasksLoad[bestEuT] += inst.siteTaskCount[s];
                    positiveSites++;
                }
            }

            int minT = Integer.MAX_VALUE, maxT = 0;
            int minL = Integer.MAX_VALUE, maxL = 0;
            for (int t = 0; t < k; t++) {
                minT = Math.min(minT, tasksLoad[t]);
                maxT = Math.max(maxT, tasksLoad[t]);
                minL = Math.min(minL, load[t]);
                maxL = Math.max(maxL, load[t]);
            }
            double voronoiNet = voronoiSum - sumCost - inst.balanceCoefficient * (maxT - minT);
            double effCapBound = k * (minCap + inst.maxDifference);
            Double cached = fastCache.get(key(ids));
            double meanRw = totalReward / Math.max(1, nSites);

            double[] x = new double[DIM];
            x[0] = 1.0;
            x[1] = k / (double) Math.max(1, nOff);
            x[2] = x[1] * x[1];
            x[3] = sumS / (scale * Math.max(1, k));
            x[4] = (minS == Double.POSITIVE_INFINITY ? 0.0 : minS) / scale;
            x[5] = (maxS == Double.NEGATIVE_INFINITY ? 0.0 : maxS) / scale;
            x[6] = sumCost / (totalReward + 1.0);
            x[7] = sumCap / Math.max(1.0, nSites);
            x[8] = minPair / diagOffices;
            x[9] = meanPair / diagOffices;
            x[10] = effCapBound / Math.max(1.0, sumCap);
            x[11] = Math.tanh(inst.balanceCoefficient / (meanRw + 1e-6));
            x[12] = Math.abs(k - predictedK) / (double) Math.max(1, nOff);
            x[13] = cached == null ? (voronoiNet / scale) : (cached / scale);
            x[14] = 1.0 - Math.min(1.0, sumCap / Math.max(1.0, nSites));
            x[15] = minCap / Math.max(1.0, sumCap / Math.max(1, k));
            x[16] = k < 2 ? 0.0 : Math.max(0.0, 0.20 - minPair / Math.max(diagOffices, diagSites));
            x[17] = roadMismatch / (double) Math.max(1, nSites);
            x[18] = voronoiNet / scale;
            x[19] = (maxL - minL) / (double) Math.max(1, inst.maxDifference + 1);
            x[20] = positiveSites / Math.max(1.0, sumCap);
            x[21] = inst.balanceCoefficient * (maxT - minT) / (totalReward + 1.0);
            return x;
        }

        private static double clip(double v) {
            return Math.max(-CLIP, Math.min(CLIP, v));
        }

        private static String key(int[] ids) {
            int[] c = ids.clone();
            Arrays.sort(c);
            return Arrays.toString(c);
        }

        private static double bboxDiag(Office[] offices, Site[] sites) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            if (offices != null) {
                for (Office o : offices) {
                    minX = Math.min(minX, o.pos.x);
                    maxX = Math.max(maxX, o.pos.x);
                    minY = Math.min(minY, o.pos.y);
                    maxY = Math.max(maxY, o.pos.y);
                }
            }
            if (sites != null) {
                for (Site s : sites) {
                    minX = Math.min(minX, s.pos.x);
                    maxX = Math.max(maxX, s.pos.x);
                    minY = Math.min(minY, s.pos.y);
                    maxY = Math.max(maxY, s.pos.y);
                }
            }
            return Math.hypot(maxX - minX, maxY - minY);
        }
    }

    /* ============================ CORE SOLVER ============================ */

    static final class Solver {
        private static final long RESERVE_THOROUGH_NS = 15_000_000_000L;
        private static final int ELITE_POOL_CAP = 64;

        final Instance inst;
        final FastGeo geo;
        final WorkerSolver workerSolver;
        final SurrogateML ml;
        final long deadlineNanos;
        final long reserveNs;
        final double[] officeSingle;

        private Solution best = Solution.empty();
        private long rng = 20260519L;
        private final Set<String> evaluatedFast = new HashSet<>();
        private final Set<String> evaluatedThorough = new HashSet<>();
        private final List<EliteSubset> elites = new ArrayList<>();
        private final double[] bestObjByK;
        private final int[][] bestIdsByK;

        static final class EliteSubset {
            final int[] ids;
            double obj;

            EliteSubset(int[] ids, double obj) {
                this.ids = ids;
                this.obj = obj;
            }
        }

        Solver(Instance inst, long deadlineNanos) {
            this.inst = inst;
            this.geo = new FastGeo(inst);
            this.workerSolver = new WorkerSolver(inst);
            this.deadlineNanos = deadlineNanos;
            long budget = Math.max(0L, deadlineNanos - System.nanoTime());
            this.reserveNs = Math.min(RESERVE_THOROUGH_NS, Math.max(budget / 3, 1_000_000_000L));
            this.officeSingle = new double[inst.offices.length];
            Arrays.fill(this.officeSingle, -1e100);
            this.bestObjByK = new double[inst.offices.length + 1];
            Arrays.fill(this.bestObjByK, -1e100);
            this.bestIdsByK = new int[inst.offices.length + 1][];
            this.ml = new SurrogateML(inst, this.officeSingle);
        }

        boolean timeLeft() {
            return System.nanoTime() < deadlineNanos;
        }

        boolean searchTimeLeft() {
            return System.nanoTime() + reserveNs < deadlineNanos;
        }

        int randInt(int n) {
            if (n <= 1) return 0;
            rng ^= rng << 13;
            rng ^= rng >>> 7;
            rng ^= rng << 17;
            return (int) ((rng & Long.MAX_VALUE) % n);
        }

        boolean isTiny() {
            return inst.offices.length <= 6 && inst.sites.length <= 85;
        }

        boolean isCompact() {
            return inst.offices.length <= 9 && inst.sites.length <= 250;
        }

        Solution solve() {
            int nOff = inst.offices.length;
            if (nOff == 0 || inst.sites.length == 0 || inst.workers.length == 0) {
                return Solution.empty();
            }

            Integer[] rank = new Integer[nOff];
            for (int o = 0; o < nOff; o++) {
                rank[o] = o;
                if (timeLeft()) {
                    Solution sol = solveSingleOfficeExact(o);
                    officeSingle[o] = sol.objective;
                    evaluatedFast.add(Arrays.toString(new int[]{o}));
                    evaluatedThorough.add(Arrays.toString(new int[]{o}));
                    ml.observeThorough(new int[]{o}, sol);
                    consider(sol);
                }
            }
            Arrays.sort(rank, (a, b) -> Double.compare(officeSingle[b], officeSingle[a]));

            int maxK = Math.min(nOff, inst.workers.length);
            int predictedK = estimateOptimalK(rank, maxK);
            ml.setPredictedK(predictedK);

            long totalMasks = (nOff <= 12) ? (1L << nOff) : Long.MAX_VALUE;
            if (nOff <= 7 && inst.sites.length <= 130) {
                enumerateAllSubsets(maxK, false);
                enumerateAllSubsetsRanked(maxK, true);
            } else if (totalMasks <= 512 && inst.sites.length <= 260) {
                enumerateAllSubsets(maxK, false);
                localSearchOfficeSubsets(rank, maxK);
            } else {
                searchByCardinality(rank, predictedK, maxK);
                localSearchOfficeSubsets(rank, maxK);
                banditGuidedSubsetSearch(rank, predictedK, maxK);
            }

            refineElitesThorough(rank, maxK);

            return best;
        }

        /**
         * Exact O(N_S log N_S) closed-form solver for a single opened office (k = 1).
         */
        private Solution solveSingleOfficeExact(int officeId) {
            int cap = inst.offices[officeId].maxSize;
            if (cap <= 0) return Solution.empty();
            int[] ranked = rankSitesForOffice(officeId);
            List<Integer> chosen = new ArrayList<>(Math.min(cap, ranked.length));
            for (int s : ranked) {
                if (chosen.size() >= cap) break;
                if (!inst.feasibleSite[s]) continue;
                if (inst.netVal[officeId][s] <= 0.0) break;
                chosen.add(s);
            }
            if (chosen.isEmpty()) return Solution.empty();
            BitSet[] need = new BitSet[]{skillsOf(chosen)};
            List<Integer>[] workers = workerSolver.allocate(need);
            if (workers == null) {
                List<Integer> allW = new ArrayList<>(inst.workers.length);
                for (int w = 0; w < inst.workers.length; w++) allW.add(w);
                workers = new List[]{allW};
            }
            List<OpenedOffice> opened = List.of(new OpenedOffice(officeId, chosen, workers[0]));
            double obj = Solution.objectiveOf(inst, opened);
            return obj > 0.0 ? new Solution(opened, obj) : Solution.empty();
        }

        private int estimateOptimalK(Integer[] rank, int maxK) {
            int cap = Math.min(maxK, 7);
            int bestK = 1;
            double bestSc = best.objective;
            for (int k = 2; k <= cap && searchTimeLeft(); k++) {
                int[] probe = new int[k];
                for (int i = 0; i < k; i++) probe[i] = rank[i];
                Solution sol = evaluateFast(probe);
                if (sol.objective > bestSc + 1e-6) {
                    bestSc = sol.objective;
                    bestK = k;
                }
                int[] spread = pickSpatiallySpread(k, rank);
                if (spread != null) {
                    Solution sSol = evaluateFast(spread);
                    if (sSol.objective > bestSc + 1e-6) {
                        bestSc = sSol.objective;
                        bestK = k;
                    }
                }
            }
            return bestK;
        }

        private void enumerateAllSubsets(int maxK, boolean thorough) {
            int nOff = inst.offices.length;
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit && (thorough ? timeLeft() : searchTimeLeft()); mask++) {
                int k = Integer.bitCount(mask);
                if (k > maxK) continue;
                int[] ids = maskToIds(mask, k, nOff);
                if (thorough) evaluateThorough(ids);
                else evaluateFast(ids);
            }
        }

        private void enumerateAllSubsetsRanked(int maxK, boolean thorough) {
            int nOff = inst.offices.length;
            int limit = 1 << nOff;
            List<int[]> all = new ArrayList<>(limit);
            for (int mask = 1; mask < limit; mask++) {
                int k = Integer.bitCount(mask);
                if (k <= maxK) all.add(maskToIds(mask, k, nOff));
            }
            all.sort((a, b) -> Double.compare(ml.predictUCB(b, 0.15), ml.predictUCB(a, 0.15)));
            for (int[] ids : all) {
                if (!timeLeft()) break;
                if (thorough) evaluateThorough(ids);
                else evaluateFast(ids);
            }
        }

        private int[] maskToIds(int mask, int k, int nOff) {
            int[] ids = new int[k];
            int p = 0;
            for (int o = 0; o < nOff; o++) {
                if ((mask & (1 << o)) != 0) ids[p++] = o;
            }
            return ids;
        }

        private void searchByCardinality(Integer[] rank, int predictedK, int maxK) {
            int[] orderK = new int[maxK];
            int ptr = 0;
            for (int k : new int[]{2, 3, 4, 5}) {
                if (k <= maxK) orderK[ptr++] = k;
            }
            for (int k = 2; k <= maxK; k++) {
                boolean seen = false;
                for (int i = 0; i < ptr; i++) if (orderK[i] == k) seen = true;
                if (!seen) orderK[ptr++] = k;
            }

            for (int idx = 0; idx < ptr && searchTimeLeft(); idx++) {
                int k = orderK[idx];
                int nOff = inst.offices.length;
                long combos = binom(nOff, k);
                long enumCap = (k == 2) ? 2500L : (k == 3 ? 400L : 180L);
                if (combos <= enumCap) {
                    int[] buf = new int[k];
                    enumCombos(0, 0, k, buf);
                } else {
                    int poolSize = Math.min(nOff, Math.max(k + 4, 12));
                    List<int[]> cands = new ArrayList<>();
                    int[] spread = pickSpatiallySpread(k, rank);
                    if (spread != null) cands.add(spread);
                    int[] buf = new int[k];
                    collectTopRankCombos(rank, Math.min(nOff, k + 3), 0, 0, k, buf, cands, 90);
                    for (int s = 0; s < 160; s++) {
                        int[] rnd = sampleSubset(rank, k, poolSize);
                        if (rnd != null) cands.add(rnd);
                    }
                    cands.sort((a, b) -> Double.compare(ml.predictUCB(b, 0.25), ml.predictUCB(a, 0.25)));
                    int evalCap = (k <= 3) ? 90 : (k == 4 ? 120 : 70);
                    int done = 0;
                    for (int[] c : cands) {
                        if (!searchTimeLeft() || done >= evalCap) break;
                        Arrays.sort(c);
                        if (!evaluatedFast.contains(Arrays.toString(c))) {
                            evaluateFast(c);
                            done++;
                        }
                    }
                }
            }
        }

        private void enumCombos(int start, int filled, int k, int[] buf) {
            if (!searchTimeLeft()) return;
            if (filled == k) {
                evaluateFast(buf);
                return;
            }
            int nOff = inst.offices.length;
            for (int i = start; i <= nOff - (k - filled); i++) {
                buf[filled] = i;
                enumCombos(i + 1, filled + 1, k, buf);
                if (!searchTimeLeft()) return;
            }
        }

        private void collectTopRankCombos(Integer[] rank, int pref, int start, int filled, int k,
                                          int[] buf, List<int[]> out, int limit) {
            if (out.size() >= limit) return;
            if (filled == k) {
                int[] c = buf.clone();
                Arrays.sort(c);
                out.add(c);
                return;
            }
            for (int i = start; i <= pref - (k - filled) && out.size() < limit; i++) {
                buf[filled] = rank[i];
                collectTopRankCombos(rank, pref, i + 1, filled + 1, k, buf, out, limit);
            }
        }

        private int[] pickSpatiallySpread(int k, Integer[] rank) {
            int nOff = inst.offices.length;
            if (k > nOff) return null;
            boolean[] used = new boolean[nOff];
            int[] ids = new int[k];
            ids[0] = rank[0];
            used[rank[0]] = true;
            for (int filled = 1; filled < k; filled++) {
                int bestO = -1;
                double bestScore = -1e100;
                int bound = Math.min(nOff, Math.max(k * 4, 14));
                for (int r = 0; r < bound; r++) {
                    int o = rank[r];
                    if (used[o] || inst.offices[o].maxSize < 1) continue;
                    double minD = Double.POSITIVE_INFINITY;
                    for (int i = 0; i < filled; i++) {
                        minD = Math.min(minD, inst.officeEuclid[o][ids[i]]);
                    }
                    double sc = minD * (1.0 + 0.3 * Math.max(0.0, officeSingle[o]) / (Math.abs(officeSingle[rank[0]]) + 1.0));
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestO = o;
                    }
                }
                if (bestO < 0) return null;
                used[bestO] = true;
                ids[filled] = bestO;
            }
            Arrays.sort(ids);
            return ids;
        }

        private void localSearchOfficeSubsets(Integer[] rank, int maxK) {
            int nOff = inst.offices.length;
            int rounds = 0;
            while (rounds++ < 14 && searchTimeLeft() && !best.offices.isEmpty()) {
                double before = best.objective;
                int[] cur = bestIds();
                if (cur.length >= 2) {
                    for (int i = 0; i < cur.length && searchTimeLeft(); i++) {
                        evaluateFast(removeAt(cur, i));
                    }
                }
                if (cur.length < maxK) {
                    boolean[] in = new boolean[nOff];
                    for (int id : cur) in[id] = true;
                    int bound = Math.min(nOff, Math.max(14, cur.length * 5));
                    for (int r = 0; r < bound && searchTimeLeft(); r++) {
                        int neu = rank[r];
                        if (!in[neu] && inst.offices[neu].maxSize >= 1) {
                            evaluateFast(appendId(cur, neu));
                        }
                    }
                }
                boolean[] in = new boolean[nOff];
                for (int id : cur) in[id] = true;
                int bound = Math.min(nOff, Math.max(14, cur.length * 4));
                for (int r = 0; r < bound && searchTimeLeft(); r++) {
                    int neu = rank[r];
                    if (in[neu] || inst.offices[neu].maxSize < 1) continue;
                    for (int i = 0; i < cur.length && searchTimeLeft(); i++) {
                        int[] next = cur.clone();
                        next[i] = neu;
                        evaluateFast(next);
                    }
                }
                // 2-Swap neighborhood guided by SurrogateML when 1-swap plateaus
                if (best.objective <= before + 1e-9 && cur.length >= 2 && nOff >= cur.length + 2) {
                    List<int[]> twoSwaps = new ArrayList<>();
                    int topOut = Math.min(nOff, 10);
                    for (int i = 0; i < cur.length; i++) {
                        for (int j = i + 1; j < cur.length; j++) {
                            for (int r1 = 0; r1 < topOut; r1++) {
                                int o1 = rank[r1];
                                if (in[o1] || inst.offices[o1].maxSize < 1) continue;
                                for (int r2 = r1 + 1; r2 < topOut; r2++) {
                                    int o2 = rank[r2];
                                    if (in[o2] || inst.offices[o2].maxSize < 1) continue;
                                    int[] next = cur.clone();
                                    next[i] = o1;
                                    next[j] = o2;
                                    Arrays.sort(next);
                                    if (!evaluatedFast.contains(Arrays.toString(next))) {
                                        twoSwaps.add(next);
                                    }
                                }
                            }
                        }
                    }
                    twoSwaps.sort((a, b) -> Double.compare(ml.predictUCB(b, 0.20), ml.predictUCB(a, 0.20)));
                    for (int m = 0; m < Math.min(12, twoSwaps.size()) && searchTimeLeft(); m++) {
                        evaluateFast(twoSwaps.get(m));
                        if (best.objective > before + 1e-9) break;
                    }
                }
                if (best.objective <= before + 1e-9) break;
            }
        }

        private void banditGuidedSubsetSearch(Integer[] rank, int predictedK, int maxK) {
            if (maxK < 2) return;
            int nOff = inst.offices.length;
            int miss = 0;
            while (searchTimeLeft() && miss < 180) {
                int baseK = randInt(2) == 0 ? Math.max(2, predictedK) : Math.max(2, best.offices.size());
                int k = Math.max(2, Math.min(maxK, baseK + randInt(3) - 1));
                int pref = Math.min(nOff, Math.max(k + 4, 12));

                int[] bestCand = null;
                double bestUCB = -1e100;
                for (int t = 0; t < 8; t++) {
                    int[] c = (t == 0 && !elites.isEmpty())
                            ? mutateSubset(elites.get(randInt(Math.min(elites.size(), 8))).ids, rank, maxK)
                            : sampleSubset(rank, k, pref);
                    if (c == null) continue;
                    Arrays.sort(c);
                    if (evaluatedFast.contains(Arrays.toString(c))) continue;
                    double ucb = ml.predictUCB(c, 0.30);
                    if (ucb > bestUCB) {
                        bestUCB = ucb;
                        bestCand = c;
                    }
                }
                if (bestCand == null) {
                    miss++;
                    continue;
                }
                miss = 0;
                double prevBest = best.objective;
                evaluateFast(bestCand);
                if (best.objective > prevBest + 1e-6) {
                    localSearchOfficeSubsets(rank, maxK);
                }
            }
        }

        private int[] mutateSubset(int[] base, Integer[] rank, int maxK) {
            int nOff = inst.offices.length;
            if (base == null || base.length == 0) return sampleSubset(rank, 2, nOff);
            boolean[] in = new boolean[nOff];
            for (int id : base) in[id] = true;
            int op = randInt(3);
            if (op == 0 && base.length >= 2) {
                return removeAt(base, randInt(base.length));
            } else if (op == 1 && base.length < maxK) {
                for (int g = 0; g < 16; g++) {
                    int o = rank[randInt(Math.min(nOff, base.length * 4 + 4))];
                    if (!in[o] && inst.offices[o].maxSize >= 1) return appendId(base, o);
                }
            }
            for (int g = 0; g < 16; g++) {
                int o = rank[randInt(Math.min(nOff, base.length * 4 + 4))];
                if (!in[o] && inst.offices[o].maxSize >= 1) {
                    int[] next = base.clone();
                    next[randInt(next.length)] = o;
                    Arrays.sort(next);
                    return next;
                }
            }
            return null;
        }

        private int[] sampleSubset(Integer[] rank, int k, int pref) {
            int nOff = inst.offices.length;
            if (k <= 0 || k > nOff) return null;
            boolean[] used = new boolean[nOff];
            int[] pick = new int[k];
            int filled = 0, guard = 0;
            while (filled < k && guard++ < nOff * 8) {
                int o = (randInt(4) == 0) ? randInt(nOff) : rank[randInt(Math.max(1, Math.min(pref, nOff)))];
                if (!used[o] && inst.offices[o].maxSize >= 1) {
                    used[o] = true;
                    pick[filled++] = o;
                }
            }
            if (filled < k) return null;
            Arrays.sort(pick);
            return pick;
        }

        private void refineElitesThorough(Integer[] rank, int maxK) {
            List<int[]> queue = new ArrayList<>();
            addUnique(queue, bestIds());
            elites.sort((a, b) -> Double.compare(b.obj, a.obj));
            int capCheap = isCompact() ? 16 : 7;
            for (int i = 0; i < Math.min(capCheap, elites.size()); i++) {
                addUnique(queue, elites.get(i).ids);
            }
            List<EliteSubset> byML = new ArrayList<>(elites);
            byML.sort((a, b) -> Double.compare(ml.predict(b.ids), ml.predict(a.ids)));
            int capML = isCompact() ? 16 : 7;
            for (int i = 0; i < Math.min(capML, byML.size()); i++) {
                addUnique(queue, byML.get(i).ids);
            }
            for (int k = 1; k < bestIdsByK.length; k++) {
                if (bestIdsByK[k] != null) addUnique(queue, bestIdsByK[k]);
            }

            for (int[] ids : queue) {
                if (!timeLeft()) break;
                evaluateThorough(ids);
            }

            if (!best.offices.isEmpty() && timeLeft()) {
                int[] cur = bestIds();
                boolean[] in = new boolean[inst.offices.length];
                for (int id : cur) in[id] = true;
                int bound = Math.min(inst.offices.length, Math.max(10, cur.length * 3));
                int extra = 0;
                int maxExtra = isCompact() ? 10 : 4;
                for (int r = 0; r < bound && extra < maxExtra && timeLeft(); r++) {
                    int neu = rank[r];
                    if (in[neu] || inst.offices[neu].maxSize < 1) continue;
                    for (int i = 0; i < cur.length && extra < maxExtra && timeLeft(); i++) {
                        int[] next = cur.clone();
                        next[i] = neu;
                        Arrays.sort(next);
                        if (!evaluatedThorough.contains(Arrays.toString(next))) {
                            evaluateThorough(next);
                            extra++;
                        }
                    }
                }
            }
        }

        private void addUnique(List<int[]> list, int[] ids) {
            if (ids == null || ids.length == 0) return;
            int[] c = ids.clone();
            Arrays.sort(c);
            for (int[] e : list) {
                if (Arrays.equals(e, c)) return;
            }
            list.add(c);
        }

        private Solution evaluateFast(int[] ids) {
            if (!timeLeft() || ids == null || ids.length == 0) return Solution.empty();
            int[] copy = ids.clone();
            Arrays.sort(copy);
            String key = Arrays.toString(copy);
            if (!evaluatedFast.add(key)) {
                return Solution.empty();
            }
            Solution sol = solveSubset(copy, false);
            ml.observeFast(copy, sol.offices.isEmpty() ? 0.0 : sol.objective);
            consider(sol);
            return sol;
        }

        private Solution evaluateThorough(int[] ids) {
            if (!timeLeft() || ids == null || ids.length == 0) return Solution.empty();
            int[] copy = ids.clone();
            Arrays.sort(copy);
            String key = Arrays.toString(copy);
            if (!evaluatedThorough.add(key)) {
                return Solution.empty();
            }
            Solution sol = solveSubset(copy, true);
            ml.observeThorough(copy, sol);
            consider(sol);
            return sol;
        }

        private void consider(Solution sol) {
            if (sol == null || sol.offices.isEmpty()) return;
            int[] ids = idsOf(sol);
            rememberElite(ids, sol.objective);
            if (sol.objective > best.objective + 1e-9 && Constraints.valid(inst, sol.offices)) {
                best = sol;
            }
        }

        private void rememberElite(int[] ids, double obj) {
            if (ids == null || ids.length == 0 || obj < -1e90) return;
            int[] copy = ids.clone();
            Arrays.sort(copy);
            int k = copy.length;
            if (k < bestObjByK.length && obj > bestObjByK[k] + 1e-9) {
                bestObjByK[k] = obj;
                bestIdsByK[k] = copy;
            }
            for (EliteSubset e : elites) {
                if (Arrays.equals(e.ids, copy)) {
                    if (obj > e.obj) e.obj = obj;
                    return;
                }
            }
            elites.add(new EliteSubset(copy, obj));
            elites.sort((a, b) -> Double.compare(b.obj, a.obj));
            while (elites.size() > ELITE_POOL_CAP) {
                elites.remove(elites.size() - 1);
            }
        }

        private int[] bestIds() {
            return idsOf(best);
        }

        private int[] idsOf(Solution sol) {
            int[] ids = new int[sol.offices.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = sol.offices.get(i).officeId;
            Arrays.sort(ids);
            return ids;
        }

        private int[] removeAt(int[] ids, int skip) {
            int[] next = new int[ids.length - 1];
            int p = 0;
            for (int i = 0; i < ids.length; i++) if (i != skip) next[p++] = ids[i];
            return next;
        }

        private int[] appendId(int[] ids, int extra) {
            int[] next = Arrays.copyOf(ids, ids.length + 1);
            next[ids.length] = extra;
            Arrays.sort(next);
            return next;
        }

        /* ==================== MULTI-STRATEGY SUBSET SOLVER (WITH EXACT 2D CONTAINER DP) ==================== */

        @SuppressWarnings("unchecked")
        Solution solveSubset(int[] officeIds, boolean thorough) {
            if (!timeLeft()) return Solution.empty();
            int k = officeIds.length;
            if (k == 0) return Solution.empty();
            if (k == 1) return solveSingleOfficeExact(officeIds[0]);
            int[] oid = officeIds.clone();

            List<Integer>[] powerPoolCount = buildPowerDiagramPools(oid, 0);
            List<BitSet[]> skillPolicies = workerSolver.generateWorkerPolicies(oid, powerPoolCount, thorough);

            if (!thorough) {
                BitSet[] skills = skillPolicies.get(0);
                Solution s0 = buildContainerDPAndOptimize(oid, powerPoolCount, skills, false, false);
                Solution s1 = buildVoronoiAndGrow(oid, powerPoolCount, skills, false, false);
                if (s1.objective > s0.objective) s0 = s1;
                if (inst.maxDifference <= 2) {
                    Solution s2 = buildTargetWindowBalanced(oid, powerPoolCount, skills, false, false);
                    if (s2.objective > s0.objective) s0 = s2;
                }
                return s0;
            }

            List<Integer>[] powerPoolVal = buildPowerDiagramPools(oid, 1);
            List<Integer>[] powerPoolTask = (inst.balanceCoefficient > 0.5) ? buildPowerDiagramPools(oid, 2) : null;
            List<Integer>[] euclidPool = buildVoronoiPools(oid, false);
            List<Integer>[] roadPool = buildVoronoiPools(oid, true);

            Solution bestSub = Solution.empty();

            for (int pIdx = 0; pIdx < skillPolicies.size() && timeLeft(); pIdx++) {
                BitSet[] skills = skillPolicies.get(pIdx);
                boolean isFullRelaxation = (pIdx == 1 && k > 1);

                if (!isFullRelaxation) {
                    Solution sdp0 = buildContainerDPAndOptimize(oid, powerPoolCount, skills, false, true);
                    if (sdp0.objective > bestSub.objective) bestSub = sdp0;

                    Solution sdp1 = buildContainerDPAndOptimize(oid, powerPoolVal, skills, false, true);
                    if (sdp1.objective > bestSub.objective) bestSub = sdp1;

                    if (powerPoolTask != null) {
                        Solution sdp2 = buildContainerDPAndOptimize(oid, powerPoolTask, skills, false, true);
                        if (sdp2.objective > bestSub.objective) bestSub = sdp2;
                    }
                }

                Solution s0 = buildVoronoiAndGrow(oid, powerPoolCount, skills, isFullRelaxation, true);
                if (s0.objective > bestSub.objective) bestSub = s0;

                Solution s1 = buildVoronoiAndGrow(oid, euclidPool, skills, isFullRelaxation, true);
                if (s1.objective > bestSub.objective) bestSub = s1;

                Solution s2 = buildTargetWindowBalanced(oid, powerPoolCount, skills, isFullRelaxation, true);
                if (s2.objective > bestSub.objective) bestSub = s2;

                Solution s3 = buildIntrusionAwareHullGrow(oid, skills, isFullRelaxation, 0.0);
                if (s3.objective > bestSub.objective) bestSub = s3;
                if (inst.balanceCoefficient > 1.0) {
                    Solution s4 = buildIntrusionAwareHullGrow(oid, skills, isFullRelaxation, 0.65);
                    if (s4.objective > bestSub.objective) bestSub = s4;
                }
                if (roadPool != null && pIdx <= 1) {
                    Solution s5 = buildVoronoiAndGrow(oid, roadPool, skills, isFullRelaxation, true);
                    if (s5.objective > bestSub.objective) bestSub = s5;
                }
            }

            if (!bestSub.offices.isEmpty() && timeLeft()) {
                bestSub = ruinAndRecreateALNS(oid, bestSub);
            }

            return bestSub;
        }

        /**
         * Two-Stage Convex Container + Exact 2D Knapsack & Task-Envelope DP + Iterative Container Tightening.
         *
         * Stage 1: Grow maximal mutually-disjoint convex container pools C_t around each office t
         *          (up to 56 sites per office, including top positive sites and high-ranking filler candidates).
         * Stage 2: Because H_t = conv({o_t} U C_t) are pairwise disjoint, ANY subset S_t <= C_t has
         *          conv({o_t} U S_t) <= H_t and is 100% conflict-free! Solve the exact 2D Knapsack DP
         *          dp[t][n][tau] + Task-Range Envelope optimization across all k offices simultaneously.
         * Stage 3 (Iterative Container Tightening): Once S_t* is selected by exact DP, the actual hulls
         *          conv({o_t} U S_t*) shrink compared to H_t, freeing space between offices. Expand C_t
         *          into the newly freed space and re-run Exact 2D DP before 10-neighborhood local search!
         */
        @SuppressWarnings("unchecked")
        private Solution buildContainerDPAndOptimize(int[] oid, List<Integer>[] basePools, BitSet[] baseSkills,
                                                     boolean dynamicWorkers, boolean thorough) {
            int k = oid.length;
            BitSet[] skills = cloneBitSets(baseSkills);
            List<Integer>[] containers = new List[k];
            boolean[] inContainer = new boolean[inst.sites.length];
            int[][] hulls = new int[k][inst.sites.length + 2];
            int[] hLens = new int[k];
            long[][] covMasks = new long[k][geo.words];

            for (int t = 0; t < k; t++) {
                containers[t] = new ArrayList<>();
                hulls[t][0] = geo.nS + oid[t];
                hLens[t] = 1;
                geo.computeCoverMask(hulls[t], 1, covMasks[t]);
            }

            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            // Pass 1: Add all geometrically compatible sites from the Power Diagram cell (up to 48 per office)
            for (int t = 0; t < k; t++) {
                geo.buildUnionOther(covMasks, k, t, unionOther);
                int maxContainer = Math.min(48, inst.offices[oid[t]].maxSize + 14);
                int negAdded = 0;
                for (int s : basePools[t]) {
                    if (containers[t].size() >= maxContainer) break;
                    if (inContainer[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                    double v = inst.netVal[oid[t]][s];
                    if (v <= 0 && negAdded >= 4) continue;
                    int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                    if (newHLen < 0) continue;
                    containers[t].add(s);
                    inContainer[s] = true;
                    if (v <= 0) negAdded++;
                    System.arraycopy(candHull, 0, hulls[t], 0, newHLen);
                    hLens[t] = newHLen;
                    geo.computeCoverMask(hulls[t], newHLen, covMasks[t]);
                }
            }

            // Solve Exact 2D Knapsack + Task-Envelope DP on the safe containers
            List<Integer>[] taken = solveExactPoolDP(oid, containers);
            if (taken == null) {
                return Solution.empty();
            }

            boolean[] usedSite = new boolean[inst.sites.length];
            for (int t = 0; t < k; t++) {
                for (int s : taken[t]) usedSite[s] = true;
                rebuildOfficeHull(oid[t], taken[t], hulls[t], hLens, t, covMasks[t]);
            }

            // Stage 3: Iterative Container Tightening (shrink hulls to `taken`, expand into freed space, re-solve DP)
            if (thorough && timeLeft()) {
                List<Integer>[] tightContainers = new List[k];
                boolean[] inTight = new boolean[inst.sites.length];
                for (int t = 0; t < k; t++) {
                    tightContainers[t] = new ArrayList<>(taken[t]);
                    for (int s : taken[t]) inTight[s] = true;
                }
                for (int t = 0; t < k; t++) {
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int maxContainer = Math.min(52, inst.offices[oid[t]].maxSize + 16);
                    int[] rankedAll = rankSitesForOffice(oid[t]);
                    int negAdded = 0;
                    for (int s : rankedAll) {
                        if (tightContainers[t].size() >= maxContainer) break;
                        if (inTight[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                        double v = inst.netVal[oid[t]][s];
                        if (v <= 0 && negAdded >= 4) break;
                        int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                        if (newHLen < 0) continue;
                        tightContainers[t].add(s);
                        inTight[s] = true;
                        if (v <= 0) negAdded++;
                        System.arraycopy(candHull, 0, hulls[t], 0, newHLen);
                        hLens[t] = newHLen;
                        geo.computeCoverMask(hulls[t], newHLen, covMasks[t]);
                    }
                }
                List<Integer>[] refinedTaken = solveExactPoolDP(oid, tightContainers);
                if (refinedTaken != null && evalScore(oid, refinedTaken) > evalScore(oid, taken) + 1e-9) {
                    taken = refinedTaken;
                }
                Arrays.fill(usedSite, false);
                for (int t = 0; t < k; t++) {
                    for (int s : taken[t]) usedSite[s] = true;
                    rebuildOfficeHull(oid[t], taken[t], hulls[t], hLens, t, covMasks[t]);
                }
            }

            return optimizeAndPack(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, thorough);
        }

        /**
         * Exact 2D Knapsack + Task-Envelope DP over mutually disjoint office containers:
         * Finds the exact global optimum of site subsets S_t <= containers[t] (|containers[t]| <= 60)
         * maximizing:
         *    sum_t (sum_{s in S_t} netVal[o_t][s] - cost[o_t]) - alpha * (max_t T_t - min_t T_t)
         * subject to:
         *    1 <= |S_t| <= maxSize[o_t]   and   max_t |S_t| - min_t |S_t| <= maxDifference.
         */
        @SuppressWarnings("unchecked")
        private List<Integer>[] solveExactPoolDP(int[] oid, List<Integer>[] containers) {
            int k = oid.length;
            double[][][] dp = new double[k][][];
            long[][][] mask = new long[k][][];
            int[] maxN = new int[k];
            int[] maxTau = new int[k];
            int globalMaxTau = 0;

            for (int t = 0; t < k; t++) {
                List<Integer> pool = containers[t];
                int m = Math.min(pool.size(), 60);
                if (m == 0) return null;
                int cap = Math.min(m, inst.offices[oid[t]].maxSize);
                if (cap == 0) return null;
                maxN[t] = cap;

                int[] tasksArr = new int[m];
                double[] valArr = new double[m];
                for (int i = 0; i < m; i++) {
                    int s = pool.get(i);
                    tasksArr[i] = inst.siteTaskCount[s];
                    valArr[i] = inst.netVal[oid[t]][s];
                }
                int[] sortedTasks = tasksArr.clone();
                Arrays.sort(sortedTasks);
                int tauBound = 0;
                for (int i = m - 1; i >= m - cap; i--) tauBound += sortedTasks[i];
                maxTau[t] = tauBound;
                globalMaxTau = Math.max(globalMaxTau, tauBound);

                double[][] dpT = new double[cap + 1][tauBound + 1];
                long[][] maskT = new long[cap + 1][tauBound + 1];
                for (int n = 0; n <= cap; n++) {
                    Arrays.fill(dpT[n], -1e100);
                }
                dpT[0][0] = 0.0;

                int curMaxN = 0;
                int curMaxTau = 0;
                for (int idx = 0; idx < m; idx++) {
                    int tk = tasksArr[idx];
                    double v = valArr[idx];
                    long bit = 1L << idx;
                    int nextNLim = Math.min(cap, curMaxN + 1);
                    for (int n = nextNLim; n >= 1; n--) {
                        double[] rowPrev = dpT[n - 1];
                        double[] rowCur = dpT[n];
                        long[] mPrev = maskT[n - 1];
                        long[] mCur = maskT[n];
                        int tauLim = Math.min(tauBound - tk, curMaxTau);
                        for (int tau = tauLim; tau >= 0; tau--) {
                            double prev = rowPrev[tau];
                            if (prev > -1e90) {
                                double cand = prev + v;
                                if (cand > rowCur[tau + tk]) {
                                    rowCur[tau + tk] = cand;
                                    mCur[tau + tk] = mPrev[tau] | bit;
                                }
                            }
                        }
                    }
                    curMaxN = nextNLim;
                    curMaxTau = Math.min(tauBound, curMaxTau + tk);
                }
                dp[t] = dpT;
                mask[t] = maskT;
            }

            int minCap = Integer.MAX_VALUE;
            int maxCap = 0;
            double totalCost = 0.0;
            for (int t = 0; t < k; t++) {
                minCap = Math.min(minCap, maxN[t]);
                maxCap = Math.max(maxCap, maxN[t]);
                totalCost += inst.offices[oid[t]].cost;
            }
            if (minCap < 1) return null;

            double bestTotalObj = -1e100;
            int[] bestN = new int[k];
            int[] bestTau = new int[k];

            double[][] bestValForTau = new double[k][globalMaxTau + 1];
            int[][] bestNForTau = new int[k][globalMaxTau + 1];

            int maxL = Math.min(minCap, maxCap);
            for (int L = 1; L <= maxL; L++) {
                int U = L + inst.maxDifference;

                int minReachableTau = Integer.MAX_VALUE;
                int maxReachableTau = 0;
                boolean windowFeasible = true;

                for (int t = 0; t < k; t++) {
                    int hiN = Math.min(U, maxN[t]);
                    if (hiN < L) {
                        windowFeasible = false;
                        break;
                    }
                    Arrays.fill(bestValForTau[t], -1e100);
                    int tMaxTau = maxTau[t];
                    boolean anyValid = false;
                    for (int n = L; n <= hiN; n++) {
                        double[] row = dp[t][n];
                        for (int tau = 0; tau <= tMaxTau; tau++) {
                            if (row[tau] > bestValForTau[t][tau]) {
                                bestValForTau[t][tau] = row[tau];
                                bestNForTau[t][tau] = n;
                                anyValid = true;
                                if (tau < minReachableTau) minReachableTau = tau;
                                if (tau > maxReachableTau) maxReachableTau = tau;
                            }
                        }
                    }
                    if (!anyValid) {
                        windowFeasible = false;
                        break;
                    }
                }
                if (!windowFeasible) continue;

                if (inst.balanceCoefficient <= 1e-9 || k == 1) {
                    double sum = 0.0;
                    int minT = Integer.MAX_VALUE, maxT = 0;
                    int[] curTau = new int[k];
                    for (int t = 0; t < k; t++) {
                        double bestTVal = -1e100;
                        int bTau = 0;
                        for (int tau = 0; tau <= maxTau[t]; tau++) {
                            if (bestValForTau[t][tau] > bestTVal) {
                                bestTVal = bestValForTau[t][tau];
                                bTau = tau;
                            }
                        }
                        sum += bestTVal;
                        curTau[t] = bTau;
                        minT = Math.min(minT, bTau);
                        maxT = Math.max(maxT, bTau);
                    }
                    double obj = sum - totalCost - inst.balanceCoefficient * (maxT - minT);
                    if (obj > bestTotalObj) {
                        bestTotalObj = obj;
                        for (int t = 0; t < k; t++) {
                            bestTau[t] = curTau[t];
                            bestN[t] = bestNForTau[t][curTau[t]];
                        }
                    }
                } else {
                    for (int tauMin = minReachableTau; tauMin <= maxReachableTau; tauMin++) {
                        for (int tauMax = tauMin; tauMax <= maxReachableTau; tauMax++) {
                            double sum = 0.0;
                            boolean ok = true;
                            for (int t = 0; t < k; t++) {
                                int hi = Math.min(tauMax, maxTau[t]);
                                if (hi < tauMin) {
                                    ok = false;
                                    break;
                                }
                                double bestTVal = -1e100;
                                for (int tau = tauMin; tau <= hi; tau++) {
                                    if (bestValForTau[t][tau] > bestTVal) {
                                        bestTVal = bestValForTau[t][tau];
                                    }
                                }
                                if (bestTVal <= -1e90) {
                                    ok = false;
                                    break;
                                }
                                sum += bestTVal;
                            }
                            if (!ok) continue;
                            double obj = sum - totalCost - inst.balanceCoefficient * (tauMax - tauMin);
                            if (obj > bestTotalObj) {
                                bestTotalObj = obj;
                                for (int t = 0; t < k; t++) {
                                    int hi = Math.min(tauMax, maxTau[t]);
                                    double bestTVal = -1e100;
                                    int bTau = tauMin;
                                    for (int tau = tauMin; tau <= hi; tau++) {
                                        if (bestValForTau[t][tau] > bestTVal) {
                                            bestTVal = bestValForTau[t][tau];
                                            bTau = tau;
                                        }
                                    }
                                    bestTau[t] = bTau;
                                    bestN[t] = bestNForTau[t][bTau];
                                }
                            }
                        }
                    }
                }
            }

            if (bestTotalObj <= -1e90) return null;

            List<Integer>[] result = new List[k];
            for (int t = 0; t < k; t++) {
                result[t] = new ArrayList<>(bestN[t]);
                long chosenMask = mask[t][bestN[t]][bestTau[t]];
                List<Integer> pool = containers[t];
                int m = Math.min(pool.size(), 60);
                for (int i = 0; i < m; i++) {
                    if ((chosenMask & (1L << i)) != 0L) {
                        result[t].add(pool.get(i));
                    }
                }
            }
            return result;
        }

        /**
         * 3-Mode Adaptive Power Diagram (Laguerre Voronoi) Generator:
         * - mode 0: Capacity & site-count balanced Power Diagram
         * - mode 1: Net-value-weighted Power Diagram (shifts boundaries toward offices with higher net value)
         * - mode 2: Task-load-balanced Power Diagram (shifts boundaries to equalize total tasks per office)
         */
        @SuppressWarnings("unchecked")
        private List<Integer>[] buildPowerDiagramPools(int[] oid, int mode) {
            int k = oid.length;
            double[] weights = new double[k];
            double[] maxPairD2 = new double[k];
            Arrays.fill(maxPairD2, Double.POSITIVE_INFINITY);
            for (int t = 0; t < k; t++) {
                for (int u = 0; u < k; u++) {
                    if (t != u) {
                        double d = inst.officeEuclid[oid[t]][oid[u]];
                        maxPairD2[t] = Math.min(maxPairD2[t], d * d);
                    }
                }
            }

            if (mode == 1) {
                double meanSingle = 0.0;
                for (int t = 0; t < k; t++) meanSingle += Math.max(0.0, officeSingle[oid[t]]);
                meanSingle /= Math.max(1, k);
                for (int t = 0; t < k; t++) {
                    double rel = (Math.max(0.0, officeSingle[oid[t]]) - meanSingle) / (meanSingle + 100.0);
                    weights[t] = Math.max(-0.30 * maxPairD2[t], Math.min(0.30 * maxPairD2[t], 0.18 * rel * maxPairD2[t]));
                }
            }

            int[] metricCount = new int[k];
            int iters = (mode == 1) ? 6 : 10;
            for (int iter = 0; iter < iters; iter++) {
                Arrays.fill(metricCount, 0);
                int[] siteCount = new int[k];
                for (int s = 0; s < inst.sites.length; s++) {
                    if (!inst.feasibleSite[s]) continue;
                    int bestT = 0;
                    double bestP = Double.POSITIVE_INFINITY;
                    for (int t = 0; t < k; t++) {
                        double p = inst.euclidDist2[oid[t]][s] - weights[t];
                        if (p < bestP) {
                            bestP = p;
                            bestT = t;
                        }
                    }
                    if (inst.netVal[oid[bestT]][s] > 0) {
                        siteCount[bestT]++;
                        if (mode == 2 && siteCount[bestT] <= inst.offices[oid[bestT]].maxSize) {
                            metricCount[bestT] += inst.siteTaskCount[s];
                        } else {
                            metricCount[bestT]++;
                        }
                    }
                }

                double meanM = 0;
                for (int t = 0; t < k; t++) meanM += metricCount[t];
                meanM /= k;

                for (int t = 0; t < k; t++) {
                    double step = 0.075 * maxPairD2[t];
                    if (siteCount[t] < 2 || metricCount[t] < meanM - 0.5) {
                        weights[t] = Math.min(0.42 * maxPairD2[t], weights[t] + step);
                    } else if (siteCount[t] > inst.offices[oid[t]].maxSize || metricCount[t] > meanM + 0.5) {
                        weights[t] = Math.max(-0.42 * maxPairD2[t], weights[t] - step);
                    }
                }
            }

            List<Integer>[] pools = new List[k];
            for (int t = 0; t < k; t++) pools[t] = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!inst.feasibleSite[s]) continue;
                int bestT = 0;
                double bestP = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    double p = inst.euclidDist2[oid[t]][s] - weights[t];
                    if (p < bestP - 1e-15 || (Math.abs(p - bestP) <= 1e-15 && oid[t] < oid[bestT])) {
                        bestP = p;
                        bestT = t;
                    }
                }
                pools[bestT].add(s);
            }
            for (int t = 0; t < k; t++) {
                final int o = oid[t];
                pools[t].sort((a, b) -> Double.compare(inst.netVal[o][b], inst.netVal[o][a]));
            }
            return pools;
        }

        @SuppressWarnings("unchecked")
        private List<Integer>[] buildVoronoiPools(int[] oid, boolean road) {
            int k = oid.length;
            List<Integer>[] pools = new List[k];
            for (int t = 0; t < k; t++) pools[t] = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!inst.feasibleSite[s]) continue;
                int bestT = 0;
                double bestD = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    double d = road ? inst.distances[oid[t]][s] : inst.euclidDist2[oid[t]][s];
                    if (d < bestD - 1e-15 || (Math.abs(d - bestD) <= 1e-15 && oid[t] < oid[bestT])) {
                        bestD = d;
                        bestT = t;
                    }
                }
                pools[bestT].add(s);
            }
            for (int t = 0; t < k; t++) {
                final int o = oid[t];
                pools[t].sort((a, b) -> Double.compare(inst.netVal[o][b], inst.netVal[o][a]));
            }
            return pools;
        }

        @SuppressWarnings("unchecked")
        private Solution buildVoronoiAndGrow(int[] oid, List<Integer>[] pools, BitSet[] baseSkills,
                                             boolean dynamicWorkers, boolean thorough) {
            int k = oid.length;
            List<Integer>[] taken = new List[k];
            BitSet[] skills = cloneBitSets(baseSkills);
            boolean[] usedSite = new boolean[inst.sites.length];
            int[][] hulls = new int[k][inst.sites.length + 2];
            int[] hLens = new int[k];
            long[][] covMasks = new long[k][geo.words];

            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
                int officePt = geo.nS + oid[t];
                hulls[t][0] = officePt;
                hLens[t] = 1;
                geo.computeCoverMask(hulls[t], 1, covMasks[t]);
            }

            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            for (int t = 0; t < k; t++) {
                geo.buildUnionOther(covMasks, k, t, unionOther);
                int cap = inst.offices[oid[t]].maxSize;
                for (int s : pools[t]) {
                    if (taken[t].size() >= cap) break;
                    double v = inst.netVal[oid[t]][s];
                    if (v <= 0) break;
                    if (usedSite[s] || !coveredBy(s, skills[t])) continue;

                    int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                    if (newHLen < 0) continue;

                    taken[t].add(s);
                    if (dynamicWorkers && !checkWorkersFeasible(taken)) {
                        taken[t].remove(taken[t].size() - 1);
                        continue;
                    }
                    usedSite[s] = true;
                    System.arraycopy(candHull, 0, hulls[t], 0, newHLen);
                    hLens[t] = newHLen;
                    geo.computeCoverMask(hulls[t], newHLen, covMasks[t]);
                }
            }

            return optimizeAndPack(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, thorough);
        }

        @SuppressWarnings("unchecked")
        private Solution buildTargetWindowBalanced(int[] oid, List<Integer>[] pools, BitSet[] baseSkills,
                                                   boolean dynamicWorkers, boolean thorough) {
            int k = oid.length;
            int minCap = Integer.MAX_VALUE;
            for (int o : oid) {
                minCap = Math.min(minCap, inst.offices[o].maxSize);
            }

            Set<Integer> candidateLs = new HashSet<>();
            candidateLs.add(Math.max(1, minCap - inst.maxDifference));
            if (thorough) {
                for (int t = 0; t < k; t++) {
                    int posCount = 0;
                    for (int s : pools[t]) {
                        if (inst.netVal[oid[t]][s] > 0) posCount++;
                    }
                    int cap = inst.offices[oid[t]].maxSize;
                    int c1 = Math.max(1, Math.min(minCap, posCount));
                    int c2 = Math.max(1, Math.min(minCap, Math.min(cap, posCount) - inst.maxDifference));
                    candidateLs.add(c1);
                    candidateLs.add(c2);
                }
                candidateLs.add(minCap);
            }

            Solution bestWin = Solution.empty();
            for (int L : candidateLs) {
                if (!timeLeft()) break;
                int U = L + inst.maxDifference;
                List<Integer>[] taken = new List[k];
                BitSet[] skills = cloneBitSets(baseSkills);
                boolean[] usedSite = new boolean[inst.sites.length];
                int[][] hulls = new int[k][inst.sites.length + 2];
                int[] hLens = new int[k];
                long[][] covMasks = new long[k][geo.words];

                for (int t = 0; t < k; t++) {
                    taken[t] = new ArrayList<>();
                    hulls[t][0] = geo.nS + oid[t];
                    hLens[t] = 1;
                    geo.computeCoverMask(hulls[t], 1, covMasks[t]);
                }

                long[] unionOther = new long[geo.words];
                int[] candHull = new int[inst.sites.length + 2];

                for (int t = 0; t < k; t++) {
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int cap = Math.min(U, inst.offices[oid[t]].maxSize);
                    for (int s : pools[t]) {
                        if (taken[t].size() >= cap) break;
                        double v = inst.netVal[oid[t]][s];
                        if (v <= 0 && taken[t].size() >= L) break;
                        if (usedSite[s] || !coveredBy(s, skills[t])) continue;
                        int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                        if (newHLen < 0) continue;
                        taken[t].add(s);
                        if (dynamicWorkers && !checkWorkersFeasible(taken)) {
                            taken[t].remove(taken[t].size() - 1);
                            continue;
                        }
                        usedSite[s] = true;
                        System.arraycopy(candHull, 0, hulls[t], 0, newHLen);
                        hLens[t] = newHLen;
                        geo.computeCoverMask(hulls[t], newHLen, covMasks[t]);
                    }
                }

                for (int t = 0; t < k; t++) {
                    if (taken[t].size() >= L) continue;
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int[] rankedAll = rankSitesForOffice(oid[t]);
                    for (int s : rankedAll) {
                        if (taken[t].size() >= L) break;
                        if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                        int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                        if (newHLen < 0) continue;
                        taken[t].add(s);
                        if (dynamicWorkers && !checkWorkersFeasible(taken)) {
                            taken[t].remove(taken[t].size() - 1);
                            continue;
                        }
                        usedSite[s] = true;
                        System.arraycopy(candHull, 0, hulls[t], 0, newHLen);
                        hLens[t] = newHLen;
                        geo.computeCoverMask(hulls[t], newHLen, covMasks[t]);
                    }
                }

                Solution sol = optimizeAndPack(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, thorough);
                if (sol.objective > bestWin.objective) {
                    bestWin = sol;
                }
            }
            return bestWin;
        }

        @SuppressWarnings("unchecked")
        private Solution buildIntrusionAwareHullGrow(int[] oid, BitSet[] baseSkills,
                                                     boolean dynamicWorkers, double alphaWeight) {
            int k = oid.length;
            List<Integer>[] taken = new List[k];
            BitSet[] skills = cloneBitSets(baseSkills);
            boolean[] usedSite = new boolean[inst.sites.length];
            int[][] hulls = new int[k][inst.sites.length + 2];
            int[] hLens = new int[k];
            long[][] covMasks = new long[k][geo.words];
            int[] tasksLoad = new int[k];

            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
                hulls[t][0] = geo.nS + oid[t];
                hLens[t] = 1;
                geo.computeCoverMask(hulls[t], 1, covMasks[t]);
            }

            double[][] intrusionPen = new double[k][inst.sites.length];
            for (int s = 0; s < inst.sites.length; s++) {
                double minEu = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    minEu = Math.min(minEu, Math.sqrt(inst.euclidDist2[oid[t]][s]));
                }
                for (int t = 0; t < k; t++) {
                    double d = Math.sqrt(inst.euclidDist2[oid[t]][s]);
                    intrusionPen[t][s] = Math.max(0.0, d - minEu);
                }
            }

            int[][] ranked = new int[k][];
            for (int t = 0; t < k; t++) {
                final int ft = t;
                final int o = oid[t];
                Integer[] ord = new Integer[inst.sites.length];
                for (int s = 0; s < inst.sites.length; s++) ord[s] = s;
                Arrays.sort(ord, (a, b) -> {
                    double sa = inst.netVal[o][a] - 1.2 * intrusionPen[ft][a] + 4.0 * ml.affinityBonus(o, a);
                    double sb = inst.netVal[o][b] - 1.2 * intrusionPen[ft][b] + 4.0 * ml.affinityBonus(o, b);
                    return Double.compare(sb, sa);
                });
                ranked[t] = new int[inst.sites.length];
                for (int i = 0; i < ord.length; i++) ranked[t][i] = ord[i];
            }

            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];
            boolean[] stalled = new boolean[k];
            int maxAdd = 0;
            for (int o : oid) maxAdd += inst.offices[o].maxSize;
            maxAdd = Math.min(maxAdd, inst.sites.length);
            int scanWindow = inst.sites.length <= 100 ? inst.sites.length : 45;

            for (int step = 0; step < maxAdd && timeLeft(); step++) {
                int bestT = -1;
                int bestS = -1;
                double bestScore = -1e100;

                int minSz = Integer.MAX_VALUE;
                int minTk = Integer.MAX_VALUE, maxTk = 0;
                int activeCount = 0;
                for (int t = 0; t < k; t++) {
                    if (taken[t].isEmpty() && stalled[t]) continue;
                    activeCount++;
                    minSz = Math.min(minSz, taken[t].size());
                    minTk = Math.min(minTk, tasksLoad[t]);
                    maxTk = Math.max(maxTk, tasksLoad[t]);
                }

                for (int t = 0; t < k; t++) {
                    if (stalled[t] || taken[t].size() >= inst.offices[oid[t]].maxSize) continue;
                    if (activeCount > 1 && taken[t].size() + 1 - minSz > inst.maxDifference) {
                        int countAtMin = 0;
                        for (int u = 0; u < k; u++) {
                            if (!(taken[u].isEmpty() && stalled[u]) && taken[u].size() == minSz) countAtMin++;
                        }
                        if (!(taken[t].size() == minSz && countAtMin == 1)) {
                            continue;
                        }
                    }

                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int tried = 0;
                    for (int pos = 0; pos < ranked[t].length && tried < scanWindow; pos++) {
                        int s = ranked[t][pos];
                        if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                        double v = inst.netVal[oid[t]][s];
                        int newMinTk = minTk;
                        int newMaxTk = Math.max(maxTk, tasksLoad[t] + inst.siteTaskCount[s]);
                        if (tasksLoad[t] == minTk) {
                            newMinTk = tasksLoad[t] + inst.siteTaskCount[s];
                            for (int u = 0; u < k; u++) {
                                if (u != t && !(taken[u].isEmpty() && stalled[u])) {
                                    newMinTk = Math.min(newMinTk, tasksLoad[u]);
                                }
                            }
                        }
                        double deltaTaskPen = inst.balanceCoefficient * ((newMaxTk - newMinTk) - (maxTk - minTk));
                        double candidateSc = v - alphaWeight * deltaTaskPen - 0.8 * intrusionPen[t][s]
                                + 3.0 * ml.affinityBonus(oid[t], s);
                        if (v <= 0 && candidateSc <= 0) continue;

                        tried++;
                        int hLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                        if (hLen < 0) continue;

                        if (dynamicWorkers) {
                            taken[t].add(s);
                            boolean wf = checkWorkersFeasible(taken);
                            taken[t].remove(taken[t].size() - 1);
                            if (!wf) continue;
                        }

                        if (candidateSc > bestScore) {
                            bestScore = candidateSc;
                            bestT = t;
                            bestS = s;
                        }
                    }
                }

                if (bestT < 0) {
                    boolean unstalledAny = false;
                    for (int t = 0; t < k; t++) {
                        if (taken[t].isEmpty() && !stalled[t]) {
                            stalled[t] = true;
                            unstalledAny = true;
                        }
                    }
                    if (!unstalledAny) break;
                    continue;
                }

                taken[bestT].add(bestS);
                usedSite[bestS] = true;
                tasksLoad[bestT] += inst.siteTaskCount[bestS];
                int[] tmp = new int[hLens[bestT] + 1];
                System.arraycopy(hulls[bestT], 0, tmp, 0, hLens[bestT]);
                tmp[hLens[bestT]] = bestS;
                hLens[bestT] = geo.buildHull(tmp, hLens[bestT] + 1, hulls[bestT]);
                geo.computeCoverMask(hulls[bestT], hLens[bestT], covMasks[bestT]);
            }

            return optimizeAndPack(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, true);
        }

        /* ==================== 10-NEIGHBORHOOD LOCAL OPTIMIZER ==================== */

        private Solution optimizeAndPack(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                         int[][] hulls, int[] hLens, long[][] covMasks,
                                         boolean[] usedSite, boolean dynamicWorkers, boolean thorough) {
            repairSiteBalanceConstructively(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers);
            syncWorkerSkills(taken, skills);

            int maxRounds = thorough ? (isCompact() ? 90 : 35) : 4;
            int round = 0;
            boolean changed = true;
            boolean peeledOnce = false;
            while (changed && round++ < maxRounds && timeLeft()) {
                changed = false;
                if (tryAddSingleSite(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (oid.length >= 2 && tryLevelUpMinOffices(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (trySwapTakenWithFree(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, thorough)) {
                    changed = true;
                    continue;
                }
                if (tryDropSingleSiteForAlpha(oid, taken, hulls, hLens, covMasks, usedSite)) {
                    changed = true;
                    continue;
                }
                if (oid.length >= 2 && tryRelocateSite(oid, taken, skills, hulls, hLens, covMasks, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (thorough && oid.length >= 2 && inst.balanceCoefficient > 0.5
                        && tryCompressTiedTaskRange(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (thorough && oid.length >= 2 && tryAddPairedSites(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (thorough && oid.length >= 2 && trySwapBetweenOffices(oid, taken, skills, hulls, hLens, covMasks, dynamicWorkers)) {
                    changed = true;
                    continue;
                }
                if (thorough && !peeledOnce && oid.length >= 2 && isCompact()) {
                    peeledOnce = true;
                    if (tryPeelBlockingHullVertex(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                        changed = true;
                    }
                }
            }

            if (thorough && !dynamicWorkers && oid.length >= 2 && timeLeft()) {
                BitSet[] fullSkills = new BitSet[oid.length];
                for (int t = 0; t < oid.length; t++) fullSkills[t] = (BitSet) inst.coveredGlobally.clone();
                for (int extra = 0; extra < 4 && timeLeft(); extra++) {
                    if (!tryAddSingleSite(oid, taken, fullSkills, hulls, hLens, covMasks, usedSite, true)
                            && !trySwapTakenWithFree(oid, taken, fullSkills, hulls, hLens, covMasks, usedSite, true, false)) {
                        break;
                    }
                }
            }

            return finalizeSolution(oid, taken);
        }

        @SuppressWarnings("unchecked")
        private boolean tryLevelUpMinOffices(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                             int[][] hulls, int[] hLens, long[][] covMasks,
                                             boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int minS = Integer.MAX_VALUE, maxS = 0;
            for (int t = 0; t < k; t++) {
                if (taken[t].isEmpty()) continue;
                minS = Math.min(minS, taken[t].size());
                maxS = Math.max(maxS, taken[t].size());
            }
            if (maxS - minS < inst.maxDifference) return false;

            List<Integer> minOffices = new ArrayList<>();
            for (int t = 0; t < k; t++) {
                if (!taken[t].isEmpty() && taken[t].size() == minS) {
                    if (taken[t].size() >= inst.offices[oid[t]].maxSize) return false;
                    minOffices.add(t);
                }
            }
            if (minOffices.isEmpty() || minOffices.size() > 6) return false;

            List<Integer>[] backupTaken = copyLists(taken);
            boolean[] backupUsed = usedSite.clone();
            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            for (int t : minOffices) {
                int o = oid[t];
                geo.buildUnionOther(covMasks, k, t, unionOther);
                int bestS = -1;
                double bestV = -1e100;
                for (int s = 0; s < inst.sites.length; s++) {
                    if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                    double v = inst.netVal[o][s];
                    if (v <= bestV) continue;
                    if (geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull) >= 0) {
                        bestV = v;
                        bestS = s;
                    }
                }
                if (bestS < 0) {
                    restoreLists(taken, backupTaken);
                    System.arraycopy(backupUsed, 0, usedSite, 0, backupUsed.length);
                    for (int u = 0; u < k; u++) rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
                    return false;
                }
                taken[t].add(bestS);
                usedSite[bestS] = true;
                rebuildOfficeHull(o, taken[t], hulls[t], hLens, t, covMasks[t]);
            }

            if (inst.maxDifference > 0) {
                for (int t = 0; t < k; t++) {
                    if (taken[t].isEmpty() || minOffices.contains(t)) continue;
                    if (taken[t].size() >= inst.offices[oid[t]].maxSize) continue;
                    int o = oid[t];
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int bestS = -1;
                    double bestV = 0.0;
                    for (int s = 0; s < inst.sites.length; s++) {
                        if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                        double v = inst.netVal[o][s];
                        if (v <= bestV) continue;
                        if (geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull) >= 0) {
                            bestV = v;
                            bestS = s;
                        }
                    }
                    if (bestS >= 0) {
                        taken[t].add(bestS);
                        usedSite[bestS] = true;
                        rebuildOfficeHull(o, taken[t], hulls[t], hLens, t, covMasks[t]);
                    }
                }
            }

            if (allHullsDisjoint(covMasks, k) && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                if (evalScore(oid, taken) > curSc + 1e-9) {
                    if (dynamicWorkers) syncWorkerSkills(taken, skills);
                    return true;
                }
            }

            restoreLists(taken, backupTaken);
            System.arraycopy(backupUsed, 0, usedSite, 0, backupUsed.length);
            for (int u = 0; u < k; u++) rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
            return false;
        }

        @SuppressWarnings("unchecked")
        private boolean tryCompressTiedTaskRange(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                                 int[][] hulls, int[] hLens, long[][] covMasks,
                                                 boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int minT = Integer.MAX_VALUE, maxT = 0;
            int[] tasks = new int[k];
            int countMax = 0, countMin = 0;
            for (int t = 0; t < k; t++) {
                if (taken[t].isEmpty()) continue;
                int tk = 0;
                for (int s : taken[t]) tk += inst.siteTaskCount[s];
                tasks[t] = tk;
                minT = Math.min(minT, tk);
                maxT = Math.max(maxT, tk);
            }
            if (maxT <= minT) return false;
            for (int t = 0; t < k; t++) {
                if (taken[t].isEmpty()) continue;
                if (tasks[t] == maxT) countMax++;
                if (tasks[t] == minT) countMin++;
            }
            if (countMax <= 1 && countMin <= 1) return false;

            List<Integer>[] backupTaken = copyLists(taken);
            boolean[] backupUsed = usedSite.clone();
            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            if (countMax >= 2 && countMax <= 4) {
                boolean allReduced = true;
                for (int t = 0; t < k; t++) {
                    if (taken[t].isEmpty() || tasks[t] != maxT) continue;
                    int o = oid[t];
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int bestInIdx = -1, bestOutSite = -1;
                    double bestScore = -1e100;

                    for (int idx = 0; idx < taken[t].size(); idx++) {
                        int sIn = taken[t].get(idx);
                        int tkIn = inst.siteTaskCount[sIn];
                        if (tkIn <= 1) continue;
                        double vIn = inst.netVal[o][sIn];

                        taken[t].remove(idx);
                        int[] redHull = new int[taken[t].size() + 2];
                        long[] redMask = new long[geo.words];
                        int[] pts = new int[taken[t].size() + 1];
                        pts[0] = geo.nS + o;
                        for (int i = 0; i < taken[t].size(); i++) pts[i + 1] = taken[t].get(i);
                        int redHLen = geo.buildHull(pts, pts.length, redHull);
                        geo.computeCoverMask(redHull, redHLen, redMask);

                        for (int sOut = 0; sOut < inst.sites.length; sOut++) {
                            if (usedSite[sOut] || !inst.feasibleSite[sOut] || !coveredBy(sOut, skills[t])) continue;
                            int tkOut = inst.siteTaskCount[sOut];
                            if (tkOut >= tkIn) continue;
                            double sc = (inst.netVal[o][sOut] - vIn) + inst.balanceCoefficient * (tkIn - tkOut) / countMax;
                            if (sc <= bestScore) continue;
                            if (geo.testAddSiteToHull(redHull, redHLen, redMask, unionOther, sOut, candHull) >= 0) {
                                bestScore = sc;
                                bestInIdx = idx;
                                bestOutSite = sOut;
                            }
                        }
                        taken[t].add(idx, sIn);
                    }
                    if (bestInIdx < 0) {
                        allReduced = false;
                        break;
                    }
                    int oldS = taken[t].set(bestInIdx, bestOutSite);
                    usedSite[oldS] = false;
                    usedSite[bestOutSite] = true;
                    rebuildOfficeHull(o, taken[t], hulls[t], hLens, t, covMasks[t]);
                }
                if (allReduced && allHullsDisjoint(covMasks, k) && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                    if (evalScore(oid, taken) > curSc + 1e-9) {
                        if (dynamicWorkers) syncWorkerSkills(taken, skills);
                        return true;
                    }
                }
                restoreLists(taken, backupTaken);
                System.arraycopy(backupUsed, 0, usedSite, 0, backupUsed.length);
                for (int u = 0; u < k; u++) rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
            }

            if (countMin >= 2 && countMin <= 4) {
                boolean allIncreased = true;
                for (int t = 0; t < k; t++) {
                    if (taken[t].isEmpty() || tasks[t] != minT) continue;
                    int o = oid[t];
                    geo.buildUnionOther(covMasks, k, t, unionOther);
                    int bestInIdx = -1, bestOutSite = -1;
                    double bestScore = -1e100;

                    for (int idx = 0; idx < taken[t].size(); idx++) {
                        int sIn = taken[t].get(idx);
                        int tkIn = inst.siteTaskCount[sIn];
                        double vIn = inst.netVal[o][sIn];

                        taken[t].remove(idx);
                        int[] redHull = new int[taken[t].size() + 2];
                        long[] redMask = new long[geo.words];
                        int[] pts = new int[taken[t].size() + 1];
                        pts[0] = geo.nS + o;
                        for (int i = 0; i < taken[t].size(); i++) pts[i + 1] = taken[t].get(i);
                        int redHLen = geo.buildHull(pts, pts.length, redHull);
                        geo.computeCoverMask(redHull, redHLen, redMask);

                        for (int sOut = 0; sOut < inst.sites.length; sOut++) {
                            if (usedSite[sOut] || !inst.feasibleSite[sOut] || !coveredBy(sOut, skills[t])) continue;
                            int tkOut = inst.siteTaskCount[sOut];
                            if (tkOut <= tkIn) continue;
                            double sc = (inst.netVal[o][sOut] - vIn) + inst.balanceCoefficient * (tkOut - tkIn) / countMin;
                            if (sc <= bestScore) continue;
                            if (geo.testAddSiteToHull(redHull, redHLen, redMask, unionOther, sOut, candHull) >= 0) {
                                bestScore = sc;
                                bestInIdx = idx;
                                bestOutSite = sOut;
                            }
                        }
                        taken[t].add(idx, sIn);
                    }
                    if (bestInIdx < 0) {
                        allIncreased = false;
                        break;
                    }
                    int oldS = taken[t].set(bestInIdx, bestOutSite);
                    usedSite[oldS] = false;
                    usedSite[bestOutSite] = true;
                    rebuildOfficeHull(o, taken[t], hulls[t], hLens, t, covMasks[t]);
                }
                if (allIncreased && allHullsDisjoint(covMasks, k) && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                    if (evalScore(oid, taken) > curSc + 1e-9) {
                        if (dynamicWorkers) syncWorkerSkills(taken, skills);
                        return true;
                    }
                }
                restoreLists(taken, backupTaken);
                System.arraycopy(backupUsed, 0, usedSite, 0, backupUsed.length);
                for (int u = 0; u < k; u++) rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
            }

            return false;
        }

        private void repairSiteBalanceConstructively(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                                     int[][] hulls, int[] hLens, long[][] covMasks,
                                                     boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            if (k < 2) return;
            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            int guard = 0;
            while (guard++ < inst.sites.length + 4 && timeLeft()) {
                int minI = -1, maxI = -1;
                int minSz = Integer.MAX_VALUE, maxSz = -1;
                int active = 0;
                for (int t = 0; t < k; t++) {
                    int sz = taken[t].size();
                    if (sz == 0) continue;
                    active++;
                    if (sz < minSz) {
                        minSz = sz;
                        minI = t;
                    }
                    if (sz > maxSz) {
                        maxSz = sz;
                        maxI = t;
                    }
                }
                if (active < 2 || maxSz - minSz <= inst.maxDifference) break;

                if (taken[minI].size() < inst.offices[oid[minI]].maxSize) {
                    int bestIdx = -1;
                    double bestDelta = -1e100;
                    for (int idx = 0; idx < taken[maxI].size(); idx++) {
                        int s = taken[maxI].get(idx);
                        if (!coveredBy(s, skills[minI])) continue;
                        double delta = inst.netVal[oid[minI]][s] - inst.netVal[oid[maxI]][s];
                        taken[maxI].remove(idx);
                        rebuildOfficeHull(oid[maxI], taken[maxI], hulls[maxI], hLens, maxI, covMasks[maxI]);
                        geo.buildUnionOther(covMasks, k, minI, unionOther);
                        int newHLen = geo.testAddSiteToHull(hulls[minI], hLens[minI], covMasks[minI], unionOther, s, candHull);
                        boolean ok = newHLen >= 0;
                        if (ok && dynamicWorkers) {
                            taken[minI].add(s);
                            ok = checkWorkersFeasible(taken);
                            taken[minI].remove(taken[minI].size() - 1);
                        }
                        taken[maxI].add(idx, s);
                        rebuildOfficeHull(oid[maxI], taken[maxI], hulls[maxI], hLens, maxI, covMasks[maxI]);

                        if (ok && delta > bestDelta) {
                            bestDelta = delta;
                            bestIdx = idx;
                        }
                    }
                    if (bestIdx >= 0 && bestDelta > -inst.sites[taken[maxI].get(bestIdx)].reward) {
                        int s = taken[maxI].remove(bestIdx);
                        taken[minI].add(s);
                        rebuildOfficeHull(oid[maxI], taken[maxI], hulls[maxI], hLens, maxI, covMasks[maxI]);
                        rebuildOfficeHull(oid[minI], taken[minI], hulls[minI], hLens, minI, covMasks[minI]);
                        continue;
                    }
                }

                int worstMaxIdx = worstSiteIndexInOffice(oid[maxI], taken[maxI]);
                double dropLoss = inst.netVal[oid[maxI]][taken[maxI].get(worstMaxIdx)];

                if (taken[minI].size() < inst.offices[oid[minI]].maxSize) {
                    geo.buildUnionOther(covMasks, k, minI, unionOther);
                    int bestFiller = -1;
                    double bestFillerVal = -1e100;
                    for (int s = 0; s < inst.sites.length; s++) {
                        if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[minI])) continue;
                        double v = inst.netVal[oid[minI]][s];
                        if (v <= bestFillerVal) continue;
                        int newHLen = geo.testAddSiteToHull(hulls[minI], hLens[minI], covMasks[minI], unionOther, s, candHull);
                        if (newHLen < 0) continue;
                        if (dynamicWorkers) {
                            taken[minI].add(s);
                            boolean wf = checkWorkersFeasible(taken);
                            taken[minI].remove(taken[minI].size() - 1);
                            if (!wf) continue;
                        }
                        bestFillerVal = v;
                        bestFiller = s;
                    }
                    if (bestFiller >= 0 && bestFillerVal + dropLoss > 0.0) {
                        taken[minI].add(bestFiller);
                        usedSite[bestFiller] = true;
                        rebuildOfficeHull(oid[minI], taken[minI], hulls[minI], hLens, minI, covMasks[minI]);
                        continue;
                    }
                }

                int removed = taken[maxI].remove(worstMaxIdx);
                usedSite[removed] = false;
                rebuildOfficeHull(oid[maxI], taken[maxI], hulls[maxI], hLens, maxI, covMasks[maxI]);
            }
        }

        private boolean trySwapTakenWithFree(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                             int[][] hulls, int[] hLens, long[][] covMasks,
                                             boolean[] usedSite, boolean dynamicWorkers, boolean thorough) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int bestT = -1;
            int bestInIdx = -1;
            int bestOutSite = -1;
            double bestSc = curSc;

            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            List<Integer> freeSites = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!usedSite[s] && inst.feasibleSite[s]) freeSites.add(s);
            }
            if (freeSites.isEmpty()) return false;

            int maxFreeCheck = thorough ? Math.min(freeSites.size(), 90) : Math.min(freeSites.size(), 16);

            for (int t = 0; t < k && timeLeft(); t++) {
                if (taken[t].isEmpty()) continue;
                final int o = oid[t];
                freeSites.sort((a, b) -> Double.compare(inst.netVal[o][b], inst.netVal[o][a]));
                int nFree = Math.min(freeSites.size(), maxFreeCheck);

                geo.buildUnionOther(covMasks, k, t, unionOther);
                int nIn = taken[t].size();
                int inCheck = thorough ? nIn : Math.min(nIn, 8);

                for (int idx = 0; idx < inCheck; idx++) {
                    int sIn = taken[t].get(idx);
                    double vIn = inst.netVal[o][sIn];
                    int tkIn = inst.siteTaskCount[sIn];

                    taken[t].remove(idx);
                    boolean hullRebuilt = false;
                    int[] reducedHull = null;
                    int reducedHLen = 0;
                    long[] reducedMask = null;

                    for (int f = 0; f < nFree; f++) {
                        int sOut = freeSites.get(f);
                        if (!coveredBy(sOut, skills[t])) continue;
                        if ((unionOther[sOut >>> 6] & (1L << (sOut & 63))) != 0L) continue;

                        double vOut = inst.netVal[o][sOut];
                        int tkOut = inst.siteTaskCount[sOut];
                        double maxPossibleGain = (vOut - vIn) + inst.balanceCoefficient * Math.abs(tkOut - tkIn);
                        if (curSc + maxPossibleGain <= bestSc + 1e-9) continue;

                        taken[t].add(idx, sOut);
                        double candSc = evalScore(oid, taken);
                        taken[t].remove(idx);
                        if (candSc <= bestSc + 1e-9) continue;

                        if (!hullRebuilt) {
                            reducedHull = new int[taken[t].size() + 2];
                            reducedMask = new long[geo.words];
                            int[] pts = new int[taken[t].size() + 1];
                            pts[0] = geo.nS + o;
                            for (int i = 0; i < taken[t].size(); i++) pts[i + 1] = taken[t].get(i);
                            reducedHLen = geo.buildHull(pts, pts.length, reducedHull);
                            geo.computeCoverMask(reducedHull, reducedHLen, reducedMask);
                            hullRebuilt = true;
                        }
                        int newHLen = geo.testAddSiteToHull(reducedHull, reducedHLen, reducedMask, unionOther, sOut, candHull);
                        if (newHLen < 0) continue;

                        if (dynamicWorkers) {
                            taken[t].add(idx, sOut);
                            boolean wf = checkWorkersFeasible(taken);
                            taken[t].remove(idx);
                            if (!wf) continue;
                        }

                        bestSc = candSc;
                        bestT = t;
                        bestInIdx = idx;
                        bestOutSite = sOut;
                    }

                    taken[t].add(idx, sIn);
                }
            }

            if (bestT < 0) return false;
            int oldSite = taken[bestT].set(bestInIdx, bestOutSite);
            usedSite[oldSite] = false;
            usedSite[bestOutSite] = true;
            rebuildOfficeHull(oid[bestT], taken[bestT], hulls[bestT], hLens, bestT, covMasks[bestT]);
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        private boolean tryAddSingleSite(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                         int[][] hulls, int[] hLens, long[][] covMasks,
                                         boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int bestT = -1;
            int bestS = -1;
            double bestSc = curSc;

            long[] unionOther = new long[geo.words];
            int[] candHull = new int[inst.sites.length + 2];

            for (int t = 0; t < k && timeLeft(); t++) {
                if (taken[t].size() >= inst.offices[oid[t]].maxSize) continue;
                geo.buildUnionOther(covMasks, k, t, unionOther);

                for (int s = 0; s < inst.sites.length; s++) {
                    if (usedSite[s] || !inst.feasibleSite[s] || !coveredBy(s, skills[t])) continue;
                    if ((unionOther[s >>> 6] & (1L << (s & 63))) != 0L) continue;

                    taken[t].add(s);
                    double sc = evalScore(oid, taken);
                    taken[t].remove(taken[t].size() - 1);
                    if (sc <= bestSc + 1e-9) continue;

                    int newHLen = geo.testAddSiteToHull(hulls[t], hLens[t], covMasks[t], unionOther, s, candHull);
                    if (newHLen < 0) continue;

                    if (dynamicWorkers) {
                        taken[t].add(s);
                        boolean wf = checkWorkersFeasible(taken);
                        taken[t].remove(taken[t].size() - 1);
                        if (!wf) continue;
                    }

                    bestSc = sc;
                    bestT = t;
                    bestS = s;
                }
            }

            if (bestT < 0) return false;
            taken[bestT].add(bestS);
            usedSite[bestS] = true;
            rebuildOfficeHull(oid[bestT], taken[bestT], hulls[bestT], hLens, bestT, covMasks[bestT]);
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        private boolean tryDropSingleSiteForAlpha(int[] oid, List<Integer>[] taken,
                                                  int[][] hulls, int[] hLens, long[][] covMasks,
                                                  boolean[] usedSite) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int bestT = -1;
            int bestIdx = -1;
            double bestSc = curSc;

            for (int t = 0; t < k; t++) {
                if (taken[t].size() <= 1) continue;
                for (int idx = 0; idx < taken[t].size(); idx++) {
                    int s = taken[t].remove(idx);
                    double sc = evalScore(oid, taken);
                    taken[t].add(idx, s);
                    if (sc > bestSc + 1e-9) {
                        bestSc = sc;
                        bestT = t;
                        bestIdx = idx;
                    }
                }
            }
            if (bestT < 0) return false;
            int removed = taken[bestT].remove(bestIdx);
            usedSite[removed] = false;
            rebuildOfficeHull(oid[bestT], taken[bestT], hulls[bestT], hLens, bestT, covMasks[bestT]);
            return true;
        }

        private boolean tryRelocateSite(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                        int[][] hulls, int[] hLens, long[][] covMasks,
                                        boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int bestFrom = -1, bestIdx = -1, bestTo = -1;
            double bestSc = curSc;

            int[] tmpHullFrom = new int[inst.sites.length + 2];
            int[] tmpHullTo = new int[inst.sites.length + 2];
            int[] tmpLen = new int[2];
            long[] tmpMaskFrom = new long[geo.words];
            long[] tmpMaskTo = new long[geo.words];

            for (int from = 0; from < k && timeLeft(); from++) {
                if (taken[from].size() <= 1) continue;
                for (int idx = 0; idx < taken[from].size(); idx++) {
                    int s = taken[from].get(idx);
                    taken[from].remove(idx);
                    boolean builtFrom = false;

                    for (int to = 0; to < k; to++) {
                        if (to == from || taken[to].size() >= inst.offices[oid[to]].maxSize) continue;
                        if (!coveredBy(s, skills[to])) continue;

                        taken[to].add(s);
                        double sc = evalScore(oid, taken);
                        if (sc > bestSc + 1e-9) {
                            if (!builtFrom) {
                                rebuildOfficeHull(oid[from], taken[from], tmpHullFrom, tmpLen, 0, tmpMaskFrom);
                                builtFrom = true;
                            }
                            rebuildOfficeHull(oid[to], taken[to], tmpHullTo, tmpLen, 1, tmpMaskTo);
                            boolean geoOk = !FastGeo.masksIntersect(tmpMaskFrom, tmpMaskTo, geo.words);
                            for (int u = 0; u < k && geoOk; u++) {
                                if (u == from || u == to) continue;
                                if (FastGeo.masksIntersect(tmpMaskFrom, covMasks[u], geo.words)
                                        || FastGeo.masksIntersect(tmpMaskTo, covMasks[u], geo.words)) {
                                    geoOk = false;
                                }
                            }
                            if (geoOk && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                                bestSc = sc;
                                bestFrom = from;
                                bestIdx = idx;
                                bestTo = to;
                            }
                        }
                        taken[to].remove(taken[to].size() - 1);
                    }
                    taken[from].add(idx, s);
                }
            }

            if (bestFrom < 0) return false;
            int s = taken[bestFrom].remove(bestIdx);
            taken[bestTo].add(s);
            rebuildOfficeHull(oid[bestFrom], taken[bestFrom], hulls[bestFrom], hLens, bestFrom, covMasks[bestFrom]);
            rebuildOfficeHull(oid[bestTo], taken[bestTo], hulls[bestTo], hLens, bestTo, covMasks[bestTo]);
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        private boolean tryAddPairedSites(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                          int[][] hulls, int[] hLens, long[][] covMasks,
                                          boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            List<Integer> free = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!usedSite[s] && inst.feasibleSite[s]) free.add(s);
            }
            if (free.size() < 2) return false;

            free.sort((a, b) -> Double.compare(bestNetVal(oid, b), bestNetVal(oid, a)));
            int nTry = Math.min(free.size(), isCompact() ? 45 : 24);

            int bestT1 = -1, bestT2 = -1, bestS1 = -1, bestS2 = -1;
            double bestSc = curSc;

            long[] union1 = new long[geo.words];
            long[] union2 = new long[geo.words];
            int[] candHull1 = new int[inst.sites.length + 2];
            int[] candHull2 = new int[inst.sites.length + 2];
            long[] mask1 = new long[geo.words];

            for (int t1 = 0; t1 < k && timeLeft(); t1++) {
                if (taken[t1].size() >= inst.offices[oid[t1]].maxSize) continue;
                geo.buildUnionOther(covMasks, k, t1, union1);

                for (int t2 = t1 + 1; t2 < k; t2++) {
                    if (taken[t2].size() >= inst.offices[oid[t2]].maxSize) continue;

                    for (int i = 0; i < nTry; i++) {
                        int s1 = free.get(i);
                        if (!coveredBy(s1, skills[t1])) continue;
                        int h1 = geo.testAddSiteToHull(hulls[t1], hLens[t1], covMasks[t1], union1, s1, candHull1);
                        if (h1 < 0) continue;
                        geo.computeCoverMask(candHull1, h1, mask1);

                        Arrays.fill(union2, 0L);
                        for (int u = 0; u < k; u++) {
                            if (u == t2) continue;
                            long[] m = (u == t1) ? mask1 : covMasks[u];
                            for (int w = 0; w < geo.words; w++) union2[w] |= m[w];
                        }

                        taken[t1].add(s1);
                        for (int j = 0; j < nTry; j++) {
                            int s2 = free.get(j);
                            if (s2 == s1 || !coveredBy(s2, skills[t2])) continue;
                            taken[t2].add(s2);
                            double sc = evalScore(oid, taken);
                            if (sc > bestSc + 1e-9) {
                                int h2 = geo.testAddSiteToHull(hulls[t2], hLens[t2], covMasks[t2], union2, s2, candHull2);
                                if (h2 >= 0 && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                                    bestSc = sc;
                                    bestT1 = t1;
                                    bestT2 = t2;
                                    bestS1 = s1;
                                    bestS2 = s2;
                                }
                            }
                            taken[t2].remove(taken[t2].size() - 1);
                        }
                        taken[t1].remove(taken[t1].size() - 1);
                    }
                }
            }

            if (bestT1 < 0) return false;
            taken[bestT1].add(bestS1);
            taken[bestT2].add(bestS2);
            usedSite[bestS1] = true;
            usedSite[bestS2] = true;
            rebuildOfficeHull(oid[bestT1], taken[bestT1], hulls[bestT1], hLens, bestT1, covMasks[bestT1]);
            rebuildOfficeHull(oid[bestT2], taken[bestT2], hulls[bestT2], hLens, bestT2, covMasks[bestT2]);
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        private boolean trySwapBetweenOffices(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                              int[][] hulls, int[] hLens, long[][] covMasks,
                                              boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            int bestT1 = -1, bestI1 = -1, bestT2 = -1, bestI2 = -1;
            double bestSc = curSc;

            int capPerOff = inst.sites.length <= 100 ? 30 : 12;
            int[] tmpHull1 = new int[inst.sites.length + 2];
            int[] tmpHull2 = new int[inst.sites.length + 2];
            int[] tmpLen = new int[2];
            long[] tmpMask1 = new long[geo.words];
            long[] tmpMask2 = new long[geo.words];

            for (int t1 = 0; t1 < k && timeLeft(); t1++) {
                int n1 = Math.min(taken[t1].size(), capPerOff);
                for (int t2 = t1 + 1; t2 < k; t2++) {
                    int n2 = Math.min(taken[t2].size(), capPerOff);
                    for (int i1 = 0; i1 < n1; i1++) {
                        int s1 = taken[t1].get(i1);
                        if (!coveredBy(s1, skills[t2])) continue;
                        for (int i2 = 0; i2 < n2; i2++) {
                            int s2 = taken[t2].get(i2);
                            if (!coveredBy(s2, skills[t1])) continue;

                            taken[t1].set(i1, s2);
                            taken[t2].set(i2, s1);
                            double sc = evalScore(oid, taken);
                            if (sc > bestSc + 1e-9) {
                                rebuildOfficeHull(oid[t1], taken[t1], tmpHull1, tmpLen, 0, tmpMask1);
                                rebuildOfficeHull(oid[t2], taken[t2], tmpHull2, tmpLen, 1, tmpMask2);
                                boolean geoOk = !FastGeo.masksIntersect(tmpMask1, tmpMask2, geo.words);
                                for (int u = 0; u < k && geoOk; u++) {
                                    if (u == t1 || u == t2) continue;
                                    if (FastGeo.masksIntersect(tmpMask1, covMasks[u], geo.words)
                                            || FastGeo.masksIntersect(tmpMask2, covMasks[u], geo.words)) {
                                        geoOk = false;
                                    }
                                }
                                if (geoOk && (!dynamicWorkers || checkWorkersFeasible(taken))) {
                                    bestSc = sc;
                                    bestT1 = t1;
                                    bestI1 = i1;
                                    bestT2 = t2;
                                    bestI2 = i2;
                                }
                            }
                            taken[t1].set(i1, s1);
                            taken[t2].set(i2, s2);
                        }
                    }
                }
            }

            if (bestT1 < 0) return false;
            int s1 = taken[bestT1].get(bestI1);
            int s2 = taken[bestT2].get(bestI2);
            taken[bestT1].set(bestI1, s2);
            taken[bestT2].set(bestI2, s1);
            rebuildOfficeHull(oid[bestT1], taken[bestT1], hulls[bestT1], hLens, bestT1, covMasks[bestT1]);
            rebuildOfficeHull(oid[bestT2], taken[bestT2], hulls[bestT2], hLens, bestT2, covMasks[bestT2]);
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        @SuppressWarnings("unchecked")
        private boolean tryPeelBlockingHullVertex(int[] oid, List<Integer>[] taken, BitSet[] skills,
                                                  int[][] hulls, int[] hLens, long[][] covMasks,
                                                  boolean[] usedSite, boolean dynamicWorkers) {
            int k = oid.length;
            double curSc = evalScore(oid, taken);
            if (curSc <= -1e90) return false;

            List<int[]> candidates = new ArrayList<>();
            for (int t = 0; t < k; t++) {
                if (taken[t].size() < 2) continue;
                for (int idx = 0; idx < taken[t].size(); idx++) {
                    int s = taken[t].get(idx);
                    for (int h = 0; h < hLens[t]; h++) {
                        if (hulls[t][h] == s) {
                            candidates.add(new int[]{t, idx, s});
                            break;
                        }
                    }
                }
            }
            if (candidates.isEmpty()) return false;
            candidates.sort(Comparator.comparingDouble(a -> inst.netVal[oid[a[0]]][a[2]]));

            List<Integer>[] savedTaken = copyLists(taken);
            boolean[] savedUsed = usedSite.clone();
            List<Integer>[] bestTaken = null;
            double bestSc = curSc;

            int trials = Math.min(4, candidates.size());
            for (int tr = 0; tr < trials && timeLeft(); tr++) {
                restoreLists(taken, savedTaken);
                System.arraycopy(savedUsed, 0, usedSite, 0, savedUsed.length);
                int t = candidates.get(tr)[0];
                int sBan = candidates.get(tr)[2];
                taken[t].remove(Integer.valueOf(sBan));
                usedSite[sBan] = false;
                for (int u = 0; u < k; u++) {
                    rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
                }

                for (int step = 0; step < 6; step++) {
                    if (!tryAddSingleSite(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers)) {
                        break;
                    }
                }
                trySwapTakenWithFree(oid, taken, skills, hulls, hLens, covMasks, usedSite, dynamicWorkers, false);

                double sc = evalScore(oid, taken);
                if (sc > bestSc + 1e-9 && allHullsDisjoint(covMasks, k) && checkWorkersFeasible(taken)) {
                    bestSc = sc;
                    bestTaken = copyLists(taken);
                }
            }

            if (bestTaken == null) {
                restoreLists(taken, savedTaken);
                System.arraycopy(savedUsed, 0, usedSite, 0, savedUsed.length);
                for (int u = 0; u < k; u++) {
                    rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
                }
                return false;
            }

            restoreLists(taken, bestTaken);
            Arrays.fill(usedSite, false);
            for (int u = 0; u < k; u++) {
                for (int s : taken[u]) usedSite[s] = true;
                rebuildOfficeHull(oid[u], taken[u], hulls[u], hLens, u, covMasks[u]);
            }
            if (dynamicWorkers) syncWorkerSkills(taken, skills);
            return true;
        }

        @SuppressWarnings("unchecked")
        private Solution ruinAndRecreateALNS(int[] oid, Solution startSol) {
            Solution bestSol = startSol;
            int iters = isTiny() ? 6 : (isCompact() ? 4 : 2);
            for (int it = 0; it < iters && timeLeft(); it++) {
                int k = bestSol.offices.size();
                if (k == 0) break;
                int[] curOid = new int[k];
                List<Integer>[] taken = new List[k];
                BitSet[] skills = new BitSet[k];
                boolean[] usedSite = new boolean[inst.sites.length];
                int[][] hulls = new int[k][inst.sites.length + 2];
                int[] hLens = new int[k];
                long[][] covMasks = new long[k][geo.words];

                for (int t = 0; t < k; t++) {
                    OpenedOffice o = bestSol.offices.get(t);
                    curOid[t] = o.officeId;
                    taken[t] = new ArrayList<>(o.sites);
                    skills[t] = new BitSet();
                    for (int w : o.workers) skills[t].or(inst.workerSkills[w]);
                    for (int s : o.sites) usedSite[s] = true;
                }
                syncWorkerSkills(taken, skills);

                for (int t = 0; t < k; t++) {
                    int nDrop = Math.min(taken[t].size() - 1, 1 + randInt(2));
                    for (int d = 0; d < nDrop && taken[t].size() > 1; d++) {
                        int pick = randInt(taken[t].size());
                        int s = taken[t].remove(pick);
                        usedSite[s] = false;
                    }
                    rebuildOfficeHull(curOid[t], taken[t], hulls[t], hLens, t, covMasks[t]);
                }

                Solution cand = optimizeAndPack(curOid, taken, skills, hulls, hLens, covMasks, usedSite, false, true);
                if (cand.objective > bestSol.objective + 1e-9) {
                    bestSol = cand;
                }
            }
            return bestSol;
        }

        /* ==================== HELPERS & FINALIZATION ==================== */

        private void rebuildOfficeHull(int officeId, List<Integer> sites, int[] hull, int[] hLens, int t, long[] covMask) {
            int n = sites.size();
            int[] pts = new int[n + 1];
            pts[0] = geo.nS + officeId;
            for (int i = 0; i < n; i++) pts[i + 1] = sites.get(i);
            hLens[t] = geo.buildHull(pts, n + 1, hull);
            geo.computeCoverMask(hull, hLens[t], covMask);
        }

        private boolean allHullsDisjoint(long[][] covMasks, int k) {
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    if (FastGeo.masksIntersect(covMasks[i], covMasks[j], geo.words)) return false;
                }
            }
            return true;
        }

        private boolean checkWorkersFeasible(List<Integer>[] taken) {
            List<BitSet> needs = new ArrayList<>(taken.length);
            for (List<Integer> g : taken) {
                if (g.isEmpty()) continue;
                needs.add(skillsOf(g));
            }
            if (needs.isEmpty()) return false;
            return workerSolver.allocate(needs.toArray(BitSet[]::new)) != null;
        }

        private void syncWorkerSkills(List<Integer>[] taken, BitSet[] skills) {
            List<Integer> nonEmptyIdx = new ArrayList<>();
            List<BitSet> needs = new ArrayList<>();
            for (int t = 0; t < taken.length; t++) {
                if (!taken[t].isEmpty()) {
                    nonEmptyIdx.add(t);
                    needs.add(skillsOf(taken[t]));
                }
            }
            if (needs.isEmpty()) return;
            List<Integer>[] alloc = workerSolver.allocate(needs.toArray(BitSet[]::new));
            if (alloc == null) return;
            boolean[] usedW = new boolean[inst.workers.length];
            for (int i = 0; i < nonEmptyIdx.size(); i++) {
                int t = nonEmptyIdx.get(i);
                skills[t].clear();
                for (int w : alloc[i]) {
                    skills[t].or(inst.workerSkills[w]);
                    usedW[w] = true;
                }
            }
            BitSet freeSkills = new BitSet();
            for (int w = 0; w < inst.workers.length; w++) {
                if (!usedW[w]) freeSkills.or(inst.workerSkills[w]);
            }
            for (int t = 0; t < skills.length; t++) {
                skills[t].or(freeSkills);
            }
        }

        private double evalScore(int[] oid, List<Integer>[] taken) {
            int opened = 0;
            int minS = Integer.MAX_VALUE, maxS = 0;
            int minT = Integer.MAX_VALUE, maxT = 0;
            double rewardDist = 0.0;
            double cost = 0.0;
            for (int t = 0; t < oid.length; t++) {
                int sz = taken[t].size();
                if (sz == 0) continue;
                if (sz > inst.offices[oid[t]].maxSize) return -1e100;
                opened++;
                cost += inst.offices[oid[t]].cost;
                int tasks = 0;
                int o = oid[t];
                for (int s : taken[t]) {
                    rewardDist += inst.netVal[o][s];
                    tasks += inst.siteTaskCount[s];
                }
                minS = Math.min(minS, sz);
                maxS = Math.max(maxS, sz);
                minT = Math.min(minT, tasks);
                maxT = Math.max(maxT, tasks);
            }
            if (opened == 0 || maxS - minS > inst.maxDifference) return -1e100;
            return rewardDist - cost - inst.balanceCoefficient * (maxT - minT);
        }

        private Solution finalizeSolution(int[] oid, List<Integer>[] taken) {
            List<Integer> usedOff = new ArrayList<>();
            List<List<Integer>> groups = new ArrayList<>();
            List<BitSet> needs = new ArrayList<>();
            for (int t = 0; t < oid.length; t++) {
                if (taken[t].isEmpty()) continue;
                List<Integer> sites = new ArrayList<>(taken[t]);
                usedOff.add(oid[t]);
                groups.add(sites);
                needs.add(skillsOf(sites));
            }
            if (usedOff.isEmpty()) return Solution.empty();

            List<Integer>[] workers = workerSolver.allocate(needs.toArray(BitSet[]::new));
            if (workers == null) {
                int guard = 0;
                while (workers == null && guard++ < inst.sites.length && !groups.isEmpty()) {
                    int dropG = -1, dropIdx = -1;
                    double worstMetric = Double.POSITIVE_INFINITY;
                    for (int g = 0; g < groups.size(); g++) {
                        int o = usedOff.get(g);
                        List<Integer> list = groups.get(g);
                        for (int j = 0; j < list.size(); j++) {
                            int s = list.get(j);
                            int minSup = Integer.MAX_VALUE;
                            BitSet sk = inst.sites[s].skillSet;
                            for (int b = sk.nextSetBit(0); b >= 0; b = sk.nextSetBit(b + 1)) {
                                minSup = Math.min(minSup, inst.skillSupply[b]);
                            }
                            double metric = minSup * 1000.0 + inst.netVal[o][s];
                            if (metric < worstMetric) {
                                worstMetric = metric;
                                dropG = g;
                                dropIdx = j;
                            }
                        }
                    }
                    if (dropG < 0) break;
                    groups.get(dropG).remove(dropIdx);
                    if (groups.get(dropG).isEmpty()) {
                        groups.remove(dropG);
                        usedOff.remove(dropG);
                    }
                    if (groups.isEmpty()) return Solution.empty();
                    needs.clear();
                    for (List<Integer> g : groups) needs.add(skillsOf(g));
                    workers = workerSolver.allocate(needs.toArray(BitSet[]::new));
                }
                if (workers == null) return Solution.empty();
            }

            int guard = 0;
            while (guard++ < inst.sites.length + 2 && groups.size() >= 2) {
                int minI = 0, maxI = 0;
                for (int i = 1; i < groups.size(); i++) {
                    if (groups.get(i).size() < groups.get(minI).size()) minI = i;
                    if (groups.get(i).size() > groups.get(maxI).size()) maxI = i;
                }
                if (groups.get(maxI).size() - groups.get(minI).size() <= inst.maxDifference) break;
                int o = usedOff.get(maxI);
                List<Integer> big = groups.get(maxI);
                int worstJ = worstSiteIndexInOffice(o, big);
                big.remove(worstJ);
                if (big.isEmpty()) {
                    groups.remove(maxI);
                    usedOff.remove(maxI);
                }
            }
            if (usedOff.isEmpty()) return Solution.empty();

            needs.clear();
            for (List<Integer> g : groups) needs.add(skillsOf(g));
            workers = workerSolver.allocate(needs.toArray(BitSet[]::new));
            if (workers == null) return Solution.empty();

            List<OpenedOffice> opened = new ArrayList<>(usedOff.size());
            for (int i = 0; i < usedOff.size(); i++) {
                opened.add(new OpenedOffice(usedOff.get(i), groups.get(i), workers[i]));
            }

            if (opened.size() > 1 && !Constraints.valid(inst, opened)) {
                opened = repairConflictingHullsTargeted(opened);
                if (opened.isEmpty() || !Constraints.valid(inst, opened)) {
                    return Solution.empty();
                }
            }

            return new Solution(opened, Solution.objectiveOf(inst, opened));
        }

        private List<OpenedOffice> repairConflictingHullsTargeted(List<OpenedOffice> opened) {
            int guard = 0;
            while (guard++ < inst.sites.length && opened.size() > 1 && !Constraints.valid(inst, opened)) {
                int n = opened.size();
                long[][] masks = new long[n][geo.words];
                int[][] hulls = new int[n][inst.sites.length + 2];
                int[] hLens = new int[n];
                for (int i = 0; i < n; i++) {
                    OpenedOffice o = opened.get(i);
                    rebuildOfficeHull(o.officeId, o.sites, hulls[i], hLens, i, masks[i]);
                }
                int confA = -1, confB = -1;
                for (int i = 0; i < n && confA < 0; i++) {
                    for (int j = i + 1; j < n; j++) {
                        if (FastGeo.masksIntersect(masks[i], masks[j], geo.words)) {
                            confA = i;
                            confB = j;
                            break;
                        }
                    }
                }
                if (confA < 0) break;

                int target = opened.get(confA).sites.size() >= opened.get(confB).sites.size() ? confA : confB;
                OpenedOffice o = opened.get(target);
                if (o.sites.size() <= 1) {
                    opened.remove(target);
                    continue;
                }
                int otherOff = opened.get(target == confA ? confB : confA).officeId;
                int bestRemoveIdx = 0;
                double bestScore = Double.POSITIVE_INFINITY;
                for (int idx = 0; idx < o.sites.size(); idx++) {
                    int s = o.sites.get(idx);
                    double distToOther = inst.euclidDist2[otherOff][s];
                    double sc = inst.netVal[o.officeId][s] + 0.5 * Math.sqrt(distToOther);
                    if (sc < bestScore) {
                        bestScore = sc;
                        bestRemoveIdx = idx;
                    }
                }
                List<Integer> nextSites = new ArrayList<>(o.sites);
                nextSites.remove(bestRemoveIdx);
                opened.set(target, new OpenedOffice(o.officeId, nextSites, o.workers));

                int minS = Integer.MAX_VALUE, maxS = 0, maxIdx = 0;
                for (int i = 0; i < opened.size(); i++) {
                    int sz = opened.get(i).sites.size();
                    minS = Math.min(minS, sz);
                    if (sz > maxS) {
                        maxS = sz;
                        maxIdx = i;
                    }
                }
                if (maxS - minS > inst.maxDifference && opened.get(maxIdx).sites.size() > 1) {
                    OpenedOffice big = opened.get(maxIdx);
                    List<Integer> bs = new ArrayList<>(big.sites);
                    bs.remove(worstSiteIndexInOffice(big.officeId, bs));
                    opened.set(maxIdx, new OpenedOffice(big.officeId, bs, big.workers));
                }
            }
            return opened;
        }

        private int worstSiteIndexInOffice(int officeId, List<Integer> sites) {
            int worstIdx = 0;
            double worstVal = Double.POSITIVE_INFINITY;
            for (int i = 0; i < sites.size(); i++) {
                double v = inst.netVal[officeId][sites.get(i)];
                if (v < worstVal) {
                    worstVal = v;
                    worstIdx = i;
                }
            }
            return worstIdx;
        }

        private int[] rankSitesForOffice(int officeId) {
            int n = inst.sites.length;
            Integer[] ord = new Integer[n];
            for (int s = 0; s < n; s++) ord[s] = s;
            Arrays.sort(ord, (a, b) -> Double.compare(inst.netVal[officeId][b], inst.netVal[officeId][a]));
            int[] res = new int[n];
            for (int i = 0; i < n; i++) res[i] = ord[i];
            return res;
        }

        private double bestNetVal(int[] oid, int site) {
            double best = -1e100;
            for (int o : oid) best = Math.max(best, inst.netVal[o][site]);
            return best;
        }

        private boolean coveredBy(int siteId, BitSet skills) {
            BitSet need = inst.sites[siteId].skillSet;
            for (int s = need.nextSetBit(0); s >= 0; s = need.nextSetBit(s + 1)) {
                if (!skills.get(s)) return false;
            }
            return true;
        }

        private BitSet skillsOf(List<Integer> sites) {
            BitSet need = new BitSet();
            for (int s : sites) need.or(inst.sites[s].skillSet);
            return need;
        }

        private BitSet[] cloneBitSets(BitSet[] src) {
            BitSet[] out = new BitSet[src.length];
            for (int i = 0; i < src.length; i++) out[i] = (BitSet) src[i].clone();
            return out;
        }

        @SuppressWarnings("unchecked")
        private List<Integer>[] copyLists(List<Integer>[] src) {
            List<Integer>[] out = new List[src.length];
            for (int i = 0; i < src.length; i++) out[i] = new ArrayList<>(src[i]);
            return out;
        }

        private void restoreLists(List<Integer>[] dst, List<Integer>[] src) {
            for (int i = 0; i < dst.length; i++) {
                dst[i].clear();
                dst[i].addAll(src[i]);
            }
        }

        private static long binom(int n, int k) {
            if (k < 0 || k > n) return 0;
            long r = 1;
            for (int i = 1; i <= k; i++) {
                r = r * (n - k + i) / i;
                if (r > 10_000_000L) return r;
            }
            return r;
        }
    }
}