package dev.sirenclient;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Block lists + settings, saved to config/siren-client.json. */
public class Config {
    /** "base"/"sus": block -> weight.  "esp": block -> RGB color. */
    public static final Map<String, Map<Block, Integer>> lists = new LinkedHashMap<>();
    public static int minBase = 12, minSus = 30, espAlpha = 60, espDist = 192;
    public static boolean beams = true, plates = true, notify = true;

    public record Waypoint(String name, int x, int y, int z) {}
    public static final java.util.List<Waypoint> waypoints = new java.util.ArrayList<>();

    public static final String[] COLOR_NAMES = {"Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "White"};
    public static final int[] COLORS = {0xFF3B3B, 0xFF9A2E, 0xFFE23B, 0x3BFF7A, 0x3BE8FF, 0x4D7CFF, 0xB15CFF, 0xFF5CC8, 0xFFFFFF};

    static {
        lists.put("base", new LinkedHashMap<>());
        lists.put("sus", new LinkedHashMap<>());
        lists.put("esp", new LinkedHashMap<>());
    }

    public static String name(Block b) { return BuiltInRegistries.BLOCK.getKey(b).getPath(); }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("siren-client.json"); }

    public static void resetList(String name) {
        Map<Block, Integer> m = lists.get(name);
        m.clear();
        if (name.equals("esp")) {
            m.put(Blocks.CHEST, 0xC8873A);
            m.put(Blocks.TRAPPED_CHEST, 0xE04A2E);
            m.put(Blocks.BARREL, 0xB5762B);
            m.put(Blocks.ENDER_CHEST, 0x7A3CFF);
            m.put(Blocks.SPAWNER, 0xFF2D2D);
            m.put(Blocks.BEACON, 0x3DF5FF);
            for (Block b : BuiltInRegistries.BLOCK) if (b instanceof ShulkerBoxBlock) m.put(b, 0xC070FF);
            return;
        }
        for (Block b : BuiltInRegistries.BLOCK) {
            int w = name.equals("base") ? baseWeight(b) : susWeight(b);
            if (w > 0) m.put(b, w);
        }
    }

    // Defaults use class/ID checks, not tags (tags are not loaded at client init).
    private static int baseWeight(Block b) {
        if (b == Blocks.BEACON) return 5;
        if (b instanceof ShulkerBoxBlock || b == Blocks.ENDER_CHEST || b == Blocks.RESPAWN_ANCHOR) return 3;
        if (b instanceof BedBlock) return 3;
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) return 2;
        if (b instanceof SignBlock) return 2;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return 1;
        if (b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE || b instanceof AnvilBlock) return 1;
        if (b == Blocks.BREWING_STAND || b == Blocks.LECTERN || b == Blocks.GRINDSTONE) return 1;
        if (b == Blocks.HOPPER || b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.OBSERVER) return 1;
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN) return 1;
        return 0;
    }

    private static int susWeight(Block b) {
        if (b instanceof DoorBlock) return 2;
        if (b instanceof TrapDoorBlock || b instanceof FenceBlock) return 1;
        String p = name(b);
        if (p.endsWith("_planks") || p.endsWith("_wool") || p.contains("concrete")
                || p.endsWith("glazed_terracotta") || p.endsWith("glass") || p.endsWith("glass_pane")
                || p.endsWith("bricks") || p.startsWith("polished_") || p.equals("cobblestone")) return 1;
        return 0;
    }

    public static void load() {
        for (String n : lists.keySet()) resetList(n);
        Path f = file();
        try {
            if (!Files.exists(f)) { save(); return; }
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (root.has("minBase")) minBase = root.get("minBase").getAsInt();
            if (root.has("minSus")) minSus = root.get("minSus").getAsInt();
            if (root.has("espAlpha")) espAlpha = root.get("espAlpha").getAsInt();
            if (root.has("espDist")) espDist = root.get("espDist").getAsInt();
            if (root.has("beams")) beams = root.get("beams").getAsBoolean();
            if (root.has("plates")) plates = root.get("plates").getAsBoolean();
            if (root.has("notify")) notify = root.get("notify").getAsBoolean();
            if (root.has("waypoints")) {
                waypoints.clear();
                for (JsonElement el : root.getAsJsonArray("waypoints")) {
                    JsonObject o = el.getAsJsonObject();
                    waypoints.add(new Waypoint(o.get("name").getAsString(), o.get("x").getAsInt(),
                            o.get("y").getAsInt(), o.get("z").getAsInt()));
                }
            }
            if (root.has("lists")) {
                JsonObject ls = root.getAsJsonObject("lists");
                for (String name : lists.keySet()) {
                    if (!ls.has(name)) continue;
                    Map<Block, Integer> m = lists.get(name);
                    m.clear();
                    for (var e : ls.getAsJsonObject(name).entrySet()) {
                        Identifier id = Identifier.tryParse(e.getKey());
                        if (id != null && BuiltInRegistries.BLOCK.containsKey(id))
                            m.put(BuiltInRegistries.BLOCK.getValue(id), e.getValue().getAsInt());
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("[SirenClient] config load failed, using defaults: " + ex);
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("minBase", minBase);
            root.addProperty("minSus", minSus);
            root.addProperty("espAlpha", espAlpha);
            root.addProperty("espDist", espDist);
            root.addProperty("beams", beams);
            root.addProperty("plates", plates);
            root.addProperty("notify", notify);
            JsonArray wps = new JsonArray();
            for (Waypoint w : waypoints) {
                JsonObject o = new JsonObject();
                o.addProperty("name", w.name());
                o.addProperty("x", w.x());
                o.addProperty("y", w.y());
                o.addProperty("z", w.z());
                wps.add(o);
            }
            root.add("waypoints", wps);
            JsonObject ls = new JsonObject();
            lists.forEach((name, m) -> {
                JsonObject o = new JsonObject();
                m.forEach((b, w) -> o.addProperty(BuiltInRegistries.BLOCK.getKey(b).toString(), w));
                ls.add(name, o);
            });
            root.add("lists", ls);
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ex) {
            System.err.println("[SirenClient] config save failed: " + ex);
        }
    }
}
