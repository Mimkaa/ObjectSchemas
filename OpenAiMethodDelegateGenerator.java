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
 * Command-line tool (METHODS):
 *
 *   java OpenAiMethodDelegateGenerator Base_spec.json
 *   or
 *   java OpenAiMethodDelegateGenerator --Target_spec Base_spec.json
 *
 * Requires:
 *   - Java 11+
 *   - Environment variable OPENAI_API_KEY set
 *
 * Effect:
 *   - Reads <Target>_spec.json
 *   - Asks OpenAI (GPT-5.1) to generate a delegate class
 *   - Delegate may include NEW METHODS that use existing/public fields
 *   - Writes <TargetSimpleName>Delegate.java and compiles it automatically
 */
public class OpenAiMethodDelegateGenerator {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-5.1";

    private final String apiKey;
    private final HttpClient httpClient;

    // Style example (vibe only)
    private static final String EXAMPLE_DELEGATE = """
        public class SampleDelegate extends Sample {
            public int score = 0;

            public void add(int x) {
                score += x;
            }

            public int get() {
                return score;
            }
        }
        """;

    public OpenAiMethodDelegateGenerator() {
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
                  java OpenAiMethodDelegateGenerator <spec.json>
                  or
                  java OpenAiMethodDelegateGenerator --Target_spec <spec.json>
                """);
            System.exit(1);
        }

        try {
            Path out = new OpenAiMethodDelegateGenerator().generateDelegateFile(Paths.get(specFile));
            System.out.println("\n✔ Method delegate generated → " + out.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("\n❌ Error during method delegate creation:");
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

        System.out.println("\n===== GENERATED METHOD DELEGATE SOURCE =====\n" + javaSource + "\n===== END =====");

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
    // OPENAI REQUEST → METHOD-FOCUSED DELEGATE
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
              - "target"             : fully qualified or simple base class name.
              - "fields"             : array of existing fields on the base class.
              - "methods"            : array of existing methods on the base class.
              - "newFieldDescSpec"   : OPTIONAL. Object mapping fieldName → natural language
                                       description of a NEW FIELD to add (for transfer).
              - "newMethodLogicSpec" : OPTIONAL. Object mapping key → natural language
                                       description of NEW METHODS to generate.

            CLASS DECLARATION (STRICT)
            --------------------------
            You MUST output exactly one class:

                public class %s extends %s {
                    ...
                }

            Do NOT change the name.
            Do NOT omit or change 'extends %s'.
            No extra classes, no outer wrappers, no markdown.

            FIELD USAGE CONTEXT
            -------------------
            • Any NEW fields that exist in newFieldDescSpec have already been handled
              by a separate field generator. Assume they are (or will be) present
              as public fields, and you ARE allowed to use them.
            • You MUST NOT declare new fields here that duplicate fields created
              in the field pipeline. This generator should focus on METHODS.
            • Only declare a NEW field if the description clearly requires brand-new
              state that is NOT already captured by any field listed in the spec or
              newFieldDescSpec. In most cases you should NOT declare new fields.

            STATE & FIELD USAGE RULES (VERY IMPORTANT)
            -----------------------------------------
            • Whenever the description talks about:
                - "storing", "keeping", "remembering", "tracking",
                  "counting", "accumulating", "collecting", or similar ideas,
              you MUST use class fields for that state, NOT just local variables.

            • If a suitable field already exists in:
                - non-private entries in the "fields" array, OR
                - any public field from newFieldDescSpec,
              then the method MUST use that field instead of introducing a new local
              variable for the same concept.

            • Local variables are allowed only for short-lived, intermediate values
              (loop counters, temporary results, etc.), NOT for main state that should
              persist across method calls or be visible to other methods.

            • DISALLOWED PATTERN (avoid this when there is a matching field):
                  int sum = 0;  // local variable used for accumulated state
              when there is a field like:
                  public int sum;
              In that case, use:
                  this.sum = 0;
                  this.sum += value;

            • In general:
                - Prefer reading/writing fields (this.fieldName) when the description
                  implies persistent state.
                - Only use locals when the value does not need to be stored on the object.

            METHOD GENERATION RULES
            -----------------------
            1) Methods come from newMethodLogicSpec.
               Each value is a natural-language description of one method
               (like "a non static method named addAndPrint ...").

            METHOD VISIBILITY RULE (CRITICAL)
            ---------------------------------
            • All generated methods MUST be declared public by default.
            • Private methods are FORBIDDEN unless the description explicitly
              says the method should be private.
            • Non-public methods (protected/package-private) are only allowed
              if the description explicitly requests that visibility.

            2) You must convert each description into ONE valid Java method:
                 • If the description gives an explicit signature
                     e.g. "static void main(String[] args) that ..."
                     use that exact signature (add 'public' if no access modifier).
                 • Otherwise, infer return type and parameters from the description,
                   and declare the method as public.

            3) Methods may read and write the following fields:
                 • Any public fields from newFieldDescSpec.
                 • Any existing fields from the "fields" array that are
                   NOT marked as private (i.e. no 'private' modifier there).

               IMPORTANT:
                 • Do NOT directly access fields that are listed in "fields" with
                   a 'private' modifier, unless you also declared a NEW public
                   field with the same meaning via newFieldDescSpec.
                 • When a description implies state that should persist or be shared,
                   you MUST choose an appropriate field (existing or from
                   newFieldDescSpec) instead of keeping that state only in locals.

            METHOD CALL RESTRICTION RULE (CRITICAL)
            ---------------------------------------
            • A generated method may ONLY call:
                 1) Methods listed in the "methods" array of the spec, OR
                 2) Methods that you are generating from newMethodLogicSpec in the
                    same delegate, OR
                 3) Methods inherited from java.lang.Object (toString, equals, etc.).

            • It is STRICTLY FORBIDDEN to invent or call helper methods that do not
              exist in the spec or in newMethodLogicSpec.             

            • If the description requires extra steps, you MUST write the necessary
              logic inline inside the method body instead of calling imaginary
              helper functions.

            4) Methods must follow the semantics of the description exactly,
               and the resulting class MUST be 100%% valid, compilable Java code.

            5) Methods may be instance or static depending on the description.
               If not specified, prefer instance methods (non-static).

            OUTPUT REQUIREMENTS
            -------------------
            • Output ONLY one complete Java class:
                  public class %s extends %s { ... }
            • No markdown, no backticks, no commentary, no extra text.

            RAW SPEC FOR CONTEXT (DO NOT ECHO VERBATIM):
            --------------------------------------------
            %s

            STYLE EXAMPLE (JUST FOR VIBE, DO NOT COPY LITERALLY):
            ----------------------------------------------------
            %s
            """.formatted(
                delegateName, parentName, parentName,
                delegateName, parentName,
                specJson, EXAMPLE_DELEGATE
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
