import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Responsible for generating dynamic classes using Byte Buddy.
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

    /**
     * Creates a dynamic class with a private field 'name' and a public 'sayHello' method.
     * Returns the generated class bytes.
     */
    public byte[] createDynamicClass(String className) throws Exception {
        if (!byteBuddyJar.exists()) {
            throw new IllegalStateException("Byte Buddy JAR not found: " + byteBuddyJar.getAbsolutePath());
        }

        URL jarUrl = byteBuddyJar.toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader())) {

            Class<?> byteBuddyClass = Class.forName("net.bytebuddy.ByteBuddy", true, loader);
            Class<?> fixedValueClass = Class.forName("net.bytebuddy.implementation.FixedValue", true, loader);
            Class<?> dynamicTypeClass = Class.forName("net.bytebuddy.dynamic.DynamicType", true, loader);

            Object byteBuddy = byteBuddyClass.getDeclaredConstructor().newInstance();

            Method subclass = byteBuddyClass.getMethod("subclass", Class.class);
            Object subclassStep = subclass.invoke(byteBuddy, Object.class);

            Method nameMethod = subclassStep.getClass().getMethod("name", String.class);
            Object namedStep = nameMethod.invoke(subclassStep, className);

            Method defineField = namedStep.getClass().getMethod("defineField", String.class, Class.class, int.class);
            Object withField = defineField.invoke(namedStep, "name", String.class, java.lang.reflect.Modifier.PRIVATE);

            Method defineMethod = withField.getClass().getMethod("defineMethod", String.class, Class.class, int.class);
            Object methodStep = defineMethod.invoke(withField, "sayHello", String.class, java.lang.reflect.Modifier.PUBLIC);

            Method fixedValue = fixedValueClass.getMethod("value", Object.class);
            Object fixedValueInstance = fixedValue.invoke(null, "Hello from " + className + "!");

            Method intercept = methodStep.getClass().getMethod("intercept",
                    Class.forName("net.bytebuddy.implementation.Implementation", true, loader));
            Object intercepted = intercept.invoke(methodStep, fixedValueInstance);

            Method make = intercepted.getClass().getMethod("make");
            Object unloaded = make.invoke(intercepted);

            Method getBytes = dynamicTypeClass.getMethod("getBytes");
            byte[] bytes = (byte[]) getBytes.invoke(unloaded);

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
