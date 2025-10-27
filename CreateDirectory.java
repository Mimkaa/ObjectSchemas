import java.io.File;

public class CreateDirectory {

    public static void main(String[] args) {
        // Show help if no parameters provided
        if (args.length == 0) {
            System.out.println("""
                Usage:
                  java CreateDirectory --name <directoryName> [--path <targetPath>]

                Examples:
                  java CreateDirectory --name testDir
                  java CreateDirectory --name projectA --path ./projects
                """);
            return;
        }

        String dirName = null;
        String targetPath = "."; // Default: current directory

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name":
                    if (i + 1 < args.length) {
                        dirName = args[++i];
                    }
                    break;
                case "--path":
                    if (i + 1 < args.length) {
                        targetPath = args[++i];
                    }
                    break;
            }
        }

        // Validate required parameter
        if (dirName == null) {
            System.out.println("❌ Error: Missing required parameter --name");
            return;
        }

        // Construct full directory path
        File dir = new File(targetPath, dirName);

        // Create directory (and parent folders if necessary)
        if (dir.exists()) {
            System.out.println("⚠️ Directory already exists: " + dir.getAbsolutePath());
        } else if (dir.mkdirs()) {
            System.out.println("✅ Directory created successfully: " + dir.getAbsolutePath());
        } else {
            System.out.println("❌ Failed to create directory: " + dir.getAbsolutePath());
        }
    }
}
