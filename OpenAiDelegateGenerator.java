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
 * Command-line tool:
 *
 *   java OpenAiDelegateGenerator Base_spec.json
 *   or
 *   java OpenAiDelegateGenerator --Target_spec Base_spec.json
 *
 * Requires:
 *   - Java 11+
 *   - Environment variable OPENAI_API_KEY set
 *
 * Effect:
 *   - Reads <Target>_spec.json
 *   - Asks OpenAI (GPT-5.1) to generate a delegate class
 *   - Delegate may include NEW FIELDS, NEW METHODS, or BOTH
 *   - Writes <TargetSimpleName>Delegate.java and compiles it automatically
 */
public class OpenAiDelegateGenerator {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    // Upgraded model
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

    public OpenAiDelegateGenerator() {
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
                  java OpenAiDelegateGenerator <spec.json>
                  or
                  java OpenAiDelegateGenerator --Target_spec <spec.json>
                """);
            System.exit(1);
        }

        try {
            Path out = new OpenAiDelegateGenerator().generateDelegateFile(Paths.get(specFile));
            System.out.println("\n✔ Delegate generated → " + out.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("\n❌ Error during delegate creation:");
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

        System.out.println("\n===== GENERATED DELEGATE SOURCE =====\n" + javaSource + "\n===== END =====");

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

        } catch (Exception e) { return fallback; }
    }

    // =====================================================================
    // OPENAI REQUEST → DELEGATE JAVA CLASS
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

            FIELD GENERATION RULES (CRITICAL)
            ---------------------------------
            1) All NEW fields that are meant to be transferred (cloned into the base)
               come from newFieldDescSpec.

               For EACH entry (fieldName → description) in newFieldDescSpec:
                 • Declare a field with EXACTLY that fieldName.
                 • Infer its type from the description text.
                 • The field MUST be declared as PUBLIC.
                     Example:
                         public java.util.ArrayList<String> stringList;
                         public String extraString;

            2) Do NOT re-declare fields already present in the "fields" array of the spec.
               Only add truly NEW fields requested in newFieldDescSpec.

            3) If newFieldDescSpec is present and non-empty but newMethodLogicSpec is
               missing or empty, you MUST generate a delegate that ONLY contains:
                 • the public fields based on newFieldDescSpec
                 • and NO methods.

            4) If BOTH newFieldDescSpec and newMethodLogicSpec exist, then:
                 • All fields created from newFieldDescSpec MUST be public.
                 • Methods are allowed (see method rules below).

            METHOD GENERATION RULES
            -----------------------
            1) Methods come from newMethodLogicSpec.
               Each value is a natural-language description of one method
               (like "a non static method named addAndPrint ...").

            2) You must convert each description into ONE valid Java method:
                 • If the description gives an explicit signature
                     e.g. "static void main(String[] args) that ..."
                     use that exact signature (add 'public' if no access modifier).
                 • Otherwise, infer return type and parameters from the description.

            3) Methods may read and write the following fields:
                 • Any public fields you declared from newFieldDescSpec
                   (for example: stringList, extraString).
                 • Any existing fields from the "fields" array that are
                   NOT marked as private (i.e. no 'private' modifier there).

               IMPORTANT:
                 • Do NOT directly access fields that are listed in "fields" with
                   a 'private' modifier, unless you also declared a NEW public
                   field with the same meaning via newFieldDescSpec.
                 • In practice: for methods that should work with transferred
                   fields, you normally use the public fields coming from
                   newFieldDescSpec.

            4) Methods must follow the semantics of the description exactly.
               For example, for:

                 "a non static method named addAndPrint with no parameters and no
                  return value that does the following steps in order:
                    first ensure that the stringList field is initialized by checking
                    if it is null and if so creating a new java.util.ArrayList<String>
                    and assigning it to stringList, then add the extraString field value
                    to the stringList list, then iterate over all elements of stringList
                    in insertion order and print each element on its own line using
                    System.out.println"

               you should generate something along the lines of:

                 public void addAndPrint() {
                     if (this.stringList == null) {
                         this.stringList = new java.util.ArrayList<String>();
                     }
                     this.stringList.add(this.extraString);
                     for (String s : this.stringList) {
                         System.out.println(s);
                     }
                 }

               (using public fields generated from newFieldDescSpec).

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
                .header("Authorization","Bearer " + apiKey)
                .header("Content-Type","application/json")
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
                .replace("\\","\\\\")
                .replace("\"","\\\"")
                .replace("\n","\\n") + '"';
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
