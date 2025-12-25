import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Fully reflection-safe DynamicClassCreator for modern Byte Buddy versions.
 *
 * Added: Base64 variants for EVERY CLI parameter (UTF-8 decoded):
 *   --nameB64
 *   --byteBuddyJarB64
 *
 * Plain variants still work:
 *   --name
 *   --byteBuddyJar
 *
 * If neither jar param is provided, it auto-detects byte-buddy*.jar in current dir (original behavior).
 */
public class DynamicClassCreator {

    private final File byteBuddyJar;

    public DynamicClassCreator(File byteBuddyJar) {
        this.byteBuddyJar = byteBuddyJar;
    }

    public static File findByteBuddyJar() {
        File currentDir = new File(".");
        File[] jars = currentDir.listFiles((dir, name) -> name.startsWith("byte-buddy") && name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IllegalStateException("No Byte Buddy JAR found in current directory");
        }
        return jars[0]; // pick the first one found
    }

    /**
     * Recursively finds a method by name in the object class or any of its interfaces/superclasses.
     */
    private static Method findMethodRecursive(Object obj, String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                return cls.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {}
            for (Class<?> iface : cls.getInterfaces()) {
                try {
                    return iface.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException ignored) {}
            }
            cls = cls.getSuperclass();
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * Finds a static method by name in a class.
     */
    private static Method findStaticMethod(Class<?> cls, String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        return cls.getMethod(methodName, paramTypes);
    }

    /**
     * Try to add a single parameter String[] args to the given method builder, using several
     * Byte Buddy API variants:
     *  1) withParameters(Class<?>...)
     *  2) withParameters(List)
     *  3) withParameters(TypeList) + TypeList.ForLoadedTypes
     */
    private static Object addStringArrayParameter(Object methodBuilder, ClassLoader bbLoader) throws Exception {
        Class<?>[] paramTypes = new Class<?>[]{String[].class};

        // Variant 1: withParameters(Class<?>...)
        try {
            Method varargs = methodBuilder.getClass().getMethod("withParameters", Class[].class);
            return varargs.invoke(methodBuilder, (Object) paramTypes);
        } catch (NoSuchMethodException ignore) {}

        // Variant 2: withParameters(List)
        try {
            Method withList = methodBuilder.getClass().getMethod("withParameters", List.class);
            List<Class<?>> asList = Collections.singletonList(String[].class);
            return withList.invoke(methodBuilder, asList);
        } catch (NoSuchMethodException ignore) {}

        // Variant 3: withParameters(TypeList)
        try {
            Class<?> typeList = Class.forName("net.bytebuddy.description.type.TypeList", true, bbLoader);
            Class<?> forLoadedTypes = Class.forName("net.bytebuddy.description.type.TypeList$ForLoadedTypes", true, bbLoader);
            java.lang.reflect.Constructor<?> tlCtor = forLoadedTypes.getConstructor(Class[].class);
            Object tl = tlCtor.newInstance((Object) paramTypes);

            Method withTL = methodBuilder.getClass().getMethod("withParameters", typeList);
            return withTL.invoke(methodBuilder, tl);
        } catch (NoSuchMethodException ignore) {}

        System.out.println("⚠️ Failed to add parameters to main(String[] args); main() will be parameterless.");
        return methodBuilder;
    }

    private static String decodeB64Utf8(String b64) {
        byte[] decoded = Base64.getDecoder().decode(b64);
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    /**
     * Creates a dynamic class with:
     *   - private String name;
     *   - public String sayHello();
     *   - public static void main(String[] args);
     *
     * Returns the generated class bytes.
     */
    public byte[] createDynamicClass(String className) throws Exception {
        if (!byteBuddyJar.exists()) {
            throw new IllegalStateException("Byte Buddy JAR not found: " + byteBuddyJar.getAbsolutePath());
        }

        URL jarUrl = byteBuddyJar.toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader())) {

            Class<?> byteBuddyClass         = Class.forName("net.bytebuddy.ByteBuddy", true, loader);
            Class<?> fixedValueClass        = Class.forName("net.bytebuddy.implementation.FixedValue", true, loader);
            Class<?> implementationClass    = Class.forName("net.bytebuddy.implementation.Implementation", true, loader);
            Class<?> typeDefinitionClass    = Class.forName("net.bytebuddy.description.type.TypeDefinition", true, loader);
            Class<?> typeForLoadedTypeClass = Class.forName("net.bytebuddy.description.type.TypeDescription$ForLoadedType", true, loader);
            Class<?> stubMethodClass        = Class.forName("net.bytebuddy.implementation.StubMethod", true, loader);

            Object byteBuddy = byteBuddyClass.getDeclaredConstructor().newInstance();

            // Subclass Object
            Method subclassMethod = findMethodRecursive(byteBuddy, "subclass", Class.class);
            Object builder = subclassMethod.invoke(byteBuddy, Object.class);

            // Set class name
            Method nameMethod = findMethodRecursive(builder, "name", String.class);
            builder = nameMethod.invoke(builder, className);

            // ------------------------------------------------------------------
            // Define private field 'name'
            // ------------------------------------------------------------------
            try {
                Method defineFieldMethod = findMethodRecursive(
                        builder,
                        "defineField",
                        String.class,
                        typeDefinitionClass,
                        int.class
                );
                Object stringTypeDefinition =
                        typeForLoadedTypeClass.getDeclaredConstructor(Class.class).newInstance(String.class);
                builder = defineFieldMethod.invoke(builder, "name", stringTypeDefinition, Modifier.PRIVATE);
                System.out.println("✅ Defined field 'name' with TypeDefinition");
            } catch (NoSuchMethodException e) {
                Method defineFieldMethod = findMethodRecursive(
                        builder,
                        "defineField",
                        String.class,
                        Class.class,
                        int.class
                );
                builder = defineFieldMethod.invoke(builder, "name", String.class, Modifier.PRIVATE);
                System.out.println("✅ Defined field 'name' with Class");
            }

            // ------------------------------------------------------------------
            // Define public String sayHello()
            // ------------------------------------------------------------------
            Object methodBuilder;

            try {
                Method defineMethod = findMethodRecursive(
                        builder,
                        "defineMethod",
                        String.class,
                        typeDefinitionClass,
                        int.class
                );
                Object stringTypeDefinition =
                        typeForLoadedTypeClass.getDeclaredConstructor(Class.class).newInstance(String.class);
                methodBuilder = defineMethod.invoke(builder, "sayHello", stringTypeDefinition, Modifier.PUBLIC);
                System.out.println("✅ Defined method 'sayHello' with TypeDefinition");
            } catch (NoSuchMethodException e) {
                Method defineMethod = findMethodRecursive(
                        builder,
                        "defineMethod",
                        String.class,
                        Class.class,
                        int.class
                );
                methodBuilder = defineMethod.invoke(builder, "sayHello", String.class, Modifier.PUBLIC);
                System.out.println("✅ Defined method 'sayHello' with Class");
            }

            // Create FixedValue instance - use static method call
            Method valueMethod = findStaticMethod(fixedValueClass, "value", Object.class);
            Object fixedValueInstance = valueMethod.invoke(null, "Hello from " + className + "!");

            // Intercept sayHello()
            Method interceptMethod = findMethodRecursive(methodBuilder, "intercept", implementationClass);
            builder = interceptMethod.invoke(methodBuilder, fixedValueInstance);

            // ------------------------------------------------------------------
            // Define public static void main(String[] args)
            // ------------------------------------------------------------------
            Object mainBuilder;

            try {
                Method defineMethod = findMethodRecursive(
                        builder,
                        "defineMethod",
                        String.class,
                        typeDefinitionClass,
                        int.class
                );

                Object voidTypeDefinition =
                        typeForLoadedTypeClass.getDeclaredConstructor(Class.class).newInstance(void.class);

                mainBuilder = defineMethod.invoke(
                        builder,
                        "main",
                        voidTypeDefinition,
                        Modifier.PUBLIC | Modifier.STATIC
                );
                System.out.println("✅ Defined method 'main' with TypeDefinition");
            } catch (NoSuchMethodException e) {
                Method defineMethod = findMethodRecursive(
                        builder,
                        "defineMethod",
                        String.class,
                        Class.class,
                        int.class
                );
                mainBuilder = defineMethod.invoke(
                        builder,
                        "main",
                        void.class,
                        Modifier.PUBLIC | Modifier.STATIC
                );
                System.out.println("✅ Defined method 'main' with Class");
            }

            // Try to add parameter: String[] args using multiple variants
            mainBuilder = addStringArrayParameter(mainBuilder, loader);

            // ------------------------------------------------------------------
            // Implementation for main: StubMethod (does nothing)
            // ------------------------------------------------------------------
            Object stubImpl;
            try {
                Method stubInstanceMethod = stubMethodClass.getMethod("instance");
                stubImpl = stubInstanceMethod.invoke(null);
            } catch (NoSuchMethodException ex) {
                Field instanceField = stubMethodClass.getField("INSTANCE");
                stubImpl = instanceField.get(null);
            }

            Method interceptMain = findMethodRecursive(mainBuilder, "intercept", implementationClass);
            builder = interceptMain.invoke(mainBuilder, stubImpl);
            System.out.println("✅ Attached StubMethod implementation to main");

            // ------------------------------------------------------------------
            // Build class & write bytes
            // ------------------------------------------------------------------
            Method makeMethod = findMethodRecursive(builder, "make");
            Object dynamicType = makeMethod.invoke(builder);

            Method getBytesMethod = findMethodRecursive(dynamicType, "getBytes");
            byte[] bytes = (byte[]) getBytesMethod.invoke(dynamicType);

            // Save class file
            Path classFile = Path.of(className + ".class");
            try (FileOutputStream fos = new FileOutputStream(classFile.toFile())) {
                fos.write(bytes);
            }

            System.out.println("✅ Generated class: " + className);
            System.out.println("✅ Saved to: " + classFile.toAbsolutePath());
            System.out.println("ℹ️ You can now run:  java " + className);

            return bytes;
        }
    }

    private static void printUsage() {
        System.out.println("""
            DynamicClassCreator — Generate a stub .class using Byte Buddy.

            Usage:
              java DynamicClassCreator --name <ClassName>
              java DynamicClassCreator --nameB64 <base64-utf8-classname>

              (optional override ByteBuddy jar path)
              java DynamicClassCreator --byteBuddyJar <path-to-byte-buddy-jar> --name <ClassName>
              java DynamicClassCreator --byteBuddyJarB64 <base64-utf8-path> --nameB64 <base64-utf8-classname>

            Notes:
              - If no --byteBuddyJar is provided, it auto-finds byte-buddy*.jar in the current directory.
              - *B64 flags decode Base64 as UTF-8 and behave like the non-B64 variant.
            """);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String className = "MyGeneratedClass";

        // Optional jar override; otherwise auto-detect
        String bbJarPath = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];

            switch (a) {
                case "--name" -> {
                    if (i + 1 < args.length) className = args[++i];
                }
                case "--nameB64" -> {
                    if (i + 1 < args.length) className = decodeB64Utf8(args[++i]);
                }
                case "--byteBuddyJar" -> {
                    if (i + 1 < args.length) bbJarPath = args[++i];
                }
                case "--byteBuddyJarB64" -> {
                    if (i + 1 < args.length) bbJarPath = decodeB64Utf8(args[++i]);
                }
                default -> {
                    // ignore unknown
                }
            }
        }

        File jar = (bbJarPath != null && !bbJarPath.isBlank())
                ? new File(bbJarPath)
                : findByteBuddyJar();

        DynamicClassCreator creator = new DynamicClassCreator(jar);
        creator.createDynamicClass(className);
    }
}
