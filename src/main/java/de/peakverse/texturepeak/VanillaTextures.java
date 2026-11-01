package de.peakverse.texturepeak;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Die Vorschaubilder der Standard-Texturen fuer die Auswahl im Web-Editor. Die Bilder gehoeren Mojang und liegen deshalb NICHT in diesem Projekt: der
 * Admin loest den Download per Knopf aus, der Server holt sich das Client-Jar der eigenen Spielversion von den offiziellen Mojang-Servern und legt nur die
 * Item- und Block-Bilder in {@code plugins/TexturePeak/vanilla/} ab.
 */
final class VanillaTextures {
    static final Pattern ENTRY = Pattern.compile("assets/minecraft/textures/(item|block)/([a-z0-9_]+)\\.png");
    static final long MAX_ENTRY = 1L << 20;
    static final int MAX_FILES = 6000;
    private static final long MAX_JAR = 400L << 20;
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Gson GSON = new Gson();

    private final Path dir;
    private final String version;
    private final Logger log;
    private volatile boolean busy;
    private volatile String error = "";

    VanillaTextures(Path dataFolder, String version, Logger log) {
        this.dir = dataFolder.resolve("vanilla");
        this.version = version;
        this.log = log;
    }

    boolean busy() {
        return busy;
    }

    String error() {
        return error;
    }

    String version() {
        return version;
    }

    boolean ready() {
        return Files.isRegularFile(dir.resolve("version.txt"));
    }

    /** Dateinamen ohne Endung, z. B. {@code diamond_sword}. */
    List<String> names(String kind) {
        if (!kind.equals("item") && !kind.equals("block")) return List.of();
        Path d = dir.resolve(kind);
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".png")).map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Die PNG-Datei oder {@code null}; Art und Name werden streng geprueft (kein Ausbrechen aus dem Ordner). */
    Path file(String kind, String name) {
        if (!(kind.equals("item") || kind.equals("block")) || !name.matches("[a-z0-9_]{1,80}")) return null;
        Path p = dir.resolve(kind).resolve(name + ".png");
        return Files.isRegularFile(p) ? p : null;
    }

    /** Startet den Download im Hintergrund; {@code false}, wenn schon einer laeuft. */
    synchronized boolean start() {
        if (busy) return false;
        busy = true;
        error = "";
        Thread t = new Thread(() -> {
            try {
                int n = download();
                log.info("Standard-Texturen geladen: " + n + " Bilder (Spiel " + version + ").");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warning("Standard-Texturen konnten nicht geladen werden: " + error);
            } finally {
                busy = false;
            }
        }, "TexturePeak-vanilla");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private int download() throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
        JsonObject manifest = GSON.fromJson(getString(http, MANIFEST), JsonObject.class);
        String versionUrl = null;
        for (var e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (v.get("id").getAsString().equals(version)) versionUrl = v.get("url").getAsString();
        }
        if (versionUrl == null) throw new IOException("Mojang kennt die Version '" + version + "' nicht.");
        JsonObject info = GSON.fromJson(getString(http, versionUrl), JsonObject.class);
        String jarUrl = info.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        Path jar = Files.createTempFile("texturepeak-client", ".jar");
        try {
            HttpResponse<InputStream> res = http.send(request(jarUrl, 300), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Download: HTTP " + res.statusCode());
            try (InputStream in = res.body()) {
                long copied = 0;
                try (var out = Files.newOutputStream(jar)) {
                    byte[] buf = new byte[65536];
                    for (int r; (r = in.read(buf)) > 0; ) {
                        copied += r;
                        if (copied > MAX_JAR) throw new IOException("Das Client-Jar ist unerwartet gross.");
                        out.write(buf, 0, r);
                    }
                }
            }
            return extract(jar, dir, version);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static HttpRequest request(String url, int seconds) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!"https".equals(uri.getScheme()) || !(host.endsWith(".mojang.com") || host.endsWith(".minecraft.net"))) throw new IOException("Unerwartete Adresse: " + host);
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds)).header("User-Agent", "PeakVerse-TexturePeak").build();
    }

    private static String getString(HttpClient http, String url) throws Exception {
        HttpResponse<String> res = http.send(request(url, 30), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Mojang antwortet mit HTTP " + res.statusCode());
        return res.body();
    }

    /** Legt nur {@code textures/item} und {@code textures/block} als PNG ab; alles andere im Jar wird ignoriert. */
    static int extract(Path jar, Path dir, String version) throws IOException {
        Path tmp = dir.resolveSibling(dir.getFileName() + ".neu");
        deleteTree(tmp);
        Files.createDirectories(tmp.resolve("item"));
        Files.createDirectories(tmp.resolve("block"));
        int count = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = new ArrayList<>(zip.stream().toList());
            for (ZipEntry e : entries) {
                var m = ENTRY.matcher(e.getName());
                if (e.isDirectory() || !m.matches()) continue;
                if (count >= MAX_FILES) throw new IOException("Zu viele Bilder im Jar.");
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] data = in.readNBytes((int) MAX_ENTRY + 1);
                    if (data.length > MAX_ENTRY) continue;
                    Files.write(tmp.resolve(m.group(1)).resolve(m.group(2) + ".png"), data);
                    count++;
                }
            }
        }
        if (count == 0) throw new IOException("Im Client-Jar wurden keine Bilder gefunden.");
        Files.writeString(tmp.resolve("version.txt"), version);
        deleteTree(dir);
        Files.move(tmp, dir, StandardCopyOption.REPLACE_EXISTING);
        return count;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
