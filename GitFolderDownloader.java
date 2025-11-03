import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

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
        // Sanitize folder string
        remoteFolder = remoteFolder.replace("\\", "/").replace(" ", "%20");
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo
                + "/contents/" + remoteFolder + "?ref=" + branch;

        System.out.println("📡 Fetching file list from " + apiUrl);

        String json;
        try {
            json = new String(new URL(apiUrl).openStream().readAllBytes());
        } catch (FileNotFoundException e) {
            if (!remoteFolder.equals(defaultFolder)) {
                System.out.println("⚠️ Folder not found " + remoteFolder + ". Falling back to default " + defaultFolder);
                downloadFolder(defaultFolder);  // recursive fallback
                return;
            } else {
                System.out.println("❌ Default folder also not found " + defaultFolder);
                return;
            }
        }

        // Simple manual JSON parsing to extract download URLs
        List<String> downloadUrls = new ArrayList<>();
        String[] entries = json.split("\"download_url\":");
        for (int i = 1; i < entries.length; i++) {
            String part = entries[i];
            String url = part.split("\"")[1]; // take the first string after "download_url":
            downloadUrls.add(url);
        }

        if (downloadUrls.isEmpty()) {
            System.out.println("⚠️ No files found in folder " + remoteFolder);
            return;
        }

        // Create folder locally if it doesn't exist
        Path folderPath = Paths.get(remoteFolder);
        if (!Files.exists(folderPath)) {
            Files.createDirectories(folderPath);
        }

        // Download files
        for (String url : downloadUrls) {
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            Path localPath = folderPath.resolve(fileName);
            System.out.println("⬇️ Downloading " + fileName + " to " + localPath);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("✅ Saved to " + localPath);
        }

        System.out.println("🎉 All files from '" + remoteFolder + "' downloaded!");
    }

    public static void main(String[] args) throws IOException {
        String folderName = "PythonStuff";  // default
        for (int i = 0; i < args.length; i++) {
            if ("--name".equals(args[i]) && i + 1 < args.length) {
                folderName = args[i + 1];
            }
        }

        GitFolderDownloader downloader = new GitFolderDownloader("Mimkaa", "ObjectSchemas", "main");
        downloader.downloadFolder(folderName);
        System.out.println("✅ Done downloading folder " + folderName);

        // Move all contents from downloaded folder to current working directory
        Path folderPath = Paths.get(folderName).toAbsolutePath();
        Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
            for (Path file : stream) {
                Path target = currentDir.resolve(file.getFileName());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("📦 Moved " + file + " -> " + target);
            }
        }

        // Delete the now-empty downloaded folder
        Files.delete(folderPath);
        System.out.println("🗑️ Deleted folder " + folderPath);

        // Open a new CMD in current folder and setup Python environment
        System.out.println("📁 Setting up Python environment in folder " + currentDir);
        try {
            String cmd = String.join(" && ",
                    "python -m venv venv",
                    "venv\\Scripts\\activate",
                    "if exist requirements.txt pip install -r requirements.txt",
                    "python Interactive_prompt.py"
            );

            new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/K", "cd /d " + currentDir + " && " + cmd)
                    .start();

        } catch (IOException e) {
            System.out.println("❌ Failed to setup Python environment or run script: " + e.getMessage());
        }
    }
}
