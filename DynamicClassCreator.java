import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Modifier;

/**
 * Creates dynamic classes using Byte Buddy via reflection.
 * Automatically finds a Byte Buddy JAR starting with "byte-buddy".
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

    public byte[] createDynamicClass(String className) throws Exception {
        if (!byteBuddyJar.exists()) {
            throw new IllegalStateException("Byte Buddy JAR not found: " + byteBuddyJar.getAbsolutePath());
        }

        URL jarUrl = byteBuddyJar.toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader())) {

            // Load Byte Buddy classes
            Class<?> byteBuddyClass = Class.forName("net.bytebuddy.ByteBuddy", true, loader);
            Class<?> dynamicTypeBuilderClass = Class.forName("net.bytebuddy.dynamic.DynamicType$Builder", true, loader);
            Class<?> fixedValueClass = Class.forName("net.bytebuddy.implementation.FixedValue", true, loader);
            Class<?> implementationClass = Class.forName("net.bytebuddy.implementation.Implementation", true, loader);
            Class<?> dynamicTypeUnloadedClass = Class.forName("net.bytebuddy.dynamic.DynamicType$Unloaded", true, loader);

            // Instantiate ByteBuddy
            Object byteBuddy = byteBuddyClass.getDeclaredConstructor().newInstance();

            // builder = new ByteBuddy().subclass(Object.class)
            Method subclassMethod = byteBuddyClass.getMethod("subclass", Class.class);
            Object builder = subclassMethod.invoke(byteBuddy, Object.class);

            // builder = builder.name(className)
            Method nameMethod = dynamicTypeBuilderClass.getMethod("name", String.class);
            builder = nameMethod.invoke(builder, className);

            // builder = builder.defineField("name", String.class, Modifier.PRIVATE)
            Method defineFieldMethod = dynamicTypeBuilderClass.getMethod("defineField", String.class, Class.class, int.class);
            builder = defineFieldMethod.invoke(builder, "name", String.class, Modifier.PRIVATE);

            // methodBuilder = builder.defineMethod("sayHello", String.class, Modifier.PUBLIC)
            Method defineMethod = dynamicTypeBuilderClass.getMethod("defineMethod", String.class, Class.class, int.class);
            Object methodBuilder = defineMethod.invoke(builder, "sayHello", String.class, Modifier.PUBLIC);

            // fixedValueInstance = FixedValue.value("Hello from <className>!")
            Method valueMethod = fixedValueClass.getMethod("value", Object.class);
            Object fixedValueInstance = valueMethod.invoke(null, "Hello from " + className + "!");

            // builder = methodBuilder.intercept(fixedValueInstance)
            Method interceptMethod = methodBuilder.getClass().getMethod("intercept", implementationClass);
            builder = interceptMethod.invoke(methodBuilder, fixedValueInstance);

            // unloaded = builder.make()
            Method makeMethod = builder.getClass().getMethod("make");
            Object unloaded = makeMethod.invoke(builder);

            // bytes = unloaded.getBytes()
            Method getBytesMethod = dynamicTypeUnloadedClass.getMethod("getBytes");
            byte[] bytes = (byte[]) getBytesMethod.invoke(unloaded);

            // Save to file
            Path classFile = Path.of(className + ".class");
            try (FileOutputStream fos = new FileOutputStream(classFile.toFile())) {
                fos.write(bytes);
            }

            System.out.println("✅ Generated class: " + className);
            System.out.println("✅ Saved to: " + classFile.toAbsolutePath());

            return bytes;
        }
    }

    public static void main(String[] args) throws Exception {
        String className = "MyGeneratedClass";
        if (args.length >= 2 && "--name".equals(args[0])) {
            className = args[1];
        }

        File jar = findByteBuddyJar(); // automatically find the JAR
        DynamicClassCreator creator = new DynamicClassCreator(jar);
        creator.createDynamicClass(className);
    }
}
