import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Jsp Neo5 - single-file compiler.
 *
 * This compiler itself has no GUI dependency (no Swing, no AWT, no WebView).
 * It only produces HTML. A desktop shell, if needed, is built by the user's
 * own Java program, which is free to use Swing.
 *
 * Commands:
 *   jspneo5 -initialize   Install JSweet into lib/ once.
 *   jspneo5 build         Single build pipeline: .jsn5 -> JSweet -> dist/web/*.html
 *   jspneo5               Print usage.
 *
 * build-config (in jspneo5.toml):
 *   web       Produce HTML.
 *   desktop   Produce the same HTML. No shell is generated; the user's own
 *             Java program loads dist/web/index.html.
 */
public class Jspneo5 {

    static final String INIT_MARKER = ".jspneo5-initialized";

    // 0. Initialization
    static boolean isInitialized() {
        return Files.exists(Paths.get(INIT_MARKER));
    }

    static void initialize() {
        boolean jsweetOk = false;
        try {
            Files.createDirectories(Paths.get("lib"));

            String jsweetUrl = "https://repo1.maven.org/maven2/org/jsweet/"
                    + "jsweet-transpiler/2.3.7/"
                    + "jsweet-transpiler-2.3.7-jar-with-dependencies.jar";
            Path jsweetJar = Paths.get(
                    "lib", "jsweet-transpiler-2.3.7-jar-with-dependencies.jar");
            jsweetOk = downloadIfMissing(jsweetUrl, jsweetJar, "JSweet transpiler");

            if (jsweetOk) {
                Files.writeString(Paths.get(INIT_MARKER),
                        "Initialized at " + java.time.Instant.now() + "\n");
                System.out.println();
                System.out.println("[jspneo5] Initialization complete. "
                        + "Marker written: " + INIT_MARKER);
                System.out.println("[jspneo5] You can now build normally:");
                System.out.println("[jspneo5]     jspneo5 build");
            } else {
                System.out.println();
                System.out.println("[jspneo5] Initialization FAILED "
                        + "(JSweet download unsuccessful).");
                System.out.println("[jspneo5] Please retry: jspneo5 -initialize");
            }
        } catch (Exception e) {
            System.err.println("[jspneo5] Initialization error: " + e.getMessage());
        }
    }

    static boolean downloadIfMissing(String url, Path dest, String label) {
        if (Files.exists(dest)) {
            System.out.println("[jspneo5] " + label + " already present, skipped.");
            return true;
        }
        System.out.println("[jspneo5] Downloading " + label + " ...");
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[jspneo5]   -> " + dest);
            return true;
        } catch (Exception e) {
            System.err.println("[jspneo5]   failed: " + e.getMessage());
            try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            return false;
        }
    }

    // 1. Configuration model
    public static class BuildConfig {
        public String buildConfig;
        public String sourceDir = "src";
        public String outputDir = "dist";

        static BuildConfig load(Path toml) throws IOException {
            BuildConfig c = new BuildConfig();
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim().replace("\"", "");
                switch (key) {
                    case "build-config" -> c.buildConfig = val;
                    case "source-dir"   -> c.sourceDir = val;
                    case "output-dir"   -> c.outputDir = val;
                }
            }
            if (c.buildConfig == null)
                throw new IllegalArgumentException("jspneo5.toml is missing build-config");
            return c;
        }
    }

    // 2. Entry point
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "-initialize", "init" -> initialize();
            case "build" -> build();
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("Usage:");
        System.out.println("  jspneo5 -initialize    Install JSweet into lib/ (once)");
        System.out.println("  jspneo5 build          Compile .jsn5 -> dist/web/*.html");
    }

    static void build() throws Exception {
        if (!isInitialized()) {
            System.out.println();
            System.out.println("[jspneo5] Not initialized. Run the following command once:");
            System.out.println("[jspneo5]     jspneo5 -initialize");
            System.out.println("[jspneo5] After that you can build normally.");
            System.out.println();
            System.exit(1);
        }

        Path toml = Paths.get("jspneo5.toml");
        if (!Files.exists(toml)) {
            System.err.println("[jspneo5] jspneo5.toml not found.");
            System.exit(1);
        }

        BuildConfig cfg = BuildConfig.load(toml);
        List<Path> files = scan(cfg.sourceDir);
        if (files.isEmpty()) {
            System.err.println("[jspneo5] No .jsn5 files found in: " + cfg.sourceDir);
            System.exit(1);
        }

        Builder.build(cfg, files);

        if ("desktop".equals(cfg.buildConfig)) {
            Path out = Paths.get(cfg.outputDir, "web").toAbsolutePath();
            System.out.println("[jspneo5] build-config = \"desktop\"");
            System.out.println("[jspneo5] HTML written to: " + out);
            System.out.println("[jspneo5] This compiler does not bundle a desktop shell.");
            System.out.println("[jspneo5] Load dist/web/index.html from your own Java");
            System.out.println("[jspneo5] desktop program (Swing is allowed there).");
        } else if (!"web".equals(cfg.buildConfig)) {
            System.err.println("[jspneo5] Unknown build-config: " + cfg.buildConfig);
            System.exit(1);
        }
    }

    static List<Path> scan(String dir) throws IOException {
        try (var s = Files.walk(Paths.get(dir))) {
            return s.filter(p -> p.toString().endsWith(".jsn5"))
                    .collect(Collectors.toList());
        }
    }

    // 3. Single build pipeline
    static class Builder {
        static void build(BuildConfig cfg, List<Path> files) throws Exception {
            Path out = Paths.get(cfg.outputDir, "web");
            Files.createDirectories(out);
            Path tmpJava = Files.createTempDirectory("jspneo5-java-");

            for (Path f : files) {
                Jsn5 j = Jsn5.parse(f);
                String base = f.getFileName().toString().replace(".jsn5", "");

                if (!j.javaCode.isBlank()) {
                    Path jf = tmpJava.resolve(capitalize(base) + ".java");
                    Files.writeString(jf,
                            "public class " + capitalize(base) + " {\n"
                                    + j.javaCode + "\n}");
                }

                runJsweet(tmpJava);

                String body = j.html.isBlank() ? "<div id=\"app\"></div>" : j.html;
                String full = "<!DOCTYPE html>\n"
                        + "<html><head><meta charset=\"UTF-8\">\n"
                        + "<link rel=\"stylesheet\" href=\"style.css\">\n"
                        + "</head><body>\n"
                        + body + "\n"
                        + "<script src=\"" + base + ".js\"></script>\n"
                        + "</body></html>";
                Files.writeString(out.resolve(base + ".html"), full);
            }
            System.out.println("[jspneo5] HTML output: " + out.toAbsolutePath());
        }

        static void runJsweet(Path javaDir) throws Exception {
            Path lib = Paths.get("lib");
            if (!Files.isDirectory(lib)) return;
            Path jsweetJar;
            try (var s = Files.list(lib)) {
                jsweetJar = s.filter(p -> p.getFileName().toString()
                                .startsWith("jsweet-transpiler")
                                && p.toString().endsWith(".jar"))
                        .findFirst().orElse(null);
            }
            if (jsweetJar == null) return;

            String mainClass = "org.jsweet.JSweetCommandLineLauncher";
            String cp = jsweetJar.toAbsolutePath() + File.pathSeparator
                    + javaDir.toAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-cp", cp, mainClass,
                    javaDir.toString(), "--outDir", javaDir.toString());
            pb.inheritIO();
            if (pb.start().waitFor() == 0) {
                System.out.println("[jspneo5] JSweet done.");
            } else {
                System.out.println("[jspneo5] JSweet failed, continuing without JS output.");
            }
        }
    }

    static class Jsn5 {
        final String javaCode;
        final String html;
        Jsn5(String j, String h) { javaCode = j; html = h; }

        static Jsn5 parse(Path f) throws IOException {
            String src = Files.readString(f);
            int s = src.indexOf("{{ html }}");
            int e = src.indexOf("{{ /html }}");
            if (s < 0 || e < 0 || e < s) return new Jsn5(src, "");
            return new Jsn5(src.substring(0, s).trim(),
                    src.substring(s + 10, e).trim());
        }
    }

    // 4. Utilities
    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}