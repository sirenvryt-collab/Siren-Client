package dev.sirenclient.modules;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Marker blocks for naturally generated structures. SusChunks uses these to ignore villages, mineshafts,
 * strongholds, ancient cities, trial chambers, monuments, nether and end structures, and ruined portals.
 * Each marker block adds weight to a category; a chunk is "natural" once a category reaches its threshold.
 */
public final class Structures {
    public static final int N = 9;
    // village, mineshaft, stronghold/ruins, ancient city, trial chambers, monument, nether, end city, ruined portal
    private static final int[] NEED = {20, 20, 10, 8, 12, 20, 20, 10, 6};
    private static Map<Block, Integer> map;

    private Structures() {}

    public static boolean isNatural(int[] counts) {
        for (int i = 0; i < N; i++) if (counts[i] >= NEED[i]) return true;
        return false;
    }

    /** block -> category * 1000 + weight */
    public static Map<Block, Integer> map() {
        if (map == null) build();
        return map;
    }

    /** Blocks that make up trial chambers (tuff bricks and the copper bulbs / grates / chiseled copper). */
    private static boolean isTrialBlock(String p) {
        if (p.contains("tuff_brick") || p.equals("chiseled_tuff") || p.startsWith("polished_tuff")) return true;
        return p.contains("copper_bulb") || p.contains("copper_grate") || p.contains("chiseled_copper");
    }

    private static final int TRIAL = 4;

    /** True if this chunk is part of a trial chamber. Used so the base/ESP scanners ignore it. */
    public static boolean isTrialChunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
        Map<Block, Integer> m = map();
        int sum = 0;
        for (var sec : chunk.getSections()) {
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(st -> { Integer v = m.get(st.getBlock()); return v != null && v / 1000 == TRIAL; })) continue;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Integer v = m.get(sec.getBlockState(x, y, z).getBlock());
                        if (v != null && v / 1000 == TRIAL) sum += v % 1000;
                    }
            if (sum >= NEED[TRIAL]) return true;
        }
        return false;
    }

    private static void build() {
        Map<Block, Integer> m = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
            int c = -1, w = 1;
            switch (p) {
                case "bell" -> { c = 0; w = 20; }
                case "dirt_path" -> { c = 0; w = 1; }
                case "hay_block" -> { c = 0; w = 3; }
                case "composter", "fletching_table", "cartography_table", "smithing_table", "loom", "stonecutter" -> { c = 0; w = 5; }
                case "cobweb" -> { c = 1; w = 4; }
                case "rail", "powered_rail", "detector_rail", "activator_rail" -> { c = 1; w = 1; }
                case "end_portal_frame" -> { c = 2; w = 10; }
                case "cracked_stone_bricks" -> { c = 2; w = 3; }
                case "mossy_stone_bricks", "mossy_cobblestone" -> { c = 2; w = 2; }
                case "sculk", "sculk_vein" -> { c = 3; w = 1; }
                case "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst" -> { c = 3; w = 4; }
                case "sculk_shrieker", "reinforced_deepslate" -> { c = 3; w = 8; }
                case "trial_spawner", "vault" -> { c = 4; w = 6; }
                case "prismarine", "prismarine_bricks", "dark_prismarine", "wet_sponge" -> { c = 5; w = 1; }
                case "sea_lantern" -> { c = 5; w = 2; }
                case "end_rod" -> { c = 7; w = 2; }
                case "end_stone_bricks" -> { c = 7; w = 1; }
                case "crying_obsidian" -> { c = 8; w = 3; }
                default -> {
                    if (isTrialBlock(p)) { c = 4; w = p.contains("copper") ? 2 : 1; }
                    else if (p.contains("nether_brick") || p.contains("blackstone")) { c = 6; w = 1; }
                    else if (p.contains("purpur")) { c = 7; w = 1; }
                }
            }
            if (c >= 0) m.put(b, c * 1000 + w);
        }
        map = m;
    }
}
