package dev.sirenclient;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Item lists for the fake shop tab. Everything is free; null in an item array means "Amethyst Pickaxe". */
public final class Shop {
    private Shop() {}

    public static final String[] CATS = {"Combat", "Explosives", "Tools", "Food", "Blocks"};

    public static final Item[][] ITEMS = {
            // Combat
            {Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.OBSIDIAN, Items.RESPAWN_ANCHOR, Items.GLOWSTONE,
                    Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE,
                    Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW, Items.NETHERITE_SWORD, Items.NETHERITE_AXE,
                    Items.MACE, Items.WIND_CHARGE, Items.COBWEB},
            // Explosives + fire
            {Items.TNT, Items.FLINT_AND_STEEL, Items.FIREWORK_ROCKET, Items.FIRE_CHARGE, Items.GUNPOWDER,
                    Items.TNT_MINECART, Items.LAVA_BUCKET, Items.WATER_BUCKET, Items.REDSTONE, Items.REPEATER,
                    Items.OBSERVER, Items.PISTON, Items.STICKY_PISTON, Items.SLIME_BLOCK, Items.DISPENSER,
                    Items.HOPPER, Items.LEVER, Items.STONE_BUTTON},
            // Tools
            {null, Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.DIAMOND_PICKAXE, Items.ELYTRA,
                    Items.SHEARS, Items.FISHING_ROD, Items.COMPASS, Items.CLOCK, Items.BUCKET, Items.TORCH,
                    Items.LADDER, Items.CHEST, Items.ENDER_CHEST, Items.SHULKER_BOX, Items.BARREL,
                    Items.SPYGLASS, Items.NAME_TAG},
            // Food
            {Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.BREAD, Items.BAKED_POTATO,
                    Items.GOLDEN_CARROT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.APPLE,
                    Items.CARROT, Items.PUMPKIN_PIE, Items.COOKIE},
            // Blocks
            {Items.OBSIDIAN, Items.COBBLESTONE, Items.STONE, Items.DEEPSLATE, Items.OAK_PLANKS, Items.DIRT,
                    Items.SAND, Items.GLASS, Items.NETHERRACK, Items.END_STONE, Items.SCAFFOLDING, Items.COBBLED_DEEPSLATE,
                    Items.IRON_BLOCK, Items.DIAMOND_BLOCK, Items.NETHERITE_BLOCK, Items.ANVIL, Items.CRAFTING_TABLE,
                    Items.FURNACE}
    };
}
