import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI tool that generates a delegate class:
 *   public class DynamicDelegate extends <parent> { ... }
 *
 * Supports inline snippets:
 *   --field  <multi-token java snippet>
 *   --method <multi-token java snippet>
 *
 * Supports file-based snippets (recommended for large payloads):
 *   --fieldFile  <path-to-text-file>
 *   --methodFile <path-to-text-file>
 *
 * Usage:
 *   java DynamicDelegateCreator --parent MyBaseClass --methodFile Method.txt --outputDir .
 */
public class DynamicDelegateCreator {

    public static void main(String[] args) {
        String parentClass = null;

        String fieldDecl = null;
        String methodDecl = null;

        String fieldFile = null;
        String methodFile = null;

        String outputDir = "."; // default: current directory

        // Parse args, allowing multi-word field/method declarations
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--parent" -> {
                    if (i + 1 < args.length) parentClass = args[++i];
                }

                case "--fieldFile" -> {
                    if (i + 1 < args.length) fieldFile = args[++i];
                }

                case "--methodFile" -> {
                    if (i + 1 < args.length) methodFile = args[++i];
                }

                case "--field" -> {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < args.length && !args[i].startsWith("--")) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(args[i]);
                        i++;
                    }
                    i--; // step back so outer loop sees the flag again
                    fieldDecl = sb.toString().trim();
                }

                case "--method" -> {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < args.length && !args[i].startsWith("--")) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(args[i]);
                        i++;
                    }
                    i--;
                    methodDecl = sb.toString().trim();
                }

                case "--outputdir", "--outputDir" -> {
                    if (i + 1 < args.length) outputDir = args[++i];
                }

                default -> {
                    // ignore unknown tokens
                }
            }
        }

        if (parentClass == null) {
            System.err.println("ERROR: --parent <ParentClass> is required.");
            printUsage();
            return;
        }

        try {
            // If file flags exist, they override inline snippets
            if (fieldFile != null && !fieldFile.isBlank()) {
                fieldDecl = readAll(Paths.get(fieldFile));
            }
            if (methodFile != null && !methodFile.isBlank()) {
                methodDecl = readAll(Paths.get(methodFile));
            }

            new DynamicDelegateCreator().writeDelegate(parentClass, fieldDecl, methodDecl, outputDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("""
            DynamicDelegateCreator — Generate DynamicDelegate.java extending a given parent.

            Usage:
              java DynamicDelegateCreator \\
                  --parent MyBaseClass \\
                  [--field "public java.util.ArrayList<String> places = new java.util.ArrayList<>();"] \\
                  [--method "public void foo() { System.out.println(\\"hi\\"); }"] \\
                  [--fieldFile Field.txt] \\
                  [--methodFile Method.txt] \\
                  [--outputDir .]

            Notes:
              - --field and --method accept multi-word Java snippets; everything up to the next --flag
                is treated as part of the declaration.
              - --fieldFile / --methodFile read the entire file content (UTF-8) and override inline values.
              - Both --outputDir and --outputdir are accepted.
            """);
    }

    private static String readAll(Path p) throws IOException {
        // Resolve relative paths against the current working directory
        Path abs = p.isAbsolute() ? p : Paths.get(System.getProperty("user.dir")).resolve(p).normalize();
        return Files.readString(abs, StandardCharsets.UTF_8).trim();
    }

    public void writeDelegate(String parentClass,
                              String fieldDecl,
                              String methodDecl,
                              String outputDir) throws IOException, InterruptedException {

        Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
        Files.createDirectories(outDir);

        Path outFile = outDir.resolve("DynamicDelegate.java");

        StringBuilder sb = new StringBuilder();

        sb.append("/**\n");
        sb.append(" * Auto-generated delegate class.\n");
        sb.append(" * Extends: ").append(parentClass).append("\n");
        sb.append(" */\n");
        sb.append("public class DynamicDelegate extends ").append(parentClass).append(" {\n\n");

        if (fieldDecl != null && !fieldDecl.isBlank()) {
            sb.append("    // Cloned / specified field\n");
            sb.append("    ").append(fieldDecl).append("\n\n");
        }

        if (methodDecl != null && !methodDecl.isBlank()) {
            sb.append("    // Cloned / specified method\n");
            sb.append("    ").append(methodDecl).append("\n\n");
        }

        sb.append("}\n");

        // Write (overwrite) .java file
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("✅ Wrote delegate source: " + outFile.toAbsolutePath());

        // --- Compile to .class using external javac ---
        System.out.println("🛠  Compiling DynamicDelegate.java with external javac ...");

        ProcessBuilder pb = new ProcessBuilder("javac", outFile.getFileName().toString());
        pb.directory(outDir.toFile());
        pb.inheritIO();

        Process p = pb.start();
        int exit = p.waitFor();

        if (exit != 0) {
            System.err.println("❌ javac failed for DynamicDelegate.java, exit code: " + exit);
        } else {
            System.out.println("✅ Compiled DynamicDelegate.class in: " + outDir.toAbsolutePath());
        }
    }
}
