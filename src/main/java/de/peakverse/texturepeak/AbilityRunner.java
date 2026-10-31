package de.peakverse.texturepeak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Wendet die Faehigkeiten jede Sekunde an: Effekte kurz (laufen ohne Erneuerung nach 2 Sekunden aus), Werte als fluechtige Modifikatoren
 * (nicht gespeichert, werden beim Ablegen, Abmelden und Abschalten entfernt) und Partikel. Muss auf dem Haupt-Thread laufen.
 */
final class AbilityRunner {
    /** Effekte werden jede Sekunde erneuert; so lange bleiben sie nach dem Ablegen hoechstens. */
    static final int EFFECT_TICKS = 40;

    private final EntryStore store;
    private final Function<ItemStack, String> idOf;
    private final Map<UUID, Set<NamespacedKey>> appliedModifiers = new HashMap<>();

    AbilityRunner(EntryStore store, Function<ItemStack, String> idOf) {
        this.store = store;
        this.idOf = idOf;
    }

    // ---- Namen aus den Registern des Servers ----

    static PotionEffectType effect(String name) {
        NamespacedKey key = NamespacedKey.fromString("minecraft:" + name);
        return key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
    }

    static Attribute attribute(String name) {
        NamespacedKey key = NamespacedKey.fromString("minecraft:" + name);
        return key == null ? null : Registry.ATTRIBUTE.get(key);
    }

    /** Nur Partikel ohne Zusatzdaten (Farbe, Block, ...), die sich allein mit dem Namen ausloesen lassen. */
    static Particle particle(String name) {
        NamespacedKey key = NamespacedKey.fromString("minecraft:" + name);
        Particle p = key == null ? null : Registry.PARTICLE_TYPE.get(key);
        return p != null && p.getDataType() == Void.class ? p : null;
    }

    static List<String> check(Ability a) {
        return Ability.check(a, n -> effect(n) != null, n -> attribute(n) != null, n -> particle(n) != null);
    }

    // ---- Anwendung ----

    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                apply(p);
            } catch (RuntimeException e) {
                Bukkit.getLogger().warning("[TexturePeak] Faehigkeiten bei " + p.getName() + " fehlgeschlagen: " + e);
            }
        }
    }

    private void apply(Player p) {
        PlayerInventory inv = p.getInventory();
        List<String> worn = new ArrayList<>();
        for (ItemStack armor : inv.getArmorContents()) add(worn, armor);
        List<String> held = new ArrayList<>();
        add(held, inv.getItemInMainHand());
        add(held, inv.getItemInOffHand());
        AbilityPlanner.Plan plan = AbilityPlanner.plan(worn, held, store::get);

        for (AbilityPlanner.Active a : plan.effects()) {
            PotionEffectType type = effect(a.ability().name);
            if (type != null) p.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, a.ability().level - 1, true, false, false));
        }
        for (AbilityPlanner.Active a : plan.particles()) {
            Particle particle = particle(a.ability().name);
            if (particle != null && !p.isInvisible()) p.getWorld().spawnParticle(particle, p.getLocation().add(0, 1, 0), a.ability().level, 0.3, 0.5, 0.3, 0.0);
        }
        applyAttributes(p, plan.attributes());
    }

    private void add(List<String> ids, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        String id = idOf.apply(item);
        if (id != null) ids.add(id);
    }

    private void applyAttributes(Player p, List<AbilityPlanner.Active> wanted) {
        Set<NamespacedKey> before = appliedModifiers.getOrDefault(p.getUniqueId(), Set.of());
        Set<NamespacedKey> now = new HashSet<>();
        for (AbilityPlanner.Active a : wanted) {
            Attribute attribute = attribute(a.ability().name);
            AttributeInstance instance = attribute == null ? null : p.getAttribute(attribute);
            if (instance == null) continue;
            NamespacedKey key = new NamespacedKey(PackFiles.NAMESPACE, "ab_" + a.key());
            AttributeModifier.Operation op = a.ability().multiply ? AttributeModifier.Operation.ADD_SCALAR : AttributeModifier.Operation.ADD_NUMBER;
            AttributeModifier existing = instance.getModifier(key);
            if (existing != null && (existing.getAmount() != a.ability().amount || existing.getOperation() != op)) {
                instance.removeModifier(existing);
                existing = null;
            }
            if (existing == null) instance.addTransientModifier(new AttributeModifier(key, a.ability().amount, op, EquipmentSlotGroup.ANY));
            now.add(key);
        }
        for (NamespacedKey old : before) if (!now.contains(old)) removeModifier(p, old);
        if (now.isEmpty()) appliedModifiers.remove(p.getUniqueId());
        else appliedModifiers.put(p.getUniqueId(), now);
    }

    private static void removeModifier(Player p, NamespacedKey key) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = p.getAttribute(attribute);
            if (instance != null && instance.getModifier(key) != null) instance.removeModifier(key);
        }
    }

    /** Entfernt alle Werte-Modifikatoren eines Spielers (Abmelden). */
    void forget(Player p) {
        Set<NamespacedKey> keys = appliedModifiers.remove(p.getUniqueId());
        if (keys != null) for (NamespacedKey key : keys) removeModifier(p, key);
    }

    /** Entfernt alle Modifikatoren aller Spieler (Abschalten, Neuladen). */
    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) forget(p);
        appliedModifiers.clear();
    }
}
