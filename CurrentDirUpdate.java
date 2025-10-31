import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CurrentDirUpdate {
    private static final String FILE_NAME = "CurrentWorkingDir.txt";

    public static void main(String[] args) {
        if (args.length < 2 || !args[0].equals("--dirname")) {
            System.out.println("Usage: java CurrentDirUpdate --dirname <directory_name>");
            return;
        }

        String dirName = args[1];
        Path filePath = Paths.get(FILE_NAME);

        try {
            // If file does not exist, create it
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Created file: " + FILE_NAME);
            }

            // Read existing lines (if any)
            List<String> lines = new ArrayList<>();
            if (Files.size(filePath) > 0) {
                lines = Files.readAllLines(filePath);
            }

            // Update first line or add it if empty
            if (lines.isEmpty()) {
                lines.add(dirName);
            } else {
                lines.set(0, dirName);
            }

            // Write back to file (overwrite existing content)
            Files.write(filePath, lines, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            System.out.println("Updated first line of " + FILE_NAME + " to: " + dirName);
        } catch (IOException e) {
            System.err.println("Error updating file: " + e.getMessage());
        }
    }
}
