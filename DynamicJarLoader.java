import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.Base64;

public class DynamicJarLoader {

    private URLClassLoader classLoader;
    private final List<URL> urls = new ArrayList<>();

    public DynamicJarLoader() {
        this.classLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
    }

    // Adds JAR URL and recreates class loader
    public void loadJarFromUrl(String jarUrl) throws IOException {
        String fileName = jarUrl.substring(jarUrl.lastIndexOf('/') + 1);
        Path localPath = Paths.get(fileName);

        if (!Files.exists(localPath)) {
            System.out.println("⬇️  Downloading: " + jarUrl);
            Files.copy(new URL(jarUrl).openStream(), localPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("✅ Downloaded to: " + localPath);
        } else {
            System.out.println("📦 Using cached JAR: " + localPath);
        }

        URL jarFileUrl = localPath.toUri().toURL();

        if (!urls.contains(jarFileUrl)) {
            urls.add(jarFileUrl);
            recreateClassLoader();
            System.out.println("🔗 Added to classpath: " + jarFileUrl);
        }
    }

    private void recreateClassLoader() {
        this.classLoader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return classLoader.loadClass(className);
    }

    public static String buildMavenJarUrl(String groupId, String artifactId, String version) {
        String base = "https://repo1.maven.org/maven2";
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        String jarName = artifactId + "-" + version + ".jar";
        return base + "/" + path + "/" + jarName;
    }

    // --------------------------------------------------
    // B64 helper (UTF-8)
    // --------------------------------------------------
    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    // 🧠 CLI Entry Point
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("""
                Usage:
                  java DynamicJarLoader --library <group:artifact:version>
                  java DynamicJarLoader --libraryB64 <base64-utf8-group:artifact:version>

                Or (explicit form):
                  java DynamicJarLoader --group <groupId> --artifact <artifactId> --version <version>

                Explicit Base64 variants (UTF-8 decoded):
                  java DynamicJarLoader --groupB64 <b64> --artifactB64 <b64> --versionB64 <b64>

                Examples:
                  java DynamicJarLoader --library org.json:json:20240303
                  java DynamicJarLoader --libraryB64 b3JnLmpzb246anNvbjoyMDI0MDMwMw==
                  java DynamicJarLoader --group com.google.code.gson --artifact gson --version 2.11.0
                """);
            return;
        }

        String groupId = null, artifactId = null, version = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                // ----- compact form (plain + B64)
                case "--library" -> {
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 3) {
                            groupId = parts[0];
                            artifactId = parts[1];
                            version = parts[2];
                        } else {
                            System.out.println("❌ Invalid format for --library. Use group:artifact:version");
                            return;
                        }
                    } else {
                        System.out.println("❌ Missing value for --library");
                        return;
                    }
                }
                case "--libraryB64" -> {
                    if (i + 1 < args.length) {
                        String decoded = decodeB64Utf8(args[++i]);
                        String[] parts = decoded.split(":");
                        if (parts.length == 3) {
                            groupId = parts[0];
                            artifactId = parts[1];
                            version = parts[2];
                        } else {
                            System.out.println("❌ Invalid decoded format for --libraryB64. Must decode to group:artifact:version");
                            return;
                        }
                    } else {
                        System.out.println("❌ Missing value for --libraryB64");
                        return;
                    }
                }

                // ----- explicit form (plain + B64)
                case "--group" -> { if (i + 1 < args.length) groupId = args[++i]; }
                case "--artifact" -> { if (i + 1 < args.length) artifactId = args[++i]; }
                case "--version" -> { if (i + 1 < args.length) version = args[++i]; }

                case "--groupB64" -> { if (i + 1 < args.length) groupId = decodeB64Utf8(args[++i]); }
                case "--artifactB64" -> { if (i + 1 < args.length) artifactId = decodeB64Utf8(args[++i]); }
                case "--versionB64" -> { if (i + 1 < args.length) version = decodeB64Utf8(args[++i]); }

                default -> {
                    // ignore unknown tokens
                }
            }
        }

        if (groupId == null || artifactId == null || version == null) {
            System.out.println("❌ Missing parameters. Run without arguments for help.");
            return;
        }

        try {
            DynamicJarLoader loader = new DynamicJarLoader();
            String url = buildMavenJarUrl(groupId, artifactId, version);
            loader.loadJarFromUrl(url);
            System.out.println("🎉 Library loaded successfully!");
        } catch (Exception e) {
            System.out.println("❌ Failed to load JAR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
