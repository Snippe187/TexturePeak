package de.peakverse.texturepeak;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebRequest;
import de.peakverse.core.api.web.WebResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.Registry;

/**
 * Web-Schnittstelle von TexturePeak ({@code /api/texturepeak/...}): Eintraege, Texturen, Archiv und Faehigkeiten. Alle Zugriffe auf die Datenbank laufen
 * auf dem Haupt-Thread (der Speicher ist nicht thread-sicher); nach jeder Aenderung wird das Pack neu gebaut und den anderen Seiten Bescheid gesagt.
 */
final class TextureWeb {
    private static final Gson GSON = new Gson();

    private final WebRegistry web;
    private final Supplier<EntryStore> store;
    private final Predicate<String> isItemType;

    private final VanillaTextures vanilla;

    TextureWeb(WebRegistry web, Supplier<EntryStore> store, Predicate<String> isItemType, VanillaTextures vanilla) {
        this.web = web;
        this.store = store;
        this.isItemType = isItemType;
        this.vanilla = vanilla;
    }

    void register(String script) {
        web.panel("Texturen", "Textures", script);
        web.route("meta", r -> WebResponse.json(meta()));
        web.route("entries", r -> r.method().equals("GET") ? main(() -> WebResponse.json(list(false))) : create(r));
        web.route("entries/*", this::entry);
        web.route("archive", r -> main(() -> WebResponse.json(list(true))));
        web.route("archive/*", this::archive);
        web.route("texture/*", this::texture);
        web.route("vanilla", r -> WebResponse.json(vanillaStatus()));
        web.route("vanilla/download", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            boolean started = vanilla.start();
            if (started) web.log(r.admin(), "Download der Standard-Texturen gestartet (Spiel " + vanilla.version() + ")");
            return WebResponse.json(vanillaStatus());
        });
        web.route("vanilla/*", this::vanillaFile);
    }

    private String vanillaStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("ready", vanilla.ready());
        o.addProperty("busy", vanilla.busy());
        o.addProperty("error", vanilla.error());
        o.addProperty("version", vanilla.version());
        o.add("item", strings(vanilla.names("item")));
        o.add("block", strings(vanilla.names("block")));
        return GSON.toJson(o);
    }

    private WebResponse vanillaFile(WebRequest r) throws Exception {
        String[] p = r.path().split("/"); // vanilla/<item|block>/<name>
        Path file = p.length == 3 ? vanilla.file(p[1], p[2]) : null;
        return file == null ? WebResponse.error(404, "Keine Textur") : WebResponse.bytes("image/png", Files.readAllBytes(file));
    }

    private interface Job {
        WebResponse run() throws Exception;
    }

    private WebResponse main(Job job) throws Exception {
        return web.onMain(job::run);
    }

    /** Aenderung: auf dem Haupt-Thread ausfuehren, protokollieren, Pack bauen, anderen Seiten melden. */
    private WebResponse change(WebRequest r, String what, Job job) throws Exception {
        WebResponse res = web.onMain(() -> {
            WebResponse out = job.run();
            boolean built = PeakCoreApi.get().map(PeakCoreApi::refreshPack).orElse(false);
            if (!built) web.log(r.admin(), "Pack konnte nach der Aenderung nicht gebaut werden");
            return out;
        });
        web.log(r.admin(), what);
        web.changed("entries", r.admin());
        return res;
    }

    // ---- Lesen ----

    private String meta() {
        JsonObject o = new JsonObject();
        o.add("materials", strings(Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isLegacy() && !m.isAir()).map(Material::name).sorted().toList()));
        o.add("effects", strings(Registry.POTION_EFFECT_TYPE.stream().map(t -> t.getKey().getKey()).sorted().toList()));
        o.add("attributes", strings(Registry.ATTRIBUTE.stream().map(t -> t.getKey().getKey()).sorted().toList()));
        o.add("particles", strings(Registry.PARTICLE_TYPE.stream().map(t -> t.getKey().getKey()).sorted().toList()));
        o.addProperty("maxId", TextureCheck.MAX_ID);
        o.addProperty("maxName", TextureCheck.MAX_NAME);
        o.addProperty("maxAbilities", EntryStore.MAX_ABILITIES);
        o.addProperty("maxLevel", Ability.MAX_LEVEL);
        o.addProperty("maxParticles", Ability.MAX_PARTICLES);
        o.addProperty("maxAmount", Ability.MAX_AMOUNT);
        return GSON.toJson(o);
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private String list(boolean archived) {
        JsonArray a = new JsonArray();
        for (Entry e : archived ? store.get().archive() : store.get().entries()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("material", e.material);
            o.addProperty("name", e.name);
            o.addProperty("enabled", e.enabled);
            o.addProperty("createdAt", e.createdAt);
            o.addProperty("archivedAt", e.archivedAt);
            JsonArray abilities = new JsonArray();
            for (Ability ab : e.abilities) {
                JsonObject x = new JsonObject();
                x.addProperty("kind", ab.kind.name());
                x.addProperty("when", ab.when.name());
                x.addProperty("name", ab.name);
                x.addProperty("level", ab.level);
                x.addProperty("amount", ab.amount);
                x.addProperty("multiply", ab.multiply);
                x.addProperty("text", ab.describe());
                abilities.add(x);
            }
            o.add("abilities", abilities);
            a.add(o);
        }
        return GSON.toJson(a);
    }

    private WebResponse texture(WebRequest r) throws Exception {
        String id = r.path().substring("texture/".length());
        if (!TextureCheck.validId(id)) return WebResponse.error(404, "Keine Textur");
        boolean archived = "1".equals(r.query().get("archived"));
        byte[] png = web.onMain(() -> {
            Path file = archived ? store.get().archivedTexture(id) : store.get().texture(id);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        });
        return png == null ? WebResponse.error(404, "Keine Textur") : WebResponse.bytes("image/png", png);
    }

    // ---- Aendern ----

    private WebResponse create(WebRequest r) throws Exception {
        if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
        JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
        String id = str(o, "id").toLowerCase(Locale.ROOT);
        String material = str(o, "material");
        String name = str(o, "name");
        byte[] png = decode(str(o, "png"));
        return change(r, "Eintrag angelegt: " + id + " (" + material + " \"" + name + "\")", () -> {
            checkAll(id, material, name, png);
            store.get().add(id, material, name, png);
            return WebResponse.ok();
        });
    }

    private void checkAll(String id, String material, String name, byte[] png) {
        List<String> errors = new java.util.ArrayList<>(TextureCheck.checkEntry(id, material, name, isItemType));
        errors.addAll(TextureCheck.checkPng(png));
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
    }

    private WebResponse entry(WebRequest r) throws Exception {
        String[] p = r.path().split("/"); // entries/<id>[/<aktion>[/<nr>]]
        if (p.length < 2) return WebResponse.error(404, "Unbekannte Adresse");
        String id = p[1];
        if (!TextureCheck.validId(id)) return WebResponse.error(400, "Ungueltige Kennung");
        if (p.length == 2 && r.method().equals("DELETE")) {
            return change(r, "Eintrag ins Archiv verschoben: " + id, () -> {
                store.get().remove(id);
                return WebResponse.ok();
            });
        }
        if (p.length == 3 && r.method().equals("POST")) {
            switch (p[2]) {
                case "texture" -> {
                    return change(r, "Textur geaendert: " + id, () -> {
                        List<String> errors = TextureCheck.checkPng(r.body());
                        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
                        store.get().replaceTexture(id, r.body());
                        return WebResponse.ok();
                    });
                }
                case "enabled" -> {
                    boolean on = GSON.fromJson(r.text(), JsonObject.class).get("enabled").getAsBoolean();
                    return change(r, "Eintrag " + id + (on ? " eingeschaltet" : " ausgeschaltet"), () -> {
                        store.get().setEnabled(id, on);
                        return WebResponse.ok();
                    });
                }
                case "duplicate" -> {
                    JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
                    String newId = str(o, "id").toLowerCase(Locale.ROOT);
                    String newName = str(o, "name");
                    return change(r, "Eintrag dupliziert: " + id + " -> " + newId, () -> {
                        Entry src = store.get().get(id);
                        if (src == null) throw new IllegalArgumentException("Kein Eintrag '" + id + "'.");
                        byte[] png = Files.readAllBytes(store.get().texture(id));
                        checkAll(newId, src.material, newName, png);
                        store.get().add(newId, src.material, newName, png);
                        for (Ability a : src.abilities) store.get().addAbility(newId, new Ability(a.kind, a.when, a.name, a.level, a.amount, a.multiply));
                        return WebResponse.ok();
                    });
                }
                case "abilities" -> {
                    JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
                    Ability a = new Ability(Ability.Kind.valueOf(str(o, "kind").toUpperCase(Locale.ROOT)), Ability.When.valueOf(str(o, "when").toUpperCase(Locale.ROOT)),
                            Ability.normalize(str(o, "name")), o.has("level") ? o.get("level").getAsInt() : 1, o.has("amount") ? o.get("amount").getAsDouble() : 0,
                            o.has("multiply") && o.get("multiply").getAsBoolean());
                    return change(r, "Faehigkeit hinzugefuegt bei " + id + ": " + a.describe(), () -> {
                        List<String> errors = AbilityRunner.check(a);
                        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
                        store.get().addAbility(id, a);
                        return WebResponse.ok();
                    });
                }
                default -> {
                    return WebResponse.error(404, "Unbekannte Aktion");
                }
            }
        }
        if (p.length == 4 && p[2].equals("abilities") && r.method().equals("DELETE")) {
            int number = Integer.parseInt(p[3]);
            return change(r, "Faehigkeit " + number + " entfernt bei " + id, () -> {
                store.get().removeAbility(id, number);
                return WebResponse.ok();
            });
        }
        return WebResponse.error(404, "Unbekannte Adresse");
    }

    private WebResponse archive(WebRequest r) throws Exception {
        String[] p = r.path().split("/"); // archive/<id>/restore
        if (p.length == 3 && p[2].equals("restore") && r.method().equals("POST") && TextureCheck.validId(p[1])) {
            String id = p[1];
            return change(r, "Eintrag wiederhergestellt: " + id, () -> {
                store.get().restore(id);
                return WebResponse.ok();
            });
        }
        return WebResponse.error(404, "Unbekannte Adresse");
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) throw new IllegalArgumentException("Feld fehlt: " + key);
        return o.get(key).getAsString().strip();
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Die Textur ist kein gueltiges Bild");
        }
    }
}
