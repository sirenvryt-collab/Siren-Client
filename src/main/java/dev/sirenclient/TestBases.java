package dev.sirenclient;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Singleplayer test helper: builds known bases around you so every module has something to find, and
 * pastes random schematics from siren-schematics/ in the game folder deep underground.
 */
public final class TestBases {
    public static final List<String> info = new CopyOnWriteArrayList<>();       // built-in test bases
    public static final List<String> schemInfo = new CopyOnWriteArrayList<>();  // pasted schematics

    private static final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
    private static boolean hooked = false;
    private static int unknown = 0; // unknown block types met while loading the current schematic

    private static final int BLOCKS_PER_TICK = 20_000;
    private static final int MAX_VOLUME = 12_000_000;
    private static final int TOP_Y = -10;      // top of every pasted base
    private static final int FLOOR_Y = -60;    // lowest the bottom may go

    private TestBases() {}

    // =====================================================================
    // Built-in test bases
    // =====================================================================
    public static String spawn() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD)
            return "Be in the overworld first.";

        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int ax = px + 150, az = pz;          // surface house
        int bx = px, bz = pz + 250;          // surface house, more storage
        int cx = px - 220, cz = pz;          // buried room (should be flagged by SusChunks)
        int dx = px, dz = pz - 300;          // buried room + bell (natural marker, should NOT be flagged)

        info.clear();
        info.add("House A:  x " + ax + "  z " + az);
        info.add("House B:  x " + bx + "  z " + bz);
        info.add("Buried:   x " + cx + "  z " + cz + "  y -30");
        info.add("Decoy:    x " + dx + "  z " + dz + "  y -30");

        server.execute(() -> {
            ServerLevel level = server.overworld();
            house(level, ax, az, 0);
            house(level, bx, bz, 4);
            buried(level, cx, cz, false);
            buried(level, dx, dz, true);
        });
        return "Placing bases (may stutter for a second).";
    }

    public static String gear() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.getInventory().add(new ItemStack(Items.ELYTRA));
                p.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 64));
            }
        });
        return "Gave elytra + 64 rockets. Equip the elytra.";
    }

    private static void set(ServerLevel l, int x, int y, int z, Block b) {
        l.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 2); // flag 2: no neighbour updates
    }

    private static void house(ServerLevel l, int x0, int z0, int extraChests) {
        int y = l.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + 4, z0 + 4);
        for (int dx = 0; dx < 9; dx++)
            for (int dz = 0; dz < 9; dz++) {
                set(l, x0 + dx, y - 1, z0 + dz, Blocks.OAK_PLANKS);
                boolean edge = dx == 0 || dx == 8 || dz == 0 || dz == 8;
                for (int dy = 0; dy <= 4; dy++) {
                    Block b = dy == 4 ? Blocks.OAK_PLANKS : (edge ? Blocks.OAK_PLANKS : Blocks.AIR);
                    set(l, x0 + dx, y + dy, z0 + dz, b);
                }
            }
        set(l, x0 + 4, y, z0, Blocks.AIR);
        set(l, x0 + 4, y + 1, z0, Blocks.AIR);
        storage(l, x0, y, z0, 6 + extraChests);
    }

    private static void buried(ServerLevel l, int x0, int z0, boolean decoy) {
        int y0 = -30;
        for (int dx = 0; dx < 9; dx++)
            for (int dz = 0; dz < 9; dz++)
                for (int dy = 0; dy < 5; dy++) {
                    boolean shell = dx == 0 || dx == 8 || dz == 0 || dz == 8 || dy == 0 || dy == 4;
                    set(l, x0 + dx, y0 + dy, z0 + dz, shell ? Blocks.OAK_PLANKS : Blocks.AIR);
                }
        storage(l, x0, y0 + 1, z0, 6);
        if (decoy) set(l, x0 + 4, y0 + 1, z0 + 4, Blocks.BELL);
    }

    private static void storage(ServerLevel l, int x0, int y, int z0, int chests) {
        for (int i = 0; i < Math.min(chests, 7); i++) set(l, x0 + 1 + i, y, z0 + 1, Blocks.CHEST);
        set(l, x0 + 1, y, z0 + 7, Blocks.BARREL);
        set(l, x0 + 2, y, z0 + 7, Blocks.BARREL);
        set(l, x0 + 4, y, z0 + 7, Blocks.SHULKER_BOX);
        set(l, x0 + 5, y, z0 + 7, Blocks.SHULKER_BOX);
        set(l, x0 + 6, y, z0 + 7, Blocks.ENDER_CHEST);
        set(l, x0 + 7, y, z0 + 7, Blocks.FURNACE);
        set(l, x0 + 7, y, z0 + 1, Blocks.CRAFTING_TABLE);
    }

    // =====================================================================
    // Schematic loader: .schem (Sponge v2/v3), .litematic, vanilla .nbt
    // =====================================================================
    private record Part(int ox, int oy, int oz, int sx, int sy, int sz, BlockState[] palette, int[] idx) {}

    private record Schem(List<Part> parts, int w, int h, int l) {}

    private static final class Job {
        final ServerLevel level;
        final String name;
        final List<Part> parts;
        final int bx, by, bz;
        int partIdx = 0, cell = 0;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        Job(ServerLevel level, String name, List<Part> parts, int bx, int by, int bz) {
            this.level = level; this.name = name; this.parts = parts; this.bx = bx; this.by = by; this.bz = bz;
        }

        /** Places up to `budget` blocks; returns true when the whole schematic is done. */
        boolean step(int budget) {
            while (budget > 0 && partIdx < parts.size()) {
                Part p = parts.get(partIdx);
                int vol = p.sx() * p.sy() * p.sz();
                while (cell < vol && budget > 0) {
                    int i = cell++;
                    budget--;
                    int pi = p.idx()[i];
                    if (pi < 0 || pi >= p.palette().length || p.palette()[pi] == null) continue;
                    int x = i % p.sx(), t = i / p.sx(), z = t % p.sz(), y = t / p.sz();
                    pos.set(bx + p.ox() + x, by + p.oy() + y, bz + p.oz() + z);
                    level.setBlock(pos, p.palette()[pi], 2);
                }
                if (cell >= vol) { partIdx++; cell = 0; }
            }
            return partIdx >= parts.size();
        }
    }

    /** The folder for your schematics (created if missing). */
    public static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("siren-schematics");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    public static String loadSchematics(int count) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD)
            return "Be in the overworld first.";

        Path dir = folder();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".schem") || n.endsWith(".litematic") || n.endsWith(".nbt");
            }).sorted().toList();
        } catch (IOException e) {
            return "Could not read folder: " + e.getMessage();
        }
        schemInfo.clear();
        if (files.isEmpty())
            return "No files. Put .schem / .litematic / .nbt in siren-schematics/ (folder is ready).";
        List<Path> shuffled = new ArrayList<>(files);
        Collections.shuffle(shuffled); // random picks each time

        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int x = px + 160, z = pz, rowDepth = 0, inRow = 0, queued = 0;
        ServerLevel level = server.overworld();
        for (Path f : shuffled) {
            if (queued >= count) break;
            String name = f.getFileName().toString();
            String shortName = name.length() > 22 ? name.substring(0, 21) + "~" : name;
            Schem sc;
            try {
                unknown = 0;
                sc = load(f);
            } catch (Exception e) {
                schemInfo.add(shortName + ": failed (" + e.getMessage() + ")");
                continue;
            }
            if (sc.h() > 110) { schemInfo.add(shortName + ": too tall to bury (" + sc.h() + ")"); continue; }
            int by = Math.max(FLOOR_Y, TOP_Y - sc.h() + 1);
            if (inRow >= 4) { x = px + 160; z += rowDepth + 64; rowDepth = 0; inRow = 0; }
            schemInfo.add(shortName + ": x " + x + " z " + z + " y " + by + (unknown > 0 ? " (" + unknown + " unknown)" : ""));
            jobs.add(new Job(level, name, sc.parts(), x, by, z));
            x += sc.w() + 64;
            rowDepth = Math.max(rowDepth, sc.l());
            inRow++;
            queued++;
        }
        schemInfo.add(0, "Random picks: " + queued + " of " + files.size() + " files");
        if (!hooked) { ServerTickEvents.END_SERVER_TICK.register(TestBases::tick); hooked = true; }
        return queued + " random schematic(s) queued, placed a little each tick.";
    }

    private static void tick(MinecraftServer server) {
        Job j = jobs.peek();
        if (j == null) return;
        if (j.step(BLOCKS_PER_TICK)) {
            jobs.poll();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Siren] Placed " + j.name), false);
            });
        }
    }

    private static Schem load(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase();
        Map<String, Object> root = asMap(Nbt.read(file));
        if (n.endsWith(".litematic")) return litematic(root);
        if (n.endsWith(".nbt")) return vanilla(root);
        return sponge(root);
    }

    private static Schem sponge(Map<String, Object> m) throws IOException {
        Map<String, Object> s = m.containsKey("Schematic") ? asMap(m.get("Schematic")) : m;
        int w = num(s.get("Width")), h = num(s.get("Height")), l = num(s.get("Length"));
        checkVolume(w, h, l);
        Map<String, Object> palMap;
        byte[] data;
        if (s.get("Blocks") instanceof Map) {
            Map<String, Object> b = asMap(s.get("Blocks"));
            palMap = asMap(b.get("Palette"));
            data = (byte[]) b.get("Data");
        } else {
            palMap = asMap(s.get("Palette"));
            data = (byte[]) s.get("BlockData");
        }
        if (palMap == null || data == null) throw new IOException("not a Sponge schematic");
        int max = 0;
        for (Object v : palMap.values()) max = Math.max(max, num(v));
        BlockState[] pal = new BlockState[max + 1];
        for (var e : palMap.entrySet()) pal[num(e.getValue())] = fromString(e.getKey());

        int vol = w * h * l;
        int[] idx = new int[vol];
        int pos = 0;
        for (int i = 0; i < vol && pos < data.length; i++) {
            int v = 0, shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            idx[i] = v;
        }
        return new Schem(List.of(new Part(0, 0, 0, w, h, l, pal, idx)), w, h, l);
    }

    private static Schem vanilla(Map<String, Object> m) throws IOException {
        List<Object> size = asList(m.get("size"));
        List<Object> palList = asList(m.get("palette"));
        if (palList == null && m.get("palettes") != null) palList = asList(asList(m.get("palettes")).get(0));
        if (size == null || palList == null) throw new IOException("not a structure .nbt");
        int w = num(size.get(0)), h = num(size.get(1)), l = num(size.get(2));
        checkVolume(w, h, l);
        List<BlockState> pal = new ArrayList<>();
        for (Object o : palList) {
            Map<String, Object> c = asMap(o);
            pal.add(state((String) c.get("Name"), strProps(c.get("Properties"))));
        }
        int air = -1;
        for (int i = 0; i < pal.size(); i++) if (pal.get(i).getBlock() == Blocks.AIR) air = i;
        if (air < 0) { pal.add(Blocks.AIR.defaultBlockState()); air = pal.size() - 1; }
        int[] idx = new int[w * h * l];
        Arrays.fill(idx, air);
        List<Object> blocks = asList(m.get("blocks"));
        if (blocks != null)
            for (Object o : blocks) {
                Map<String, Object> c = asMap(o);
                List<Object> p = asList(c.get("pos"));
                int x = num(p.get(0)), y = num(p.get(1)), z = num(p.get(2));
                if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) continue;
                idx[(y * l + z) * w + x] = num(c.get("state"));
            }
        return new Schem(List.of(new Part(0, 0, 0, w, h, l, pal.toArray(new BlockState[0]), idx)), w, h, l);
    }

    private static Schem litematic(Map<String, Object> m) throws IOException {
        Map<String, Object> regions = asMap(m.get("Regions"));
        if (regions == null) throw new IOException("not a litematic");
        List<Part> raw = new ArrayList<>();
        int gMinX = Integer.MAX_VALUE, gMinY = Integer.MAX_VALUE, gMinZ = Integer.MAX_VALUE;
        int gMaxX = Integer.MIN_VALUE, gMaxY = Integer.MIN_VALUE, gMaxZ = Integer.MIN_VALUE;
        for (Object ro : regions.values()) {
            Map<String, Object> r = asMap(ro);
            Map<String, Object> pos = asMap(r.get("Position")), size = asMap(r.get("Size"));
            int px = num(pos.get("x")), py = num(pos.get("y")), pz = num(pos.get("z"));
            int sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            checkVolume(ax, ay, az);
            int mnx = sx >= 0 ? px : px + sx + 1, mny = sy >= 0 ? py : py + sy + 1, mnz = sz >= 0 ? pz : pz + sz + 1;

            List<Object> pl = asList(r.get("BlockStatePalette"));
            long[] bits = (long[]) r.get("BlockStates");
            if (pl == null || pl.isEmpty() || bits == null) continue;
            BlockState[] pal = new BlockState[pl.size()];
            for (int i = 0; i < pal.length; i++) {
                Map<String, Object> c = asMap(pl.get(i));
                pal[i] = state((String) c.get("Name"), strProps(c.get("Properties")));
            }
            int bpe = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
            long mask = (1L << bpe) - 1;
            int vol = ax * ay * az;
            int[] idx = new int[vol];
            for (int i = 0; i < vol; i++) {
                long start = (long) i * bpe;
                int sl = (int) (start >> 6), so = (int) (start & 63);
                int el = (int) (((long) (i + 1) * bpe - 1) >> 6);
                if (sl >= bits.length) break;
                long v;
                if (sl == el) v = (bits[sl] >>> so) & mask;
                else v = ((bits[sl] >>> so) | (el < bits.length ? bits[el] << (64 - so) : 0)) & mask;
                idx[i] = (int) v;
            }
            raw.add(new Part(mnx, mny, mnz, ax, ay, az, pal, idx));
            gMinX = Math.min(gMinX, mnx); gMinY = Math.min(gMinY, mny); gMinZ = Math.min(gMinZ, mnz);
            gMaxX = Math.max(gMaxX, mnx + ax); gMaxY = Math.max(gMaxY, mny + ay); gMaxZ = Math.max(gMaxZ, mnz + az);
        }
        if (raw.isEmpty()) throw new IOException("no regions");
        List<Part> parts = new ArrayList<>();
        for (Part p : raw)
            parts.add(new Part(p.ox() - gMinX, p.oy() - gMinY, p.oz() - gMinZ, p.sx(), p.sy(), p.sz(), p.palette(), p.idx()));
        return new Schem(parts, gMaxX - gMinX, gMaxY - gMinY, gMaxZ - gMinZ);
    }

    private static void checkVolume(int w, int h, int l) throws IOException {
        if (w <= 0 || h <= 0 || l <= 0) throw new IOException("empty");
        if ((long) w * h * l > MAX_VOLUME) throw new IOException("too big");
    }

    // ---- block state helpers ----
    private static BlockState fromString(String s) {
        int br = s.indexOf('[');
        String name = br < 0 ? s : s.substring(0, br);
        Map<String, String> props = new HashMap<>();
        if (br >= 0) {
            String body = s.substring(br + 1, s.endsWith("]") ? s.length() - 1 : s.length());
            for (String kv : body.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return state(name, props);
    }

    private static BlockState state(String name, Map<String, String> props) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            unknown++;
            return Blocks.AIR.defaultBlockState();
        }
        Block b = BuiltInRegistries.BLOCK.getValue(id);
        BlockState s = b.defaultBlockState();
        if (props != null)
            for (var e : props.entrySet()) {
                Property<?> p = b.getStateDefinition().getProperty(e.getKey());
                if (p != null) s = with(s, p, e.getValue());
            }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
    }

    private static Map<String, String> strProps(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, String> out = new HashMap<>();
        for (var e : asMap(o).entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return (List<Object>) o; }

    private static int num(Object o) { return ((Number) o).intValue(); }

    // =====================================================================
    // Minimal NBT reader (gzip or raw), independent of game classes
    // =====================================================================
    private static final class Nbt {
        static Object read(Path file) throws IOException {
            try (BufferedInputStream b = new BufferedInputStream(Files.newInputStream(file))) {
                b.mark(2);
                int b0 = b.read(), b1 = b.read();
                b.reset();
                InputStream src = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(b) : b;
                DataInputStream in = new DataInputStream(new BufferedInputStream(src));
                int type = in.readUnsignedByte();
                if (type != 10) throw new IOException("not NBT");
                in.readUTF();
                return tag(in, 10);
            }
        }

        private static Object tag(DataInputStream in, int type) throws IOException {
            switch (type) {
                case 1: return in.readByte();
                case 2: return in.readShort();
                case 3: return in.readInt();
                case 4: return in.readLong();
                case 5: return in.readFloat();
                case 6: return in.readDouble();
                case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
                case 8: return in.readUTF();
                case 9: {
                    int et = in.readUnsignedByte(), n = in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 1 << 20)));
                    for (int i = 0; i < n; i++) list.add(tag(in, et));
                    return list;
                }
                case 10: {
                    Map<String, Object> m = new LinkedHashMap<>();
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == 0) break;
                        String k = in.readUTF();
                        m.put(k, tag(in, t));
                    }
                    return m;
                }
                case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
                case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
                default: throw new IOException("bad NBT tag " + type);
            }
        }
    }
}
