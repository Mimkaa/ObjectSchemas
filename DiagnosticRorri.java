import java.lang.reflect.Method;

public class DiagnosticRorri {
    public static void main(String[] args) throws Exception {
        Class<?> rorriClass = Class.forName("Rorri");
        Object rorri = rorriClass.getDeclaredConstructor().newInstance();
        
        System.out.println("=== Rorri Class Analysis ===");
        System.out.println("Class: " + rorriClass.getName());
        System.out.println("All methods:");
        
        Method[] allMethods = rorriClass.getMethods();
        for (Method method : allMethods) {
            System.out.println("  - " + method.getName() + getMethodSignature(method) + 
                             " (declared in: " + method.getDeclaringClass().getSimpleName() + ")");
        }
        
        System.out.println("\n=== Testing Specific Methods ===");
        
        // Test methods we expect
        String[] testMethods = {
            "sayHello", "getGreeting", "getStatus", "getInstanceInfo", "resetCounter",
            "processText", "calculate", "updateAndGet", "processWithState"
        };
        
        for (String methodName : testMethods) {
            try {
                Method method = findMethod(rorriClass, methodName);
                if (method != null) {
                    System.out.println("✅ " + methodName + getMethodSignature(method) + " - FOUND");
                    // Try to invoke no-arg methods
                    if (method.getParameterCount() == 0) {
                        Object result = method.invoke(rorri);
                        System.out.println("   Result: " + result);
                    }
                } else {
                    System.out.println("❌ " + methodName + " - NOT FOUND");
                }
            } catch (Exception e) {
                System.out.println("❌ " + methodName + " - ERROR: " + e.getMessage());
            }
        }
    }
    
    private static Method findMethod(Class<?> clazz, String methodName) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }
    
    private static String getMethodSignature(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) return "()";
        
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < paramTypes.length; i++) {
            sb.append(paramTypes[i].getSimpleName());
            if (i < paramTypes.length - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }
}