package de.peakverse.texturepeak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbilityTest {
    private static final Set<String> EFFECTS = Set.of("speed", "strength");
    private static final Set<String> ATTRS = Set.of("max_health", "armor");
    private static final Set<String> PARTICLES = Set.of("flame", "heart");

    private static List<String> check(Ability a) {
        return Ability.check(a, EFFECTS::contains, ATTRS::contains, PARTICLES::contains);
    }

    private static Ability effect(Ability.When w, String name, int level) {
        return new Ability(Ability.Kind.EFFECT, w, name, level, 0, false);
    }

    // ---- Pruefung ----

    @Test
    void validAbilitiesPass() {
        assertTrue(check(effect(Ability.When.WORN, "speed", 2)).isEmpty());
        assertTrue(check(new Ability(Ability.Kind.ATTRIBUTE, Ability.When.HELD, "max_health", 1, 4, false)).isEmpty());
        assertTrue(check(new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 0.1, true)).isEmpty());
        assertTrue(check(new Ability(Ability.Kind.PARTICLE, Ability.When.WORN, "flame", 5, 0, false)).isEmpty());
    }

    @Test
    void invalidAbilitiesAreExplained() {
        assertFalse(check(effect(Ability.When.WORN, "fliegen", 1)).isEmpty(), "unbekannter Effekt");
        assertFalse(check(effect(Ability.When.WORN, "speed", 0)).isEmpty());
        assertFalse(check(effect(Ability.When.WORN, "speed", Ability.MAX_LEVEL + 1)).isEmpty());
        assertFalse(check(effect(Ability.When.WORN, "Speed", 1)).isEmpty(), "Grossbuchstaben");
        assertFalse(check(new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 0, false)).isEmpty(), "Wert 0");
        assertFalse(check(new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 1000, false)).isEmpty(), "zu gross");
        assertFalse(check(new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, Double.NaN, false)).isEmpty());
        assertFalse(check(new Ability(Ability.Kind.PARTICLE, Ability.When.WORN, "flame", 99, 0, false)).isEmpty());
        assertFalse(check(new Ability(Ability.Kind.PARTICLE, Ability.When.WORN, "kaboom", 1, 0, false)).isEmpty());
        assertFalse(check(new Ability()).isEmpty(), "leer");
        assertFalse(check(null).isEmpty());
    }

    @Test
    void normalizeStripsNamespaceAndCase() {
        assertEquals("speed", Ability.normalize("  Minecraft:SPEED "));
    }

    // ---- Planer ----

    private static Entry entry(String id, boolean enabled, Ability... abilities) {
        Entry e = new Entry(id, "STICK", id, 0);
        e.enabled = enabled;
        e.abilities.addAll(List.of(abilities));
        return e;
    }

    @Test
    void wornAbilitiesApplyOnlyWhenWornAndHeldOnlyWhenHeld() {
        Entry e = entry("a", true, effect(Ability.When.WORN, "speed", 1), effect(Ability.When.HELD, "strength", 1));
        Map<String, Entry> db = Map.of("a", e);
        AbilityPlanner.Plan worn = AbilityPlanner.plan(List.of("a"), List.of(), db::get);
        assertEquals(List.of("speed"), worn.effects().stream().map(x -> x.ability().name).toList());
        AbilityPlanner.Plan held = AbilityPlanner.plan(List.of(), List.of("a"), db::get);
        assertEquals(List.of("strength"), held.effects().stream().map(x -> x.ability().name).toList());
        assertTrue(AbilityPlanner.plan(List.of(), List.of(), db::get).isEmpty());
    }

    @Test
    void fourPiecesOfTheSameEntryCountOnce() {
        Entry e = entry("a", true, new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 2, false));
        AbilityPlanner.Plan plan = AbilityPlanner.plan(List.of("a", "a", "a", "a"), List.of(), Map.of("a", e)::get);
        assertEquals(1, plan.attributes().size());
    }

    @Test
    void highestEffectLevelWinsAcrossEntries() {
        Map<String, Entry> db = Map.of("a", entry("a", true, effect(Ability.When.WORN, "speed", 1)), "b", entry("b", true, effect(Ability.When.WORN, "speed", 3)));
        AbilityPlanner.Plan plan = AbilityPlanner.plan(List.of("a", "b"), List.of(), db::get);
        assertEquals(1, plan.effects().size());
        assertEquals(3, plan.effects().get(0).ability().level);
    }

    @Test
    void disabledOrUnknownEntriesGiveNothing() {
        Map<String, Entry> db = Map.of("aus", entry("aus", false, effect(Ability.When.WORN, "speed", 1)));
        assertTrue(AbilityPlanner.plan(List.of("aus", "weg"), List.of(), db::get).isEmpty());
    }

    @Test
    void modifierKeysAreUniquePerEntryAndNumber() {
        Entry e = entry("a", true, new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 1, false),
                new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "max_health", 1, 2, false));
        AbilityPlanner.Plan plan = AbilityPlanner.plan(List.of("a"), List.of(), Map.of("a", e)::get);
        assertEquals(List.of("a_1", "a_2"), plan.attributes().stream().map(AbilityPlanner.Active::key).toList());
    }

    // ---- Speicherung ----

    @Test
    void abilitiesSurviveRestartAndOldFilesWithoutThemLoad(@TempDir Path dir) throws IOException {
        EntryStore store = new EntryStore(dir, () -> 1);
        store.add("a", "STICK", "Stab", new byte[] {1});
        store.addAbility("a", effect(Ability.When.HELD, "speed", 2));
        store.addAbility("a", new Ability(Ability.Kind.ATTRIBUTE, Ability.When.WORN, "armor", 1, 0.5, true));

        EntryStore again = new EntryStore(dir, () -> 1);
        again.load();
        List<Ability> list = again.get("a").abilities;
        assertEquals(2, list.size());
        assertEquals(Ability.When.HELD, list.get(0).when);
        assertTrue(list.get(1).multiply);

        Files.writeString(dir.resolve("entries.json"), "[{\"id\":\"alt\",\"material\":\"STICK\",\"name\":\"Alt\"},{\"id\":\"neu\",\"material\":\"STICK\",\"name\":\"Neu\",\"abilities\":null}]");
        again.load();
        assertEquals(0, again.get("alt").abilities.size());
        assertEquals(0, again.get("neu").abilities.size());
    }

    @Test
    void removeAndLimit(@TempDir Path dir) throws IOException {
        EntryStore store = new EntryStore(dir, () -> 1);
        store.add("a", "STICK", "Stab", new byte[] {1});
        for (int i = 0; i < EntryStore.MAX_ABILITIES; i++) store.addAbility("a", effect(Ability.When.WORN, "speed", 1 + i % 3));
        assertThrows(IllegalArgumentException.class, () -> store.addAbility("a", effect(Ability.When.WORN, "speed", 1)));
        assertEquals(Ability.When.WORN, store.removeAbility("a", 1).when);
        assertEquals(EntryStore.MAX_ABILITIES - 1, store.get("a").abilities.size());
        assertThrows(IllegalArgumentException.class, () -> store.removeAbility("a", 0));
        assertThrows(IllegalArgumentException.class, () -> store.removeAbility("a", 99));
        assertThrows(IllegalArgumentException.class, () -> store.addAbility("nix", effect(Ability.When.WORN, "speed", 1)));
    }
}
