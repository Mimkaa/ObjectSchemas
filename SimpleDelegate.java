/**
 * Simple delegate class for testing ClassMethodAdder
 */
public class SimpleDelegate {
    
    // Instance field to demonstrate state
    private int callCount = 0;

    // ------------------------------------------------------------
    // Basic instance methods
    // ------------------------------------------------------------

    public String getGreeting() {
        return "Hello from SimpleDelegate!";
    }

    public String processText(String input) {
        return "Processed: " + input.toUpperCase();
    }

    public int calculate(int a, int b) {
        return a + b;
    }

    public String getStatus() {
        return "System is working!";
    }

    public String getInstanceInfo() {
        callCount++;
        return "Instance: " + this.getClass().getSimpleName() + 
               " | Calls: " + callCount + 
               " | Hash: " + System.identityHashCode(this);
    }

    public String updateAndGet(String newData) {
        callCount++;
        return "Updated: '" + newData + "' | Total calls: " + callCount;
    }

    public String processWithState(String data) {
        callCount++;
        return "Data: '" + data + "' | Processed by: " + this.getClass().getSimpleName() + 
               " | Call #" + callCount;
    }

    public String resetCounter() {
        int oldCount = callCount;
        callCount = 0;
        return "Counter reset from " + oldCount + " to 0";
    }


    // ------------------------------------------------------------
    // NEW: Static methods
    // ------------------------------------------------------------

    public static int staticSum(int a, int b) {
        return a + b;
    }

    public static String staticHello() {
        return "Hello from static method!";
    }

    public static double staticMultiply(double x, double y) {
        return x * y;
    }


    // ------------------------------------------------------------
    // NEW: Void methods
    // ------------------------------------------------------------

    public void doNothing() {
        // intentionally empty
    }

    public void increment() {
        callCount++;
    }


    // ------------------------------------------------------------
    // NEW: Methods returning complex objects
    // ------------------------------------------------------------

    public java.util.List<String> makeList(String a, String b) {
        return java.util.Arrays.asList(a, b);
    }

    public java.util.Map<String, Integer> makeMap(int a, int b) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        map.put("a", a);
        map.put("b", b);
        return map;
    }


    // ------------------------------------------------------------
    // NEW: Overloaded methods
    // ------------------------------------------------------------

    public String overload(String s) {
        return "String version: " + s;
    }

    public String overload(int x) {
        return "Int version: " + x;
    }

    public String overload(String a, String b) {
        return "Two-strings version: " + a + ", " + b;
    }


    // ------------------------------------------------------------
    // NEW: Varargs method
    // ------------------------------------------------------------

    public String joinStrings(String... parts) {
        return String.join("|", parts);
    }


    // ------------------------------------------------------------
    // NEW: Method throwing exception
    // ------------------------------------------------------------

    public String riskyOperation(int n) throws Exception {
        if (n < 0) throw new Exception("Negative number!");
        return "OK: " + n;
    }


    // ------------------------------------------------------------
    // NEW: Method with many parameters
    // ------------------------------------------------------------

    public String everything(int a, double b, String c, boolean d) {
        return "a=" + a + ", b=" + b + ", c=" + c + ", d=" + d;
    }
}
