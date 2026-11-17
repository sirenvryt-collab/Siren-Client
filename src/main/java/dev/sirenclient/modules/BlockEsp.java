package dev.sirenclient.modules;

import dev.sirenclient.Config;
import dev.sirenclient.TestBases;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;

/** Highlights blocks from your ESP list (default: storage + spawners) through walls. */
public class BlockEsp extends ChunkScanModule<BlockEsp.Found> {
    private static final int MAX_PER_CHUNK = 64;

    /** Packed positions: xyz triples; rgb per position. */
    public record Found(int[] xyz, int[] rgb) {
        public int size() { return rgb.length; }
    }

    public BlockEsp() { super("BlockESP", "Highlights chosen blocks through walls"); }

    @Override protected boolean keep(Found f) {
        return f.size() > 0 && !TestBases.isHidden(f.xyz()[0] >> 4, f.xyz()[2] >> 4); // larp: hidden until relog
    }

    @Override
    protected Found scan(LevelChunk chunk) {
        Map<Block, Integer> w = Config.lists.get("esp");
        if (w.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (Structures.isTrialChunk(chunk)) return null;   // no chests/spawners ESP inside trial chambers
        int minSec = mc.level.dimensionType().minY() >> 4;
        int[] xyz = new int[MAX_PER_CHUNK * 3];
        int[] rgb = new int[MAX_PER_CHUNK];
        int n = 0;
        LevelChunkSection[] secs = chunk.getSections();
        int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < secs.length && n < MAX_PER_CHUNK; i++) {
            LevelChunkSection s = secs[i];
            if (s == null || s.hasOnlyAir()) continue;
            if (!s.maybeHas(st -> w.containsKey(st.getBlock()))) continue;
            int oy = (minSec + i) << 4;
            for (int x = 0; x < 16 && n < MAX_PER_CHUNK; x++)
                for (int y = 0; y < 16 && n < MAX_PER_CHUNK; y++)
                    for (int z = 0; z < 16 && n < MAX_PER_CHUNK; z++) {
                        BlockState st = s.getBlockState(x, y, z);
                        Integer c = w.get(st.getBlock());
                        if (c == null) continue;
                        xyz[n * 3] = ox + x; xyz[n * 3 + 1] = oy + y; xyz[n * 3 + 2] = oz + z;
                        rgb[n++] = c;
                    }
        }
        if (n == 0) return null;
        return new Found(java.util.Arrays.copyOf(xyz, n * 3), java.util.Arrays.copyOf(rgb, n));
    }
}
