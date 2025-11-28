import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarClassExtractor {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        List<String> jarPaths = new ArrayList<>();
        Path targetDir = Paths.get(".").toAbsolutePath().normalize();

        // --- Simple arg parsing ---
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jar" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("ERROR: --jar requires a file path");
                        return;
                    }
                    jarPaths.add(args[++i]);
                }
                case "--target" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("ERROR: --target requires a directory path");
                        return;
                    }
                    targetDir = Paths.get(args[++i]).toAbsolutePath().normalize();
                }
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("WARNING: Unknown argument: " + args[i]);
                }
            }
        }

        if (jarPaths.isEmpty()) {
            System.err.println("ERROR: No --jar arguments provided.");
            printUsage();
            return;
        }

        System.out.println("JarClassExtractor");
        System.out.println("------------------");
        System.out.println("Target directory: " + targetDir);
        System.out.println("JARs to extract:  " + jarPaths);

        try {
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            for (String jarPath : jarPaths) {
                extractJar(jarPath, targetDir);
            }

            System.out.println("✅ Extraction finished.");
        } catch (IOException e) {
            System.err.println("❌ Extraction failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("JarClassExtractor - Extract all files from one or more JARs");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java JarClassExtractor --jar <pathToJar1> [--jar <pathToJar2> ...] [--target <outputDir>]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java JarClassExtractor --jar asm-9.7.jar --jar asm-tree-9.7.jar");
        System.out.println("  java JarClassExtractor --jar lib/asm-9.7.jar --target extracted");
        System.out.println();
        System.out.println("If --target is not specified, the current directory is used.");
    }

    private static void extractJar(String jarPath, Path targetDir) throws IOException {
        Path jarFilePath = Paths.get(jarPath).toAbsolutePath().normalize();
        System.out.println("\n🔍 Extracting JAR: " + jarFilePath);

        if (!Files.exists(jarFilePath)) {
            throw new IOException("JAR file not found: " + jarFilePath);
        }

        try (JarFile jarFile = new JarFile(jarFilePath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Skip directories, we'll create them as needed
                if (entry.isDirectory()) {
                    continue;
                }

                Path outPath = targetDir.resolve(name).normalize();

                // Make sure parent directories exist
                if (outPath.getParent() != null) {
                    Files.createDirectories(outPath.getParent());
                }

                try (InputStream in = jarFile.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(outPath)) {

                    in.transferTo(out);
                }

                System.out.println("  ➜ " + name);
            }
        }

        System.out.println("✅ Done extracting: " + jarFilePath.getFileName());
    }
}
