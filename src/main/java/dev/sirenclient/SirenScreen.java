package dev.sirenclient;

import dev.sirenclient.modules.ChunkScanModule;
import dev.sirenclient.modules.Hit;
import dev.sirenclient.modules.HitScanModule;
import dev.sirenclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
import java.util.*;
import java.util.function.IntConsumer;

/** Click menu: Modules / Blocks / Results / Waypoints / Scan / Visuals / Larp. Built from vanilla widgets. */
public class SirenScreen extends Screen {
    private static final int W = 440, H = 280;
    private static final int ACCENT = 0xFF2EE6A6, WHITE = 0xFFFFFFFF, GRAY = 0xFF8899AA, DIM = 0xFF5C6B78;
    private static final String[] TABS = {"Modules", "Blocks", "Results", "Waypoints", "Scan", "Visuals", "Larp", "Fake", "Shop"};
    private static final String[] LISTS = {"base", "sus", "esp"};

    private static int tab = 0, page = 0, weight = 2, colorIdx = 0, shopCat = 0, shopAmt = 64;
    private static String list = "base", pending = "", status = "";
    private static boolean refocus = false;

    private record Label(String text, int x, int y, int color) {}
    private final List<Label> labels = new ArrayList<>();
    private int x0, y0, cx, cy;

    public SirenScreen() { super(Component.literal("Siren Client")); }

    @Override public boolean isPauseScreen() { return false; }

    /** Settings are saved when the menu closes, so sliders do not write to disk while dragging. */
    @Override public void removed() { Config.save(); }

    // ---------- layout ----------
    @Override
    protected void init() {
        labels.clear();
        x0 = (this.width - W) / 2;
        y0 = (this.height - H) / 2;
        cx = x0 + 108;
        cy = y0 + 38;
        labels.add(new Label("SIREN CLIENT", x0 + 10, y0 + 9, ACCENT));
        labels.add(new Label(TABS[tab], x0 + W - 10 - this.font.width(TABS[tab]), y0 + 9, GRAY));
        labels.add(new Label("v0.4", x0 + 10, y0 + H - 11, DIM));

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal((tab == i ? "> " : "") + TABS[i]), b -> {
                tab = idx; page = 0; status = ""; rebuildWidgets();
            }).bounds(x0 + 8, y0 + 36 + i * 24, 86, 20).build());
        }
        switch (tab) {
            case 0 -> modulesTab();
            case 1 -> blocksTab();
            case 2 -> resultsTab();
            case 3 -> waypointsTab();
            case 4 -> scanTab();
            case 5 -> visualsTab();
            case 7 -> fakeTab();
            case 8 -> shopTab();
            default -> larpTab();
        }
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        Button b = Button.builder(Component.literal(text), x1 -> r.run()).bounds(x, y, w, 20).build();
        addRenderableWidget(b);
        return b;
    }

    // ---------- tab: modules ----------
    private void modulesTab() {
        int y = cy;
        for (Module m : SirenClient.modules.all()) {
            String found = "";
            if (m instanceof ChunkScanModule<?> c && m.isEnabled() && c.count() > 0) found = "   [" + c.count() + " found]";
            labels.add(new Label(m.name, cx, y + 2, WHITE));
            labels.add(new Label(m.desc + found, cx, y + 13, m.isEnabled() && !found.isEmpty() ? ACCENT : GRAY));
            Component state = Component.literal(m.isEnabled() ? "ON" : "OFF")
                    .withStyle(m.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);
            addRenderableWidget(Button.builder(state, b -> { m.toggle(); rebuildWidgets(); })
                    .bounds(cx + 244, y + 2, 72, 20).build());
            y += 32;
        }
        labels.add(new Label("Menu key and per-module toggle keys: Controls > Siren Client", cx, y + 6, DIM));
    }

    // ---------- tab: blocks ----------
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
        String what = list.equals("base") ? "BaseFinder" : list.equals("sus") ? "SusChunks (buried only)" : "BlockESP colors";
        labels.add(new Label(what, cx + 204, y + 6, GRAY));
        y += 26;

        EditBox input = new EditBox(this.font, cx, y, 150, 20, Component.literal("block"));
        input.setMaxLength(64);
        input.setHint(Component.literal("type to search or add"));
        input.setValue(pending);
        input.setResponder(s -> {
            pending = s; page = 0; refocus = true;
            Minecraft.getInstance().execute(this::rebuildWidgets); // defer: we are inside the box's own key handler
        });
        addRenderableWidget(input);
        if (refocus) { setFocused(input); refocus = false; }

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
                map.put(BuiltInRegistries.BLOCK.getValue(id), esp ? Config.COLORS[colorIdx] : weight);
                Config.save();
                status = "Added " + id;
                pending = "";
            }
            rebuildWidgets();
        });
        y += 28;

        String f = pending.trim().toLowerCase();
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>();
        for (var e : map.entrySet()) if (f.isEmpty() || Config.name(e.getKey()).contains(f)) entries.add(e);
        entries.sort(Comparator.comparing(e -> Config.name(e.getKey())));
        int per = 6, pages = Math.max(1, (entries.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = page * per; i < Math.min(entries.size(), (page + 1) * per); i++) {
            var e = entries.get(i);
            Block b = e.getKey();
            if (esp) {
                labels.add(new Label(Config.name(b), cx, y + 6, 0xFF000000 | e.getValue()));
                btn("Color", cx + 226, y, 50, () -> {
                    int idx = 0;
                    for (int c = 0; c < Config.COLORS.length; c++) if (Config.COLORS[c] == map.get(b)) idx = c;
                    map.put(b, Config.COLORS[(idx + 1) % Config.COLORS.length]);
                    Config.save(); rebuildWidgets();
                });
            } else {
                labels.add(new Label(Config.name(b) + "  x" + e.getValue(), cx, y + 6, WHITE));
                btn("-", cx + 200, y, 22, () -> { map.put(b, Math.max(1, map.get(b) - 1)); Config.save(); rebuildWidgets(); });
                btn("+", cx + 226, y, 22, () -> { map.put(b, Math.min(50, map.get(b) + 1)); Config.save(); rebuildWidgets(); });
            }
            btn("X", cx + 282, y, 20, () -> { map.remove(b); Config.save(); rebuildWidgets(); });
            y += 22;
        }
        pager(pages, entries.size() + (f.isEmpty() ? " blocks" : " matches"));
    }

    private void pager(int pages, String info) {
        int by = y0 + H - 50;
        btn("<", cx, by, 24, () -> { page = Math.max(0, page - 1); rebuildWidgets(); });
        btn(">", cx + 28, by, 24, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); });
        labels.add(new Label("Page " + (page + 1) + "/" + pages + "  (" + info + ")", cx + 58, by + 6, GRAY));
        if (!status.isEmpty()) labels.add(new Label(status, cx, by + 26, ACCENT));
    }

    // ---------- tab: results ----------
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
            labels.add(new Label(h.score() + " pts   " + dist(mc, h.x(), h.z()) + "m   " + h.x() + ", " + h.y() + ", " + h.z(), cx, y + 6, WHITE));
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

    private static int dist(Minecraft mc, int x, int z) {
        if (mc.player == null) return 0;
        double dx = x + 0.5 - mc.player.getX(), dz = z + 0.5 - mc.player.getZ();
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    // ---------- tab: waypoints ----------
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
        labels.add(new Label("Cyan boxes, beams and tracers", cx + 184, y + 6, GRAY));
        y += 28;

        int per = 6, total = Config.waypoints.size(), pages = Math.max(1, (total + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = page * per; i < Math.min(total, (page + 1) * per); i++) {
            Config.Waypoint w = Config.waypoints.get(i);
            labels.add(new Label(w.name() + "   " + w.x() + ", " + w.y() + ", " + w.z() + "   (" + dist(mc, w.x(), w.z()) + "m)", cx, y + 6, WHITE));
            btn("X", cx + 282, y, 20, () -> { Config.waypoints.remove(w); Config.save(); rebuildWidgets(); });
            y += 22;
        }
        pager(pages, total + " waypoints");
    }

    // ---------- tab: scan ----------
    private void scanTab() {
        int y = cy;
        slider(y, "BaseFinder min score", 1, 100, 1, Config.minBase, v -> Config.minBase = v);
        y += 24;
        slider(y, "SusChunks min score", 5, 300, 5, Config.minSus, v -> Config.minSus = v);
        y += 24;
        slider(y, "Sus cluster density", 1, 100, 1, Config.susDensity, v -> Config.susDensity = v);
        y += 24;
        slider(y, "Sus burial depth", 2, 32, 1, Config.susDepth, v -> Config.susDepth = v);
        y += 24;
        toggleRow(y, "Pop-up on strong finds", Config.notify, () -> Config.notify = !Config.notify);
        y += 32;
        for (String s : new String[]{
                "SusChunks counts only blocks buried at least 'depth' blocks below the",
                "surface (overworld), needs a dense cluster, and skips natural structures",
                "(villages, mineshafts, strongholds, ancient cities, nether and end).",
                "Raise min score or density for fewer results, lower for more."}) {
            labels.add(new Label(s, cx, y, GRAY));
            y += 11;
        }
    }

    // ---------- tab: visuals ----------
    private void visualsTab() {
        int y = cy;
        slider(y, "ESP opacity %", 10, 100, 10, Config.espAlpha, v -> Config.espAlpha = v);
        y += 24;
        slider(y, "ESP distance", 32, 512, 32, Config.espDist, v -> Config.espDist = v);
        y += 24;
        colorRow(y, "BaseFinder color", Config.baseColorIdx, () -> Config.baseColorIdx = (Config.baseColorIdx + 1) % Config.COLORS.length);
        y += 24;
        colorRow(y, "SusChunks color", Config.susColorIdx, () -> Config.susColorIdx = (Config.susColorIdx + 1) % Config.COLORS.length);
        y += 24;
        toggleRow(y, "Beams above hits", Config.beams, () -> Config.beams = !Config.beams);
        y += 24;
        toggleRow(y, "Chunk plates on hits", Config.plates, () -> Config.plates = !Config.plates);
        y += 24;
        toggleRow(y, "Tracers", Config.tracers, () -> Config.tracers = !Config.tracers);
        y += 28;
        labels.add(new Label("Tracers: all base/sus hits, nearest 16 BlockESP blocks, players, waypoints.", cx, y, GRAY));
    }

    // ---------- tab: larp ----------
    private void larpTab() {
        int y = cy;
        toggleRow(y, "Larp mode", TestBases.isLarp(), () -> status = TestBases.setLarp(!TestBases.isLarp()));
        y += 24;
        slider(y, "Avg seconds gliding between bases", 30, 300, 10, TestBases.avgSeconds, v -> TestBases.avgSeconds = v);
        y += 26;
        btn("Scanners on", cx, y, 96, () -> {
            Module[] mods = {SirenClient.modules.base, SirenClient.modules.sus,
                    SirenClient.modules.newChunks, SirenClient.modules.blockEsp};
            for (Module m : mods) if (!m.isEnabled()) m.toggle();
            status = "BaseFinder, SusChunks, NewChunks, BlockESP are on.";
            rebuildWidgets();
        });
        btn("Get gear", cx + 100, y, 100, () -> { status = TestBases.gear(); rebuildWidgets(); });
        btn("Spawn one now", cx + 204, y, 112, () -> { status = TestBases.spawnNow(); rebuildWidgets(); });
        y += 24;
        btn("Spawner cages now", cx, y, 130, () -> { status = TestBases.spawnSpawnersNow(); rebuildWidgets(); });
        y += 24;
        toggleRow(y, "Dig-down beacons (yellow lines)", TestBases.showDigBeacons, () -> TestBases.showDigBeacons = !TestBases.showDigBeacons);
        y += 28;
        TestBases.folder(); // makes sure siren-schematics/ exists (optional, used for some buried bases)
        labels.add(new Label("Singleplayer survival only. Bases appear ahead while you glide.", cx, y, GRAY));
        y += 11;
        labels.add(new Label("Buried bases + spawner cages stay out of the ESP until you go below Y 0 and relog.", cx, y, GRAY));
        y += 15;
        int hid = TestBases.hiddenCount();
        labels.add(new Label("Hidden bases waiting: " + hid + (hid > 0 ? (TestBases.isMinedDown() ? "   (mined down, relog now)" : "   (mine below Y 0, then relog)") : ""),
                cx, y, hid > 0 ? ACCENT : GRAY));
        y += 14;
        int shown = 0;
        for (String s : TestBases.info) {
            if (shown++ >= 6) break;
            labels.add(new Label(s, cx, y, WHITE));
            y += 11;
        }
        if (!status.isEmpty()) labels.add(new Label(status, cx, y0 + H - 30, ACCENT));
    }

    // ---------- tab: fake shop (everything is free) ----------
    private void shopTab() {
        int y = cy;
        labels.add(new Label("SHOP   every price is $0.00", cx, y + 6, ACCENT));
        btn("Amount: " + shopAmt, cx + 244, y, 72, () -> {
            shopAmt = shopAmt == 1 ? 16 : shopAmt == 16 ? 64 : 1; rebuildWidgets();
        });
        y += 24;
        int cw = 62;
        for (int i = 0; i < Shop.CATS.length; i++) {
            final int idx = i;
            btn((shopCat == i ? "> " : "") + Shop.CATS[i], cx + i * (cw + 1), y, cw, () -> { shopCat = idx; status = ""; rebuildWidgets(); });
        }
        y += 26;
        var items = Shop.ITEMS[shopCat];
        for (int i = 0; i < items.length; i++) {
            final var item = items[i];
            String name = item == null ? "Amethyst Pickaxe" : new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            btn(name, cx + (i % 3) * 107, y + (i / 3) * 22, 105, () -> {
                status = item == null ? TestBases.shopPick() : TestBases.shopGive(item, shopAmt);
                rebuildWidgets();
            });
        }
        if (!status.isEmpty()) labels.add(new Label(status, cx, y0 + H - 30, ACCENT));
    }

    // ---------- tab: fake client cosmetics ----------
    private void fakeTab() {
        int y = cy;
        toggleRow(y, "Fake tab list + ping", FakeHud.tabList, () -> FakeHud.tabList = !FakeHud.tabList);
        y += 24;
        toggleRow(y, "Module list (top right)", FakeHud.moduleList, () -> FakeHud.moduleList = !FakeHud.moduleList);
        y += 24;
        toggleRow(y, "ESP text labels", FakeHud.espLabels, () -> FakeHud.espLabels = !FakeHud.espLabels);
        y += 24;
        toggleRow(y, "Fake scoreboard", FakeHud.scoreboard, () -> FakeHud.scoreboard = !FakeHud.scoreboard);
        y += 24;
        toggleRow(y, "Rubberband when you dig down", FakeHud.rubberband, () -> FakeHud.rubberband = !FakeHud.rubberband);
        y += 24;
        toggleRow(y, "Lag spike on relog", FakeHud.lagSpike, () -> FakeHud.lagSpike = !FakeHud.lagSpike);
        y += 30;
        labels.add(new Label("These only show while Larp mode is on (the find pop-up always works).", cx, y, GRAY));
        y += 11;
        labels.add(new Label("Hold the tab-list key (Tab) to see the fake player list.", cx, y, GRAY));
    }

    private void toggleRow(int y, String text, boolean on, Runnable flip) {
        labels.add(new Label(text, cx, y + 6, WHITE));
        addRenderableWidget(Button.builder(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED),
                b -> { flip.run(); rebuildWidgets(); })
                .bounds(cx + 244, y, 72, 20).build());
    }

    private void colorRow(int y, String text, int idx, Runnable next) {
        labels.add(new Label(text + ": " + Config.COLOR_NAMES[idx], cx, y + 6, 0xFF000000 | Config.COLORS[idx]));
        btn("Change", cx + 244, y, 72, () -> { next.run(); rebuildWidgets(); });
    }

    private void slider(int y, String label, int min, int max, int step, int cur, IntConsumer setter) {
        addRenderableWidget(new Slider(cx, y, 316, label, min, max, step, cur, setter));
    }

    /** Integer slider with a live label; the value is applied immediately and saved when the menu closes. */
    private static class Slider extends AbstractSliderButton {
        private final String label;
        private final int min, max, step;
        private final IntConsumer setter;

        Slider(int x, int y, int w, String label, int min, int max, int step, int cur, IntConsumer setter) {
            super(x, y, w, 20, Component.empty(), (cur - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            updateMessage();
        }

        private int val() {
            int v = min + (int) Math.round(this.value * (max - min));
            v = Math.round(v / (float) step) * step;
            return Math.max(min, Math.min(max, v));
        }

        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + val())); }

        @Override protected void applyValue() { setter.accept(val()); }
    }

    // ---------- drawing ----------
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0x99000000);
        g.fill(x0, y0, x0 + W, y0 + H, 0xF0101418);
        g.fill(x0, y0, x0 + W, y0 + 26, 0xFF16241F);
        g.fill(x0, y0 + 26, x0 + W, y0 + 28, ACCENT);
        g.fill(x0, y0 + 28, x0 + 100, y0 + H, 0xF00B0F12);
        g.fill(x0 + 100, y0 + 28, x0 + 101, y0 + H, 0xFF1E2A33);
        g.fill(x0, y0 + H - 14, x0 + W, y0 + H, 0xFF0B0F12);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        for (Label l : labels) g.drawString(this.font, l.text(), l.x(), l.y(), l.color(), true);
    }
}
