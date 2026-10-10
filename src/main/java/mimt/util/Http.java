package mimt.util;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public final class Http {
    private static HttpClient client;
    public static volatile boolean offline = false;

    private static synchronized HttpClient client() {
        if (client == null)
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        return client;
    }

    public static Path cacheDir() {
        String override = System.getProperty("mimt.cache", System.getenv("MIMT_CACHE"));
        Path p;
        if (override != null && !override.isBlank()) p = Paths.get(override);
        else {
            String appdata = System.getenv("LOCALAPPDATA");
            if (appdata != null) p = Paths.get(appdata, "mimodel-tool", "cache");
            else p = Paths.get(System.getProperty("user.home"), ".cache", "mimodel-tool");
        }
        try { Files.createDirectories(p); } catch (IOException ignored) { }
        return p;
    }

    public static byte[] get(String url) throws IOException {
        if (offline) throw new IOException(I18n.t("Downloading is disabled: ") + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "mimodel-tool/1.0").GET().build();
        try {
            HttpResponse<byte[]> r = client().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + ": " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path cached(String url, String rel, String sha1) throws IOException {
        Path f = cacheDir().resolve(rel);
        if (Files.isRegularFile(f) && (sha1 == null || sha1.equalsIgnoreCase(sha1(Files.readAllBytes(f))))) return f;
        Log.info(I18n.t("Downloading ") + url);
        byte[] data = get(url);
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1(data))) throw new IOException(I18n.t("Checksum mismatch: ") + url);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        return f;
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
