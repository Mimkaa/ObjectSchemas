import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * ClassFieldAdder
 *
 * Adds new public fields to an existing compiled .class file.
 * Uses Byte Buddy (loaded dynamically from byte-buddy-*.jar in the current directory).
 *
 * Example usage:
 *   java ClassFieldAdder --classNameToModify Dori ^
 *       --field "items:java.util.ArrayList" ^
 *       --field "age:int:42"
 *
 * Syntax of --field:
 *   name:type[:initialValue]
 *     - name:        field name, e.g. age
 *     - type:        fully qualified type or primitive, e.g. int, double, java.lang.String
 *     - initialValue (optional):
 *          * for primitives: parsed as number / boolean
 *          * for String: used as-is
 *          * "null": null
 */
public class ClassFieldAdder {

    public static void main(String[] args) {
        // Allow Byte Buddy to work with newer classfile versions (Java 24, etc.)
        System.setProperty("net.bytebuddy.experimental", "true");

        if (args.length == 0) {
            printUsage();
            return;
        }

        String classNameToModify = null;
        List<String> fieldSpecs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classNameToModify" -> {
                    if (i + 1 < args.length) classNameToModify = args[++i];
                }
                case "--field" -> {
                    if (i + 1 < args.length) fieldSpecs.add(args[++i]);
                }
            }
        }

        if (classNameToModify == null || fieldSpecs.isEmpty()) {
            System.err.println("ERROR: Missing required arguments!");
            printUsage();
            return;
        }

        try {
            new ClassFieldAdder().addFields(classNameToModify, fieldSpecs);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Simple holder for field spec
    static class FieldSpec {
        String name;
        String typeName;
        String rawInitial;   // may be null
    }

    public void addFields(String classNameToModify, List<String> rawFieldSpecs) throws Exception {
        Path inputClassFile = Path.of(classNameToModify.replace('.', '/') + ".class");
        if (!Files.exists(inputClassFile)) {
            throw new IllegalStateException("Class file not found: " + inputClassFile);
        }

        System.out.println(">>> Target class: " + classNameToModify);

        List<FieldSpec> fields = parseFieldSpecs(rawFieldSpecs);
        if (fields.isEmpty()) {
            System.out.println("No fields to add. Exiting.");
            return;
        }

        File[] bbJars = findValidByteBuddyJars();
        URL[] jarUrls = Arrays.stream(bbJars)
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);

        File root = new File(".").getCanonicalFile();
        ClassLoader parent = ClassLoader.getSystemClassLoader();

        try (URLClassLoader bb = new URLClassLoader(jarUrls, parent)) {

            // Load Byte Buddy classes via this loader
            Class<?> ByteBuddy        = Class.forName("net.bytebuddy.ByteBuddy", true, bb);
            Class<?> DynamicType      = Class.forName("net.bytebuddy.dynamic.DynamicType", true, bb);
            Class<?> Unloaded         = Class.forName("net.bytebuddy.dynamic.DynamicType$Unloaded", true, bb);

            Class<?> ClassFileLocator = Class.forName("net.bytebuddy.dynamic.ClassFileLocator", true, bb);
            Class<?> ForFolder        = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$ForFolder", true, bb);
            Class<?> ForClassLoader   = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$ForClassLoader", true, bb);
            Class<?> Compound         = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$Compound", true, bb);

            Class<?> TypePool         = Class.forName("net.bytebuddy.pool.TypePool", true, bb);
            Class<?> TypePoolDefault  = Class.forName("net.bytebuddy.pool.TypePool$Default", true, bb);
            Class<?> TypeDescription  = Class.forName("net.bytebuddy.description.type.TypeDescription", true, bb);
            Class<?> TypeDefinition   = Class.forName("net.bytebuddy.description.type.TypeDefinition", true, bb);
            Class<?> GenericForLoaded = Class.forName(
                    "net.bytebuddy.description.type.TypeDescription$Generic$OfNonGenericType$ForLoadedType",
                    true,
                    bb
            );

            // ----- Build COMPOUND ClassFileLocator (folder + system + bootstrap) -----
            Object locatorFolder = ForFolder.getConstructor(File.class).newInstance(root);

            Object locatorSystem = ForClassLoader.getMethod("ofSystemLoader")
                    .invoke(null);

            Object locatorBootstrap = ForClassLoader
                    .getMethod("of", ClassLoader.class)
                    .invoke(null, new Object[]{null});

            Object locatorArray = Array.newInstance(ClassFileLocator, 3);
            Array.set(locatorArray, 0, locatorFolder);
            Array.set(locatorArray, 1, locatorSystem);
            Array.set(locatorArray, 2, locatorBootstrap);

            Object locator = Compound
                    .getConstructor(locatorArray.getClass())
                    .newInstance(locatorArray);

            Object typePool = TypePoolDefault
                    .getMethod("of", ClassFileLocator)
                    .invoke(null, locator);

            Object resolution = TypePool
                    .getMethod("describe", String.class)
                    .invoke(typePool, classNameToModify);

            Object targetType = resolution.getClass()
                    .getMethod("resolve")
                    .invoke(resolution);

            // Rebase existing class
            Object bbInstance = ByteBuddy.getConstructor().newInstance();
            Object builder = ByteBuddy
                    .getMethod("rebase", TypeDescription, ClassFileLocator)
                    .invoke(bbInstance, targetType, locator);

            int added = 0;

            for (FieldSpec fs : fields) {
                Class<?> fieldType = resolveType(fs.typeName);
                Object initialValue = parseInitialValue(fieldType, fs.rawInitial);

                System.out.println("[ADD FIELD] " + fs.name + " : " + fieldType.getName() +
                        (fs.rawInitial != null ? (" = " + fs.rawInitial) : ""));

                Class<?> builderCls = builder.getClass();
                int mods = Modifier.PUBLIC; // tweak if you want (public/private/static/etc.)

                Object fieldBuilder;

                try {
                    // Variant 1: defineField(String, Class, int)
                    java.lang.reflect.Method defineField =
                            builderCls.getMethod("defineField", String.class, Class.class, int.class);
                    fieldBuilder = defineField.invoke(builder, fs.name, fieldType, mods);
                } catch (NoSuchMethodException e) {
                    // Variant 2: defineField(String, TypeDefinition, int)
                    Object fieldTypeGeneric = GenericForLoaded
                            .getConstructor(Class.class)
                            .newInstance(fieldType);

                    java.lang.reflect.Method defineField =
                            builderCls.getMethod("defineField", String.class, TypeDefinition, int.class);
                    fieldBuilder = defineField.invoke(builder, fs.name, fieldTypeGeneric, mods);
                }

                // If an initial value is supplied, try fieldBuilder.value(initialValue)
                if (fs.rawInitial != null) {
                    try {
                        java.lang.reflect.Method valueMethod =
                                fieldBuilder.getClass().getMethod("value", Object.class);
                        builder = valueMethod.invoke(fieldBuilder, initialValue);
                    } catch (NoSuchMethodException e) {
                        System.out.println("  [WARN] Could not set initial value via Byte Buddy 'value(...)'. " +
                                "Field will be added without explicit initializer.");
                        builder = fieldBuilder;
                    }
                } else {
                    builder = fieldBuilder;
                }

                added++;
            }

            System.out.println();
            System.out.println("=== SUMMARY ===");
            System.out.println("Successfully added " + added + " field(s)");

            if (added == 0) {
                return;
            }

            // Build class
            java.lang.reflect.Method make = builder.getClass().getMethod("make");
            Object unloaded = make.invoke(builder);

            backupOnce(inputClassFile);

            java.lang.reflect.Method saveIn = Unloaded.getMethod("saveIn", File.class);
            saveIn.invoke(unloaded, root);

            System.out.println("SUCCESS: Overwrote " + inputClassFile.getFileName());
        }
    }

    // ---------------- CLI helpers ----------------

    private static void printUsage() {
        System.out.println("ClassFieldAdder - Add new public fields to an existing class");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java ClassFieldAdder --classNameToModify <FQN> ^");
        System.out.println("      --field \"name:type[:initialValue]\" [--field \"...\"] ...");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java ClassFieldAdder --classNameToModify Dori ^");
        System.out.println("      --field \"age:int:42\" ^");
        System.out.println("      --field \"name:java.lang.String:Rorri\" ^");
        System.out.println("      --field \"items:java.util.ArrayList\"");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - type may be primitive (int, double, boolean, etc.) or FQN (java.lang.String).");
        System.out.println("  - initialValue is optional; use 'null' for null.");
    }

    private static List<FieldSpec> parseFieldSpecs(List<String> specs) {
        List<FieldSpec> list = new ArrayList<>();
        for (String s : specs) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;

            String[] parts = s.split(":", 3);
            if (parts.length < 2) {
                System.err.println("Invalid --field spec (expected name:type[:initial]): " + s);
                continue;
            }
            FieldSpec fs = new FieldSpec();
            fs.name = parts[0].trim();
            fs.typeName = parts[1].trim();
            if (parts.length == 3) {
                fs.rawInitial = parts[2].trim();
            }
            list.add(fs);
        }
        return list;
    }

    // ---------------- Type & value helpers ----------------

    private static Class<?> resolveType(String typeName) throws ClassNotFoundException {
        switch (typeName) {
            case "byte"    -> {
                return byte.class;
            }
            case "short"   -> {
                return short.class;
            }
            case "int"     -> {
                return int.class;
            }
            case "long"    -> {
                return long.class;
            }
            case "float"   -> {
                return float.class;
            }
            case "double"  -> {
                return double.class;
            }
            case "boolean" -> {
                return boolean.class;
            }
            case "char"    -> {
                return char.class;
            }
            default        -> {
                return Class.forName(typeName);
            }
        }
    }

    private static Object parseInitialValue(Class<?> type, String raw) {
        if (raw == null) return null;
        if ("null".equalsIgnoreCase(raw)) return null;

        if (type == String.class) {
            return raw;
        } else if (type == int.class || type == Integer.class) {
            return Integer.parseInt(raw);
        } else if (type == long.class || type == Long.class) {
            return Long.parseLong(raw);
        } else if (type == double.class || type == Double.class) {
            return Double.parseDouble(raw);
        } else if (type == float.class || type == Float.class) {
            return Float.parseFloat(raw);
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(raw);
        } else if (type == short.class || type == Short.class) {
            return Short.parseShort(raw);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.parseByte(raw);
        } else if (type == char.class || type == Character.class) {
            if (raw.length() != 1) {
                throw new IllegalArgumentException("Initial value for char must be a single character: " + raw);
            }
            return raw.charAt(0);
        }

        // Fallback: keep as String, Byte Buddy may reject incompatible types at runtime
        return raw;
    }

    // ---------------- Byte Buddy jar discovery (same style as your ClassMethodAdder) ----------------

    public static File[] findValidByteBuddyJars() throws IOException {
        File dir = new File(".");
        File[] all = dir.listFiles((d, name) ->
                name.endsWith(".jar") &&
                        name.startsWith("byte-buddy") &&
                        !name.contains("sources") &&
                        !name.contains("javadoc"));

        if (all == null || all.length == 0) {
            throw new IllegalStateException("ERROR: No byte-buddy*.jar found in current directory!");
        }

        File core = null, agent = null;

        for (File f : all) {
            String n = f.getName();
            boolean isCoreCandidate = n.matches("^byte-buddy-\\d+.*\\.jar$") && !n.startsWith("byte-buddy-agent");
            boolean isAgentCandidate = n.matches("^byte-buddy-agent-\\d+.*\\.jar$");
            if (isCoreCandidate && jarContains(f, "net/bytebuddy/dynamic/ClassFileLocator.class")) {
                core = f;
            } else if (isAgentCandidate) {
                agent = f;
            }
        }

        if (core == null) {
            String found = Arrays.stream(all).map(File::getName).collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "ERROR: Could not find a VALID core Byte Buddy jar containing net/bytebuddy/dynamic/ClassFileLocator.class. " +
                            "Found: [" + found + "].");
        }

        if (agent != null) {
            System.out.println("Using Byte Buddy jars: [" + core.getName() + ", " + agent.getName() + "]");
            return new File[]{core, agent};
        } else {
            System.out.println("Using Byte Buddy jar: [" + core.getName() + "]");
            return new File[]{core};
        }
    }

    private static boolean jarContains(File jar, String entry) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            return jf.getEntry(entry) != null;
        }
    }

    private static void backupOnce(Path classFile) throws Exception {
        Path backup = classFile.resolveSibling(classFile.getFileName().toString() + ".backup");
        if (!Files.exists(backup)) {
            Files.copy(classFile, backup);
            System.out.println("Created backup: " + backup.getFileName());
        }
    }
}
