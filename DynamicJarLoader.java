import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class DynamicJarLoader {

    private URLClassLoader classLoader;

    // Keep a list of URLs added so far
    private final List<URL> urls = new ArrayList<>();

    public DynamicJarLoader() {
        this.classLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
    }

    // Adds JAR URL and recreates the class loader with all URLs so far
    public void loadJarFromUrl(String jarUrl) throws IOException {
        String fileName = jarUrl.substring(jarUrl.lastIndexOf('/') + 1);
        Path localPath = Paths.get(fileName);

        if (!Files.exists(localPath)) {
            System.out.println("Downloading: " + jarUrl);
            Files.copy(new URL(jarUrl).openStream(), localPath);
            System.out.println("Downloaded to: " + localPath);
        }

        URL jarFileUrl = localPath.toUri().toURL();

        if (!urls.contains(jarFileUrl)) {
            urls.add(jarFileUrl);
            recreateClassLoader();
        }
    }

    // Recreate the class loader with all collected URLs
    private void recreateClassLoader() {
        this.classLoader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return classLoader.loadClass(className);
    }

    public static String buildMavenJarUrl(String groupId, String artifactId, String version) {
        String base = "https://repo1.maven.org/maven2";
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        String jarName = artifactId + "-" + version + ".jar";
        return base + "/" + path + "/" + jarName;
    }
}
