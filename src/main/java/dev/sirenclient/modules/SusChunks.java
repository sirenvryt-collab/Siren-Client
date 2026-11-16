package dev.sirenclient.modules;

import dev.sirenclient.Config;
import net.minecraft.world.level.chunk.LevelChunk;

/** Flags chunks with lots of man-made building blocks and/or an "old chunk in new terrain" trail pattern. */
public class SusChunks extends HitScanModule {
    public SusChunks() { super("SusChunks", "Man-made blocks and old-chunk trails"); }

    @Override protected int minScore() { return Config.minSus; }

    @Override
    protected Hit scan(LevelChunk chunk) {
        Hit h = scanWeights(chunk, Config.lists.get("sus"));
        int cx = chunk.getPos().getMinBlockX() >> 4, cz = chunk.getPos().getMinBlockZ() >> 4;
        int bonus = ChunkAge.trailBonus(cx, cz);
        if (h == null && bonus == 0) return null;
        if (h == null) return new Hit(bonus, chunk.getPos().getMinBlockX() + 8, 64, chunk.getPos().getMinBlockZ() + 8);
        return new Hit(h.score() + bonus, h.x(), h.y(), h.z());
    }

    @Override
    public void clear() {
        super.clear();
        ChunkAge.clear();
    }
}
