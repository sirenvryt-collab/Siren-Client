package dev.sirenclient.modules;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/**
 * NewChunks-style heuristic: a chunk that arrives with flowing liquid was just generated (new).
 * A chunk whose liquids are all still has been loaded before (old).
 * Old chunks bordering new ones are the classic sign that a player passed through.
 * Only chunks loaded while SusChunks is enabled get classified.
 */
public final class ChunkAge {
    private static final Map<Long, Boolean> isNew = new HashMap<>(); // true = new, false = old

    private ChunkAge() {}

    public static void clear() { isNew.clear(); }
    public static Map<Long, Boolean> all() { return isNew; }

    public static void classify(LevelChunk chunk) {
        int cx = chunk.getPos().getMinBlockX() >> 4, cz = chunk.getPos().getMinBlockZ() >> 4;
        long k = ChunkScanModule.key(cx, cz);
        boolean sawFluid = false;
        for (LevelChunkSection s : chunk.getSections()) {
            if (s == null || s.hasOnlyAir()) continue;
            if (!s.maybeHas(st -> st.getBlock() instanceof LiquidBlock)) continue;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        var st = s.getBlockState(x, y, z);
                        if (!(st.getBlock() instanceof LiquidBlock)) continue;
                        FluidState f = st.getFluidState();
                        if (f.isEmpty()) continue;
                        sawFluid = true;
                        if (!f.isSource()) { isNew.put(k, true); return; }
                    }
        }
        if (sawFluid) isNew.put(k, false);
    }

    /** Bonus for old chunks that border new ones. */
    public static int trailBonus(int cx, int cz) {
        Boolean self = isNew.get(ChunkScanModule.key(cx, cz));
        if (self == null || self) return 0;
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Boolean.TRUE.equals(isNew.get(ChunkScanModule.key(cx + dx, cz + dz)))) n++;
            }
        return n == 0 ? 0 : 8 + n * 2;
    }
}
