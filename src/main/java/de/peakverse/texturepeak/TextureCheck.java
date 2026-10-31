package de.peakverse.texturepeak;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

/** Pruefungen fuer neue Eintraege. Alle Methoden liefern verstaendliche Fehlermeldungen (leere Liste = in Ordnung). */
public final class TextureCheck {
    public static final int MAX_ID = 40;
    public static final int MAX_NAME = 50;
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 512;
    public static final int MAX_BYTES = 512 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final Set<Integer> SIZES = Set.of(16, 32, 64, 128, 256, 512);

    private TextureCheck() {}

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z][a-z0-9_]{0," + (MAX_ID - 1) + "}");
    }

    /**
     * @param isItemType sagt, ob ein Bukkit-Name ein Gegenstand ist (nicht jeder Block ist ein Item)
     */
    public static List<String> checkEntry(String id, String material, String name, Predicate<String> isItemType) {
        List<String> errors = new ArrayList<>();
        if (!validId(id)) errors.add("Ungueltige Kennung '" + id + "': 1-" + MAX_ID + " Zeichen, a-z 0-9 _, mit einem Buchstaben beginnen.");
        if (material == null || !material.equals(material.toUpperCase(java.util.Locale.ROOT)) || !isItemType.test(material)) {
            errors.add("'" + material + "' ist kein Item-Typ (Bukkit-Name in Grossbuchstaben, z. B. DIAMOND_CHESTPLATE).");
        }
        if (name == null || name.isBlank()) errors.add("Der Name fehlt.");
        else {
            if (name.length() > MAX_NAME) errors.add("Der Name ist laenger als " + MAX_NAME + " Zeichen.");
            if (name.indexOf('§') >= 0) errors.add("Der Name darf keine Formatierungszeichen (§) enthalten.");
            if (name.chars().anyMatch(Character::isISOControl)) errors.add("Der Name enthaelt Steuerzeichen.");
        }
        return errors;
    }

    /** Prueft eine PNG-Datei: echtes PNG, lesbar, quadratisch, 16/32/64/128/256/512 Pixel, hoechstens 512 KB. */
    public static List<String> checkPng(byte[] data) {
        List<String> errors = new ArrayList<>();
        if (data == null || data.length == 0) return List.of("Die Datei ist leer.");
        if (data.length > MAX_BYTES) errors.add("Die Datei ist groesser als " + MAX_BYTES / 1024 + " KB.");
        if (data.length < PNG_SIGNATURE.length) return List.of("Das ist keine PNG-Datei.");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return List.of("Das ist keine PNG-Datei (falsche Kennung am Dateianfang).");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return List.of("Die PNG-Datei ist beschaedigt: " + e.getMessage());
        }
        if (image == null) return List.of("Die PNG-Datei ist beschaedigt oder in einem unbekannten Format.");
        int w = image.getWidth(), h = image.getHeight();
        if (w != h) errors.add("Die Textur muss quadratisch sein, ist aber " + w + "x" + h + ".");
        else if (!SIZES.contains(w)) errors.add("Erlaubte Kantenlaengen: 16, 32, 64, 128, 256 oder 512 Pixel (ist " + w + ").");
        return errors;
    }
}
