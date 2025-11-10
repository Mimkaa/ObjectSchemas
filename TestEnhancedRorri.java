import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class TestEnhancedRorri {
    public static void main(String[] args) throws Exception {
        // Load the rewritten Rorri.class using a fresh URLClassLoader
        URL classUrl = new URL("file:./"); // current directory
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classUrl})) {
            Class<?> enhancedRorri = loader.loadClass("Rorri");
            Object rorri = enhancedRorri.getDeclaredConstructor().newInstance();

            System.out.println("=== Testing Enhanced Rorri ===");

            // Test original method
            try {
                Method sayHello = enhancedRorri.getMethod("sayHello");
                System.out.println("sayHello: " + sayHello.invoke(rorri));
            } catch (NoSuchMethodException e) {
                System.out.println("No sayHello method found");
            }

            // Test delegate methods safely
            String[] delegateMethods = {
                "getGreeting",
                "processText",
                "calculate",
                "getStatus",
                "getInstanceInfo",
                "updateAndGet",
                "processWithState",
                "resetCounter"
            };

            for (String name : delegateMethods) {
                try {
                    Method m;
                    if (name.equals("processText") || name.equals("processWithState") || name.equals("updateAndGet")) {
                        m = enhancedRorri.getMethod(name, String.class);
                        System.out.println(name + ": " + m.invoke(rorri, "test"));
                    } else if (name.equals("calculate")) {
                        m = enhancedRorri.getMethod(name, int.class, int.class);
                        System.out.println(name + ": " + m.invoke(rorri, 5, 3));
                    } else {
                        m = enhancedRorri.getMethod(name);
                        System.out.println(name + ": " + m.invoke(rorri));
                    }
                } catch (NoSuchMethodException e) {
                    System.out.println("Method " + name + " not found.");
                }
            }
        }
    }
}
