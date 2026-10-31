package de.peakverse.texturepeak;

import de.peakverse.core.api.pack.PackSink;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

/** Die Pack-Dateien eines Eintrags: Item-Definition, Modell und Textur im Namensraum {@code texturepeak}. */
public final class PackFiles {
    public static final String NAMESPACE = "texturepeak";

    private PackFiles() {}

    public static String itemDefinition(String id) {
        return "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NAMESPACE + ":item/" + id + "\"}}";
    }

    public static String model(String id) {
        return "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + NAMESPACE + ":item/" + id + "\"}}";
    }

    /** Legt alle aktiven Eintraege ins Pack. Ein Eintrag mit fehlender Textur wird uebersprungen und gemeldet; die anderen bleiben. */
    public static void contribute(EntryStore store, PackSink sink, Logger log) {
        for (Entry e : store.entries()) {
            if (!e.enabled) continue;
            byte[] png;
            try {
                png = Files.readAllBytes(store.texture(e.id));
            } catch (IOException ex) {
                log.warning("Eintrag '" + e.id + "' uebersprungen: Textur nicht lesbar (" + ex.getMessage() + ")");
                continue;
            }
            sink.file("assets/" + NAMESPACE + "/textures/item/" + e.id + ".png", png);
            sink.text("assets/" + NAMESPACE + "/models/item/" + e.id + ".json", model(e.id));
            sink.text("assets/" + NAMESPACE + "/items/" + e.id + ".json", itemDefinition(e.id));
        }
    }

    /** Hilfsliste fuer Tests und Anzeige: alle Pfade, die ein Eintrag belegt. */
    public static List<String> paths(String id) {
        return List.of("assets/" + NAMESPACE + "/textures/item/" + id + ".png", "assets/" + NAMESPACE + "/models/item/" + id + ".json",
                "assets/" + NAMESPACE + "/items/" + id + ".json");
    }
}
