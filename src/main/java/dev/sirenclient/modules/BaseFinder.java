package dev.sirenclient.modules;

import dev.sirenclient.Config;
import net.minecraft.world.level.chunk.LevelChunk;

public class BaseFinder extends HitScanModule {
    public BaseFinder() { super("BaseFinder", "Scores chunks by player-made blocks"); }

    @Override protected int minScore() { return Config.minBase; }

    @Override
    protected Hit scan(LevelChunk chunk) {
        return scanWeights(chunk, Config.lists.get("base"));
    }
}
