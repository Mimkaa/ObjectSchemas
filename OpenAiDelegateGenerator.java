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
 *   java OpenAiDelegateGenerator Loco_spec.json
 *   or
 *   java OpenAiDelegateGenerator --Target_spec Loco_spec.json
 *
 * Requires:
 *   - Java 11+
 *   - Environment variable OPENAI_API_KEY set
 *
 * Effect:
 *   - Reads <Target>_spec.json
 *   - Asks OpenAI to generate a delegate class
 *   - Writes <TargetSimpleName>Delegate.java in the same directory
 *   - Then compiles it to <TargetSimpleName>Delegate.class via javac
 */
public class OpenAiDelegateGenerator {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4.1-mini";

    private final String apiKey;
    private final HttpClient httpClient;

    // A short style example so the model matches your vibe
    private static final String EXAMPLE_DELEGATE = """
        /**
         * Simple delegate class for testing ClassMethodAdder
         */
        public class SimpleDelegate {

            // Instance field to demonstrate state
            private int callCount = 0;

            public String getGreeting() {
                return "Hello from SimpleDelegate!";
            }

            public String processText(String input) {
                return "Processed: " + input.toUpperCase();
            }

            public int calculate(int a, int b) {
                return a + b;
            }

            public String getStatus() {
                return "System is working!";
            }

            public String getInstanceInfo() {
                callCount++;
                return "Instance: " + this.getClass().getSimpleName() +
                       " | Calls: " + callCount +
                       " | Hash: " + System.identityHashCode(this);
            }
        }
        """;

    public OpenAiDelegateGenerator() {
        this.apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is not set");
        }
        this.httpClient = HttpClient.newHttpClient();
    }

    // ------------------------------------------------------------
    // main: accepts either positional spec.json or --Target_spec <file>
    // ------------------------------------------------------------
    public static void main(String[] args) {
        String specFile = null;

        if (args.length == 1 && !args[0].startsWith("--")) {
            // Positional form: java OpenAiDelegateGenerator Loco_spec.json
            specFile = args[0];
        } else {
            // Flag form: java OpenAiDelegateGenerator --Target_spec Loco_spec.json
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--Target_spec".equals(arg) && i + 1 < args.length) {
                    specFile = args[++i];
                    break;
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

        Path specPath = Paths.get(specFile);

        try {
            OpenAiDelegateGenerator gen = new OpenAiDelegateGenerator();
            Path out = gen.generateDelegateFile(specPath);
            System.out.println("Generated delegate: " + out.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Error while generating delegate:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Reads <Target>_spec.json, asks OpenAI for the delegate, writes <TargetSimpleName>Delegate.java,
     * prints its content, and compiles it to <TargetSimpleName>Delegate.class.
     */
    public Path generateDelegateFile(Path specPath) throws IOException, InterruptedException {
        String specJson = Files.readString(specPath, StandardCharsets.UTF_8);

        String fileName = specPath.getFileName().toString();
        String baseName = fileName.replace("_spec.json", "").replace(".json", "");

        // Try to infer target simple name from the JSON "target" field.
        String inferredTargetSimpleName = extractTargetSimpleName(specJson, baseName);
        String delegateClassName = inferredTargetSimpleName + "Delegate";

        String javaSource = generateDelegateSource(delegateClassName, inferredTargetSimpleName, specJson);

        Path outPath = specPath.getParent() == null
                ? Paths.get(delegateClassName + ".java")
                : specPath.getParent().resolve(delegateClassName + ".java");

        Files.writeString(outPath, javaSource, StandardCharsets.UTF_8);
        System.out.println("Wrote delegate source: " + outPath.toAbsolutePath());

        // 🔹 Print the generated delegate before compilation
        System.out.println("===== GENERATED DELEGATE SOURCE =====");
        System.out.println(javaSource);
        System.out.println("===== END GENERATED DELEGATE SOURCE =====");

        // Compile the generated Java file
        compileGeneratedJava(outPath);

        return outPath;
    }

    /**
     * Extracts the simple class name from the "target" field of the spec JSON.
     * If anything fails, falls back to the provided baseName.
     *
     * Examples:
     *   "target": "Dori"                    -> "Dori"
     *   "target": "com.example.model.Dori"  -> "Dori"
     */
    private static String extractTargetSimpleName(String specJson, String fallbackBaseName) {
        try {
            String key = "\"target\"";
            int idx = specJson.indexOf(key);
            if (idx < 0) {
                return fallbackBaseName;
            }
            int colon = specJson.indexOf(':', idx + key.length());
            if (colon < 0) {
                return fallbackBaseName;
            }
            int firstQuote = specJson.indexOf('"', colon);
            if (firstQuote < 0) {
                return fallbackBaseName;
            }
            int secondQuote = specJson.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) {
                return fallbackBaseName;
            }
            String targetValue = specJson.substring(firstQuote + 1, secondQuote).trim();
            if (targetValue.isEmpty()) {
                return fallbackBaseName;
            }
            // If target is fully qualified, strip package
            int lastDot = targetValue.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < targetValue.length() - 1) {
                return targetValue.substring(lastDot + 1);
            }
            return targetValue;
        } catch (Exception e) {
            return fallbackBaseName;
        }
    }

    /**
     * Calls OpenAI and returns the generated delegate class as plain Java source text.
     */
    public String generateDelegateSource(String delegateClassName,
                                         String targetSimpleName,
                                         String specJson)
            throws IOException, InterruptedException {

        String systemPrompt = """
            You are an expert senior Java engineer.
            Generate ONLY a full Java class. No explanations.
            """;

        String userPrompt = buildUserPrompt(delegateClassName, targetSimpleName, specJson);

        String requestBody = buildRequestBody(systemPrompt, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new IOException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        String body = response.body();

        // Very simple extraction of "content" from the first choice.
        String marker = "\"content\":";
        int idx = body.indexOf(marker);
        if (idx < 0) {
            throw new IOException("No 'content' field in OpenAI response: " + body);
        }
        int startQuote = body.indexOf('"', idx + marker.length());
        if (startQuote < 0) {
            throw new IOException("Malformed 'content' field in OpenAI response");
        }
        StringBuilder content = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> content.append('\n');
                    case 'r' -> content.append('\r');
                    case 't' -> content.append('\t');
                    case '"' -> content.append('"');
                    case '\\' -> content.append('\\');
                    default -> content.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
            }
        }

        return content.toString().trim();
    }

    // Build the JSON request body manually (no JSON lib)
    private String buildRequestBody(String systemPrompt, String userPrompt) {
        String sysJson = toJsonString(systemPrompt);
        String userJson = toJsonString(userPrompt);

        return """
        {
          "model": "%s",
          "messages": [
            { "role": "system", "content": %s },
            { "role": "user",   "content": %s }
          ],
          "temperature": 0.2
        }
        """.formatted(MODEL, sysJson, userJson);
    }

    // Escape a Java String to a JSON string literal (including quotes)
    private static String toJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // 🔹 PROMPT BUILDER: previous logic + note about main/instance helpers
    private String buildUserPrompt(String delegateClassName,
                                   String targetSimpleName,
                                   String specJson) {
        return """
            You are given a JSON SPEC describing an EXISTING Java class.
            It contains:
              - "target": the original class name (possibly fully qualified).
              - "fields": fields that ALREADY exist in the original class.
              - "methods": methods that ALREADY exist in the original class.
              - "newMethodLogicSpec": OPTIONAL. A map from NEW method names
                to natural-language descriptions of what those NEW methods
                should do.

            IMPORTANT: CONTEXT VS NEW CODE
            --------------------------------
            - The "fields" and "methods" entries describe what ALREADY EXISTS
              in the original class. They are CONTEXT for you.
            - You MUST NOT:
                  * re-declare those existing methods,
                  * change their bodies, signatures, or modifiers,
                  * re-declare the existing fields or change their type/modifiers.
            - HOWEVER, you ARE allowed to read and write those existing fields
              inside the new methods you generate, exactly as if your code
              were placed inside the original class (for example: this.age = 42; is OK).
            - Think of the generated methods as code that will be injected into
              the original class later.

            CLASS NAME AND EXTENDS CLAUSE
            -----------------------------
            - The class you generate MUST be declared EXACTLY as:
                  public class %s extends %s {
                      ...
                  }
            - Do NOT change the class name.
            - Do NOT omit the 'extends %s'.
            - Do NOT extend any other type.

            HOW TO TREAT NAMES IN LOGIC (VERY IMPORTANT)
            --------------------------------------------
            For each entry in "newMethodLogicSpec" (methodKey -> description):

            1. Build an internal map of existing field names from "fields".
               Example: if fields contain an entry with "name": "age",
               you know there is already a field called "age" on the original class.

            2. When reading the natural-language description, for every identifier-like term
               that looks like a variable (for example: "name", "numbers", "age", etc.):
               - If this name EXACTLY matches a known field name from "fields", then:
                   * Treat it as an EXISTING field on the class.
                   * You may freely read and write it, e.g. this.age = 42; age++; etc.
                   * Do NOT create a new field with that name.
               - If the name does NOT match any field in "fields":
                   * Prefer to model it as a METHOD PARAMETER or a LOCAL VARIABLE.

            3. The method signatures you choose MUST be consistent with the logic:
               - Example: "Return a + b" with no matching fields "a" and "b":
                     -> treat "a" and "b" as PARAMETERS:
                            public int add(int a, int b) { return a + b; }
               - Example: "Set age to 42" and "age" is a known field:
                     -> it is correct to generate:
                            public void initializevariables() {
                                this.age = 42;
                            }

            4. If the description explicitly says "create X ..." (for example:
                  "create numbers list of type List<Integer> and fill it"):
               - Then declare a LOCAL variable named X inside the method with
                 an appropriate type.
               - Do NOT add X as a new field of the class.

            5. When the description says "use numbers" or "use name":
               - Check carefully if "numbers" or "name" exist as fields.
               - If yes, treat them as existing fields and use this.<fieldName>.
               - If not, model them as parameters or locals.

            SPECIAL HANDLING FOR EXPLICIT METHOD SIGNATURES (CRITICAL)
            ----------------------------------------------------------
            The description text may explicitly mention a FULL Java method signature,
            for example:

                "static void main(String[] args) that creates a 3x3 int matrix ..."

            In that case:

            - You MUST generate a method with EXACTLY that signature
              (including 'static' and parameter list).
            - If no access modifier is given, default to 'public'.
              For example: "static void main(String[] args)" ->
                   public static void main(String[] args) { ... }
            - You may IGNORE the methodKey name when a full signature is given.
              The signature in the description takes precedence.

            SPECIAL HANDLING FOR main AND INSTANCE METHODS
            ----------------------------------------------
            - If you generate a static main method (for example
              public static void main(String[] args)) and it needs to call
              ANY helper methods that are NOT static, you MUST first create
              an instance of the delegate class and call the helpers on that
              instance.
            - Concretely, inside main you should write something like:
                  DelegateClass delegate = new DelegateClass();
                  delegate.someHelper(...);
              whenever someHelper(...) is not static.
            - Do NOT call instance methods directly from static main without
              creating an instance first.

            WHAT METHODS TO GENERATE
            ------------------------
            For each entry in "newMethodLogicSpec" (methodKey -> description):

            - If the description clearly specifies a full Java method signature
              (e.g. "static void main(String[] args)" or "int sum(int a, int b)"):
                * Use THAT signature.
                * Do not invent another name; do not rename it to methodKey.

            - OTHERWISE (no full signature is given):
                * Create exactly ONE new method whose name is methodKey.
                * Choose parameters and return type that match the described behavior.
                * The method may be instance or static depending on the logic, but
                  if the description does not say otherwise, prefer an INSTANCE method.

            GENERAL SEMANTICS
            -----------------
            - Do NOT re-declare or modify the signatures/modifiers of existing fields/methods.
            - You MAY read and write existing fields in the new methods.
            - Prefer parameters and locals for new concepts that are not fields.

            OUTPUT REQUIREMENTS
            -------------------
            - Output ONLY a single valid Java class with:
                  public class %s extends %s { ... }
            - Do NOT wrap it in Markdown.
            - Do NOT output any explanation text.
            - The result must compile as-is (assuming standard Java and imports).

            JSON SPEC (context only, NOT to be echoed in output):
            -----------------------------------------------------
            %s

            STYLE EXAMPLE (for formatting / vibe only, do NOT copy literally):
            ------------------------------------------------------------------
            %s
            """.formatted(
                    delegateClassName, targetSimpleName, targetSimpleName,
                    delegateClassName, targetSimpleName,
                    specJson, EXAMPLE_DELEGATE
            );
    }

    // ------------------------------------------------------------
    // compile the generated Java file using javac
    // ------------------------------------------------------------
    private void compileGeneratedJava(Path javaFile) throws IOException, InterruptedException {
        System.out.println("Compiling generated file: " + javaFile);

        ProcessBuilder pb = new ProcessBuilder("javac", javaFile.toString());
        pb.redirectErrorStream(true);

        Process p = pb.start();

        String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();

        if (exitCode == 0) {
            Path classFile = javaFile.getParent() == null
                    ? Paths.get(javaFile.getFileName().toString().replace(".java", ".class"))
                    : javaFile.getParent().resolve(javaFile.getFileName().toString().replace(".java", ".class"));

            System.out.println("Compiled successfully: " + classFile.toAbsolutePath());
        } else {
            System.err.println("Compilation failed with exit code " + exitCode + ":");
            System.err.println(result);
        }
    }
}
