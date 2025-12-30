import java.io.File;
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
 */
public class DynamicDelegateCreator {

    public static void main(String[] args) {
        String parentClass = null;

        String fieldDecl = null;
        String methodDecl = null;

        String fieldFile = null;
        String methodFile = null;

        String outputDir = "."; // default: current directory

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--parent" -> {
                    if (i + 1 < args.length) parentClass = args[++i];
                }
                case "--parentB64" -> {
                    if (i + 1 < args.length) parentClass = decodeB64Utf8(args[++i]);
                }

                case "--fieldFile" -> {
                    if (i + 1 < args.length) fieldFile = args[++i];
                }
                case "--fieldFileB64" -> {
                    if (i + 1 < args.length) fieldFile = decodeB64Utf8(args[++i]);
                }

                case "--methodFile" -> {
                    if (i + 1 < args.length) methodFile = args[++i];
                }
                case "--methodFileB64" -> {
                    if (i + 1 < args.length) methodFile = decodeB64Utf8(args[++i]);
                }

                case "--field" -> {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < args.length && !args[i].startsWith("--")) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(args[i]);
                        i++;
                    }
                    i--;
                    fieldDecl = sb.toString().trim();
                }
                case "--fieldB64" -> {
                    if (i + 1 < args.length) fieldDecl = decodeB64Utf8(args[++i]);
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
                case "--methodB64" -> {
                    if (i + 1 < args.length) methodDecl = decodeB64Utf8(args[++i]);
                }

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
            System.exit(2);
            return;
        }

        try {
            // file overrides inline/b64
            if (fieldFile != null && !fieldFile.isBlank()) {
                fieldDecl = readAll(Paths.get(fieldFile));
            }
            if (methodFile != null && !methodFile.isBlank()) {
                methodDecl = readAll(Paths.get(methodFile));
            }

            // Guard: methodDecl must be a METHOD, not a full class
            if (methodDecl != null) {
                String m = methodDecl;
                if (m.contains("class ") || m.contains("public class") || m.contains("private class")) {
                    System.err.println("ERROR: method snippet looks like a FULL CLASS definition.");
                    System.err.println("DynamicDelegateCreator expects ONLY a method declaration, e.g.:");
                    System.err.println("  public static void main(String[] args) { ... }");
                    System.exit(3);
                    return;
                }
            }

            new DynamicDelegateCreator().writeDelegate(parentClass, fieldDecl, methodDecl, outputDir);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("""
            DynamicDelegateCreator — Generate DynamicDelegate.java extending a given parent.

            Usage:
              java DynamicDelegateCreator \
                  --parent MyBaseClass \
                  [--field "public int x;"] \
                  [--method "public void foo() { System.out.println(\\"hi\\"); }"] \
                  [--fieldFile Field.txt] \
                  [--methodFile Method.txt] \
                  [--outputDir .]

            Base64 variants (UTF-8):
              --parentB64 --fieldB64 --methodB64 --fieldFileB64 --methodFileB64 --outputDirB64

            Notes:
              - --field and --method accept multi-word Java snippets; everything up to the next --flag is part of it.
              - If --fieldFile/--methodFile are provided, their file contents override inline snippets.
              - Compilation is done with a classpath auto-built from the output directory:
                  <outDir>;<outDir>\\*   (Windows)  /  <outDir>:<outDir>/*  (Linux/Mac)
                so it can see the base .class and any jars in that folder.
              - If javac fails, this tool exits non-zero (no silent reuse of old DynamicDelegate.class).
            """);
    }

    private static String readAll(Path p) throws IOException {
        Path abs = p.isAbsolute() ? p : Paths.get(System.getProperty("user.dir")).resolve(p).normalize();
        return Files.readString(abs, StandardCharsets.UTF_8).trim();
    }

    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    private static String defaultClasspathForDir(Path dir) {
        String abs = dir.toAbsolutePath().normalize().toString();
        String sep = File.pathSeparator; // ';' on Windows, ':' on Unix
        String wildcard = abs + File.separator + "*";
        return abs + sep + wildcard;
    }

    private static String indentBlock(String text, String indent) {
        if (text == null) return null;
        String t = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = t.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(indent).append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
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
            sb.append(indentBlock(fieldDecl, "    ")).append("\n\n");
        }

        if (methodDecl != null && !methodDecl.isBlank()) {
            sb.append("    // Cloned / specified method\n");
            sb.append(indentBlock(methodDecl, "    ")).append("\n\n");
        }

        sb.append("}\n");

        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("✅ Wrote delegate source: " + outFile.toAbsolutePath());

        // Compile WITH classpath (so parent + jars resolve)
        String cp = defaultClasspathForDir(outDir);

        System.out.println("🛠  Compiling DynamicDelegate.java with external javac ...");
        System.out.println("    javac -cp \"" + cp + "\" " + outFile.getFileName());

        ProcessBuilder pb = new ProcessBuilder("javac", "-cp", cp, outFile.getFileName().toString());
        pb.directory(outDir.toFile());
        pb.inheritIO();

        Process p = pb.start();
        int exit = p.waitFor();

        if (exit != 0) {
            System.err.println("❌ javac failed for DynamicDelegate.java, exit code: " + exit);
            System.err.println("❌ NOT continuing (prevents cloning from stale DynamicDelegate.class).");
            System.exit(exit);
        }

        System.out.println("✅ Compiled DynamicDelegate.class in: " + outDir.toAbsolutePath());
    }
}
