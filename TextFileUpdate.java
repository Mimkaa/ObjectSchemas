import java.io.*;
import java.nio.file.*;
import java.util.*;

public class TextFileUpdate {
    public static void main(String[] args) {
        // Expected usage: java TextFileUpdate --file <file_name> --line "<new_first_line>"
        if (args.length < 4 || !args[0].equals("--file") || !args[2].equals("--line")) {
            System.out.println("Usage: java TextFileUpdate --file <file_name> --line \"<new_first_line>\"");
            return;
        }

        String fileName = args[1];
        String newLine = args[3];
        Path filePath = Paths.get(fileName);

        try {
            // If file does not exist, create it
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Created file: " + fileName);
            }

            // Read existing lines
            List<String> lines = new ArrayList<>();
            if (Files.size(filePath) > 0) {
                lines = Files.readAllLines(filePath);
            }

            // Update or add first line
            if (lines.isEmpty()) {
                lines.add(newLine);
            } else {
                lines.set(0, newLine);
            }

            // Write updated content
            Files.write(filePath, lines, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            System.out.println("✅ Updated first line of " + fileName + " to: " + newLine);
        } catch (IOException e) {
            System.err.println("❌ Error updating file: " + e.getMessage());
        }
    }
}
