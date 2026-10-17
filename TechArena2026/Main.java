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
 * Эвристический солвер TechArena 2026: открытие сервисных офисов,
 * назначение объектов и специалистов.
 *
 * Целевая функция (максимизация):
 * reward − distance − cost − alpha * (maxTasks − minTasks)
 *
 * Ограничения повторяют validate_solution.py: индексы, ёмкость, баланс числа
 * объектов, покрытие навыков, непересекающиеся выпуклые оболочки (офис + объекты).
 *
 * Крупные скрытые: пары до 5000, затем выборка k=4/5 из остатка CPU.
 * Thorough — рост оболочек по reward−dist, не по индексу объекта.
 * MLP прогнозирует зазор cheap→thorough. Навыки двигаются, чтобы взять leftover.
 */
public class Main {

    /**
     * Лимит NSUTS — процессорное время ~300 с. Многопоток его умножает и даёт TLE.
     * Держим один поток и оставляем запас.
     */
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

    /* ============================ JSON ============================ */

    /** Минимальный JSON-парсер (объекты, массивы, строки, числа). */
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
                if (c == '{') {
                    return parseObject();
                }
                if (c == '[') {
                    return parseArray();
                }
                if (c == '"') {
                    return parseString();
                }
                if (c == '-' || Character.isDigit(c)) {
                    return parseNumber();
                }
                throw new RuntimeException("bad json at " + i);
            }

            String parseString() {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c == '\\') {
                        out.append(s.charAt(i++));
                    } else {
                        out.append(c);
                    }
                }
                throw new RuntimeException("unterminated string");
            }

            Number parseNumber() {
                int start = i;
                if (s.charAt(i) == '-') {
                    i++;
                }
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
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    return (int) v;
                }
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
                    if (c == '}') {
                        return map;
                    }
                    if (c != ',') {
                        throw new RuntimeException("expected , or }");
                    }
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
                    if (c == ']') {
                        return list;
                    }
                    if (c != ',') {
                        throw new RuntimeException("expected , or ]");
                    }
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

    /* ============================ МОДЕЛЬ ============================ */

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
        /** Типы задач (навыки), которые нужны на объекте. */
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
        final int[] workerPrimarySkill;
        /** Сколько работников владеют навыком skill. */
        final int[] skillSupply;
        final double[][] distances;
        final int maxDifference;
        final double balanceCoefficient;
        final int skillBound;
        /** Навыки, которые вообще нельзя покрыть. */
        final BitSet coveredGlobally;

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
            this.workerPrimarySkill = new int[workers.length];
            for (int i = 0; i < workers.length; i++) {
                workerSkills[i] = new BitSet();
                workerPrimarySkill[i] = workers[i].length == 0 ? -1 : workers[i][0];
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

        @SuppressWarnings("unchecked")
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

        static List<Integer> intList(Object value) {
            int[] a = intArray(value);
            List<Integer> out = new ArrayList<>(a.length);
            for (int v : a) {
                out.add(v);
            }
            return out;
        }

        boolean siteFeasible(int siteId) {
            BitSet need = sites[siteId].skillSet;
            for (int s = need.nextSetBit(0); s >= 0; s = need.nextSetBit(s + 1)) {
                if (!coveredGlobally.get(s)) {
                    return false;
                }
            }
            return true;
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

        /**
         * Целевая как в validate_solution.py.
         * Штраф α — по сумме len(tasks). Жёсткий maxDifference — по числу объектов, не по задачам.
         */
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

        static Solution checked(Instance inst, List<OpenedOffice> opened) {
            if (opened.isEmpty() || !Constraints.valid(inst, opened)) {
                return empty();
            }
            return new Solution(opened, objectiveOf(inst, opened));
        }

        @SuppressWarnings("unchecked")
        static Solution fromJson(Instance inst, Object raw) {
            if (!(raw instanceof List<?> arr)) {
                return empty();
            }
            List<OpenedOffice> opened = new ArrayList<>();
            for (Object item : arr) {
                if (!(item instanceof Map<?, ?> map)) {
                    return empty();
                }
                Map<String, Object> m = (Map<String, Object>) map;
                opened.add(new OpenedOffice(
                        (int) Instance.num(m.get("officeId")),
                        Instance.intList(m.get("sites")),
                        Instance.intList(m.get("workers"))));
            }
            return checked(inst, opened);
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
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(values.get(i));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /* ============================ ГЕОМЕТРИЯ ============================ */

    /**
     * Выпуклая оболочка и проверка покрытия точки (включая границу),
     * по смыслу как shapely MultiPoint.convex_hull.covers.
     */
    static final class Geometry {
        private static final double EPS = 1e-12;

        static List<Vec> convexHull(List<Vec> points) {
            // Сначала сортировка, потом соседние дубликаты: попарное удаление было O(n²).
            List<Vec> sorted = new ArrayList<>(points);
            sorted.sort(Comparator
                    .comparingDouble((Vec p) -> p.x)
                    .thenComparingDouble(p -> p.y));
            List<Vec> unique = new ArrayList<>(sorted.size());
            for (Vec p : sorted) {
                if (unique.isEmpty() || !same(unique.get(unique.size() - 1), p)) {
                    unique.add(p);
                }
            }
            int n = unique.size();
            if (n <= 2) {
                return unique;
            }
            List<Vec> lower = new ArrayList<>();
            for (Vec p : unique) {
                while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= EPS) {
                    lower.remove(lower.size() - 1);
                }
                lower.add(p);
            }
            List<Vec> upper = new ArrayList<>();
            for (int i = unique.size() - 1; i >= 0; i--) {
                Vec p = unique.get(i);
                while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= EPS) {
                    upper.remove(upper.size() - 1);
                }
                upper.add(p);
            }
            lower.remove(lower.size() - 1);
            upper.remove(upper.size() - 1);
            lower.addAll(upper);
            return lower;
        }

        static boolean covers(List<Vec> hull, Vec p) {
            int n = hull.size();
            if (n == 0) {
                return false;
            }
            if (n == 1) {
                return same(hull.get(0), p);
            }
            if (n == 2) {
                return onSegment(hull.get(0), hull.get(1), p);
            }
            boolean hasPos = false;
            boolean hasNeg = false;
            for (int i = 0; i < n; i++) {
                double cr = cross(hull.get(i), hull.get((i + 1) % n), p);
                if (cr > EPS) {
                    hasPos = true;
                } else if (cr < -EPS) {
                    hasNeg = true;
                }
                if (hasPos && hasNeg) {
                    return false;
                }
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
            if (Math.abs(cross(a, b, p)) > EPS) {
                return false;
            }
            return p.x + EPS >= Math.min(a.x, b.x) && p.x - EPS <= Math.max(a.x, b.x)
                    && p.y + EPS >= Math.min(a.y, b.y) && p.y - EPS <= Math.max(a.y, b.y);
        }

        static double euclid2(Vec a, Vec b) {
            double dx = a.x - b.x;
            double dy = a.y - b.y;
            return dx * dx + dy * dy;
        }
    }

    /** Проверки, эквивалентные validate_solution.py. */
    static final class Constraints {
        static boolean valid(Instance inst, List<OpenedOffice> opened) {
            int nO = inst.offices.length;
            int nS = inst.sites.length;
            int nW = inst.workers.length;
            boolean[] usedOffice = new boolean[nO];
            boolean[] usedSite = new boolean[nS];
            boolean[] usedWorker = new boolean[nW];
            for (OpenedOffice o : opened) {
                if (o.officeId < 0 || o.officeId >= nO || usedOffice[o.officeId]) {
                    return false;
                }
                usedOffice[o.officeId] = true;
                if (o.sites.size() > inst.offices[o.officeId].maxSize) {
                    return false;
                }
                for (int s : o.sites) {
                    if (s < 0 || s >= nS || usedSite[s]) {
                        return false;
                    }
                    usedSite[s] = true;
                }
                for (int w : o.workers) {
                    if (w < 0 || w >= nW || usedWorker[w]) {
                        return false;
                    }
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
                        if (!ok) {
                            return false;
                        }
                    }
                }
            }
            // maxDifference — число объектов. Штраф α в цели считается по числу задач.
            if (!opened.isEmpty() && maxSites - minSites > inst.maxDifference) {
                return false;
            }
            BitSet[] covered = new BitSet[opened.size()];
            for (int i = 0; i < opened.size(); i++) {
                OpenedOffice o = opened.get(i);
                List<Vec> raw = new ArrayList<>();
                raw.add(inst.offices[o.officeId].pos);
                for (int s : o.sites) {
                    raw.add(inst.sites[s].pos);
                }
                List<Vec> hull = Geometry.convexHull(raw);
                BitSet mask = new BitSet(nS + nO);
                for (int s = 0; s < nS; s++) {
                    if (Geometry.covers(hull, inst.sites[s].pos)) {
                        mask.set(s);
                    }
                }
                for (int j = 0; j < nO; j++) {
                    if (Geometry.covers(hull, inst.offices[j].pos)) {
                        mask.set(nS + j);
                    }
                }
                covered[i] = mask;
            }
            for (int i = 0; i < opened.size(); i++) {
                for (int j = i + 1; j < opened.size(); j++) {
                    if (covered[i].intersects(covered[j])) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * Residual MLP только как прогноз зазора thorough − cheap.
     * Назначение объектов модель не трогает. Cheap-метки в SGD не идут.
     */
    static final class RankNet {
        static final int DIM = 18;
        static final int HID = 8;
        /** Линейный приор после SGD на жёстких картах (LocalSuite fit). */
        private static final double[] PRIOR_LIN = {
                -0.10670, 0.39137, -0.53374, 0.77727, -0.13683, -0.15072, -0.84097, 0.52868,
                0.18807, -0.03099, 0.03881, -0.09242, 0.05208, 0.53374, 0.16121, 0.01117,
                -0.70048, -0.20619
        };
        /** Скрытый слой: дообучен на leftover/hull-картах, на тесте ещё SGD. */
        private static final double[][] PRIOR_W1 = {
                {-0.03600, 0.00000, 0.03600, -0.06000, -0.02400, 0.01200, 0.04800, -0.04800,
                        -0.01200, 0.02400, 0.06000, -0.03600, 0.00000, 0.03600, -0.06000, -0.02400, 0.01200, 0.04800},
                {0.03498, 0.05247, -0.03809, 0.05262, 0.09132, -0.00998, -0.02479, -0.00166,
                        0.08159, -0.01400, 0.06358, -0.00182, 0.06961, 0.01850, 0.02470, 0.05193, -0.07518, -0.03076},
                {-0.05946, -0.01288, 0.02393, 0.04806, -0.04794, -0.01194, 0.03594, -0.06160,
                        -0.02400, 0.01200, 0.03654, -0.05094, -0.01376, 0.01206, 0.05014, -0.04746, 0.00000, 0.03600},
                {0.03295, 0.05654, -0.04569, -0.02475, 0.00896, 0.04911, -0.03488, 0.01614,
                        0.02414, -0.07147, -0.04475, 0.03990, 0.04457, -0.05482, -0.00709, 0.04128, 0.06999, -0.03095},
                {-0.06000, -0.02400, 0.01200, 0.04800, -0.04800, -0.01200, 0.02400, 0.06000,
                        -0.03600, 0.00000, 0.03600, -0.06000, -0.02400, 0.01200, 0.04800, -0.04800, -0.01200, 0.02400},
                {-0.00379, 0.03537, -0.06010, -0.02611, 0.00997, 0.04581, -0.04805, -0.01337,
                        0.02066, 0.05666, -0.03722, -0.00183, 0.03568, -0.06392, -0.02641, 0.00838, 0.04800, -0.04811},
                {0.06849, -0.04021, -0.00138, 0.07084, -0.02295, 0.00930, 0.01140, 0.03961,
                        -0.02581, 0.01004, 0.07509, 0.04366, -0.02942, 0.03642, 0.05278, -0.04781, -0.03364, 0.00741},
                {-0.01964, 0.02283, 0.05980, -0.04106, -0.00491, 0.03078, -0.06008, -0.02634,
                        0.00664, 0.04264, -0.05116, -0.01540, 0.02320, 0.05264, -0.04130, -0.00736, 0.03612, -0.06029}
        };
        private static final double[] PRIOR_B1 = {
                0.00000, 0.01118, -0.01146, 0.02105, 0.00000, -0.00379, 0.00881, -0.00764
        };
        private static final double[] PRIOR_W2 = {
                0.18000, -0.16533, 0.21776, 0.12002, -0.16000, 0.13981, -0.09463, 0.10919
        };
        private static final double LR = 0.02;
        private static final double L2 = 1e-4;
        private static final double CLIP = 3.0;

        private final Instance inst;
        private final double[] wLin;
        private final double[][] w1;
        private final double[] b1;
        private final double[] w2;
        private final double[] officeSingle;
        private final double diag;
        private final double siteDiag;
        private final double totalReward;
        private final Map<String, Double> cheapOf = new HashMap<>();
        private double scale = 1.0;
        private int predictedK = 1;
        private int[] champIds;
        private double champObj = -1e100;
        /** Евклид между офисами, один раз: в признаках пара считается многократно. */
        private final double[][] officeEuclid;
        /** Порядок officeSingle после одиночных сборок. До freezeSingles пусто. */
        private int[] betterCount;
        private double[] topPrefix;

        RankNet(Instance inst, double[] officeSingle) {
            this.inst = inst;
            this.officeSingle = officeSingle;
            this.wLin = PRIOR_LIN.clone();
            this.w1 = copyMat(PRIOR_W1);
            this.b1 = PRIOR_B1.clone();
            this.w2 = PRIOR_W2.clone();
            this.totalReward = sumRewards(inst);
            this.diag = Math.max(1e-9, bboxDiagOffices(inst));
            this.siteDiag = Math.max(1e-9, bboxDiagSites(inst));
            this.scale = Math.max(1.0, totalReward / Math.max(1, inst.offices.length));
            int nOff = inst.offices.length;
            this.officeEuclid = new double[nOff][nOff];
            for (int i = 0; i < nOff; i++) {
                for (int j = i + 1; j < nOff; j++) {
                    double d = Math.sqrt(Geometry.euclid2(inst.offices[i].pos, inst.offices[j].pos));
                    officeEuclid[i][j] = d;
                    officeEuclid[j][i] = d;
                }
            }
        }

        /**
         * Одиночные цели больше не меняются. Дальше features не сортирует офисы заново.
         */
        void freezeSingles() {
            int nOff = inst.offices.length;
            Integer[] order = new Integer[nOff];
            for (int o = 0; o < nOff; o++) {
                order[o] = o;
            }
            Arrays.sort(order, (a, b) -> Double.compare(clampedSingle(b), clampedSingle(a)));
            betterCount = new int[nOff];
            topPrefix = new double[nOff + 1];
            for (int i = 0; i < nOff; i++) {
                topPrefix[i + 1] = topPrefix[i] + clampedSingle(order[i]);
            }
            for (int id = 0; id < nOff; id++) {
                double s = officeSingle[id];
                if (s < -1e90 || !Double.isFinite(s)) {
                    s = 0.0;
                }
                int better = 0;
                for (int o = 0; o < nOff; o++) {
                    if (officeSingle[o] > s) {
                        better++;
                    }
                }
                betterCount[id] = better;
            }
        }

        private double clampedSingle(int id) {
            double s = officeSingle[id];
            if (s < -1e90 || !Double.isFinite(s)) {
                return 0.0;
            }
            return s;
        }

        void setPredictedK(int k) {
            this.predictedK = Math.max(1, k);
        }

        void rememberCheap(int[] ids, double objective) {
            if (ids == null || ids.length == 0 || !Double.isFinite(objective)) {
                return;
            }
            cheapOf.put(key(ids), objective);
        }

        /** Cheap только как база: SGD по нему портит прогноз зазора. */
        void observe(int[] ids, double objective) {
            rememberCheap(ids, objective);
        }

        /** Учит gap = thorough − cheap. Pairwise — по predicted thorough. */
        void observeThorough(int[] ids, double objective) {
            if (ids == null || ids.length == 0 || !Double.isFinite(objective)) {
                return;
            }
            Double cheap = cheapOf.get(key(ids));
            double gap = objective - (cheap == null ? 0.0 : cheap);
            double[] x = features(ids);
            Act act = forward(x);
            double err = act.y - gap / scale;
            if (Double.isFinite(err)) {
                backward(x, act, err);
            }
            if (champIds != null && Math.abs(objective - champObj) > 1e-9) {
                if (objective > champObj) {
                    rankToward(ids, champIds);
                } else {
                    rankToward(champIds, ids);
                }
            }
            if (objective > champObj) {
                champObj = objective;
                champIds = ids.clone();
                Arrays.sort(champIds);
            }
            if (Math.abs(gap) > 1e-9) {
                scale = Math.max(scale, Math.abs(gap) * 0.25 + scale * 0.75);
            }
        }

        private void rankToward(int[] better, int[] worse) {
            double sb = predict(better) / scale;
            double sw = predict(worse) / scale;
            double s = Math.max(-20.0, Math.min(20.0, sb - sw));
            double sigma = 1.0 / (1.0 + Math.exp(-s));
            double g = 1.0 - sigma;
            double[] xb = features(better);
            double[] xw = features(worse);
            backward(xb, forward(xb), -g);
            backward(xw, forward(xw), g);
        }

        private static final class Act {
            final double[] h;
            final double y;

            Act(double[] h, double y) {
                this.h = h;
                this.y = y;
            }
        }

        private Act forward(double[] x) {
            double[] h = new double[HID];
            for (int u = 0; u < HID; u++) {
                double s = b1[u];
                for (int d = 0; d < DIM; d++) {
                    s += w1[u][d] * x[d];
                }
                h[u] = s > 0.0 ? s : 0.0;
            }
            double y = dot(wLin, x) + dot(w2, h);
            return new Act(h, y);
        }

        private void backward(double[] x, Act act, double err) {
            if (!Double.isFinite(err)) {
                return;
            }
            for (int d = 0; d < DIM; d++) {
                wLin[d] -= LR * (err * x[d] + L2 * wLin[d]);
                clip(wLin, d);
            }
            for (int u = 0; u < HID; u++) {
                if (act.h[u] <= 0.0) {
                    continue;
                }
                double oldW2 = w2[u];
                w2[u] -= LR * (err * act.h[u] + L2 * w2[u]);
                clip(w2, u);
                // Градиент скрытого слоя — по весу до шага, иначе backprop смотрит уже новое w2.
                double dh = err * oldW2;
                b1[u] -= LR * dh;
                b1[u] = clipVal(b1[u]);
                for (int d = 0; d < DIM; d++) {
                    w1[u][d] -= LR * (dh * x[d] + L2 * w1[u][d]);
                    w1[u][d] = clipVal(w1[u][d]);
                }
            }
        }

        /** cheap + предсказанный зазор; без cheap — сумма одиночных. */
        double predict(int[] ids) {
            double gap = forward(features(ids)).y * scale;
            Double cheap = cheapOf.get(key(ids));
            double base = cheap != null ? cheap : singleSum(ids);
            return base + gap;
        }

        private double singleSum(int[] ids) {
            double s = 0;
            for (int id : ids) {
                double v = officeSingle[id];
                if (v > -1e90 && Double.isFinite(v)) {
                    s += v;
                }
            }
            return s;
        }

        double targetOf(double objective) {
            return objective / scale;
        }

        double[] features(int[] ids) {
            int k = ids.length;
            int nOff = inst.offices.length;
            int nSites = inst.sites.length;
            double sumS = 0;
            double minS = Double.POSITIVE_INFINITY;
            double maxS = Double.NEGATIVE_INFINITY;
            double sumCost = 0;
            double sumCap = 0;
            double minCap = Double.POSITIVE_INFINITY;
            int topN = Math.min(nOff, Math.max(4, k + 2));
            int topHits = 0;
            for (int id : ids) {
                double s = officeSingle[id];
                if (s < -1e90 || !Double.isFinite(s)) {
                    s = 0.0;
                }
                sumS += s;
                minS = Math.min(minS, s);
                maxS = Math.max(maxS, s);
                sumCost += inst.offices[id].cost;
                sumCap += inst.offices[id].maxSize;
                minCap = Math.min(minCap, inst.offices[id].maxSize);
                int better = betterCount == null ? countBetter(s) : betterCount[id];
                if (better < topN) {
                    topHits++;
                }
            }
            double minPair = 0;
            double meanPair = 0;
            if (k >= 2) {
                minPair = Double.POSITIVE_INFINITY;
                int pairs = 0;
                for (int i = 0; i < k; i++) {
                    for (int j = i + 1; j < k; j++) {
                        double d = officeEuclid[ids[i]][ids[j]];
                        minPair = Math.min(minPair, d);
                        meanPair += d;
                        pairs++;
                    }
                }
                meanPair /= Math.max(1, pairs);
            }
            double topK = topPrefix == null ? topKSum(k) : topPrefix[Math.min(k, nOff)];
            Double cheap = cheapOf.get(key(ids));
            double meanRw = totalReward / Math.max(1, nSites);
            double leftoverGap = 1.0 - Math.min(1.0, sumCap / Math.max(1.0, nSites));
            double hullRisk = k < 2 ? 0.0 : Math.max(0.0, 0.18 - minPair / Math.max(diag, siteDiag));
            double[] x = new double[DIM];
            x[0] = 1.0;
            x[1] = k / (double) Math.max(1, nOff);
            x[2] = x[1] * x[1];
            x[3] = sumS / (scale * Math.max(1, k));
            x[4] = minS / scale;
            x[5] = maxS / scale;
            x[6] = sumCost / (totalReward + 1.0);
            x[7] = sumCap / Math.max(1.0, nSites);
            x[8] = minPair / diag;
            x[9] = meanPair / diag;
            x[10] = topHits / (double) k;
            x[11] = Math.tanh(inst.balanceCoefficient / (meanRw + 1e-6));
            x[12] = Math.abs(k - predictedK) / (double) Math.max(1, nOff);
            x[13] = cheap == null ? 0.0 : cheap / scale;
            x[14] = leftoverGap;
            x[15] = minCap / Math.max(1.0, sumCap / Math.max(1, k));
            x[16] = hullRisk;
            x[17] = (topK - sumS) / (scale * Math.max(1, k));
            return x;
        }

        private int countBetter(double s) {
            int better = 0;
            for (int o = 0; o < inst.offices.length; o++) {
                if (officeSingle[o] > s) {
                    better++;
                }
            }
            return better;
        }

        private double topKSum(int k) {
            int nOff = inst.offices.length;
            Integer[] order = new Integer[nOff];
            for (int o = 0; o < nOff; o++) {
                order[o] = o;
            }
            Arrays.sort(order, (a, b) -> Double.compare(clampedSingle(b), clampedSingle(a)));
            double topK = 0;
            for (int i = 0; i < k && i < nOff; i++) {
                topK += clampedSingle(order[i]);
            }
            return topK;
        }

        static final class FitSample {
            final double[] x;
            final double y;

            FitSample(double[] x, double y) {
                this.x = x;
                this.y = y;
            }
        }

        /** Оффлайн SGD по выборке (x, thorough/scale). Печатает Java-приор. */
        static String trainGlobal(List<FitSample> samples, int epochs) {
            if (samples.isEmpty()) {
                return "no samples";
            }
            double[] wLin = PRIOR_LIN.clone();
            double[][] w1 = copyMat(PRIOR_W1);
            double[] b1 = PRIOR_B1.clone();
            double[] w2 = PRIOR_W2.clone();
            for (int ep = 0; ep < epochs; ep++) {
                for (FitSample s : samples) {
                    double[] h = new double[HID];
                    for (int u = 0; u < HID; u++) {
                        double z = b1[u];
                        for (int d = 0; d < DIM; d++) {
                            z += w1[u][d] * s.x[d];
                        }
                        h[u] = z > 0.0 ? z : 0.0;
                    }
                    double pred = 0;
                    for (int d = 0; d < DIM; d++) {
                        pred += wLin[d] * s.x[d];
                    }
                    for (int u = 0; u < HID; u++) {
                        pred += w2[u] * h[u];
                    }
                    double err = pred - s.y;
                    if (!Double.isFinite(err)) {
                        continue;
                    }
                    for (int d = 0; d < DIM; d++) {
                        wLin[d] -= LR * (err * s.x[d] + L2 * wLin[d]);
                        wLin[d] = clipVal(wLin[d]);
                    }
                    for (int u = 0; u < HID; u++) {
                        if (h[u] <= 0.0) {
                            continue;
                        }
                        double oldW2 = w2[u];
                        w2[u] -= LR * (err * h[u] + L2 * w2[u]);
                        w2[u] = clipVal(w2[u]);
                        double dh = err * oldW2;
                        b1[u] -= LR * dh;
                        b1[u] = clipVal(b1[u]);
                        for (int d = 0; d < DIM; d++) {
                            w1[u][d] -= LR * (dh * s.x[d] + L2 * w1[u][d]);
                            w1[u][d] = clipVal(w1[u][d]);
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("PRIOR_LIN = {");
            for (int d = 0; d < DIM; d++) {
                if (d > 0) {
                    sb.append(", ");
                }
                sb.append(String.format(java.util.Locale.ROOT, "%.5f", wLin[d]));
            }
            sb.append("};\nPRIOR_W2 = {");
            for (int u = 0; u < HID; u++) {
                if (u > 0) {
                    sb.append(", ");
                }
                sb.append(String.format(java.util.Locale.ROOT, "%.5f", w2[u]));
            }
            sb.append("};\nPRIOR_B1 = {");
            for (int u = 0; u < HID; u++) {
                if (u > 0) {
                    sb.append(", ");
                }
                sb.append(String.format(java.util.Locale.ROOT, "%.5f", b1[u]));
            }
            sb.append("};\n");
            sb.append("PRIOR_W1 rows:\n");
            for (int u = 0; u < HID; u++) {
                sb.append("  {");
                for (int d = 0; d < DIM; d++) {
                    if (d > 0) {
                        sb.append(", ");
                    }
                    sb.append(String.format(java.util.Locale.ROOT, "%.5f", w1[u][d]));
                }
                sb.append("},\n");
            }
            return sb.toString();
        }

        private static String key(int[] ids) {
            int[] c = ids.clone();
            Arrays.sort(c);
            return Arrays.toString(c);
        }

        private static double[][] copyMat(double[][] src) {
            double[][] m = new double[src.length][];
            for (int i = 0; i < src.length; i++) {
                m[i] = src[i].clone();
            }
            return m;
        }

        private static void clip(double[] a, int i) {
            a[i] = clipVal(a[i]);
        }

        private static double clipVal(double v) {
            if (v > CLIP) {
                return CLIP;
            }
            if (v < -CLIP) {
                return -CLIP;
            }
            return v;
        }

        private static double dot(double[] a, double[] b) {
            double s = 0;
            for (int i = 0; i < a.length; i++) {
                s += a[i] * b[i];
            }
            return s;
        }

        private static double sumRewards(Instance inst) {
            double s = 0;
            for (Site site : inst.sites) {
                s += site.reward;
            }
            return s;
        }

        private static double bboxDiagOffices(Instance inst) {
            return bboxDiag(inst.offices, null);
        }

        private static double bboxDiagSites(Instance inst) {
            return bboxDiag(null, inst.sites);
        }

        private static double bboxDiag(Office[] offices, Site[] sites) {
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
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

    /** Кандидат объекта внутри кластера. */
    static final class Cand {
        final int site;
        final double value;
        final int tasks;

        Cand(int site, double value, int tasks) {
            this.site = site;
            this.value = value;
            this.tasks = tasks;
        }
    }

    /* ============================ СОЛВЕР ============================ */

    static final class Solver {
        /** Запас на финальную thorough-сборку, polish и запись JSON. */
        private static final long THOROUGH_RESERVE_NS = 12_000_000_000L;
        /** Сколько лучших дешёвых наборов потом считаем thoroughly. */
        private static final int ELITE_CAP = 12;
        /** Сколько наборов помним, чтобы RankNet мог выбрать thorough не только по cheap-obj. */
        private static final int ELITE_STORE = 48;
        private static final int THOROUGH_NEIGHBOR_CAP = 6;
        private static final int RECOVER_GUARD = 80;
        private static final int LEFTOVER_RANK_CAP = 500;
        private static final int DIRECT_TRY_CAP = 160;
        private static final int PAIR_TRY_CAP = 48;
        /** Полный cheap-перебор C(n,k) для k≥3. 5000 на тройках съедал пары 5/11. */
        private static final long ENUM_COMBOS = 450L;
        /**
         * Пары на крупной карте перебираем до 5000: так было в версии
         * с максимумом скрытого теста 11. k≥3 этот потолок не получает.
         */
        private static final long PAIR_ENUM_COMBOS = 5_000L;
        /** Выборка k=4, если C(n,4) уже больше ENUM_COMBOS. */
        private static final int WIDE_K4_CAP = 220;
        /** Выборка k=5 на крупной карте, после пар и четвёрок. */
        private static final int WIDE_K5_CAP = 80;
        /**
         * Рост оболочки на карте крупнее этого размера не сканирует все объекты:
         * берёт верхние по reward−dist, иначе в зону попадают первые индексы.
         */
        private static final int GROW_FULL_SCAN_SITES = 80;
        /** Геопроверок на офис за одно окно роста. */
        private static final int GROW_WINDOW = 28;
        /** Второе окно, если верхние объекты все конфликтуют оболочками. */
        private static final int GROW_EXTRA_WINDOW = 28;

        private final Instance inst;
        private final long deadlineNanos;
        private final long reserveNs;
        private Solution best = Solution.empty();
        private long rng = 20261L;
        private final Set<String> searchedSets = new HashSet<>();
        private final List<Elite> elites = new ArrayList<>();
        private final double[] bestObjByK;
        private final int[][] bestIdsByK;
        private final double[] officeSingle;
        private final RankNet rankNet;

        static final class Elite {
            final int[] ids;
            double obj;

            Elite(int[] ids, double obj) {
                this.ids = ids;
                this.obj = obj;
            }
        }

        Solver(Instance inst, long deadlineNanos) {
            this.inst = inst;
            this.deadlineNanos = deadlineNanos;
            long budget = Math.max(0L, deadlineNanos - System.nanoTime());
            long floor = Math.min(2_000_000_000L, Math.max(budget / 5, 1_000_000L));
            this.reserveNs = Math.min(THOROUGH_RESERVE_NS, Math.max(budget / 10, floor));
            this.bestObjByK = new double[inst.offices.length + 1];
            Arrays.fill(this.bestObjByK, -1e100);
            this.bestIdsByK = new int[inst.offices.length + 1][];
            this.officeSingle = new double[inst.offices.length];
            Arrays.fill(this.officeSingle, -1e100);
            this.rankNet = new RankNet(inst, this.officeSingle);
        }

        boolean timeLeft() {
            // Tiny-карты обязаны добить полный перебор: обрыв по лимиту
            // давал 90% от оракула на локальных тестах.
            return tiny() || System.nanoTime() < deadlineNanos;
        }

        /** Поиск дешёвыми сборками останавливаем раньше, чтобы успеть thorough. */
        boolean searchTimeLeft() {
            return tiny() || System.nanoTime() + reserveNs < deadlineNanos;
        }

        int randInt(int n) {
            if (n <= 1) {
                return 0;
            }
            rng ^= rng << 13;
            rng ^= rng >>> 7;
            rng ^= rng << 17;
            return (int) ((rng & Long.MAX_VALUE) % n);
        }

        Solution solve() {
            int nOff = inst.offices.length;
            if (nOff == 0 || inst.sites.length == 0) {
                return Solution.empty();
            }

            Integer[] rank = new Integer[nOff];
            for (int o = 0; o < nOff; o++) {
                rank[o] = o;
            }
            for (int o = 0; o < nOff && timeLeft(); o++) {
                Solution sol = construct(new int[]{o}, false);
                officeSingle[o] = sol.objective;
                rankNet.observe(new int[]{o}, sol.offices.isEmpty() ? 0.0 : sol.objective);
                consider(sol);
            }
            rankNet.freezeSingles();
            Arrays.sort(rank, (a, b) -> Double.compare(officeSingle[b], officeSingle[a]));

            int predicted = predictK(rank);
            rankNet.setPredictedK(predicted);
            int maxK = Math.min(nOff, inst.workers.length);
            // Tiny/compact: все cheap-маски. Крупные: сначала k=2 и k=3 (это 5/11),
            // k=4 только из остатка. Cheap-all по 2^nOff на nOff=10+ обрывался на младших масках.
            if (canThoroughAll()) {
                searchAllCheap(maxK);
                searchAllMasksRanked(maxK, true);
            } else if (compact()) {
                searchAllCheap(maxK);
                if (searchTimeLeft()) {
                    searchMarginalOffices(rank, maxK);
                }
            } else {
                Set<Integer> searchedK = new HashSet<>();
                for (int k : new int[]{2, 3}) {
                    if (k <= maxK && searchedK.add(k) && searchTimeLeft()) {
                        searchK(k, rank);
                    }
                }
                int lo = Math.max(2, predicted - 1);
                int hi = Math.min(maxK, predicted + 1);
                for (int k = lo; k <= hi && searchTimeLeft(); k++) {
                    if (searchedK.add(k)) {
                        searchK(k, rank);
                    }
                }
                if (maxK >= 4 && searchedK.add(4) && searchTimeLeft()) {
                    searchK(4, rank);
                }
                // k=5 только из остатка CPU: в версии с максимумом теста 11
                // четвёрки и пятёрки искались после пар, не вместо них.
                if (maxK >= 5 && searchedK.add(5) && searchTimeLeft()) {
                    searchK(5, rank);
                }
                searchOfficeLocal(rank, maxK);
                searchSpread(rank, maxK);
                searchNeighbors(rank);
                searchUntilTime(rank, predicted, maxK);
            }

            if (!best.offices.isEmpty() && timeLeft()) {
                consider(construct(bestIds(), true));
            }
            if (timeLeft()) {
                // На крупной карте тоже: чемпион, элита модели и лучший набор каждого k.
                // thoroughBestPerK входит в эту очередь, отдельно его не гоняем.
                refineThorough(rank);
            }
            return best;
        }

        /** Малая карта: можно позволить polish/несколько thorough. */
        private boolean compact() {
            return inst.sites.length <= 250 && inst.offices.length <= 8;
        }

        /** Совсем маленькая карта: полный thorough по маскам ещё укладывается. */
        private boolean tiny() {
            return inst.offices.length <= 6 && inst.sites.length <= 80;
        }

        /**
         * Полный thorough по маскам, если 2^{nOff}·nSites небольшое.
         * Тест 8/9 как раз в этой зоне; 5/11 — нет.
         */
        private boolean canThoroughAll() {
            int nOff = inst.offices.length;
            int nSites = inst.sites.length;
            if (nOff > 7 || nSites > 130) {
                return false;
            }
            return (1L << nOff) * (long) nSites <= 12_000L;
        }

        /** Все cheap-маски без thorough: 2^{nOff} ещё влезает в CPU. */
        private boolean cheapAllFits() {
            int nOff = inst.offices.length;
            if (nOff > 11 || nOff <= 0) {
                return false;
            }
            return (1L << nOff) * (long) inst.sites.length <= 400_000L;
        }

        /** Тяжёлое назначение только на compact: на крупных скрытых это съедало 5/11. */
        private boolean assignmentHeavy() {
            return compact();
        }

        /** Выборка (признаки, thorough) для оффлайн-дообучения MLP. */
        List<RankNet.FitSample> harvestFit(int nSets) {
            List<RankNet.FitSample> out = new ArrayList<>();
            int nOff = inst.offices.length;
            if (nOff == 0 || inst.sites.length == 0) {
                return out;
            }
            Integer[] rank = new Integer[nOff];
            for (int o = 0; o < nOff; o++) {
                rank[o] = o;
                Solution sol = construct(new int[]{o}, false);
                officeSingle[o] = sol.objective;
                rankNet.observe(new int[]{o}, sol.offices.isEmpty() ? 0.0 : sol.objective);
            }
            rankNet.freezeSingles();
            Arrays.sort(rank, (a, b) -> Double.compare(officeSingle[b], officeSingle[a]));
            rankNet.setPredictedK(Math.min(nOff, 3));
            int maxK = Math.min(nOff, Math.min(inst.workers.length, 5));
            Set<String> seen = new HashSet<>();
            for (int k = 1; k <= maxK && out.size() < nSets && timeLeft(); k++) {
                int[] buf = new int[k];
                harvestFitRec(0, 0, k, buf, rank, seen, out, nSets);
            }
            int guard = 0;
            while (out.size() < nSets && guard++ < nSets * 4 && timeLeft()) {
                int k = 1 + randInt(maxK);
                int[] pick = randomSubset(rank, k, Math.min(nOff, Math.max(k + 2, 8)));
                if (pick == null) {
                    continue;
                }
                addFitSample(pick, seen, out);
            }
            return out;
        }

        private void harvestFitRec(int start, int filled, int k, int[] buf, Integer[] rank,
                                   Set<String> seen, List<RankNet.FitSample> out, int nSets) {
            if (out.size() >= nSets || !timeLeft()) {
                return;
            }
            if (filled == k) {
                addFitSample(buf.clone(), seen, out);
                return;
            }
            int nOff = inst.offices.length;
            int pref = Math.min(nOff, Math.max(k + 3, 8));
            for (int i = start; i < pref && filled + (pref - i) >= k; i++) {
                buf[filled] = rank[i];
                harvestFitRec(i + 1, filled + 1, k, buf, rank, seen, out, nSets);
            }
        }

        private void addFitSample(int[] ids, Set<String> seen, List<RankNet.FitSample> out) {
            Arrays.sort(ids);
            if (!seen.add(Arrays.toString(ids))) {
                return;
            }
            Solution cheap = construct(ids, false);
            double cheapObj = cheap.offices.isEmpty() ? 0.0 : cheap.objective;
            rankNet.observe(ids, cheapObj);
            Solution thr = construct(ids, true);
            double obj = thr.offices.isEmpty() ? 0.0 : thr.objective;
            rankNet.observeThorough(ids, obj);
            out.add(new RankNet.FitSample(rankNet.features(ids), rankNet.targetOf(obj - cheapObj)));
        }

        /** Thorough лучшего набора каждого k — дёшево и закрывает «cheap недооценил». */
        private void thoroughBestPerK() {
            for (int k = 1; k < bestIdsByK.length && timeLeft(); k++) {
                if (bestIdsByK[k] != null) {
                    consider(construct(bestIdsByK[k], true));
                }
            }
        }

        /** Все непустые подмножества. */
        private void searchAllMasks(int maxK, boolean thorough) {
            int nOff = inst.offices.length;
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit && timeLeft(); mask++) {
                if (!thorough && !searchTimeLeft()) {
                    break;
                }
                int k = Integer.bitCount(mask);
                if (k > maxK) {
                    continue;
                }
                int[] ids = new int[k];
                int p = 0;
                for (int o = 0; o < nOff; o++) {
                    if ((mask & (1 << o)) != 0) {
                        ids[p++] = o;
                    }
                }
                Solution sol = construct(ids, thorough);
                rankNet.observe(ids, sol.offices.isEmpty() ? 0.0 : sol.objective);
                consider(sol);
            }
        }

        /** Все непустые подмножества, только дешёвый construct. */
        private void searchAllCheap(int maxK) {
            int nOff = inst.offices.length;
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit && searchTimeLeft(); mask++) {
                int k = Integer.bitCount(mask);
                if (k > maxK) {
                    continue;
                }
                int[] ids = idsFromMask(mask, k, nOff);
                considerCheap(ids);
            }
        }

        /**
         * Thorough в порядке RankNet: если дедлайн обрежет, успеем сильные наборы.
         */
        private void searchAllMasksRanked(int maxK, boolean thorough) {
            int nOff = inst.offices.length;
            List<int[]> sets = new ArrayList<>();
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit; mask++) {
                int k = Integer.bitCount(mask);
                if (k > maxK) {
                    continue;
                }
                sets.add(idsFromMask(mask, k, nOff));
            }
            sets.sort((a, b) -> Double.compare(rankNet.predict(b), rankNet.predict(a)));
            for (int[] ids : sets) {
                if (!timeLeft()) {
                    break;
                }
                Solution sol = construct(ids, thorough);
                rankNet.observe(ids, sol.offices.isEmpty() ? 0.0 : sol.objective);
                consider(sol);
            }
        }

        private int[] idsFromMask(int mask, int k, int nOff) {
            int[] ids = new int[k];
            int p = 0;
            for (int o = 0; o < nOff; o++) {
                if ((mask & (1 << o)) != 0) {
                    ids[p++] = o;
                }
            }
            return ids;
        }

        /**
         * Дешёвая сборка занижает leftover/навыки/дорогу. Thorough чинит
         * назначение на наборах, которые RankNet ставит выше после cheap-прогона.
         */
        private void refineThorough(Integer[] rank) {
            List<int[]> queue = new ArrayList<>();
            addUniqueSet(queue, bestIds());
            elites.sort((a, b) -> Double.compare(b.obj, a.obj));
            int byCheap = compact() ? ELITE_CAP / 2 : 3;
            int extra = 0;
            for (Elite e : elites) {
                if (extra >= byCheap) {
                    break;
                }
                addUniqueSet(queue, e.ids);
                extra++;
            }
            List<Elite> byModel = new ArrayList<>(elites);
            byModel.sort((a, b) -> Double.compare(rankNet.predict(b.ids), rankNet.predict(a.ids)));
            extra = 0;
            int byRank = compact() ? ELITE_CAP : 4;
            for (Elite e : byModel) {
                if (extra >= byRank) {
                    break;
                }
                addUniqueSet(queue, e.ids);
                extra++;
            }
            if (compact() && inst.offices.length <= 8) {
                enqueueTopRankedMasks(queue, Math.min(inst.offices.length, inst.workers.length), ELITE_CAP);
            }
            for (int k = 1; k < bestIdsByK.length; k++) {
                addUniqueSet(queue, bestIdsByK[k]);
            }
            Set<String> done = new HashSet<>();
            for (int[] ids : queue) {
                if (!timeLeft() || ids == null || ids.length == 0) {
                    continue;
                }
                String key = Arrays.toString(ids);
                if (!done.add(key)) {
                    continue;
                }
                consider(construct(ids, true));
            }
            if (compact()) {
                thoroughNeighbors(rank, done);
            }
        }

        /** Все маски compact-карты: thorough тем, кого модель ставит выше cheap-obj. */
        private void enqueueTopRankedMasks(List<int[]> queue, int maxK, int cap) {
            int nOff = inst.offices.length;
            List<int[]> sets = new ArrayList<>();
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit; mask++) {
                int k = Integer.bitCount(mask);
                if (k > maxK) {
                    continue;
                }
                sets.add(idsFromMask(mask, k, nOff));
            }
            sets.sort((a, b) -> Double.compare(rankNet.predict(b), rankNet.predict(a)));
            int added = 0;
            for (int[] ids : sets) {
                if (added >= cap) {
                    break;
                }
                addUniqueSet(queue, ids);
                added++;
            }
        }

        private void addUniqueSet(List<int[]> queue, int[] ids) {
            if (ids == null || ids.length == 0) {
                return;
            }
            int[] copy = ids.clone();
            Arrays.sort(copy);
            for (int[] e : queue) {
                if (Arrays.equals(e, copy)) {
                    return;
                }
            }
            queue.add(copy);
        }

        private void thoroughNeighbors(Integer[] rank, Set<String> done) {
            if (best.offices.isEmpty() || !timeLeft()) {
                return;
            }
            int[] cur = bestIds();
            int nOff = inst.offices.length;
            boolean[] in = new boolean[nOff];
            for (int id : cur) {
                in[id] = true;
            }
            int extra = 0;
            int bound = Math.min(nOff, Math.max(8, cur.length * 3));
            for (int r = 0; r < bound && extra < THOROUGH_NEIGHBOR_CAP && timeLeft(); r++) {
                int neu = rank[r];
                if (in[neu] || inst.offices[neu].maxSize < 1) {
                    continue;
                }
                for (int i = 0; i < cur.length && extra < THOROUGH_NEIGHBOR_CAP && timeLeft(); i++) {
                    int[] next = cur.clone();
                    next[i] = neu;
                    Arrays.sort(next);
                    String key = Arrays.toString(next);
                    if (!done.add(key)) {
                        continue;
                    }
                    consider(construct(next, true));
                    extra++;
                }
            }
        }

        /**
         * Оценка выгодного числа офисов: берём k лучших по одиночной оценке
         * и добавляем офис, пока probe-сборка растёт. Это не полный перебор k.
         */
        private int predictK(Integer[] rank) {
            int nOff = inst.offices.length;
            int cap = Math.min(nOff, Math.min(inst.workers.length, 6));
            int predicted = 1;
            double prev = best.objective;
            for (int k = 2; k <= cap && searchTimeLeft(); k++) {
                int[] probe = new int[k];
                for (int i = 0; i < k; i++) {
                    probe[i] = rank[i];
                }
                Solution sol = construct(probe, false);
                rankNet.observe(probe, sol.offices.isEmpty() ? 0.0 : sol.objective);
                consider(sol);
                if (sol.objective > prev + 1e-6) {
                    predicted = k;
                    prev = sol.objective;
                } else {
                    break;
                }
            }
            return predicted;
        }

        private void searchK(int k, Integer[] rank) {
            int nOff = inst.offices.length;
            long combos = binom(nOff, k);
            long enumCap = k <= 2 ? PAIR_ENUM_COMBOS : ENUM_COMBOS;
            if (combos <= enumCap) {
                int[] buf = new int[k];
                enumerate(0, 0, k, buf);
                return;
            }
            int limit = k <= 3 ? 80 : (k == 4 ? WIDE_K4_CAP : WIDE_K5_CAP);
            Set<String> seen = new HashSet<>();
            int pref = Math.min(nOff, Math.max(k + 2, 8));
            int[] buf = new int[k];
            collectFromRank(rank, pref, 0, 0, k, buf, seen, limit);
            while (seen.size() < limit && searchTimeLeft()) {
                int[] pick = new int[k];
                boolean[] used = new boolean[nOff];
                int filled = 0;
                int guard = 0;
                if (randInt(2) == 0) {
                    int[] guided = modelSubset(rank, k);
                    if (guided != null) {
                        Arrays.sort(guided);
                        if (seen.add(Arrays.toString(guided))) {
                            considerCheap(guided);
                        }
                        continue;
                    }
                }
                while (filled < k && guard++ < nOff * 8) {
                    int o = rank[randInt(pref)];
                    if (randInt(4) == 0) {
                        o = randInt(nOff);
                    }
                    if (!used[o] && inst.offices[o].maxSize >= 1) {
                        used[o] = true;
                        pick[filled++] = o;
                    }
                }
                if (filled == k) {
                    Arrays.sort(pick);
                    if (seen.add(Arrays.toString(pick))) {
                        considerCheap(pick);
                    }
                }
            }
        }

        private void enumerate(int start, int filled, int k, int[] buf) {
            // searchTimeLeft, не timeLeft: полный перебор пар не съедает thorough.
            if (!searchTimeLeft()) {
                return;
            }
            if (filled == k) {
                considerCheap(buf);
                return;
            }
            int nOff = inst.offices.length;
            for (int i = start; i <= nOff - (k - filled); i++) {
                buf[filled] = i;
                enumerate(i + 1, filled + 1, k, buf);
                if (!searchTimeLeft()) {
                    return;
                }
            }
        }

        private void collectFromRank(Integer[] rank, int pref, int start, int filled, int k,
                                     int[] buf, Set<String> seen, int limit) {
            if (!searchTimeLeft() || seen.size() >= limit) {
                return;
            }
            if (filled == k) {
                int[] copy = buf.clone();
                Arrays.sort(copy);
                if (seen.add(Arrays.toString(copy))) {
                    considerCheap(copy);
                }
                return;
            }
            for (int i = start; i <= pref - (k - filled) && searchTimeLeft(); i++) {
                buf[filled] = rank[i];
                collectFromRank(rank, pref, i + 1, filled + 1, k, buf, seen, limit);
            }
        }

        /** Пространственно разнесённые офисы — дешёвая сборка, один набор на k. */
        private void searchSpread(Integer[] rank, int maxK) {
            int up = Math.min(maxK, 6);
            for (int k = 2; k <= up && searchTimeLeft(); k++) {
                int[] ids = pickSpread(k, rank);
                if (ids != null) {
                    considerCheap(ids);
                }
            }
        }

        private int[] pickSpread(int k, Integer[] rank) {
            int nOff = inst.offices.length;
            if (k > nOff) {
                return null;
            }
            boolean[] used = new boolean[nOff];
            int[] ids = new int[k];
            ids[0] = rank[0];
            used[rank[0]] = true;
            for (int filled = 1; filled < k; filled++) {
                int bestId = -1;
                double bestMin = -1;
                int bound = Math.min(nOff, Math.max(k * 4, 12));
                for (int r = 0; r < bound; r++) {
                    int o = rank[r];
                    if (used[o] || inst.offices[o].maxSize < 1) {
                        continue;
                    }
                    double minD = Double.POSITIVE_INFINITY;
                    for (int i = 0; i < filled; i++) {
                        minD = Math.min(minD, Geometry.euclid2(inst.offices[o].pos, inst.offices[ids[i]].pos));
                    }
                    if (minD > bestMin) {
                        bestMin = minD;
                        bestId = o;
                    }
                }
                if (bestId < 0) {
                    return null;
                }
                used[bestId] = true;
                ids[filled] = bestId;
            }
            Arrays.sort(ids);
            return ids;
        }

        /** Соседи лучшего набора: замена одного офиса. */
        /**
         * Локальный поиск по офисам: drop / add / swap. Дешёвый construct,
         * закрывает наборы, которые random searchUntilTime не находит.
         */
        private void searchOfficeLocal(Integer[] rank, int maxK) {
            int rounds = 0;
            while (rounds++ < 10 && searchTimeLeft() && !best.offices.isEmpty()) {
                double before = best.objective;
                int[] cur = bestIds();
                if (cur.length >= 2) {
                    for (int i = 0; i < cur.length && searchTimeLeft(); i++) {
                        considerCheap(withoutIndex(cur, i));
                    }
                }
                if (cur.length < maxK) {
                    boolean[] in = new boolean[inst.offices.length];
                    for (int id : cur) {
                        in[id] = true;
                    }
                    int bound = Math.min(inst.offices.length, Math.max(14, cur.length * 5));
                    for (int r = 0; r < bound && searchTimeLeft(); r++) {
                        int neu = rank[r];
                        if (!in[neu] && inst.offices[neu].maxSize >= 1) {
                            considerCheap(withOffice(cur, neu));
                        }
                    }
                }
                searchNeighbors(rank);
                if (best.objective <= before + 1e-9) {
                    break;
                }
            }
            if (searchTimeLeft()) {
                searchMarginalOffices(rank, maxK);
            }
        }

        /**
         * Add/drop офиса по leftover текущего лучшего решения, а не по сумме
         * одиночных cheap-сборок: новый офис растёт только на невзятых объектах.
         */
        private void searchMarginalOffices(Integer[] rank, int maxK) {
            if (best.offices.isEmpty() || !searchTimeLeft()) {
                return;
            }
            int[] cur = bestIds();
            if (cur.length >= 2) {
                for (int id : cur) {
                    if (!searchTimeLeft()) {
                        return;
                    }
                    consider(dropOfficeParkLeftover(best, id));
                }
            }
            if (cur.length >= maxK) {
                return;
            }
            boolean[] in = new boolean[inst.offices.length];
            for (int id : cur) {
                in[id] = true;
            }
            int bound = Math.min(inst.offices.length, Math.max(10, cur.length * 4));
            for (int r = 0; r < bound && searchTimeLeft(); r++) {
                int neu = rank[r];
                if (in[neu] || inst.offices[neu].maxSize < 1) {
                    continue;
                }
                consider(addOfficeOnLeftover(best, neu));
            }
        }

        private int[] withoutIndex(int[] ids, int skip) {
            int[] next = new int[ids.length - 1];
            int p = 0;
            for (int i = 0; i < ids.length; i++) {
                if (i != skip) {
                    next[p++] = ids[i];
                }
            }
            return next;
        }

        private int[] withOffice(int[] ids, int extra) {
            int[] next = Arrays.copyOf(ids, ids.length + 1);
            next[ids.length] = extra;
            return next;
        }

        /**
         * Открыть офис только на leftover текущей упаковки: маргинал, а не
         * «одиночная сборка плюс уже открытые».
         */
        @SuppressWarnings("unchecked")
        private Solution addOfficeOnLeftover(Solution cur, int newOffice) {
            int[] oldIds = idsOf(cur);
            int[] oid = withOffice(oldIds, newOffice);
            List<Cand>[] taken = takenFromSolution(oid, cur);
            int tNew = -1;
            for (int t = 0; t < oid.length; t++) {
                if (oid[t] == newOffice) {
                    tNew = t;
                    break;
                }
            }
            if (tNew < 0) {
                return Solution.empty();
            }
            BitSet[] skills = skillsFromTaken(oid, taken);
            skills[tNew] = fullOfficeSkills(1)[0];
            growOneOffice(oid, taken, skills, tNew);
            int maxOther = 0;
            for (int t = 0; t < oid.length; t++) {
                if (t != tNew) {
                    maxOther = Math.max(maxOther, taken[t].size());
                }
            }
            if (taken[tNew].isEmpty()
                    || taken[tNew].size() < Math.max(0, maxOther - inst.maxDifference)) {
                return Solution.empty();
            }
            recoverLeftovers(oid, taken, skills);
            return finalize(oid, taken);
        }

        /**
         * Закрыть офис и попытаться пристроить его объекты к оставшимся зонам.
         */
        @SuppressWarnings("unchecked")
        private Solution dropOfficeParkLeftover(Solution cur, int dropOffice) {
            int[] oldIds = idsOf(cur);
            if (oldIds.length < 2) {
                return Solution.empty();
            }
            int skip = -1;
            for (int i = 0; i < oldIds.length; i++) {
                if (oldIds[i] == dropOffice) {
                    skip = i;
                    break;
                }
            }
            if (skip < 0) {
                return Solution.empty();
            }
            int[] oid = withoutIndex(oldIds, skip);
            List<Cand>[] taken = takenFromSolution(oid, cur);
            BitSet[] skills = skillsFromTaken(oid, taken);
            for (int t = 0; t < oid.length && timeLeft(); t++) {
                growOneOffice(oid, taken, skills, t);
            }
            recoverLeftovers(oid, taken, skills);
            return finalize(oid, taken);
        }

        private List<Cand>[] takenFromSolution(int[] oid, Solution sol) {
            @SuppressWarnings("unchecked")
            List<Cand>[] taken = new List[oid.length];
            for (int t = 0; t < oid.length; t++) {
                taken[t] = new ArrayList<>();
            }
            for (OpenedOffice o : sol.offices) {
                int t = -1;
                for (int i = 0; i < oid.length; i++) {
                    if (oid[i] == o.officeId) {
                        t = i;
                        break;
                    }
                }
                if (t < 0) {
                    continue;
                }
                for (int s : o.sites) {
                    taken[t].add(candOf(o.officeId, s));
                }
            }
            return taken;
        }

        private BitSet[] skillsFromTaken(int[] oid, List<Cand>[] taken) {
            BitSet[] skills = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                List<Integer> sites = new ArrayList<>();
                for (Cand c : taken[t]) {
                    sites.add(c.site);
                }
                skills[t] = skillsOf(sites);
            }
            return skills;
        }

        private void searchNeighbors(Integer[] rank) {
            if (best.offices.isEmpty() || !searchTimeLeft()) {
                return;
            }
            int[] cur = bestIds();
            int nOff = inst.offices.length;
            boolean[] in = new boolean[nOff];
            for (int id : cur) {
                in[id] = true;
            }
            int bound = Math.min(nOff, Math.max(12, cur.length * 4));
            for (int r = 0; r < bound && searchTimeLeft(); r++) {
                int neu = rank[r];
                if (in[neu] || inst.offices[neu].maxSize < 1) {
                    continue;
                }
                for (int i = 0; i < cur.length && searchTimeLeft(); i++) {
                    int[] next = cur.clone();
                    next[i] = neu;
                    considerCheap(next);
                }
            }
        }

        /** Оставшееся CPU — дешёвые случайные наборы, не thorough. */
        private void searchUntilTime(Integer[] rank, int predicted, int maxK) {
            if (maxK < 2) {
                return;
            }
            int nOff = inst.offices.length;
            int miss = 0;
            String lastBest = Arrays.toString(bestIds());
            searchNeighbors(rank);
            while (searchTimeLeft()) {
                String nowBest = Arrays.toString(bestIds());
                if (!nowBest.equals(lastBest)) {
                    searchNeighbors(rank);
                    lastBest = nowBest;
                }
                int center = randInt(2) == 0 ? Math.max(2, predicted) : Math.max(2, best.offices.size());
                int k = Math.max(2, Math.min(maxK, center + randInt(3) - 1));
                int pref = Math.min(nOff, Math.max(k + 2, 8));
                int[] pick = randInt(3) == 0
                        ? modelSubset(rank, k)
                        : bestOfModelSamples(rank, k, pref, 4);
                if (pick == null) {
                    pick = randomSubset(rank, k, pref);
                }
                if (pick == null) {
                    if (++miss > 64) {
                        return;
                    }
                    continue;
                }
                int before = searchedSets.size();
                considerCheap(pick);
                if (searchedSets.size() == before) {
                    if (++miss > 256) {
                        searchNeighbors(rank);
                        return;
                    }
                } else {
                    miss = 0;
                }
            }
        }

        /**
         * ε-greedy набор: на каждом шаге офис с лучшим предсказанием RankNet.
         */
        private int[] modelSubset(Integer[] rank, int k) {
            int nOff = inst.offices.length;
            if (k <= 0 || k > nOff) {
                return null;
            }
            boolean[] used = new boolean[nOff];
            int[] ids = new int[k];
            int filled = 0;
            int[] probe = new int[k];
            while (filled < k) {
                int bestO = -1;
                double bestSc = -1e300;
                int bound = Math.min(nOff, Math.max(k * 5, 12));
                if (randInt(5) == 0) {
                    int o = rank[randInt(Math.min(bound, nOff))];
                    if (!used[o] && inst.offices[o].maxSize >= 1) {
                        bestO = o;
                    }
                }
                if (bestO < 0) {
                    for (int r = 0; r < bound; r++) {
                        int o = rank[r];
                        if (used[o] || inst.offices[o].maxSize < 1) {
                            continue;
                        }
                        for (int i = 0; i < filled; i++) {
                            probe[i] = ids[i];
                        }
                        probe[filled] = o;
                        double sc = rankNet.predict(Arrays.copyOf(probe, filled + 1));
                        if (sc > bestSc) {
                            bestSc = sc;
                            bestO = o;
                        }
                    }
                }
                if (bestO < 0) {
                    return filled == 0 ? null : Arrays.copyOf(ids, filled);
                }
                used[bestO] = true;
                ids[filled++] = bestO;
            }
            Arrays.sort(ids);
            return ids;
        }

        /** Несколько случайных кандидатов, берём с максимальным predict. */
        private int[] bestOfModelSamples(Integer[] rank, int k, int pref, int tries) {
            int[] bestPick = null;
            double bestSc = -1e300;
            for (int t = 0; t < tries; t++) {
                int[] pick = randomSubset(rank, k, pref);
                if (pick == null) {
                    continue;
                }
                if (searchedSets.contains(Arrays.toString(pick))) {
                    continue;
                }
                double sc = rankNet.predict(pick);
                if (sc > bestSc) {
                    bestSc = sc;
                    bestPick = pick;
                }
            }
            return bestPick;
        }

        private int[] randomSubset(Integer[] rank, int k, int pref) {
            int nOff = inst.offices.length;
            if (k <= 0 || k > nOff) {
                return null;
            }
            boolean[] used = new boolean[nOff];
            int[] pick = new int[k];
            int filled = 0;
            int guard = 0;
            while (filled < k && guard++ < nOff * 8) {
                int o = rank[randInt(Math.max(1, Math.min(pref, nOff)))];
                if (randInt(4) == 0) {
                    o = randInt(nOff);
                }
                if (!used[o] && inst.offices[o].maxSize >= 1) {
                    used[o] = true;
                    pick[filled++] = o;
                }
            }
            if (filled != k) {
                return null;
            }
            Arrays.sort(pick);
            return pick;
        }

        private int[] bestIds() {
            int[] ids = new int[best.offices.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = best.offices.get(i).officeId;
            }
            Arrays.sort(ids);
            return ids;
        }

        private void considerCheap(int[] ids) {
            if (!timeLeft()) {
                return;
            }
            int[] copy = ids.clone();
            Arrays.sort(copy);
            if (!searchedSets.add(Arrays.toString(copy))) {
                return;
            }
            Solution sol = construct(copy, false);
            rankNet.observe(copy, sol.offices.isEmpty() ? 0.0 : sol.objective);
            consider(sol);
        }

        private void consider(Solution sol) {
            if (sol == null || sol.offices.isEmpty()) {
                return;
            }
            int[] ids = idsOf(sol);
            rememberElite(ids, sol.objective);
            if (sol.objective <= best.objective + 1e-9) {
                return;
            }
            if (!Constraints.valid(inst, sol.offices)) {
                return;
            }
            best = sol;
        }

        private int[] idsOf(Solution sol) {
            int[] ids = new int[sol.offices.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = sol.offices.get(i).officeId;
            }
            Arrays.sort(ids);
            return ids;
        }

        private void rememberElite(int[] ids, double obj) {
            if (ids == null || ids.length == 0 || obj < -1e90) {
                return;
            }
            int[] copy = ids.clone();
            Arrays.sort(copy);
            int k = copy.length;
            if (k < bestObjByK.length && obj > bestObjByK[k] + 1e-9) {
                bestObjByK[k] = obj;
                bestIdsByK[k] = copy;
            }
            for (Elite e : elites) {
                if (Arrays.equals(e.ids, copy)) {
                    if (obj > e.obj) {
                        e.obj = obj;
                    }
                    return;
                }
            }
            elites.add(new Elite(copy, obj));
            elites.sort((a, b) -> Double.compare(b.obj, a.obj));
            while (elites.size() > ELITE_STORE) {
                elites.remove(elites.size() - 1);
            }
        }

        /**
         * Собирает решение для фиксированного набора офисов:
         * Вороной по евклидову расстоянию, несколько политик навыков,
         * добор чужих объектов с проверкой оболочек.
         */
        @SuppressWarnings("unchecked")
        Solution construct(int[] officeIds, boolean thorough) {
            if (!timeLeft()) {
                return Solution.empty();
            }
            List<Integer> unique = new ArrayList<>();
            boolean[] seen = new boolean[inst.offices.length];
            for (int o : officeIds) {
                if (o < 0 || o >= inst.offices.length || seen[o]) {
                    continue;
                }
                if (inst.offices[o].maxSize < 1) {
                    continue;
                }
                seen[o] = true;
                unique.add(o);
            }
            if (unique.isEmpty()) {
                return Solution.empty();
            }
            int k = unique.size();
            int[] oid = new int[k];
            for (int i = 0; i < k; i++) {
                oid[i] = unique.get(i);
            }

            Solution bestLocal = Solution.empty();
            if (thorough) {
                List<BitSet[]> growSkills = new ArrayList<>();
                List<Cand>[] euclid = clusterSites(oid, false);
                growSkills.add(assignSkillsConcentrate(oid, euclid));
                growSkills.add(fullOfficeSkills(k));
                if (compact()) {
                    growSkills.add(assignSkillsByDemand(oid, euclid));
                    growSkills.add(assignSkillsGreedy(oid, euclid));
                }
                for (BitSet[] officeSkills : growSkills) {
                    if (!timeLeft()) {
                        break;
                    }
                    Solution grow = buildHullGrow(oid, officeSkills);
                    if (grow.objective > bestLocal.objective) {
                        bestLocal = grow;
                    }
                }
            }
            boolean heavy = thorough && assignmentHeavy();
            List<List<Cand>[]> pools = new ArrayList<>();
            pools.add(clusterSites(oid, false));
            if (thorough) {
                pools.add(clusterSites(oid, true));
                if (heavy) {
                    pools.add(clusterCapped(oid, false));
                    pools.add(clusterCapped(oid, true));
                }
            }
            for (List<Cand>[] pool : pools) {
                if (!timeLeft()) {
                    break;
                }
                List<BitSet[]> policies = new ArrayList<>();
                policies.add(assignSkillsConcentrate(oid, pool));
                if (thorough) {
                    policies.add(assignSkillsByDemand(oid, pool));
                    policies.add(assignSkillsGreedy(oid, pool));
                    if (tiny()) {
                        for (int start = 0; start < k; start++) {
                            policies.add(assignSkillsRoundRobinFrom(oid, start));
                        }
                    } else {
                        policies.add(assignSkillsRoundRobinFrom(oid, 0));
                    }
                }
                for (BitSet[] officeSkills : policies) {
                    Solution built = considerConstruct(oid, pool, officeSkills, thorough);
                    if (built.objective > bestLocal.objective) {
                        bestLocal = built;
                    }
                }
            }
            if (thorough && (tiny() || (compact() && inst.sites.length <= 120))) {
                for (BitSet[] officeSkills : extraSkillPolicies(oid)) {
                    if (!timeLeft()) {
                        break;
                    }
                    Solution alt = buildByValue(oid, officeSkills);
                    if (alt.objective > bestLocal.objective) {
                        bestLocal = alt;
                    }
                    Solution bal = buildBalanced(oid, officeSkills);
                    if (bal.objective > bestLocal.objective) {
                        bestLocal = bal;
                    }
                }
                if (k <= 4 && inst.sites.length <= 40
                        && k >= Math.max(2, inst.offices.length - 1)) {
                    BitSet[] allSkills = fullOfficeSkills(k);
                    int restarts = inst.maxDifference <= 2 ? 20 : 8;
                    for (int seed = 0; seed < restarts && timeLeft(); seed++) {
                        Solution bal = buildBalancedSeeded(oid, allSkills, seed);
                        if (bal.objective > bestLocal.objective) {
                            bestLocal = bal;
                        }
                    }
                }
            }
            if (thorough && !bestLocal.offices.isEmpty()) {
                rankNet.observeThorough(oid, bestLocal.objective);
            }
            return bestLocal;
        }

        /**
         * Сравнивает сборки одной политики навыков и возвращает лучшую.
         */
        private Solution considerConstruct(int[] oid, List<Cand>[] pool, BitSet[] officeSkills,
                                           boolean thorough) {
            Solution best = buildFromSkills(oid, pool, officeSkills, thorough);
            if (thorough) {
                Solution alt = buildByValue(oid, officeSkills);
                if (alt.objective > best.objective) {
                    best = alt;
                }
                if (tiny() || (inst.sites.length <= 250 && oid.length <= 6)) {
                    Solution bal = buildBalanced(oid, officeSkills);
                    if (bal.objective > best.objective) {
                        best = bal;
                    }
                }
            }
            return best;
        }

        /**
         * Назначение растёт по оболочкам, а не чинит Вороной. Сайт берём только
         * если новая оболочка не пересекается с уже открытыми.
         */
        @SuppressWarnings("unchecked")
        private Solution hullGrowSolve(int[] officeIds) {
            if (officeIds == null || officeIds.length == 0 || !timeLeft()) {
                return Solution.empty();
            }
            List<Cand>[] euclid = clusterSites(officeIds, false);
            BitSet[] skills = assignSkillsConcentrate(officeIds, euclid);
            return buildHullGrow(officeIds, skills);
        }

        @SuppressWarnings("unchecked")
        private Solution buildHullGrow(int[] oid, BitSet[] skills) {
            int k = oid.length;
            List<Cand>[] taken = new List[k];
            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
            }
            boolean[] used = new boolean[inst.sites.length];
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[k];
            int capSum = 0;
            for (int t = 0; t < k; t++) {
                capSum += inst.offices[oid[t]].maxSize;
            }
            int maxAdd = Math.min(inst.sites.length, capSum);
            int[][] ranked = rankSitesByRoad(oid);
            // Пустой офис, который так и не смог взять объект, больше не держит
            // остальные на расстоянии maxDifference от нуля.
            boolean[] stalled = new boolean[k];
            for (int round = 0; round < maxAdd && timeLeft(); round++) {
                int bestS = -1;
                int bestT = -1;
                double bestV = 1e-12;
                int[] sz = new int[k];
                boolean[] canAccept = new boolean[k];
                boolean[] balanceHit = new boolean[k];
                for (int t = 0; t < k; t++) {
                    sz[t] = taken[t].size();
                }
                for (int t = 0; t < k; t++) {
                    if (sz[t] >= inst.offices[oid[t]].maxSize) {
                        continue;
                    }
                    boolean emptyOffice = sz[t] == 0;
                    int tried = 0;
                    int limit = emptyOffice ? emptyGrowLimit() : growScanLimit(false);
                    boolean hit = false;
                    for (int pos = 0; pos < ranked[t].length && tried < limit; pos++) {
                        int s = ranked[t][pos];
                        if (used[s] || !inst.siteFeasible(s) || !coveredBy(s, skills[t])) {
                            continue;
                        }
                        double v = inst.sites[s].reward - inst.distances[oid[t]][s];
                        // Список убывает по ценности: дальше этого офиса лучше не будет.
                        if (v <= bestV) {
                            break;
                        }
                        tried++;
                        sz[t]++;
                        boolean balanceOk = growBalanceOk(sz, stalled);
                        sz[t]--;
                        if (balanceOk) {
                            balanceHit[t] = true;
                        }
                        if (!balanceOk) {
                            if (!emptyOffice && tried == GROW_WINDOW && !hit) {
                                limit = growScanLimit(true);
                            }
                            continue;
                        }
                        taken[t].add(candOf(oid[t], s));
                        boolean geo = geometryAllows(oid, taken, t, hulls, covered);
                        taken[t].remove(taken[t].size() - 1);
                        if (geo) {
                            canAccept[t] = true;
                        }
                        if (geo && v > bestV) {
                            bestV = v;
                            bestS = s;
                            bestT = t;
                            hit = true;
                        } else if (!emptyOffice && tried == GROW_WINDOW && !hit) {
                            limit = growScanLimit(true);
                        }
                    }
                }
                if (bestS < 0) {
                    boolean stalledNew = false;
                    boolean waitingOnEmpty = false;
                    for (int t = 0; t < k; t++) {
                        if (sz[t] > 0 || stalled[t]) {
                            continue;
                        }
                        if (balanceHit[t] && !canAccept[t]) {
                            // Баланс пускает, оболочка — нет. Ноль больше не держит соседей.
                            stalled[t] = true;
                            stalledNew = true;
                        } else if (!balanceHit[t]) {
                            waitingOnEmpty = true;
                        }
                    }
                    if (!stalledNew && waitingOnEmpty) {
                        // Ни один объект не встаёт, пока пустые офисы сидят в балансе.
                        for (int t = 0; t < k; t++) {
                            if (sz[t] == 0 && !stalled[t]) {
                                stalled[t] = true;
                                stalledNew = true;
                            }
                        }
                    }
                    if (!stalledNew) {
                        break;
                    }
                    continue;
                }
                taken[bestT].add(candOf(oid[bestT], bestS));
                used[bestS] = true;
                hulls[bestT] = hullOf(oid[bestT], taken[bestT]);
                covered[bestT] = coverMask(hulls[bestT]);
            }
            List<Cand>[] left = new List[k];
            for (int t = 0; t < k; t++) {
                left[t] = new ArrayList<>();
            }
            improve(oid, taken, left, skills, true);
            stealSites(oid, taken, skills);
            recoverLeftovers(oid, taken, skills);
            if (assignmentHeavy()) {
                return polishSafe(oid, taken, skills);
            }
            return finalize(oid, taken);
        }

        /**
         * Доращивает одну зону leftover-объектами, не трогая уже назначенные.
         */
        private void growOneOffice(int[] oid, List<Cand>[] taken, BitSet[] skills, int tFocus) {
            growOneOffice(oid, taken, skills, tFocus, -1);
        }

        /**
         * banSite — объект, который в этом проходе возвращать в зону нельзя
         * (только что снятая вершина оболочки).
         */
        private void growOneOffice(int[] oid, List<Cand>[] taken, BitSet[] skills, int tFocus, int banSite) {
            boolean[] used = new boolean[inst.sites.length];
            for (List<Cand> group : taken) {
                for (Cand c : group) {
                    used[c.site] = true;
                }
            }
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            int cap = inst.offices[oid[tFocus]].maxSize;
            int[] ranked = rankSitesByRoad(oid[tFocus]);
            int maxAdd = cap - taken[tFocus].size();
            for (int round = 0; round < maxAdd && timeLeft(); round++) {
                int[] sz = new int[oid.length];
                for (int t = 0; t < oid.length; t++) {
                    sz[t] = taken[t].size();
                }
                int bestS = -1;
                double bestV = 1e-12;
                int tried = 0;
                int limit = growScanLimit(false);
                boolean hit = false;
                for (int pos = 0; pos < ranked.length && tried < limit; pos++) {
                    int s = ranked[pos];
                    if (s == banSite || used[s] || !inst.siteFeasible(s) || !coveredBy(s, skills[tFocus])) {
                        continue;
                    }
                    double v = inst.sites[s].reward - inst.distances[oid[tFocus]][s];
                    if (v <= bestV) {
                        break;
                    }
                    tried++;
                    sz[tFocus]++;
                    int lo = Integer.MAX_VALUE;
                    int hi = 0;
                    for (int u = 0; u < oid.length; u++) {
                        if (sz[u] == 0) {
                            continue;
                        }
                        lo = Math.min(lo, sz[u]);
                        hi = Math.max(hi, sz[u]);
                    }
                    sz[tFocus]--;
                    if (lo != Integer.MAX_VALUE && hi - lo > inst.maxDifference) {
                        if (tried == GROW_WINDOW && !hit) {
                            limit = growScanLimit(true);
                        }
                        continue;
                    }
                    taken[tFocus].add(candOf(oid[tFocus], s));
                    boolean geo = geometryAllows(oid, taken, tFocus, hulls, covered);
                    taken[tFocus].remove(taken[tFocus].size() - 1);
                    if (geo && v > bestV) {
                        bestV = v;
                        bestS = s;
                        hit = true;
                    } else if (tried == GROW_WINDOW && !hit) {
                        limit = growScanLimit(true);
                    }
                }
                if (bestS < 0) {
                    break;
                }
                taken[tFocus].add(candOf(oid[tFocus], bestS));
                used[bestS] = true;
                hulls[tFocus] = hullOf(oid[tFocus], taken[tFocus]);
                covered[tFocus] = coverMask(hulls[tFocus]);
            }
        }

        /**
         * Объекты по убыванию reward−dist к одному офису.
         * Рост оболочки идёт по этому порядку, а не по индексу в инстансе.
         */
        private int[] rankSitesByRoad(int officeId) {
            int n = inst.sites.length;
            Integer[] order = new Integer[n];
            for (int s = 0; s < n; s++) {
                order[s] = s;
            }
            Arrays.sort(order, (a, b) -> Double.compare(
                    inst.sites[b].reward - inst.distances[officeId][b],
                    inst.sites[a].reward - inst.distances[officeId][a]));
            int[] ranked = new int[n];
            for (int i = 0; i < n; i++) {
                ranked[i] = order[i];
            }
            return ranked;
        }

        private int[][] rankSitesByRoad(int[] oid) {
            int[][] ranked = new int[oid.length][];
            for (int t = 0; t < oid.length; t++) {
                ranked[t] = rankSitesByRoad(oid[t]);
            }
            return ranked;
        }

        /** Полный список на мелкой карте, иначе одно или два окна. */
        private int growScanLimit(boolean extraWindow) {
            if (inst.sites.length <= GROW_FULL_SCAN_SITES) {
                return inst.sites.length;
            }
            return extraWindow ? GROW_WINDOW + GROW_EXTRA_WINDOW : GROW_WINDOW;
        }

        /** Пустой офис ищет первый объект шире: иначе его легко признать мёртвым. */
        private int emptyGrowLimit() {
            if (inst.sites.length <= GROW_FULL_SCAN_SITES) {
                return inst.sites.length;
            }
            return Math.min(inst.sites.length, GROW_WINDOW * 4);
        }

        /**
         * Баланс числа объектов как в валидаторе: пустой и уже отбракованный офис
         * в решение не входит и разницу не держит.
         */
        private boolean growBalanceOk(int[] sz, boolean[] stalled) {
            int minV = Integer.MAX_VALUE;
            int maxV = 0;
            int opened = 0;
            for (int u = 0; u < sz.length; u++) {
                if (sz[u] == 0 && stalled[u]) {
                    continue;
                }
                opened++;
                minV = Math.min(minV, sz[u]);
                maxV = Math.max(maxV, sz[u]);
            }
            return opened <= 1 || maxV - minV <= inst.maxDifference;
        }

        @SuppressWarnings("unchecked")
        private List<Cand>[] clusterSites(int[] oid, boolean road) {
            int k = oid.length;
            List<Cand>[] pool = new List[k];
            for (int t = 0; t < k; t++) {
                pool[t] = new ArrayList<>();
            }
            for (int s = 0; s < inst.sites.length; s++) {
                if (!inst.siteFeasible(s)) {
                    continue;
                }
                int bestT = 0;
                double bestKey = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    double key = road
                            ? inst.distances[oid[t]][s]
                            : Geometry.euclid2(inst.sites[s].pos, inst.offices[oid[t]].pos);
                    if (key < bestKey - 1e-15
                            || (Math.abs(key - bestKey) <= 1e-15 && oid[t] < oid[bestT])) {
                        bestKey = key;
                        bestT = t;
                    }
                }
                double value = inst.sites[s].reward - inst.distances[oid[bestT]][s];
                pool[bestT].add(new Cand(s, value, inst.sites[s].tasks.length));
            }
            for (int t = 0; t < k; t++) {
                pool[t].sort((a, b) -> Double.compare(b.value, a.value));
            }
            return pool;
        }

        /**
         * Жадно кладём объект в офис с лучшим (reward−dist) среди тех,
         * у кого ещё есть ёмкость. Вороной без капа сваливает всё на один офис.
         */
        @SuppressWarnings("unchecked")
        private List<Cand>[] clusterCapped(int[] oid, boolean road) {
            int k = oid.length;
            List<Cand>[] pool = new List[k];
            int[] load = new int[k];
            for (int t = 0; t < k; t++) {
                pool[t] = new ArrayList<>();
            }
            Integer[] order = new Integer[inst.sites.length];
            int nFeas = 0;
            for (int s = 0; s < inst.sites.length; s++) {
                if (inst.siteFeasible(s)) {
                    order[nFeas++] = s;
                }
            }
            Arrays.sort(order, 0, nFeas, (a, b) -> {
                double va = bestRoadValue(oid, a);
                double vb = bestRoadValue(oid, b);
                return Double.compare(vb, va);
            });
            for (int i = 0; i < nFeas; i++) {
                int s = order[i];
                int bestT = -1;
                double bestV = -1e100;
                double bestKey = Double.POSITIVE_INFINITY;
                for (int t = 0; t < k; t++) {
                    if (load[t] >= inst.offices[oid[t]].maxSize) {
                        continue;
                    }
                    double v = inst.sites[s].reward - inst.distances[oid[t]][s];
                    double key = road
                            ? inst.distances[oid[t]][s]
                            : Geometry.euclid2(inst.sites[s].pos, inst.offices[oid[t]].pos);
                    if (v > bestV + 1e-15 || (Math.abs(v - bestV) <= 1e-15 && key < bestKey)) {
                        bestV = v;
                        bestKey = key;
                        bestT = t;
                    }
                }
                if (bestT >= 0) {
                    pool[bestT].add(new Cand(s, bestV, inst.sites[s].tasks.length));
                    load[bestT]++;
                }
            }
            for (int t = 0; t < k; t++) {
                pool[t].sort((a, b) -> Double.compare(b.value, a.value));
            }
            return pool;
        }

        private double bestRoadValue(int[] oid, int site) {
            double best = -1e100;
            for (int id : oid) {
                best = Math.max(best, inst.sites[site].reward - inst.distances[id][site]);
            }
            return best;
        }

        @SuppressWarnings("unchecked")
        private Solution buildFromSkills(int[] oid, List<Cand>[] pool, BitSet[] officeSkills,
                                         boolean thorough) {
            int k = oid.length;
            List<Cand>[] taken = new List[k];
            List<Cand>[] left = new List[k];
            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
                left[t] = new ArrayList<>();
                int cap = inst.offices[oid[t]].maxSize;
                for (Cand c : pool[t]) {
                    if (c.value > 0 && taken[t].size() < cap && coveredBy(c.site, officeSkills[t])) {
                        taken[t].add(c);
                    } else {
                        left[t].add(c);
                    }
                }
            }
            improve(oid, taken, left, officeSkills, thorough);
            if (thorough) {
                stealSites(oid, taken, officeSkills);
                recoverLeftovers(oid, taken, officeSkills);
                if (assignmentHeavy()) {
                    return polishSafe(oid, taken, officeSkills);
                }
            }
            return finalize(oid, taken);
        }

        /**
         * Назначаем объект офису с максимальным (reward−dist) среди тех,
         * кто умеет его навыки. Геометрию чиним в finalize.
         */
        @SuppressWarnings("unchecked")
        private Solution buildByValue(int[] oid, BitSet[] skills) {
            int k = oid.length;
            List<Cand>[] taken = new List[k];
            List<Cand>[] left = new List[k];
            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
                left[t] = new ArrayList<>();
            }
            List<int[]> order = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!inst.siteFeasible(s)) {
                    continue;
                }
                for (int t = 0; t < k; t++) {
                    if (!coveredBy(s, skills[t])) {
                        continue;
                    }
                    double v = inst.sites[s].reward - inst.distances[oid[t]][s];
                    if (v > 0) {
                        order.add(new int[]{s, t});
                    }
                }
            }
            order.sort((a, b) -> {
                double va = inst.sites[a[0]].reward - inst.distances[oid[a[1]]][a[0]];
                double vb = inst.sites[b[0]].reward - inst.distances[oid[b[1]]][b[0]];
                return Double.compare(vb, va);
            });
            boolean[] used = new boolean[inst.sites.length];
            for (int[] rec : order) {
                int s = rec[0];
                int t = rec[1];
                if (used[s] || taken[t].size() >= inst.offices[oid[t]].maxSize) {
                    continue;
                }
                taken[t].add(new Cand(s, inst.sites[s].reward - inst.distances[oid[t]][s],
                        inst.sites[s].tasks.length));
                used[s] = true;
            }
            improve(oid, taken, left, skills, true);
            stealSites(oid, taken, skills);
            recoverLeftovers(oid, taken, skills);
            if (assignmentHeavy()) {
                return polishSafe(oid, taken, skills);
            }
            return finalize(oid, taken);
        }

        /** Каждый офис может брать любой покрываемый глобально навык. */
        private BitSet[] fullOfficeSkills(int k) {
            BitSet all = new BitSet();
            for (int s = 0; s < inst.skillBound; s++) {
                if (inst.skillSupply[s] > 0) {
                    all.set(s);
                }
            }
            BitSet[] skills = new BitSet[k];
            for (int t = 0; t < k; t++) {
                skills[t] = (BitSet) all.clone();
            }
            return skills;
        }

        private Solution buildBalanced(int[] oid, BitSet[] skills) {
            return buildBalancedSeeded(oid, skills, 0);
        }

        /**
         * Жадное назначение с проверкой |n_i−n_j|≤maxDifference и оболочек на каждом шаге.
         * seed≠0 слегка перемешивает порядок, чтобы не застревать на одном конфликте оболочек.
         */
        @SuppressWarnings("unchecked")
        private Solution buildBalancedSeeded(int[] oid, BitSet[] skills, int seed) {
            int k = oid.length;
            List<Cand>[] taken = new List[k];
            List<Cand>[] left = new List[k];
            for (int t = 0; t < k; t++) {
                taken[t] = new ArrayList<>();
                left[t] = new ArrayList<>();
            }
            boolean[] used = new boolean[inst.sites.length];
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[k];
            int guard = 0;
            int limit = inst.sites.length + 2;
            while (guard++ < limit) {
                int[] sz = new int[k];
                for (int t = 0; t < k; t++) {
                    sz[t] = taken[t].size();
                }
                int bestS = -1;
                int bestT = -1;
                double bestV = -1e100;
                for (int s = 0; s < inst.sites.length; s++) {
                    if (used[s] || !inst.siteFeasible(s)) {
                        continue;
                    }
                    for (int t = 0; t < k; t++) {
                        if (sz[t] >= inst.offices[oid[t]].maxSize || !coveredBy(s, skills[t])) {
                            continue;
                        }
                        sz[t]++;
                        int minV = sz[0];
                        int maxV = sz[0];
                        for (int u = 1; u < k; u++) {
                            minV = Math.min(minV, sz[u]);
                            maxV = Math.max(maxV, sz[u]);
                        }
                        sz[t]--;
                        if (maxV - minV > inst.maxDifference) {
                            continue;
                        }
                        Cand c = candOf(oid[t], s);
                        taken[t].add(c);
                        boolean geo = geometryAllows(oid, taken, t, hulls, covered);
                        taken[t].remove(taken[t].size() - 1);
                        if (!geo) {
                            continue;
                        }
                        double keyed = c.value + jitter(s, t, seed);
                        if (keyed > bestV) {
                            bestV = keyed;
                            bestS = s;
                            bestT = t;
                        }
                    }
                }
                if (bestS < 0 || (seed == 0 && bestV < 1e-12)) {
                    break;
                }
                if (seed != 0 && candOf(oid[bestT], bestS).value < -1.0) {
                    break;
                }
                taken[bestT].add(candOf(oid[bestT], bestS));
                used[bestS] = true;
                hulls[bestT] = hullOf(oid[bestT], taken[bestT]);
                covered[bestT] = coverMask(hulls[bestT]);
            }
            Solution greedy = finalize(oid, taken);
            improve(oid, taken, left, skills, true);
            stealSites(oid, taken, skills);
            recoverLeftovers(oid, taken, skills);
            if (assignmentHeavy()) {
                insertLeftoversByKick(oid, taken, skills);
            }
            Solution refined = assignmentHeavy() ? polishSafe(oid, taken, skills) : finalize(oid, taken);
            return refined.objective > greedy.objective ? refined : greedy;
        }

        private double jitter(int site, int office, int seed) {
            if (seed == 0) {
                return 0.0;
            }
            long x = (seed + 1L) * 0x9E3779B97F4A7C15L;
            x ^= (long) site * 0xD1B54A32D192ED03L;
            x ^= (long) office * 0xA24BAED4963EE407L;
            x ^= x >>> 33;
            x *= 0xff51afd7ed558ccdL;
            x ^= x >>> 33;
            return ((x & 2047L) / 2048.0 - 0.5) * 12.0;
        }

        /**
         * Polish может улучшить score(), но finalize потом чинит геометрию/баланс
         * и иногда теряет цель. Берём максимум до и после полировки.
         */
        @SuppressWarnings("unchecked")
        private Solution polishSafe(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            List<Cand>[] before = new List[taken.length];
            for (int t = 0; t < taken.length; t++) {
                before[t] = new ArrayList<>(taken[t]);
            }
            polish(oid, taken, skills);
            recoverLeftovers(oid, taken, skills);
            Solution polished = finalize(oid, taken);
            Solution plain = finalize(oid, before);
            return polished.objective > plain.objective ? polished : plain;
        }

        /**
         * Навык отдаём офису, у которого больше непокрытых объектов пула, которым он нужен.
         */
        private BitSet[] assignSkillsByDemand(int[] oid, List<Cand>[] pool) {
            int k = oid.length;
            BitSet[] skills = new BitSet[k];
            for (int t = 0; t < k; t++) {
                skills[t] = new BitSet();
            }
            int[] used = new int[inst.skillBound];
            Integer[] skillsOrder = new Integer[inst.skillBound];
            int nSk = 0;
            for (int s = 0; s < inst.skillBound; s++) {
                if (inst.skillSupply[s] > 0) {
                    skillsOrder[nSk++] = s;
                }
            }
            Arrays.sort(skillsOrder, 0, nSk, Comparator.comparingInt(s -> inst.skillSupply[s]));
            for (int i = 0; i < nSk; i++) {
                int skill = skillsOrder[i];
                while (used[skill] < inst.skillSupply[skill]) {
                    int bestT = -1;
                    int bestNeed = 0;
                    for (int t = 0; t < k; t++) {
                        if (skills[t].get(skill)) {
                            continue;
                        }
                        int need = 0;
                        for (Cand c : pool[t]) {
                            if (c.value > 0 && inst.sites[c.site].skillSet.get(skill)
                                    && !coveredBy(c.site, skills[t])) {
                                need++;
                            }
                        }
                        if (need > bestNeed) {
                            bestNeed = need;
                            bestT = t;
                        }
                    }
                    if (bestT < 0) {
                        break;
                    }
                    skills[bestT].set(skill);
                    used[skill]++;
                }
            }
            return skills;
        }

        /**
         * Жадно раздаём навыки офисам: каждый следующий навык туда,
         * где он сильнее увеличивает сумму (reward−dist) покрываемых объектов ячейки.
         * Число офисов с навыком не превышает числа работников с этим навыком.
         */
        private BitSet[] assignSkillsGreedy(int[] oid, List<Cand>[] pool) {
            int k = oid.length;
            BitSet[] skills = new BitSet[k];
            for (int t = 0; t < k; t++) {
                skills[t] = new BitSet();
            }
            int[] used = new int[inst.skillBound];
            while (true) {
                double bestGain = 1e-9;
                int bestT = -1;
                int bestS = -1;
                for (int t = 0; t < k; t++) {
                    double cur = coverableValue(oid[t], pool[t], skills[t]);
                    for (int s = 0; s < inst.skillBound; s++) {
                        if (skills[t].get(s) || used[s] >= inst.skillSupply[s]) {
                            continue;
                        }
                        skills[t].set(s);
                        double gain = coverableValue(oid[t], pool[t], skills[t]) - cur;
                        skills[t].clear(s);
                        if (gain > bestGain) {
                            bestGain = gain;
                            bestT = t;
                            bestS = s;
                        }
                    }
                }
                if (bestT < 0) {
                    break;
                }
                skills[bestT].set(bestS);
                used[bestS]++;
            }
            return skills;
        }

        /**
         * Сначала лучшие офисы забирают по одному экземпляру каждого навыка,
         * остаток supply идёт следующим. Редкий навык остаётся у сильнейшего кластера.
         */
        private BitSet[] assignSkillsConcentrate(int[] oid, List<Cand>[] pool) {
            int k = oid.length;
            BitSet all = new BitSet();
            for (int s = 0; s < inst.skillBound; s++) {
                if (inst.skillSupply[s] > 0) {
                    all.set(s);
                }
            }
            Integer[] order = new Integer[k];
            double[] pot = new double[k];
            for (int t = 0; t < k; t++) {
                order[t] = t;
                pot[t] = coverableValue(oid[t], pool[t], all);
            }
            Arrays.sort(order, (a, b) -> Double.compare(pot[b], pot[a]));
            BitSet[] skills = new BitSet[k];
            for (int t = 0; t < k; t++) {
                skills[t] = new BitSet();
            }
            int[] used = new int[inst.skillBound];
            for (int t : order) {
                for (int s = 0; s < inst.skillBound; s++) {
                    if (used[s] < inst.skillSupply[s]) {
                        skills[t].set(s);
                        used[s]++;
                    }
                }
            }
            return skills;
        }

        /** Редкие навыки раздаём по кругу, чтобы ни один офис не остался пустым. */
        private BitSet[] assignSkillsRoundRobinFrom(int[] oid, int start) {
            int k = oid.length;
            BitSet[] skills = new BitSet[k];
            for (int t = 0; t < k; t++) {
                skills[t] = new BitSet();
            }
            if (k == 0) {
                return skills;
            }
            Integer[] rare = new Integer[inst.skillBound];
            int nSk = 0;
            for (int s = 0; s < inst.skillBound; s++) {
                if (inst.skillSupply[s] > 0) {
                    rare[nSk++] = s;
                }
            }
            Arrays.sort(rare, 0, nSk, Comparator.comparingInt(s -> inst.skillSupply[s]));
            int cursor = ((start % k) + k) % k;
            for (int i = 0; i < nSk; i++) {
                int skill = rare[i];
                int copies = Math.min(inst.skillSupply[skill], k);
                for (int copy = 0; copy < copies; copy++) {
                    skills[cursor % k].set(skill);
                    cursor++;
                }
            }
            return skills;
        }

        /**
         * Полный перебор раздач навыков и перестановок работников.
         * На TIGHT_CAP от этого зависит, откроются ли все офисы.
         */
        private List<BitSet[]> extraSkillPolicies(int[] oid) {
            List<BitSet[]> out = new ArrayList<>();
            out.addAll(enumerateSkillAssignments(oid.length));
            out.addAll(workerSkillPermutations(oid.length));
            return out;
        }

        private List<BitSet[]> enumerateSkillAssignments(int k) {
            List<BitSet[]> out = new ArrayList<>();
            if (k <= 0) {
                return out;
            }
            List<int[]> spec = new ArrayList<>();
            long ways = 1;
            long cap = 48L;
            for (int s = 0; s < inst.skillBound; s++) {
                int supply = Math.min(inst.skillSupply[s], k);
                if (supply <= 0) {
                    continue;
                }
                ways *= binom(k, supply);
                if (ways > cap) {
                    return out;
                }
                spec.add(new int[]{s, supply});
            }
            if (spec.isEmpty()) {
                return out;
            }
            BitSet[] cur = new BitSet[k];
            for (int t = 0; t < k; t++) {
                cur[t] = new BitSet();
            }
            dfsSkillSkills(0, spec, k, cur, out);
            return out;
        }

        private void dfsSkillSkills(int idx, List<int[]> spec, int k, BitSet[] cur, List<BitSet[]> out) {
            if (idx == spec.size()) {
                BitSet[] copy = new BitSet[k];
                for (int t = 0; t < k; t++) {
                    copy[t] = (BitSet) cur[t].clone();
                }
                out.add(copy);
                return;
            }
            int skill = spec.get(idx)[0];
            int supply = spec.get(idx)[1];
            int[] pick = new int[supply];
            combSkillOffices(0, 0, k, supply, pick, skill, idx, spec, cur, out);
        }

        private void combSkillOffices(int start, int chosen, int k, int supply, int[] pick,
                                      int skill, int idx, List<int[]> spec, BitSet[] cur,
                                      List<BitSet[]> out) {
            if (chosen == supply) {
                for (int p : pick) {
                    cur[p].set(skill);
                }
                dfsSkillSkills(idx + 1, spec, k, cur, out);
                for (int p : pick) {
                    cur[p].clear(skill);
                }
                return;
            }
            for (int i = start; i <= k - (supply - chosen); i++) {
                pick[chosen] = i;
                combSkillOffices(i + 1, chosen + 1, k, supply, pick, skill, idx, spec, cur, out);
            }
        }

        /**
         * Каждому офису — по работнику (перестановка), остаток жадно доклеивается.
         * Так офис может получить сразу набор навыков одного специалиста.
         */
        private List<BitSet[]> workerSkillPermutations(int k) {
            List<BitSet[]> out = new ArrayList<>();
            int nW = inst.workers.length;
            if (k <= 0 || nW < k || k > 4) {
                return out;
            }
            long ways = 1;
            for (int i = 0; i < k; i++) {
                ways *= (nW - i);
                if (ways > 48) {
                    return out;
                }
            }
            boolean[] used = new boolean[nW];
            int[] assign = new int[k];
            dfsWorkerPerm(0, k, used, assign, out);
            return out;
        }

        private void dfsWorkerPerm(int office, int k, boolean[] used, int[] assign, List<BitSet[]> out) {
            if (office == k) {
                BitSet[] skills = new BitSet[k];
                boolean[] takenW = new boolean[inst.workers.length];
                for (int t = 0; t < k; t++) {
                    skills[t] = (BitSet) inst.workerSkills[assign[t]].clone();
                    takenW[assign[t]] = true;
                }
                for (int w = 0; w < inst.workers.length; w++) {
                    if (takenW[w]) {
                        continue;
                    }
                    int bestT = 0;
                    int bestGain = -1;
                    for (int t = 0; t < k; t++) {
                        int gain = 0;
                        BitSet ws = inst.workerSkills[w];
                        for (int s = ws.nextSetBit(0); s >= 0; s = ws.nextSetBit(s + 1)) {
                            if (!skills[t].get(s)) {
                                gain++;
                            }
                        }
                        if (gain > bestGain) {
                            bestGain = gain;
                            bestT = t;
                        }
                    }
                    skills[bestT].or(inst.workerSkills[w]);
                }
                out.add(skills);
                return;
            }
            for (int w = 0; w < inst.workers.length; w++) {
                if (used[w]) {
                    continue;
                }
                used[w] = true;
                assign[office] = w;
                dfsWorkerPerm(office + 1, k, used, assign, out);
                used[w] = false;
            }
        }

        /**
         * Объекты, которые ячейка Вороного не смогла взять из-за навыков,
         * пробуем отдать другому офису с подходящими навыками, если оболочка не конфликтует.
         */
        private void stealSites(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            int nS = inst.sites.length;
            boolean[] used = new boolean[nS];
            for (List<Cand> group : taken) {
                for (Cand c : group) {
                    used[c.site] = true;
                }
            }
            List<int[]> cand = new ArrayList<>();
            for (int s = 0; s < nS; s++) {
                if (used[s] || !inst.siteFeasible(s)) {
                    continue;
                }
                for (int t = 0; t < oid.length; t++) {
                    if (taken[t].size() >= inst.offices[oid[t]].maxSize) {
                        continue;
                    }
                    if (!coveredBy(s, skills[t])) {
                        continue;
                    }
                    double value = inst.sites[s].reward - inst.distances[oid[t]][s];
                    cand.add(new int[]{s, t, Double.hashCode(value)});
                }
            }
            cand.sort((a, b) -> {
                double va = inst.sites[a[0]].reward - inst.distances[oid[a[1]]][a[0]];
                double vb = inst.sites[b[0]].reward - inst.distances[oid[b[1]]][b[0]];
                return Double.compare(vb, va);
            });
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            int added = 0;
            int limit = Math.max(64, nS / 2);
            for (int[] rec : cand) {
                if (!timeLeft() || added >= limit) {
                    break;
                }
                int s = rec[0];
                int t = rec[1];
                if (used[s] || taken[t].size() >= inst.offices[oid[t]].maxSize) {
                    continue;
                }
                Cand c = new Cand(s, inst.sites[s].reward - inst.distances[oid[t]][s], inst.sites[s].tasks.length);
                double before = score(oid, taken);
                taken[t].add(c);
                if (!geometryAllows(oid, taken, t, hulls, covered)) {
                    taken[t].remove(taken[t].size() - 1);
                    continue;
                }
                double after = score(oid, taken);
                if (after <= before + 1e-9) {
                    taken[t].remove(taken[t].size() - 1);
                    continue;
                }
                used[s] = true;
                added++;
                hulls[t] = hullOf(oid[t], taken[t]);
                covered[t] = coverMask(hulls[t]);
            }
        }

        @SuppressWarnings("unchecked")
        private List<Vec>[] currentHulls(int[] oid, List<Cand>[] taken) {
            List<Vec>[] hulls = new List[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (!taken[t].isEmpty()) {
                    hulls[t] = hullOf(oid[t], taken[t]);
                }
            }
            return hulls;
        }

        private List<Vec> hullOf(int officeId, List<Cand> sites) {
            List<Vec> raw = new ArrayList<>(sites.size() + 1);
            raw.add(inst.offices[officeId].pos);
            for (Cand c : sites) {
                raw.add(inst.sites[c.site].pos);
            }
            return Geometry.convexHull(raw);
        }

        private BitSet coverMask(List<Vec> hull) {
            int n = inst.sites.length + inst.offices.length;
            BitSet cov = new BitSet(n);
            if (hull == null || hull.isEmpty()) {
                return cov;
            }
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Vec p : hull) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            double pad = 1e-9;
            for (int i = 0; i < inst.sites.length; i++) {
                Vec p = inst.sites[i].pos;
                if (p.x + pad < minX || p.x - pad > maxX || p.y + pad < minY || p.y - pad > maxY) {
                    continue;
                }
                if (Geometry.covers(hull, p)) {
                    cov.set(i);
                }
            }
            for (int j = 0; j < inst.offices.length; j++) {
                Vec p = inst.offices[j].pos;
                if (p.x + pad < minX || p.x - pad > maxX || p.y + pad < minY || p.y - pad > maxY) {
                    continue;
                }
                if (Geometry.covers(hull, p)) {
                    cov.set(inst.sites.length + j);
                }
            }
            return cov;
        }

        private Cand candOf(int officeId, int site) {
            return new Cand(site, inst.sites[site].reward - inst.distances[officeId][site],
                    inst.sites[site].tasks.length);
        }

        /**
         * Добор leftover только на thorough: прямой add и парный add против α.
         */
        private void recoverLeftovers(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            int guard = 0;
            while (guard++ < RECOVER_GUARD && timeLeft()) {
                List<Integer> rest = leftoverSites(oid, taken);
                if (rest.isEmpty()) {
                    return;
                }
                if (admitDirect(oid, taken, skills, rest)
                        || admitPaired(oid, taken, skills, rest)
                        || admitWithSkillSteal(oid, taken, skills, rest)) {
                    continue;
                }
                break;
            }
        }

        private int leftoverRankCap() {
            return compact() ? inst.sites.length : Math.min(inst.sites.length, LEFTOVER_RANK_CAP);
        }

        private List<Integer> leftoverSites(int[] oid, List<Cand>[] taken) {
            boolean[] used = new boolean[inst.sites.length];
            for (List<Cand> group : taken) {
                for (Cand c : group) {
                    used[c.site] = true;
                }
            }
            List<Integer> rest = new ArrayList<>();
            for (int s = 0; s < inst.sites.length; s++) {
                if (!used[s] && inst.siteFeasible(s)) {
                    rest.add(s);
                }
            }
            double[] val = new double[inst.sites.length];
            for (int s : rest) {
                double bestV = -1e100;
                for (int t = 0; t < oid.length; t++) {
                    if (taken[t].size() >= inst.offices[oid[t]].maxSize) {
                        continue;
                    }
                    bestV = Math.max(bestV, inst.sites[s].reward - inst.distances[oid[t]][s]);
                }
                val[s] = bestV;
            }
            rest.sort((a, b) -> Double.compare(val[b], val[a]));
            int cap = Math.min(rest.size(), leftoverRankCap());
            return new ArrayList<>(rest.subList(0, cap));
        }

        private boolean admitDirect(int[] oid, List<Cand>[] taken, BitSet[] skills, List<Integer> rest) {
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            double before = score(oid, taken);
            int bestT = -1;
            int bestS = -1;
            double bestSc = before;
            for (int t = 0; t < oid.length && timeLeft(); t++) {
                if (taken[t].size() >= inst.offices[oid[t]].maxSize) {
                    continue;
                }
                int tried = 0;
                for (int s : rest) {
                    if (!coveredBy(s, skills[t])) {
                        continue;
                    }
                    int directCap = compact() ? rest.size() : DIRECT_TRY_CAP;
                    if (++tried > directCap) {
                        break;
                    }
                    taken[t].add(candOf(oid[t], s));
                    boolean geo = geometryAllows(oid, taken, t, hulls, covered);
                    double sc = geo ? score(oid, taken) : -1e100;
                    taken[t].remove(taken[t].size() - 1);
                    if (geo && sc > bestSc + 1e-9) {
                        bestSc = sc;
                        bestT = t;
                        bestS = s;
                    }
                }
            }
            if (bestT < 0) {
                return false;
            }
            taken[bestT].add(candOf(oid[bestT], bestS));
            return true;
        }

        private boolean admitPaired(int[] oid, List<Cand>[] taken, BitSet[] skills, List<Integer> rest) {
            if (oid.length < 2 || rest.size() < 2) {
                return false;
            }
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            double before = score(oid, taken);
            int bestT1 = -1, bestT2 = -1, bestS1 = -1, bestS2 = -1;
            double bestSc = before;
            int nTry = Math.min(rest.size(), compact() ? rest.size() : PAIR_TRY_CAP);
            for (int t1 = 0; t1 < oid.length && timeLeft(); t1++) {
                if (taken[t1].size() >= inst.offices[oid[t1]].maxSize) {
                    continue;
                }
                for (int t2 = t1 + 1; t2 < oid.length; t2++) {
                    if (taken[t2].size() >= inst.offices[oid[t2]].maxSize) {
                        continue;
                    }
                    for (int i = 0; i < nTry; i++) {
                        int s1 = rest.get(i);
                        if (!coveredBy(s1, skills[t1])) {
                            continue;
                        }
                        taken[t1].add(candOf(oid[t1], s1));
                        if (!geometryAllows(oid, taken, t1, hulls, covered)) {
                            taken[t1].remove(taken[t1].size() - 1);
                            continue;
                        }
                        List<Vec>[] hulls2 = currentHulls(oid, taken);
                        BitSet[] cov2 = new BitSet[oid.length];
                        for (int u = 0; u < oid.length; u++) {
                            if (hulls2[u] != null) {
                                cov2[u] = coverMask(hulls2[u]);
                            }
                        }
                        for (int j = 0; j < nTry; j++) {
                            int s2 = rest.get(j);
                            if (s2 == s1 || !coveredBy(s2, skills[t2])) {
                                continue;
                            }
                            taken[t2].add(candOf(oid[t2], s2));
                            boolean geo = geometryAllows(oid, taken, t2, hulls2, cov2);
                            double sc = geo ? score(oid, taken) : -1e100;
                            taken[t2].remove(taken[t2].size() - 1);
                            if (geo && sc > bestSc + 1e-9) {
                                bestSc = sc;
                                bestT1 = t1;
                                bestT2 = t2;
                                bestS1 = s1;
                                bestS2 = s2;
                            }
                        }
                        taken[t1].remove(taken[t1].size() - 1);
                    }
                }
            }
            if (bestT1 < 0) {
                return false;
            }
            taken[bestT1].add(candOf(oid[bestT1], bestS1));
            taken[bestT2].add(candOf(oid[bestT2], bestS2));
            return true;
        }

        /**
         * Leftover с навыком, которого нет у зоны: забираем навык у офиса,
         * которому он сейчас не нужен, либо берём свободный слот skillSupply.
         */
        private boolean admitWithSkillSteal(int[] oid, List<Cand>[] taken, BitSet[] skills,
                                            List<Integer> rest) {
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            double before = score(oid, taken);
            int bestS = -1;
            int bestT = -1;
            BitSet[] bestSkills = null;
            double bestSc = before;
            int nTry = Math.min(rest.size(), compact() ? rest.size() : 48);
            for (int i = 0; i < nTry && timeLeft(); i++) {
                int s = rest.get(i);
                for (int t = 0; t < oid.length; t++) {
                    if (taken[t].size() >= inst.offices[oid[t]].maxSize) {
                        continue;
                    }
                    if (coveredBy(s, skills[t])) {
                        continue;
                    }
                    BitSet[] trial = cloneSkills(skills);
                    if (!giveSkillsToOffice(t, trial, taken, inst.sites[s].skillSet)) {
                        continue;
                    }
                    taken[t].add(candOf(oid[t], s));
                    boolean geo = geometryAllows(oid, taken, t, hulls, covered);
                    boolean work = geo && workersFeasible(oid, taken);
                    double sc = work ? score(oid, taken) : -1e100;
                    taken[t].remove(taken[t].size() - 1);
                    if (work && sc > bestSc + 1e-9) {
                        bestSc = sc;
                        bestS = s;
                        bestT = t;
                        bestSkills = trial;
                    }
                }
            }
            if (bestS < 0 || bestSkills == null) {
                return false;
            }
            for (int t = 0; t < skills.length; t++) {
                skills[t] = bestSkills[t];
            }
            taken[bestT].add(candOf(oid[bestT], bestS));
            return true;
        }

        private BitSet[] cloneSkills(BitSet[] skills) {
            BitSet[] copy = new BitSet[skills.length];
            for (int i = 0; i < skills.length; i++) {
                copy[i] = (BitSet) skills[i].clone();
            }
            return copy;
        }

        private boolean giveSkillsToOffice(int t, BitSet[] skills, List<Cand>[] taken, BitSet need) {
            BitSet missing = (BitSet) need.clone();
            missing.andNot(skills[t]);
            for (int sk = missing.nextSetBit(0); sk >= 0; sk = missing.nextSetBit(sk + 1)) {
                int holders = 0;
                for (BitSet b : skills) {
                    if (b.get(sk)) {
                        holders++;
                    }
                }
                int supply = sk < inst.skillSupply.length ? inst.skillSupply[sk] : 0;
                if (holders < supply) {
                    skills[t].set(sk);
                    continue;
                }
                boolean stole = false;
                for (int u = 0; u < skills.length; u++) {
                    if (u == t || !skills[u].get(sk)) {
                        continue;
                    }
                    if (!officeUsesSkill(taken[u], sk)) {
                        skills[u].clear(sk);
                        skills[t].set(sk);
                        stole = true;
                        break;
                    }
                }
                if (!stole) {
                    return false;
                }
            }
            return true;
        }

        private boolean officeUsesSkill(List<Cand> group, int skill) {
            for (Cand c : group) {
                if (inst.sites[c.site].skillSet.get(skill)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Leftover часто упирается в оболочку. Вытесняем один объект офиса,
         * вставляем leftover, вытесненный пробуем пристроить в другой офис.
         */
        private boolean insertLeftoversByKick(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            boolean any = false;
            int guard = 0;
            while (guard++ < inst.sites.length && insertLeftoverOnce(oid, taken, skills)) {
                any = true;
            }
            return any;
        }

        private boolean insertLeftoverOnce(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            List<Integer> rest = leftoverSites(oid, taken);
            if (rest.isEmpty()) {
                return false;
            }
            double cur = score(oid, taken);
            int bestS = -1;
            int bestT = -1;
            int bestKick = -1;
            int bestU = -1;
            double bestSc = cur;
            int nTry = Math.min(rest.size(), compact() ? rest.size() : 32);
            for (int i = 0; i < nTry && timeLeft(); i++) {
                int s = rest.get(i);
                for (int t = 0; t < oid.length; t++) {
                    if (!coveredBy(s, skills[t])) {
                        continue;
                    }
                    int cap = inst.offices[oid[t]].maxSize;
                    if (taken[t].size() < cap) {
                        taken[t].add(candOf(oid[t], s));
                        boolean ok = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                        double sc = ok ? score(oid, taken) : -1e100;
                        taken[t].remove(taken[t].size() - 1);
                        if (ok && sc > bestSc + 1e-9) {
                            bestSc = sc;
                            bestS = s;
                            bestT = t;
                            bestKick = -1;
                            bestU = -1;
                        }
                    }
                    for (int j = 0; j < taken[t].size(); j++) {
                        Cand kicked = taken[t].remove(j);
                        taken[t].add(candOf(oid[t], s));
                        if (assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken)) {
                            double sc = score(oid, taken);
                            if (sc > bestSc + 1e-9) {
                                bestSc = sc;
                                bestS = s;
                                bestT = t;
                                bestKick = j;
                                bestU = -1;
                            }
                            for (int u = 0; u < oid.length; u++) {
                                if (u == t || taken[u].size() >= inst.offices[oid[u]].maxSize
                                        || !coveredBy(kicked.site, skills[u])) {
                                    continue;
                                }
                                taken[u].add(candOf(oid[u], kicked.site));
                                boolean ok2 = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                                double sc2 = ok2 ? score(oid, taken) : -1e100;
                                taken[u].remove(taken[u].size() - 1);
                                if (ok2 && sc2 > bestSc + 1e-9) {
                                    bestSc = sc2;
                                    bestS = s;
                                    bestT = t;
                                    bestKick = j;
                                    bestU = u;
                                }
                            }
                        }
                        taken[t].remove(taken[t].size() - 1);
                        taken[t].add(j, kicked);
                    }
                }
            }
            if (bestS < 0) {
                return false;
            }
            if (bestKick < 0) {
                taken[bestT].add(candOf(oid[bestT], bestS));
                return true;
            }
            Cand kicked = taken[bestT].remove(bestKick);
            taken[bestT].add(candOf(oid[bestT], bestS));
            if (bestU >= 0) {
                taken[bestU].add(candOf(oid[bestU], kicked.site));
            }
            return true;
        }

        /**
         * Локальный поиск назначения: закрыть слабый офис, выровнять |сайтов|,
         * перенести/обменять объекты. Геометрия и работники проверяются сразу.
         */
        private void polish(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            int guard = 0;
            int guardMax = compact() ? 80 : 24;
            boolean changed = true;
            boolean shedTried = false;
            while (changed && guard++ < guardMax && timeLeft()) {
                changed = tryCloseOffice(oid, taken)
                        || tryShiftToBalance(oid, taken, skills, true)
                        || relocateBest(oid, taken, skills)
                        || swapBest(oid, taken, skills)
                        || insertLeftoversByKick(oid, taken, skills);
                // Один раз за polish: снять дешёвую вершину оболочки и дорастить зоны.
                if (!changed && compact() && !shedTried && timeLeft()) {
                    shedTried = true;
                    changed = shedBlockingVertex(oid, taken, skills);
                }
            }
        }

        /**
         * Вершина оболочки держит площадь зоны и часто накрывает чужой leftover.
         * Снимаем до трёх самых дешёвых вершин по очереди, доращиваем зоны
         * и оставляем вариант, только если цель выросла и ограничения живы.
         * На крупных картах не вызывается: там дороже поиск набора офисов.
         */
        @SuppressWarnings("unchecked")
        private boolean shedBlockingVertex(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            List<int[]> verts = new ArrayList<>();
            for (int t = 0; t < oid.length; t++) {
                if (taken[t].size() < 2) {
                    continue;
                }
                List<Vec> hull = hullOf(oid[t], taken[t]);
                for (int j = 0; j < taken[t].size(); j++) {
                    Vec p = inst.sites[taken[t].get(j).site].pos;
                    for (Vec h : hull) {
                        if (Geometry.same(h, p)) {
                            verts.add(new int[]{t, j});
                            break;
                        }
                    }
                }
            }
            if (verts.isEmpty()) {
                return false;
            }
            verts.sort((a, b) -> Double.compare(
                    taken[a[0]].get(a[1]).value,
                    taken[b[0]].get(b[1]).value));
            int trials = Math.min(3, verts.size());
            List<Cand>[] origin = copyGroups(taken);
            double cur = score(oid, taken);
            List<Cand>[] bestTaken = null;
            double bestSc = cur;
            for (int i = 0; i < trials && timeLeft(); i++) {
                restoreGroups(taken, origin);
                int t = verts.get(i)[0];
                int j = verts.get(i)[1];
                if (j < 0 || j >= taken[t].size()) {
                    continue;
                }
                int banned = taken[t].get(j).site;
                taken[t].remove(j);
                // Сначала чужие зоны: иначе снятая вершина сразу возвращается на место.
                for (int u = 0; u < oid.length && timeLeft(); u++) {
                    if (u == t) {
                        continue;
                    }
                    growOneOffice(oid, taken, skills, u, banned);
                }
                growOneOffice(oid, taken, skills, t, banned);
                if (assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken)) {
                    double sc = score(oid, taken);
                    if (sc > bestSc + 1e-9) {
                        bestSc = sc;
                        bestTaken = copyGroups(taken);
                    }
                }
            }
            if (bestTaken == null) {
                restoreGroups(taken, origin);
                return false;
            }
            restoreGroups(taken, bestTaken);
            return true;
        }

        private boolean tryCloseOffice(int[] oid, List<Cand>[] taken) {
            if (liveCount(taken) < 2) {
                return false;
            }
            double cur = score(oid, taken);
            int bestT = -1;
            double bestSc = cur;
            for (int t = 0; t < oid.length && timeLeft(); t++) {
                if (taken[t].isEmpty()) {
                    continue;
                }
                List<Cand> saved = new ArrayList<>(taken[t]);
                taken[t].clear();
                boolean ok = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                double sc = ok ? score(oid, taken) : -1e100;
                taken[t].addAll(saved);
                if (sc > bestSc + 1e-9) {
                    bestSc = sc;
                    bestT = t;
                }
            }
            if (bestT < 0) {
                return false;
            }
            taken[bestT].clear();
            return true;
        }

        private boolean tryShiftToBalance(int[] oid, List<Cand>[] taken, BitSet[] skills, boolean checkGeo) {
            int minI = -1;
            int maxI = -1;
            int minV = Integer.MAX_VALUE;
            int maxV = -1;
            for (int t = 0; t < taken.length; t++) {
                int sz = taken[t].size();
                if (sz == 0) {
                    continue;
                }
                if (sz < minV) {
                    minV = sz;
                    minI = t;
                }
                if (sz > maxV) {
                    maxV = sz;
                    maxI = t;
                }
            }
            if (minI < 0 || maxI < 0 || minI == maxI || maxV - minV <= inst.maxDifference) {
                return false;
            }
            if (taken[minI].size() >= inst.offices[oid[minI]].maxSize) {
                return false;
            }
            double cur = score(oid, taken);
            int bestJ = -1;
            double bestSc = cur;
            for (int j = 0; j < taken[maxI].size() && timeLeft(); j++) {
                Cand c = taken[maxI].get(j);
                if (skills != null && !coveredBy(c.site, skills[minI])) {
                    continue;
                }
                taken[maxI].remove(j);
                taken[minI].add(candOf(oid[minI], c.site));
                boolean ok = true;
                if (checkGeo) {
                    ok = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                }
                double sc = ok ? score(oid, taken) : -1e100;
                taken[minI].remove(taken[minI].size() - 1);
                taken[maxI].add(j, c);
                if (sc > bestSc + 1e-9) {
                    bestSc = sc;
                    bestJ = j;
                }
            }
            if (bestJ < 0) {
                return false;
            }
            Cand c = taken[maxI].remove(bestJ);
            taken[minI].add(candOf(oid[minI], c.site));
            return true;
        }

        private boolean relocateBest(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            double cur = score(oid, taken);
            int bestFrom = -1;
            int bestIdx = -1;
            int bestTo = -1;
            double bestSc = cur;
            int perOffice = inst.sites.length <= 80 ? Integer.MAX_VALUE : 10;
            for (int t = 0; t < oid.length && timeLeft(); t++) {
                int nTry = Math.min(taken[t].size(), perOffice);
                for (int i = 0; i < nTry; i++) {
                    Cand c = taken[t].get(i);
                    for (int u = 0; u < oid.length; u++) {
                        if (u == t || taken[u].size() >= inst.offices[oid[u]].maxSize) {
                            continue;
                        }
                        if (!coveredBy(c.site, skills[u])) {
                            continue;
                        }
                        taken[t].remove(i);
                        taken[u].add(candOf(oid[u], c.site));
                        boolean ok = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                        double sc = ok ? score(oid, taken) : -1e100;
                        taken[u].remove(taken[u].size() - 1);
                        taken[t].add(i, c);
                        if (sc > bestSc + 1e-9) {
                            bestSc = sc;
                            bestFrom = t;
                            bestIdx = i;
                            bestTo = u;
                        }
                    }
                }
            }
            if (bestFrom < 0) {
                return false;
            }
            Cand c = taken[bestFrom].remove(bestIdx);
            taken[bestTo].add(candOf(oid[bestTo], c.site));
            return true;
        }

        private boolean swapBest(int[] oid, List<Cand>[] taken, BitSet[] skills) {
            if (oid.length < 2) {
                return false;
            }
            // Полный обмен только на мелкой карте. На compact режем число пар,
            // иначе polish на тесте класса M не успевает.
            int pairCap = inst.sites.length <= 90 ? Integer.MAX_VALUE : (compact() ? 8 : 0);
            if (pairCap == 0) {
                return false;
            }
            double cur = score(oid, taken);
            int bestT = -1, bestI = -1, bestU = -1, bestJ = -1;
            double bestSc = cur;
            for (int t = 0; t < oid.length && timeLeft(); t++) {
                for (int u = t + 1; u < oid.length; u++) {
                    if (taken[t].isEmpty() || taken[u].isEmpty()) {
                        continue;
                    }
                    int[] topI = highestValueIndices(taken[t], pairCap);
                    int[] topJ = highestValueIndices(taken[u], pairCap);
                    for (int i : topI) {
                        Cand a = taken[t].get(i);
                        if (!coveredBy(a.site, skills[u])) {
                            continue;
                        }
                        for (int j : topJ) {
                            Cand b = taken[u].get(j);
                            if (!coveredBy(b.site, skills[t])) {
                                continue;
                            }
                            taken[t].set(i, candOf(oid[t], b.site));
                            taken[u].set(j, candOf(oid[u], a.site));
                            boolean ok = assignmentGeometryOk(oid, taken) && workersFeasible(oid, taken);
                            double sc = ok ? score(oid, taken) : -1e100;
                            taken[t].set(i, a);
                            taken[u].set(j, b);
                            if (sc > bestSc + 1e-9) {
                                bestSc = sc;
                                bestT = t;
                                bestI = i;
                                bestU = u;
                                bestJ = j;
                            }
                        }
                    }
                }
            }
            if (bestT < 0) {
                return false;
            }
            Cand a = taken[bestT].get(bestI);
            Cand b = taken[bestU].get(bestJ);
            taken[bestT].set(bestI, candOf(oid[bestT], b.site));
            taken[bestU].set(bestJ, candOf(oid[bestU], a.site));
            return true;
        }

        /** Индексы самых ценных объектов. При cap больше размера списка — все. */
        private int[] highestValueIndices(List<Cand> list, int cap) {
            int n = list.size();
            if (cap >= n) {
                int[] all = new int[n];
                for (int i = 0; i < n; i++) {
                    all[i] = i;
                }
                return all;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(list.get(b).value, list.get(a).value));
            int[] top = new int[cap];
            for (int i = 0; i < cap; i++) {
                top[i] = order[i];
            }
            return top;
        }

        private boolean assignmentGeometryOk(int[] oid, List<Cand>[] taken) {
            List<Vec>[] hulls = currentHulls(oid, taken);
            BitSet[] covered = new BitSet[oid.length];
            for (int t = 0; t < oid.length; t++) {
                if (hulls[t] != null) {
                    covered[t] = coverMask(hulls[t]);
                }
            }
            for (int i = 0; i < oid.length; i++) {
                if (covered[i] == null) {
                    continue;
                }
                for (int j = i + 1; j < oid.length; j++) {
                    if (covered[j] != null && covered[i].intersects(covered[j])) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean workersFeasible(int[] oid, List<Cand>[] taken) {
            List<BitSet> needs = new ArrayList<>();
            for (List<Cand> group : taken) {
                if (group.isEmpty()) {
                    continue;
                }
                List<Integer> sites = new ArrayList<>(group.size());
                for (Cand c : group) {
                    sites.add(c.site);
                }
                needs.add(skillsOf(sites));
            }
            if (needs.isEmpty()) {
                return false;
            }
            return allocateWorkers(needs.toArray(BitSet[]::new)) != null;
        }

        private boolean geometryAllows(int[] oid, List<Cand>[] taken, int t,
                                       List<Vec>[] hulls, BitSet[] covered) {
            BitSet cov = coverMask(hullOf(oid[t], taken[t]));
            for (int u = 0; u < oid.length; u++) {
                if (u != t && covered[u] != null && cov.intersects(covered[u])) {
                    return false;
                }
            }
            return true;
        }

        private double coverableValue(int officeId, List<Cand> pool, BitSet skills) {
            double sum = 0;
            int cap = inst.offices[officeId].maxSize;
            int taken = 0;
            for (Cand c : pool) {
                if (c.value <= 0 || !coveredBy(c.site, skills)) {
                    continue;
                }
                sum += c.value;
                taken++;
                if (taken >= cap) {
                    break;
                }
            }
            return sum;
        }

        private boolean coveredBy(int siteId, BitSet skills) {
            BitSet need = inst.sites[siteId].skillSet;
            for (int s = need.nextSetBit(0); s >= 0; s = need.nextSetBit(s + 1)) {
                if (!skills.get(s)) {
                    return false;
                }
            }
            return true;
        }

        @SuppressWarnings("unchecked")
        private void improve(int[] oid, List<Cand>[] taken, List<Cand>[] left,
                             BitSet[] officeSkills, boolean thorough) {
            if (!thorough) {
                improveOnce(oid, taken, left, officeSkills, 0);
                return;
            }
            List<Cand>[] startTaken = copyGroups(taken);
            List<Cand>[] startLeft = copyGroups(left);
            double bestSc = -1e300;
            List<Cand>[] bestTaken = copyGroups(taken);
            List<Cand>[] bestLeft = copyGroups(left);
            // 0 — старый порядок (trim→add→drop), 1 — сдвиг до trim, 2 — сдвиг после trim.
            for (int mode = 0; mode <= 2; mode++) {
                restoreGroups(taken, startTaken);
                restoreGroups(left, startLeft);
                improveOnce(oid, taken, left, officeSkills, mode);
                double sc = score(oid, taken);
                if (sc > bestSc + 1e-9) {
                    bestSc = sc;
                    bestTaken = copyGroups(taken);
                    bestLeft = copyGroups(left);
                }
            }
            restoreGroups(taken, bestTaken);
            restoreGroups(left, bestLeft);
        }

        private void improveOnce(int[] oid, List<Cand>[] taken, List<Cand>[] left,
                                 BitSet[] officeSkills, int mode) {
            int guard = 0;
            boolean changed = true;
            while (changed && guard++ < 4000 && timeLeft()) {
                changed = false;
                if (mode == 0) {
                    if (trimSiteCounts(oid, taken, left)) {
                        changed = true;
                        continue;
                    }
                    if (tryBestAdd(oid, taken, left, officeSkills, 12)) {
                        changed = true;
                        continue;
                    }
                    if (tryBestDrop(oid, taken, left)) {
                        changed = true;
                    }
                    continue;
                }
                int addCap = inst.sites.length <= 80 ? Integer.MAX_VALUE : 12;
                if (tryBestAdd(oid, taken, left, officeSkills, addCap)) {
                    changed = true;
                    continue;
                }
                if (mode == 1 && tryShiftToBalance(oid, taken, officeSkills, false)) {
                    changed = true;
                    continue;
                }
                if (trimSiteCounts(oid, taken, left)) {
                    changed = true;
                    continue;
                }
                if (mode == 2 && tryShiftToBalance(oid, taken, officeSkills, false)) {
                    changed = true;
                    continue;
                }
                if (tryBestDrop(oid, taken, left)) {
                    changed = true;
                }
            }
            trimSiteCounts(oid, taken, left);
        }

        @SuppressWarnings("unchecked")
        private List<Cand>[] copyGroups(List<Cand>[] src) {
            List<Cand>[] copy = new List[src.length];
            for (int i = 0; i < src.length; i++) {
                copy[i] = new ArrayList<>(src[i]);
            }
            return copy;
        }

        private void restoreGroups(List<Cand>[] dst, List<Cand>[] src) {
            for (int i = 0; i < dst.length; i++) {
                dst[i].clear();
                dst[i].addAll(src[i]);
            }
        }

        private boolean trimSiteCounts(int[] oid, List<Cand>[] taken, List<Cand>[] left) {
            boolean any = false;
            int guard = 0;
            while (guard++ < inst.sites.length + 2) {
                int minI = -1;
                int maxI = -1;
                int minV = Integer.MAX_VALUE;
                int maxV = -1;
                int opened = 0;
                for (int t = 0; t < taken.length; t++) {
                    int sz = taken[t].size();
                    if (sz == 0) {
                        continue;
                    }
                    opened++;
                    if (sz < minV) {
                        minV = sz;
                        minI = t;
                    }
                    if (sz > maxV) {
                        maxV = sz;
                        maxI = t;
                    }
                }
                if (opened < 2 || maxV - minV <= inst.maxDifference) {
                    return any;
                }
                int dropAt = worstIndex(taken[maxI]);
                left[maxI].add(taken[maxI].remove(dropAt));
                any = true;
            }
            return any;
        }

        /** Добавляем объект, если прирост цели положительный (в т.ч. ради баланса задач). */
        private boolean tryBestAdd(int[] oid, List<Cand>[] taken, List<Cand>[] left,
                                   BitSet[] officeSkills, int addCap) {
            double cur = score(oid, taken);
            int bestT = -1;
            int bestJ = -1;
            double bestSc = cur;
            for (int t = 0; t < taken.length; t++) {
                int cap = inst.offices[oid[t]].maxSize;
                if (taken[t].size() >= cap || left[t].isEmpty()) {
                    continue;
                }
                indexByGain(left[t]);
                int tried = 0;
                for (int j = 0; j < left[t].size() && tried < addCap; j++) {
                    Cand c = left[t].get(j);
                    if (!coveredBy(c.site, officeSkills[t])) {
                        continue;
                    }
                    tried++;
                    taken[t].add(c);
                    double sc = score(oid, taken);
                    taken[t].remove(taken[t].size() - 1);
                    if (sc > bestSc + 1e-9) {
                        bestSc = sc;
                        bestT = t;
                        bestJ = j;
                    }
                }
            }
            if (bestT < 0) {
                return false;
            }
            taken[bestT].add(left[bestT].remove(bestJ));
            return true;
        }

        /** Снимаем объект с перегруженного по задачам офиса, если это увеличивает цель. */
        private boolean tryBestDrop(int[] oid, List<Cand>[] taken, List<Cand>[] left) {
            int maxT = maxTaskCluster(taken);
            if (maxT < 0 || (taken[maxT].size() <= 1 && liveCount(taken) == 1)) {
                return false;
            }
            double cur = score(oid, taken);
            int bestJ = -1;
            double bestSc = cur;
            Integer[] order = new Integer[taken[maxT].size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingDouble(j -> taken[maxT].get(j).value));
            int limit = Math.min(order.length, 16);
            for (int pos = 0; pos < limit; pos++) {
                int j = order[pos];
                Cand c = taken[maxT].remove(j);
                double sc = score(oid, taken);
                taken[maxT].add(j, c);
                if (sc > bestSc + 1e-9) {
                    bestSc = sc;
                    bestJ = j;
                }
            }
            if (bestJ < 0) {
                return false;
            }
            left[maxT].add(taken[maxT].remove(bestJ));
            return true;
        }

        private void indexByGain(List<Cand> list) {
            double alpha = inst.balanceCoefficient;
            list.sort((a, b) -> Double.compare(b.value + alpha * b.tasks, a.value + alpha * a.tasks));
        }

        private int worstIndex(List<Cand> list) {
            int w = 0;
            double worst = Double.POSITIVE_INFINITY;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).value < worst) {
                    worst = list.get(i).value;
                    w = i;
                }
            }
            return w;
        }

        private int liveCount(List<Cand>[] taken) {
            int n = 0;
            for (List<Cand> t : taken) {
                if (!t.isEmpty()) {
                    n++;
                }
            }
            return n;
        }

        private int maxTaskCluster(List<Cand>[] taken) {
            int best = -1;
            int bestLoad = -1;
            for (int t = 0; t < taken.length; t++) {
                if (taken[t].isEmpty()) {
                    continue;
                }
                int load = 0;
                for (Cand c : taken[t]) {
                    load += c.tasks;
                }
                if (load > bestLoad) {
                    bestLoad = load;
                    best = t;
                }
            }
            return best;
        }

        private double score(int[] oid, List<Cand>[] taken) {
            int opened = 0;
            int minS = Integer.MAX_VALUE;
            int maxS = 0;
            int minT = Integer.MAX_VALUE;
            int maxT = 0;
            double reward = 0;
            double dist = 0;
            double cost = 0;
            for (int t = 0; t < taken.length; t++) {
                if (taken[t].isEmpty()) {
                    continue;
                }
                opened++;
                cost += inst.offices[oid[t]].cost;
                int load = 0;
                for (Cand c : taken[t]) {
                    reward += inst.sites[c.site].reward;
                    dist += inst.distances[oid[t]][c.site];
                    load += c.tasks;
                }
                int sz = taken[t].size();
                minS = Math.min(minS, sz);
                maxS = Math.max(maxS, sz);
                minT = Math.min(minT, load);
                maxT = Math.max(maxT, load);
            }
            if (opened == 0) {
                return -1e100;
            }
            if (maxS - minS > inst.maxDifference) {
                return -1e100;
            }
            return reward - dist - cost - inst.balanceCoefficient * (maxT - minT);
        }

        private Solution finalize(int[] oid, List<Cand>[] taken) {
            List<Integer> usedOff = new ArrayList<>();
            List<List<Integer>> groups = new ArrayList<>();
            List<BitSet> needs = new ArrayList<>();
            for (int t = 0; t < oid.length; t++) {
                if (taken[t].isEmpty()) {
                    continue;
                }
                List<Integer> sites = new ArrayList<>();
                for (Cand c : taken[t]) {
                    sites.add(c.site);
                }
                usedOff.add(oid[t]);
                groups.add(sites);
                needs.add(skillsOf(sites));
            }
            if (usedOff.isEmpty()) {
                return Solution.empty();
            }

            List<Integer>[] workers = allocateWorkers(needs.toArray(BitSet[]::new));
            if (workers == null) {
                for (int i = 0; i < groups.size(); i++) {
                    groups.set(i, dropUntilCoverable(groups, i));
                }
                List<Integer> off2 = new ArrayList<>();
                List<List<Integer>> gr2 = new ArrayList<>();
                List<BitSet> n2 = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) {
                    if (groups.get(i).isEmpty()) {
                        continue;
                    }
                    off2.add(usedOff.get(i));
                    gr2.add(groups.get(i));
                    n2.add(skillsOf(groups.get(i)));
                }
                usedOff = off2;
                groups = gr2;
                needs = n2;
                if (usedOff.isEmpty()) {
                    return Solution.empty();
                }
                workers = allocateWorkers(needs.toArray(BitSet[]::new));
                if (workers == null) {
                    return Solution.empty();
                }
            }

            enforceSiteBalance(usedOff, groups);
            if (usedOff.isEmpty()) {
                return Solution.empty();
            }
            needs.clear();
            for (List<Integer> g : groups) {
                needs.add(skillsOf(g));
            }
            workers = allocateWorkers(needs.toArray(BitSet[]::new));
            if (workers == null) {
                return Solution.empty();
            }

            List<OpenedOffice> opened = new ArrayList<>();
            for (int i = 0; i < usedOff.size(); i++) {
                opened.add(new OpenedOffice(usedOff.get(i), groups.get(i), workers[i]));
            }
            if (opened.size() > 1 && !Constraints.valid(inst, opened)) {
                opened = repairGeometry(opened);
                if (opened.isEmpty() || !Constraints.valid(inst, opened)) {
                    return Solution.empty();
                }
            }
            return pack(opened);
        }

        private void enforceSiteBalance(List<Integer> officeIds, List<List<Integer>> groups) {
            int guard = 0;
            while (guard++ < inst.sites.length + 2 && !groups.isEmpty()) {
                int minI = 0;
                int maxI = 0;
                int minV = groups.get(0).size();
                int maxV = minV;
                for (int i = 1; i < groups.size(); i++) {
                    int sz = groups.get(i).size();
                    if (sz < minV) {
                        minV = sz;
                        minI = i;
                    }
                    if (sz > maxV) {
                        maxV = sz;
                        maxI = i;
                    }
                }
                if (maxV - minV <= inst.maxDifference) {
                    return;
                }
                List<Integer> big = groups.get(maxI);
                if (big.isEmpty()) {
                    officeIds.remove(maxI);
                    groups.remove(maxI);
                    continue;
                }
                int dropAt = 0;
                double worst = Double.POSITIVE_INFINITY;
                int officeId = officeIds.get(maxI);
                for (int j = 0; j < big.size(); j++) {
                    int s = big.get(j);
                    double v = inst.sites[s].reward - inst.distances[officeId][s];
                    if (v < worst) {
                        worst = v;
                        dropAt = j;
                    }
                }
                big.remove(dropAt);
                if (big.isEmpty()) {
                    officeIds.remove(maxI);
                    groups.remove(maxI);
                }
            }
        }

        /**
         * Если оболочки всё же пересеклись (численность / ничья Вороного),
         * снимаем самый дальний объект у большего кластера.
         */
        private List<OpenedOffice> repairGeometry(List<OpenedOffice> opened) {
            int guard = 0;
            while (guard++ < inst.sites.length && !opened.isEmpty() && !Constraints.valid(inst, opened)) {
                int maxI = 0;
                for (int i = 1; i < opened.size(); i++) {
                    if (opened.get(i).sites.size() > opened.get(maxI).sites.size()) {
                        maxI = i;
                    }
                }
                OpenedOffice o = opened.get(maxI);
                if (o.sites.isEmpty()) {
                    opened.remove(maxI);
                    continue;
                }
                int far = 0;
                double farD = -1;
                for (int j = 0; j < o.sites.size(); j++) {
                    double d = Geometry.euclid2(inst.sites[o.sites.get(j)].pos, inst.offices[o.officeId].pos);
                    if (d > farD) {
                        farD = d;
                        far = j;
                    }
                }
                List<Integer> nextSites = new ArrayList<>(o.sites);
                nextSites.remove(far);
                if (nextSites.isEmpty()) {
                    opened.remove(maxI);
                    continue;
                }
                BitSet need = skillsOf(nextSites);
                List<Integer>[] w = allocateWorkers(new BitSet[]{need});
                if (w == null) {
                    opened.remove(maxI);
                    continue;
                }
                opened.set(maxI, new OpenedOffice(o.officeId, nextSites, w[0]));
                List<BitSet> needs = new ArrayList<>();
                for (OpenedOffice x : opened) {
                    needs.add(skillsOf(x.sites));
                }
                List<Integer>[] allW = allocateWorkers(needs.toArray(BitSet[]::new));
                if (allW == null) {
                    opened.remove(maxI);
                    continue;
                }
                List<OpenedOffice> rebuilt = new ArrayList<>();
                for (int i = 0; i < opened.size(); i++) {
                    rebuilt.add(new OpenedOffice(opened.get(i).officeId, opened.get(i).sites, allW[i]));
                }
                opened = rebuilt;
            }
            return opened;
        }

        private List<Integer> dropUntilCoverable(List<List<Integer>> groups, int idx) {
            List<Integer> rest = new ArrayList<>(groups.get(idx));
            while (!rest.isEmpty()) {
                BitSet[] needs = new BitSet[groups.size()];
                for (int i = 0; i < groups.size(); i++) {
                    needs[i] = i == idx ? skillsOf(rest) : skillsOf(groups.get(i));
                }
                if (allocateWorkers(needs) != null) {
                    return rest;
                }
                int drop = scarcestSite(rest);
                if (drop < 0) {
                    rest.remove(rest.size() - 1);
                } else {
                    rest.remove(Integer.valueOf(drop));
                }
            }
            return rest;
        }

        private int scarcestSite(List<Integer> sites) {
            int best = -1;
            int bestSupply = Integer.MAX_VALUE;
            for (int s : sites) {
                for (int skill = inst.sites[s].skillSet.nextSetBit(0);
                     skill >= 0;
                     skill = inst.sites[s].skillSet.nextSetBit(skill + 1)) {
                    int supply = skill < inst.skillSupply.length ? inst.skillSupply[skill] : 0;
                    if (supply < bestSupply) {
                        bestSupply = supply;
                        best = s;
                    }
                }
            }
            return best;
        }

        private BitSet skillsOf(List<Integer> sites) {
            BitSet need = new BitSet();
            for (int s : sites) {
                need.or(inst.sites[s].skillSet);
            }
            return need;
        }

        @SuppressWarnings("unchecked")
        private List<Integer>[] allocateWorkers(BitSet[] needs) {
            boolean[] used = new boolean[inst.workers.length];
            List<Integer>[] perOffice = new List[needs.length];
            for (int i = 0; i < needs.length; i++) {
                perOffice[i] = new ArrayList<>();
                BitSet remain = (BitSet) needs[i].clone();
                while (!remain.isEmpty()) {
                    int bestW = -1;
                    int bestCover = 0;
                    for (int w = 0; w < inst.workers.length; w++) {
                        if (used[w]) {
                            continue;
                        }
                        int cover = 0;
                        for (int s = remain.nextSetBit(0); s >= 0; s = remain.nextSetBit(s + 1)) {
                            if (inst.workerSkills[w].get(s)) {
                                cover++;
                            }
                        }
                        if (cover > bestCover) {
                            bestCover = cover;
                            bestW = w;
                        }
                    }
                    if (bestW < 0 || bestCover == 0) {
                        return null;
                    }
                    used[bestW] = true;
                    perOffice[i].add(bestW);
                    remain.andNot(inst.workerSkills[bestW]);
                }
            }
            return perOffice;
        }

        private Solution pack(List<OpenedOffice> opened) {
            if (opened.isEmpty()) {
                return Solution.empty();
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
            double obj = reward - distance - cost - inst.balanceCoefficient * (maxTasks - minTasks);
            return new Solution(opened, obj);
        }

        private static long binom(int n, int k) {
            if (k < 0 || k > n) {
                return 0;
            }
            long r = 1;
            for (int i = 1; i <= k; i++) {
                r = r * (n - k + i) / i;
                if (r > 10_000_000L) {
                    return r;
                }
            }
            return r;
        }
    }
}
