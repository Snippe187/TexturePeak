package de.peakverse.texturepeak;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Eine Faehigkeit eines Eintrags, rein serverseitig: ein Trank-Effekt, ein Wert (Attribut) oder ein Partikel-Effekt,
 * wirksam solange der Gegenstand getragen (Ruestungsplatz) oder gehalten (Haupt- oder Nebenhand) wird.
 */
public final class Ability {
    public enum Kind { EFFECT, ATTRIBUTE, PARTICLE }

    public enum When { WORN, HELD }

    public static final int MAX_LEVEL = 10;
    public static final int MAX_PARTICLES = 20;
    public static final double MAX_AMOUNT = 100;

    public Kind kind;
    public When when;
    /** Kleingeschriebener Minecraft-Name ohne Namensraum, z. B. {@code speed}, {@code max_health}, {@code flame}. */
    public String name;
    /** Effekt: Stufe 1-10. Partikel: Anzahl je Sekunde 1-20. Attribut: unbenutzt. */
    public int level = 1;
    /** Attribut: Wert. */
    public double amount;
    /** Attribut: {@code true} = Anteil (+0,1 = +10 %), {@code false} = fester Wert. */
    public boolean multiply;

    public Ability() {}

    public Ability(Kind kind, When when, String name, int level, double amount, boolean multiply) {
        this.kind = kind;
        this.when = when;
        this.name = name;
        this.level = level;
        this.amount = amount;
        this.multiply = multiply;
    }

    public static String normalize(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    /** Eine Zeile fuer die Anzeige. */
    public String describe() {
        String where = when == When.WORN ? "getragen" : "gehalten";
        return switch (kind) {
            case EFFECT -> "Effekt " + name + " " + level + " (" + where + ")";
            case PARTICLE -> "Partikel " + name + " x" + level + "/s (" + where + ")";
            case ATTRIBUTE -> "Wert " + name + " " + (amount >= 0 ? "+" : "") + amount + (multiply ? " (Anteil)" : "") + " (" + where + ")";
        };
    }

    /** Welche Namen es gibt, entscheidet der Server (Registry); die Pruefung bekommt sie als Funktionen. */
    public static List<String> check(Ability a, Predicate<String> effectExists, Predicate<String> attributeExists, Predicate<String> particleExists) {
        List<String> errors = new ArrayList<>();
        if (a == null || a.kind == null || a.when == null || a.name == null) return List.of("Unvollstaendige Faehigkeit.");
        switch (a.kind) {
            case EFFECT -> {
                if (!effectExists.test(a.name)) errors.add("Unbekannter Effekt '" + a.name + "'.");
                if (a.level < 1 || a.level > MAX_LEVEL) errors.add("Die Effekt-Stufe muss zwischen 1 und " + MAX_LEVEL + " liegen.");
            }
            case ATTRIBUTE -> {
                if (!attributeExists.test(a.name)) errors.add("Unbekannter Wert '" + a.name + "'.");
                if (Double.isNaN(a.amount) || Double.isInfinite(a.amount) || Math.abs(a.amount) > MAX_AMOUNT) errors.add("Der Wert muss zwischen -" + (int) MAX_AMOUNT + " und " + (int) MAX_AMOUNT + " liegen.");
                if (a.amount == 0) errors.add("Der Wert 0 aendert nichts.");
            }
            case PARTICLE -> {
                if (!particleExists.test(a.name)) errors.add("Unbekannter Partikel '" + a.name + "'.");
                if (a.level < 1 || a.level > MAX_PARTICLES) errors.add("Die Partikel-Anzahl muss zwischen 1 und " + MAX_PARTICLES + " liegen.");
            }
        }
        if (a.name != null && !a.name.equals(normalize(a.name))) errors.add("Der Name muss klein geschrieben sein, ohne Namensraum.");
        return errors;
    }
}
