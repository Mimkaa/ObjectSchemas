import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

public class RunClass {

    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    public static void main(String[] args) {
        String className = null;
        String argsText = null; // optional extra args for main()

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                // -------- class name (plain + B64)
                case "--class" -> {
                    if (i + 1 < args.length) className = args[++i];
                }
                case "--classB64" -> {
                    if (i + 1 < args.length) className = decodeB64Utf8(args[++i]);
                }

                // -------- optional args passed to main(String[] args)
                case "--args" -> {
                    if (i + 1 < args.length) argsText = args[++i];
                }
                case "--argsB64" -> {
                    if (i + 1 < args.length) argsText = decodeB64Utf8(args[++i]);
                }

                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (className == null || className.isBlank()) {
            System.out.println("""
                Usage:
                  java RunClass --class <ClassName>
                  java RunClass --classB64 <base64-utf8-ClassName>

                Optional:
                  --args "<space separated args>"
                  --argsB64 <base64-utf8-args>
                """);
            return;
        }

        try {
            // Build command
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add(className);

            if (argsText != null && !argsText.isBlank()) {
                // split on whitespace (simple & deterministic)
                for (String a : argsText.split("\\s+")) {
                    cmd.add(a);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process p = pb.start();

            // Stream output
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(p.getInputStream()))) {

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

        } catch (IOException | InterruptedException e) {
            System.err.println("❌ Error running class: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
