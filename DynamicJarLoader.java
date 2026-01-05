import java.net.URL;
import java.net.URLClassLoader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.Base64;

public class DynamicJarLoader {

    private URLClassLoader classLoader;
    private final List<URL> urls = new ArrayList<>();

    public DynamicJarLoader() {
        this.classLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
    }

    // ==============================
    // DOWNLOAD (with HTTP status)
    // ==============================
    public void loadJarFromUrl(String jarUrl) throws IOException {
        String fileName = jarUrl.substring(jarUrl.lastIndexOf('/') + 1);
        Path localPath = Paths.get(fileName);

        if (!Files.exists(localPath)) {
            System.out.println("⬇️  Downloading: " + jarUrl);

            HttpURLConnection con = (HttpURLConnection) new URL(jarUrl).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setRequestMethod("GET");
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "DynamicJarLoader/1.0");

            int code = con.getResponseCode();
            if (code != 200) {
                throw new HttpStatusIOException(code, "HTTP " + code + " for " + jarUrl);
            }

            try (InputStream in = con.getInputStream()) {
                Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
            }

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

    // ==================================================
    // "DID YOU MEAN?" via Maven Central Search API
    // ==================================================
    private static List<String> suggestCoords(String artifactId, String version) {
        // 1) Best case: exact artifact+version => find correct groupId(s)
        List<CentralDoc> exact = searchCentral(
                "a:\"" + escQ(artifactId) + "\" AND v:\"" + escQ(version) + "\"",
                10
        );

        List<String> exactMatches = new ArrayList<>();
        for (CentralDoc d : exact) {
            if (artifactId.equals(d.a) && version.equals(d.v)) {
                exactMatches.add(d.g + ":" + d.a + ":" + d.v);
            }
        }
        exactMatches = dedupKeepOrder(exactMatches);
        if (!exactMatches.isEmpty()) return exactMatches;

        // 2) Fallback: artifact only => show likely groups + hint to check versions
        List<CentralDoc> byArtifact = searchCentral(
                "a:\"" + escQ(artifactId) + "\"",
                10
        );

        List<String> guesses = new ArrayList<>();
        for (CentralDoc d : byArtifact) {
            if (artifactId.equals(d.a)) {
                guesses.add(d.g + ":" + d.a + ":<check version>");
            }
        }
        return dedupKeepOrder(guesses);
    }

    private static String escQ(String s) {
        // minimal escaping for central query context
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> dedupKeepOrder(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static class CentralDoc {
        final String g, a, v;
        CentralDoc(String g, String a, String v) {
            this.g = g;
            this.a = a;
            this.v = v;
        }
    }

    private static List<CentralDoc> searchCentral(String query, int rows) {
        // Maven Central search endpoint (Solr). Dependency-free parsing:
        // https://search.maven.org/solrsearch/select?q=...&rows=...&wt=json
        String url = "https://search.maven.org/solrsearch/select?q=" + urlEncode(query)
                + "&rows=" + rows + "&wt=json";

        try {
            String json = httpGetText(url);
            return parseCentralDocs(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String urlEncode(String s) {
        // small encoder sufficient for our query strings
        return s.replace(" ", "%20")
                .replace("\"", "%22")
                .replace(":", "%3A")
                .replace("\\", "%5C");
    }

    private static String httpGetText(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(10_000);
        con.setReadTimeout(20_000);
        con.setRequestProperty("User-Agent", "DynamicJarLoader/1.0");

        int code = con.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        if (in == null) throw new IOException("HTTP " + code + " for " + url);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // Very small JSON-ish parser: pull "g","a","v" from docs[]
    private static List<CentralDoc> parseCentralDocs(String json) {
        List<CentralDoc> out = new ArrayList<>();
        int docsPos = json.indexOf("\"docs\":[");
        if (docsPos < 0) return out;

        int i = docsPos;
        while (true) {
            int gPos = json.indexOf("\"g\":\"", i);
            if (gPos < 0) break;

            int aPos = json.indexOf("\"a\":\"", gPos);
            int vPos = json.indexOf("\"v\":\"", gPos);
            if (aPos < 0 || vPos < 0) break;

            String g = readJsonString(json, gPos + 5);
            String a = readJsonString(json, aPos + 5);
            String v = readJsonString(json, vPos + 5);

            out.add(new CentralDoc(g, a, v));
            i = vPos + 5;

            if (out.size() >= 50) break; // safety cap
        }
        return out;
    }

    private static String readJsonString(String json, int start) {
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class HttpStatusIOException extends IOException {
        final int status;
        HttpStatusIOException(int status, String msg) {
            super(msg);
            this.status = status;
        }
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

        String url = buildMavenJarUrl(groupId, artifactId, version);

        try {
            DynamicJarLoader loader = new DynamicJarLoader();
            loader.loadJarFromUrl(url);
            System.out.println("🎉 Library loaded successfully!");
        } catch (HttpStatusIOException e) {
            System.out.println("❌ Failed to load JAR (HTTP " + e.status + ")");
            System.out.println("   " + e.getMessage());

            if (e.status == 404) {
                List<String> suggestions = suggestCoords(artifactId, version);
                if (!suggestions.isEmpty()) {
                    System.out.println("💡 Did you mean:");
                    for (String s : suggestions) System.out.println("   " + s);
                } else {
                    System.out.println("💡 No suggestions from Maven Central search.");
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Failed to load JAR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
