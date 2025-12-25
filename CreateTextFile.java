import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CreateTextFile {

    public static void main(String[] args) {

        if (args.length == 0) {
            printUsage();
            return;
        }

        String fileName = null;
        String targetPath = System.getProperty("user.dir"); // default
        String content = ""; // default empty file

        // --------------------------------------------------
        // Parse arguments
        // --------------------------------------------------
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                // ---------- name (plain + B64)
                case "--name" -> {
                    if (i + 1 < args.length) fileName = args[++i];
                }
                case "--nameB64" -> {
                    if (i + 1 < args.length) fileName = decodeB64(args[++i]);
                }

                // ---------- path (plain + B64)
                case "--path" -> {
                    if (i + 1 < args.length && !args[i + 1].isEmpty()) {
                        targetPath = args[++i];
                    }
                }
                case "--pathB64" -> {
                    if (i + 1 < args.length) targetPath = decodeB64(args[++i]);
                }

                // ---------- content (plain + B64)
                case "--content" -> {
                    if (i + 1 < args.length) content = args[++i];
                }
                case "--contentB64" -> {
                    if (i + 1 < args.length) content = decodeB64(args[++i]);
                }

                default -> {
                    // ignore unknown flags
                }
            }
        }

        // --------------------------------------------------
        // Validate
        // --------------------------------------------------
        if (fileName == null || fileName.isBlank()) {
            System.out.println("❌ Error: Missing required parameter --name / --nameB64");
            return;
        }

        if (!fileName.endsWith(".txt")) {
            fileName += ".txt";
        }

        File file = new File(targetPath, fileName);

        // --------------------------------------------------
        // Create file
        // --------------------------------------------------
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }

            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                writer.write(content);
            }

            System.out.println("✅ File created successfully: " + file.getAbsolutePath());

        } catch (IOException e) {
            System.out.println("❌ Failed to create file: " + file.getAbsolutePath());
            System.out.println("Error: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------
    private static String decodeB64(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static void printUsage() {
        System.out.println("""
            CreateTextFile — Create a .txt file with optional content.

            Usage:
              java CreateTextFile \\
                  --name <fileName> | --nameB64 <base64> \\
                  [--path <dir> | --pathB64 <base64>] \\
                  [--content <text> | --contentB64 <base64>]

            Examples:
              java CreateTextFile --name hello --content "Hi"
              java CreateTextFile --nameB64 aGVsbG8= --contentB64 cHVibGljIGNsYXNzIFggeyB9
            """);
    }
}
