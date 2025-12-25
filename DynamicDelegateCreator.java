import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * CLI tool that generates a delegate class:
 *   public class DynamicDelegate extends <parent> { ... }
 *
 * Supports inline snippets:
 *   --field  <multi-token java snippet>
 *   --method <multi-token java snippet>
 *
 * Supports file-based snippets:
 *   --fieldFile  <path-to-text-file>
 *   --methodFile <path-to-text-file>
 *
 * Supports Base64 variants for EVERY parameter (UTF-8 decoded):
 *   --parentB64
 *   --fieldB64
 *   --methodB64
 *   --fieldFileB64
 *   --methodFileB64
 *   --outputDirB64   (also accepts --outputdirB64)
 *
 * Precedence (per param):
 *   file > B64 > inline
 *
 * Note: For *paths*, file flags still mean "read this file's content".
 *       The B64 variants for file flags decode to the *path string*.
 */
public class DynamicDelegateCreator {

    public static void main(String[] args) {
        String parentClass = null;

        String fieldDecl = null;
        String methodDecl = null;

        String fieldFile = null;
        String methodFile = null;

        String outputDir = "."; // default: current directory

        // --------------------------------------------------
        // Parse args, allowing multi-word field/method declarations
        // --------------------------------------------------
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                // -------- parent (plain + B64)
                case "--parent" -> {
                    if (i + 1 < args.length) parentClass = args[++i];
                }
                case "--parentB64" -> {
                    if (i + 1 < args.length) parentClass = decodeB64Utf8(args[++i]);
                }

                // -------- fieldFile (plain + B64 path)
                case "--fieldFile" -> {
                    if (i + 1 < args.length) fieldFile = args[++i];
                }
                case "--fieldFileB64" -> {
                    if (i + 1 < args.length) fieldFile = decodeB64Utf8(args[++i]);
                }

                // -------- methodFile (plain + B64 path)
                case "--methodFile" -> {
                    if (i + 1 < args.length) methodFile = args[++i];
                }
                case "--methodFileB64" -> {
                    if (i + 1 < args.length) methodFile = decodeB64Utf8(args[++i]);
                }

                // -------- field (inline multi-token) + B64
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
                case "--fieldB64" -> {
                    if (i + 1 < args.length) fieldDecl = decodeB64Utf8(args[++i]);
                }

                // -------- method (inline multi-token) + B64
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
                case "--methodB64" -> {
                    if (i + 1 < args.length) methodDecl = decodeB64Utf8(args[++i]);
                }

                // -------- outputDir (plain + B64)
                case "--outputdir", "--outputDir" -> {
                    if (i + 1 < args.length) outputDir = args[++i];
                }
                case "--outputdirB64", "--outputDirB64" -> {
                    if (i + 1 < args.length) outputDir = decodeB64Utf8(args[++i]);
                }

                default -> {
                    // ignore unknown tokens
                }
            }
        }

        if (parentClass == null || parentClass.isBlank()) {
            System.err.println("ERROR: --parent <ParentClass> (or --parentB64) is required.");
            printUsage();
            return;
        }

        try {
            // --------------------------------------------------
            // Precedence: file > inline/B64 snippet
            //
            // If fieldFile/methodFile exist, they override fieldDecl/methodDecl
            // --------------------------------------------------
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
                  [--parentB64 <base64-utf8-parent>] \\
                  [--field "public int x;"] \\
                  [--fieldB64 <base64-utf8-field-snippet>] \\
                  [--method "public void foo() { System.out.println(\\"hi\\"); }"] \\
                  [--methodB64 <base64-utf8-method-snippet>] \\
                  [--fieldFile Field.txt] \\
                  [--fieldFileB64 <base64-utf8-path-to-field-file>] \\
                  [--methodFile Method.txt] \\
                  [--methodFileB64 <base64-utf8-path-to-method-file>] \\
                  [--outputDir .] \\
                  [--outputDirB64 <base64-utf8-outputdir>]

            Notes:
              - --field and --method accept multi-word Java snippets; everything up to the next --flag
                is treated as part of the declaration.
              - --*B64 variants decode Base64 as UTF-8 and behave like the non-B64 variant.
              - If --fieldFile/--methodFile are provided (plain or B64), their file contents override inline snippets.
              - Both --outputDir and --outputdir are accepted (and same for B64).
            """);
    }

    private static String readAll(Path p) throws IOException {
        // Resolve relative paths against the current working directory
        Path abs = p.isAbsolute() ? p : Paths.get(System.getProperty("user.dir")).resolve(p).normalize();
        return Files.readString(abs, StandardCharsets.UTF_8).trim();
    }

    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
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
