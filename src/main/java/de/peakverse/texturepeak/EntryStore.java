package de.peakverse.texturepeak;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Die Eintrags-Datenbank: {@code entries.json} (aktiv) und {@code archive.json} (geloescht, aber wiederholbar), dazu die PNG-Dateien in {@code textures/}
 * und {@code archive/}. Geschrieben wird atomar (Temp-Datei, dann Umbenennen). Nicht thread-sicher; Aufrufe gehoeren auf einen Thread.
 */
public final class EntryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type LIST = new TypeToken<List<Entry>>() {}.getType();

    public static final int MAX_ABILITIES = 8;

    private final Path folder;
    private final LongSupplier clock;
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Map<String, Entry> archived = new LinkedHashMap<>();

    public EntryStore(Path folder, LongSupplier clock) {
        this.folder = folder;
        this.clock = clock;
    }

    private Path entriesFile() { return folder.resolve("entries.json"); }
    private Path archiveFile() { return folder.resolve("archive.json"); }
    public Path texture(String id) { return folder.resolve("textures").resolve(id + ".png"); }
    public Path archivedTexture(String id) { return folder.resolve("archive").resolve(id + ".png"); }

    /** Ersetzt die Textur eines aktiven Eintrags (die Pruefung des PNG macht der Aufrufer). */
    public void replaceTexture(String id, byte[] png) throws IOException {
        if (!active.containsKey(id)) throw new IllegalArgumentException("Kein Eintrag '" + id + "'.");
        Files.createDirectories(texture(id).getParent());
        Path tmp = texture(id).resolveSibling(id + ".png.tmp");
        Files.write(tmp, png);
        Files.move(tmp, texture(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Laedt beide Dateien. Eine kaputte Datei wird als {@code .kaputt} aufgehoben und ersetzt, damit der Server trotzdem startet. */
    public void load() throws IOException {
        active.clear();
        archived.clear();
        for (Entry e : read(entriesFile())) if (TextureCheck.validId(e.id)) active.put(e.id, e);
        for (Entry e : read(archiveFile())) if (TextureCheck.validId(e.id)) archived.put(e.id, e);
    }

    private List<Entry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<Entry> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST);
            if (list == null) return List.of();
            List<Entry> ok = new ArrayList<>();
            for (Entry e : list) {
                if (e == null || e.id == null || e.material == null || e.name == null) continue;
                if (e.abilities == null) e.abilities = new ArrayList<>();
                e.abilities.removeIf(java.util.Objects::isNull);
                ok.add(e);
            }
            return ok;
        } catch (RuntimeException e) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".kaputt"), StandardCopyOption.REPLACE_EXISTING);
            return List.of();
        }
    }

    private void save() throws IOException {
        Files.createDirectories(folder);
        write(entriesFile(), new ArrayList<>(active.values()));
        write(archiveFile(), new ArrayList<>(archived.values()));
    }

    private static void write(Path file, List<Entry> list) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(list, LIST), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- Abfragen ----

    public List<Entry> entries() {
        return active.values().stream().sorted(Comparator.comparing(e -> e.id)).toList();
    }

    public List<Entry> archive() {
        return archived.values().stream().sorted(Comparator.comparingLong((Entry e) -> e.archivedAt).reversed()).toList();
    }

    public Entry get(String id) {
        return active.get(id);
    }

    /** Die aktive Regel fuer Typ und Name oder {@code null}. */
    public Entry find(String material, String name) {
        String key = Entry.key(material, name);
        for (Entry e : active.values()) if (e.enabled && Entry.key(e.material, e.name).equals(key)) return e;
        return null;
    }

    // ---- Aenderungen ----

    /**
     * Legt einen Eintrag samt Textur an.
     *
     * @throws IllegalArgumentException wenn Kennung oder Typ+Name schon vergeben sind (auch im Archiv: dort erst wiederherstellen oder endgueltig loeschen)
     */
    public Entry add(String id, String material, String name, byte[] png) throws IOException {
        if (active.containsKey(id)) throw new IllegalArgumentException("Die Kennung '" + id + "' gibt es schon.");
        if (archived.containsKey(id)) throw new IllegalArgumentException("Die Kennung '" + id + "' liegt im Archiv: erst wiederherstellen.");
        Entry same = find(material, name);
        if (same != null) throw new IllegalArgumentException("Fuer " + material + " mit dem Namen '" + name + "' gibt es schon einen Eintrag: " + same.id);
        Entry e = new Entry(id, material, name, clock.getAsLong());
        Files.createDirectories(texture(id).getParent());
        Files.write(texture(id), png);
        active.put(id, e);
        save();
        return e;
    }

    /** Verschiebt einen Eintrag samt Textur ins Archiv. */
    public Entry remove(String id) throws IOException {
        Entry e = active.remove(id);
        if (e == null) throw new IllegalArgumentException("Kein Eintrag '" + id + "'.");
        e.archivedAt = clock.getAsLong();
        archived.put(id, e);
        if (Files.isRegularFile(texture(id))) {
            Files.createDirectories(archivedTexture(id).getParent());
            Files.move(texture(id), archivedTexture(id), StandardCopyOption.REPLACE_EXISTING);
        }
        save();
        return e;
    }

    /** Holt einen Eintrag aus dem Archiv zurueck. */
    public Entry restore(String id) throws IOException {
        Entry e = archived.get(id);
        if (e == null) throw new IllegalArgumentException("Kein Archiv-Eintrag '" + id + "'.");
        Entry clash = find(e.material, e.name);
        if (clash != null) throw new IllegalArgumentException("Wiederherstellen nicht moeglich: " + e.material + " mit dem Namen '" + e.name + "' gehoert jetzt zu '" + clash.id + "'.");
        if (!Files.isRegularFile(archivedTexture(id))) throw new IllegalArgumentException("Die Textur dieses Archiv-Eintrags fehlt.");
        archived.remove(id);
        e.archivedAt = 0;
        Files.createDirectories(texture(id).getParent());
        Files.move(archivedTexture(id), texture(id), StandardCopyOption.REPLACE_EXISTING);
        active.put(id, e);
        save();
        return e;
    }

    /** Haengt eine (bereits gepruefte) Faehigkeit an. Hoechstens {@value #MAX_ABILITIES} je Eintrag. */
    public void addAbility(String id, Ability ability) throws IOException {
        Entry e = require(id);
        if (e.abilities.size() >= MAX_ABILITIES) throw new IllegalArgumentException("Hoechstens " + MAX_ABILITIES + " Faehigkeiten je Eintrag.");
        e.abilities.add(ability);
        save();
    }

    /** @param number Nummer ab 1, wie in der Anzeige */
    public Ability removeAbility(String id, int number) throws IOException {
        Entry e = require(id);
        if (number < 1 || number > e.abilities.size()) throw new IllegalArgumentException("Keine Faehigkeit Nr. " + number + " bei " + id + ".");
        Ability a = e.abilities.remove(number - 1);
        save();
        return a;
    }

    private Entry require(String id) {
        Entry e = active.get(id);
        if (e == null) throw new IllegalArgumentException("Kein Eintrag " + id + ".");
        return e;
    }

    public void setEnabled(String id, boolean enabled) throws IOException {
        Entry e = active.get(id);
        if (e == null) throw new IllegalArgumentException("Kein Eintrag '" + id + "'.");
        e.enabled = enabled;
        save();
    }
}
