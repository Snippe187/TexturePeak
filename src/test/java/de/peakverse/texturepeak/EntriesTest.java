package de.peakverse.texturepeak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.pack.PackSink;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntriesTest {
    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static boolean item(String name) {
        return name.equals("DIAMOND_CHESTPLATE") || name.equals("STICK");
    }

    private Path dir;
    private long now;
    private EntryStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        dir = tmp;
        now = 1000;
        store = new EntryStore(dir, () -> now++);
    }

    // ---- Pruefungen ----

    @Test
    void entryChecksAcceptGoodAndExplainBadValues() {
        assertTrue(TextureCheck.checkEntry("rubin_brust", "DIAMOND_CHESTPLATE", "Rubin Brustplatte", EntriesTest::item).isEmpty());
        assertEquals(1, TextureCheck.checkEntry("Rubin", "STICK", "x", EntriesTest::item).size(), "Grossbuchstaben in der Kennung");
        assertEquals(1, TextureCheck.checkEntry("a", "BANANE", "x", EntriesTest::item).size(), "kein Item-Typ");
        assertEquals(1, TextureCheck.checkEntry("a", "stick", "x", EntriesTest::item).size(), "Typ muss gross geschrieben sein");
        assertFalse(TextureCheck.checkEntry("a", "STICK", " ", EntriesTest::item).isEmpty());
        assertFalse(TextureCheck.checkEntry("a", "STICK", "§cRot", EntriesTest::item).isEmpty());
        assertFalse(TextureCheck.checkEntry("a", "STICK", "x".repeat(TextureCheck.MAX_NAME + 1), EntriesTest::item).isEmpty());
        assertFalse(TextureCheck.validId("../x"));
        assertFalse(TextureCheck.validId("1abc"));
    }

    @Test
    void pngChecksSizeShapeAndFormat() throws IOException {
        assertTrue(TextureCheck.checkPng(png(16, 16)).isEmpty());
        assertTrue(TextureCheck.checkPng(png(512, 512)).isEmpty());
        assertFalse(TextureCheck.checkPng(png(16, 32)).isEmpty(), "nicht quadratisch");
        assertFalse(TextureCheck.checkPng(png(24, 24)).isEmpty(), "keine erlaubte Kantenlaenge");
        assertFalse(TextureCheck.checkPng(png(1024, 1024)).isEmpty(), "zu gross");
        assertFalse(TextureCheck.checkPng(new byte[0]).isEmpty());
        assertFalse(TextureCheck.checkPng("kein png".getBytes()).isEmpty());
        byte[] cut = png(16, 16);
        assertFalse(TextureCheck.checkPng(java.util.Arrays.copyOf(cut, cut.length / 2)).isEmpty(), "abgeschnitten");
    }

    // ---- Datenbank ----

    @Test
    void addFindAndPersist() throws IOException {
        store.add("rubin", "DIAMOND_CHESTPLATE", "Rubin Brustplatte", png(16, 16));
        assertEquals("rubin", store.find("DIAMOND_CHESTPLATE", "  rubin BRUSTPLATTE ").id, "Gross-/Kleinschreibung und Rand zaehlen nicht");
        assertNull(store.find("STICK", "Rubin Brustplatte"));
        assertTrue(Files.isRegularFile(store.texture("rubin")));

        EntryStore again = new EntryStore(dir, () -> now++);
        again.load();
        assertEquals(1, again.entries().size());
        assertEquals("Rubin Brustplatte", again.get("rubin").name);
    }

    @Test
    void duplicatesAreRejected() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        assertThrows(IllegalArgumentException.class, () -> store.add("a", "DIAMOND_CHESTPLATE", "Anderer", png(16, 16)), "Kennung");
        assertThrows(IllegalArgumentException.class, () -> store.add("b", "STICK", "STAB", png(16, 16)), "Typ + Name");
    }

    @Test
    void removeArchivesAndRestoreBringsItBack() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        store.remove("a");
        assertTrue(store.entries().isEmpty());
        assertNull(store.find("STICK", "Stab"));
        assertEquals(1, store.archive().size());
        assertFalse(Files.exists(store.texture("a")));
        assertThrows(IllegalArgumentException.class, () -> store.add("a", "STICK", "Neu", png(16, 16)), "Kennung im Archiv");

        EntryStore again = new EntryStore(dir, () -> now++);
        again.load();
        assertEquals(1, again.archive().size(), "Archiv ueberlebt Neustart");

        again.restore("a");
        assertEquals(1, again.entries().size());
        assertTrue(again.archive().isEmpty());
        assertTrue(Files.isRegularFile(again.texture("a")));
    }

    @Test
    void restoreIsRefusedWhenTypeAndNameAreTakenMeanwhile() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        store.remove("a");
        store.add("b", "STICK", "Stab", png(16, 16));
        assertThrows(IllegalArgumentException.class, () -> store.restore("a"));
        assertEquals(1, store.archive().size(), "bleibt im Archiv");
    }

    @Test
    void archiveIsSortedNewestFirst() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        store.add("b", "DIAMOND_CHESTPLATE", "Brust", png(16, 16));
        store.remove("a");
        store.remove("b");
        assertEquals(List.of("b", "a"), store.archive().stream().map(e -> e.id).toList());
    }

    @Test
    void disabledEntriesDoNotMatch() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        store.setEnabled("a", false);
        assertNull(store.find("STICK", "Stab"));
    }

    @Test
    void brokenFileStartsEmptyAndIsKept() throws IOException {
        Files.writeString(dir.resolve("entries.json"), "{kaputt");
        store.load();
        assertTrue(store.entries().isEmpty());
        assertTrue(Files.exists(dir.resolve("entries.json.kaputt")));
    }

    @Test
    void entriesWithInvalidIdsInTheFileAreIgnored() throws IOException {
        Files.writeString(dir.resolve("entries.json"), "[{\"id\":\"../boese\",\"material\":\"STICK\",\"name\":\"x\"},{\"id\":\"gut\",\"material\":\"STICK\",\"name\":\"y\"}]");
        store.load();
        assertEquals(List.of("gut"), store.entries().stream().map(e -> e.id).toList());
    }

    // ---- Pack ----

    private static final class MapSink implements PackSink {
        final Map<String, byte[]> files = new LinkedHashMap<>();

        public void file(String path, byte[] data) { files.put(path, data); }
    }

    @Test
    void packContainsTextureModelAndItemDefinitionForEachEnabledEntry() throws IOException {
        store.add("rubin", "DIAMOND_CHESTPLATE", "Rubin", png(16, 16));
        store.add("aus", "STICK", "Aus", png(16, 16));
        store.setEnabled("aus", false);
        MapSink sink = new MapSink();
        PackFiles.contribute(store, sink, Logger.getAnonymousLogger());
        assertEquals(PackFiles.paths("rubin").stream().sorted().toList(), sink.files.keySet().stream().sorted().toList());
        String model = new String(sink.files.get("assets/texturepeak/models/item/rubin.json"));
        assertTrue(model.contains("texturepeak:item/rubin"), model);
        assertTrue(new String(sink.files.get("assets/texturepeak/items/rubin.json")).contains("texturepeak:item/rubin"));
    }

    @Test
    void entryWithMissingTextureIsSkippedOthersStay() throws IOException {
        store.add("a", "STICK", "Stab", png(16, 16));
        store.add("b", "DIAMOND_CHESTPLATE", "Brust", png(16, 16));
        Files.delete(store.texture("a"));
        MapSink sink = new MapSink();
        PackFiles.contribute(store, sink, Logger.getAnonymousLogger());
        assertNotNull(sink.files.get("assets/texturepeak/items/b.json"));
        assertNull(sink.files.get("assets/texturepeak/items/a.json"));
    }

    @Test
    void generatedPathsAreValidForThePack() {
        for (String p : PackFiles.paths("rubin_brust_2")) de.peakverse.core.api.pack.PackPaths.validate(p);
    }
}
