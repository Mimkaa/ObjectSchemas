import java.io.*;
import java.net.*;
import java.nio.file.*;

public class GitFolderDownloader {

    private final String owner;
    private final String repo;
    private final String branch;
    private final String defaultFolder = "PythonStuff";

    public GitFolderDownloader(String owner, String repo, String branch) {
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
    }

    public void downloadFolder(String remoteFolder) throws IOException {
        // sanitize folder string
        remoteFolder = remoteFolder.replace("\\", "/").replace(":", "");
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo
                + "/contents/" + remoteFolder + "?ref=" + branch;

        System.out.println("📡 Fetching file list from: " + apiUrl);

        String json;
        try {
            json = new String(new URL(apiUrl).openStream().readAllBytes());
        } catch (FileNotFoundException e) {
            if (!remoteFolder.equals(defaultFolder)) {
                System.out.println("⚠️ Folder not found: " + remoteFolder + ". Falling back to default: " + defaultFolder);
                downloadFolder(defaultFolder); // recursive fallback
                return;
            } else {
                System.out.println("❌ Default folder also not found: " + defaultFolder);
                return;
            }
        }

        String[] parts = json.split("\"download_url\":\"");
        if (parts.length <= 1) {
            System.out.println("⚠️ No files found in folder: " + remoteFolder);
            return;
        }

        // Create folder locally if it doesn't exist
        Path folderPath = Paths.get(remoteFolder);
        if (!Files.exists(folderPath)) {
            Files.createDirectories(folderPath);
        }

        for (int i = 1; i < parts.length; i++) {
            String url = parts[i].split("\"")[0];
            String fileName = url.substring(url.lastIndexOf("/") + 1);
            Path localPath = folderPath.resolve(fileName);
            System.out.println("⬇️ Downloading: " + fileName + " to " + localPath);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("✅ Saved to " + localPath);
        }

        System.out.println("🎉 All files from '" + remoteFolder + "' downloaded!");
    }

    public static void main(String[] args) throws IOException {
        String folderName = "PythonStuff"; // default
        for (int i = 0; i < args.length; i++) {
            if ("--name".equals(args[i]) && i + 1 < args.length) {
                folderName = args[i + 1];
            }
        }

        GitFolderDownloader downloader = new GitFolderDownloader("Mimkaa", "ObjectSchemas", "main");
        downloader.downloadFolder(folderName);
        System.out.println("✅ Done downloading folder: " + folderName);

        // --- Open a new CMD in the downloaded folder and setup Python environment ---
        Path folderPath = Paths.get(folderName).toAbsolutePath();
        System.out.println("📁 Setting up Python environment in folder: " + folderPath);

        try {
            // Windows batch commands:
            // 1. cd into folder
            // 2. create venv
            // 3. activate venv
            // 4. install requirements.txt if it exists
            // 5. run Interactive_prompt.py
            String cmd = String.join(" && ",
                    "cd /d \"" + folderPath + "\"",
                    "python -m venv venv",
                    "venv\\Scripts\\activate",
                    "if exist requirements.txt pip install -r requirements.txt",
                    "python Interactive_prompt.py"
            );

            ProcessBuilder pb = new ProcessBuilder(
                    "cmd.exe", "/c", "start", "cmd.exe", "/K", cmd
            );
            pb.start();
        } catch (IOException e) {
            System.out.println("❌ Failed to setup Python environment or run script: " + e.getMessage());
        }
    }
}
