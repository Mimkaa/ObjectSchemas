// === OpenAiMethodDelegateGenerator (FIXED & UPGRADED) ===
// This version:
//  • Correctly extracts ONE method description from newMethodLogicSpec
//  • Sends ONLY that description to GPT
//  • Generates exactly one method
//  • Outputs a correct delegate class
//  • Fully replaces your broken implementation

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public class OpenAiMethodDelegateGenerator {

    private static final String MODEL = "gpt-5.1";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey = System.getenv("OPENAI_API_KEY");
    private final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String specFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--Target_spec".equals(args[i]) && i + 1 < args.length) {
                specFile = args[i + 1];
                break;
            }
        }

        if (specFile == null) {
            System.err.println("Usage: java OpenAiMethodDelegateGenerator --Target_spec <spec.json>");
            System.exit(1);
        }

        new OpenAiMethodDelegateGenerator().run(Paths.get(specFile));
    }

    public void run(Path specPath) throws Exception {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("OPENAI_API_KEY missing");

        String specText = Files.readString(specPath);
        JSONObject spec = new JSONObject(specText);

        String baseName = extractBaseName(spec, specPath);
        String delegateName = baseName + "Delegate";

        // === FIX #1: Extract one method description ===
        MethodSpec extracted = extractOneMethodSpec(spec);
        if (extracted == null) {
            throw new IllegalStateException("No newMethodLogicSpec found — cannot generate delegate");
        }

        // === FIX #2: Ask GPT for ONE method ===
        String classSource = generateDelegate(delegateName, baseName, extracted);

        // === Write file ===
        Path out = specPath.getParent().resolve(delegateName + ".java");
        Files.writeString(out, classSource, StandardCharsets.UTF_8);

        System.out.println("\n===== GENERATED METHOD DELEGATE SOURCE =====");
        System.out.println(classSource);
        System.out.println("===== END =====\n");

        compile(out);
    }

    // ============================================================
    // Extract TARGET CLASS NAME
    // ============================================================
    private String extractBaseName(JSONObject spec, Path specPath) {
        if (spec.has("target")) {
            String t = spec.getString("target");
            int dot = t.lastIndexOf('.');
            return dot >= 0 ? t.substring(dot + 1) : t;
        }
        String file = specPath.getFileName().toString();
        return file.replace("_spec.json", "");
    }

    // ============================================================
    // Extract ONE method description
    // ============================================================
    private static class MethodSpec {
        final String name;
        final String description;

        MethodSpec(String n, String d) { name = n; description = d; }
    }

    private MethodSpec extractOneMethodSpec(JSONObject spec) {
        if (!spec.has("newMethodLogicSpec"))
            return null;

        JSONObject obj = spec.getJSONObject("newMethodLogicSpec");

        Iterator<String> keys = obj.keys();
        if (!keys.hasNext())
            return null;

        // FIRST ENTRY ONLY (your chosen behavior)
        String key = keys.next();
        String desc = obj.getString(key);

        return new MethodSpec(key, desc);
    }

    // ============================================================
    // GPT CALL
    // ============================================================
    private String generateDelegate(String delegateName, String parentName, MethodSpec method)
            throws IOException, InterruptedException {

        String prompt = """
            Generate EXACTLY ONE Java class:

                public class %s extends %s { ... }

            Requirements:
            • Generate exactly ONE method.
            • The method name is: %s
            • The natural language description is:

              "%s"

            • Implement the logic fully.
            • No TODOs, no empty bodies.
            • Do NOT generate extra helper methods.
            • Do NOT generate constructors.
            • Methods must compile.
            • If description implies private, make it private; otherwise public.

            Output ONLY valid Java — no markdown.
            """.formatted(delegateName, parentName, method.name, method.description);

        JSONObject req = new JSONObject();
        req.put("model", MODEL);
        req.put("temperature", 0.2);

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", "You output ONLY pure Java code."));
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        req.put("messages", msgs);

        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                .build();

        HttpResponse<String> resp = client.send(http, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2)
            throw new IOException("OpenAI error: " + resp.statusCode() + "\n" + resp.body());

        JSONObject json = new JSONObject(resp.body());
        return json.getJSONArray("choices")
                   .getJSONObject(0)
                   .getJSONObject("message")
                   .getString("content")
                   .trim();
    }

    // ============================================================
    // Compile
    // ============================================================
    private void compile(Path file) throws IOException, InterruptedException {
        System.out.println("Compiling " + file.getFileName());

        Process p = new ProcessBuilder("javac", file.toString())
                .redirectErrorStream(true)
                .start();

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        System.out.println(out);
        if (code == 0) System.out.println("✔ Compilation OK");
        else System.out.println("❌ Compile error: " + code);
    }
}
