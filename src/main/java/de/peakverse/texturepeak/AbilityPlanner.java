package de.peakverse.texturepeak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Entscheidet, welche Faehigkeiten fuer einen Spieler gerade gelten. Reine Logik, ohne Server. */
public final class AbilityPlanner {
    /** Eine geltende Faehigkeit; {@code key} ist je Eintrag und Nummer eindeutig (fuer Attribut-Modifikatoren). */
    public record Active(String entryId, int index, Ability ability) {
        public String key() {
            return entryId + "_" + index;
        }
    }

    /** Ergebnis: Effekte (je Name nur die hoechste Stufe), Werte und Partikel. */
    public record Plan(List<Active> effects, List<Active> attributes, List<Active> particles) {
        public boolean isEmpty() {
            return effects.isEmpty() && attributes.isEmpty() && particles.isEmpty();
        }
    }

    private AbilityPlanner() {}

    /**
     * @param wornIds Eintrags-Kennungen der getragenen markierten Gegenstaende (mehrfach moeglich)
     * @param heldIds Eintrags-Kennungen der gehaltenen markierten Gegenstaende
     * @param lookup  Eintrag zur Kennung oder {@code null} (geloescht, aus)
     */
    public static Plan plan(List<String> wornIds, List<String> heldIds, Function<String, Entry> lookup) {
        Map<String, Active> effectsByName = new LinkedHashMap<>();
        Map<String, Active> others = new LinkedHashMap<>();
        collect(wornIds, Ability.When.WORN, lookup, effectsByName, others);
        collect(heldIds, Ability.When.HELD, lookup, effectsByName, others);
        List<Active> attributes = new ArrayList<>();
        List<Active> particles = new ArrayList<>();
        for (Active a : others.values()) (a.ability().kind == Ability.Kind.ATTRIBUTE ? attributes : particles).add(a);
        return new Plan(List.copyOf(effectsByName.values()), attributes, particles);
    }

    private static void collect(List<String> ids, Ability.When when, Function<String, Entry> lookup, Map<String, Active> effects, Map<String, Active> others) {
        for (String id : Set.copyOf(ids)) {
            Entry e = lookup.apply(id);
            if (e == null || !e.enabled) continue;
            for (int i = 0; i < e.abilities.size(); i++) {
                Ability a = e.abilities.get(i);
                if (a.when != when) continue;
                Active active = new Active(id, i + 1, a);
                if (a.kind == Ability.Kind.EFFECT) {
                    effects.merge(a.name, active, (old, now) -> now.ability().level > old.ability().level ? now : old);
                } else {
                    others.putIfAbsent(active.key(), active);
                }
            }
        }
    }
}
