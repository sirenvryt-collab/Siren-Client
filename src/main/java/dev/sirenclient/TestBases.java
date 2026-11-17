package dev.sirenclient;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Larp mode (singleplayer only). While you glide around in survival, a base is placed far ahead of you now
 * and then. Surface bases show up in the ESP as soon as their chunks load. Buried bases are hidden from the
 * ESP until you have been below Y 0 and relogged, so it looks like you had to dig down and reconnect to
 * make the scanner pick them up. "Get gear" hands out base-raiding gear.
 */
public final class TestBases {
    public static final List<String> info = new CopyOnWriteArrayList<>();   // recent spawns, shown in the menu
    /** Average seconds of gliding between bases (each gap is randomised +-40%). Set from the menu slider. */
    public static int avgSeconds = 150;

    private static final Random RNG = new Random();
    private static final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
    private static boolean hooked = false;
    private static boolean larp = false;
    private static int unknown = 0; // unknown block types met while loading the current schematic

    // spawn timing
    private static int flightTicks = 0, nextAt = 0;
    private static String world = "";

    // hide-until-relog state (touched from client and server threads, so the list is copy-on-write)
    private record Box(int minCx, int minCz, int maxCx, int maxCz) {}
    private static final List<Box> hidden = new CopyOnWriteArrayList<>();
    private static boolean minedDown = false;
    /** Recommended "dig down here" spots {x, z}, one per hidden site. They vanish when the hidden sites are revealed. */
    private static final List<int[]> digSpots = new CopyOnWriteArrayList<>();
    /** Menu toggle: draw the beacon lines over the dig spots. */
    public static boolean showDigBeacons = true;
    private static Object armedLevel = null;

    // staggered reveal after the relog (client thread only) + position history for the fake rubberband
    private static final List<Box> revealQueue = new ArrayList<>();
    private static boolean revealing = false;
    private static int revealWait = 0, revealTotal = 0;
    private static final ArrayDeque<Vec3> posHistory = new ArrayDeque<>();

    private static final int BLOCKS_PER_TICK = 6_000;
    private static final int MAX_VOLUME = 12_000_000;
    private static final int TOP_Y = -10;      // top of every pasted schematic
    private static final int FLOOR_Y = -60;    // lowest a pasted schematic may go

    private static final Block[] SURFACE_WALLS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS,
            Blocks.BIRCH_PLANKS, Blocks.STONE_BRICKS, Blocks.COBBLESTONE};
    private static final Block[] BURIED_WALLS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS,
            Blocks.BIRCH_PLANKS};

    private TestBases() {}

    // =====================================================================
    // Larp switch + timing
    // =====================================================================
    public static boolean isLarp() { return larp; }
    public static int hiddenCount() { return hidden.size(); }
    public static boolean isMinedDown() { return minedDown; }
    public static boolean isRevealing() { return revealing; }
    public static int revealTotal() { return revealTotal; }
    public static int revealDone() { return revealTotal - revealQueue.size(); }
    public static List<int[]> digSpots() { return showDigBeacons ? digSpots : List.of(); }

    public static String setLarp(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (on && mc.getSingleplayerServer() == null) return "Singleplayer only.";
        larp = on;
        if (on) {
            ensureHooks();
            flightTicks = 0;
            nextAt = rollInterval();
        }
        return on ? "Larp on. Glide around and bases will appear ahead." : "Larp off.";
    }

    private static void ensureHooks() {
        if (hooked) return;
        hooked = true;
        ServerTickEvents.END_SERVER_TICK.register(TestBases::tick);
        ClientTickEvents.END_CLIENT_TICK.register(TestBases::clientTick);
    }

    private static int rollInterval() {
        double dry = RNG.nextDouble() < 0.18 ? 2.0 + RNG.nextDouble() * 1.5 : 1.0;   // now and then a long dry spell
        return (int) (avgSeconds * 20 * (0.6 + RNG.nextDouble() * 0.8) * dry);
    }

    private static void clientTick(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) return;

        // Different save: forget everything from the old one. (A relog of the same save keeps the hidden list.)
        String w = server.getWorldData().getLevelName();
        if (!w.equals(world)) {
            world = w;
            hidden.clear();
            digSpots.clear();
            revealQueue.clear();
            revealing = false;
            posHistory.clear();
            info.clear();
            minedDown = false;
            armedLevel = null;
            flightTicks = 0;
            nextAt = rollInterval();
        }

        posHistory.addLast(mc.player.position());
        while (posHistory.size() > 14) posHistory.removeFirst();

        checkReveal(mc);
        // Once you have been deep underground with a hidden base waiting, the next relog reveals it.
        if (!hidden.isEmpty() && !minedDown && !revealing && mc.player.getY() < 0) {
            minedDown = true;
            armedLevel = mc.level;
            if (larp && FakeHud.rubberband && posHistory.size() >= 10) rubberband(server, mc);
        }

        if (!larp || mc.level.dimension() != Level.OVERWORLD) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;   // survival only
        if (!mc.player.isFallFlying()) return;                           // only counts while gliding
        if (++flightTicks < nextAt) return;
        flightTicks = 0;
        nextAt = rollInterval();
        spawnAhead(mc, server);
    }

    /** Relog (or dimension change) after mining down: hidden bases become visible to the ESP. */
    private static void checkReveal(Minecraft mc) {
        if (minedDown && mc.level != null && mc.level != armedLevel) {
            digSpots.clear();                      // the beacons go away on the relog
            minedDown = false;
            armedLevel = null;
            if (!hidden.isEmpty()) {
                revealQueue.clear();
                revealQueue.addAll(hidden);
                Collections.shuffle(revealQueue, RNG);
                revealTotal = revealQueue.size();
                revealing = true;
                revealWait = 20 + RNG.nextInt(40);
                if (FakeHud.lagSpike) {            // short stutter, like a heavy chunk load
                    try { Thread.sleep(200 + RNG.nextInt(150)); } catch (InterruptedException ignored) {}
                }
            }
        }
        // Sites pop into the ESP one at a time (the scanners pick them up on their next pass).
        if (revealing) {
            if (revealQueue.isEmpty()) { revealing = false; return; }
            if (--revealWait <= 0) {
                hidden.remove(revealQueue.remove(0));
                revealWait = 30 + RNG.nextInt(80);
                if (revealQueue.isEmpty()) revealing = false;
            }
        }
    }

    /** Fake anticheat setback: snap the player back to where they were about 0.7 s ago. */
    private static void rubberband(MinecraftServer server, Minecraft mc) {
        Vec3 b = posHistory.peekFirst();
        if (b == null) return;
        float yr = mc.player.getYRot(), xr = mc.player.getXRot();
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.connection.teleport(b.x, b.y, b.z, yr, xr);
        });
    }

    /** Called by the scanners: true while this chunk belongs to a base that should not show in the ESP yet. */
    public static boolean isHidden(int cx, int cz) {
        if (hidden.isEmpty()) return false;
        for (Box b : hidden)
            if (cx >= b.minCx() && cx <= b.maxCx() && cz >= b.minCz() && cz <= b.maxCz()) return true;
        return false;
    }

    private static void hide(int x, int z, int w, int d) {
        hidden.add(new Box(x >> 4, z >> 4, (x + w - 1) >> 4, (z + d - 1) >> 4));
        // Dig spot: 18-40 blocks from the site in a random direction, so you tunnel over to it from there.
        double ang = RNG.nextDouble() * Math.PI * 2, dist = 18 + RNG.nextInt(23);
        digSpots.add(new int[]{(int) Math.floor(x + w / 2.0 + Math.cos(ang) * dist),
                (int) Math.floor(z + d / 2.0 + Math.sin(ang) * dist)});
    }

    private static void log(String s) {
        info.add(s);
        while (info.size() > 8) info.remove(0);
    }

    // =====================================================================
    // Spawning
    // =====================================================================
    /** Menu button: drops a base ahead of you right now. */
    public static String spawnNow() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD)
            return "Be in the overworld first.";
        ensureHooks();
        spawnAhead(mc, server);
        return "Placed a base ahead of you.";
    }

    /** Menu button: drops a cluster of fake spawners near bedrock ahead of you right now. */
    public static String spawnSpawnersNow() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD)
            return "Be in the overworld first.";
        ensureHooks();
        spawnAhead(mc, server, true);
        return "Placed spawner cages ahead of you (hidden until you dig down and relog).";
    }

    private static void spawnAhead(Minecraft mc, MinecraftServer server) { spawnAhead(mc, server, false); }

    private static void spawnAhead(Minecraft mc, MinecraftServer server, boolean forceSpawners) {
        var p = mc.player;
        Vec3 v = p.getDeltaMovement();
        double dx = v.x, dz = v.z;
        if (dx * dx + dz * dz < 0.01) { Vec3 l = p.getLookAngle(); dx = l.x; dz = l.z; }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) { dx = 1; dz = 0; len = 1; }
        dx /= len; dz /= len;

        // Always beyond your render distance so nothing pops in where you can see it.
        int minDist = Math.max(200, mc.options.getEffectiveRenderDistance() * 16 + 64);
        double dist = minDist + RNG.nextInt(140);
        double side = RNG.nextInt(141) - 70;
        int x = (int) Math.floor(p.getX() + dx * dist - dz * side);
        int z = (int) Math.floor(p.getZ() + dz * dist + dx * side);
        ServerLevel level = server.overworld();

        double roll = forceSpawners ? 0 : RNG.nextDouble();
        if (roll < 0.01) {                                   // spawner cages near bedrock: hidden until dig down + relog
            server.execute(() -> placeSpawners(level, x, z));
        } else if (roll < 0.41) {                            // buried: hidden until mine down + relog
            List<Path> files = schematicFiles();
            if (!files.isEmpty() && RNG.nextDouble() < 0.6) {
                queueSchematic(files.get(RNG.nextInt(files.size())), level, x, z);
                return;
            }
            server.execute(() -> placeBuried(level, x, z));
        } else {                                             // surface: shows once its chunks load
            server.execute(() -> { if (!placeSurface(level, x, z)) placeBuried(level, x, z); });
        }
    }

    private static boolean placeSurface(ServerLevel l, int x0, int z0) {
        for (int a = 0; a < 6; a++) {
            int w = 7 + RNG.nextInt(5), d = 7 + RNG.nextInt(5);
            int ax = x0 + (a == 0 ? 0 : RNG.nextInt(97) - 48), az = z0 + (a == 0 ? 0 : RNG.nextInt(97) - 48);
            int mx = ax + w / 2, mz = az + d / 2;
            int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz);
            if (y <= 63) continue;                                                        // ocean / beach
            if (!l.getBlockState(new BlockPos(mx, y - 1, mz)).getFluidState().isEmpty()) continue;
            int y2 = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ax, az);
            int y3 = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ax + w - 1, az + d - 1);
            if (Math.abs(y2 - y) > 4 || Math.abs(y3 - y) > 4) continue;                    // too steep
            Block wall = SURFACE_WALLS[RNG.nextInt(SURFACE_WALLS.length)];
            house(l, ax, y, az, w, d, wall, RNG.nextDouble() < 0.2 ? -1 : 4 + RNG.nextInt(7));   // -1 = junk base
            ownerSign(l, ax + w / 2 + 1, y, az - 1);
            log("Surface: x " + ax + " z " + az);
            return true;
        }
        return false;
    }

    private static void placeBuried(ServerLevel l, int x0, int z0) {
        int w = 7 + RNG.nextInt(6), d = 7 + RNG.nextInt(6), y0 = -50 + RNG.nextInt(30);
        Block wall = BURIED_WALLS[RNG.nextInt(BURIED_WALLS.length)];
        hide(x0, z0, w, d);                                  // hidden before the first block exists
        buried(l, x0, z0, w, d, y0, wall, RNG.nextDouble() < 0.2 ? -1 : 4 + RNG.nextInt(7));
        log("Buried:  x " + x0 + " z " + z0 + " y " + y0);
    }

    // =====================================================================
    // Fake spawners (donut-smp style cages near bedrock)
    // =====================================================================
    private static final EntityType<?>[] SPAWNER_MOBS = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.CAVE_SPIDER, EntityType.ZOMBIE, EntityType.SKELETON};

    /**
     * 2-4 spawners a few blocks apart, just above the bedrock layer. They show the mob in the cage but never
     * spawn anything: every non-solid block within spawning range (x/z +-4, y +-1) is filled with deepslate.
     */
    private static void placeSpawners(ServerLevel l, int x0, int z0) {
        int span = 14;
        hide(x0, z0, span, span);                           // hidden before the first block exists
        int count = 2 + RNG.nextInt(3);
        int placed = 0, firstY = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = new BlockPos(x0 + 4 + RNG.nextInt(span - 8), -59 + RNG.nextInt(6), z0 + 4 + RNG.nextInt(span - 8));
            seal(l, pos);
            l.setBlock(pos, Blocks.SPAWNER.defaultBlockState(), 2);
            if (l.getBlockEntity(pos) instanceof SpawnerBlockEntity be)
                be.setEntityId(SPAWNER_MOBS[RNG.nextInt(SPAWNER_MOBS.length)], l.getRandom());
            if (placed++ == 0) firstY = pos.getY();
        }
        log("Cages:   x " + x0 + " z " + z0 + " y " + firstY + " (x" + placed + ")");
    }

    private static void seal(ServerLevel l, BlockPos c) {
        for (int dx = -4; dx <= 4; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = c.offset(dx, dy, dz);
                    if (!l.getBlockState(p).isCollisionShapeFullBlock(l, p))
                        l.setBlock(p, Blocks.DEEPSLATE.defaultBlockState(), 2);
                }
    }

    // =====================================================================
    // Gear
    // =====================================================================
    public static String gear() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return "Singleplayer only.";
        ensurePickHook();
        server.execute(() -> {
            var reg = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) giveGear(p, reg);
        });
        return "Gave raid gear (elytra is on, chestplate is in your inventory).";
    }

    // ---- Amethyst Pickaxe: mines a 3x3 like DonutSMP's ----
    private static final String PICK_NAME = "Amethyst Pickaxe";
    private static boolean pickHooked = false;

    private static void ensurePickHook() {
        if (pickHooked) return;
        pickHooked = true;
        PlayerBlockBreakEvents.AFTER.register(TestBases::onBreak);
    }

    /** After the block you mined breaks, also break the 8 around it. Always a vertical 3x3 wall (like DonutSMP),
     *  even when you look straight up or down. The wall faces the way you are looking horizontally. */
    private static void onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be) {
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer)) return;
        ItemStack tool = player.getMainHandItem();
        if (!tool.is(Items.NETHERITE_PICKAXE) || !PICK_NAME.equals(tool.getHoverName().getString())) return;
        Direction.Axis facing = player.getDirection().getAxis();   // horizontal facing only, ignores pitch
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                BlockPos p = facing == Direction.Axis.X ? pos.offset(0, a, b) : pos.offset(b, a, 0);
                BlockState st = sl.getBlockState(p);
                if (st.isAir() || st.getBlock() instanceof LiquidBlock || st.getDestroySpeed(sl, p) < 0) continue; // skips bedrock
                if (!player.isCreative()) Block.dropResources(st, sl, p, sl.getBlockEntity(p), player, tool);
                sl.destroyBlock(p, false, player);
            }
    }

    private static void ench(ItemStack s, HolderLookup.RegistryLookup<Enchantment> reg, ResourceKey<Enchantment> key, int lvl) {
        s.enchant(reg.getOrThrow(key), lvl);
    }

    private static ItemStack armor(Item item, HolderLookup.RegistryLookup<Enchantment> reg) {
        ItemStack s = new ItemStack(item);
        ench(s, reg, Enchantments.BLAST_PROTECTION, 4);
        ench(s, reg, Enchantments.UNBREAKING, 3);
        ench(s, reg, Enchantments.MENDING, 1);
        return s;
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    private static void giveGear(ServerPlayer p, HolderLookup.RegistryLookup<Enchantment> reg) {
        p.setItemSlot(EquipmentSlot.HEAD, armor(Items.NETHERITE_HELMET, reg));
        p.setItemSlot(EquipmentSlot.LEGS, armor(Items.NETHERITE_LEGGINGS, reg));
        p.setItemSlot(EquipmentSlot.FEET, armor(Items.NETHERITE_BOOTS, reg));

        ItemStack elytra = new ItemStack(Items.ELYTRA);
        ench(elytra, reg, Enchantments.UNBREAKING, 3);
        ench(elytra, reg, Enchantments.MENDING, 1);
        p.setItemSlot(EquipmentSlot.CHEST, elytra);
        give(p, armor(Items.NETHERITE_CHESTPLATE, reg));

        // Amethyst Pickaxe: DonutSMP-style netherite pickaxe that mines a 3x3.
        ItemStack drill = new ItemStack(Items.NETHERITE_PICKAXE);
        ench(drill, reg, Enchantments.EFFICIENCY, 10);
        ench(drill, reg, Enchantments.UNBREAKING, 5);
        ench(drill, reg, Enchantments.FORTUNE, 3);
        ench(drill, reg, Enchantments.MENDING, 1);
        drill.set(DataComponents.CUSTOM_NAME,
                Component.literal(PICK_NAME).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(false)));
        drill.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("A powerful pickaxe crafted from amethyst").withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)),
                Component.literal("that mines in a 3x3 area!").withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)))));
        give(p, drill);

        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        for (int i = 0; i < 5; i++) give(p, new ItemStack(Items.TOTEM_OF_UNDYING));
        give(p, new ItemStack(Items.END_CRYSTAL, 64));
        give(p, new ItemStack(Items.END_CRYSTAL, 64));
        give(p, new ItemStack(Items.OBSIDIAN, 64));
        give(p, new ItemStack(Items.OBSIDIAN, 64));
        give(p, new ItemStack(Items.OBSIDIAN, 64));
        give(p, new ItemStack(Items.GOLDEN_APPLE, 32));
        give(p, new ItemStack(Items.ENDER_PEARL, 16));
        give(p, new ItemStack(Items.COOKED_BEEF, 64));
        give(p, new ItemStack(Items.FIREWORK_ROCKET, 64));
    }

    // =====================================================================
    // Building
    // =====================================================================
    private static void set(ServerLevel l, int x, int y, int z, Block b) {
        l.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 2); // flag 2: no neighbour updates
    }

    private static void house(ServerLevel l, int x0, int y, int z0, int w, int d, Block wall, int chests) {
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                set(l, x0 + dx, y - 1, z0 + dz, wall);
                for (int fy = 2; fy <= 5; fy++) {            // foundation so it does not hover on slopes
                    BlockPos fp = new BlockPos(x0 + dx, y - fy, z0 + dz);
                    if (l.getBlockState(fp).isAir()) l.setBlock(fp, Blocks.COBBLESTONE.defaultBlockState(), 2);
                }
                boolean edge = dx == 0 || dx == w - 1 || dz == 0 || dz == d - 1;
                for (int dy = 0; dy <= 4; dy++) {
                    Block b = dy == 4 ? wall : (edge ? wall : Blocks.AIR);
                    set(l, x0 + dx, y + dy, z0 + dz, b);
                }
            }
        set(l, x0 + w / 2, y, z0, Blocks.AIR);
        set(l, x0 + w / 2, y + 1, z0, Blocks.AIR);
        storage(l, x0, y, z0, w, d, chests);
    }

    private static void buried(ServerLevel l, int x0, int z0, int w, int d, int y0, Block wall, int chests) {
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++)
                for (int dy = 0; dy < 5; dy++) {
                    boolean shell = dx == 0 || dx == w - 1 || dz == 0 || dz == d - 1 || dy == 0 || dy == 4;
                    set(l, x0 + dx, y0 + dy, z0 + dz, shell ? wall : Blocks.AIR);
                }
        storage(l, x0, y0 + 1, z0, w, d, chests);
    }

    private static void storage(ServerLevel l, int x0, int y, int z0, int w, int d, int chests) {
        int inner = w - 2;
        boolean junk = chests < 0;                           // decoy: a chest or two of rubbish, nothing else
        if (junk) chests = 1 + RNG.nextInt(2);
        for (int i = 0; i < Math.min(chests, inner * 2); i++) {
            BlockPos pos = new BlockPos(x0 + 1 + i % inner, y, z0 + 1 + i / inner);
            l.setBlock(pos, Blocks.CHEST.defaultBlockState(), 2);
            if (junk) fillJunk(l, pos); else fill(l, pos);
        }
        if (junk) {
            set(l, x0 + 1, y, z0 + d - 3, Blocks.TORCH);
            return;
        }
        Block[] back = {Blocks.BARREL, Blocks.BARREL, Blocks.SHULKER_BOX, Blocks.SHULKER_BOX, Blocks.ENDER_CHEST,
                Blocks.FURNACE, Blocks.CRAFTING_TABLE, Blocks.BARREL, Blocks.SHULKER_BOX};
        for (int i = 0; i < inner; i++) {
            BlockPos pos = new BlockPos(x0 + 1 + i, y, z0 + d - 2);
            Block b = back[i % back.length];
            l.setBlock(pos, b.defaultBlockState(), 2);
            if (b == Blocks.BARREL || b == Blocks.SHULKER_BOX) fill(l, pos);
        }
        set(l, x0 + 1, y, z0 + d - 3, Blocks.TORCH);
        set(l, x0 + w - 2, y, z0 + d - 3, Blocks.TORCH);
    }

    /** Puts some base-looking loot into a container. */
    private static void fill(ServerLevel l, BlockPos pos) {
        if (!(l.getBlockEntity(pos) instanceof Container c)) return;
        int n = 3 + RNG.nextInt(10);
        for (int i = 0; i < n; i++) c.setItem(RNG.nextInt(c.getContainerSize()), loot());
    }

    private static void fillJunk(ServerLevel l, BlockPos pos) {
        if (!(l.getBlockEntity(pos) instanceof Container c)) return;
        Item[] junk = {Items.ROTTEN_FLESH, Items.STICK, Items.COBBLESTONE, Items.STRING, Items.BONE, Items.DIRT, Items.COAL};
        int n = 2 + RNG.nextInt(6);
        for (int i = 0; i < n; i++) c.setItem(RNG.nextInt(c.getContainerSize()), new ItemStack(junk[RNG.nextInt(junk.length)], 1 + RNG.nextInt(20)));
    }

    /** A standing sign outside the door with a made-up owner name. */
    private static void ownerSign(ServerLevel l, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!l.getBlockState(pos).isAir() || l.getBlockState(pos.below()).isAir()) return;
        l.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 2);
        if (l.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText t = new SignText()
                    .setMessage(1, Component.literal("Owner:"))
                    .setMessage(2, Component.literal(FakeHud.randomName(RNG)));
            sign.setText(t, true);
            sign.setChanged();
        }
    }

    private static ItemStack loot() {
        return switch (RNG.nextInt(14)) {
            case 0 -> new ItemStack(Items.DIAMOND, 1 + RNG.nextInt(16));
            case 1 -> new ItemStack(Items.NETHERITE_INGOT, 1 + RNG.nextInt(4));
            case 2 -> new ItemStack(Items.TOTEM_OF_UNDYING);
            case 3 -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1 + RNG.nextInt(3));
            case 4 -> new ItemStack(Items.END_CRYSTAL, 4 + RNG.nextInt(13));
            case 5 -> new ItemStack(Items.OBSIDIAN, 16 + RNG.nextInt(49));
            case 6 -> new ItemStack(Items.ANCIENT_DEBRIS, 2 + RNG.nextInt(7));
            case 7 -> new ItemStack(Items.DIAMOND_BLOCK, 1 + RNG.nextInt(5));
            case 8 -> new ItemStack(Items.GOLD_BLOCK, 1 + RNG.nextInt(8));
            case 9 -> new ItemStack(Items.EXPERIENCE_BOTTLE, 16 + RNG.nextInt(49));
            case 10 -> new ItemStack(Items.ELYTRA);
            case 11 -> new ItemStack(Items.FIREWORK_ROCKET, 16 + RNG.nextInt(49));
            case 12 -> new ItemStack(Items.ENDER_PEARL, 4 + RNG.nextInt(13));
            default -> new ItemStack(Items.COOKED_BEEF, 16 + RNG.nextInt(49));
        };
    }

    // =====================================================================
    // Schematic loader: .schem (Sponge v2/v3), .litematic, vanilla .nbt
    // (optional: files in siren-schematics/ are used for some buried bases)
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

    private static List<Path> schematicFiles() {
        try (Stream<Path> s = Files.list(folder())) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".schem") || n.endsWith(".litematic") || n.endsWith(".nbt");
            }).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Loads a schematic off-thread, then places it a little each server tick, buried and hidden from the ESP. */
    private static void queueSchematic(Path f, ServerLevel level, int x, int z) {
        Thread t = new Thread(() -> {
            Schem sc;
            try {
                unknown = 0;
                sc = load(f);
            } catch (Exception e) {
                return;
            }
            if (sc.h() > 110) return;
            int by = Math.max(FLOOR_Y, TOP_Y - sc.h() + 1);
            hide(x, z, sc.w(), sc.l());
            log("Buried:  x " + x + " z " + z + " y " + by);
            jobs.add(new Job(level, f.getFileName().toString(), sc.parts(), x, by, z));
        }, "siren-larp-load");
        t.setDaemon(true);
        t.start();
    }

    private static void tick(MinecraftServer server) {
        Job j = jobs.peek();
        if (j == null) return;
        if (j.step(BLOCKS_PER_TICK)) jobs.poll();   // no chat message: it would give the larp away
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
