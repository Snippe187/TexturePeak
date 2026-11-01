package de.peakverse.texturepeak;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.pack.PackSink;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverrideStoreTest {
    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void anyShapeIsAllowedButItMustBeARealPng() throws Exception {
        assertTrue(OverrideStore.check(png(16, 16)).isEmpty());
        assertTrue(OverrideStore.check(png(256, 166)).isEmpty(), "GUI-Bilder sind nicht quadratisch");
        assertTrue(OverrideStore.check(png(16, 64)).isEmpty(), "Animationen liegen als hohes Bild vor");
        assertFalse(OverrideStore.check(png(3000, 10)).isEmpty());
        assertFalse(OverrideStore.check(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}).isEmpty());
        assertFalse(OverrideStore.check(new byte[0]).isEmpty());
        assertFalse(OverrideStore.check(null).isEmpty());
    }

    @Test
    void saveReadResetAndTheNamesAreChecked(@TempDir Path tmp) throws Exception {
        OverrideStore s = new OverrideStore(tmp);
        byte[] a = png(16, 16);
        s.put("item", "diamond_sword", a);
        s.put("gui", "container/inventory", png(256, 166));
        assertArrayEquals(a, s.get("item", "diamond_sword"));
        assertEquals(List.of("gui/container/inventory", "item/diamond_sword"), s.list());
        assertNull(s.get("block", "stone"));
        assertTrue(s.delete("item", "diamond_sword"));
        assertFalse(s.delete("item", "diamond_sword"));
        assertEquals(List.of("gui/container/inventory"), s.list());

        assertThrows(IllegalArgumentException.class, () -> s.put("item", "../x", a));
        assertThrows(IllegalArgumentException.class, () -> s.put("item", "Big", a));
        assertThrows(IllegalArgumentException.class, () -> s.put("entity", "zombie", a));
        assertThrows(IllegalArgumentException.class, () -> s.put("gui", "../../etc", a));
        assertThrows(IllegalArgumentException.class, () -> s.put("item", "x", new byte[] {1, 2, 3}));
    }

    @Test
    void changesGoIntoThePackAtTheMinecraftPath(@TempDir Path tmp) throws Exception {
        OverrideStore s = new OverrideStore(tmp);
        s.put("item", "apple", png(16, 16));
        s.put("gui", "sprites/hud/heart/full", png(9, 9));
        Map<String, byte[]> files = new LinkedHashMap<>();
        PackSink sink = new PackSink() {
            public void file(String path, byte[] data) {
                files.put(path, data);
            }

            public void optionalFile(String path, byte[] data) {
                files.put(path, data);
            }
        };
        s.contribute(sink, Logger.getAnonymousLogger());
        assertEquals(List.of("assets/minecraft/textures/gui/sprites/hud/heart/full.png", "assets/minecraft/textures/item/apple.png"), files.keySet().stream().sorted().toList());
    }
}