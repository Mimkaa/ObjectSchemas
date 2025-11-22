import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class RunClass {

    public static void main(String[] args) {
        if (args.length < 2 || !args[0].equals("--class")) {
            System.out.println("Usage: java RunClass --class <ClassName>");
            return;
        }

        String className = args[1];

        try {
            // Build the command: java ClassName
            ProcessBuilder pb = new ProcessBuilder("java", className);
            pb.redirectErrorStream(true);

            Process p = pb.start();

            // Read and print output
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(p.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = p.waitFor();
            if (exitCode == 0) {
                System.out.println("Class executed successfully.");
            } else {
                System.out.println("Execution failed with code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error running class: " + e.getMessage());
        }
    }
}
