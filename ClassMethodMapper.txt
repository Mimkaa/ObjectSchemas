import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * ClassMethodMapper
 *
 * Replaces existing methods in a compiled .class file so that their bodies call
 * mapped methods on a delegate class.
 *
 * No -cp needed for Byte Buddy: this tool loads byte-buddy-*.jar from the current directory.
 *
 * Recommended JDK to run this tool: JDK 17 or JDK 21.
 *
 * Usage:
 *   java ClassMethodMapper \
 *       --classNameToModify <FullyQualifiedClassName> \
 *       --delegateclass <FullyQualifiedDelegateClassName> \
 *       --mapping method1:delegateMethod1,method2:delegateMethod2
 *
 * Example:
 *   java ClassMethodMapper \
 *       --classNameToModify Base \
 *       --delegateclass BaseDelegate \
 *       --mapping doStuff:doStuffNew,hello:helloV2
 *
 * Effect:
 *   Base.doStuff(...) body is replaced to call BaseDelegate.doStuffNew(...)
 *   Base.hello(...)   body is replaced to call BaseDelegate.helloV2(...)
 *
 * Requirements:
 *   - byte-buddy-*.jar in the same directory
 *   - <ClassNameToModify>.class in the current directory (or subfolders matching its package)
 *   - Delegate class available on the default classpath
 */
public class ClassMethodMapper {

    public static void main(String[] args) {
        // allow newer class file versions if needed
        System.setProperty("net.bytebuddy.experimental", "true");

        String classNameToModify = null;
        String delegateClassName = null;
        String mappingArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classNameToModify" -> {
                    if (i + 1 < args.length) classNameToModify = args[++i];
                }
                case "--delegateclass" -> {
                    if (i + 1 < args.length) delegateClassName = args[++i];
                }
                case "--mapping" -> {
                    if (i + 1 < args.length) mappingArg = args[++i];
                }
                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (classNameToModify == null || delegateClassName == null || mappingArg == null) {
            System.err.println("ERROR: Missing required arguments!");
            printUsage();
            return;
        }

        Map<String, String> mapping = parseMapping(mappingArg);
        if (mapping.isEmpty()) {
            System.err.println("ERROR: Parsed mapping is empty. Check your --mapping argument.");
            printUsage();
            return;
        }

        try {
            new ClassMethodMapper().mapMethods(classNameToModify, delegateClassName, mapping);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------
    // Core logic
    // ------------------------------------------------------------
    public void mapMethods(String classNameToModify,
                           String delegateClassName,
                           Map<String, String> mapping) throws Exception {

        // For FQN like "com.example.Base" we expect "com/example/Base.class" relative to cwd
        Path inputClassFile = Path.of(classNameToModify.replace('.', '/') + ".class");
        if (!Files.exists(inputClassFile)) {
            throw new IllegalStateException("Class file not found: " + inputClassFile);
        }

        System.out.println(">>> Target class:   " + classNameToModify);
        System.out.println(">>> Delegate class: " + delegateClassName);
        System.out.println(">>> Mapping:        " + mapping);

        // Load delegate class (runtime reflection)
        Class<?> delegateClass = Class.forName(delegateClassName);

        // If there are instance methods, we might need a no-arg ctor
        Constructor<?> delegateNoArgCtor = null;
        for (Method mm : delegateClass.getDeclaredMethods()) {
            int mods = mm.getModifiers();
            if (Modifier.isPublic(mods)
                    && !Modifier.isStatic(mods)
                    && mm.getDeclaringClass() == delegateClass) {
                try {
                    delegateNoArgCtor = delegateClass.getDeclaredConstructor();
                    delegateNoArgCtor.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    // we'll complain later if we actually need it
                }
                break;
            }
        }

        // Find valid Byte Buddy jar(s)
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

        ClassLoader parent = ClassLoader.getSystemClassLoader();
        File root = new File(".").getCanonicalFile();

        try (URLClassLoader bb = new URLClassLoader(jarUrls, parent)) {

            Class<?> ByteBuddy         = Class.forName("net.bytebuddy.ByteBuddy", true, bb);
            Class<?> DynamicType       = Class.forName("net.bytebuddy.dynamic.DynamicType", true, bb);
            Class<?> Unloaded          = Class.forName("net.bytebuddy.dynamic.DynamicType$Unloaded", true, bb);

            Class<?> ClassFileLocator  = Class.forName("net.bytebuddy.dynamic.ClassFileLocator", true, bb);
            Class<?> ForFolder         = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$ForFolder", true, bb);
            Class<?> ForClassLoader    = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$ForClassLoader", true, bb);
            Class<?> Compound          = Class.forName("net.bytebuddy.dynamic.ClassFileLocator$Compound", true, bb);

            Class<?> TypePool          = Class.forName("net.bytebuddy.pool.TypePool", true, bb);
            Class<?> TypePoolDefault   = Class.forName("net.bytebuddy.pool.TypePool$Default", true, bb);
            Class<?> TypeDescription   = Class.forName("net.bytebuddy.description.type.TypeDescription", true, bb);
            Class<?> MethodDescription = Class.forName("net.bytebuddy.description.method.MethodDescription", true, bb);

            Class<?> ElementMatcher    = Class.forName("net.bytebuddy.matcher.ElementMatcher", true, bb);
            Class<?> ElementMatchers   = Class.forName("net.bytebuddy.matcher.ElementMatchers", true, bb);

            Class<?> MethodCall        = Class.forName("net.bytebuddy.implementation.MethodCall", true, bb);
            Class<?> Implementation    = Class.forName("net.bytebuddy.implementation.Implementation", true, bb);

            // ----- Build COMPOUND ClassFileLocator -----

            Constructor<?> forFolderCtor = ForFolder.getConstructor(File.class);
            Object locatorFolder = forFolderCtor.newInstance(root);

            Method ofSystemLoader = ForClassLoader.getMethod("ofSystemLoader");
            Object locatorSystem = ofSystemLoader.invoke(null);

            Method ofAnyLoader = ForClassLoader.getMethod("of", ClassLoader.class);
            Object locatorBootstrap = ofAnyLoader.invoke(null, new Object[]{ null });

            Object locatorArray = Array.newInstance(ClassFileLocator, 3);
            Array.set(locatorArray, 0, locatorFolder);
            Array.set(locatorArray, 1, locatorSystem);
            Array.set(locatorArray, 2, locatorBootstrap);

            Constructor<?> compoundCtor = Compound.getConstructor(locatorArray.getClass());
            Object locator = compoundCtor.newInstance(locatorArray);

            Method tpOf = TypePoolDefault.getMethod("of", ClassFileLocator);
            Object typePool = tpOf.invoke(null, locator);

            Method describe = TypePool.getMethod("describe", String.class);
            Object resolution = describe.invoke(typePool, classNameToModify);
            Method resolve = resolution.getClass().getMethod("resolve");
            Object targetType = resolve.invoke(resolution);

            Object bbInstance = ByteBuddy.getConstructor().newInstance();
            Method rebase = ByteBuddy.getMethod("rebase", TypeDescription, ClassFileLocator);
            Object builder = rebase.invoke(bbInstance, targetType, locator);

            // ElementMatchers.named
            Method emNamed = ElementMatchers.getMethod("named", String.class);

            // MethodCall factories
            Method mcInvoke     = MethodCall.getMethod("invoke", Method.class);
            Method mcConstruct  = MethodCall.getMethod("construct", Constructor.class);

            int overridden = 0;

            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String targetMethodName   = entry.getKey();
                String delegateMethodName = entry.getValue();

                Method delegateMethod = findCompatibleDelegateMethod(delegateClass, delegateMethodName);
                if (delegateMethod == null) {
                    System.out.println("[WARN] No public delegate method found: " + delegateMethodName);
                    continue;
                }

                System.out.println("[MAP] " + targetMethodName + " -> " + prettySig(delegateMethod));

                int mods = delegateMethod.getModifiers();
                boolean isStatic = Modifier.isStatic(mods);

                // Build Implementation via MethodCall
                Object impl;
                if (isStatic) {
                    // Static: MethodCall.invoke(delegateMethod).withAllArguments()
                    Object methodCall = mcInvoke.invoke(null, delegateMethod);
                    try {
                        Method withAllArgs = methodCall.getClass().getMethod("withAllArguments");
                        methodCall = withAllArgs.invoke(methodCall);
                    } catch (NoSuchMethodException ignored) {
                        // older/newer Byte Buddy variant
                    }
                    impl = methodCall;
                    System.out.println("  [INFO] static -> MethodCall.invoke");
                } else {
                    // Instance: MethodCall.invoke(delegateMethod).onMethodCall(MethodCall.construct(ctor)).withAllArguments()
                    if (delegateNoArgCtor == null) {
                        throw new IllegalStateException(
                                "ERROR: Delegate has instance methods but no no-arg constructor.");
                    }
                    Object invokeCall = mcInvoke.invoke(null, delegateMethod);
                    Object constructCall = mcConstruct.invoke(null, delegateNoArgCtor);
                    Method onMethodCall = invokeCall.getClass().getMethod(
                            "onMethodCall",
                            Class.forName("net.bytebuddy.implementation.MethodCall", true, bb)
                    );
                    Object bound = onMethodCall.invoke(invokeCall, constructCall);
                    try {
                        Method withAllArgs = bound.getClass().getMethod("withAllArguments");
                        bound = withAllArgs.invoke(bound);
                    } catch (NoSuchMethodException ignored) {
                        // older/newer Byte Buddy variant
                    }
                    impl = bound;
                    System.out.println("  [INFO] instance -> MethodCall.invoke(...).onMethodCall(construct(...))");
                }

                // builder.method(ElementMatchers.named(targetMethodName)).intercept(impl)
                Class<?> builderCls = builder.getClass();
                Method methodSel = builderCls.getMethod("method", ElementMatcher);
                Object nameMatcher = emNamed.invoke(null, targetMethodName);
                Object methodBuilder = methodSel.invoke(builder, nameMatcher);

                Method intercept = methodBuilder.getClass().getMethod("intercept", Implementation);
                builder = intercept.invoke(methodBuilder, impl);

                overridden++;
                System.out.println("  [OK] Overrode body of method: " + targetMethodName);
            }

            System.out.println();
            System.out.println("=== SUMMARY ===");
            System.out.println("Successfully remapped " + overridden + " method(s)");

            if (overridden == 0) {
                return;
            }

            // Build class
            Method make = builder.getClass().getMethod("make");
            Object unloaded = make.invoke(builder);

            backupOnce(inputClassFile);

            Method saveIn = Unloaded.getMethod("saveIn", File.class);
            saveIn.invoke(unloaded, root);

            System.out.println("SUCCESS: Overwrote " + inputClassFile.getFileName());

            try {
                ClassLoader fresh = new URLClassLoader(new URL[]{root.toURI().toURL()},
                        ClassLoader.getSystemClassLoader().getParent());
                Class<?> enhanced = Class.forName(classNameToModify, true, fresh);
                System.out.println("SUCCESS: Loaded enhanced class: " + enhanced);
            } catch (Throwable t) {
                System.out.println("WARNING: Post-load smoke test failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private static Map<String, String> parseMapping(String mappingArg) {
        Map<String, String> map = new LinkedHashMap<>();
        if (mappingArg == null || mappingArg.isBlank()) return map;

        String[] pairs = mappingArg.split(",");
        for (String p : pairs) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length != 2) {
                System.out.println("[WARN] Ignoring malformed mapping: " + trimmed);
                continue;
            }
            String target = parts[0].trim();
            String delegate = parts[1].trim();
            if (!target.isEmpty() && !delegate.isEmpty()) {
                map.put(target, delegate);
            }
        }
        return map;
    }

    private static Method findCompatibleDelegateMethod(Class<?> delegateClass, String methodName) {
        for (Method m : delegateClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (!m.getName().equals(methodName)) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            if (m.getDeclaringClass() != delegateClass) continue;
            return m;
        }
        return null;
    }

    private static void backupOnce(Path classFile) throws Exception {
        Path backup = classFile.resolveSibling(classFile.getFileName().toString() + ".mapper.backup");
        if (!Files.exists(backup)) {
            Files.copy(classFile, backup);
            System.out.println("Created backup: " + backup.getFileName());
        }
    }

    private static String prettySig(Method m) {
        return m.getName() + "(" +
                Arrays.stream(m.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(",")) +
                ")";
    }

    public static File[] findValidByteBuddyJars() throws IOException {
        File dir = new File(".");
        File[] all = dir.listFiles((d, name) ->
                name.endsWith(".jar")
                        && name.startsWith("byte-buddy")
                        && !name.contains("sources")
                        && !name.contains("javadoc"));

        if (all == null || all.length == 0) {
            throw new IllegalStateException("ERROR: No byte-buddy*.jar found in current directory!");
        }

        File core = null;
        File agent = null;

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

    private static void printUsage() {
        System.out.println("ClassMethodMapper - Replace bodies of existing methods via delegate class");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java ClassMethodMapper --classNameToModify <FullyQualifiedClassName> "
                + "--delegateclass <FullyQualifiedDelegateClassName> "
                + "--mapping method1:delegateMethod1,method2:delegateMethod2");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java ClassMethodMapper --classNameToModify Base "
                + "--delegateclass BaseDelegate "
                + "--mapping doStuff:doStuffNew,hello:helloV2");
        System.out.println();
    }
}
