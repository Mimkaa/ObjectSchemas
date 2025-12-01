import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class SpecFieldLogicAppender {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (!a.ok()) { Args.usage(); return; }

        Path specPath = Paths.get(a.specFile).toAbsolutePath().normalize();
        if (!Files.exists(specPath)) {
            System.err.println("[ERROR] No such spec file: " + specPath);
            return;
        }

        Path logicPath = Paths.get(a.logicFile).toAbsolutePath().normalize();
        if (!Files.exists(logicPath)) {
            System.err.println("[ERROR] No such field-logic file: " + logicPath);
            return;
        }

        String json  = readTextBestEffort(specPath);
        String logic = readTextBestEffort(logicPath).trim();

        if (logic.isEmpty()) {
            System.err.println("[WARN] Logic file is empty, nothing to insert.");
            return;
        }

        String fieldKey = a.fieldName;
        String block    = buildNewFieldSpecBlock(fieldKey, logic);

        int idx = json.lastIndexOf("\n}\n"); // insert inside root JSON
        if (idx < 0) idx = json.lastIndexOf('}');
        String head = json.substring(0, idx).replaceFirst("\\s*$","");
        String tail = json.substring(idx);

        String out = head + ",\n" + block + "\n" + tail;
        Files.writeString(specPath, out, StandardCharsets.UTF_8);

        System.out.println("[OK] Appended newFieldDescSpec entry:");
        System.out.println("     Field = " + fieldKey);
        System.out.println("     Spec → " + specPath.toAbsolutePath());
    }

    private static String buildNewFieldSpecBlock(String fieldName, String desc) {
        return """
          "newFieldDescSpec": {
            "%s": "%s"
          }
        """.formatted(fieldName,
                desc.replace("\\","\\\\").replace("\"","\\\""));
    }

    private static String readTextBestEffort(Path p) throws Exception {
        try { return Files.readString(p, StandardCharsets.UTF_8); }
        catch (MalformedInputException e) {
            byte[] b = Files.readAllBytes(p);
            return new String(b, Charset.defaultCharset());
        }
    }

    static final class Args {
        String specFile;
        String logicFile;
        String fieldName;

        static Args parse(String[] av) {
            Args a = new Args();
            for (int i = 0; i < av.length; i++) {
                switch(av[i]) {
                    case "--specFile"  -> a.specFile  = (i+1<av.length)? av[++i] : null;
                    case "--logicFile" -> a.logicFile = (i+1<av.length)? av[++i] : null;
                    case "--fieldName" -> a.fieldName = (i+1<av.length)? av[++i] : null;
                }
            }
            return a;
        }

        boolean ok(){ return specFile!=null && logicFile!=null && fieldName!=null; }

        static void usage(){
            System.out.println("""
              SpecFieldLogicAppender — Insert a new field description into *_spec.json

              Usage:
                java SpecFieldLogicAppender \\
                    --specFile  Base_spec.json \\
                    --logicFile stringListField.txt \\
                    --fieldName stringList

              Result inside spec:
                "newFieldDescSpec": {
                    "stringList": "<natural-language description>"
                }
            """);
        }
    }
}
