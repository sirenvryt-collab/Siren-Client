package dev.sirenclient.modules;

import dev.sirenclient.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Chunk scanner that produces a ranked list of scored chunks. */
public abstract class HitScanModule extends ChunkScanModule<Hit> {
    protected HitScanModule(String name, String desc) { super(name, desc); }

    protected abstract int minScore();

    private long lastNotify = -1000;

    /** Chat ping for strong new finds (score >= 2x the minimum), at most one per second. */
    @Override
    protected void onFound(Hit h) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.notify || mc.player == null || mc.level == null || h.score() < minScore() * 2) return;
        long now = mc.level.getGameTime();
        if (now - lastNotify < 20) return;
        lastNotify = now;
        mc.player.displayClientMessage(Component.literal("[Siren] " + name + ": " + h.score()
                + " pts at " + h.x() + " " + h.y() + " " + h.z()).withStyle(ChatFormatting.GREEN), false);
    }

    @Override protected boolean keep(Hit h) { return h.score() >= minScore(); }

    public List<Hit> ranked(int n) {
        List<Hit> list = new ArrayList<>(results.values());
        list.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return new ArrayList<>(list.subList(0, Math.min(n, list.size())));
    }

    public void export(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Hit h : ranked(Integer.MAX_VALUE))
            lines.add("score " + h.score() + " @ " + h.x() + " " + h.y() + " " + h.z());
        Files.write(file, lines);
    }

    /** Sums weights of listed blocks in a chunk; the palette check skips sections with none of them. */
    protected static Hit scanWeights(LevelChunk chunk, Map<Block, Integer> w) {
        if (w.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        int minSec = mc.level.dimensionType().minY() >> 4;
        int score = 0, best = 0, bx = 0, by = 0, bz = 0;
        LevelChunkSection[] secs = chunk.getSections();
        int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            if (s == null || s.hasOnlyAir()) continue;
            if (!s.maybeHas(st -> w.containsKey(st.getBlock()))) continue;
            int oy = (minSec + i) << 4;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        BlockState st = s.getBlockState(x, y, z);
                        Integer v = w.get(st.getBlock());
                        if (v == null) continue;
                        score += v;
                        if (v > best) { best = v; bx = ox + x; by = oy + y; bz = oz + z; }
                    }
        }
        return score > 0 ? new Hit(score, bx, by, bz) : null;
    }
}
