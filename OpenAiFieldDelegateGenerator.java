import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line tool (FIELDS):
 *
 *     java OpenAiFieldDelegateGenerator --Target_spec Base_spec.json
 *
 * Requires:
 *   - Java 11+
 *   - Environment variable OPENAI_API_KEY set
 *
 * Effect:
 *   - Reads <Target>_spec.json
 *   - Asks OpenAI (GPT-5.1) to generate a DELEGATE containing ONLY PUBLIC FIELDS
 *     that correspond to fields listed in the spec or newFieldDescSpec.
 *
 *   - Writes <TargetSimpleName>Delegate.java and compiles it automatically.
 */
public class OpenAiFieldDelegateGenerator {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-5.1";

    private final String apiKey;
    private final HttpClient httpClient;

    public OpenAiFieldDelegateGenerator() {
        this.apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable missing");
        }
        this.httpClient = HttpClient.newHttpClient();
    }

    // =====================================================================
    // MAIN — FLAG-ONLY VERSION
    // =====================================================================
    public static void main(String[] args) {
        String specFile = null;

        // Strict flag-based parsing: --Target_spec <spec.json>
        for (int i = 0; i < args.length; i++) {
            if ("--Target_spec".equals(args[i]) && i + 1 < args.length) {
                specFile = args[i + 1];
                break;
            }
        }

        if (specFile == null) {
            System.err.println("""
                Usage:
                  java OpenAiFieldDelegateGenerator --Target_spec <spec.json>
                """);
            System.exit(1);
        }

        try {
            Path out = new OpenAiFieldDelegateGenerator().generateDelegateFile(Paths.get(specFile));
            System.out.println("\n✔ Field delegate generated → " + out.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("\n❌ Error during field delegate creation:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // =====================================================================
    // CORE GENERATION — BUILD FIELD-ONLY DELEGATE
    // =====================================================================
    public Path generateDelegateFile(Path specPath) throws IOException, InterruptedException {
        String specJson = Files.readString(specPath, StandardCharsets.UTF_8);

        String base = specPath.getFileName().toString()
                .replace("_spec.json", "")
                .replace(".json", "");

        String original = extractTargetSimpleName(specJson, base);
        String delegateName = original + "Delegate";

        String javaSource = generateDelegateSource(delegateName, original, specJson);

        Path out = specPath.getParent() != null
                ? specPath.getParent().resolve(delegateName + ".java")
                : Paths.get(delegateName + ".java");

        Files.writeString(out, javaSource, StandardCharsets.UTF_8);

        System.out.println("\n===== GENERATED FIELD DELEGATE =====\n" + javaSource + "\n===== END =====");

        compile(out);
        return out;
    }

    // =====================================================================
    // NAME EXTRACTION
    // =====================================================================
    private static String extractTargetSimpleName(String json, String fallback) {
        try {
            int idx = json.indexOf("\"target\"");
            if (idx < 0) return fallback;

            int c = json.indexOf(':', idx);
            int q1 = json.indexOf('"', c);
            int q2 = json.indexOf('"', q1 + 1);

            String full = json.substring(q1 + 1, q2).trim();
            if (full.isEmpty()) return fallback;

            int dot = full.lastIndexOf('.');
            return (dot >= 0) ? full.substring(dot + 1) : full;

        } catch (Exception e) {
            return fallback;
        }
    }

    // =====================================================================
    // OPENAI REQUEST — FIELD-ONLY DELEGATE
    // =====================================================================
    private String generateDelegateSource(String delegateName,
                                          String parentName,
                                          String specJson)
            throws IOException, InterruptedException {

        String systemPrompt = """
            You generate ONLY a valid Java delegate class.
            The class MUST:
            - extend the original base class,
            - contain ONLY public fields,
            - NO methods,
            - NO comments,
            - NO constructors,
            - NO extra text.
            """;

        String userPrompt = """
            You are given a SPEC describing a Java class.
            You must generate a delegate containing ONLY PUBLIC FIELDS.

            RULES:
            • Output exactly one class:
                  public class %s extends %s { ... }
            • No methods allowed.
            • Every field must be public.
            • For each field entry in:
                - "fields" array
                - "newFieldDescSpec" object
              → declare a corresponding PUBLIC FIELD in the delegate.

            • Field types:
                - If type is explicitly known in the spec, use it.
                - If type is unknown, infer the simplest reasonable type
                  (String, int, boolean) based on description.
                - If ambiguous, default to String.

            RAW SPEC (for context only — do NOT echo):
            ------------------------------------------
            %s
            """.formatted(delegateName, parentName, specJson);

        String requestBody = """
        {
          "model": "%s",
          "messages":[
            {"role":"system","content":%s},
            {"role":"user","content":%s}
          ],
          "temperature":0.15
        }
        """.formatted(MODEL, toJson(systemPrompt), toJson(userPrompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("OpenAI " + resp.statusCode() + " → " + resp.body());
        }

        return extractAssistantContent(resp.body()).trim();
    }

    // =====================================================================
    // JSON PARSER FOR ASSISTANT OUTPUT
    // =====================================================================
    private static String extractAssistantContent(String body) {
        int p = body.indexOf("\"content\":");
        int q = body.indexOf('"', p + 10);

        StringBuilder out = new StringBuilder();
        boolean esc = false;

        for (int i = q + 1; i < body.length(); i++) {
            char c = body.charAt(i);

            if (esc) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    default -> out.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // =====================================================================
    // JSON ESCAPE UTILITY
    // =====================================================================
    private static String toJson(String s) {
        return '"' + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }

    // =====================================================================
    // COMPILER
    // =====================================================================
    private void compile(Path file) throws IOException, InterruptedException {
        System.out.println("\nCompiling " + file.getFileName());

        Process p = new ProcessBuilder("javac", file.toString())
                .redirectErrorStream(true)
                .start();

        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();

        System.out.println(out);
        if (code == 0) {
            System.out.println("✔ Compilation success\n");
        } else {
            System.out.println("❌ Compile error: " + code);
        }
    }
}
