import java.io.*;
import java.nio.file.*;
import java.util.*;

public class TextFileUpdate {
    public static void main(String[] args) {
        if (args.length < 3 || !args[0].equals("--file")) {
            System.out.println("Usage: java TextFileUpdate --file <file_name> <new_first_line>");
            return;
        }

        String fileName = args[1];
        String newLine = args[2];
        Path filePath = Paths.get(fileName);

        try {
            // If file does not exist, create it
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Created file: " + fileName);
            }

            // Read existing lines (if any)
            List<String> lines = new ArrayList<>();
            if (Files.size(filePath) > 0) {
                lines = Files.readAllLines(filePath);
            }

            // Update first line or add it if empty
            if (lines.isEmpty()) {
                lines.add(newLine);
            } else {
                lines.set(0, newLine);
            }

            // Write back to file (overwrite existing content)
            Files.write(filePath, lines, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            System.out.println("Updated first line of " + fileName + " to: " + newLine);
        } catch (IOException e) {
            System.err.println("Error updating file: " + e.getMessage());
        }
    }
}
