import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Автотесты TechArena 2026: каждый запуск — новый seed и новые карты.
 * {@code java LocalSuite} / {@code exam} — генерация + прогон солвера, все должны PASS.
 * {@code generate} — записать карты; {@code train} — улучшить сохранённые ответы.
 * Для воспроизведения падения: {@code java LocalSuite exam 1000 <dir> <seed>}.
 */
public class LocalSuite {

    /** Сколько задач в одном прогоне автогенерации. */
    private static final int TEST_COUNT = 1000;
    /** Лимит солвера на одну карту экзамена (малые nOff заканчивают раньше). */
    private static final long EXAM_SOLVE_MS = 2_500L;
    private static final Path DEFAULT_TESTS =
            Path.of("src/main/java/org/example/hackathons/TechArena2026/data/local_tests");
    private static final Path DEFAULT_EXAM =
            Path.of("src/main/java/org/example/hackathons/TechArena2026/data/local_exam");
    /** Крупные карты, похожие на скрытые тесты (nOff 8–15, leftover/оболочки). */
    private static final Path DEFAULT_HARD =
            Path.of("src/main/java/org/example/hackathons/TechArena2026/data/hard_tests");
    private static final int HARD_COUNT = 80;
    private static final long HARD_SOLVE_MS = 8_000L;

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "exam" : args[0];
        if ("train".equals(cmd)) {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : TEST_COUNT;
            Path root = Path.of(args.length > 2 ? args[2] : DEFAULT_TESTS.toString());
            train(root, count);
            return;
        }
        if ("check".equals(cmd)) {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : TEST_COUNT;
            Path root = Path.of(args.length > 2 ? args[2] : DEFAULT_TESTS.toString());
            long timeMs = args.length > 3 ? Long.parseLong(args[3]) : EXAM_SOLVE_MS;
            boolean passed = check(root, count, timeMs);
            if (!passed) {
                System.exit(1);
            }
            return;
        }
        if ("hard".equals(cmd)) {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : HARD_COUNT;
            Path root = Path.of(args.length > 2 ? args[2] : DEFAULT_HARD.toString());
            long seed = args.length > 3 ? Long.parseLong(args[3]) : freshSeed();
            generateHard(root, count, seed);
            return;
        }
        if ("fit".equals(cmd)) {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : 24;
            long seed = args.length > 2 ? Long.parseLong(args[2]) : freshSeed();
            fitModel(count, seed);
            return;
        }
        if ("generate".equals(cmd) || looksLikeCount(cmd)) {
            int count = "generate".equals(cmd)
                    ? (args.length > 1 ? Integer.parseInt(args[1]) : TEST_COUNT)
                    : Integer.parseInt(cmd);
            Path root = Path.of(args.length > ("generate".equals(cmd) ? 2 : 1)
                    ? args["generate".equals(cmd) ? 2 : 1]
                    : DEFAULT_TESTS.toString());
            long seed = args.length > ("generate".equals(cmd) ? 3 : 2)
                    ? Long.parseLong(args["generate".equals(cmd) ? 3 : 2])
                    : freshSeed();
            generate(root, count, seed);
            return;
        }
        int count = args.length > 1 ? Integer.parseInt(args[1]) : TEST_COUNT;
        Path root = Path.of(args.length > 2 ? args[2] : DEFAULT_EXAM.toString());
        long seed = args.length > 3 ? Long.parseLong(args[3]) : freshSeed();
        boolean passed = exam(root, count, seed);
        if (!passed) {
            System.exit(1);
        }
    }

    private static boolean looksLikeCount(String cmd) {
        if (cmd.isEmpty()) {
            return false;
        }
        for (int i = 0; i < cmd.length(); i++) {
            if (!Character.isDigit(cmd.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Новый seed на каждый запуск: время + наносекунды. */
    static long freshSeed() {
        long nano = System.nanoTime();
        long millis = System.currentTimeMillis();
        return nano ^ (millis * 0x9E3779B97F4A7C15L) ^ (nano << 21) ^ (nano >>> 17);
    }

    static void generate(Path root, int count, long seed) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("seed.txt"), Long.toString(seed), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "generate seed=%d count=%d%n", seed, count);

        InstanceFactory factory = new InstanceFactory(seed);
        StringBuilder scores = new StringBuilder();
        scores.append("index\tfamily\tnSites\tnOff\tnWorkers\tobjective\n");

        int nonempty = 0;
        double sumObj = 0.0;
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve(String.format(Locale.ROOT, "t%04d", i));
            Files.createDirectories(dir);
            Path instancePath = dir.resolve("instance.json");
            Path solutionPath = dir.resolve("solution.json");

            DraftWorld world = factory.makeWorld(i);
            String json = factory.toJson(world);
            Files.writeString(instancePath, json, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(json);
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solution solution = new LocalOracle(instance).best();
            Files.writeString(solutionPath, solution.toJson(), StandardCharsets.UTF_8);

            if (!solution.offices.isEmpty()) {
                nonempty++;
            }
            sumObj += solution.objective;
            scores.append(i).append('\t')
                    .append(world.family.name()).append('\t')
                    .append(world.sites.size()).append('\t')
                    .append(world.offices.size()).append('\t')
                    .append(world.workers.size()).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", solution.objective))
                    .append('\n');

            if ((i + 1) % 50 == 0 || i == 0) {
                System.out.printf(Locale.ROOT,
                        "local_tests %d/%d  obj=%.4f  nonempty=%d  %s%n",
                        i + 1, count, solution.objective, nonempty, dir.getFileName());
            }
        }
        Files.writeString(root.resolve("scores.tsv"), scores.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "done: %d tests, nonempty=%d, meanObj=%.4f, root=%s%n",
                count, nonempty, sumObj / Math.max(1, count), root.toAbsolutePath());
    }

    /**
     * Жёсткие карты: nOff 8–15, nSites 45–160, пересекающиеся оболочки, leftover.
     * Оракул-перебор 2^{nOff} не влезает — пишем ответ солвера с лимитом HARD_SOLVE_MS.
     */
    static void generateHard(Path root, int count, long seed) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("seed.txt"), Long.toString(seed), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "hard seed=%d count=%d%n", seed, count);
        InstanceFactory factory = new InstanceFactory(seed, true);
        StringBuilder scores = new StringBuilder();
        scores.append("index\tscenario\tnSites\tnOff\tobj\tleftoverFrac\toverlapBeforeRepair\tcheapThoroughDiffer\n");
        double sum = 0;
        double sumLeftover = 0;
        int sumOverlap = 0;
        int differCount = 0;
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve(String.format(Locale.ROOT, "h%04d", i));
            Files.createDirectories(dir);
            DraftWorld world = factory.makeWorld(i);
            String json = factory.toJson(world);
            Files.writeString(dir.resolve("instance.json"), json, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(json);
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solver solver = new Main.Solver(instance, System.nanoTime() + HARD_SOLVE_MS * 1_000_000L);
            Main.Solution solution = solver.solve();
            Files.writeString(dir.resolve("solution.json"), solution.toJson(), StandardCharsets.UTF_8);
            sum += solution.objective;
            sumLeftover += solver.diagLeftoverFrac;
            sumOverlap += solver.diagOverlapPoints;
            if (solver.diagCheapThoroughDiffer) {
                differCount++;
            }
            scores.append(i).append('\t').append(world.family.name()).append('\t')
                    .append(world.sites.size()).append('\t')
                    .append(world.offices.size()).append('\t')
                    .append(String.format(Locale.ROOT, "%.6f", solution.objective)).append('\t')
                    .append(String.format(Locale.ROOT, "%.6f", solver.diagLeftoverFrac)).append('\t')
                    .append(solver.diagOverlapPoints).append('\t')
                    .append(solver.diagCheapThoroughDiffer ? 1 : 0).append('\n');
            if ((i + 1) % 5 == 0 || i == 0) {
                System.out.printf(Locale.ROOT,
                        "hard %d/%d  nS=%d nO=%d obj=%.2f leftover=%.3f overlap=%d differ=%d%n",
                        i + 1, count, world.sites.size(), world.offices.size(), solution.objective,
                        solver.diagLeftoverFrac, solver.diagOverlapPoints,
                        solver.diagCheapThoroughDiffer ? 1 : 0);
            }
        }
        Files.writeString(root.resolve("scores.tsv"), scores.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "hard done meanObj=%.4f meanLeftover=%.4f overlapSum=%d cheapThoroughDiffer=%d/%d root=%s%n",
                sum / Math.max(1, count), sumLeftover / Math.max(1, count), sumOverlap,
                differCount, count, root.toAbsolutePath());
    }

    /**
     * Оффлайн SGD residual-MLP на жёстких картах. Печатает зашиваемый приор.
     */
    static void fitModel(int count, long seed) throws Exception {
        System.out.printf(Locale.ROOT, "fit count=%d seed=%d%n", count, seed);
        InstanceFactory factory = new InstanceFactory(seed, true);
        List<Main.RankNet.FitSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DraftWorld world = factory.makeWorld(i);
            String json = factory.toJson(world);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(json);
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solver solver = new Main.Solver(instance, System.nanoTime() + 5_000_000_000L);
            List<Main.RankNet.FitSample> part = solver.harvestFit(36);
            samples.addAll(part);
            System.out.printf(Locale.ROOT, "fit %d/%d  nS=%d nO=%d samples=%d%n",
                    i + 1, count, world.sites.size(), world.offices.size(), part.size());
        }
        System.out.printf(Locale.ROOT, "fit total samples=%d%n", samples.size());
        System.out.println(Main.RankNet.trainGlobal(samples, 10));
    }

    /**
     * Автогенерация нового набора (другой seed каждый запуск) и прогон {@link Main.Solver}.
     * Тест считается пройденным, если ответ валиден, цель совпадает с формулой
     * и не хуже лучшего одиночного офиса.
     *
     * @return true, если все карты пройдены
     */
    static boolean exam(Path root, int count, long seed) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("seed.txt"), Long.toString(seed), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "exam seed=%d count=%d root=%s%n", seed, count, root);

        InstanceFactory factory = new InstanceFactory(seed);
        StringBuilder scores = new StringBuilder();
        scores.append("index\tfamily\tnSites\tnOff\tstatus\tobj\tlb\n");
        int passed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve(String.format(Locale.ROOT, "t%04d", i));
            Files.createDirectories(dir);
            DraftWorld world = factory.makeWorld(i);
            String json = factory.toJson(world);
            Files.writeString(dir.resolve("instance.json"), json, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(json);
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solution solution;
            String status;
            double lb = 0.0;
            try {
                lb = singleLowerBound(instance);
                solution = runSolve(instance);
                status = judge(instance, solution, lb);
            } catch (RuntimeException ex) {
                solution = Main.Solution.empty();
                status = "crash: " + ex.getClass().getSimpleName() + " " + ex.getMessage();
            }
            Files.writeString(dir.resolve("solution.json"), solution.toJson(), StandardCharsets.UTF_8);

            boolean ok = "ok".equals(status);
            if (ok) {
                passed++;
            } else {
                failed++;
                String line = String.format(Locale.ROOT, "FAIL t%04d %s obj=%.6f lb=%.6f %s",
                        i, world.family.name(), solution.objective, lb, status);
                failures.add(line);
                System.out.println(line);
                if (failures.size() >= 10) {
                    System.out.println("exam abort: слишком много ошибок");
                    break;
                }
            }
            scores.append(i).append('\t')
                    .append(world.family.name()).append('\t')
                    .append(world.sites.size()).append('\t')
                    .append(world.offices.size()).append('\t')
                    .append(ok ? "PASS" : "FAIL").append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", solution.objective)).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", lb))
                    .append('\n');
            if ((i + 1) % 50 == 0 || i == 0 || !ok) {
                System.out.printf(Locale.ROOT, "exam %d/%d  passed=%d failed=%d  obj=%.4f  %s%n",
                        i + 1, count, passed, failed, solution.objective, status);
            }
        }
        Files.writeString(root.resolve("scores.tsv"), scores.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "exam %s: passed=%d failed=%d seed=%d%n",
                failed == 0 ? "PASS" : "FAIL", passed, failed, seed);
        for (String line : failures) {
            System.out.println(line);
        }
        return failed == 0 && passed == count;
    }

    /**
     * Прогон {@link Main.Solver} по уже записанным local_tests:
     * валидность и формула цели — обязательны; отставание от сохранённого ответа — статистика.
     */
    static boolean check(Path root, int count, long timeMs) throws Exception {
        int passed = 0;
        int failed = 0;
        int worse = 0;
        int better = 0;
        double sumGap = 0.0;
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve(String.format(Locale.ROOT, "t%04d", i));
            Path instancePath = dir.resolve("instance.json");
            Path solutionPath = dir.resolve("solution.json");
            if (!Files.exists(instancePath)) {
                failed++;
                System.out.printf(Locale.ROOT, "FAIL t%04d missing instance%n", i);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(
                    Files.readString(instancePath, StandardCharsets.UTF_8));
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solution stored = Main.Solution.empty();
            if (Files.exists(solutionPath)) {
                stored = Main.Solution.fromJson(instance,
                        Main.Json.parse(Files.readString(solutionPath, StandardCharsets.UTF_8)));
            }
            Main.Solution got;
            String status;
            try {
                Main.Solver solver = new Main.Solver(instance, System.nanoTime() + timeMs * 1_000_000L);
                got = solver.solve();
                status = judge(instance, got, 0.0);
            } catch (RuntimeException ex) {
                got = Main.Solution.empty();
                status = "crash: " + ex.getClass().getSimpleName();
            }
            if ("ok".equals(status)) {
                passed++;
            } else {
                failed++;
                System.out.printf(Locale.ROOT, "FAIL t%04d %s got=%.4f stored=%.4f%n",
                        i, status, got.objective, stored.objective);
                if (failed >= 15) {
                    break;
                }
            }
            if (got.objective + 1e-6 < stored.objective) {
                worse++;
                sumGap += stored.objective - got.objective;
                System.out.printf(Locale.ROOT, "GAP t%04d got=%.6f stored=%.6f delta=%.6f%n",
                        i, got.objective, stored.objective, stored.objective - got.objective);
            } else if (got.objective > stored.objective + 1e-6) {
                better++;
            }
            if ((i + 1) % 25 == 0 || i == 0) {
                System.out.printf(Locale.ROOT,
                        "check %d/%d  pass=%d fail=%d worse=%d better=%d got=%.2f stored=%.2f%n",
                        i + 1, count, passed, failed, worse, better, got.objective, stored.objective);
            }
        }
        System.out.printf(Locale.ROOT,
                "check %s: pass=%d fail=%d worseThanStored=%d better=%d meanGap=%.4f%n",
                failed == 0 ? "PASS" : "FAIL", passed, failed, worse, better,
                worse == 0 ? 0.0 : sumGap / worse);
        return failed == 0;
    }

    static Main.Solution runSolve(Main.Instance inst) {
        Main.Solver solver = new Main.Solver(inst, System.nanoTime() + EXAM_SOLVE_MS * 1_000_000L);
        return solver.solve();
    }

    /** Нижняя оценка: лучшая дешёвая одиночная сборка (или 0, если все убыточны). */
    static double singleLowerBound(Main.Instance inst) {
        Main.Solver solver = new Main.Solver(inst, System.nanoTime() + EXAM_SOLVE_MS * 1_000_000L);
        double lb = 0.0;
        for (int o = 0; o < inst.offices.length && solver.timeLeft(); o++) {
            Main.Solution one = solver.construct(new int[]{o}, false);
            if (one.offices.isEmpty()) {
                continue;
            }
            if (!Main.Constraints.valid(inst, one.offices)) {
                continue;
            }
            lb = Math.max(lb, one.objective);
        }
        return lb;
    }

    static String judge(Main.Instance inst, Main.Solution sol, double lowerBound) {
        if (sol == null) {
            return "null solution";
        }
        if (!sol.offices.isEmpty() && !Main.Constraints.valid(inst, sol.offices)) {
            return "invalid constraints";
        }
        double recomputed = Main.Solution.objectiveOf(inst, sol.offices);
        if (Math.abs(recomputed - sol.objective) > 1e-6) {
            return "objective mismatch";
        }
        if (sol.objective + 1e-6 < lowerBound) {
            return "worse than single office";
        }
        return "ok";
    }

    /**
     * Пересчитывает ответы усиленным солвером, не трогая instance.json.
     * Если новая цель выше сохранённой — перезаписывает solution.json.
     */
    static void train(Path root, int count) throws Exception {
        StringBuilder scores = new StringBuilder();
        scores.append("index\tfamily\tnSites\tnOff\tnWorkers\tobj_old\tobj_new\tdelta\n");
        int improved = 0;
        int equal = 0;
        int worse = 0;
        double sumNew = 0.0;
        double sumDelta = 0.0;
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve(String.format(Locale.ROOT, "t%04d", i));
            Path instancePath = dir.resolve("instance.json");
            Path solutionPath = dir.resolve("solution.json");
            String instText = Files.readString(instancePath, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(instText);
            Main.Instance instance = Main.Instance.fromJson(raw);
            Main.Solution stored = Main.Solution.empty();
            if (Files.exists(solutionPath)) {
                stored = Main.Solution.fromJson(instance,
                        Main.Json.parse(Files.readString(solutionPath, StandardCharsets.UTF_8)));
            }
            Main.Solution neu = new LocalOracle(instance).best();
            Main.Solution keep = neu.objective > stored.objective + 1e-9 ? neu : stored;
            if (keep != stored) {
                Files.writeString(solutionPath, keep.toJson(), StandardCharsets.UTF_8);
                improved++;
            } else if (Math.abs(neu.objective - stored.objective) <= 1e-9) {
                equal++;
            } else {
                worse++;
            }
            double delta = keep.objective - stored.objective;
            sumNew += keep.objective;
            sumDelta += delta;
            scores.append(i).append('\t')
                    .append(familyOf(i)).append('\t')
                    .append(instance.sites.length).append('\t')
                    .append(instance.offices.length).append('\t')
                    .append(instance.workers.length).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", stored.objective)).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", keep.objective)).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", delta))
                    .append('\n');
            if ((i + 1) % 50 == 0 || i == 0) {
                System.out.printf(Locale.ROOT,
                        "train %d/%d  old=%.4f new=%.4f keep=%.4f  improved=%d%n",
                        i + 1, count, stored.objective, neu.objective, keep.objective, improved);
            }
        }
        Files.writeString(root.resolve("scores.tsv"), scores.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "train done: improved=%d equal=%d worseKeptOld=%d mean=%.4f sumDelta=%.4f%n",
                improved, equal, worse, sumNew / Math.max(1, count), sumDelta);
    }

    static String familyOf(int index) {
        Family[] all = Family.values();
        return all[index % all.length].name();
    }

    /**
     * Перебор всех непустых наборов офисов (nOff ≤ 6 → до 63 наборов).
     * Для каждого — cheap и thorough construct, оставляем максимум целевой.
     */
    static final class LocalOracle {
        private final Main.Instance inst;
        private final Main.Solver solver;

        LocalOracle(Main.Instance inst) {
            this.inst = inst;
            this.solver = new Main.Solver(inst, System.nanoTime() + 180_000_000_000L);
        }

        Main.Solution best() {
            Main.Solution best = Main.Solution.empty();
            int nOff = inst.offices.length;
            if (nOff == 0 || inst.sites.length == 0) {
                return best;
            }
            int limit = 1 << nOff;
            for (int mask = 1; mask < limit; mask++) {
                int k = Integer.bitCount(mask);
                if (k > inst.workers.length) {
                    continue;
                }
                int[] ids = new int[k];
                int p = 0;
                for (int o = 0; o < nOff; o++) {
                    if ((mask & (1 << o)) != 0) {
                        ids[p++] = o;
                    }
                }
                best = keep(best, solver.construct(ids, false));
                best = keep(best, solver.construct(ids, true));
            }
            return best;
        }

        private Main.Solution keep(Main.Solution cur, Main.Solution cand) {
            if (cand == null || cand.offices.isEmpty()) {
                return cur;
            }
            if (cand.objective <= cur.objective + 1e-12) {
                return cur;
            }
            if (!Main.Constraints.valid(inst, cand.offices)) {
                return cur;
            }
            return cand;
        }
    }

    /** Семейства карт: α, навыки, геометрия, ёмкости. */
    enum Family {
        BALANCED,
        HIGH_ALPHA,
        ZERO_ALPHA,
        SCARCE_SKILL,
        MISSING_SKILL,
        TIGHT_CAP,
        WIDE_BALANCE,
        FAR_OFFICES,
        SITE_CLUSTERS,
        COSTLY_OPEN
    }

    static final class Vec2 {
        final double x;
        final double y;

        Vec2(double x, double y) {
            this.x = x;
            this.y = y;
        }

        double dist(Vec2 o) {
            return Math.hypot(x - o.x, y - o.y);
        }
    }

    static final class DraftSite {
        final Vec2 pos;
        final double reward;
        final int[] tasks;

        DraftSite(Vec2 pos, double reward, int[] tasks) {
            this.pos = pos;
            this.reward = reward;
            this.tasks = tasks;
        }
    }

    static final class DraftOffice {
        final Vec2 pos;
        final double cost;
        final int maxSize;

        DraftOffice(Vec2 pos, double cost, int maxSize) {
            this.pos = pos;
            this.cost = cost;
            this.maxSize = maxSize;
        }
    }

    static final class DraftWorld {
        final Family family;
        final List<DraftSite> sites = new ArrayList<>();
        final List<DraftOffice> offices = new ArrayList<>();
        final List<int[]> workers = new ArrayList<>();
        final double[][] distances;
        final int maxDifference;
        final double alpha;

        DraftWorld(Family family, int nOff, int nSites, int maxDifference, double alpha) {
            this.family = family;
            this.distances = new double[nOff][nSites];
            this.maxDifference = maxDifference;
            this.alpha = alpha;
        }
    }

    /** Генератор инстансов: детерминированный seed от номера теста. */
    static final class InstanceFactory {
        private final long salt;
        private final boolean hard;

        InstanceFactory(long salt) {
            this(salt, false);
        }

        InstanceFactory(long salt, boolean hard) {
            this.salt = salt;
            this.hard = hard;
        }

        DraftWorld makeWorld(int index) {
            Rng rng = new Rng(salt ^ (index * 0x9E3779B97F4A7C15L) ^ (index + 1L) * 0xD1B54A32D192ED03L);
            if (hard) {
                return buildHard(rng, index);
            }
            Family family = Family.values()[index % Family.values().length];
            return build(rng, family, index);
        }

        String toJson(DraftWorld w) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"sites\": [\n");
            for (int i = 0; i < w.sites.size(); i++) {
                DraftSite s = w.sites.get(i);
                sb.append("    {\"x\": ").append(num(s.pos.x))
                        .append(", \"y\": ").append(num(s.pos.y))
                        .append(", \"reward\": ").append(num(s.reward))
                        .append(", \"tasks\": ").append(ints(s.tasks)).append("}");
                sb.append(i + 1 < w.sites.size() ? ",\n" : "\n");
            }
            sb.append("  ],\n  \"offices\": [\n");
            for (int i = 0; i < w.offices.size(); i++) {
                DraftOffice o = w.offices.get(i);
                sb.append("    {\"x\": ").append(num(o.pos.x))
                        .append(", \"y\": ").append(num(o.pos.y))
                        .append(", \"cost\": ").append(num(o.cost))
                        .append(", \"maxSize\": ").append(o.maxSize).append("}");
                sb.append(i + 1 < w.offices.size() ? ",\n" : "\n");
            }
            sb.append("  ],\n  \"workers\": [\n");
            for (int i = 0; i < w.workers.size(); i++) {
                sb.append("    ").append(ints(w.workers.get(i)));
                sb.append(i + 1 < w.workers.size() ? ",\n" : "\n");
            }
            sb.append("  ],\n  \"distances\": [\n");
            for (int o = 0; o < w.offices.size(); o++) {
                sb.append("    [");
                for (int s = 0; s < w.sites.size(); s++) {
                    if (s > 0) {
                        sb.append(", ");
                    }
                    sb.append(num(w.distances[o][s]));
                }
                sb.append(o + 1 < w.offices.size() ? "],\n" : "]\n");
            }
            sb.append("  ],\n  \"maxDifference\": ").append(w.maxDifference)
                    .append(",\n  \"balanceCoefficient\": ").append(num(w.alpha))
                    .append("\n}\n");
            return sb.toString();
        }

        private DraftWorld build(Rng rng, Family family, int index) {
            SizeTier tier = SizeTier.of(index);
            int nSites = tier.minSites + rng.nextInt(tier.spanSites);
            int nOff = tier.minOff + rng.nextInt(tier.spanOff);
            int nSkills = 2 + rng.nextInt(3);
            if (family == Family.MISSING_SKILL) {
                nSkills = Math.max(3, nSkills);
            }

            double box = 8.0 + rng.nextDouble() * 12.0;
            double meanReward = 12.0 + rng.nextDouble() * 18.0;
            double alpha = switch (family) {
                case HIGH_ALPHA -> meanReward * (4.0 + rng.nextDouble() * 10.0);
                case ZERO_ALPHA -> 0.0;
                default -> rng.nextDouble() * meanReward * 0.25;
            };
            int maxDifference = switch (family) {
                case TIGHT_CAP -> 1 + rng.nextInt(2);
                case WIDE_BALANCE -> nSites;
                default -> Math.max(2, nSites / 4 + rng.nextInt(Math.max(2, nSites / 5)));
            };

            DraftWorld world = new DraftWorld(family, nOff, nSites, maxDifference, alpha);

            List<Vec2> hubs = new ArrayList<>();
            int hubCount = family == Family.SITE_CLUSTERS
                    ? Math.min(nOff, 2 + rng.nextInt(2))
                    : 1 + (rng.nextInt(3) == 0 ? 1 : 0);
            placeHubs(hubs, hubCount, box, rng);

            BitSet usedSkills = new BitSet();
            for (int s = 0; s < nSites; s++) {
                Vec2 hub = hubs.get(rng.nextInt(hubs.size()));
                double spread = family == Family.SITE_CLUSTERS ? box * 0.12 : box * 0.35;
                Vec2 pos = new Vec2(
                        clamp(hub.x + (rng.nextDouble() - 0.5) * spread, 0, box),
                        clamp(hub.y + (rng.nextDouble() - 0.5) * spread, 0, box));
                int taskCount = 1 + rng.nextInt(2);
                int[] tasks = new int[taskCount];
                for (int t = 0; t < taskCount; t++) {
                    int skill = rng.nextInt(nSkills);
                    if (family == Family.MISSING_SKILL && skill == 0) {
                        skill = 1 + rng.nextInt(nSkills - 1);
                    }
                    tasks[t] = skill;
                    usedSkills.set(skill);
                }
                double reward = meanReward * (0.75 + rng.nextDouble() * 0.7);
                if (rng.nextInt(11) == 0) {
                    reward *= 1.6;
                }
                world.sites.add(new DraftSite(pos, reward, tasks));
            }
            // Каждый реально встретившийся навык должен быть у какого-то объекта.
            if (usedSkills.cardinality() == 0) {
                usedSkills.set(family == Family.MISSING_SKILL ? 1 : 0);
            }

            int[] caps = new int[nOff];
            int capSum = 0;
            for (int o = 0; o < nOff; o++) {
                int cap = switch (family) {
                    case TIGHT_CAP -> 3 + rng.nextInt(3);
                    default -> Math.max(4, (nSites + nOff - 1) / nOff + 2 + rng.nextInt(4));
                };
                caps[o] = cap;
                capSum += cap;
            }
            int minCap = Math.max(nSites / 2, nOff);
            int extra = 0;
            while (capSum < minCap) {
                caps[extra % nOff]++;
                capSum++;
                extra++;
            }

            for (int o = 0; o < nOff; o++) {
                Vec2 pos = officePosition(family, world.sites, hubs, box, o, rng);
                double expectedCatch = meanReward * Math.min(caps[o], Math.max(3, nSites / Math.max(1, nOff)));
                double costShare = switch (family) {
                    case COSTLY_OPEN -> 0.28 + rng.nextDouble() * 0.18;
                    case HIGH_ALPHA -> 0.06 + rng.nextDouble() * 0.08;
                    default -> 0.08 + rng.nextDouble() * 0.12;
                };
                double cost = Math.max(0.5, expectedCatch * costShare);
                world.offices.add(new DraftOffice(pos, cost, caps[o]));
            }

            int nWorkers = Math.max(nOff, nSkills + 1 + rng.nextInt(nOff + 2));
            nWorkers = Math.min(nWorkers, nOff + nSkills + 6);
            fillWorkers(world, rng, family, nSkills, nWorkers, usedSkills);

            for (int o = 0; o < nOff; o++) {
                Vec2 op = world.offices.get(o).pos;
                for (int s = 0; s < nSites; s++) {
                    double euclid = op.dist(world.sites.get(s).pos);
                    double detour = 1.05 + rng.nextDouble() * 0.28;
                    world.distances[o][s] = Math.max(1e-6, euclid * detour);
                }
            }
            return world;
        }

        /**
         * Скрытые тесты бьют leftover и пересечение оболочек, а не «открой 2 офиса
         * в 20 точках». Здесь офисы в центре облака, кольца сайтов и далёкие кластеры.
         */
        private DraftWorld buildHard(Rng rng, int index) {
            int scenario = index % 6;
            Family family = switch (scenario) {
                case 0 -> Family.SITE_CLUSTERS;
                case 1 -> Family.HIGH_ALPHA;
                case 2 -> Family.FAR_OFFICES;
                case 3 -> Family.SCARCE_SKILL;
                case 4 -> Family.TIGHT_CAP;
                default -> Family.COSTLY_OPEN;
            };
            boolean wide = index % 5 == 0;
            int nSites = wide ? 90 + rng.nextInt(70) : 48 + rng.nextInt(42);
            int nOff = wide ? 10 + rng.nextInt(6) : 8 + rng.nextInt(5);
            int nSkills = 3 + rng.nextInt(3);
            double box = 20.0 + rng.nextDouble() * 30.0;
            double meanReward = 18.0 + rng.nextDouble() * 40.0;
            double alpha = switch (family) {
                case HIGH_ALPHA -> meanReward * (6.0 + rng.nextDouble() * 12.0);
                default -> meanReward * (0.2 + rng.nextDouble() * 1.8);
            };
            int maxDifference = family == Family.TIGHT_CAP
                    ? 1 + rng.nextInt(2)
                    : Math.max(2, nSites / (3 * nOff) + rng.nextInt(4));
            DraftWorld world = new DraftWorld(family, nOff, nSites, maxDifference, alpha);

            List<Vec2> hubs = new ArrayList<>();
            if (scenario == 2) {
                placeHubs(hubs, Math.min(nOff, 3 + rng.nextInt(2)), box, rng);
            } else {
                hubs.add(new Vec2(box * 0.5, box * 0.5));
            }

            BitSet usedSkills = new BitSet();
            for (int s = 0; s < nSites; s++) {
                Vec2 pos;
                if (scenario == 1) {
                    @SuppressWarnings("unused")
                    int ring = s % Math.max(2, nOff);
                    double ang = (2.0 * Math.PI * s) / nSites + rng.nextDouble() * 0.2;
                    double rad = box * (0.12 + 0.08 * (s % 3));
                    Vec2 hub = hubs.get(0);
                    pos = new Vec2(hub.x + Math.cos(ang) * rad, hub.y + Math.sin(ang) * rad);
                } else if (scenario == 2) {
                    Vec2 hub = hubs.get(s % hubs.size());
                    pos = new Vec2(
                            clamp(hub.x + (rng.nextDouble() - 0.5) * box * 0.18, 0, box),
                            clamp(hub.y + (rng.nextDouble() - 0.5) * box * 0.18, 0, box));
                } else {
                    Vec2 hub = hubs.get(0);
                    double spread = scenario == 0 ? box * 0.22 : box * 0.45;
                    pos = new Vec2(
                            clamp(hub.x + (rng.nextDouble() - 0.5) * spread, 0, box),
                            clamp(hub.y + (rng.nextDouble() - 0.5) * spread, 0, box));
                }
                int taskCount = 1 + rng.nextInt(2);
                int[] tasks = new int[taskCount];
                for (int t = 0; t < taskCount; t++) {
                    int skill = rng.nextInt(nSkills);
                    if (family == Family.SCARCE_SKILL && rng.nextInt(7) == 0) {
                        skill = nSkills - 1;
                    }
                    tasks[t] = skill;
                    usedSkills.set(skill);
                }
                double reward = meanReward * (0.7 + rng.nextDouble() * 0.9);
                if (rng.nextInt(9) == 0) {
                    reward *= 2.2;
                }
                world.sites.add(new DraftSite(pos, reward, tasks));
            }
            if (usedSkills.cardinality() == 0) {
                usedSkills.set(0);
            }

            int[] caps = new int[nOff];
            int capSum = 0;
            for (int o = 0; o < nOff; o++) {
                caps[o] = family == Family.TIGHT_CAP
                        ? 4 + rng.nextInt(4)
                        : Math.max(6, nSites / nOff + 3 + rng.nextInt(6));
                capSum += caps[o];
            }
            while (capSum < nSites) {
                caps[capSum % nOff]++;
                capSum++;
            }

            Vec2 centroid = new Vec2(0, 0);
            for (DraftSite s : world.sites) {
                centroid = new Vec2(centroid.x + s.pos.x, centroid.y + s.pos.y);
            }
            centroid = new Vec2(centroid.x / nSites, centroid.y / nSites);

            for (int o = 0; o < nOff; o++) {
                Vec2 pos;
                if (scenario == 0) {
                    pos = new Vec2(
                            centroid.x + (rng.nextDouble() - 0.5) * box * 0.08,
                            centroid.y + (rng.nextDouble() - 0.5) * box * 0.08);
                } else if (scenario == 2) {
                    Vec2 hub = hubs.get(o % hubs.size());
                    if (o >= hubs.size() && rng.nextInt(3) == 0) {
                        hub = hubs.get(rng.nextInt(hubs.size()));
                    }
                    pos = new Vec2(
                            hub.x + (rng.nextDouble() - 0.5) * box * 0.1,
                            hub.y + (rng.nextDouble() - 0.5) * box * 0.1);
                } else {
                    DraftSite near = world.sites.get(rng.nextInt(nSites));
                    pos = new Vec2(
                            near.pos.x + (rng.nextDouble() - 0.5) * 2.5,
                            near.pos.y + (rng.nextDouble() - 0.5) * 2.5);
                }
                double expected = meanReward * Math.min(caps[o], nSites / (double) nOff);
                double costShare = family == Family.COSTLY_OPEN ? 0.22 + rng.nextDouble() * 0.15
                        : 0.07 + rng.nextDouble() * 0.1;
                world.offices.add(new DraftOffice(pos, Math.max(1.0, expected * costShare), caps[o]));
            }

            int nWorkers = Math.max(nOff + 2, nSkills + nOff);
            fillWorkers(world, rng, family, nSkills, nWorkers, usedSkills);

            for (int o = 0; o < nOff; o++) {
                Vec2 op = world.offices.get(o).pos;
                for (int s = 0; s < nSites; s++) {
                    double euclid = op.dist(world.sites.get(s).pos);
                    world.distances[o][s] = Math.max(1e-6, euclid * (1.08 + rng.nextDouble() * 0.55));
                }
            }
            return world;
        }

        private static void placeHubs(List<Vec2> hubs, int hubCount, double box, Rng rng) {
            for (int guard = 0; guard < 40 && hubs.size() < hubCount; guard++) {
                Vec2 cand = new Vec2(1.0 + rng.nextDouble() * (box - 2.0),
                        1.0 + rng.nextDouble() * (box - 2.0));
                boolean far = true;
                for (Vec2 h : hubs) {
                    if (h.dist(cand) < box * 0.35) {
                        far = false;
                        break;
                    }
                }
                if (far || hubs.isEmpty()) {
                    hubs.add(cand);
                }
            }
            while (hubs.size() < hubCount) {
                hubs.add(new Vec2(rng.nextDouble() * box, rng.nextDouble() * box));
            }
        }

        private static Vec2 officePosition(Family family, List<DraftSite> sites,
                                           List<Vec2> hubs, double box, int officeIndex, Rng rng) {
            if (family == Family.FAR_OFFICES && officeIndex > 0 && rng.nextInt(3) != 0) {
                return new Vec2(-0.4 * box - rng.nextDouble() * 0.6 * box,
                        -0.4 * box - rng.nextDouble() * 0.6 * box);
            }
            if (family == Family.SITE_CLUSTERS) {
                Vec2 hub = hubs.get(officeIndex % hubs.size());
                return new Vec2(
                        clamp(hub.x + (rng.nextDouble() - 0.5) * box * 0.08, -1, box + 1),
                        clamp(hub.y + (rng.nextDouble() - 0.5) * box * 0.08, -1, box + 1));
            }
            DraftSite near = sites.get(rng.nextInt(sites.size()));
            return new Vec2(
                    clamp(near.pos.x + (rng.nextDouble() - 0.5) * 1.8, -0.5, box + 0.5),
                    clamp(near.pos.y + (rng.nextDouble() - 0.5) * 1.8, -0.5, box + 0.5));
        }

        /**
         * Сначала закрываем все навыки объектов, затем добиваем пул работников.
         */
        private static void fillWorkers(DraftWorld world, Rng rng, Family family,
                                        int nSkills, int nWorkers, BitSet usedSkills) {
            List<Integer> must = new ArrayList<>();
            for (int s = usedSkills.nextSetBit(0); s >= 0; s = usedSkills.nextSetBit(s + 1)) {
                must.add(s);
            }
            int rare = nSkills - 1;
            boolean scarce = family == Family.SCARCE_SKILL && usedSkills.get(rare);

            for (int w = 0; w < nWorkers; w++) {
                int primary;
                if (w < must.size()) {
                    primary = must.get(w);
                } else if (scarce && w > 0) {
                    primary = rng.nextInt(Math.max(1, nSkills - 1));
                    if (family == Family.MISSING_SKILL && primary == 0) {
                        primary = 1;
                    }
                } else {
                    primary = must.get(w % must.size());
                }
                if (family == Family.MISSING_SKILL && primary == 0) {
                    primary = must.get(0);
                }
                boolean extra = rng.nextInt(4) == 0 && nSkills > 1 && !(scarce && primary == rare);
                int second = rng.nextInt(nSkills);
                if (family == Family.MISSING_SKILL && second == 0) {
                    second = primary;
                }
                if (scarce && w > 0 && second == rare) {
                    second = primary;
                }
                int[] skills = extra && second != primary
                        ? new int[]{primary, second}
                        : new int[]{primary};
                world.workers.add(skills);
            }
            if (scarce) {
                boolean hasRare = false;
                for (int[] sk : world.workers) {
                    for (int v : sk) {
                        if (v == rare) {
                            hasRare = true;
                            break;
                        }
                    }
                }
                if (!hasRare) {
                    world.workers.set(0, new int[]{rare});
                }
            }
        }

        private static double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private static String num(double v) {
            return String.format(Locale.ROOT, "%.12f", v);
        }

        private static String ints(int[] a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(a[i]);
            }
            return sb.append(']').toString();
        }
    }

    /** Масштаб карты: мелкие / средние / чуть крупнее, всегда nOff ≤ 6. */
    enum SizeTier {
        SMALL(8, 8, 2, 2),
        MEDIUM(14, 12, 3, 3),
        LARGE(22, 14, 4, 3);

        final int minSites;
        final int spanSites;
        final int minOff;
        final int spanOff;

        SizeTier(int minSites, int spanSites, int minOff, int spanOff) {
            this.minSites = minSites;
            this.spanSites = spanSites;
            this.minOff = minOff;
            this.spanOff = spanOff;
        }

        static SizeTier of(int index) {
            int m = index % 7;
            if (m == 0) {
                return SMALL;
            }
            if (m == 1 || m == 2) {
                return LARGE;
            }
            return MEDIUM;
        }
    }

    /** SplitMix64, свой ГПСЧ, не из Main. */
    static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        }

        long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int nextInt(int n) {
            if (n <= 1) {
                return 0;
            }
            return (int) ((nextLong() & Long.MAX_VALUE) % n);
        }

        double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }
    }
}
