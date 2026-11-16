package dev.sirenclient.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/** Scans loaded chunks a couple at a time and keeps one result per chunk. */
public abstract class ChunkScanModule<T> extends Module {
    private static final int CHUNKS_PER_TICK = 2;

    protected final Map<Long, T> results = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private int cooldown = 0;

    protected ChunkScanModule(String name, String desc) { super(name, desc); }

    protected abstract T scan(LevelChunk chunk);
    protected abstract boolean keep(T result);
    protected void onFound(T result) {}

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    @Override public void onDisable() { queue.clear(); }

    @Override
    public void clear() {
        results.clear();
        queue.clear();
    }

    public int count() { return results.size(); }
    public Collection<T> values() { return results.values(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        if (queue.isEmpty() && --cooldown <= 0) {
            int r = mc.options.getEffectiveRenderDistance();
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            List<long[]> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    list.add(new long[]{pcx + dx, pcz + dz, (long) dx * dx + (long) dz * dz});
            list.sort(Comparator.comparingLong(a -> a[2]));
            for (long[] a : list) queue.add(key((int) a[0], (int) a[1]));
            cooldown = 100; // about 5s between full passes
        }

        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
            long k = queue.poll();
            int cx = (int) (k >> 32), cz = (int) k;
            if (!level.hasChunk(cx, cz)) continue;
            LevelChunk chunk = level.getChunk(cx, cz);
            T r = scan(chunk);
            if (r != null && keep(r)) {
                boolean fresh = !results.containsKey(k);
                results.put(k, r);
                if (fresh) onFound(r);
            } else results.remove(k);
        }
    }
}
