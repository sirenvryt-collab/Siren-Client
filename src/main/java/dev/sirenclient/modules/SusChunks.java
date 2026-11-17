package dev.sirenclient.modules;

import dev.sirenclient.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Looks for hidden builds. In the overworld only blocks buried well below the surface count, so surface
 * houses never show up (BaseFinder covers those). Chunks near natural structures are ignored, and a chunk
 * needs a dense cluster of man-made blocks plus a total score above the minimum.
 */
public class SusChunks extends HitScanModule {
    private final Set<Long> natural = new HashSet<>();
    private int ticks = 0;

    public SusChunks() { super("SusChunks", "Hidden builds, ignoring natural structures"); }

    @Override protected int minScore() { return Config.minSus; }

    private boolean nearNatural(int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                if (natural.contains(key(cx + dx, cz + dz))) return true;
        return false;
    }

    @Override
    public void onTick() {
        super.onTick();
        // Neighbours may be classified after a chunk was already accepted, so re-check periodically.
        if (++ticks % 40 == 0 && !natural.isEmpty())
            results.keySet().removeIf(k -> nearNatural((int) (k >> 32), (int) (long) k));
    }

    private static int[] floorMap(LevelChunk chunk) {
        int[] f = new int[256];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                f[x * 16 + z] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        return f;
    }

    @Override
    protected Hit scan(LevelChunk chunk) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        int cx = ox >> 4, cz = oz >> 4;
        boolean overworld = mc.level.dimension() == Level.OVERWORLD;
        int minSec = mc.level.dimensionType().minY() >> 4;
        Map<Block, Integer> w = Config.lists.get("sus");
        Map<Block, Integer> mark = Structures.map();
        int[] counts = new int[Structures.N];

        int[] floor = null;
        int score = 0, bestSec = 0, best = 0, bx = 0, by = 0, bz = 0;
        LevelChunkSection[] secs = chunk.getSections();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            if (s == null || s.hasOnlyAir()) continue;
            if (!s.maybeHas(st -> { Block b = st.getBlock(); return w.containsKey(b) || mark.containsKey(b); })) continue;
            int oy = (minSec + i) << 4, sec = 0;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Block b = s.getBlockState(x, y, z).getBlock();
                        Integer mk = mark.get(b);
                        if (mk != null) counts[mk / 1000] += mk % 1000;
                        Integer v = w.get(b);
                        if (v == null) continue;
                        int wy = oy + y;
                        if (overworld) {
                            if (floor == null) floor = floorMap(chunk);
                            if (wy >= floor[x * 16 + z] - Config.susDepth) continue; // not buried
                        }
                        sec += v;
                        if (v > best) { best = v; bx = ox + x; by = wy; bz = oz + z; }
                    }
            score += sec;
            bestSec = Math.max(bestSec, sec);
        }

        long k = key(cx, cz);
        if (Structures.isNatural(counts)) { natural.add(k); return null; }
        natural.remove(k);
        if (nearNatural(cx, cz)) return null;
        if (score == 0 || bestSec < Config.susDensity) return null;
        return new Hit(score + ChunkAge.trailBonus(cx, cz), bx, by, bz);
    }

    @Override
    public void clear() {
        super.clear();
        natural.clear();
        ChunkAge.clear();
    }
}
