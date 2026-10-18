import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public class Inspect {
    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of("data/hard_tests");
        double sumGot = 0;
        double sumStored = 0;
        int better = 0, worse = 0, equal = 0;

        java.util.List<Path> dirs = new java.util.ArrayList<>();
        if (args.length > 0 && !args[0].contains("hard_tests")) {
            for (int i = 0; i < 5; i++) {
                dirs.add(Path.of(args[0], "instance" + i));
            }
        } else {
            for (int i = 0; i < 12; i++) {
                dirs.add(root.resolve(String.format(Locale.ROOT, "h%04d", i)));
            }
        }

        for (Path dir : dirs) {
            Path instPath = dir.resolve("instance.json");
            Path solPath = dir.resolve("solution.json");

            String text = Files.readString(instPath, StandardCharsets.UTF_8);
            Map<String, Object> raw = (Map<String, Object>) Main.Json.parse(text);
            Main.Instance inst = Main.Instance.fromJson(raw);

            double storedObj = 0;
            if (Files.exists(solPath)) {
                String solText = Files.readString(solPath, StandardCharsets.UTF_8);
                Main.Solution storedSol = Main.Solution.fromJson(inst, Main.Json.parse(solText));
                storedObj = storedSol.objective;
            }

            long t0 = System.currentTimeMillis();
            Main.Solver solver = new Main.Solver(inst, System.nanoTime() + 8_000_000_000L);
            Main.Solution got = solver.solve();
            long elapsed = System.currentTimeMillis() - t0;

            sumGot += got.objective;
            sumStored += storedObj;
            double diff = got.objective - storedObj;

            if (diff > 1e-6) better++;
            else if (diff < -1e-6) worse++;
            else equal++;

            System.out.printf(Locale.ROOT, "%s: nS=%d, nO=%d | in %d ms | got=%.2f stored=%.2f delta=%+.2f (valid=%b)%n",
                dir.getFileName(), inst.sites.length, inst.offices.length, elapsed, got.objective, storedObj, diff, Main.Constraints.valid(inst, got.offices));
        }

        System.out.printf(Locale.ROOT, "TOTAL: sumGot=%.2f, sumStored=%.2f | better=%d, worse=%d, equal=%d%n",
            sumGot, sumStored, better, worse, equal);
    }
}
