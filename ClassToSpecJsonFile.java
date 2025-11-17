import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ClassToSpecJsonFile {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (!a.ok()) { Args.usage(); return; }

        Path classFile = Paths.get(a.targetFile).toAbsolutePath().normalize();
        if (!Files.exists(classFile)) {
            System.err.println("[ERROR] No such file: " + classFile);
            return;
        }

        // ---- Infer FQN if missing (from .class constant pool) ----
        String fqn = a.fqn;
        if (fqn == null) {
            try {
                fqn = readFqnFromClassFile(classFile);
                System.out.println("[INFO] Inferred FQN from classfile: " + fqn);
            } catch (IOException e) {
                fqn = simpleFromFileName(classFile.getFileName().toString());
                System.out.println("[WARN] Could not parse FQN from classfile; using simple name: " + fqn);
            }
        }

        // ---- Build classpath root from classFile + FQN ----
        Path cpRoot = deriveClasspathRootFromFqn(classFile, fqn);
        URLClassLoader loader = new URLClassLoader(new URL[]{ cpRoot.toUri().toURL() });

        // ---- Load class and extract ----
        Class<?> target = Class.forName(fqn, true, loader);
        List<FieldInfo> fields = extractFields(target, a.includePrivate);
        List<MethodInfo> methods = extractMethods(target, a.includePrivate);

        // ---- Parse method specs (keep detailed entries to know provided names) ----
        List<MethodSpec> providedSpecs = parseMethodSpecsDetailed(a.methodSpecs);

        // ---- Attach logic specs to EXISTING methods (match by full signature first, then by name) ----
        Set<String> existingNames = new HashSet<>();
        for (MethodInfo mi : methods) existingNames.add(mi.name);

        for (MethodInfo mi : methods) {
            String keySig = mi.signatureForMatch();
            // find best matching provided spec
            MethodSpec match = null;
            for (MethodSpec ms : providedSpecs) {
                if (ms.signature != null && ms.signature.equals(keySig)) { match = ms; break; }
            }
            if (match == null) {
                for (MethodSpec ms : providedSpecs) {
                    if (ms.name != null && ms.name.equals(mi.name)) { match = ms; break; }
                }
            }
            if (match != null) {
                mi.logicSpec = match.logic;
            }
        }

        // ---- Collect NON-EXISTING method specs into top-level "newMethodLogicSpec" ----
        Map<String,String> newMethodLogicSpec = new LinkedHashMap<>();
        for (MethodSpec ms : providedSpecs) {
            // if the given method name is not present among existing methods, record it as "planned"
            if (ms.name != null && !existingNames.contains(ms.name)) {
                newMethodLogicSpec.put(ms.name, ms.logic);
            }
        }

        // ---- Build JSON and write ----
        String json = toJson(fqn, fields, methods, newMethodLogicSpec);
        Path out = (a.outPath == null)
                ? classFile.getParent().resolve(simple(fqn) + "_spec.json")
                : Paths.get(a.outPath);
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("[OK] Wrote spec: " + out.toAbsolutePath());
    }

    // ---------- Args ----------
    static final class Args {
        String targetFile;        // required: path/to/Some.class
        String fqn;               // optional (auto-inferred)
        String outPath;           // optional
        boolean includePrivate = true;
        List<String> methodSpecs = new ArrayList<>(); // optional: "<signature>; // logic"

        static Args parse(String[] av) {
            Args a = new Args();
            for (int i = 0; i < av.length; i++) {
                switch (av[i]) {
                    case "--targetFile" -> { if (i+1 < av.length) a.targetFile = av[++i]; }
                    case "--fqn"        -> { if (i+1 < av.length) a.fqn = av[++i]; }
                    case "--out"        -> { if (i+1 < av.length) a.outPath = av[++i]; }
                    case "--publicOnly" -> a.includePrivate = false;
                    case "--methodSpec" -> { if (i+1 < av.length) a.methodSpecs.add(av[++i]); }
                }
            }
            return a;
        }
        boolean ok() { return targetFile != null; }
        static void usage() {
            System.out.println("""
                ClassToSpecJsonFile - read a .class from disk and emit a JSON spec.
                - Auto-detects FQN from the .class file when --fqn is omitted
                - No external JSON libs required
                - NEW: records non-existing methods under top-level "newMethodLogicSpec"

                Usage:
                  java ClassToSpecJsonFile --targetFile <path/to/Model.class> [--fqn com.example.Model]
                                           [--out model_spec.json] [--publicOnly]
                                           [--methodSpec "<signature>; // <logic>"] ...

                Examples:
                  java ClassToSpecJsonFile --targetFile ./com/example/Model.class
                  java ClassToSpecJsonFile --targetFile ./Model.class \
                    --methodSpec "sayHello; // Return 'Hello, ' + this.name"
                  java ClassToSpecJsonFile --targetFile ./Model.class \
                    --methodSpec "incrementNumbers; // Loop over numbers and add 1"
                """);
        }
    }

    // ---------- Read FQN from .class constant pool ----------
    static String readFqnFromClassFile(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Bad classfile magic");
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            Object[] cp = new Object[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: cp[i] = in.readUTF(); break;            // Utf8
                    case 3: case 4: in.readInt(); break;            // int/float
                    case 5: case 6: in.readLong(); i++; break;      // long/double (2 slots)
                    case 7: case 8: cp[i] = in.readUnsignedShort(); break; // Class/String (index)
                    case 9: case 10: case 11: case 12:
                        in.readUnsignedShort(); in.readUnsignedShort(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    case 16: in.readUnsignedShort(); break;
                    case 18: in.readUnsignedShort(); in.readUnsignedShort(); break;
                    case 19: case 20: in.readUnsignedShort(); break;
                    default: throw new IOException("Unknown CP tag: " + tag);
                }
            }
            in.readUnsignedShort(); // access flags
            int thisClassIndex = in.readUnsignedShort();
            in.readUnsignedShort(); // super

            int nameIndex = (int) cp[thisClassIndex];
            String internalName = (String) cp[nameIndex];
            return internalName.replace('/', '.');
        }
    }

    // ---------- Classpath root from fqn & file path ----------
    static Path deriveClasspathRootFromFqn(Path classFile, String fqn) {
        String rel = fqn.replace('.', File.separatorChar) + ".class";
        String ap = classFile.toString();
        int idx = ap.lastIndexOf(rel);
        if (idx >= 0) return Paths.get(ap.substring(0, idx)).normalize();
        return classFile.getParent(); // fallback
    }

    // ---------- Models ----------
    static final class FieldInfo {
        String name;
        String type;        // generic-aware
        List<String> modifiers;
        String generic;     // Field#toGenericString()
        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"name\":").append(js(name))
              .append(",\"type\":").append(js(type))
              .append(",\"modifiers\":").append(listJson(modifiers));
            if (generic != null) sb.append(",\"generic\":").append(js(generic));
            sb.append("}");
            return sb.toString();
        }
    }

    static final class MethodInfo {
        String name;
        String returnType;         // generic-aware
        List<Param> parameters = new ArrayList<>();
        List<String> throwsTypes = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        String logicSpec;          // optional

        String signatureForMatch() {
            StringBuilder sb = new StringBuilder();
            sb.append(modifiersString(modifiers)).append(" ");
            sb.append(returnType).append(" ");
            sb.append(name).append("(");
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(parameters.get(i).type);
            }
            sb.append(")");
            return sb.toString().trim();
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"name\":").append(js(name))
              .append(",\"returnType\":").append(js(returnType))
              .append(",\"parameters\":[");
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(parameters.get(i).toJson());
            }
            sb.append("]")
              .append(",\"throws\":").append(listJson(throwsTypes))
              .append(",\"modifiers\":").append(listJson(modifiers));
            if (logicSpec != null) sb.append(",\"logicSpec\":").append(js(logicSpec));
            sb.append("}");
            return sb.toString();
        }
    }

    static final class Param {
        String name;
        String type; // generic-aware
        String toJson() {
            return "{\"name\":" + js(name) + ",\"type\":" + js(type) + "}";
        }
    }

    // For capturing exactly what the user provided via --methodSpec
    static final class MethodSpec {
        String signature; // normalized signature text (optional)
        String name;      // method name derived from signature (required to index planned specs)
        String logic;     // user's description
    }

    // ---------- Extraction ----------
    static List<FieldInfo> extractFields(Class<?> cls, boolean includePrivate) {
        Field[] fs = includePrivate ? cls.getDeclaredFields() : cls.getFields();
        List<FieldInfo> out = new ArrayList<>();
        for (Field f : fs) {
            FieldInfo fi = new FieldInfo();
            fi.name = f.getName();
            fi.type = f.getGenericType().getTypeName();
            fi.modifiers = mods(f.getModifiers());
            fi.generic = f.toGenericString();
            out.add(fi);
        }
        return out;
    }

    static List<MethodInfo> extractMethods(Class<?> cls, boolean includePrivate) {
        Method[] ms = includePrivate ? cls.getDeclaredMethods() : cls.getMethods();
        List<MethodInfo> out = new ArrayList<>();
        for (Method m : ms) {
            MethodInfo mi = new MethodInfo();
            mi.name = m.getName();
            mi.returnType = m.getGenericReturnType().getTypeName();
            mi.modifiers = mods(m.getModifiers());
            Type[] pts = m.getGenericParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                Param p = new Param();
                p.name = "arg" + i; // param names often erased without debug info
                p.type = pts[i].getTypeName();
                mi.parameters.add(p);
            }
            for (Type ex : m.getGenericExceptionTypes()) {
                mi.throwsTypes.add(ex.getTypeName());
            }
            out.add(mi);
        }
        return out;
    }

    // ---------- JSON builder ----------
    static String toJson(String target, List<FieldInfo> fields, List<MethodInfo> methods,
                         Map<String,String> newMethodLogicSpec) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"target\": ").append(js(target)).append(",\n");

        sb.append("  \"fields\": [");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(fields.get(i).toJson());
        }
        sb.append("],\n");

        sb.append("  \"methods\": [");
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(methods.get(i).toJson());
        }
        sb.append("]");

        if (newMethodLogicSpec != null && !newMethodLogicSpec.isEmpty()) {
            sb.append(",\n  \"newMethodLogicSpec\": {");
            int k = 0;
            for (Map.Entry<String,String> e : newMethodLogicSpec.entrySet()) {
                if (k++ > 0) sb.append(",");
                sb.append("\n    ").append(js(e.getKey())).append(": ").append(js(e.getValue()));
            }
            sb.append("\n  }");
        }

        sb.append("\n}\n");
        return sb.toString();
    }

    // ---------- Helpers ----------
    static List<String> mods(int m) {
        List<String> r = new ArrayList<>();
        if (Modifier.isPublic(m))    r.add("public");
        if (Modifier.isProtected(m)) r.add("protected");
        if (Modifier.isPrivate(m))   r.add("private");
        if (Modifier.isStatic(m))    r.add("static");
        if (Modifier.isFinal(m))     r.add("final");
        if (Modifier.isAbstract(m))  r.add("abstract");
        if (Modifier.isSynchronized(m)) r.add("synchronized");
        if (Modifier.isNative(m))    r.add("native");
        if (Modifier.isStrict(m))    r.add("strictfp");
        if (Modifier.isTransient(m)) r.add("transient");
        if (Modifier.isVolatile(m))  r.add("volatile");
        return r;
    }

    static String listJson(List<?> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<xs.size();i++) {
            if (i>0) sb.append(",");
            Object v = xs.get(i);
            if (v == null) sb.append("null");
            else if (v instanceof String s) sb.append(js(s));
            else sb.append(js(String.valueOf(v)));
        }
        sb.append("]");
        return sb.toString();
    }

    static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }

    static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i<0 ? fqn : fqn.substring(i+1);
    }

    static String simpleFromFileName(String file) {
        return file.endsWith(".class") ? file.substring(0, file.length()-6) : file;
    }

    static String modifiersString(List<String> mods) {
        return String.join(" ", mods);
    }

    // Parse method specs, keeping both signature and derived name so we can:
    //  - match existing methods by signature or by name
    //  - record non-existing ones by name into "newMethodLogicSpec"
    static List<MethodSpec> parseMethodSpecsDetailed(List<String> specs) {
        List<MethodSpec> out = new ArrayList<>();
        for (String s : specs) {
            String[] parts = s.split("//", 2);
            String sig = parts[0].trim();
            String logic = (parts.length > 1 ? parts[1].trim() : "");
            if (sig.endsWith(";")) sig = sig.substring(0, sig.length()-1).trim();

            MethodSpec ms = new MethodSpec();
            ms.signature = sig.isEmpty() ? null : sig;
            ms.logic = logic.isEmpty() ? "(no logic provided)" : logic;

            // Derive a method name from the signature or token:
            // Accept plain "methodName" OR "mods returnType methodName(...)" forms.
            String name = null;
            int p = sig.indexOf('(');
            if (p > 0) {
                String left = sig.substring(0, p).trim(); // e.g., "public void incrementNumbers"
                String[] tokens = left.split("\\s+");
                name = tokens.length > 0 ? tokens[tokens.length-1] : null;
            } else {
                // maybe user passed just the name
                name = sig;
            }
            ms.name = name;

            out.add(ms);
        }
        return out;
    }
}
