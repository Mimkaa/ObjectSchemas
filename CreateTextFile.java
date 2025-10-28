import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class CreateTextFile {

    public static void main(String[] args) {
        // Show help if no parameters provided
        if (args.length == 0) {
            System.out.println("""
                Usage:
                  java CreateTextFile --name <fileName> [--path <targetPath>] [--content <textContent>]

                Examples:
                  java CreateTextFile --name notes.txt
                  java CreateTextFile --name todo.txt --path ./projects --content "Buy milk"
                """);
            return;
        }

        String fileName = null;
        String targetPath = "."; // Default: current directory
        String content = "";     // Default: empty file

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name":
                    if (i + 1 < args.length) {
                        fileName = args[++i];
                    }
                    break;
                case "--path":
                    if (i + 1 < args.length) {
                        targetPath = args[++i];
                    }
                    break;
                case "--content":
                    if (i + 1 < args.length) {
                        content = args[++i];
                    }
                    break;
            }
        }

        // Validate required parameter
        if (fileName == null) {
            System.out.println("❌ Error: Missing required parameter --name");
            return;
        }

        // Construct full file path
        File file = new File(targetPath, fileName);

        // Create file and write content
        if (file.exists()) {
            System.out.println("⚠️ File already exists: " + file.getAbsolutePath());
        } else {
            try {
                // Ensure parent directories exist
                file.getParentFile().mkdirs();

                // Write content
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(content);
                }

                System.out.println("✅ File created successfully: " + file.getAbsolutePath());
            } catch (IOException e) {
                System.out.println("❌ Failed to create file: " + file.getAbsolutePath());
                System.out.println("Error: " + e.getMessage());
            }
        }
    }
}
