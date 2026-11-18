package dev.sirenclient;

import dev.sirenclient.modules.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Cosmetic HUD for larp mode (everything except the pop-up only draws while larp mode is on):
 * a hacked-client style module list, a fake tab list with pings, an optional fake scoreboard,
 * ESP text labels, a "scanning chunks" bar during the staggered reveal, and the find pop-up.
 */
public final class FakeHud {
    // ---- menu toggles (Fake tab) ----
    public static boolean tabList = true, moduleList = true, espLabels = true;
    public static boolean scoreboard = false;       // optional
    public static boolean rubberband = true, lagSpike = true;
    public static boolean cameraNoClip = true;      // free-look camera passes through walls (needs the camera mixin)

    /** World-space labels, refilled every frame by EspRenderer. */
    public record Lbl(double x, double y, double z, String text) {}
    public static final List<Lbl> labels = new ArrayList<>();

    private static final int ACCENT = 0xFF2EE6A6;

    private FakeHud() {}

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sirenclient", "fake_hud"), (HudElement) FakeHud::render);
    }

    public static void addLabel(double x, double y, double z, String text) {
        if (labels.size() < 400) labels.add(new Lbl(x, y, z, text));
    }

    // =====================================================================
    // Names + ping
    // =====================================================================
    private static final String[] PRE = {"Dark", "Void", "Shadow", "Frost", "Pixel", "Ender", "Toxic", "Nova", "Crimson",
            "Lunar", "Blaze", "Iron", "Mystic", "Turbo", "Silent", "Rapid", "Cosmic", "Savage", "Neo", "Ghost", "Alpha", "Sky"};
    private static final String[] SUF = {"Miner", "Wolf", "Slayer", "Knight", "PvP", "Gamer", "Hunter", "Fox", "Raider",
            "King", "Craft", "Storm", "Pro", "Zero", "Bee", "Tank", "Boy", "Girl", "Lord", "Z"};

    public static String randomName(Random r) {
        String p = PRE[r.nextInt(PRE.length)], s = SUF[r.nextInt(SUF.length)];
        String n = switch (r.nextInt(6)) {
            case 0 -> p + s;
            case 1 -> p + s + (10 + r.nextInt(90));
            case 2 -> p.toLowerCase() + "_" + s;
            case 3 -> p + "_" + s + r.nextInt(10);
            case 4 -> "xX" + p + "Xx";
            default -> s + (r.nextInt(9000) + 100);
        };
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    /** A believable, slowly wandering ping (40-90 ms) instead of the 0 ms of an integrated server. */
    public static int fakePing() {
        long t = System.currentTimeMillis() / 500 * 500;
        return (int) (62 + 12 * Math.sin(t / 2300.0) + 7 * Math.sin(t / 410.0));
    }

    private static List<String> tabNames;
    private static String tabFor = "";
    private static int[] tabPings;

    private static void buildTab(String self) {
        Random r = new Random(self.hashCode() * 31L + 7);
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.add(self);
        while (set.size() < 64) set.add(randomName(r));
        tabNames = new ArrayList<>(set);
        tabPings = new int[tabNames.size()];
        for (int i = 0; i < tabPings.length; i++) tabPings[i] = 25 + r.nextInt(r.nextInt(5) == 0 ? 260 : 110);
        tabFor = self;
    }

    // =====================================================================
    // Pop-up (replaces the chat ping)
    // =====================================================================
    private static String toastTitle = "", toastLine = "";
    private static long toastAt = 0;

    public static void toast(String title, String line) {
        toastTitle = title;
        toastLine = line;
        toastAt = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    }

    // =====================================================================
    // Drawing
    // =====================================================================
    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        drawToast(g, mc);
        if (!TestBases.isLarp()) return;
        if (moduleList) drawModules(g, mc);
        if (espLabels) drawLabels(g, mc);
        if (scoreboard) drawScoreboard(g, mc);
        drawScan(g, mc);
        if (tabList && mc.options.keyPlayerList.isDown()) drawTab(g, mc);
    }

    private static void drawToast(GuiGraphics g, Minecraft mc) {
        long e = System.currentTimeMillis() - toastAt;
        if (toastAt == 0 || e > 4600) return;
        int w = Math.max(mc.font.width(toastTitle), mc.font.width(toastLine)) + 16, h = 28;
        double p = e < 300 ? e / 300.0 : e > 4300 ? (4600 - e) / 300.0 : 1.0;
        int x = (int) (mc.getWindow().getGuiScaledWidth() - w - 6 + (1 - p) * (w + 10));
        int y = mc.getWindow().getGuiScaledHeight() - 72;
        g.fill(x, y, x + w, y + h, 0xE0101418);
        g.fill(x, y, x + 2, y + h, ACCENT);
        g.drawString(mc.font, toastTitle, x + 8, y + 5, ACCENT, true);
        g.drawString(mc.font, toastLine, x + 8, y + 16, 0xFFFFFFFF, true);
    }

    private static void drawModules(GuiGraphics g, Minecraft mc) {
        if (SirenClient.modules == null) return;
        List<String> names = new ArrayList<>();
        for (Module m : SirenClient.modules.all()) if (m.isEnabled()) names.add(m.name);
        if (names.isEmpty()) return;
        names.sort((a, b) -> Integer.compare(mc.font.width(b), mc.font.width(a)));
        int W = mc.getWindow().getGuiScaledWidth();
        int y = 3;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            int tw = mc.font.width(n);
            int color = 0xFF000000 | Mth.hsvToRgb((i * 0.07f + (System.currentTimeMillis() % 6000) / 6000f) % 1f, 0.55f, 1f);
            g.fill(W - tw - 6, y - 1, W - 2, y + 10, 0x70000000);
            g.fill(W - 2, y - 1, W, y + 10, color);
            g.drawString(mc.font, n, W - tw - 4, y + 1, color, true);
            y += 11;
        }
    }

    private static void drawScoreboard(GuiGraphics g, Minecraft mc) {
        long mins = 862 + (System.currentTimeMillis() - START) / 60000;
        String[] lines = {
                "Money: $" + money(mc),
                "Shards: " + (200 + Math.abs(mc.player.getName().getString().hashCode()) % 400),
                "Kills: " + (4 + Math.abs(mc.player.getName().getString().hashCode()) % 30),
                "Deaths: " + (1 + Math.abs(mc.player.getName().getString().hashCode()) % 9),
                "Playtime: " + (mins / 60) + "h " + (mins % 60) + "m",
                "Ping: " + fakePing() + "ms",
                "",
                "donutsmp.net"};
        int w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        w += 10;
        String title = "DONUTSMP";
        w = Math.max(w, mc.font.width(title) + 10);
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        int h = (lines.length + 1) * 10 + 4;
        int x = W - w - 2, y = H / 2 - h / 2;
        g.fill(x, y, x + w, y + 11, 0x80000000);
        g.fill(x, y + 11, x + w, y + h, 0x50000000);
        g.drawString(mc.font, title, x + (w - mc.font.width(title)) / 2, y + 2, 0xFFFFAA00, true);
        int ly = y + 14;
        for (String l : lines) {
            g.drawString(mc.font, l, x + 4, ly, l.equals("donutsmp.net") ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            ly += 10;
        }
    }

    private static final long START = System.currentTimeMillis();

    private static String money(Minecraft mc) {
        long m = 480_000 + Math.abs(mc.player.getName().getString().hashCode()) % 2_000_000L;
        return m >= 1_000_000 ? String.format(Locale.ROOT, "%.2fM", m / 1_000_000.0) : (m / 1000) + "K";
    }

    private static void drawScan(GuiGraphics g, Minecraft mc) {
        if (!TestBases.isRevealing()) return;
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        int done = TestBases.revealDone(), total = Math.max(1, TestBases.revealTotal());
        String s = "Scanning chunks... " + done + "/" + total;
        int tw = mc.font.width(s), x = (W - tw) / 2, y = H / 2 + 42;
        g.drawString(mc.font, s, x, y, ACCENT, true);
        g.fill(x, y + 11, x + tw, y + 13, 0x80000000);
        g.fill(x, y + 11, x + (int) (tw * (done / (double) total)), y + 13, ACCENT);
    }

    private static void drawLabels(GuiGraphics g, Minecraft mc) {
        if (labels.isEmpty()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = mc.player.getEyePosition(pt);
        double yaw = Math.toRadians(mc.player.getViewYRot(pt)), pitch = Math.toRadians(mc.player.getViewXRot(pt));
        double cp = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cp, fy = -Math.sin(pitch), fz = Math.cos(yaw) * cp;   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);                                    // right
        double ux = -fy * rz, uy = rz * fx - rx * fz, uz = fy * rx;                          // up = right x forward
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0), aspect = W / (double) H;

        List<double[]> pts = new ArrayList<>();
        List<String> txt = new ArrayList<>();
        for (Lbl l : labels) {
            double dx = l.x() - eye.x, dy = l.y() - eye.y, dz = l.z() - eye.z;
            double fwd = dx * fx + dy * fy + dz * fz;
            if (fwd < 0.5) continue;
            double sx = W / 2.0 + ((dx * rx + dz * rz) / fwd) / (tanHalf * aspect) * (W / 2.0);
            double sy = H / 2.0 - ((dx * ux + dy * uy + dz * uz) / fwd) / tanHalf * (H / 2.0);
            if (sx < 10 || sx > W - 10 || sy < 10 || sy > H - 10) continue;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            pts.add(new double[]{sx, sy, dist});
            txt.add(l.text() + "  " + (int) dist + "m");
        }
        Integer[] order = new Integer[pts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> pts.get(i)[2]));
        for (int k = 0; k < Math.min(18, order.length); k++) {
            int i = order[k];
            String s = txt.get(i);
            int tw = mc.font.width(s), x = (int) pts.get(i)[0] - tw / 2, y = (int) pts.get(i)[1];
            g.fill(x - 2, y - 1, x + tw + 2, y + 9, 0x90000000);
            g.drawString(mc.font, s, x, y, 0xFFFFFFFF, true);
        }
    }

    private static void drawTab(GuiGraphics g, Minecraft mc) {
        String self = mc.player.getName().getString();
        if (tabNames == null || !self.equals(tabFor)) buildTab(self);
        int n = 46 + (int) ((mc.level.getGameTime() / 400) % 13);        // the count drifts as "players" come and go
        n = Math.min(n, tabNames.size());
        List<Integer> shown = new ArrayList<>();
        int selfIdx = tabNames.indexOf(self);
        for (int i = 0; i < tabNames.size() && shown.size() < n; i++) shown.add(i);
        if (!shown.contains(selfIdx)) { shown.remove(shown.size() - 1); shown.add(selfIdx); Collections.sort(shown); }

        int colW = 0;
        for (int i : shown) colW = Math.max(colW, mc.font.width(tabNames.get(i)));
        colW += 22;
        int rows = Math.min(20, shown.size()), cols = (shown.size() + rows - 1) / rows;
        int W = mc.getWindow().getGuiScaledWidth();
        int pw = cols * colW + 4, x0 = (W - pw) / 2, y0 = 8;
        g.fill(x0, y0, x0 + pw, y0 + rows * 10 + 4, 0xC0000000);
        for (int k = 0; k < shown.size(); k++) {
            int i = shown.get(k);
            int cx = x0 + 2 + (k / rows) * colW, cy = y0 + 2 + (k % rows) * 10;
            g.fill(cx, cy, cx + colW - 2, cy + 9, 0x20FFFFFF);
            g.drawString(mc.font, tabNames.get(i), cx + 2, cy + 1, 0xFFFFFFFF, true);
            bars(g, cx + colW - 13, cy, i == selfIdx ? fakePing() : tabPings[i]);
        }
    }

    private static void bars(GuiGraphics g, int x, int y, int ping) {
        int on = ping < 150 ? 5 : ping < 300 ? 4 : 3;
        for (int i = 0; i < 5; i++) {
            int h = 2 + i;
            g.fill(x + i * 2, y + 8 - h, x + i * 2 + 1, y + 8, i < on ? 0xFF55FF55 : 0xFF404040);
        }
    }
}
