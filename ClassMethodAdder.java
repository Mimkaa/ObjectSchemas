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
 * ClassMethodAdder
 *
 * Adds all missing public methods from a delegate class to an existing compiled .class file.
 * No -cp needed for Byte Buddy: this tool loads byte-buddy-*.jar from the current directory.
 *
 * Recommended JDK to run this tool: JDK 17 or JDK 21.
 */
public class ClassMethodAdder {

    public static void main(String[] args) {
        if (args.length != 4) {
            printUsage();
            return;
        }

        String classNameToModify = null;
        String delegateClassName = null;

        for (int i = 0; i < args.length; i++) {
            if ("--classNameToModify".equals(args[i]) && i + 1 < args.length) {
                classNameToModify = args[++i];
            } else if ("--delegateclass".equals(args[i]) && i + 1 < args.length) {
                delegateClassName = args[++i];
            }
        }

        if (classNameToModify == null || delegateClassName == null) {
            System.err.println("ERROR: Missing required arguments!");
            printUsage();
            return;
        }

        try {
            new ClassMethodAdder().addAllMethods(classNameToModify, delegateClassName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addAllMethods(String classNameToModify, String delegateClassName) throws Exception {
        Path inputClassFile = Path.of(classNameToModify.replace('.', '/') + ".class");
        if (!Files.exists(inputClassFile)) {
            throw new IllegalStateException("Class file not found: " + inputClassFile);
        }

        System.out.println(">>> Target class: " + classNameToModify);
        System.out.println(">>> Delegate class: " + delegateClassName);

        // Load delegate class (metadata only)
        Class<?> delegateClass = Class.forName(delegateClassName);

        // If there are instance methods, we will need a no-arg ctor
        Constructor<?> delegateNoArgCtor = null;
        for (Method mm : delegateClass.getDeclaredMethods()) {
            if (Modifier.isPublic(mm.getModifiers()) && !Modifier.isStatic(mm.getModifiers())
                    && mm.getDeclaringClass() == delegateClass) {
                try {
                    delegateNoArgCtor = delegateClass.getDeclaredConstructor();
                    delegateNoArgCtor.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    // handled later with a clear message
                }
                break;
            }
        }

        // Find valid Byte Buddy jar(s)
        File[] bbJars = findValidByteBuddyJars();
        URL[] jarUrls = Arrays.stream(bbJars)
                .map(f -> {
                    try { return f.toURI().toURL(); } catch (Exception e) { throw new RuntimeException(e); }
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
            Class<?> ForLoadedMethod   = Class.forName("net.bytebuddy.description.method.MethodDescription$ForLoadedMethod", true, bb);
            Class<?> TypeDef           = Class.forName("net.bytebuddy.description.type.TypeDefinition", true, bb);
            Class<?> TypeDescForLoaded = Class.forName("net.bytebuddy.description.type.TypeDescription$ForLoadedType", true, bb);
            Class<?> GenericForLoaded  = Class.forName("net.bytebuddy.description.type.TypeDescription$Generic$OfNonGenericType$ForLoadedType", true, bb);

            // Implementation: MethodCall only
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

            // Existing method signatures (name + descriptor)
            Set<String> existing = new HashSet<>();
            Method getDeclaredMethods = TypeDescription.getMethod("getDeclaredMethods");
            Object methodList = getDeclaredMethods.invoke(targetType);
            Method toArray = methodList.getClass().getMethod("toArray", Object[].class);
            Object emptyMDArray = Array.newInstance(MethodDescription, 0);
            Object resultArr = toArray.invoke(methodList, (Object) emptyMDArray);
            int len = Array.getLength(resultArr);
            Method mdGetInternalName = MethodDescription.getMethod("getInternalName");
            Method mdGetDescriptor = MethodDescription.getMethod("getDescriptor");
            for (int i = 0; i < len; i++) {
                Object md = Array.get(resultArr, i);
                String n = (String) mdGetInternalName.invoke(md);
                String d = (String) mdGetDescriptor.invoke(md);
                existing.add(n + d);
            }

            Object bbInstance = ByteBuddy.getConstructor().newInstance();
            Method rebase = ByteBuddy.getMethod("rebase", TypeDescription, ClassFileLocator);
            Object builder = rebase.invoke(bbInstance, targetType, locator);

            Constructor<?> forLoadedCtor = ForLoadedMethod.getConstructor(Method.class);
            Method getDesc = ForLoadedMethod.getMethod("getDescriptor");

            // MethodCall factories
            Method mcInvoke     = MethodCall.getMethod("invoke", Method.class);
            Method mcConstruct  = MethodCall.getMethod("construct", Constructor.class);

            int added = 0;

            for (Method m : delegateClass.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (!Modifier.isPublic(mods)) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.getDeclaringClass() != delegateClass) continue;

                Object forLoaded = forLoadedCtor.newInstance(m);
                String desc = (String) getDesc.invoke(forLoaded);
                String key = m.getName() + desc;

                if (existing.contains(key)) {
                    System.out.println("[SKIP] Already exists: " + prettySig(m));
                    continue;
                }

                System.out.println("[ADD] " + prettySig(m));

                // IMPORTANT: do not cache defineMethod; get it from the current builder adapter class
                Class<?> builderCls = builder.getClass();
                Method defineMethod = builderCls.getMethod("defineMethod", String.class, TypeDef, int.class);

                Object returnTypeGeneric = GenericForLoaded.getConstructor(Class.class)
                        .newInstance(m.getReturnType());

                Object methodBuilder = defineMethod.invoke(
                        builder,
                        m.getName(),
                        returnTypeGeneric,
                        Modifier.PUBLIC | (Modifier.isStatic(mods) ? Modifier.STATIC : 0)
                );

                // Only call withParameters if there are parameters
                if (m.getParameterCount() > 0) {
                    methodBuilder = applyWithParameters(methodBuilder, bb, m.getParameterTypes());
                }

                // Build Implementation via MethodCall
                Object impl;
                if (Modifier.isStatic(mods)) {
                    // Static: MethodCall.invoke(m).withAllArguments()
                    Object methodCall = mcInvoke.invoke(null, m);
                    try {
                        Method withAllArgs = methodCall.getClass().getMethod("withAllArguments");
                        methodCall = withAllArgs.invoke(methodCall);
                    } catch (NoSuchMethodException ignored) { }
                    impl = methodCall;
                    System.out.println("  [INFO] static -> MethodCall.invoke");
                } else {
                    // Instance: MethodCall.invoke(m).onMethodCall(MethodCall.construct(ctor)).withAllArguments()
                    if (delegateNoArgCtor == null) {
                        throw new IllegalStateException("ERROR: Delegate has instance methods but no no-arg constructor.");
                    }
                    Object invokeCall = mcInvoke.invoke(null, m);
                    Object constructCall = mcConstruct.invoke(null, delegateNoArgCtor);
                    Method onMethodCall = invokeCall.getClass().getMethod(
                            "onMethodCall",
                            Class.forName("net.bytebuddy.implementation.MethodCall", true, bb)
                    );
                    Object bound = onMethodCall.invoke(invokeCall, constructCall);
                    try {
                        Method withAllArgs = bound.getClass().getMethod("withAllArguments");
                        bound = withAllArgs.invoke(bound);
                    } catch (NoSuchMethodException ignored) { }
                    impl = bound;
                    System.out.println("  [INFO] instance -> MethodCall.invoke(...).onMethodCall(construct(...))");
                }

                // IMPORTANT: do not cache intercept; obtain it from the current adapter class
                Method interceptMethod = methodBuilder.getClass().getMethod(
                        "intercept",
                        Implementation
                );
                builder = interceptMethod.invoke(methodBuilder, impl);

                added++;
                System.out.println("  [OK] Added: " + prettySig(m));
            }

            System.out.println();
            System.out.println("=== SUMMARY ===");
            System.out.println("Successfully added " + added + " new methods");

            if (added == 0) {
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

    /**
     * Apply parameters to a method definition builder across Byte Buddy API variants.
     * Tries, in order:
     *  1) withParameters(Class<?>...)
     *  2) withParameters(java.util.List) where items are java.lang.reflect.Type (Class<?> implements Type)
     *  3) withParameters(TypeList) using TypeList.ForLoadedTypes(Class<?>...)
     */
    private static Object applyWithParameters(Object methodBuilder, ClassLoader bb, Class<?>[] paramTypes) throws Exception {
        // Variant 1: withParameters(Class<?>...)
        try {
            Method varargs = methodBuilder.getClass().getMethod("withParameters", Class[].class);
            return varargs.invoke(methodBuilder, (Object) paramTypes);
        } catch (NoSuchMethodException ignore) {
        }

        // Variant 2: withParameters(java.util.List) of java.lang.reflect.Type (Class<?> implements Type)
        try {
            Method withList = methodBuilder.getClass().getMethod("withParameters", List.class);
            List<Class<?>> asList = Arrays.asList(paramTypes);
            return withList.invoke(methodBuilder, asList);
        } catch (NoSuchMethodException ignore) {
        }

        // Variant 3: withParameters(TypeList) using TypeList.ForLoadedTypes(Class<?>...)
        Class<?> typeList = Class.forName("net.bytebuddy.description.type.TypeList", true, bb);
        Class<?> forLoadedTypes = Class.forName("net.bytebuddy.description.type.TypeList$ForLoadedTypes", true, bb);
        Constructor<?> tlCtor = forLoadedTypes.getConstructor(Class[].class);
        Object tl = tlCtor.newInstance((Object) paramTypes);
        Method withTL = methodBuilder.getClass().getMethod("withParameters", typeList);
        return withTL.invoke(methodBuilder, tl);
    }

    private static void backupOnce(Path classFile) throws Exception {
        Path backup = classFile.resolveSibling(classFile.getFileName().toString() + ".backup");
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

    private static void printUsage() {
        System.out.println("ClassMethodAdder - Add all public methods from delegate to an existing class");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java ClassMethodAdder --classNameToModify <FQN> --delegateclass <FQN>");
        System.out.println();
        System.out.println("Requirements:");
        System.out.println("  - byte-buddy-*.jar must be in the same directory");
        System.out.println("  - <ClassName>.class must be in the current directory");
        System.out.println("  - Delegate class must be on the default classpath");
    }
}
