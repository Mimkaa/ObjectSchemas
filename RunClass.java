import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunClass {

    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              java RunClass --class <ClassName>
              java RunClass --classB64 <base64-utf8-ClassName>

            Optional:
              --args "<space separated args>"
              --argsB64 <base64-utf8-args>

              --cp "<classpath>"
              --cpB64 <base64-utf8-classpath>

            Notes:
              - If --cp/--cpB64 is provided, the child JVM is started with: java -cp <cp> <ClassName> ...
              - Exit code of RunClass equals the child JVM exit code.
            """);
    }

    public static void main(String[] args) {
        String className = null;
        String argsText = null;     // optional extra args for main()
        String classpath = null;    // optional classpath to forward to child JVM

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                // -------- class name (plain + B64)
                case "--class" -> {
                    if (i + 1 < args.length) className = args[++i];
                }
                case "--classB64" -> {
                    if (i + 1 < args.length) className = decodeB64Utf8(args[++i]);
                }

                // -------- optional args passed to child main(String[] args)
                case "--args" -> {
                    if (i + 1 < args.length) argsText = args[++i];
                }
                case "--argsB64" -> {
                    if (i + 1 < args.length) argsText = decodeB64Utf8(args[++i]);
                }

                // -------- optional classpath forwarded to child JVM
                case "--cp" -> {
                    if (i + 1 < args.length) classpath = args[++i];
                }
                case "--cpB64" -> {
                    if (i + 1 < args.length) classpath = decodeB64Utf8(args[++i]);
                }

                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (className == null || className.isBlank()) {
            printUsage();
            System.exit(2);
            return;
        }

        try {
            // Build child command
            List<String> cmd = new ArrayList<>();
            cmd.add("java");

            if (classpath != null && !classpath.isBlank()) {
                cmd.add("-cp");
                cmd.add(classpath);
            }

            cmd.add(className);

            if (argsText != null && !argsText.isBlank()) {
                // split on whitespace (simple & deterministic)
                cmd.addAll(Arrays.asList(argsText.trim().split("\\s+")));
            }

            // Print child command for debugging
            System.out.println("CHILD CMD: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true); // merge stderr into stdout

            Process p = pb.start();

            // Stream output live
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = p.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ Class executed successfully.");
            } else {
                System.out.println("❌ Execution failed with code: " + exitCode);
            }

            // CRITICAL: propagate child exit code so pipeline fails properly
            System.exit(exitCode);

        } catch (IOException e) {
            System.err.println("❌ IO error running class: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);

        } catch (InterruptedException e) {
            System.err.println("❌ Interrupted while waiting for class: " + e.getMessage());
            e.printStackTrace();
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }
}
