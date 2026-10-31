package de.peakverse.texturepeak;

import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.api.pack.PackSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * TexturePeak (Server): Regeln "Item-Typ + Name -> Textur", Eintrags-Datenbank mit Archiv, Pruefung der Texturen, Markierung der Gegenstaende
 * (Item-Modell {@code texturepeak:<id>}) und die Pack-Dateien dazu. Verwaltet wird vorerst per {@code /texturepeak}; die Web-Oberflaeche folgt spaeter.
 */
public final class TexturePeakModule implements PeakModule, Listener, CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("list", "add", "remove", "archive", "restore", "apply", "check", "ability", "reload");

    private ModuleContext context;
    private EntryStore store;
    private NamespacedKey markerKey;
    private AbilityRunner runner;
    private org.bukkit.scheduler.BukkitTask task;

    @Override
    public String id() {
        return "texturepeak";
    }

    @Override
    public String displayName() {
        return "TexturePeak";
    }

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
        context.saveDefaultConfig();
        store = new EntryStore(context.dataFolder().toPath(), System::currentTimeMillis);
        markerKey = new NamespacedKey(context.host(), "entry");
        try {
            Files.createDirectories(importFolder());
            store.load();
        } catch (IOException e) {
            throw new IllegalStateException("Eintraege konnten nicht geladen werden: " + e.getMessage(), e);
        }
        context.host().getServer().getPluginManager().registerEvents(this, context.host());
        runner = new AbilityRunner(store, this::markedId);
        task = context.host().getServer().getScheduler().runTaskTimer(context.host(), runner::tick, 20L, 20L);
        PluginCommand command = context.host().getCommand("texturepeak");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        if (context.web().enabled()) {
            try (java.io.InputStream in = TexturePeakModule.class.getResourceAsStream("/modules/texturepeak/panel.js")) {
                if (in == null) throw new IOException("panel.js fehlt im Jar");
                new TextureWeb(context.web(), () -> store, TexturePeakModule::isItemType).register(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                context.logger().warning("Web-Seite von TexturePeak nicht verfuegbar: " + e.getMessage());
            }
        }
        context.logger().info(store.entries().size() + " Texturen-Eintraege geladen (" + store.archive().size() + " im Archiv).");
    }

    @Override
    public void contributePack(PackSink sink) {
        if (store != null) PackFiles.contribute(store, sink, context.logger());
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        task = null;
        if (runner != null) runner.shutdown();
        runner = null;
        if (context != null) {
            org.bukkit.event.HandlerList.unregisterAll(this);
            PluginCommand command = context.host().getCommand("texturepeak");
            if (command != null && command.getExecutor() == this) command.setExecutor(null);
        }
        context = null;
        store = null;
    }

    private Path importFolder() {
        return context.dataFolder().toPath().resolve("import");
    }

    // ---- Gegenstaende markieren ----

    /** Setzt Item-Modell und Markierung. @return {@code true}, wenn der Gegenstand veraendert wurde */
    boolean mark(ItemStack item, Entry entry) {
        if (item == null || item.getType().isAir() || !item.getType().name().equals(entry.material)) return false;
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey(PackFiles.NAMESPACE, entry.id));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, entry.id);
        item.setItemMeta(meta);
        return true;
    }

    /** Die Eintrags-Kennung, mit der ein Gegenstand markiert ist, oder {@code null}. */
    String markedId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (runner != null) runner.forget(event.getPlayer());
    }

    /** Beim Umbenennen am Amboss: passt Typ und neuer Name zu einer Regel, bekommt das Ergebnis die Textur. */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir() || store == null) return;
        String rename = event.getView().getRenameText();
        Entry entry = rename == null ? null : store.find(result.getType().name(), rename);
        if (entry != null) mark(result, entry);
    }

    // ---- Befehle ----

    @Override
    public boolean onCommand(CommandSender who, Command command, String label, String[] args) {
        if (store == null) return true;
        if (who instanceof Player p && !PeakCoreApi.get().map(api -> api.isAdmin(p)).orElse(false)) {
            who.sendMessage(Component.text("Nur fuer Administratoren.", NamedTextColor.RED));
            return true;
        }
        try {
            String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "list" -> list(who);
                case "archive" -> archive(who);
                case "add" -> add(who, args);
                case "remove" -> remove(who, args);
                case "restore" -> restore(who, args);
                case "apply" -> apply(who, args);
                case "check" -> check(who, args);
                case "ability" -> ability(who, args);
                case "reload" -> reload(who);
                default -> msg(who, "Benutzung: /texturepeak <list|add <kennung> <ITEM_TYP> <name...>|remove <kennung>|archive|restore <kennung>|apply <kennung>|check <kennung>|reload>", NamedTextColor.RED);
            }
        } catch (IllegalArgumentException e) {
            msg(who, e.getMessage(), NamedTextColor.RED);
        } catch (IOException e) {
            msg(who, "Fehler beim Speichern: " + e, NamedTextColor.RED);
        }
        return true;
    }

    private static void msg(CommandSender who, String text, NamedTextColor color) {
        who.sendMessage(Component.text(text, color));
    }

    private void list(CommandSender who) {
        List<Entry> entries = store.entries();
        if (entries.isEmpty()) {
            msg(who, "Noch keine Eintraege. Textur als plugins/TexturePeak/import/<kennung>.png ablegen, dann /texturepeak add <kennung> <ITEM_TYP> <name>.", NamedTextColor.GRAY);
            return;
        }
        msg(who, entries.size() + " Eintraege:", NamedTextColor.GOLD);
        for (Entry e : entries) msg(who, " " + e.id + "  " + e.material + "  \"" + e.name + "\"" + (e.enabled ? "" : "  (aus)"), NamedTextColor.WHITE);
    }

    private void archive(CommandSender who) {
        List<Entry> entries = store.archive();
        if (entries.isEmpty()) {
            msg(who, "Das Archiv ist leer.", NamedTextColor.GRAY);
            return;
        }
        msg(who, entries.size() + " Archiv-Eintraege (zurueckholen mit /texturepeak restore <kennung>):", NamedTextColor.GOLD);
        for (Entry e : entries) msg(who, " " + e.id + "  " + e.material + "  \"" + e.name + "\"", NamedTextColor.WHITE);
    }

    private void add(CommandSender who, String[] args) throws IOException {
        if (args.length < 4) throw new IllegalArgumentException("Benutzung: /texturepeak add <kennung> <ITEM_TYP> <name...>  (Textur vorher als plugins/TexturePeak/import/<kennung>.png ablegen)");
        String id = args[1];
        String material = args[2];
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        List<String> errors = new ArrayList<>(TextureCheck.checkEntry(id, material, name, TexturePeakModule::isItemType));
        byte[] png = null;
        if (errors.isEmpty()) {
            Path file = importFolder().resolve(id + ".png");
            if (!Files.isRegularFile(file)) errors.add("Die Textur fehlt: " + file.getFileName() + " in plugins/TexturePeak/import/ ablegen.");
            else {
                png = Files.readAllBytes(file);
                errors.addAll(TextureCheck.checkPng(png));
            }
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        store.add(id, material, name, png);
        msg(who, "Eintrag '" + id + "' angelegt: " + material + " \"" + name + "\".", NamedTextColor.GREEN);
        refresh(who);
    }

    private void remove(CommandSender who, String[] args) throws IOException {
        Entry e = store.remove(arg(args, "remove"));
        msg(who, "Eintrag '" + e.id + "' ins Archiv verschoben (zurueck mit /texturepeak restore " + e.id + ").", NamedTextColor.GREEN);
        refresh(who);
    }

    private void restore(CommandSender who, String[] args) throws IOException {
        Entry e = store.restore(arg(args, "restore"));
        msg(who, "Eintrag '" + e.id + "' wiederhergestellt.", NamedTextColor.GREEN);
        refresh(who);
    }

    private void apply(CommandSender who, String[] args) {
        if (!(who instanceof Player p)) throw new IllegalArgumentException("Nur im Spiel: Gegenstand in die Hand nehmen.");
        Entry e = store.get(arg(args, "apply"));
        if (e == null) throw new IllegalArgumentException("Kein Eintrag '" + args[1] + "'.");
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!mark(held, e)) throw new IllegalArgumentException("In der Hand muss ein Gegenstand vom Typ " + e.material + " sein.");
        msg(who, "Gegenstand markiert. Er zeigt die Textur, sobald das Pack beim Spieler aktiv ist.", NamedTextColor.GREEN);
    }

    private void check(CommandSender who, String[] args) throws IOException {
        Entry e = store.get(arg(args, "check"));
        if (e == null) throw new IllegalArgumentException("Kein Eintrag '" + args[1] + "'.");
        List<String> errors = TextureCheck.checkPng(Files.readAllBytes(store.texture(e.id)));
        msg(who, errors.isEmpty() ? "Eintrag '" + e.id + "' ist in Ordnung." : String.join("\n", errors), errors.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private static final String ABILITY_USAGE = "Benutzung: /texturepeak ability <kennung> <list | remove <nr> | add effect <worn|held> <name> [stufe] | add particle <worn|held> <name> [anzahl] | add attribute <worn|held> <name> <wert> [add|multiply]>";

    private void ability(CommandSender who, String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException(ABILITY_USAGE);
        Entry e = store.get(args[1].toLowerCase(Locale.ROOT));
        if (e == null) throw new IllegalArgumentException("Kein Eintrag " + args[1] + ".");
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (e.abilities.isEmpty()) msg(who, "'" + e.id + "' hat keine Faehigkeiten.", NamedTextColor.GRAY);
                for (int i = 0; i < e.abilities.size(); i++) msg(who, " " + (i + 1) + ". " + e.abilities.get(i).describe(), NamedTextColor.WHITE);
            }
            case "remove" -> {
                if (args.length < 4) throw new IllegalArgumentException(ABILITY_USAGE);
                Ability a = store.removeAbility(e.id, parseInt(args[3], "Nummer"));
                msg(who, "Entfernt: " + a.describe(), NamedTextColor.GREEN);
            }
            case "add" -> {
                Ability a = parseAbility(args);
                List<String> errors = AbilityRunner.check(a);
                if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
                store.addAbility(e.id, a);
                msg(who, "Hinzugefuegt: " + a.describe() + ". Wirkt bei Spielern, die den markierten Gegenstand " + (a.when == Ability.When.WORN ? "tragen" : "halten") + ".", NamedTextColor.GREEN);
            }
            default -> throw new IllegalArgumentException(ABILITY_USAGE);
        }
    }

    private static Ability parseAbility(String[] args) {
        if (args.length < 6) throw new IllegalArgumentException(ABILITY_USAGE);
        Ability.Kind kind;
        try {
            kind = Ability.Kind.valueOf(args[3].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(ABILITY_USAGE);
        }
        Ability.When when;
        try {
            when = Ability.When.valueOf(args[4].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'" + args[4] + "' ist weder worn (getragen) noch held (gehalten).");
        }
        String name = Ability.normalize(args[5]);
        if (kind == Ability.Kind.ATTRIBUTE) {
            if (args.length < 7) throw new IllegalArgumentException(ABILITY_USAGE);
            double amount;
            try {
                amount = Double.parseDouble(args[6]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Kein Wert: " + args[6]);
            }
            boolean multiply = args.length > 7 && args[7].equalsIgnoreCase("multiply");
            if (args.length > 7 && !multiply && !args[7].equalsIgnoreCase("add")) throw new IllegalArgumentException("Art muss add oder multiply sein.");
            return new Ability(kind, when, name, 1, amount, multiply);
        }
        int level = args.length > 6 ? parseInt(args[6], kind == Ability.Kind.EFFECT ? "Stufe" : "Anzahl") : 1;
        return new Ability(kind, when, name, level, 0, false);
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Keine Zahl fuer " + what + ": " + s);
        }
    }

    private void reload(CommandSender who) throws IOException {
        context.reloadConfig();
        store.load();
        msg(who, store.entries().size() + " Eintraege neu geladen.", NamedTextColor.GREEN);
        refresh(who);
    }

    private static String arg(String[] args, String sub) {
        if (args.length < 2) throw new IllegalArgumentException("Benutzung: /texturepeak " + sub + " <kennung>");
        return args[1].toLowerCase(Locale.ROOT);
    }

    /** Pack neu bauen; ohne laufenden Versand bleibt es beim Bauen. */
    private void refresh(CommandSender who) {
        boolean ok = PeakCoreApi.get().map(PeakCoreApi::refreshPack).orElse(false);
        msg(who, ok ? "Pack neu gebaut." : "Pack konnte nicht gebaut werden (siehe Server-Log).", ok ? NamedTextColor.GRAY : NamedTextColor.RED);
    }

    static boolean isItemType(String name) {
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() && !m.isAir() && !m.isLegacy() && m.name().equals(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender who, Command command, String alias, String[] args) {
        if (store == null) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "remove", "apply", "check", "ability" -> store.entries().stream().map(e -> e.id).filter(s -> s.startsWith(prefix)).toList();
                case "restore" -> store.archive().stream().map(e -> e.id).filter(s -> s.startsWith(prefix)).toList();
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isLegacy()).map(Material::name).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(40).toList();
        }
        return List.of();
    }
}
