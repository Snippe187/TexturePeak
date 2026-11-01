package de.peakverse.texturepeak;

import de.peakverse.core.api.pack.PackSink;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Eigene Aenderungen an den Standard-Texturen (Gegenstaende, Bloecke, GUI): ein Bild, das im Pack an der Stelle der Minecraft-Textur liegt und sie ersetzt
 * ({@code assets/minecraft/textures/item/diamond_sword.png}). Gespeichert unter {@code plugins/TexturePeak/overrides/<Art>/<Name>.png}.
 * "Zuruecksetzen" loescht die Datei, dann gilt wieder das Basis-Pack oder das Standard-Bild. Bukkit-frei.
 */
public final class OverrideStore {
    public static final int MAX_FILES = 600;
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_SIDE = 2048;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final Path dir;

    public OverrideStore(Path dataFolder) {
        this.dir = dataFolder.resolve("overrides");
    }

    /** {@code kind}: item, block oder gui. {@code name}: bei gui mit Unterordnern. */
    static boolean validName(String kind, String name) {
        if (kind == null || name == null) return false;
        return switch (kind) {
            case "item", "block" -> name.matches("[a-z0-9_]{1,80}");
            case "gui" -> VanillaTextures.validGuiName(name);
            default -> false;
        };
    }

    /** Der Pfad im Pack, den diese Datei ersetzt. */
    public static String packPath(String kind, String name) {
        return "assets/minecraft/textures/" + kind + "/" + name + ".png";
    }

    private Path file(String kind, String name) {
        if (!validName(kind, name)) throw new IllegalArgumentException("Ungueltiger Textur-Name.");
        Path p = dir.resolve(kind).resolve(name + ".png").normalize();
        if (!p.startsWith(dir.resolve(kind))) throw new IllegalArgumentException("Ungueltiger Textur-Name.");
        return p;
    }

    /** Alle Aenderungen als {@code art/name}, sortiert. */
    public List<String> list() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith(".png")) out.add(rel.substring(0, rel.length() - 4));
            }
        }
        return out;
    }

    public byte[] get(String kind, String name) throws IOException {
        Path p = file(kind, name);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    /** Prueft ein Bild fuer die Ersetzung einer Standard-Textur: echtes PNG, lesbar, nicht riesig; jede Form ist erlaubt (GUI-Bilder sind nicht quadratisch). */
    public static List<String> check(byte[] data) {
        if (data == null || data.length == 0) return List.of("Die Datei ist leer.");
        if (data.length > MAX_BYTES) return List.of("Die Datei ist groesser als " + MAX_BYTES / 1024 + " KB.");
        if (data.length < PNG.length) return List.of("Das ist keine PNG-Datei.");
        for (int i = 0; i < PNG.length; i++) if (data[i] != PNG[i]) return List.of("Das ist keine PNG-Datei (falsche Kennung am Dateianfang).");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return List.of("Die PNG-Datei ist beschaedigt: " + e.getMessage());
        }
        if (image == null) return List.of("Die PNG-Datei ist beschaedigt oder in einem unbekannten Format.");
        if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) return List.of("Das Bild ist zu gross (hoechstens " + MAX_SIDE + " Pixel je Seite).");
        return List.of();
    }

    public void put(String kind, String name, byte[] png) throws IOException {
        List<String> errors = check(png);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        Path p = file(kind, name);
        if (!Files.exists(p) && list().size() >= MAX_FILES) throw new IllegalArgumentException("Es sind schon " + MAX_FILES + " Aenderungen gespeichert.");
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, png);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    /** @return {@code true}, wenn es die Aenderung gab */
    public boolean delete(String kind, String name) throws IOException {
        return Files.deleteIfExists(file(kind, name));
    }

    /** Legt alle Aenderungen ins Pack (sie ersetzen die Standard-Texturen). */
    public void contribute(PackSink sink, java.util.logging.Logger log) {
        try {
            for (String n : list()) {
                int slash = n.indexOf('/');
                String kind = n.substring(0, slash), name = n.substring(slash + 1);
                if (!validName(kind, name)) continue;
                try {
                    sink.file(packPath(kind, name), Files.readAllBytes(file(kind, name)));
                } catch (IOException | RuntimeException e) {
                    log.warning("Aenderung " + n + " uebersprungen: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warning("Aenderungen konnten nicht gelesen werden: " + e.getMessage());
        }
    }
}
