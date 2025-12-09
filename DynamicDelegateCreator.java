import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * CLI tool that generates a DynamicDelegate.java file.
 *
 * Usage:
 *   java DynamicDelegateCreator \
 *       --parent <FullyQualifiedParentClassName> \
 *       [--field "<fieldDeclaration>"] \
 *       [--method "<methodDeclaration>"] \
 *       [--outputDir <path>]
 *
 * Examples:
 *   java DynamicDelegateCreator --parent com.example.Base
 *
 *   java DynamicDelegateCreator \
 *       --parent com.example.Base \
 *       --field "public int counter;" \
 *       --method "public void increment() { this.counter++; }"
 */
public class DynamicDelegateCreator {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String parentClass = null;
        String fieldDecl = null;
        String methodDecl = null;
        String outputDir = "."; // default: current working directory

        // --- Parse CLI args ---
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--parent" -> {
                    if (i + 1 < args.length) {
                        parentClass = args[++i];
                    }
                }
                case "--field" -> {
                    if (i + 1 < args.length) {
                        fieldDecl = args[++i];
                    }
                }
                case "--method" -> {
                    if (i + 1 < args.length) {
                        methodDecl = args[++i];
                    }
                }
                case "--outputDir" -> {
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    }
                }
                default -> {
                    // ignore unknown flags, or you can print a warning
                }
            }
        }

        if (parentClass == null || parentClass.isEmpty()) {
            System.err.println("❌ ERROR: --parent <FullyQualifiedParentClassName> is required.");
            printUsage();
            return;
        }

        try {
            generateDelegate(parentClass, fieldDecl, methodDecl, outputDir);
        } catch (IOException e) {
            System.err.println("❌ Failed to generate DynamicDelegate.java");
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("""
            DynamicDelegateCreator — generate a DynamicDelegate.java class that extends a given parent.

            Usage:
              java DynamicDelegateCreator \\
                  --parent <FullyQualifiedParentClassName> \\
                  [--field "<fieldDeclaration>"] \\
                  [--method "<methodDeclaration>"] \\
                  [--outputDir <path>]

            Notes:
              • parent must be a valid Java type name, e.g. com.example.Base
              • field is a raw Java field declaration, e.g. "public int counter;"
              • method is a raw Java method declaration, e.g.
                    "public void increment() { this.counter++; }"
              • outputDir defaults to the current directory if omitted.

            Example:
              java DynamicDelegateCreator \\
                  --parent com.example.Base \\
                  --field "public int counter;" \\
                  --method "public void increment() { this.counter++; }"
            """);
    }

    /**
     * Generate DynamicDelegate.java with the given parent, field, and method.
     */
    private static void generateDelegate(String parentClass,
                                         String fieldDecl,
                                         String methodDecl,
                                         String outputDir) throws IOException {

        // Class name is fixed by your spec
        String className = "DynamicDelegate";

        // We will use the fully qualified parent name directly in 'extends'
        // to avoid dealing with imports/packages for now.
        StringBuilder sb = new StringBuilder();

        // Optional comment header
        sb.append("/**\n")
          .append(" * Auto-generated delegate class.\n")
          .append(" * Extends: ").append(parentClass).append("\n")
          .append(" */\n");

        sb.append("public class ").append(className)
          .append(" extends ").append(parentClass).append(" {\n\n");

        // Optional field
        if (fieldDecl != null && !fieldDecl.isBlank()) {
            sb.append("    // Cloned / specified field\n");
            sb.append("    ").append(fieldDecl.trim()).append("\n\n");
        }

        // Optional method
        if (methodDecl != null && !methodDecl.isBlank()) {
            sb.append("    // Cloned / specified method\n");
            // ensure indentation of each line of the method
            String[] lines = methodDecl.split("\\R");
            for (String line : lines) {
                sb.append("    ").append(line).append("\n");
            }
            sb.append("\n");
        }

        sb.append("}\n");

        // Ensure output directory exists
        File outDir = new File(outputDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + outDir.getAbsolutePath());
        }

        File outFile = new File(outDir, className + ".java");

        try (FileWriter writer = new FileWriter(outFile)) {
            writer.write(sb.toString());
        }

        System.out.println("✅ Generated delegate source:");
        System.out.println("   " + outFile.getAbsolutePath());
    }
}
