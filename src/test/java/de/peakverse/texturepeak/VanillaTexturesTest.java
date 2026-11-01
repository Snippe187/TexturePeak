package de.peakverse.texturepeak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaTexturesTest {
    @TempDir Path tmp;

    private Path jar(String... names) throws IOException {
        Path jar = tmp.resolve("client.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                z.write(new byte[] {1, 2, 3});
                z.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void extractsOnlyItemBlockAndGuiPngs() throws Exception {
        Path jar = jar("assets/minecraft/textures/item/diamond_sword.png", "assets/minecraft/textures/block/stone.png", "assets/minecraft/textures/entity/zombie.png",
                "assets/minecraft/textures/item/Bad.png", "assets/minecraft/textures/item/sub/x.png", "assets/minecraft/sounds.json", "../evil.png",
                "assets/minecraft/textures/gui/container/inventory.png", "assets/minecraft/textures/gui/sprites/hud/heart/full.png", "assets/minecraft/textures/gui/Bad Name.png");
        Path dir = tmp.resolve("vanilla");
        assertEquals(4, VanillaTextures.extract(jar, dir, "26.2"));
        VanillaTextures v = new VanillaTextures(tmp, "26.2", Logger.getAnonymousLogger());
        assertTrue(v.ready());
        assertEquals(List.of("diamond_sword"), v.names("item"));
        assertEquals(List.of("stone"), v.names("block"));
        assertNotNull(v.file("item", "diamond_sword"));
        assertTrue(v.guiReady());
        assertEquals(List.of("container/inventory", "sprites/hud/heart/full"), v.names("gui"));
        assertNotNull(v.file("gui", "container/inventory"));
        assertNull(v.file("gui", "../item/apple"));
        assertNull(v.file("gui", "container/../../x"));
        assertNull(v.file("gui", "/etc/passwd"));
        assertFalse(Files.exists(tmp.resolve("evil.png")));
    }

    @Test
    void fileRejectsEscapesAndUnknown() throws Exception {
        VanillaTextures.extract(jar("assets/minecraft/textures/item/apple.png"), tmp.resolve("vanilla"), "26.2");
        VanillaTextures v = new VanillaTextures(tmp, "26.2", Logger.getAnonymousLogger());
        assertNull(v.file("item", "../apple"));
        assertNull(v.file("entity", "apple"));
        assertNull(v.file("item", "APPLE"));
        assertNull(v.file("item", "missing"));
        assertTrue(v.names("entity").isEmpty());
    }

    @Test
    void emptyJarIsAnError() throws Exception {
        assertThrows(IOException.class, () -> VanillaTextures.extract(jar("assets/minecraft/lang/de_de.json"), tmp.resolve("vanilla"), "26.2"));
    }
}
