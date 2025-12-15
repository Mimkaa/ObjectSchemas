import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CreateTextFileFromBase64 {

    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("""
                Usage:
                  java CreateTextFileFromBase64 \
                      --name <fileName> \
                      [--path <targetPath>] \
                      --contentB64 <base64Text>

                Example:
                  java CreateTextFileFromBase64 \
                      --name Method \
                      --contentB64 cHVibGljIHN0YXRpYyB2b2lkIGZvbygpIHt9
                """);
            return;
        }

        String fileName = null;
        String targetPath = System.getProperty("user.dir");
        String contentB64 = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                case "--name" -> {
                    if (i + 1 < args.length) {
                        fileName = args[++i];
                    }
                }

                case "--path" -> {
                    if (i + 1 < args.length) {
                        targetPath = args[++i];
                    }
                }

                case "--contentB64" -> {
                    if (i + 1 < args.length) {
                        contentB64 = args[++i];
                    }
                }
            }
        }

        if (fileName == null || fileName.isEmpty()) {
            System.out.println("❌ Error: --name is required");
            return;
        }

        if (contentB64 == null) {
            System.out.println("❌ Error: --contentB64 is required");
            return;
        }

        if (!fileName.endsWith(".txt")) {
            fileName += ".txt";
        }

        File file = new File(targetPath, fileName);

        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }

            byte[] decodedBytes = Base64.getDecoder().decode(contentB64);
            String decodedText = new String(decodedBytes, StandardCharsets.UTF_8);

            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                writer.write(decodedText);
            }

            System.out.println("✅ File created from Base64: " + file.getAbsolutePath());

        } catch (IllegalArgumentException e) {
            System.out.println("❌ Invalid Base64 input");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("❌ Failed to write file: " + file.getAbsolutePath());
            e.printStackTrace();
        }
    }
}
