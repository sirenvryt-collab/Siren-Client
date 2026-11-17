package dev.sirenclient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Singleplayer test helper: builds known bases around you so every module has something to find. */
public final class TestBases {
    public static final List<String> info = new ArrayList<>();

    private TestBases() {}

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
        info.add("Surface house A:   x " + ax + "  z " + az);
        info.add("Surface house B:   x " + bx + "  z " + bz);
        info.add("Buried room (sus): x " + cx + "  z " + cz + "  y -30");
        info.add("Decoy (bell):      x " + dx + "  z " + dz + "  y -30");

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

    /** 9x9 plank house on the surface with storage inside. */
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
        set(l, x0 + 4, y, z0, Blocks.AIR);       // doorway
        set(l, x0 + 4, y + 1, z0, Blocks.AIR);
        storage(l, x0, y, z0, 6 + extraChests);
    }

    /** 9x9x5 plank room at y -30, deep under any surface. The decoy gets a bell (village marker) inside. */
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

    /** Chests, barrels, shulker boxes and a few utility blocks along two walls. y is the floor level inside. */
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
}
