package dev.sirenclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sirenclient.modules.ChunkAge;
import dev.sirenclient.modules.Module;
import dev.sirenclient.modules.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

public class SirenClient implements ClientModInitializer {
    public static ModuleManager modules;
    private static KeyMapping menuKey;
    private static final Map<Module, KeyMapping> moduleKeys = new LinkedHashMap<>();

    @Override
    public void onInitializeClient() {
        Config.load();
        modules = new ModuleManager();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("sirenclient", "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sirenclient.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));

        // One unbound key per module: bind them in Controls > Siren Client.
        for (Module m : modules.all()) {
            moduleKeys.put(m, KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.sirenclient.toggle." + m.name.toLowerCase(), InputConstants.Type.KEYSYM, -1, category)));
        }

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new SirenScreen());
            }
            for (var e : moduleKeys.entrySet()) {
                while (e.getValue().consumeClick()) {
                    e.getKey().toggle();
                    if (mc.player != null)
                        mc.player.displayClientMessage(Component.literal(
                                e.getKey().name + ": " + (e.getKey().isEnabled() ? "ON" : "OFF")), true);
                }
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
        FakeHud.init();
    }
}
