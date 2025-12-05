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
 * Command-line tool (FIELDS ONLY):
 *
 *   java OpenAiFieldDelegateGenerator Base_spec.json
 *   or
 *   java OpenAiFieldDelegateGenerator --Target_spec Base_spec.json
 *
 * Requires:
 *   - Java 11+
 *   - Environment variable OPENAI_API_KEY set
 *
 * Effect:
 *   - Reads <Target>_spec.json
 *   - Asks OpenAI (GPT-5.1) to generate a delegate class
 *   - Delegate contains ONLY public fields from newFieldDescSpec (no methods)
 *   - Writes <TargetSimpleName>Delegate.java and compiles it automatically
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
    // MAIN
    // =====================================================================
    public static void main(String[] args) {
        String specFile = null;

        if (args.length == 1 && !args[0].startsWith("--")) {
            specFile = args[0];
        } else {
            for (int i = 0; i < args.length; i++) {
                if ("--Target_spec".equals(args[i]) && i + 1 < args.length) {
                    specFile = args[++i];
                }
            }
        }

        if (specFile == null) {
            System.err.println("""
                Usage:
                  java OpenAiFieldDelegateGenerator <spec.json>
                  or
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
        }
    }

    // =====================================================================
    // CORE GENERATION
    // =====================================================================
    public Path generateDelegateFile(Path specPath) throws IOException, InterruptedException {
        String specJson = Files.readString(specPath, StandardCharsets.UTF_8);

        String base = specPath.getFileName().toString()
                .replace("_spec.json", "")
                .replace(".json", "");

        String original = extractTargetSimpleName(specJson, base);
        String delegateName = original + "Delegate";

        String javaSource = generateDelegateSource(delegateName, original, specJson);

        Path out = specPath.getParent() != null ?
                specPath.getParent().resolve(delegateName + ".java") :
                Paths.get(delegateName + ".java");

        Files.writeString(out, javaSource, StandardCharsets.UTF_8);

        System.out.println("\n===== GENERATED FIELD DELEGATE SOURCE =====\n" + javaSource + "\n===== END =====");

        compile(out);
        return out;
    }

    // =====================================================================
    // SPEC NAME EXTRACTION
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
    // OPENAI REQUEST → FIELDS-ONLY DELEGATE
    // =====================================================================
    private String generateDelegateSource(String delegateName,
                                          String parentName,
                                          String specJson)
            throws IOException, InterruptedException {

        String systemPrompt = """
            You generate ONLY a valid Java delegate class. No explanations.
            """;

        String userPrompt = """
            You are given a SPEC describing a Java class structure.
            The delegate you generate MUST extend the original base class.

            The SPEC may contain:
              - "target"           : fully qualified or simple base class name.
              - "fields"           : array of existing fields on the base class.
              - "newFieldDescSpec" : Object mapping fieldName → natural language
                                     description of a NEW FIELD to add (for transfer).

            CLASS DECLARATION (STRICT)
            --------------------------
            You MUST output exactly one class:

                public class %s extends %s {
                    ...
                }

            Do NOT change the name.
            Do NOT omit or change 'extends %s'.
            No extra classes, no outer wrappers, no markdown.

            FIELD GENERATION RULES (FIELDS-ONLY DELEGATE)
            ---------------------------------------------
            1) This generator is for FIELDS ONLY.
               You MUST NOT generate ANY methods at all.
               The class body may contain:
                 • public fields created from newFieldDescSpec
                 • (optionally) a public no-arg constructor that does nothing,
                   but no business logic.

            2) All NEW fields that are meant to be transferred (cloned into the base)
               come from newFieldDescSpec.

               For EACH entry (fieldName → description) in newFieldDescSpec:
                 • Declare a field with EXACTLY that fieldName.
                 • Infer its type from the description text.
                 • The field MUST be declared as PUBLIC.
                     Example:
                         public java.util.ArrayList<String> stringList;
                         public String extraString;

            3) Do NOT re-declare fields already present in the "fields" array of the spec.
               Only add truly NEW fields requested in newFieldDescSpec.

            4) If newFieldDescSpec is missing, null or empty, you MUST generate a class
               with an empty body:
                 public class %s extends %s {
                 }

            5) No methods, no business logic, no helper functions.

            OUTPUT REQUIREMENTS
            -------------------
            • Output ONLY one complete Java class:
                  public class %s extends %s { ... }
            • No markdown, no backticks, no commentary, no extra text.

            RAW SPEC FOR CONTEXT (DO NOT ECHO VERBATIM):
            --------------------------------------------
            %s
            """.formatted(
                delegateName, parentName, parentName,
                delegateName, parentName,
                delegateName, parentName,
                specJson
        );

        String requestBody = """
        {
          "model": "%s",
          "messages":[
            {"role":"system","content":%s},
            {"role":"user","content":%s}
          ],
          "temperature":0.2
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

        String body = resp.body();
        int p = body.indexOf("\"content\":");
        int q = body.indexOf('"', p + 10);
        StringBuilder out = new StringBuilder();
        boolean esc = false;

        for (int i = q + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (esc) {
                if (c == 'n') out.append('\n');
                else if (c == 't') out.append('\t');
                else out.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }

        return out.toString().trim();
    }

    private static String toJson(String s) {
        return '"' + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }

    // =====================================================================
    // COMPILE RESULT
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
