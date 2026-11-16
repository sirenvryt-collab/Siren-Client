package dev.sirenclient;

import dev.sirenclient.modules.Hit;
import dev.sirenclient.modules.HitScanModule;
import dev.sirenclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
import java.util.*;

/** Click menu: Modules / Blocks / Results / Waypoints / Settings. Built from vanilla widgets. */
public class SirenScreen extends Screen {
    private static final int W = 440, H = 280;
    private static final int ACCENT = 0xFF2EE6A6, WHITE = 0xFFFFFFFF, GRAY = 0xFF8899AA;
    private static final String[] TABS = {"Modules", "Blocks", "Results", "Waypoints", "Settings"};
    private static final String[] LISTS = {"base", "sus", "esp"};

    private static int tab = 0, page = 0, weight = 2, colorIdx = 0;
    private static String list = "base", pending = "", status = "";

    private record Label(String text, int x, int y, int color) {}
    private final List<Label> labels = new ArrayList<>();
    private int x0, y0, cx, cy;

    public SirenScreen() { super(Component.literal("Siren Client")); }

    @Override public boolean isPauseScreen() { return false; }

    // ---------- layout ----------
    @Override
    protected void init() {
        labels.clear();
        x0 = (this.width - W) / 2;
        y0 = (this.height - H) / 2;
        cx = x0 + 104;
        cy = y0 + 36;
        labels.add(new Label("SIREN CLIENT", x0 + 10, y0 + 8, ACCENT));

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal((tab == i ? "> " : "") + TABS[i]), b -> {
                tab = idx; page = 0; status = ""; rebuildWidgets();
            }).bounds(x0 + 8, y0 + 36 + i * 24, 84, 20).build());
        }
        switch (tab) {
            case 0 -> modulesTab();
            case 1 -> blocksTab();
            case 2 -> resultsTab();
            case 3 -> waypointsTab();
            default -> settingsTab();
        }
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        Button b = Button.builder(Component.literal(text), x1 -> r.run()).bounds(x, y, w, 20).build();
        addRenderableWidget(b);
        return b;
    }

    private void modulesTab() {
        int y = cy;
        for (Module m : SirenClient.modules.all()) {
            labels.add(new Label(m.name, cx, y + 2, WHITE));
            labels.add(new Label(m.desc, cx, y + 13, GRAY));
            Component state = Component.literal(m.isEnabled() ? "ON" : "OFF")
                    .withStyle(m.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);
            addRenderableWidget(Button.builder(state, b -> { m.toggle(); rebuildWidgets(); })
                    .bounds(cx + 240, y + 2, 76, 20).build());
            y += 32;
        }
        labels.add(new Label("Menu key: rebind in Controls > Siren Client", cx, y + 6, GRAY));
    }

    private void blocksTab() {
        Map<Block, Integer> map = Config.lists.get(list);
        boolean esp = list.equals("esp");
        int y = cy;

        btn("List: " + list.toUpperCase(), cx, y, 110, () -> {
            list = LISTS[(Arrays.asList(LISTS).indexOf(list) + 1) % LISTS.length];
            page = 0; status = ""; rebuildWidgets();
        });
        btn("Reset list", cx + 116, y, 80, () -> {
            Config.resetList(list); Config.save(); page = 0; status = "List reset to defaults."; rebuildWidgets();
        });
        labels.add(new Label(esp ? "ESP colors" : "Block weights", cx + 204, y + 6, GRAY));
        y += 26;

        EditBox input = new EditBox(this.font, cx, y, 150, 20, Component.literal("block"));
        input.setMaxLength(64);
        input.setHint(Component.literal("block, e.g. chest"));
        input.setValue(pending);
        input.setResponder(s -> pending = s);
        addRenderableWidget(input);

        if (esp) {
            btn("Color: " + Config.COLOR_NAMES[colorIdx], cx + 156, y, 90, () -> {
                colorIdx = (colorIdx + 1) % Config.COLORS.length; rebuildWidgets();
            });
        } else {
            btn("Weight: " + weight, cx + 156, y, 90, () -> { weight = weight % 10 + 1; rebuildWidgets(); });
        }
        btn("Add", cx + 252, y, 50, () -> {
            Identifier id = Identifier.tryParse(pending.trim().toLowerCase());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                status = "Unknown block. Modded: mod:block";
            } else {
                Block b = BuiltInRegistries.BLOCK.getValue(id);
                map.put(b, esp ? Config.COLORS[colorIdx] : weight);
                Config.save();
                status = "Added " + id;
                pending = "";
            }
            rebuildWidgets();
        });
        y += 28;

        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(e -> Config.name(e.getKey())));
        int per = 6, pages = Math.max(1, (entries.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = page * per; i < Math.min(entries.size(), (page + 1) * per); i++) {
            var e = entries.get(i);
            String val = esp ? "" : "  (weight " + e.getValue() + ")";
            labels.add(new Label(Config.name(e.getKey()) + val, cx, y + 6, esp ? (0xFF000000 | e.getValue()) : WHITE));
            Block b = e.getKey();
            btn("X", cx + 282, y, 20, () -> { map.remove(b); Config.save(); rebuildWidgets(); });
            y += 22;
        }
        pager(pages, entries.size() + " blocks");
    }

    private void pager(int pages, String info) {
        int by = y0 + H - 50;
        btn("<", cx, by, 24, () -> { page = Math.max(0, page - 1); rebuildWidgets(); });
        btn(">", cx + 28, by, 24, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); });
        labels.add(new Label("Page " + (page + 1) + "/" + pages + "  (" + info + ")", cx + 58, by + 6, GRAY));
        if (!status.isEmpty()) labels.add(new Label(status, cx, by + 26, ACCENT));
    }

    private void resultsTab() {
        Minecraft mc = Minecraft.getInstance();
        int y = cy;
        y = resultBlock("BaseFinder", SirenClient.modules.base, y, mc);
        y = resultBlock("SusChunks", SirenClient.modules.sus, y + 4, mc);
        labels.add(new Label("BlockESP: " + SirenClient.modules.blockEsp.count() + " chunks with matches", cx, y + 4, WHITE));
        int by = y0 + H - 50;
        btn("Refresh", cx, by, 70, this::rebuildWidgets);
        btn("Export", cx + 76, by, 70, () -> {
            try {
                Path dir = Minecraft.getInstance().gameDirectory.toPath();
                SirenClient.modules.base.export(dir.resolve("siren-bases.txt"));
                SirenClient.modules.sus.export(dir.resolve("siren-sus.txt"));
                status = "Wrote siren-bases.txt / siren-sus.txt";
            } catch (Exception e) { status = "Export failed: " + e.getMessage(); }
            rebuildWidgets();
        });
        btn("Clear", cx + 152, by, 70, () -> { SirenClient.modules.clearAll(); status = "Cleared."; rebuildWidgets(); });
        if (!status.isEmpty()) labels.add(new Label(status, cx, by + 26, ACCENT));
    }

    private int resultBlock(String title, HitScanModule m, int y, Minecraft mc) {
        labels.add(new Label(title + (m.isEnabled() ? "" : " (off)") + " - " + m.count() + " chunks", cx, y, ACCENT));
        y += 12;
        List<Hit> top = m.ranked(3);
        if (top.isEmpty()) { labels.add(new Label("none yet", cx, y, GRAY)); return y + 12; }
        for (Hit h : top) {
            int dist = 0;
            if (mc.player != null) {
                double dx = h.x() + 0.5 - mc.player.getX(), dz = h.z() + 0.5 - mc.player.getZ();
                dist = (int) Math.sqrt(dx * dx + dz * dz);
            }
            labels.add(new Label(h.score() + " pts   " + dist + "m   " + h.x() + ", " + h.y() + ", " + h.z(), cx, y + 6, WHITE));
            btn("WP", cx + 270, y, 32, () -> {
                Config.waypoints.add(new Config.Waypoint("WP " + (Config.waypoints.size() + 1), h.x(), h.y(), h.z()));
                Config.save();
                status = "Saved as waypoint.";
                rebuildWidgets();
            });
            y += 22;
        }
        return y;
    }

    private void waypointsTab() {
        Minecraft mc = Minecraft.getInstance();
        int y = cy;
        btn("Add here", cx, y, 90, () -> {
            if (mc.player != null) {
                Config.waypoints.add(new Config.Waypoint("WP " + (Config.waypoints.size() + 1),
                        mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()));
                Config.save();
                status = "Waypoint added at your position.";
            }
            rebuildWidgets();
        });
        btn("Clear all", cx + 96, y, 80, () -> { Config.waypoints.clear(); Config.save(); page = 0; rebuildWidgets(); });
        labels.add(new Label("Shown as cyan boxes + beams", cx + 184, y + 6, GRAY));
        y += 28;

        int per = 6, total = Config.waypoints.size(), pages = Math.max(1, (total + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = page * per; i < Math.min(total, (page + 1) * per); i++) {
            Config.Waypoint w = Config.waypoints.get(i);
            int dist = 0;
            if (mc.player != null) {
                double dx = w.x() + 0.5 - mc.player.getX(), dz = w.z() + 0.5 - mc.player.getZ();
                dist = (int) Math.sqrt(dx * dx + dz * dz);
            }
            labels.add(new Label(w.name() + "   " + w.x() + ", " + w.y() + ", " + w.z() + "   (" + dist + "m)", cx, y + 6, WHITE));
            btn("X", cx + 282, y, 20, () -> { Config.waypoints.remove(w); Config.save(); rebuildWidgets(); });
            y += 22;
        }
        pager(pages, total + " waypoints");
    }

    private void settingsTab() {
        int y = cy;
        step(y, "Base min score: " + Config.minBase, () -> Config.minBase = Math.max(1, Config.minBase - 1), () -> Config.minBase++);
        y += 26;
        step(y, "Sus min score: " + Config.minSus, () -> Config.minSus = Math.max(1, Config.minSus - 1), () -> Config.minSus++);
        y += 26;
        step(y, "ESP opacity: " + Config.espAlpha + "%", () -> Config.espAlpha = Math.max(10, Config.espAlpha - 10),
                () -> Config.espAlpha = Math.min(100, Config.espAlpha + 10));
        y += 26;
        step(y, "ESP distance: " + Config.espDist + " blocks", () -> Config.espDist = Math.max(32, Config.espDist - 32),
                () -> Config.espDist = Math.min(512, Config.espDist + 32));
        y += 26;
        toggleRow(y, "Beams above hits", Config.beams, () -> Config.beams = !Config.beams);
        y += 26;
        toggleRow(y, "Chunk plates on hits", Config.plates, () -> Config.plates = !Config.plates);
        y += 26;
        toggleRow(y, "Chat ping on strong finds", Config.notify, () -> Config.notify = !Config.notify);
        y += 30;
        labels.add(new Label("Higher min score = fewer, stronger results.", cx, y, GRAY));
    }

    private void step(int y, String text, Runnable minus, Runnable plus) {
        labels.add(new Label(text, cx, y + 6, WHITE));
        btn("-", cx + 240, y, 26, () -> { minus.run(); Config.save(); rebuildWidgets(); });
        btn("+", cx + 270, y, 26, () -> { plus.run(); Config.save(); rebuildWidgets(); });
    }

    private void toggleRow(int y, String text, boolean on, Runnable flip) {
        labels.add(new Label(text, cx, y + 6, WHITE));
        addRenderableWidget(Button.builder(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED),
                b -> { flip.run(); Config.save(); rebuildWidgets(); })
                .bounds(cx + 240, y, 56, 20).build());
    }

    // ---------- drawing ----------
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0x99000000);
        g.fill(x0, y0, x0 + W, y0 + H, 0xF0101418);
        g.fill(x0, y0, x0 + W, y0 + 26, 0xFF16241F);
        g.fill(x0, y0 + 26, x0 + W, y0 + 28, ACCENT);
        g.fill(x0, y0 + 28, x0 + 100, y0 + H, 0xF00B0F12);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        for (Label l : labels) g.drawString(this.font, l.text(), l.x(), l.y(), l.color(), true);
    }
}
