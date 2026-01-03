import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CreateReadyFile {

    public static void main(String[] args) {

        File readyFile = new File(System.getProperty("user.dir"), ".ready");

        try {
            // ensure parent exists (normally current dir, but safe)
            File parent = readyFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            try (FileWriter writer = new FileWriter(readyFile, StandardCharsets.UTF_8)) {
                writer.write("READY\n");
            }

            System.out.println("✅ .ready file created at: " + readyFile.getAbsolutePath());

        } catch (IOException e) {
            System.out.println("❌ Failed to create .ready file");
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
