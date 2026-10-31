package de.peakverse.texturepeak;

/**
 * Eine Textur-Regel: Gegenstand dieses Typs mit diesem Namen bekommt diese Textur. Die PNG-Datei liegt im Datenordner unter
 * {@code textures/<id>.png}. Ein Eintrag wird nie geaendert, sondern ersetzt; geloeschte Eintraege wandern ins Archiv.
 */
public final class Entry {
    /** Kennung: kleingeschrieben, wird zum Dateinamen im Pack und zum Item-Modell {@code texturepeak:<id>}. */
    public String id;
    /** Item-Typ als Bukkit-Name, grossgeschrieben, z. B. {@code DIAMOND_CHESTPLATE}. */
    public String material;
    /** Anzeigename des Gegenstands ohne Formatierung. */
    public String name;
    public boolean enabled = true;
    public long createdAt;
    /** Nur im Archiv: wann der Eintrag geloescht wurde. */
    public long archivedAt;

    public Entry() {}

    public Entry(String id, String material, String name, long createdAt) {
        this.id = id;
        this.material = material;
        this.name = name;
        this.createdAt = createdAt;
    }

    /** Vergleichsschluessel fuer Name und Typ: Gross-/Kleinschreibung und Rand-Leerzeichen zaehlen nicht. */
    public static String key(String material, String name) {
        return material.toUpperCase(java.util.Locale.ROOT) + "|" + normalizeName(name);
    }

    public static String normalizeName(String name) {
        return name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
