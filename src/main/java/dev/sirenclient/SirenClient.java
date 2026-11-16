package dev.sirenclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sirenclient.modules.ChunkAge;
import dev.sirenclient.modules.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class SirenClient implements ClientModInitializer {
    public static ModuleManager modules;
    private static KeyMapping menuKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        modules = new ModuleManager();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("sirenclient", "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sirenclient.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new SirenScreen());
            }
            modules.tick();
        });

        // Classify chunk age as chunks arrive (only while SusChunks or NewChunks is on).
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (modules.sus.isEnabled() || modules.newChunks.isEnabled()) ChunkAge.classify(chunk);
        });

        // New server / dimension: old coordinates are meaningless, drop them.
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> modules.clearAll());

        EspRenderer.init();
    }
}
