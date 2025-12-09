import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI tool that generates a delegate class:
 *   public class DynamicDelegate extends <parent> { ... }
 *
 * It can optionally add:
 *   - one field (raw Java snippet)
 *   - one method (raw Java snippet)
 *
 * Usage (flags are expected to be lower-case if you pass through the Python pipeline):
 *
 *   java DynamicDelegateCreator \
 *       --parent MyBaseClass \
 *       --field ArrayList<String> places = new ArrayList<String>(); \
 *       --outputDir .
 */
public class DynamicDelegateCreator {

    public static void main(String[] args) {
        String parentClass = null;
        String fieldDecl   = null;
        String methodDecl  = null;
        String outputDir   = ".";  // default: current directory

        // Parse args, allowing multi-word field/method declarations
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--parent" -> {
                    if (i + 1 < args.length) {
                        parentClass = args[++i];
                    }
                }
                case "--field" -> {
                    // Collect everything until the next flag (starts with "--")
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
                    // Same multi-token logic for a method
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
                case "--outputdir" -> {
                    // NOTE: if your Python normalizes flags to lowercase, you get "--outputdir" here
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    }
                }
                case "--outputDir" -> {
                    // Also accept camelCase if you call it directly without normalization
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    }
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
            new DynamicDelegateCreator().writeDelegate(parentClass, fieldDecl, methodDecl, outputDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("""
            DynamicDelegateCreator - Generate DynamicDelegate.java extending a given parent.

            Usage:
              java DynamicDelegateCreator \\
                  --parent MyBaseClass \\
                  [--field "public java.util.ArrayList<String> places = new java.util.ArrayList<>();"] \\
                  [--method "public void foo() { System.out.println(\\"hi\\"); }"] \\
                  [--outputDir .]

            Notes:
              - --field and --method accept multi-word Java snippets; everything up to the next --flag
                is treated as part of the declaration.
              - If you run via the Python pipeline that normalizes flags to lowercase, the flags
                will arrive as --parent, --field, --method, --outputdir.
            """);
    }

    public void writeDelegate(String parentClass,
                              String fieldDecl,
                              String methodDecl,
                              String outputDir) throws IOException {

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

        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);

        System.out.println("✅ Wrote delegate: " + outFile.toAbsolutePath());
    }
}
