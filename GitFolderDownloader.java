import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class GitFolderDownloader {

    private final String owner;
    private final String repo;
    private final String branch;

    public GitFolderDownloader(String owner, String repo, String branch) {
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
    }

    public void downloadFolder(String remoteFolder) throws IOException {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + remoteFolder + "?ref=" + branch;
        System.out.println("📡 Fetching file list from: " + apiUrl);

        String json = new String(new URL(apiUrl).openStream().readAllBytes());
        // Very simple "parsing" of download_url from JSON
        String[] parts = json.split("\"download_url\":\"");
        for (int i = 1; i < parts.length; i++) {
            String url = parts[i].split("\"")[0];
            String fileName = url.substring(url.lastIndexOf("/") + 1);
            Path localPath = Paths.get(fileName);
            System.out.println("⬇️ Downloading: " + fileName);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("✅ Saved to " + localPath);
        }
    }

    public static void main(String[] args) throws IOException {
        String folderName = "PythonStuff"; // default
        for (int i = 0; i < args.length; i++) {
            if ("--name".equals(args[i]) && i + 1 < args.length) folderName = args[i + 1];
        }
        GitFolderDownloader downloader = new GitFolderDownloader("Mimkaa", "ObjectSchemas", "main");
        downloader.downloadFolder(folderName);
        System.out.println("✅ Done downloading folder: " + folderName);
    }
}
